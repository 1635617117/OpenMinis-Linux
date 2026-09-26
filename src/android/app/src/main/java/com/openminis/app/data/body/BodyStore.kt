package com.openminis.app.data.body

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Durable body files. A killed write leaves only a temp file, which the next
 * open deletes. The SQLite row is updated only after the rename succeeds.
 *
 * File layout: magic OMB1, uncompressed size (int), compressed size (int),
 * codec byte, payload. Declared uncompressed size above the cap is refused
 * before inflate.
 */
open class BodyStore(private val root: File) {
    init {
        discardTemps()
    }
    data class Put(val ok: Boolean, val ref: String? = null, val sha: String? = null, val error: String? = null)

    fun put(bytes: ByteArray): Put {
        if (bytes.size > ResourceLimits.MAX_DECLARED_UNCOMPRESSED) {
            return Put(ok = false, error = "declared size ${bytes.size} exceeds cap")
        }
        if (!root.mkdirs() && !root.isDirectory) {
            return Put(ok = false, error = "body directory unavailable")
        }
        if (!Admission.tryAdmit(bytes.size.toLong())) {
            return Put(ok = false, error = "admission refused")
        }
        try {
            val sha = sha256(bytes)
            val dest = File(root, sha)
            if (dest.isFile && dest.length() > HEADER) return Put(ok = true, ref = sha, sha = sha)
            val compressed = deflate(bytes)
            val rawCodec = compressed.isEmpty() ||
                bytes.size.toLong() > compressed.size.toLong() * ResourceLimits.MAX_EXPANSION_RATIO
            val codec = if (rawCodec) CODEC_RAW else CODEC_DEFLATE
            val payload = if (rawCodec) bytes else compressed
            val tmp = File(root, "$sha.tmp")
            return try {
                writeAtomic(tmp, MAGIC + intBytes(bytes.size) + intBytes(payload.size) + byteArrayOf(codec.toByte()) + payload)
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                Put(ok = true, ref = sha, sha = sha)
            } catch (e: Exception) {
                tmp.delete()
                Put(ok = false, error = e.javaClass.simpleName)
            }
        } finally {
            Admission.release(bytes.size.toLong())
        }
    }

    fun read(ref: String, maxBytes: Int = ResourceLimits.SQL_CELL_BYTES): ByteArray? {
        if (ref.length != 64 || ref.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val file = File(root, ref)
        if (!file.isFile) return null
        val raw = readBounded(file) ?: return null
        if (raw.size < HEADER) return null
        if (!raw.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val uncompressed = intAt(raw, 4)
        val stored = intAt(raw, 8)
        val codec = raw[12].toInt() and 0xff
        if (uncompressed < 0 || uncompressed > ResourceLimits.MAX_DECLARED_UNCOMPRESSED) return null
        if (stored < 0 || HEADER + stored > raw.size) return null
        val payload = raw.copyOfRange(HEADER, HEADER + stored)
        val body = when (codec) {
            CODEC_RAW -> if (uncompressed == stored) payload else return null
            CODEC_DEFLATE -> {
                if (stored == 0 || uncompressed > stored.toLong() * ResourceLimits.MAX_EXPANSION_RATIO) return null
                inflateCapped(payload, uncompressed) ?: return null
            }
            else -> return null
        }
        return if (body.size <= maxBytes) body else body.copyOf(maxBytes)
    }

    internal open fun writeAtomic(tmp: File, bytes: ByteArray) {
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    fun discardTemps() {
        root.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_SPEED)).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun inflateCapped(payload: ByteArray, declared: Int): ByteArray? {
        val out = ByteArray(declared)
        return try {
            InflaterInputStream(ByteArrayInputStream(payload)).use { input ->
                var off = 0
                while (off < declared) {
                    val n = input.read(out, off, declared - off)
                    if (n < 0) return null
                    off += n
                }
                if (input.read() != -1) return null
            }
            out
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun readBounded(file: File): ByteArray? {
        val len = file.length()
        if (len < HEADER || len > HEADER + ResourceLimits.MAX_DECLARED_UNCOMPRESSED.toLong()) return null
        val out = ByteArray(len.toInt())
        file.inputStream().use { input ->
            var off = 0
            while (off < out.size) {
                val n = input.read(out, off, out.size - off)
                if (n < 0) return null
                off += n
            }
            if (input.read() != -1) return null
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun intAt(raw: ByteArray, offset: Int): Int =
        ((raw[offset].toInt() and 0xff) shl 24) or
            ((raw[offset + 1].toInt() and 0xff) shl 16) or
            ((raw[offset + 2].toInt() and 0xff) shl 8) or
            (raw[offset + 3].toInt() and 0xff)

    companion object {
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
        private const val CODEC_RAW = 1
        private const val CODEC_DEFLATE = 2
        private const val HEADER = 13
    }
}
