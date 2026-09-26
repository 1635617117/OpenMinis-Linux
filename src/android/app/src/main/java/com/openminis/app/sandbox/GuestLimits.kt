package com.openminis.app.sandbox

import com.openminis.app.data.body.ResourceLimits

/**
 * Absolute guest ceilings. Not a fraction of device RAM.
 */
object GuestLimits {
    fun addressLimitKiB(): Long = ResourceLimits.GUEST_ADDRESS_BYTES / 1024L

    fun nodeOptions(existing: String? = null): String {
        val flag = "--max-old-space-size=${ResourceLimits.NODE_OLD_SPACE_MB}"
        val stripped = existing.orEmpty()
            .replace(Regex("""--max-old-space-size(?:=|\s+)\d+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (stripped.isEmpty()) flag else "$stripped $flag"
    }

    fun wrap(shellCommand: String): String =
        "ulimit -v ${addressLimitKiB()} || exit 1; $shellCommand"
}
