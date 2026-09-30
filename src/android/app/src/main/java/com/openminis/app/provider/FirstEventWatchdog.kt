package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-first-event-watchdog] Cancel a stream that produces no chunk at all
 * within [timeoutMs], then surface a [LLMError.TransientError] so the agent
 * loop's existing auto-retry/fallback chain takes over.
 *
 * A hung relay (DNS/TCP/TLS/read below the provider layer) never throws — it
 * just never emits. Without this operator the collector waits forever and no
 * retry machinery ever runs ("thinking… forever, stop button still armed").
 *
 * Muse-equivalent: FirstEventWatchdog 45s default / 90s for reasoning models.
 * The timeout is per FIRST chunk — once anything arrives (thinking delta,
 * text, tool call), the watchdog retires and the stream runs to completion.
 */
fun Flow<LLMStreamChunk>.firstEventWatchdog(
    timeoutMs: Long,
    reason: String = "no first chunk within ${timeoutMs / 1000}s",
): Flow<LLMStreamChunk> = channelFlow {
    val seen = AtomicBoolean(false)
    val upstream = launch {
        try {
            collect { chunk ->
                seen.set(true)
                send(chunk)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled by the watchdog (or by the outer job) — nothing to
            // rethrow here; the job completes cancelled and join() returns.
            throw e
        }
    }
    val watchdog = launch {
        delay(timeoutMs)
        if (!seen.get()) {
            upstream.cancel()
        }
    }
    upstream.join()
    watchdog.cancel()
    if (!seen.get()) {
        throw LLMError.TransientError(reason)
    }
}
