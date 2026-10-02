package com.openminis.app.auth

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.URI

class OAuthCallbackServer(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    private val expectedPath: String = "/callback",
    private val onCode: (code: String, state: String?) -> Unit,
) {
    companion object {
        private const val TAG = "OAuthCallbackServer"
        private const val MAX_REQUEST_LINE = 4096
        private const val MAX_RESPONSE_BYTES = 1024
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** The port actually bound (may differ from [port] if fallback was used). */
    var boundPort: Int = port
        private set

    /**
     * Invoked by [stop] when the server is shut down by an external
     * caller (e.g. the user dismisses Chrome Custom Tab and the auth
     * manager wants to abort the in-flight wait without waiting for the
     * 5-minute timeout). The callback is _only_ fired when stop() is
     * called by something other than the success path —
     * [onCode] callers set this to null before calling stop() so they
     * don't fire a cancel after a successful redirect.
     *
     * [T-xai-oauth-stop-resume, port iOS d1dbdd5d]
     */
    @Volatile var onExternalCancel: (() -> Unit)? = null

    fun start() {
        running = true
        Thread {
            try {
                val portsToTry = listOf(port) + fallbackPorts
                var bound = false
                for (p in portsToTry) {
                    try {
                        serverSocket = ServerSocket(p)
                        boundPort = p
                        bound = true
                        break
                    } catch (e: java.net.BindException) {
                        Log.w(TAG, "Port $p in use, trying next...")
                    }
                }
                if (!bound) {
                    Log.e(TAG, "All ports unavailable: $portsToTry")
                    return@Thread
                }
                Log.d(TAG, "Listening on port $boundPort")
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    try {
                        socket.soTimeout = 5000
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val requestLine = reader.readLine() ?: continue
                        if (requestLine.length > MAX_REQUEST_LINE) { writeResponse(socket, 414, "Request URI too long"); continue }
                        val parts = requestLine.split(' ')
                        if (parts.size != 3 || parts[2] != "HTTP/1.1") { writeResponse(socket, 400, "Bad request"); continue }
                        if (parts[0] != "GET") { writeResponse(socket, 405, "Method not allowed"); continue }
                        val requestTarget = parts[1]
                        var headerBytes = 0
                        while (true) {
                            val header = reader.readLine() ?: break
                            headerBytes += header.length
                            if (header.isEmpty() || headerBytes > MAX_REQUEST_LINE) break
                        }
                        if (headerBytes > MAX_REQUEST_LINE) { writeResponse(socket, 431, "Headers too large"); continue }

                        // Parse only the exact loopback callback path.
                        val uri = URI("http://localhost$requestTarget")
                            if (uri.rawPath != expectedPath || uri.rawFragment != null || (uri.rawQuery?.length ?: 0) > MAX_REQUEST_LINE) {
                                writeResponse(socket, 404, "Not found")
                                continue
                            }
                            val params = uri.query?.split("&")?.associate {
                                val kv = it.split("=", limit = 2)
                                kv[0] to (if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else "")
                            } ?: emptyMap()

                            val code = params["code"]
                            val state = params["state"]

                            // Send response
                            val html = "<html><body><h1>Authorization complete</h1><p>You can close this tab.</p><script>window.close()</script></body></html>"
                            writeResponse(socket, 200, html)
                            Log.i(TAG, "OAuth callback accepted: codePresent=${!code.isNullOrEmpty()} statePresent=${!state.isNullOrEmpty()}")

                            if (!code.isNullOrEmpty()) {
                                onCode(code, state)
                                stop()
                                return@Thread
                            }
                        socket.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling connection", e)
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            }
        }.start()
    }

    private fun writeResponse(socket: java.net.Socket, status: Int, body: String) {
        val safeBody = body.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_RESPONSE_BYTES) it.copyOf(MAX_RESPONSE_BYTES) else it }
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 414 -> "URI Too Long"; 431 -> "Request Header Fields Too Large"; else -> "Error" }
        val response = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${safeBody.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().use { it.write(response.toByteArray()); it.write(safeBody) }
        socket.close()
    }

    fun stop() {
        val wasRunning = running
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // Notify external callers (e.g. XAIOAuthManager) so they can
        // cancel an in-flight suspendCancellableCoroutine instead of
        // hanging until the next inbound connection / the 5-min
        // OAuth wait timeout fires. Consume the callback (set to
        // null before invoking) so re-entrant stop() calls don't
        // double-fire (T-xai-oauth-stop-resume).
        if (wasRunning) {
            val cancel = onExternalCancel
            onExternalCancel = null
            try { cancel?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onExternalCancel threw: ${e.message}")
            }
        }
    }
}
