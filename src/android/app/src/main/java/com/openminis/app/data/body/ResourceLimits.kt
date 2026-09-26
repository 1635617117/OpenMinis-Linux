package com.openminis.app.data.body

/**
 * Absolute ceilings for untrusted input. Not a row count, and not a fraction
 * of device RAM. A 12 GB tablet with gigabytes free and a phone with 66 MB
 * free share these numbers.
 */
object ResourceLimits {
    const val PREVIEW_BYTES = 8 * 1024
    const val SQL_CELL_BYTES = 64 * 1024
    const val PARSE_OUTPUT_BYTES = 256 * 1024
    const val MAX_PARSE_NODES = 256
    const val INLINE_BODY_BYTES = 2048
    const val MAX_EXPANSION_RATIO = 32
    const val MAX_DECLARED_UNCOMPRESSED = 8 * 1024 * 1024
    /** One session load may materialize at most this many preview bytes. */
    const val SESSION_PREVIEW_BUDGET = 1024 * 1024
    /** Process-wide budget for concurrent untrusted expansions. */
    const val ADMIT_BUDGET_BYTES = 8L * 1024L * 1024L
    const val SUBSTR_CHUNK_CHARS = 65536
    const val HEALTHY_TICK_MS = 60_000L
    /** Guest address-space cap. Absolute, never a percent of device RAM. */
    const val GUEST_ADDRESS_BYTES = 256L * 1024L * 1024L
    const val NODE_OLD_SPACE_MB = 192
}
