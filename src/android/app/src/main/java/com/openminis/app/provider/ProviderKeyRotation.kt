package com.openminis.app.provider

import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-key-rotation] Optional multi-key rotation without a schema change.
 *
 * When the environment variable `OPENMINIS_KEYS_<instanceId>` holds a
 * comma-separated key list for a provider instance, the repository picks
 * the next key on every call (round-robin). Combined with
 * [ProviderKeyGate] circuit breakers, a dead key cools down instead of
 * bouncing between providers with backoff.
 *
 * No env var → single-key behaviour exactly as before.
 */
object ProviderKeyRotation {
    private val cursors = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()

    fun pool(instanceId: String): List<String> {
        val raw = System.getenv("OPENMINIS_KEYS_$instanceId") ?: return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotBlank() }
    }

    /** Returns the next key from the pool, or null when no pool exists. */
    fun next(instanceId: String): String? {
        val pool = pool(instanceId)
        if (pool.isEmpty()) return null
        val cursor = cursors.getOrPut(instanceId) { AtomicInteger(0) }
        val index = Math.floorMod(cursor.getAndIncrement(), pool.size)
        return pool[index]
    }
}
