package com.openminis.app.provider

import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-key-rotation] Optional multi-key rotation without a schema change.
 *
 * Sticky-by-design: the pool key stays pinned **per model** until a breaker
 * trip advances the cursor (see [ProviderKeyGate] — three consecutive
 * transient failures on the same bucket). A healthy key is never rotated
 * away, so provider-side prompt caching and quota stay stable; only a dead
 * key triggers a switch to the next pool member.
 *
 * Pool sources, in order: the per-instance prefs pool (provider detail page)
 * then the `OPENMINIS_KEYS_<instanceId>` env var (comma-separated).
 *
 * No pool → null → callers fall back to the single stored key, exactly as
 * before.
 */
object ProviderKeyRotation {
    /** Cursor per model (same dimension as breaker buckets). */
    private val cursors = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    fun pool(instanceId: String, prefsPool: String? = null): List<String> {
        val raw = prefsPool?.takeIf { it.isNotBlank() }
            ?: System.getenv("OPENMINIS_KEYS_$instanceId")
            ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * The currently pinned key for this model, without advancing. Returns
     * null when no pool exists (single-key behaviour unchanged).
     */
    fun current(instanceId: String, prefsPool: String? = null, modelId: String = ""): String? {
        val pool = pool(instanceId, prefsPool)
        if (pool.isEmpty()) return null
        val key = cursorKey(modelId)
        val cursor = cursors.getOrPut(key) { AtomicInteger(0) }
        val index = Math.floorMod(cursor.get(), pool.size)
        return pool[index]
    }

    /** Advance to the next pool member for this model (breaker trip). */
    fun advance(modelId: String) {
        cursors.getOrPut(cursorKey(modelId)) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun cursorKey(modelId: String): String =
        modelId.trim().lowercase().ifBlank { "default" }
}
