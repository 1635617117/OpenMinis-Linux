package com.openminis.app.security

/**
 * Argv tokenizer and expansion-syntax detector.
 *
 * Adapted from XINCODE-Public ExecPolicy.kt (GPL-3.0-or-later)
 * which itself ports Codex (Apache-2.0) quoting rules.
 * https://github.com/kusesad-1122/XINCODE-Public
 */
fun tokenizeCommand(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote = '\u0000'
    var i = 0
    var hasToken = false
    while (i < raw.length) {
        val c = raw[i]
        when {
            c == '\\' && i + 1 < raw.length -> {
                cur.append(raw[i + 1]); i += 2; hasToken = true; continue
            }
            quote != '\u0000' -> {
                if (c == quote) quote = '\u0000' else { cur.append(c); hasToken = true }
            }
            c == '\'' || c == '"' -> quote = c
            c.isWhitespace() -> {
                if (hasToken || cur.isNotEmpty()) {
                    out.add(cur.toString()); cur.clear(); hasToken = false
                }
            }
            else -> { cur.append(c); hasToken = true }
        }
        i++
    }
    if (hasToken || cur.isNotEmpty()) out.add(cur.toString())
    return out
}

val APP_DATA_ROOTS = listOf(
    "/data/data/com.openminis.linux",
    "/data/user/0/com.openminis.linux",
)

fun containsShellExpansionSyntax(raw: String): Boolean {
    if (raw.isEmpty()) return false
    if (raw.contains('$')) return true
    if (raw.contains('`')) return true
    if (raw.contains(';')) return true
    if (raw.contains('|')) return true
    if (raw.contains('&')) return true
    if (raw.contains('>')) return true
    if (raw.contains('\n') || raw.contains('\r')) return true
    if (Regex("\\{[^}\\s]*,[^}\\s]*\\}").containsMatchIn(raw)) return true
    return false
}

/**
 * Split on `;`, `|`, `&`, newlines, and — [T-prefixrule-substitution] — on
 * command-substitution boundaries (a backtick, or `$(`). The old splitter
 * only saw the outer operator, so `echo $(rm -rf /x)` was ONE segment with
 * argv[0] == "echo" and the prefix-rule layer never saw the `rm` token
 * inside the substitution. Splitting at every substitution opener hands the
 * inner command its own segment, where prefix matching can see it. Nesting
 * is not balanced (each `$(` opens a cut); the innermost real command token
 * still surfaces as a segment head.
 */
fun shellSegments(raw: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote = '\u0000'
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (quote != '\u0000') {
            cur.append(c)
            if (c == quote) quote = '\u0000'
            i++
            continue
        }
        if (c == '\\' && i + 1 < raw.length) {
            cur.append(raw[i + 1])
            i += 2
            continue
        }
        if (c == '\'' || c == '"') {
            quote = c
            cur.append(c)
            i++
            continue
        }
        if (c == '`' || (c == '$' && i + 1 < raw.length && raw[i + 1] == '(')) {
            val seg = cur.toString().trim()
            if (seg.isNotEmpty()) out.add(seg)
            cur.clear()
            i += if (c == '$') 2 else 1
            continue
        }
        if (c == '\n' || c == '\r' || c == ';' || c == '|' || c == '&') {
            val sepLen = if ((c == '|' || c == '&') && i + 1 < raw.length && raw[i + 1] == c) 2 else 1
            val seg = cur.toString().trim()
            if (seg.isNotEmpty()) out.add(seg)
            cur.clear()
            i += sepLen
            continue
        }
        cur.append(c)
        i++
    }
    val tail = cur.toString().trim()
    if (tail.isNotEmpty()) out.add(tail)
    return out
}

/**
 * Units that must be classified on their own. A `su -c` / `sh -c` payload is
 * split further so `rm /tmp; ls /data/...` is not one fatal string.
 *
 * [T-prefixrule-wrapper-unwrap] The same holds for argv[0] WRAPPERS: the old
 * version only unwrapped `su`/`sh`/`bash`, so `env X=1 rm -rf /`,
 * `xargs rm -rf /`, `find . -exec rm -rf {} \;`, `nice -n 19 rm -rf /`,
 * `timeout 5 rm -rf /`, `busybox rm -rf /` and friends all presented a
 * wrapper as argv[0] and the prefix-rule layer never saw the real program
 * token — several wrappers even sit in SAFE_COMMANDS and auto-ran. Every
 * wrapper head now recursively yields an extra unit that starts at the
 * wrapped program, and `find -exec` payloads are lifted into their own
 * units. Over-unwrapping is safe by construction: this layer only tightens.
 */
private val WRAPPER_HEADS = setOf(
    "env", "nice", "timeout", "xargs", "stdbuf", "setsid",
    "nohup", "sudo", "busybox", "strace", "script",
)

/** Index of the wrapped program inside [argv] when [argv] starts with a wrapper. */
private fun wrappedProgramIndex(argv: List<String>): Int {
    val head = argv.firstOrNull()?.substringAfterLast('/') ?: return -1
    var i = 1
    val valueOptions = when (head) {
        "env" -> setOf("-u", "--unset", "-C", "--chdir", "-S", "--split-string")
        "sudo" -> setOf("-u", "--user", "-g", "--group", "-h", "--host", "-p", "--prompt", "-C", "--close-from")
        "nice" -> setOf("-n", "--adjustment")
        "timeout" -> setOf("-k", "--kill-after", "-s", "--signal")
        "stdbuf" -> setOf("-i", "-o", "-e")
        "xargs" -> setOf("-n", "--max-args", "-P", "--max-procs", "-s", "--max-chars", "-I", "--replace", "-L", "--max-lines", "-E", "--eof", "-d", "--delimiter", "-a", "--arg-file")
        else -> emptySet()
    }
    if (head in setOf("env", "sudo", "nice", "timeout", "stdbuf", "xargs")) {
        while (i < argv.size) {
            val arg = argv[i]
            if (arg == "--") { i++; break }
            if (arg in valueOptions) {
                i += 2
                continue
            }
            if (arg.startsWith("-") && arg != "-") { i++; continue }
            if (head == "env" && arg.contains('=')) { i++; continue }
            if (head == "nice" && arg.toIntOrNull() != null) { i++; continue }
            break
        }
    }
    if (head == "timeout" && i < argv.size) i++ // mandatory duration, regardless of spelling
    return if (i < argv.size) i else -1
}

fun riskUnits(command: String): List<String> {
    val units = mutableListOf<String>()
    for (segment in shellSegments(command)) {
        collectRiskUnits(segment, 0, units)
    }
    return units
}

private const val MAX_UNWRAP_DEPTH = 4

private fun collectRiskUnits(segment: String, depth: Int, units: MutableList<String>) {
    val argv = tokenizeCommand(segment)
    val head = argv.firstOrNull()?.substringAfterLast('/')
    val cIdx = argv.indexOf("-c")
    if (cIdx >= 0 && cIdx + 1 < argv.size &&
        (head == "su" || head == "android-su" || head == "sh" || head == "bash" ||
            head == "ash" || head == "dash" || head == "zsh" || head == "ksh")
    ) {
        for (inner in shellSegments(argv[cIdx + 1])) collectRiskUnits(inner, depth, units)
    }
    if (depth < MAX_UNWRAP_DEPTH) {
        if (head in WRAPPER_HEADS) {
            val programIdx = wrappedProgramIndex(argv)
            if (programIdx > 0 && programIdx < argv.size) {
                collectRiskUnits(argv.drop(programIdx).joinToString(" "), depth + 1, units)
            }
        }
        // `find … -exec cmd … {} ;` / `-execdir` — the embedded command gets
        // its own unit so `rm -rf` style prefixes match inside it.
        val execIdx = argv.indexOfFirst { it == "-exec" || it == "-execdir" }
        if (execIdx >= 0 && execIdx + 1 < argv.size) {
            val embedded = argv.drop(execIdx + 1)
                .takeWhile { it != "{}" && it != ";" && it != "\\;" }
                .joinToString(" ")
            if (embedded.isNotBlank()) collectRiskUnits(embedded, depth + 1, units)
        }
    }
    units += segment
}
