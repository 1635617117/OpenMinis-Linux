package com.openminis.app.provider

import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-key-rotation] Optional multi-key rotation without a schema change.
 *
 * Sticky-by-design: the pool key stays pinned **per instance** until a breaker
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
 *
 * Cursor dimension is [instanceId] (not modelId). Key pools are per-instance;
 * QuickTest, refresh, fallback, and the main send path all share the same
 * cursor, so a breaker-triggered advance is visible to every code path that
 * reads credentials — it can never fork behind different model-name strings.
 */
object ProviderKeyRotation {
    /** Cursor per instance (one pool → one cursor). */
    private val cursors = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    fun pool(instanceId: String, prefsPool: String? = null): List<String> {
        val raw = prefsPool?.takeIf { it.isNotBlank() }
            ?: System.getenv("OPENMINIS_KEYS_$instanceId")
            ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * The currently pinned key for this instance, without advancing. Returns
     * null when no pool exists (single-key behaviour unchanged).
     *
     * The `modelId` parameter is kept for caller compatibility but is ignored
     * — the cursor dimension is the instance id so every code path (send,
     * fallback, QuickTest, refresh) sees the same pool index.
     */
    @Suppress("UNUSED_PARAMETER")
    fun current(instanceId: String, prefsPool: String? = null, modelId: String = ""): String? {
        val pool = pool(instanceId, prefsPool)
        if (pool.isEmpty()) return null
        val cursor = cursors.getOrPut(instanceId) { AtomicInteger(0) }
        val index = Math.floorMod(cursor.get(), pool.size)
        return pool[index]
    }

    /** Advance to the next pool member for this instance (breaker trip). */
    fun advance(instanceId: String) {
        cursors.getOrPut(instanceId) { AtomicInteger(0) }.incrementAndGet()
    }
}