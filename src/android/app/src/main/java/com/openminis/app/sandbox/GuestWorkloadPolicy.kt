package com.openminis.app.sandbox

/**
 * Handheld ceilings for guest work. This APK only runs on a phone or tablet:
 * a Python tree walk at foreground priority wedged a 12GB device in eight
 * minutes (OML-IA-2026-0927). Scheduling isolation is the primary brake;
 * these classifiers decide the wall-clock, CPU, disk and approval brakes.
 *
 * Pure. No Android types, so the decisions are unit-tested without a device.
 */
internal object GuestWorkloadPolicy {

    const val CPU_SECONDS_DEFAULT = 120
    const val CPU_SECONDS_BUILD = 300
    const val CPU_SECONDS_INSTALL = 300
    const val CPU_SECONDS_SETUP = 900

    /**
     * `ulimit -u` and the host-side cap. A guest that raises its own soft
     * limit, or forks before the prefix runs, still dies when the host sees
     * more than this many owned pids.
     */
    const val PROCESS_LIMIT = 256

    const val NOFILE_LIMIT = 4096

    /**
     * `ulimit -f` is in 1024-byte blocks. 2GB stops one `dd` from filling the
     * volume without rejecting an SDK zip or an apt package. A directory of
     * small files is caught by [diskRefusal], not by this per-file cap.
     */
    const val FILE_SIZE_KB = 2 * 1024 * 1024

    const val DISK_MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024
    const val DISK_HARD_USED = 0.92
    const val DISK_AMPLIFY_USED = 0.82

    const val HOST_SU_MAX_TIMEOUT_MS = 120_000L

    /**
     * Hard CPU budget. Command names do not opt out: a process that calls
     * itself a server can still spin. An idle server burns almost no CPU, so
     * the cap does not kill it. Only the documented setup scripts get more.
     */
    fun cpuSeconds(command: String): Int =
        com.openminis.app.sandbox.kernel.BudgetClassifier.classify(command).cpuSeconds

    fun isSetup(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("minis-dev-setup-full") ||
            c.contains("minis-android-sdk-setup") ||
            c.contains("minis-self-build")
    }

    fun isBuild(command: String): Boolean {
        val c = command.lowercase()
        if (Regex("""(?:^|\s|\./)gradle(?:w)?\s+--stop\s*$""").containsMatchIn(c)) return false
        return c.contains("gradle") || c.contains("assembledebug") ||
            c.contains("assemblerelease") || c.contains("aapt2")
    }

    fun isInstall(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("apt-get") || c.contains("apt ") || c.contains("dpkg") ||
            c.contains("sdkmanager") || c.contains("minis-dev-setup") ||
            c.contains("pip install") || c.contains("pip3 install")
    }

    fun isBroadFind(command: String): Boolean =
        findPathArgs(command).any(::isBroadRoot)

    /**
     * Refuse before spawn when the disk is already stressed. This is not a
     * denylist of known tools: anything that is not an obvious read is
     * refused, including a binary we have never seen. Reads stay available
     * so the agent can still see why.
     */
    fun diskRefusal(availableBytes: Long, totalBytes: Long, command: String): String? {
        if (totalBytes <= 0L || availableBytes < 0L) return null
        if (isObviouslyReadOnly(command)) return null
        val used = 1.0 - availableBytes.toDouble() / totalBytes.toDouble()
        val stressed = availableBytes < DISK_MIN_FREE_BYTES || used >= DISK_AMPLIFY_USED
        if (!stressed) return null
        val freeMiB = availableBytes / (1024 * 1024)
        val pct = (used * 100).toInt()
        return "命令未启动（exit 126）：磁盘已用 $pct%，空闲 ${freeMiB} MiB。" +
            "磁盘超过 ${(DISK_AMPLIFY_USED * 100).toInt()}% 或空闲不足 2GB 时，只放行明确的只读命令。" +
            "先清理空间再重试。"
    }

    /**
     * A live guest plus one long stall is already the failure. A single short
     * stutter is not: markdown can hitch for a few seconds without a guest
     * being the cause. Two counted hangs, or one stall of 8s or more, kills.
     */
    fun shouldKillLiveWork(hangCount: Int, durationMs: Long): Boolean =
        hangCount >= 2 || durationMs >= 8_000L

    fun exceedsProcessCap(ownedPids: Int): Boolean = ownedPids > PROCESS_LIMIT

    fun isObviouslyReadOnly(command: String): Boolean {
        if (command.isBlank()) return true
        if (hasWriteSyntax(command)) return false
        val segments = command.split(Regex("""&&|\|\||[;|]""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return true
        return segments.all { segment ->
            val token = commandToken(segment) ?: return@all false
            when (token) {
                "git" -> isReadOnlyGit(segment)
                else -> token in READ_ONLY_TOKENS
            }
        }
    }

    /**
     * The ceiling may reduce log sampling. It must not drop the counter:
     * a gap that is only "too long to be a hang" is exactly the freeze the
     * remediation loop has to see.
     */
    @Suppress("UNUSED_PARAMETER")
    fun countsHang(gapMs: Long, ceilingMs: Long, workloadLive: Boolean): Boolean = true

    fun shouldSampleHang(gapMs: Long, ceilingMs: Long, workloadLive: Boolean): Boolean =
        workloadLive || gapMs <= ceilingMs

    /** Host `su` and unscoped walks must not inherit "allow this tool". */
    fun requiresFreshConfirm(command: String): Boolean {
        if (isBroadFind(command)) return true
        val c = command.lowercase()
        return c.contains("android-su") || c.contains("su -c") ||
            Regex("""(?:^|[;&|`(\n])\s*(?:\S*/)?su(?:\s|$)""").containsMatchIn(c)
    }

    /** Hard refuse. Confirmation cannot make these safe on a handheld. */
    fun hostRefusal(command: String): String? {
        val c = command.lowercase().replace(Regex("\\s+"), " ")
        if (c.contains("mkfs") || c.contains("of=/dev/") || c.contains(">/dev/block") ||
            c.contains(">/dev/mmc")
        ) {
            return "命令未启动（exit 126）：禁止在宿主上操作块设备。"
        }
        if (Regex("""\brm\s+-[a-z]*f[a-z]*\s+/\s*$""").containsMatchIn(c) ||
            c.contains("rm -rf /") || c.contains("rm -fr /")
        ) {
            return "命令未启动（exit 126）：禁止删除根目录。"
        }
        if (isBroadFind(command)) {
            return "命令未启动（exit 126）：禁止对 /、/data、/root、/home、/var 做无界 find。" +
                "改为限定目录，例如 find /var/minis/workspace -maxdepth 4。"
        }
        return null
    }

    private val READ_ONLY_TOKENS = setOf(
        "ls", "cat", "head", "tail", "pwd", "echo", "printf", "df", "stat", "file",
        "wc", "true", "false", "whoami", "id", "uname", "date", "env", "printenv",
        "which", "type", "basename", "dirname", "readlink", "realpath", "test", "[",
        "sha256sum", "sha1sum", "md5sum", "cmp", "diff", "cut", "tr", "sort", "uniq",
        "nl", "od", "hexdump", "fold", "grep", "egrep", "fgrep",
    )

    fun clampHostTimeout(requestedMs: Long): Long =
        requestedMs.coerceIn(1L, HOST_SU_MAX_TIMEOUT_MS)

    fun findPathArgs(command: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("""(?:^|[;&|`(\n])\s*(?:\S*/)?find\b""")
        var from = 0
        while (true) {
            val m = re.find(command, from) ?: break
            val rest = command.substring(m.range.last + 1).trim()
            for (raw in rest.split(Regex("\\s+"))) {
                if (raw.isEmpty()) continue
                if (raw == "|" || raw == "||" || raw == "&&" || raw == ";") break
                if (raw.startsWith("-")) break
                out += raw.trim('"', '\'', '`')
            }
            from = m.range.last + 1
        }
        return out
    }

    private fun hasWriteSyntax(command: String): Boolean {
        val stripped = command.replace(Regex(""""[^"]*"|'[^']*'"""), " ")
        if (Regex("""\btee\b""").containsMatchIn(stripped)) return true
        return Regex("""(^|[^>])>>?(?![>&])""").containsMatchIn(stripped)
    }

    private fun commandToken(segment: String): String? {
        for (raw in segment.trim().split(Regex("\\s+"))) {
            if (raw.isEmpty()) continue
            if (!raw.startsWith("-") && raw.contains("=")) continue
            return raw.substringAfterLast('/').lowercase()
        }
        return null
    }

    private fun isReadOnlyGit(segment: String): Boolean {
        val verb = segment.trim().split(Regex("\\s+")).dropWhile { it.contains("=") }
            .drop(1).firstOrNull()?.lowercase() ?: return false
        return verb in setOf("status", "log", "diff", "show", "rev-parse", "blame", "ls-files", "describe")
    }

    private fun isBroadRoot(path: String): Boolean {
        val p = path.trimEnd('/').ifEmpty { "/" }
        return p == "/" || p == "/data" || p.startsWith("/data/") ||
            p == "/home" || p == "/root" || p == "/var" || p == "/sys" || p == "/proc"
    }
}
