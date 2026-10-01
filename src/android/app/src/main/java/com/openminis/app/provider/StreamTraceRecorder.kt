package com.openminis.app.provider

import com.openminis.app.data.model.LLMStreamChunk
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-stream-trace-live] Runtime stream-trace recorder for the log-management
 * replay view. When enabled, every decoded stream chunk is appended as one
 * JSON line under `offloads/stream-traces/` (one file per recording session,
 * rotated by day), replayable by eye or via StreamTrace.decode for diffing.
 *
 * The unit-test regression net ([StreamTrace] + test resources) is the
 * enforcement layer; this recorder is the observation layer — "what did the
 * model actually stream last night" without re-running anything.
 */
object StreamTraceRecorder {
    const val DIR = "/var/minis/workspace/offloads/stream-traces"
    private const val PREFS = "stream_trace"
    private const val PREFS_ENABLED = "enabled"

    @Volatile
    var enabled: Boolean = false

    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
    }

    private val file = java.util.concurrent.atomic.AtomicReference<File?>(null)

    fun setEnabled(enabled: Boolean, context: android.content.Context) {
        this.enabled = enabled
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(PREFS_ENABLED, enabled).apply()
        if (!enabled) file.set(null)
    }

    fun restore(context: android.content.Context) {
        enabled = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREFS_ENABLED, false)
    }

    /** One JSON line per chunk; new file per recording session. */
    fun record(chunk: LLMStreamChunk) {
        if (!enabled) return
        runCatching {
            val f = file.get() ?: synchronized(this) {
                file.get() ?: File(DIR).let { dir ->
                    dir.mkdirs()
                    File(dir, "trace_${dateFormat.get().format(Date())}.jsonl")
                }.also { file.set(it) }
            }
            f.appendText(StreamTrace.encodeChunk(chunk).toString() + "\n")
        }
    }

    /** End the current recording session (next record starts a new file). */
    fun newSession() {
        file.set(null)
    }
}
