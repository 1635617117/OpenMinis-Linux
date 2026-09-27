package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.openminis.app.agent.Level
import com.openminis.app.agent.ToolLoopDetector
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.db.AppDatabase
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.openminis.app.data.BPETokenizer
import com.openminis.app.data.ContextOffload
import com.openminis.app.data.ContextPolicy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.FileMentionIndex
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.isPureVideoGenerator
import com.openminis.app.data.CapabilityRouter
import com.openminis.app.data.ModelCapability
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.data.repository.MultiAgentSettingsRepository
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.provider.ImageBudget
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.provider.catalogMaxThinkingLevel
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.agent.shell.BashismDetector
import com.openminis.app.agent.shell.BashismReminder
import com.openminis.app.agent.shell.OnDemandBash
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.terminal.MinisUrlMarker
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.CodeGraphTool
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.SubAgentLane
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.CronJobTool
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.tools.PlanDiscussionOrchestrator
import com.openminis.app.data.PlanDiscussionPrefs
import com.openminis.app.data.PlanDiscussionTrigger
import com.openminis.app.MinisApp
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.service.ApprovalGate
import com.openminis.app.security.SecurityGateHolder
import com.openminis.app.notification.ApprovalNotifier
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import com.openminis.app.util.IsoTime

// [T-android-split-chat] StreamingDelta / ChatMessage / QueuedPrompt /
// ToolBlockStatus / SlashCommand / AssistantBlock moved verbatim to ChatModels.kt.

class ChatViewModel(
    internal val sessionId: String,
    internal val chatRepository: ChatRepository,
    internal val providerRepository: ProviderRepository,
    internal val context: Context,
    val memoryRepository: MemoryRepository? = null,
    val skillRepository: com.openminis.app.data.repository.SkillRepository? = null,
    val mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
) : ViewModel(), com.openminis.app.session.ChatSessionPort {

    /** Daily logs and session GLOBAL.md live in this chat's workspace. */
    internal fun sessionMemoryRepo(): MemoryRepository {
        val sid = com.openminis.app.sandbox.ExecutionCoordinator.ownerSessionId(
            realSessionId.ifEmpty { sessionId },
        )
        return MemoryRepository(
            com.openminis.app.sandbox.SessionWorkspace.memoryDir(context.filesDir, sid),
        )
    }

    companion object {
        internal const val TAG = "ChatViewModel"
        internal val HTTP_5XX_STATUS_RE = Regex("""\[5\d{2}\]""")

        // ── [T-android-compact-runaway] Compaction budgets ──────────────
        //
        // Compaction had no ceiling of any kind. Its only time bound was the
        // provider's OkHttp readTimeout (10 minutes on every provider), and
        // the split-retry path could issue up to 1+2+4+8 = 15 SEQUENTIAL leaf
        // calls before depth 3 stopped it. Slow-but-not-timing-out calls (a
        // rate-limited or queued model at ~80s each) therefore added up to
        // roughly 20 minutes of apparent hang — which matches the report.
        //
        // Three independent ceilings now bound it, because each catches a case
        // the others miss: the call budget stops fan-out, the wall-clock
        // timeout stops slow-but-few calls, and the existing depth cap stops
        // recursion.

        /**
         * Leaf LLM calls one compaction may issue in total, across every
         * segment. The depth-3 cap alone permits 15; this cuts the worst case
         * to a third of that while still allowing a full first split (1+2) plus
         * one deeper rescue.
         */
        internal const val MAX_COMPACT_LLM_CALLS = 6

        internal const val COMPACT_SEGMENT_MIN_TOKENS = 8_000
        internal const val COMPACT_SEGMENT_MAX_TOKENS = 32_000

        internal fun estimateCompactTokens(text: String): Int =
            (text.length / 4).coerceAtLeast(1)

        internal fun compactSegmentTokenBudget(contextWindow: Int): Int =
            (contextWindow / 5).coerceIn(COMPACT_SEGMENT_MIN_TOKENS, COMPACT_SEGMENT_MAX_TOKENS)

        internal fun shouldProactivelySplit(
            messageCount: Int,
            estimatedTokens: Int,
            tokenBudget: Int,
            depth: Int,
            callsAlreadySpent: Int,
        ): Boolean =
            messageCount >= 2 &&
                depth < 3 &&
                estimatedTokens > tokenBudget &&
                callsAlreadySpent + 2 <= MAX_COMPACT_LLM_CALLS

        internal fun isFirstByteTimeout(error: Throwable): Boolean {
            val detail = when (error) {
                is LLMError.TransientError -> error.detail
                else -> error.message
            }?.lowercase() ?: return false
            return detail.contains("no response") ||
                detail.contains("ttfb") ||
                detail.contains("first byte")
        }

        /** Two shots only: session, then fallback or one session retry. */
        internal const val COMPACT_STAGE_BUDGET = 2

        internal fun compactStageKinds(hasFallbackDistinctFromSession: Boolean): List<String> =
            if (hasFallbackDistinctFromSession) listOf("session", "fallback")
            else listOf("session", "session-retry")

        /** [T-compact-summary-quality] Floor for an acceptable summary. */
        internal val MIN_ACCEPTABLE_SUMMARY_CHARS = 120

        /**
         * [T-compact-summary-quality] A summary is accepted when it is long
         * enough to carry state AND still echoes something from the user's
         * latest message — the concrete signal that the model summarized the
         * conversation instead of answering it or drifting. Pure function so
         * the policy is testable without an Android ViewModel.
         */
        internal fun isCompactSummaryAcceptable(summary: String, messages: List<LLMMessage>): Boolean {
            val trimmed = summary.trim()
            if (trimmed.length < MIN_ACCEPTABLE_SUMMARY_CHARS) return false
            val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER } ?: return true
            val probe = lastUser.content.trim()
            if (probe.length < 4) return true
            val lower = trimmed.lowercase()
            // [T-compact-summary-quality] Reuse the chunk tokenizer so the
            // check looks for the user's CONTENT terms, not arbitrary
            // character windows: a 4-gram test is fooled by filler that any
            // summary shares ("trailing context", "the length floor"), while
            // real terms ("notification", "pipeline", "压缩", "机制") are
            // exactly what a faithful summary must echo. Short ASCII words
            // are dropped - "the"/"and" match everything.
            val terms = summaryQueryTerms(probe).filter { t ->
                t.length >= 4 || (t.first().code in 0x4E00..0x9FFF)
            }
            if (terms.isEmpty()) return true
            return terms.any { t -> t in lower }
        }

        /** [T-compact-chunk-pool] Newest chunks kept in the rolling pool. */
        internal val SUMMARY_CHUNK_POOL_MAX = 8

        /** Per-chunk cap; a runaway summary must not fill the pool. */
        internal const val SUMMARY_CHUNK_MAX_CHARS = 4_000

        /** Chunks injected per turn after retrieval. */
        internal const val SUMMARY_CHUNK_TOP_K = 3

        /**
         * [T-compact-chunk-pool] Parse the marker column into chunk texts.
         * Returns an empty list for legacy/garbage rows — every caller must
         * treat that as "no pool" and fall back to `summary`.
         */
        internal fun parseSummaryChunks(json: String?): List<String> =
            parseSummaryChunkRecords(json).map { it.first }

        /** (text, epochMs) pairs, oldest first. */
        private fun parseSummaryChunkRecords(json: String?): List<Pair<String, Long>> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = org.json.JSONArray(json)
                val out = ArrayList<Pair<String, Long>>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val t = o.optString("t").takeIf { it.isNotBlank() } ?: continue
                    out.add(t to o.optLong("a", 0L))
                }
                out
            }.getOrDefault(emptyList())
        }

        /**
         * [T-compact-chunk-pool] Rolling pool update on a successful compact:
         * keep the newest [SUMMARY_CHUNK_POOL_MAX] chunks (each capped), each
         * stored as a per-compaction snapshot so earlier material can still be
         * retrieved by keyword later. Pure function, testable.
         */
        internal fun appendSummaryChunk(existingJson: String?, newSummary: String): String {
            val capped = newSummary.take(SUMMARY_CHUNK_MAX_CHARS)
            val kept = parseSummaryChunkRecords(existingJson)
                .filter { it.first.isNotBlank() }
                .toMutableList()
            kept.add(capped to System.currentTimeMillis())
            val trimmed = if (kept.size > SUMMARY_CHUNK_POOL_MAX) {
                kept.subList(kept.size - SUMMARY_CHUNK_POOL_MAX, kept.size).toList()
            } else {
                kept
            }
            return runCatching {
                val arr = org.json.JSONArray()
                for ((t, a) in trimmed) {
                    arr.put(org.json.JSONObject().put("t", t).put("a", a))
                }
                arr.toString()
            }.getOrElse { "[]" }
        }

        /**
         * [T-compact-chunk-pool] Tokenize a query into comparable units:
         * ASCII word runs plus CJK bigrams (a Chinese sentence has no spaces,
         * and a single-gram match is far too noisy). Pure function.
         */
        internal fun summaryQueryTerms(query: String): Set<String> {
            val terms = LinkedHashSet<String>()
            val lower = query.lowercase()
            val ascii = StringBuilder()
            fun flushAscii() {
                if (ascii.length >= 2) terms.add(ascii.toString())
                ascii.setLength(0)
            }
            var cjkRun = StringBuilder()
            fun flushCjk() {
                val run = cjkRun.toString()
                if (run.length >= 2) {
                    for (i in 0..run.length - 2) terms.add(run.substring(i, i + 2))
                } else if (run.length == 1) {
                    terms.add(run)
                }
                cjkRun.setLength(0)
            }
            for (c in lower) {
                when {
                    c.code < 128 && (c.isLetterOrDigit()) -> ascii.append(c)
                    c.code in 0x4E00..0x9FFF -> {
                        flushAscii()
                        cjkRun.append(c)
                    }
                    else -> {
                        flushAscii()
                        flushCjk()
                    }
                }
            }
            flushAscii()
            flushCjk()
            return terms
        }

        /**
         * [T-compact-chunk-pool] Retrieve the chunks that match the current
         * instruction, newest-first relevance, then re-sorted chronologically
         * for injection. Falls back to the most recent chunk when the query
         * shares no terms with any of them (the tail is what a conversation
         * most plausibly continues from). Pure function, testable.
         */
        internal fun selectSummaryChunks(chunksJson: String?, query: String, topK: Int = SUMMARY_CHUNK_TOP_K): List<String> {
            val chunks = parseSummaryChunkRecords(chunksJson)
            if (chunks.isEmpty()) return emptyList()
            if (chunks.size == 1) return listOf(chunks[0].first)
            val terms = summaryQueryTerms(query)
            if (terms.isEmpty()) return listOf(chunks.last().first)
            val scored = chunks.mapIndexed { idx, (text, at) ->
                val lower = text.lowercase()
                var score = 0
                for (t in terms) {
                    if (t in lower) score += if (t.length >= 2) 2 else 1
                }
                Triple(idx, score, text)
            }
            val hits = scored.filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(topK)
            val chosen = if (hits.isNotEmpty()) {
                hits
            } else {
                listOf(Triple(chunks.lastIndex, 0, chunks.last().first))
            }
            return chosen.sortedBy { it.first }.map { it.third }
        }

        /**
         * [T-compact-reduced-retry] Newest slice of a failed compaction range
         * for the last-resort reduced retry. `null` for small ranges — a
         * 5-message "transcript" does not need slicing, it just needs the
         * truncation fallback.
         */
        internal fun reducedCompactRetryInput(messages: List<LLMMessage>): List<LLMMessage>? {
            if (messages.size < 12) return null
            return messages.takeLast((messages.size * 3 / 5).coerceAtLeast(8))
        }

        /**
         * [T-compact-detached-anchor] Pure form of the detached assembly:
         * summary text + verbatim tail, first message forced to `user` with
         * the summary inlined as a content prefix. Split out of the ViewModel
         * so the fallback path is covered by tests without an Android host.
         */
        internal fun buildDetachedCompactHistory(
            summaryWrappedText: String,
            history: List<LLMMessage>,
            tailSize: Int,
        ): List<LLMMessage> {
            val tail = if (history.size > tailSize) {
                history.subList(history.size - tailSize, history.size).toList()
            } else {
                history.toList()
            }
            val start = tail.indexOfFirst { it.role == LLMMessage.Role.USER }
            if (start < 0) {
                return tail + LLMMessage(role = LLMMessage.Role.USER, content = summaryWrappedText)
            }
            val out = ArrayList<LLMMessage>(tail.size - start + 1)
            val first = tail[start]
            out.add(first.copy(content = summaryWrappedText + "\n\n" + first.content))
            out.addAll(tail.subList(start + 1, tail.size))
            return out
        }

        /** Floor for the dynamic wall-clock timeout. */
        internal const val COMPACT_TIMEOUT_BASE_MS = 90_000L

        /**
         * Added per 10k characters of transcript, so a long first compaction is
         * not cut off by a limit tuned for a short one.
         */
        internal const val COMPACT_TIMEOUT_PER_10K_CHARS_MS = 30_000L

        /**
         * Hard ceiling. Deliberately under the providers' 10-minute
         * readTimeout: past this point the run is aborted by us — with the lock
         * released and a clear message — rather than sitting on a socket that
         * may never answer.
         */
        internal const val COMPACT_TIMEOUT_MAX_MS = 300_000L

        /**
         * Wall-clock budget for compacting a transcript of [transcriptChars].
         * Grows with input so long histories get room, capped so nothing can
         * hang indefinitely.
         */
        internal fun compactTimeoutMsFor(transcriptChars: Int): Long {
            val growth = (transcriptChars / 10_000L) * COMPACT_TIMEOUT_PER_10K_CHARS_MS
            return (COMPACT_TIMEOUT_BASE_MS + growth).coerceAtMost(COMPACT_TIMEOUT_MAX_MS)
        }

        /**
         * Should a failed summary attempt be retried by splitting the input in
         * half? Pure predicate, in the companion so it is testable without an
         * Android-bound ViewModel; [isSegmentRetryableError] delegates here.
         *
         * Splitting only helps when the failure was caused by the SIZE of the
         * request. Unclassified errors still split — an over-length refusal
         * arrives as an untyped ProviderError on most providers, and a summary
         * built from halves beats no summary — but the classes known to be
         * size-independent are excluded, because for those a split turns one
         * failure into up to 15 sequential slow calls. That amplification is
         * what produced the 15-20 minute apparent hang.
         */
        internal fun shouldSplitOnError(error: Throwable): Boolean {
            if (error is CancellationException) return false
            if (error is LLMError) {
                return when (error) {
                    // Never worth a smaller payload:
                    //  - Cancelled: the user stopped it; retrying fights that.
                    //  - NetworkError: never reached a model, size is irrelevant.
                    //  - RateLimited (429): refusing on quota, not length —
                    //    halving just doubles the rejected calls under backoff.
                    //  - TransientError: generic 5xx stays unsplit. First-byte
                    //    watchdog ("no response" / TTFB) may split.
                    //  - InvalidApiKey: auth, not size.
                    is LLMError.Cancelled,
                    is LLMError.NetworkError,
                    is LLMError.RateLimited,
                    is LLMError.InvalidApiKey,
                    -> false
                    is LLMError.TransientError -> isFirstByteTimeout(error)
                    is LLMError.ProviderError ->
                        !error.detail.contains("[429]") &&
                            !com.openminis.app.provider.HttpRetryAfter.isPermanentCapacityBody(error.detail)
                    // DecodingError / Unknown stay retryable: an over-length
                    // refusal arrives untyped, and that is the case splitting exists for.
                    else -> true
                }
            }
            // Raw OkHttp/socket failures are the Android equivalent of iOS's
            // NSURLErrorDomain bail-out: offline / DNS / TLS / timeout, all
            // payload-size independent.
            if (error is java.io.IOException) return false
            return true
        }

        /**
         * [T-android-append-to-input-eats-draft] Join the composer's current
         * [draft] with an appended [snippet]. Returns null when there is
         * nothing to append (the caller then leaves the draft untouched).
         *
         * Trims the incoming SNIPPET only. The old code called
         * `draft.trimEnd()` and assigned that trimmed copy back, so "Add to
         * input" silently rewrote the user's existing draft: a deliberate
         * trailing newline — a paragraph break they had just typed — was
         * swallowed and replaced by the separator space. The draft is the
         * user's own text and must come back byte-for-byte.
         *
         * The emptiness test still runs on a trimmed VIEW of the draft (a
         * whitespace-only draft counts as empty, rather than producing a
         * leading blank run), but that trimmed value drives the DECISION
         * only — it is never assigned back. Mirrors iOS `e6c0ace6a`.
         *
         * Pure and side-effect free so it can be unit-tested without an
         * Android runtime; see `AppendToInputTest`.
         */
        internal fun joinDraftWithSnippet(draft: String, snippet: String): String? {
            val cleaned = snippet.trim()
            if (cleaned.isEmpty()) return null
            if (draft.isBlank()) return "$cleaned "
            // Preserve the draft verbatim; only add a separator when it does
            // not already end in whitespace. A trailing newline is already a
            // separator, and adding a space after it would indent the new line.
            val separator = if (draft.last().isWhitespace()) "" else " "
            return draft + separator + cleaned + " "
        }

        /**
         * [T-android-auto-grouping-injection] Strip the characters that would let
         * user-authored text escape its slot in the prompt's group list, then
         * bound the length.
         *
         * The list is rendered as `"name" — desc; "name2" — desc2`, so a quote,
         * bracket or semicolon inside a value can terminate the list early and the
         * remainder reads as instruction. Newlines do the same at the line level.
         * Collapses whitespace so a name padded with tabs/newlines can't blow the
         * budget either.
         *
         * Deliberately NOT escaping instead of stripping: the sanitized name has to
         * survive a round trip (the model echoes it back and we match it against
         * the real folder name), and an escape sequence would come back escaped.
         * Stripping keeps the value matchable — findFolderByName's trim +
         * case-fold absorbs the difference for every realistic group name.
         */
        internal fun promptSafe(raw: String, max: Int): String =
            raw.replace(Regex("[\"'\\[\\]{};\\\\]"), " ")
                // Unicode quote lookalikes: a model reads curly and CJK
                // brackets as quoting just as readily as ASCII, so leaving
                // them in re-opens the break-out the ASCII strip closes.
                .replace(Regex("[\\u2018\\u2019\\u201C\\u201D\\u300C\\u300D\\u300E\\u300F]"), " ")
                // Format/bidi controls (RLO, LRO, ZWJ...) — invisible in code
                // review, and they can reorder how the rendered line reads.
                .replace(Regex("\\p{Cf}"), "")
                // WHITESPACE: Kotlin Regex is java.util.regex WITHOUT
                // UNICODE_CHARACTER_CLASS, so plain \\s is only
                // [ \\t\\n\\x0B\\f\\r] — U+2028 LINE SEPARATOR, U+2029
                // PARAGRAPH SEPARATOR and U+0085 NEL slip through as REAL line
                // breaks, which is exactly the multi-line break-out this
                // sanitizer exists to stop. \\p{Z} additionally covers NBSP
                // (U+00A0) and the ideographic space, neither of which
                // Kotlin's trim() removes either.
                .replace(Regex("[\\s\\p{Z}\\u0085\\u2028\\u2029]+"), " ")
                .trim()
                .take(max)
                // Trim AGAIN after the cut: take() can leave a trailing space,
                // and the round-trip matcher compares trimmed values.
                .trim()

        // [T-preflight-tool-title-nonblocking] Fields kept in each tool's
        // `required` list (so the schema keeps nudging the model to emit them —
        // tool_title drives the live pill header) but which must NOT block the
        // call when absent: they carry no execution semantics, so rejecting the
        // whole call over a missing one is pure downside. Preflight skips these
        // when checking for missing required fields. Mirrors iOS
        // AIChatViewModel.preflightNonBlockingFields.
        private val PREFLIGHT_NON_BLOCKING_FIELDS = setOf("tool_title")

        /**
         * (tool name → field names) where an EMPTY STRING is a semantically
         * valid value and must not be treated as "missing".
         *
         * Distinct from [PREFLIGHT_NON_BLOCKING_FIELDS], which skips the
         * missing-field check entirely: these fields must still be PRESENT in
         * args — they are just allowed to hold "" as their content.
         *
         * The canonical case is `file_edit.new_string`, whose schema documents
         * "Use empty string to delete old_string". Blocking it broke a promised
         * deletion workflow and pushed the model into shell_execute + python
         * file-rewrite workarounds. Mirrors iOS
         * AIChatViewModel.preflightEmptyStringAllowedFields.
         * [T-preflight-empty-string-allowed]
         */
        private val PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
            "file_edit" to setOf("new_string"),
        )

        /** True when "" is a legal value for this exact (tool, field) pair. */
        internal fun preflightEmptyStringAllowed(tool: String, field: String): Boolean =
            PREFLIGHT_EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

        /**
         * Reject tool calls that have empty args or are missing required fields
         * BEFORE [executeTool] runs. Returns null when the call is well-formed,
         * or a human-readable reason string when it should be blocked.
         *
         * Driven off the canonical [AgentToolDefinition.required] list so the
         * validator never drifts from the schema published to the model. For
         * string fields we additionally require non-blank content — the model
         * occasionally emits `{"path": ""}` which passes the "key exists" check
         * but is just as broken as a missing key. We do NOT validate type beyond
         * string-emptiness here; richer schema checks (enum, regex, integer
         * range) belong in each tool's own helper because they need tool-specific
         * context.
         *
         * Mirror of iOS preflightValidateToolCall in AIChatViewModel.swift.
         *
         * Lives in the companion (and is `internal`) because it is PURE — it reads
         * only its parameters and companion constants — so unit tests can exercise
         * it without constructing a ChatViewModel and its dependency graph. Mirrors
         * the same `nonisolated static` move on iOS.
         */
        internal fun preflightValidateToolCallImpl(
            name: String,
            args: JSONObject,
            tools: List<AgentToolDefinition>,
        ): String? {
            // Unknown tool names go through to the existing `else` branch in
            // executeTool() which returns "Unknown tool: …". Preflight stays
            // silent so we don't double-fail.
            val toolDef = tools.firstOrNull { it.name == name } ?: return null
            // Required fields that actually gate execution (everything except the
            // non-blocking ones like tool_title — see PREFLIGHT_NON_BLOCKING_FIELDS).
            val enforced = toolDef.required.filter { it !in PREFLIGHT_NON_BLOCKING_FIELDS }
            // Empty args on a tool that requires anything → block. Gate on
            // `enforced` so a tool whose only required field is non-blocking isn't
            // rejected for empty args, and the message lists only real blockers.
            if (args.length() == 0 && enforced.isNotEmpty()) {
                return "Tool '$name' was called with empty arguments {} but requires: ${enforced.joinToString(", ")}."
            }
            val missing = mutableListOf<String>()
            for (field in enforced) {
                // Absent — or present as an explicit JSON null. org.json reports
                // has() == true for `{"x": null}` and opt() hands back
                // JSONObject.NULL, which is not a String, so a null previously
                // slipped through BOTH checks and reached the tool as a non-String
                // value. Both spellings are genuinely missing.
                if (!args.has(field) || args.isNull(field)) {
                    missing.add(field)
                    continue
                }
                val raw = args.opt(field)
                // Only the truly-empty literal "" is rejected — NOT whitespace.
                // The earlier `.trim().isEmpty()` over-rejected legitimate payloads,
                // most notably file_edit with `new_string: "\n"` (replace a block
                // with a newline) or `old_string: "  "` (match consecutive spaces).
                // Both are valid edits, neither is stream corruption.
                //
                // And even "" is legal for whitelisted (tool, field) pairs:
                // file_edit.new_string == "" is the documented "delete old_string"
                // form, not a missing value. [T-preflight-empty-string-allowed]
                if (raw is String && raw.isEmpty() &&
                    !preflightEmptyStringAllowed(name, field)
                ) {
                    missing.add(field)
                }
            }
            if (missing.isNotEmpty()) {
                return "Tool '$name' is missing required parameter(s): ${missing.joinToString(", ")}."
            }
            return null
        }
        // [T-android-stream-flush-dualpath] Newline fast-path thresholds (iOS parity).
        private const val NEWLINE_FLUSH_MIN_CHARS = 50
        private const val NEWLINE_FLUSH_MAX_LEN = 5_000
        // [T-android-larky-longsession-followup] see uiMessages / hasOlderMessages.
        /** Tail window size used by [uiMessages] when a session exceeds it. */
        const val INITIAL_VISIBLE_MESSAGE_CAP: Int = 200
        /** Each "load older" tap grows the cap by this many messages. */
        const val VISIBLE_MESSAGE_CAP_STEP: Int = 100
        const val MAX_LOADED_MESSAGE_WINDOW: Int = 400
        internal const val MAX_AGENT_HISTORY_MESSAGES: Int = 400
        /**
         * Sessions with this many or fewer messages bypass the windowing
         * machinery entirely — the derived `uiMessages` returns the same
         * list reference as `messages`, so Compose sees identity-equal
         * snapshots and the existing flat/stream pipeline is untouched.
         */
        const val LONG_SESSION_THRESHOLD: Int = 300
        // T258: tool block statuses with no committed tool_result. retryLast()
        // drops blocks in any of these states because they would orphan the
        // assistant tool_use entry on retry (the API rejects unmatched
        // tool_use_ids). SUCCESS / FAILED / TIMEOUT / CANCELLED all have a
        // matching tool_result row already persisted and survive the retry.
        internal val IN_FLIGHT_TOOL_STATUSES = setOf(
            ToolBlockStatus.STREAMING,
            ToolBlockStatus.PENDING,
            ToolBlockStatus.RUNNING,
        )
        // T145 phase 1: dedicated tag so the streaming-state debug pipeline
        // can be filtered with `adb logcat -s Minis.ChatVMStream:D`.
        // Removed once the retry-state regression is rooted out.
        internal const val TAG_STREAM = "ChatVMStream"
        /**
         * Hard ceiling on agent loop iterations within a single user turn.
         * Backstop against runaway tool-call cycles that slip past
         * [ToolLoopDetector] (e.g. visited args/results vary just enough to
         * dodge the global circuit breaker). On reaching the limit the loop
         * finalizes as resumable — see runAgentLoop's tail and
         * [finalizeAtTurnLimit] — so the user gets an inline explanation +
         * Resume button rather than a silently stuck "thinking" indicator.
         * Mirrors iOS AIChatViewModel.maxAgentTurns.
         */
        internal const val MAX_AGENT_TURNS = 200
        private const val MIN_MAX_TOKENS = 1024
        /**
         * Hard ceiling on max_tokens we ever send to a provider, regardless
         * of what the model itself claims. Some models advertise 128K+
         * output windows that in practice produce wandering, low-signal
         * responses and burn through context budget; cap so a single turn
         * can't run away. Mirrors iOS AIChatViewModel.globalMaxTokensCeiling.
         * [T-android-global-max-tokens-128k] Raised 64K → 128K (iOS 8a401ab6):
         * 64K clipped newer large-output models AND the number-budget thinking
         * tiers whose budget is carved out of max_tokens (Anthropic legacy
         * high/xhigh/max, Qwen thinking_budget — DashScope clamps it strictly
         * below max_completion_tokens). Raising only lifts the upper bound —
         * the value is still clamped by the model's own maxOutputTokens and
         * the remaining context window in dynamicMaxTokens().
         */
        private const val GLOBAL_MAX_TOKENS_CEILING = 128_000
        /**
         * Sentinel prefix on synthetic tool_result output marking
         * user-cancelled calls. Aligned with iOS
         * AIChatViewModel.swift:5163 so a session sync'd between
         * platforms shows the same `<system-reminder>…` text the model
         * sees on the next API call (rather than "[cancelled by user]"
         * which iOS would treat as opaque tool output).
         */
        const val CANCELLED_MARKER =
            "<system-reminder>The user cancelled this operation. The returned result may be incomplete.</system-reminder>"

        /**
         * Pre-T13 cancelled marker. Kept only so [toLLMMessage]'s
         * tool-block restore can still recognise rows persisted by
         * earlier app versions and surface them as CANCELLED instead
         * of FAILED. Never emitted by this version.
         */
        private const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"
        /**
         * Number of recent user-text turns kept verbatim as inference anchors when
         * compactAll runs. The summary stands in for everything older; the LLM
         * still sees the last N user-text turns + their assistant replies + tool
         * I/O so it can answer follow-ups that need verbatim detail rather than
         * the summary's distilled form. Mirrors iOS `compactKeepRecentUserTurns`.
         */
        internal const val COMPACT_KEEP_RECENT_USER_TURNS = 3
        /// Max per-tool-call retained `accumulated` JSON snapshots from
        /// `ToolInputDelta`. Drained on preflight failure for diagnosis.
        internal const val TOOL_INPUT_CHUNK_RING_MAX = 2
        /** Auto-retry backoff schedule (seconds). Mirrors iOS retryDelays, scaled to task spec: 1s → 2s → 4s. */
        internal val AUTO_RETRY_DELAYS_SEC = com.openminis.app.provider.HttpRetryAfter.DELAYS_SEC

        /**
         * Factory for use with `viewModel(factory = ...)`. Binds the ChatViewModel
         * to a NavBackStackEntry's ViewModelStore so the streaming job survives
         * configuration changes (rotation) and re-entering the chat screen while
         * the backstack entry is alive.
         */
        fun factory(
            sessionId: String,
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository,
            appContext: Context,
            memoryRepository: MemoryRepository?,
            skillRepository: com.openminis.app.data.repository.SkillRepository?,
            mcpRepository: com.openminis.app.data.repository.MCPRepository? = null,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    sessionId = ChatViewModelStore.resolvePersistedId(sessionId),
                    chatRepository = chatRepository,
                    providerRepository = providerRepository,
                    context = appContext,
                    memoryRepository = memoryRepository,
                    skillRepository = skillRepository,
                    mcpRepository = mcpRepository,
                ).also { ChatViewModelStore.register(sessionId, it) } as T
            }
        }
    }

    internal val mediaStore = com.openminis.app.data.storage.MediaStore(context)

    internal val _messages = SnapshotMutableStateFlow<List<ChatMessage>>(
        emptyList(),
        ::snapshotChatMessages,
    )
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // ── Long-session window cap ────────────────────────────────────────
    //
    // [T-android-larky-longsession-followup] On sessions with hundreds of
    // ChatMessage entries (Larky's 612-row monster, totalChars ~1.9MB)
    // feeding the whole list into the LazyColumn pipeline caused cascading
    // main-thread cost: per-frame regex/matcher churn from streaming-side
    // detection, repeated AnnotatedString construction for re-anchored
    // items, and LRU thrash on the markdown caches. The list-virtualization
    // is fine on its own, but the streaming pipeline (combine + sample) and
    // the FlatChat flattening both walk the full list every tick.
    //
    // Strategy: keep `_messages` as the canonical full list (every legacy
    // caller — compact / fork / regenerate / agentHistory / send pipeline —
    // still sees the whole thing) and expose a derived `uiMessages` that
    // takes the TAIL N. ChatScreen consumes `uiMessages`; everything else
    // keeps reading `messages`. When the list is short (<= cap) the derived
    // value IS the source list (same reference), so this is zero-overhead
    // for normal sessions.
    //
    // Users scroll up through the windowed slice; when they reach the top
    // of the tail-window AND older messages exist, [loadOlderMessages]
    // bumps the cap by [WINDOW_STEP] and the derived flow re-emits with
    // the older slice included.
    //
    // Reset on session load (different sessionId) is wired in loadSession.

    internal val _visibleMessageCap = MutableStateFlow(INITIAL_VISIBLE_MESSAGE_CAP)

    // Database window state. The canonical UI list contains only this loaded
    // window; older rows are fetched on demand instead of retaining the whole
    // session and its parsed LLM representation in memory.
    internal var loadedMessageOffset = 0
    internal var loadedMessageTotal = 0
    internal var loadingOlderMessages = false

    /**
     * Index (in DB order from the session start) of the first row that
     * [agentHistory] currently covers after the windowed cold-open parse.
     * Rows before it exist in `_messages` only when the user loaded older
     * UI history; their LLM forms are parsed on demand before send.
     */
    internal var llmHistoryStartOffset = 0
    /**
     * Current tail cap. Reflective via [uiMessages]; bump with
     * [loadOlderMessages] when the user scrolls past the windowed top.
     * Reset to [INITIAL_VISIBLE_MESSAGE_CAP] each time [loadSession]
     * (re)mounts a session — different sessions shouldn't inherit each
     * other's caps.
     */
    val visibleMessageCap: StateFlow<Int> = _visibleMessageCap.asStateFlow()

    /**
     * Tail-windowed view of [messages] for ChatScreen's LazyColumn. For
     * sessions with `count <= LONG_SESSION_THRESHOLD` or `count <= cap`
     * this returns the EXACT SAME list reference as `_messages.value` —
     * Compose / collectAsState gets identity-equal snapshots, no extra
     * allocation, no behavior change for normal sessions.
     */
    val uiMessages: StateFlow<List<ChatMessage>> =
        kotlinx.coroutines.flow.combine(_messages, _visibleMessageCap) { raw, cap ->
            // [T-bridge-message-ui-leak-android] Single UI-collection sink for
            // EVERY path that pushes messages to the list (loadSession, live
            // stream append, compact rebuild, snapshot reload, sync refresh…).
            // Filter the internal role-alternation bridge here so it can never
            // surface as a chat bubble regardless of which path produced it —
            // the Android analog of iOS applySnapshot (T-bridge-message-ui-leak).
            // Today the bridge lives in agentHistory only (never in _messages),
            // so this is defensive; it guards against a future refactor routing
            // the bridge into _messages. Only allocate a new list when a bridge
            // is actually present, keeping the identity-equal fast path intact.
            val full = if (raw.any { it.isInternalBridge }) raw.filterNot { it.isInternalBridge } else raw
            if (full.size <= LONG_SESSION_THRESHOLD || full.size <= cap) full
            // [T-android-uimessages-sublist-cme] `.toList()` is defensive
            // hardening, NOT a proven fix for the reported crash. Read the
            // measured facts before changing it back.
            //
            // `subList` returns a live VIEW sharing the parent's modCount, and
            // emitting it puts that view in Compose state (ChatScreen collects
            // `uiMessages`). That is a latent hazard worth closing on its own.
            //
            // MEASURED, so nobody re-derives it: a SubList only throws
            // ConcurrentModificationException when its PARENT is structurally
            // mutated IN PLACE (add/removeAt/clear). Every write here is
            // `_messages.value = <new list>` via `+` / filterNot / map, and all
            // of those ALLOCATE A FRESH ArrayList rather than mutating — so the
            // old view's parent is never touched and no CME results. Verified on
            // a JVM probe (`base + x`, `filterNot`, `map` all return a new
            // java.util.ArrayList; comparing a stale window after such a write
            // returned OK, not CME).
            //
            // Also verified end-to-end on device (Pixel 4a, build with this
            // `.toList()` deliberately REVERTED): create a multi-turn session,
            // long-press a middle user message → 编辑 → send. `truncateBeforeEdit`
            // provably ran (8 messages → 4), storing a live SubList as
            // `_messages.value`, and a further message was sent — NO crash. The
            // next `+` copies the SubList back into a plain ArrayList, so the
            // view stops being the state before anything can invalidate it.
            //
            // The user's crash (ArrayList$SubList.equals, main thread, realme
            // RMX5010 / Android 16, 2026-08-10/11/12) therefore still has an
            // UNIDENTIFIED trigger: something must mutate a subList's parent in
            // place. That site was not found in ChatViewModel; look next at
            // ChatFlatItems / ChatScreen and at any long-lived mutableListOf
            // whose contents reach Compose.
            //
            // Keep the copy regardless: the window is a snapshot by definition,
            // so copying is also the correct semantics. Only long sessions past
            // the cap allocate; the common path above still returns `raw`
            // unchanged and stays identity-equal.
            else full.subList(full.size - cap, full.size).toList()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyList(),
        )

    /**
     * Whether the current session has older messages above the window.
     * ChatScreen uses this to show / hide the "Load older messages" header
     * pill on the LazyColumn.
     */
    private val _hasOlderMessages = MutableStateFlow(false)
    val hasOlderMessages: StateFlow<Boolean> = _hasOlderMessages.asStateFlow()

    internal fun refreshHasOlderMessages() {
        val loadedVisible = _messages.value.count { !it.isInternalBridge }
        _hasOlderMessages.value = loadedMessageOffset > 0 || loadedVisible > _visibleMessageCap.value
    }

    /**
     * Reveal one bounded window above the current UI tail. Already-loaded rows
     * are exposed first; only after that in-memory prefix is exhausted do we
     * query another database page. A fetched page also grows the visible cap,
     * otherwise prepending it would leave the same tail on screen and make
     * persisted history look permanently missing.
     */
    fun loadOlderMessages() {
        if (loadingOlderMessages) return

        val loadedVisible = _messages.value.count { !it.isInternalBridge }
        val plan = ChatHistoryWindow.planOlderLoad(
            loadedVisible = loadedVisible,
            visibleCap = _visibleMessageCap.value,
            loadedOffset = loadedMessageOffset,
            step = VISIBLE_MESSAGE_CAP_STEP,
        )
        val newOffset = plan.fetchOffset
        if (newOffset == null) {
            _visibleMessageCap.value = plan.nextVisibleCap
            refreshHasOlderMessages()
            return
        }

        loadingOlderMessages = true
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    val rows = ArrayList<com.openminis.app.data.db.MessageEntity>(
                        loadedMessageOffset - newOffset,
                    )
                    var offset = newOffset
                    while (offset < loadedMessageOffset) {
                        val chunk = chatRepository.dao.loadMessagesPage(
                            sessionId,
                            offset,
                            minOf(50, loadedMessageOffset - offset),
                        )
                        if (chunk.isEmpty()) break
                        rows.addAll(chunk)
                        offset += chunk.size
                    }
                    rows
                }
                if (page.isNotEmpty()) {
                    val older = withContext(Dispatchers.IO) { page.toChatMessages() }
                    val combined = older + _messages.value
                    val retained = combined.take(MAX_LOADED_MESSAGE_WINDOW)
                    _messages.value = retained
                    loadedMessageOffset = newOffset
                    _visibleMessageCap.value = minOf(
                        plan.nextVisibleCap,
                        retained.count { !it.isInternalBridge },
                    )
                }
                refreshHasOlderMessages()
            } finally {
                loadingOlderMessages = false
            }
        }
    }

    /**
     * Streaming side-channel — see [StreamingDelta]. During a live agent
     * turn, [updateAssistantMessage] writes delta-bearing fields here
     * INSTEAD of mutating the messages list. This isolates per-token
     * updates from ChatScreen's top-level recompose scope (the 8980-line
     * mega-composable was being walked at full slot-table cost on every
     * token, costing ~94 ms per recompose). Top-level subscribers
     * (`messages.any/.associate/.isNotEmpty/.lastOrNull`) only see a new
     * list reference at turn *boundaries* — at start (message added) and
     * end (final content synced back).
     *
     * Renderers that need streaming content (AssistantText, Thinking,
     * tool pills, etc.) read this flow per-item inside their composable
     * scope so Compose's stable-skip restricts the recompose blast radius
     * to that one item.
     *
     * The map is keyed by the assistant message id; absent ⇒ no live
     * stream (turn either hasn't started or has already flushed).
     */
    internal val _streamingById = SnapshotMutableStateFlow<Map<String, StreamingDelta>>(
        emptyMap(),
        ::snapshotStreamingDeltas,
    )
    val streamingById: StateFlow<Map<String, StreamingDelta>> = _streamingById.asStateFlow()

    private val streamSession = StreamSessionController(
        scope = viewModelScope,
        messages = _messages,
        streamingById = _streamingById,
        newlineFlushMinChars = NEWLINE_FLUSH_MIN_CHARS,
        newlineFlushMaxLen = NEWLINE_FLUSH_MAX_LEN,
    )

    internal fun clearStreamFlushState(id: String) = streamSession.clearStreamFlushState(id)
    private fun clearAllStreamFlushStates() = streamSession.clearAllStreamFlushStates()
    internal fun retainStreamFlushStates(keptIds: Set<String>) =
        streamSession.retainStreamFlushStates(keptIds)

    /**
     * Composer draft. Owned by VM so it survives navigation (e.g. push EnvVars
     * and pop back) — `ChatViewModelStore` keeps the VM alive across screen
     * pushes, but `remember { … }` inside `ChatScreen` does not. Mirrors iOS
     * `AIChatView` which binds against `vm.inputText`.
     */
    internal val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] One-shot caret position the
     * composer should apply on the NEXT inputText emission, mirroring iOS
     * `pendingCaret`. Null means "no override — caret to end" (the existing
     * default). Set when the slash flow prepends "/ " (caret lands at 1, right
     * after the slash, so typing filters the menu) or inserts "/<skill> "
     * (caret after the prefix, before the preserved body). The composer reads
     * it once in its inputText LaunchedEffect and clears it via [consumePendingCaret].
     */
    internal val _pendingCaret = MutableStateFlow<Int?>(null)
    val pendingCaret: StateFlow<Int?> = _pendingCaret.asStateFlow()

    /** Read-and-clear the pending caret so it applies exactly once. */
    fun consumePendingCaret(): Int? {
        val c = _pendingCaret.value
        _pendingCaret.value = null
        return c
    }

    /**
     * Chat list scroll state. Hoisted onto the VM so it survives ChatScreen
     * recomposition / disposal triggered by forward navigation (file preview,
     * env-vars push, etc.). `rememberSaveable` was insufficient because the
     * surrounding composition is re-entered on pop and the SaveableStateHolder
     * scope doesn't always restore in time — keeping the LazyListState on the
     * session-scoped VM (kept alive by ChatViewModelStore) guarantees both the
     * firstVisibleItemIndex/offset and the layoutInfo cache survive intact, so
     * the LazyColumn paints its previous viewport on the first frame instead of
     * remeasuring from index 0 (white flash).
     */
    val listState: LazyListState = LazyListState(0, 0)

    fun setInputText(value: String) {
        _inputText.value = value
    }

    /**
     * [T-selection-add-to-input] Append [snippet] to the chat composer
     * with a single trailing space:
     *   - composer empty → `"<snippet> "`
     *   - composer non-empty → `"<existing><separator><snippet> "`
     *
     * [T-android-append-to-input-eats-draft] Trim the incoming SNIPPET only.
     * The old code called `current.trimEnd()` and assigned that trimmed copy
     * back, so "Add to input" silently rewrote the user's existing draft: a
     * deliberate trailing newline (a paragraph break they had just typed) was
     * swallowed and replaced by the separator space. The draft is the user's
     * own text and must come back byte-for-byte.
     *
     * The emptiness test still runs on a trimmed view — a draft of only
     * whitespace should be treated as empty rather than producing a leading
     * blank run — but that trimmed value drives the DECISION only, never the
     * assignment. Mirrors iOS `e6c0ace6a`.
     */
    fun appendToInputText(snippet: String) {
        val joined = joinDraftWithSnippet(_inputText.value, snippet) ?: return
        _inputText.value = joined
    }

    internal val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    /**
     * T261: tool detail sheet visibility, persistent across LazyColumn
     * recomposition / item disposal so a streaming tool's sheet doesn't
     * snap shut when its pill scrolls out of viewport. Stable key = tool
     * block id (server-assigned tool_use_id). Null = closed.
     *
     * Lifecycle: opened by [openToolDetail], closed by [closeToolDetail]
     * (user dismiss) or by ChatScreen's existence-guard LaunchedEffect when
     * the underlying block is gone (T258 retry-preserve drops in-flight
     * tools, session switch, etc.). Not persisted to disk — sheet is a
     * transient UI state.
     */
    internal val _selectedToolDetailId = MutableStateFlow<String?>(null)
    val selectedToolDetailId: StateFlow<String?> = _selectedToolDetailId.asStateFlow()

    // [T-android-split-chat] openToolDetail / closeToolDetail moved to ChatViewModelUiStateExt.kt.

    /**
     * True when the user cancelled mid-turn and the conversation can be
     * resumed by re-prompting the model to pick up where it left off.
     * Mirrors iOS AIChatViewModel.canResume. Cleared by [resume], by the
     * next real [sendMessage], or on error.
     */
    internal val _canResume = MutableStateFlow(false)
    val canResume: StateFlow<Boolean> = _canResume.asStateFlow()

    /**
     * [T-android-group-pause-badge-restamp] Marks the ONE `_canResume = true`
     * assignment that is a RE-DETECTION of an interruption that already
     * happened (loadSession finding a still-unfinished DB tail, possibly days
     * old) rather than a live new interruption. Read by the badge collector to
     * decide whether the badge's entry timestamp may be overwritten — see the
     * collector's comment for why the badge must NOT be re-stamped there.
     *
     * Why a COUNTER and not a plain boolean set-then-cleared around the
     * assignment: the collector is an async `collect` on a StateFlow, running
     * on its own coroutine. A boolean cleared right after the assignment is
     * very likely already `false` by the time the collector is resumed and
     * observes the `true`, so the annotation would be lost and the stale badge
     * re-stamped anyway — the exact bug being fixed. Instead the flag is
     * STICKY: the detecting site raises it BEFORE assigning and never clears
     * it; the collector clears it only once it has actually consumed the
     * emission it annotates. The generation counter makes that consumption
     * unambiguous even if several loads race — the collector compares the
     * value it latched against the current one.
     *
     * StateFlow conflation is also handled by this shape: if `_canResume` is
     * already `true`, the re-detection assignment emits nothing at all, so the
     * collector never runs and never re-stamps — which is the desired outcome
     * (no push, no stamp change). The pending mark simply stays raised and is
     * consumed by the next `true` emission, which for this VM instance can
     * only come from the same load path re-running (every live-interruption
     * site is preceded by a `false`, i.e. by a real run that clears it — see
     * `markLiveInterruption`).
     */
    @Volatile private var redetectingInterruptedTailGen: Long = 0L
    @Volatile private var consumedRedetectGen: Long = 0L

    /**
     * Raise the re-detection mark for the next `_canResume = true` emission.
     * Mirrors iOS `isRedetectingInterruptedTail = true` at the +Persistence
     * detection site.
     */
    internal fun markRedetectingInterruptedTail() {
        redetectingInterruptedTailGen += 1
    }

    /**
     * Cancel any pending re-detection mark. Called by every LIVE interruption
     * path before it sets `_canResume = true`, so an unconsumed mark left over
     * from a load (e.g. the load found the tail interrupted while `_canResume`
     * was already true, so nothing was emitted) can never leak onto a genuine
     * new interruption and suppress its re-stamp.
     */
    internal fun markLiveInterruption() {
        consumedRedetectGen = redetectingInterruptedTailGen
    }

    /**
     * T187: id of a user message currently being re-edited via the
     * long-press → Edit context menu. While non-null, the composer
     * shows an "Exit Edit Mode" pill, and the next sendMessage()
     * call truncates the conversation from this message (inclusive)
     * before persisting the new content as a fresh user turn.
     * Mirrors iOS AIChatViewModel.editingMessageIndex.
     */
    internal val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    internal val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    internal val _modelName = MutableStateFlow("")
    override val modelName: StateFlow<String> = _modelName.asStateFlow()

    /** T201: gate the init-time `config.collect` re-resolver so the StateFlow's
     *  replay cache can't beat [loadSession] to setting `_modelName`. Without
     *  this, opening a session that previously fell back mid-run flashes the
     *  default model name for one frame before the persisted binding settles. */
    internal val sessionLoaded = MutableStateFlow(false)

    internal val _sessionTitle = MutableStateFlow("New Chat")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /** T-chat-title-pill: category drives the icon shown in the sticky title
     *  pill (mirrors SessionRow's categoryStyle lookup). Null on draft sessions
     *  and until LLM title-generation tags the session. */
    internal val _sessionCategory = MutableStateFlow<String?>(null)
    val sessionCategory: StateFlow<String?> = _sessionCategory.asStateFlow()

    internal val _attachments = MutableStateFlow<List<InputAttachment>>(emptyList())
    val attachments: StateFlow<List<InputAttachment>> = _attachments.asStateFlow()

    override fun addAttachment(attachment: InputAttachment) {
        _attachments.value = _attachments.value + attachment
    }

    /**
     * [T-android-paste-placeholder] Long pasted blocks folded out of the
     * composer, keyed by the `[Pasted#N]` marker left in its place.
     *
     * Scoped to this ViewModel, so it is per-session by construction: the
     * store hands each session its own instance, and switching chats cannot
     * leak an id from one buffer into another's placeholders. Memory-only —
     * see [PastedText] for why persisting it would be worse than not.
     */
    internal val _pastedTexts = MutableStateFlow<List<PastedText>>(emptyList())
    val pastedTexts: StateFlow<List<PastedText>> = _pastedTexts.asStateFlow()

    /**
     * Next placeholder number. Monotonic for the session's lifetime and never
     * reused, even after entries are consumed or deleted: a recycled id would
     * let a stale marker left in the draft ("I pasted, deleted the chip, then
     * pasted again") silently expand to the WRONG text. Numbers are cheap.
     */
    private var nextPasteId: Int = 1

    /**
     * [T-android-paste-placeholder] Buffer [text], returning the marker to put
     * in the composer in its place.
     */
    fun stashPastedText(text: String): String {
        val entry = PastedText(id = nextPasteId++, text = text)
        _pastedTexts.value = _pastedTexts.value + entry
        AppLogger.info(TAG, "[Paste] stashed #${entry.id} (${text.length} chars)")
        return entry.placeholder
    }

    /**
     * [T-android-paste-oversize] Turn a very large paste into a real `.txt`
     * document attachment instead of a placeholder.
     *
     * Past [PASTE_AS_FILE_THRESHOLD] the user is effectively attaching a
     * document, and the placeholder path is the wrong shape for it: the block
     * would sit in memory for the whole draft and then have to be written out
     * anyway. Routing it through the ordinary attachment pipeline instead means
     * it inherits preview, removal, the `<user-attached-files>` inventory the
     * model can `cat`, and the same upload handling as a file the user picked —
     * none of which the buffer offers.
     *
     * The bytes go to `cacheDir/pasted_text`, matching where
     * [addAttachmentFromStagedShare] puts share-inbound copies: the composer may
     * hold this for a long time before send, so it must not live anywhere the
     * system might reclaim mid-draft.
     *
     * Returns null if the write fails, and the caller then leaves the paste in
     * the text field verbatim — worse-looking than a chip, but nothing is lost.
     */
    fun stashPastedTextAsFile(text: String): InputAttachment? {
        val dir = java.io.File(context.cacheDir, "pasted_text").apply { mkdirs() }
        // Timestamp + short uuid: sorts chronologically in a file listing and
        // cannot collide when two pastes land in the same millisecond.
        val stamp = IsoTime.formatCompactSeconds()
        val name = "Pasted_$stamp-${java.util.UUID.randomUUID().toString().take(8)}.txt"
        val file = java.io.File(dir, name)
        return try {
            file.writeText(text)
            val attachment = InputAttachment(
                fileName = name,
                uri = android.net.Uri.fromFile(file),
                mimeType = "text/plain",
                kind = com.openminis.app.session.InputAttachment.Kind.DOCUMENT,
            )
            addAttachment(attachment)
            AppLogger.info(
                TAG,
                "[Paste] oversize paste -> file attachment $name (${text.length} chars)",
            )
            attachment
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Paste] failed to write oversize paste: ${e.message}")
            null
        }
    }

    /**
     * Drop one buffered entry (the chip's delete button). The caller is
     * responsible for also removing the marker from the composer text — see
     * ChatScreen, which does both in one edit so the two never disagree.
     */
    fun removePastedText(id: Int) {
        _pastedTexts.value = _pastedTexts.value.filterNot { it.id == id }
    }

    /**
     * One-shot composer-side image-budget events (T-imgsize). Emitted by
     * [prepareUserAttachments] when [ImageBudget.applyMessageBudget] either
     * re-encodes oversize local attachments or drops images that would push
     * the message over the cumulative cap. ChatScreen collects this flow
     * and surfaces a localized Snackbar — provider-boundary compression
     * (history images) does not emit here to keep history-replay silent.
     */
    internal val _imageBudgetEvent = MutableSharedFlow<ImageBudget.BudgetResult>(extraBufferCapacity = 4)
    val imageBudgetEvent: SharedFlow<ImageBudget.BudgetResult> = _imageBudgetEvent.asSharedFlow()

    /**
     * Request-level image-budget events (T-request-imgsize). Emitted by
     * [applyRequestImageBudget] when the cumulative history image payload
     * exceeds [ImageBudget.MAX_REQUEST_BYTES] and older images had to be
     * elided to text placeholders. Distinct from [imageBudgetEvent] so the
     * UI Snackbar can show a different message ("older images compacted")
     * and the two events don't race.
     */
    internal val _requestBudgetEvent = MutableSharedFlow<ImageBudget.RequestBudgetPlan>(extraBufferCapacity = 4)
    val requestBudgetEvent: SharedFlow<ImageBudget.RequestBudgetPlan> = _requestBudgetEvent.asSharedFlow()

    /**
     * [T-android-tool-autoscroll] Fire-and-forget edge events that ask the
     * ChatScreen to scroll the LazyColumn to the visual bottom (index 0 under
     * reverseLayout). Distinct from the streaming-auto-follow collector — that
     * pipeline needs growth ticks to advance its distinctUntilChanged tuple,
     * but agent-loop START events (sendMessage, resume / "Continue", retry)
     * produce only a brief thinking placeholder before any content streams.
     * Without an explicit edge signal, the placeholder + composer interaction
     * area sits behind the input bar until the model's first token arrives
     * and the regular auto-follow finally fires. Each ViewModel entry that
     * starts a fresh agent-loop turn emits to this flow.
     */
    internal val _forceScrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val forceScrollToBottom: SharedFlow<Unit> = _forceScrollToBottom.asSharedFlow()

    /**
     * [T-android-readaloud-stop-stale] Emitted on the FIRST text delta of a new
     * reply, so any Read Aloud still playing from the previous reply is
     * stopped before the new content starts arriving.
     *
     * Deferred to the first delta rather than fired from send(): the old reply
     * should keep playing while the model is still thinking, and only yield
     * once there is actually new text to supersede it. Mirrors iOS
     * `d2fdc784f`, which sets `hasStoppedPreviousTTS` at the same point.
     *
     * The player is screen-scoped (ChatScreen owns it), so this is a signal
     * rather than a direct call — the ViewModel has no reference to it.
     */
    internal val _stopStaleReadAloud = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val stopStaleReadAloud: SharedFlow<Unit> = _stopStaleReadAloud.asSharedFlow()

    internal val _availableGroups = MutableStateFlow<List<ModelGroup>>(emptyList())
    val availableGroups: StateFlow<List<ModelGroup>> = _availableGroups.asStateFlow()

    internal val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    internal val _selectedGroupName = MutableStateFlow("")
    val selectedGroupName: StateFlow<String> = _selectedGroupName.asStateFlow()

    internal val _providerName = MutableStateFlow("")
    val providerName: StateFlow<String> = _providerName.asStateFlow()

    /** Incremented when a model fallback occurs — UI observes this to flash the model capsule. */
    internal val _fallbackTrigger = MutableStateFlow(0)
    val fallbackTrigger: StateFlow<Int> = _fallbackTrigger.asStateFlow()

    internal val _activeEntryId = MutableStateFlow<String?>(null)
    override val activeEntryId: StateFlow<String?> = _activeEntryId.asStateFlow()

    /** Prompts enqueued while the agent loop is running. Drained after the loop finishes. */
    internal val _promptQueue = MutableStateFlow<List<QueuedPrompt>>(emptyList())
    val promptQueue: StateFlow<List<QueuedPrompt>> = _promptQueue.asStateFlow()

    /**
     * Input-token count reported by the most recent API call, used by
     * [ContextPolicy] as the "estimated tokens" gate before sending. Zero
     * means either we've never called the model or the provider didn't return
     * a usage payload — in which case we treat the turn as low-pressure.
     */
    internal val _lastTurnContextTokens = MutableStateFlow(0)
    val lastTurnContextTokens: StateFlow<Int> = _lastTurnContextTokens.asStateFlow()

    /**
     * [T-compact-estimate-fallback] Cheap token estimate of the in-memory
     * agent history, used ONLY as a lower-bound floor when the last usage
     * chunk is missing or stale (`_lastTurnContextTokens` is refreshed by a
     * COMPLETED API call, so a tool-heavy stretch that has not produced a
     * new usage value yet would otherwise look "empty" to ContextPolicy and
     * skip compaction entirely). ASCII costs ~0.25 tok/char, CJK ~0.6 —
     * the classic len/4 estimator is ~4x off for CJK and would let long
     * Chinese sessions sail past the threshold.
     */
    private fun estimateAgentHistoryTokens(): Int {
        var total = 0L
        for (msg in agentHistory) {
            total += estimateMixedTokens(msg.content)
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> total += estimateMixedTokens(part.text)
                    is AgentContentPart.ToolUse -> total += estimateMixedTokens(part.input.toString())
                    is AgentContentPart.ToolResult -> total += estimateMixedTokens(part.content)
                    is AgentContentPart.ImageData -> total += 1_500
                }
            }
        }
        return total.toInt()
    }

    private fun estimateMixedTokens(text: String): Long {
        if (text.isEmpty()) return 0
        var ascii = 0
        var other = 0
        for (c in text) if (c.code < 128) ascii++ else other++
        return ascii / 4L + other * 3L / 5L
    }

    /** max(real usage, estimate) so a stale-but-real reading always wins. */
    internal fun contextTokensForPolicy(): Int =
        maxOf(_lastTurnContextTokens.value, estimateAgentHistoryTokens())

    /**
     * Latest compact summary for the current session, loaded from the DB on
     * [loadSession] and re-populated after [compactAll] finishes. When non-null,
     * [effectiveAgentHistory] prepends it as a `<context-summary>` user message
     * so the model sees a condensed recap of the turns we folded away while
     * keeping the full [agentHistory] on disk as an audit trail. Mirrors iOS
     * Phase-B compact semantics (summary synthesized at inference time, never
     * baked back into agentHistory).
     */
    internal val _compactSummary = MutableStateFlow<String?>(null)
    override val compactSummary: StateFlow<String?> = _compactSummary.asStateFlow()

    /**
     * Set each [buildSystemPrompt] from whether a personality body was
     * injected. [effectiveAgentHistory] then prefixes the latest user-text
     * turn so existing sessions don't keep the previous generic voice.
     */
    @Volatile
    internal var personaHistorySteering = false

    /** True when a compact-summary LLM call is in flight (UI disables further sends). */
    internal val _isCompacting = MutableStateFlow(false)
    override val isCompacting: StateFlow<Boolean> = _isCompacting.asStateFlow()

    /**
     * [T-android-compact-progress] Live progress of the in-flight compaction.
     *
     * Compaction could previously run for many minutes behind a single
     * unchanging "compacting" flag, which is indistinguishable from a hang —
     * the reported symptom was users staring at it for 15-20 minutes with no
     * way to tell whether it was working or wedged. This carries enough state
     * for the UI to show real movement: elapsed seconds, which segment of a
     * split is running, and how deep the split went.
     */
    data class CompactProgress(
        /** When the whole compaction started, for elapsed-time display. */
        val startedAtMs: Long,
        /** Recursion depth currently executing (0 = whole history, >0 = a split half). */
        val depth: Int = 0,
        /** Leaf LLM calls issued so far, across all segments. */
        val callsIssued: Int = 0,
        /** Total leaf calls allowed before the budget aborts the run. */
        val callBudget: Int = MAX_COMPACT_LLM_CALLS,
        /** Seconds the whole run is allowed before it is cancelled. */
        val timeoutSeconds: Int = 0,
        /** 1-based stage in the two-shot compact plan. */
        val stage: Int = 1,
        val stageBudget: Int = 2,
        /** session / fallback / session-retry */
        val stageKind: String = "session",
        val modelLabel: String = "",
        /** What happens if this stage fails. */
        val nextHint: String = "",
    )

    internal val _compactProgress = MutableStateFlow<CompactProgress?>(null)
    val compactProgress: StateFlow<CompactProgress?> = _compactProgress.asStateFlow()

    /**
     * Leaf LLM calls issued by the current compaction. Reset at the start of
     * each run; read/incremented from the split recursion, which can interleave
     * across suspension points, hence atomic.
     */
    internal val compactCallsIssued = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * The running compaction's job, so the UI can offer a Cancel affordance.
     * Cancelling routes through the same `finally` that clears the lock, so a
     * user-cancelled compaction leaves no state behind.
     */
    internal var compactJob: Job? = null

    /** Cancel an in-flight compaction. No-op when nothing is running. */
    fun cancelCompact() {
        val job = compactJob ?: return
        if (!job.isActive) return
        AppLogger.info(TAG, "[Compact] cancelled by user")
        job.cancel(CancellationException("compact cancelled by user"))
    }

    /** Current auto-retry attempt number (0 = not retrying, 1..MAX = nth retry in flight). */
    internal val _autoRetryAttempt = MutableStateFlow(0)
    val autoRetryAttempt: StateFlow<Int> = _autoRetryAttempt.asStateFlow()

    /** Seconds remaining in the current auto-retry countdown (0 = not counting down). */
    internal val _autoRetryCountdown = MutableStateFlow(0)
    val autoRetryCountdown: StateFlow<Int> = _autoRetryCountdown.asStateFlow()

    // [T-android-stale-streamjob-clears-isstreaming] @Volatile so cross-coroutine
    // reads (the orphaned previous streamJob's tail block running on a different
    // dispatcher) see the latest assignment. Without it, an old job's
    // `if (streamJob === thisJob)` guard could read a cached reference and
    // wrongly reset _isStreaming on the new live job — the exact race XIN hit
    // 2026-06-12 20:22:26 / 20:23:25 (cancel → resume → cancel → retry, where
    // the cancelled resume's finally fired ~2s after the new retry was already
    // streaming, hiding the Stop button while the new turn was live).
    @Volatile
    internal var streamJob: Job? = null
    internal var currentProvider: LLMProvider? = null
    internal var currentModel: LLMModel? = null

    /**
     * Does the CURRENTLY RESOLVED main model natively consume image pixels?
     *
     * Single source of truth for the three decisions that must agree: whether
     * `read_image` is exposed at all ([agentTools]), whether an attached image
     * is replaced by a Vision Group placeholder ([visionPlaceholderFor]), and
     * whether `read_image` returns pixels or routes through the Vision Group
     * ([executeReadImageTool]).
     *
     * [T-android-vision-native-check-misses-image_input] These three each used
     * to inline `inputModalities.map { it.lowercase() }.contains("image")`,
     * which only LOWERCASES. Provider APIs disagree on the spelling: OpenAI /
     * OpenRouter report "image_input", models.dev reports bare "image" (see
     * [normalizeModalityName]). So a model advertising "image_input" was read
     * as vision-capable by [hasImageInput] — which normalizes, and is what
     * `ProviderRepository.resolveVisionCandidates` filters Vision Group members
     * by — but as text-only here. A model that could see perfectly well would
     * therefore get its `read_image` result detoured through the Vision Group
     * and come back as a second-hand text description, exactly the complaint in
     * the 2026-08-28 report (which turned out to have a different cause).
     *
     * Reusing [hasImageInput] keeps this check and the Vision Group's member
     * filter on one definition, so the two can no longer disagree.
     */
    private val currentModelHasNativeVision: Boolean
        get() = currentModel?.hasImageInput == true

    /**
     * [T-token-attribution-snapshot] Which model actually served the turn being
     * persisted, for the message's attribution columns.
     *
     * Built from `currentModel` + `_activeEntryId` — the live request context —
     * and deliberately NOT from the session row. Automatic failover rewrites
     * `sessions.model_id` mid-turn (see the fallback path that reassigns
     * `_activeEntryId` / `currentModel` when a candidate fails), so a session
     * read at persist time can name a model that never produced this message.
     * Both fields here are updated by that same fallback path, so they always
     * describe the model that actually responded.
     */
    internal fun currentModelSnapshot(): com.openminis.app.data.model.ModelAttributionSnapshot? {
        val model = currentModel ?: return null
        val entry = _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }
        val instance = entry?.let { providerRepository.instance(it.providerInstanceId) }
        return com.openminis.app.data.model.ModelAttributionSnapshot(
            modelId = model.id,
            displayName = model.displayName,
            // `.name` is the enum's stable rawValue, never the localized
            // displayName — grouping on display strings is what produced the
            // duplicate "Google" / "Gemini" / "Google Gemini" sections.
            providerTypeRaw = instance?.providerType?.name ?: "",
            providerInstanceId = entry?.providerInstanceId,
        )
    }

    /** Structured agent history for the agent loop (contentParts-based). */
    internal val agentHistory = mutableListOf<LLMMessage>()

    /**
     * All agent tool definitions, recomputed on each read so the memory
     * toggle gate (see [_memoryEnabled]) takes effect immediately when
     * the user flips /memory mid-session without forcing a VM rebuild.
     * The cost is negligible — [AgentTools.makeAgentTools] just builds a
     * fixed list of definition objects, no I/O.
     */
    // [T-android-agent-tools-memo] agentTools used to be a bare computed
    // property, rebuilding every tool definition + JSON schema + the plugin
    // registry scan on EVERY access (3 live access sites: per stream attempt,
    // per tool execution, per tool preflight — 21+ rebuilds in a 10-tool turn).
    // The memo below invalidates on the exact inputs that shape the list:
    // the four exposure flags plus the plugin store's change stamp.
    private var agentToolsMemo: List<AgentToolDefinition>? = null
    private var agentToolsMemoStamp: Long = Long.MIN_VALUE

    internal val agentTools: List<AgentToolDefinition>
        get() {
            val stamp = (if (currentModelHasNativeVision) 1L else 0L) or
                (if (com.openminis.app.tools.VisionGroupResolver.isConfigured(
                        providerRepository, context,
                    )
                ) 2L else 0L) or
                (if (_memoryEnabled.value) 4L else 0L) or
                (if (multiAgentSettings.enabled.value) 8L else 0L) or
                (com.openminis.app.plugins.OnlinePluginStore.toolDefinitionsStamp(context) shl 4)
            agentToolsMemo?.takeIf { agentToolsMemoStamp == stamp }?.let { return it }
            val built = AgentTools.makeAgentTools(
                // [T-android-vision-group / GH#182] The main model's own vision
                // capability. When false but a Vision Group is configured, read_image
                // is still exposed and routes through the group (see
                // executeReadImageTool). Note pre-vision-group Android always passed
                // the default `true` here, so read_image was already always exposed;
                // threading the real flag lets a text-only model without a Vision
                // Group correctly LOSE the tool (iOS parity), while a configured
                // Vision Group keeps it.
                supportsImageInput = currentModelHasNativeVision,
                visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                    providerRepository, context,
                ),
                memoryEnabled = _memoryEnabled.value,
                subAgentEnabled = multiAgentSettings.enabled.value,
                codeGraphEnabled = true,
            ) + com.openminis.app.plugins.OnlinePluginStore.toolDefinitions(context)
            agentToolsMemo = built
            agentToolsMemoStamp = stamp
            return built
        }

    /**
     * Per-session loop detector. Reset alongside [agentHistory] whenever the
     * conversation is rewound (edit/regenerate) so a stale tool-call window
     * can't bleed warnings into a fresh prompt.
     */
    internal val toolLoopDetector = ToolLoopDetector()

    internal val multiAgentSettings: MultiAgentSettingsRepository
        get() = (context.applicationContext as MinisApp).multiAgentSettingsRepository

    internal val subAgentRoundRobin = AtomicInteger(0)
    internal val subAgentDepth = AtomicInteger(0)

    /**
     * Cached reference to the lazily-created [BrowserTabPool] so
     * [ensureSession] can re-point it at the real session id after a rename.
     * Read only through [browserTabPool]; the backing `by lazy` fills this in.
     */
    @Volatile
    internal var _browserTabPoolRef: BrowserTabPool? = null

    /** Browser tab pool for browser_use tool. Lazily created on first access. */
    val browserTabPool: BrowserTabPool by lazy {
        BrowserTabPool(context).also {
            it.setSession(activeSessionId)
            // Surface download start/finish/failure as system-info notices in
            // this chat. May fire from the pool's IO scope — hop to Main since
            // appendSystemInfo does a read-modify-write on _messages.
            it.onDownloadEvent = { text ->
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    appendSystemInfo(text, "info")
                }
            }
            _browserTabPoolRef = it
        }
    }

    internal val _showBrowserSheet = MutableStateFlow(false)
    val showBrowserSheet: StateFlow<Boolean> = _showBrowserSheet.asStateFlow()

    // [T-android-split-chat] toggleBrowserSheet / dismissBrowserSheet /
    // openBrowserSheetForUrl moved to ChatViewModelUiStateExt.kt.

    internal val _showMemorySheet = MutableStateFlow(false)
    val showMemorySheet: StateFlow<Boolean> = _showMemorySheet.asStateFlow()

    /** Set true by the slash-command "/clear" handler so ChatScreen can mirror
     *  it into the local Compose state that drives the existing
     *  showClearChatDialog confirmation. ChatScreen calls
     *  [ackClearChatConfirmRequest] after observing to reset back to false. */
    private val _clearChatConfirmRequested = MutableStateFlow(false)
    val clearChatConfirmRequested: StateFlow<Boolean> = _clearChatConfirmRequested.asStateFlow()

    fun ackClearChatConfirmRequest() {
        _clearChatConfirmRequested.value = false
    }

    internal val _memoryToolRecords = MutableStateFlow<List<MemoryToolRecord>>(emptyList())
    val memoryToolRecords: StateFlow<List<MemoryToolRecord>> = _memoryToolRecords.asStateFlow()

    /**
     * Revoke a previously recorded memory_write by removing its entry from
     * today's or yesterday's daily log on disk, and dropping the row from
     * [memoryToolRecords] so the SessionMemorySheet reflects the removal.
     *
     * Returns the repository result so the UI can show a success / not-found
     * / I/O error dialog. The original ChatMessage tool block stays in the
     * conversation history untouched — only the on-disk entry and the
     * op-log row are mutated.
     */
    fun revokeMemoryRecord(record: MemoryToolRecord): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = sessionMemoryRepo()
        val written = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.revokeEntry(written)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value - record
        }
        return result
    }

    /**
     * T149: revoke every `memory_write` tool block embedded in the supplied
     * messages. Used when a retry path truncates the conversation — the
     * deleted assistant turns may have written entries to today's daily
     * memory log, and leaving them on disk after the conversation rewinds
     * means user-visible history is gone but the side effects remain.
     *
     * We match by the `content` field of the tool args against
     * [MemoryToolRecord.writtenContent] (which is what `revokeMemoryRecord`
     * keys on). If multiple records share the same content body — possible
     * if the agent wrote the same note twice — we revoke them in the
     * reverse insertion order so the most recent disk write is removed
     * first; the repository's revokeEntry only removes the first match
     * each call, so subsequent records may end up NotFound on disk but
     * still get pulled from the in-memory record list.
     */
    internal fun revokeMemoryWritesInDeletedMessages(deletedMessages: List<ChatMessage>) {
        val deletedContents = mutableListOf<String>()
        for (msg in deletedMessages) {
            for (block in msg.toolBlocks) {
                if (block.kind != "tool_use") continue
                if (block.toolName != "memory_write") continue
                val content = try {
                    JSONObject(block.toolArgs).optString("content", "")
                } catch (_: Exception) { "" }
                if (content.isNotBlank()) deletedContents.add(content)
            }
        }
        if (deletedContents.isEmpty()) return
        Log.i(TAG, "revokeMemoryWritesInDeletedMessages: ${deletedContents.size} write(s) to revoke")
        for (content in deletedContents.asReversed()) {
            // Find the latest matching record so revoke targets the most
            // recent disk entry first. Snapshot value because revoke mutates
            // the flow.
            val record = _memoryToolRecords.value.lastOrNull {
                it.isWrite && it.writtenContent == content
            } ?: continue
            val result = revokeMemoryRecord(record)
            Log.i(TAG, "  revoke result: ${result::class.simpleName}")
        }
    }

    /**
     * Replace the body of a previously recorded memory_write with
     * [newContent]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog`.
     * On success, also updates the in-memory [MemoryToolRecord] so a
     * subsequent revoke or revisit sees the new body.
     */
    fun replaceMemoryRecord(
        record: MemoryToolRecord,
        newContent: String,
    ): com.openminis.app.data.repository.MemoryRepository.EntryMutationResult {
        val repo = sessionMemoryRepo()
        val old = record.writtenContent
            ?: return com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.NotFound
        val result = repo.replaceEntryBody(old, newContent)
        if (result is com.openminis.app.data.repository.MemoryRepository.EntryMutationResult.Success) {
            _memoryToolRecords.value = _memoryToolRecords.value.map {
                if (it === record) it.copy(
                    writtenContent = newContent,
                    preview = newContent.lines().firstOrNull { line -> line.isNotBlank() }?.take(100) ?: "",
                ) else it
            }
        }
        return result
    }

    // ── Slash commands (mirrors iOS AIChatViewModel) ────────────────────

    // [T-memory-global-toggle-settings-ui-android] Seed from the global
    // pref so a fresh draft VM honors the user's "memory off by default"
    // choice from Settings. For loaded sessions, `loadSession()` later
    // overwrites this with the per-session DB value, which takes
    // precedence — the global pref only applies to drafts.
    internal val _memoryEnabled =
        MutableStateFlow(com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context))
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    internal val _thinkingLevel = MutableStateFlow(ThinkingLevel.OFF)
    override val thinkingLevel: StateFlow<ThinkingLevel> = _thinkingLevel.asStateFlow()

    internal val _permissionMode = MutableStateFlow(com.openminis.app.security.PermissionMode.ASK)
    val permissionMode: StateFlow<com.openminis.app.security.PermissionMode> = _permissionMode.asStateFlow()

    /**
     * [T-android-enhanced-cache] Enhanced Cache (1-hour Anthropic cache TTL)
     * toggle. Per-VM memory state, NOT persisted — mirrors iOS
     * `AIChatViewModel.enhancedCacheEnabled`. When true, the active turn's
     * AnthropicProvider is stamped with `enhancedCache = true` just before the
     * request (see the streamMessage choke point).
     */
    internal val _enhancedCacheEnabled = MutableStateFlow(false)
    val enhancedCacheEnabled: StateFlow<Boolean> = _enhancedCacheEnabled.asStateFlow()

    /**
     * [T-android-enhanced-cache] Whether the Enhanced Cache menu item is shown.
     * Mirrors iOS `showEnhancedCacheToggle` (commit 57aaf122): only visible when
     * the current session's resolved provider instance is the *official*
     * Anthropic API (`providerType == anthropic` AND `customBaseURL` is
     * blank) — relays / other providers hide it because they don't honor the
     * 1-hour cache TTL. Recomputes whenever the active entry or provider config
     * changes so switching model/provider updates visibility instantly.
     */
    val showEnhancedCacheToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.anthropic &&
                instance.customBaseURL.isNullOrBlank()
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    /** [T-android-enhanced-cache] True once the user accepted the one-time warning. */
    fun isEnhancedCacheConfirmed(): Boolean =
        com.openminis.app.data.EnhancedCachePrefs.isConfirmed(context)

    /**
     * [T-android-enhanced-cache] Enable Enhanced Cache after the confirmation
     * dialog was accepted (records the durable acknowledgement) and flips the
     * in-memory toggle on.
     */
    fun confirmAndEnableEnhancedCache() {
        com.openminis.app.data.EnhancedCachePrefs.setConfirmed(context)
        _enhancedCacheEnabled.value = true
    }

    /**
     * [T-android-enhanced-cache] Toggle the switch when confirmation is not
     * required (turning it OFF, or turning it ON after the user already
     * acknowledged). The confirmation-gated first enable is handled in the UI.
     */
    fun setEnhancedCacheEnabled(enabled: Boolean) {
        _enhancedCacheEnabled.value = enabled
    }

    /**
     * [T-codex-fast-mode] Fast Mode toggle state. APP-LEVEL and persisted
     * (FastModePrefs / iOS UserDefaults "codexFastModeEnabled") — unlike
     * Enhanced Cache it survives across sessions and process restarts; every
     * chat reads the same flag. The provider reads FastModePrefs directly at
     * request-build time, so this flow only drives the menu row + nav badge.
     */
    internal val _fastModeEnabled =
        MutableStateFlow(com.openminis.app.data.FastModePrefs.isEnabled())
    val fastModeEnabled: StateFlow<Boolean> = _fastModeEnabled.asStateFlow()

    fun setFastModeEnabled(enabled: Boolean) {
        com.openminis.app.data.FastModePrefs.setEnabled(context, enabled)
        _fastModeEnabled.value = enabled
    }

    /**
     * Auto-compact toggle state. APP-LEVEL and persisted
     * (AutoCompactPrefs / iOS UserDefaults "autoCompactOnThreshold").
     *
     * When on, crossing the compact threshold before a send compacts silently
     * and then sends; when off, the user is asked first. Mirrors iOS
     * `AIChatViewModel.autoCompactEnabled`.
     */
    internal val _autoCompactEnabled =
        MutableStateFlow(com.openminis.app.data.AutoCompactPrefs.isEnabled())
    private val _pendingUserQuestions =
        MutableStateFlow<List<com.openminis.app.tools.AskUserQuestion.Question>?>(null)
    val pendingUserQuestions: StateFlow<List<com.openminis.app.tools.AskUserQuestion.Question>?> =
        _pendingUserQuestions.asStateFlow()
    @Volatile private var askUserDeferred: kotlinx.coroutines.CompletableDeferred<String>? = null

    val pendingApprovals: StateFlow<Map<String, ApprovalGate.ApprovalRequest>> =
        ApprovalGate.pendingApprovals

    fun approvePendingTool(id: String) {
        ApprovalGate.approve(id)
        ApprovalNotifier.cancelApproval(context, id)
    }

    fun denyPendingTool(id: String) {
        ApprovalGate.deny(id)
        ApprovalNotifier.cancelApproval(context, id)
    }

    /**
     * Third action on the approval card: approve this request AND flip the
     * session-scoped allow-all switch, so later sensitive operations in this
     * conversation execute without asking again. The switch lives in
     * ApprovalGate and resets on session teardown (cleanupAll).
     */
    fun approveAllForSession(id: String) {
        val tool = pendingApprovals.value[id]?.toolName
        if (tool != null) ApprovalGate.allowToolForSession(tool)
        else ApprovalGate.enableSessionAllowAll()
        approvePendingTool(id)
    }

    fun setSessionPermissionMode(mode: com.openminis.app.security.PermissionMode) {
        val next = if (mode.isYoyo()) com.openminis.app.security.PermissionMode.ALLOW_ALL
        else com.openminis.app.security.PermissionMode.ASK
        _permissionMode.value = next
        applyGateMode(next)
        val sid = realSessionId.ifEmpty { sessionId }
        if (!isDraft && sid.isNotBlank() && !sid.startsWith("__new__")) {
            viewModelScope.launch {
                val stored = if (next.isYoyo()) "YOYO" else "ASK"
                runCatching { chatRepository.dao.updatePermissionMode(sid, stored) }
            }
        }
    }

    internal fun applyGateMode(mode: com.openminis.app.security.PermissionMode) {
        SecurityGateHolder.setActiveSessionMode(mode)
        ApprovalGate.bindSession(realSessionId.ifEmpty { sessionId })
        if (mode.isYoyo()) ApprovalGate.enableSessionAllowAll()
        else ApprovalGate.resetSessionAllowAll()
    }

    private fun activeOverrides(): com.openminis.app.data.model.ModelOverrides? {
        val id = _activeEntryId.value ?: return null
        return providerRepository.config.value.modelEntries.find { it.id == id }?.overrides
    }

    private fun effectiveAutoCompact(): Boolean =
        activeOverrides()?.autoCompactEnabled
            ?: com.openminis.app.data.AutoCompactPrefs.isEnabled()

    internal fun effectiveCompactPercent(): Int =
        (activeOverrides()?.compactThresholdPercent
            ?: com.openminis.app.data.AutoCompactPrefs.thresholdPercent())
            .coerceIn(50, 95)

    internal fun effectiveMaxRetries(): Int =
        (activeOverrides()?.maxRetries
            ?: com.openminis.app.provider.HttpRetryAfter.DEFAULT_MAX_RETRIES)
            .coerceIn(0, 8)

    fun submitUserQuestionAnswers(selections: List<List<String>>) {
        val questions = _pendingUserQuestions.value ?: return
        val json = com.openminis.app.tools.AskUserQuestion.formatAnswers(questions, selections)
        val d = askUserDeferred
        askUserDeferred = null
        _pendingUserQuestions.value = null
        d?.complete(json)
    }

    fun skipUserQuestions() {
        dismissPendingUserQuestions("skipped")
    }

    private fun dismissPendingUserQuestions(reason: String) {
        val d = askUserDeferred
        askUserDeferred = null
        _pendingUserQuestions.value = null
        d?.complete("{\"answers\":[],\"status\":\"$reason\"}")
    }

    internal suspend fun executeAskUserQuestion(argsJson: String): ToolExecutionResult {
        val params = try { org.json.JSONObject(argsJson) } catch (_: Exception) {
            return ToolExecutionResult("ask_user_question: invalid JSON", false)
        }
        val questions = com.openminis.app.tools.AskUserQuestion.parse(params)
        if (questions.isEmpty()) {
            return ToolExecutionResult("ask_user_question: no valid questions (need 2+ options each)", false)
        }
        val deferred = kotlinx.coroutines.CompletableDeferred<String>()
        askUserDeferred = deferred
        _pendingUserQuestions.value = questions
        return try {
            val answers = deferred.await()
            ToolExecutionResult(answers, true)
        } finally {
            if (askUserDeferred === deferred) {
                askUserDeferred = null
                _pendingUserQuestions.value = null
            }
        }
    }
    val autoCompactEnabled: StateFlow<Boolean> = _autoCompactEnabled.asStateFlow()

    fun setAutoCompactEnabled(enabled: Boolean) {
        com.openminis.app.data.AutoCompactPrefs.setEnabled(context, enabled)
        _autoCompactEnabled.value = enabled
    }

    private val _planDiscussionEnabled =
        MutableStateFlow(PlanDiscussionPrefs.isEnabled())
    val planDiscussionEnabled: StateFlow<Boolean> = _planDiscussionEnabled.asStateFlow()

    fun setPlanDiscussionEnabled(enabled: Boolean) {
        PlanDiscussionPrefs.setEnabled(context, enabled)
        _planDiscussionEnabled.value = PlanDiscussionPrefs.isEnabled()
    }

    /**
     * [T-codex-fast-mode] Whether the Fast Mode menu row (and, when enabled,
     * the nav ⚡ badge) is shown. Mirrors iOS activeModelSupportsFastMode
     * (838ba929): the active model id contains "gpt" (case-insensitive —
     * matches the official fast catalog gpt-5.6-sol/terra/luna, gpt-5.5,
     * gpt-5.4) AND the request travels the Responses path — the instance has
     * useResponsesAPI on (any credential/base; Responses relays like sub2api
     * pass the tier through) OR it's the Codex OAuth route (OpenAI type +
     * oauth credential + no custom base URL). Chat-completions providers stay
     * excluded. Recomputes on entry/config changes like the Enhanced Cache
     * gate above.
     *
     * [T-android-xai-priority] xAI is a second, independent branch: xAI's
     * Priority Processing is the same `service_tier: "priority"` wire field
     * and the same user-facing promise (lower latency, higher price), so it
     * reuses this one global toggle rather than adding a competing per-provider
     * switch. The "gpt" model-id test deliberately does NOT apply — xAI's
     * models are the grok family — and neither does the Responses-path test,
     * because xAI serves Priority Processing on its Chat Completions endpoint,
     * which is the path ProviderFactory always resolves xAI to.
     */
    val showFastModeToggle: StateFlow<Boolean> =
        kotlinx.coroutines.flow.combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            val instance = entry?.let { e -> config.instances.find { it.id == e.providerInstanceId } }
            val isCodexOAuth = instance != null &&
                instance.providerType == com.openminis.app.data.model.ProviderType.openAI &&
                instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth &&
                instance.customBaseURL.isNullOrBlank()
            val isXAI = instance?.providerType == com.openminis.app.data.model.ProviderType.xAI
            entry != null && instance != null &&
                (
                    isXAI ||
                        (
                            entry.model.id.contains("gpt", ignoreCase = true) &&
                                (instance.useResponsesAPI || isCodexOAuth)
                            )
                    )
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            false,
        )

    internal val _showSlashMenu = MutableStateFlow(false)
    val showSlashMenu: StateFlow<Boolean> = _showSlashMenu.asStateFlow()

    internal val _slashFilter = MutableStateFlow("")
    val slashFilter: StateFlow<String> = _slashFilter.asStateFlow()

    internal val _slashMenuSelectedIndex = MutableStateFlow(-1)
    val slashMenuSelectedIndex: StateFlow<Int> = _slashMenuSelectedIndex.asStateFlow()

    /**
     * [T-android-slash-menu-align-ios-prepend] The user's ORIGINAL composer
     * text, saved when the slash menu is opened via the "/" button over
     * existing content. Non-null ⇒ "over-content" mode; null ⇒ the menu was
     * opened by typing a leading "/" (the input itself is the slash query).
     *
     * Mirrors iOS `savedInputBeforeSlash`. On open we PREPEND "/ " to the
     * composer so it reads `/ <original>`; the user's subsequent typing edits
     * only the `/<filter>` token (see [updateSlashMenuState]), while
     * `<original>` is preserved here. Every exit path restores/uses this saved
     * original — never the live `/ <original>` string — so the injected "/ "
     * prefix is always stripped and the body text is never lost.
     *
     * This is the iOS-parity replacement for the earlier boolean marker. It
     * does NOT regress e48fe7a0 ("don't clear input"): the original body is
     * saved and faithfully restored on dismiss / prepended on skill select; it
     * is never discarded. The only behavioral change is that the body now sits
     * AFTER the slash token (iOS semantics) instead of being edited live.
     */
    internal var savedInputBeforeSlash: String? = null

    // ── @ file-mention picker (mirrors iOS AIChatViewModel mention*) ─────
    /**
     * Per-app singleton — scans this chat's workspace/attachments/memory plus
     * shared skills and /var/minis/shared on demand, ranks matches by basename
     * fuzzy score + scope priority. The composer hooks update*MentionMenu*
     * on every keystroke; the popup composes against [mentionEntries].
     */
    val fileMentionIndex: FileMentionIndex by lazy {
        // T219: provide the SAF-mounted external folders so `@<mountName>`
        // resolves to /var/minis/mounts/<name>/... in the chat composer.
        // PRootKernel holds the MountedFoldersStore reference (set at app
        // launch by MinisApp); reading via a closure means the index sees
        // an up-to-date snapshot on every rescan without a manual refresh.
        FileMentionIndex(
            filesDir = java.io.File(context.applicationContext.filesDir, "minis-global"),
            mountsProvider = {
                com.openminis.app.sandbox.PRootKernel
                    .mountEntriesForIndex(context.applicationContext)
            },
        )
    }

    internal val _showMentionMenu = MutableStateFlow(false)
    val showMentionMenu: StateFlow<Boolean> = _showMentionMenu.asStateFlow()

    internal val _mentionFilter = MutableStateFlow("")
    val mentionFilter: StateFlow<String> = _mentionFilter.asStateFlow()

    /** Caret index of the active `@` in [inputText], or -1 when no token is open. */
    internal val _mentionAnchor = MutableStateFlow(-1)

    /** Live-filtered candidate list. Combines the index's [FileMentionIndex.entries]
     * with [mentionFilter] so matches refresh as the user types and as the
     * background scan emits more entries. Capped at 50 like iOS. */
    val mentionEntries: StateFlow<List<FileMentionIndex.Entry>> = combine(
        fileMentionIndex.entries,
        _mentionFilter,
    ) { _, filter -> fileMentionIndex.matches(filter, limit = 50) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isMentionScanning: StateFlow<Boolean>
        get() = fileMentionIndex.isScanning

    /**
     * T-at-filepicker-keyboard: highlighted row in the @-mention picker. -1 when
     * the menu is closed or the filtered list is empty. Mirrors iOS
     * `mentionSelectedIndex` so a hardware-keyboard user can Up/Down through
     * candidates and hit Return to commit the highlighted entry. Touch users
     * still tap rows directly — the highlight just shows which row Return
     * would land on.
     */
    internal val _mentionSelectedIndex = MutableStateFlow(-1)
    val mentionSelectedIndex: StateFlow<Int> = _mentionSelectedIndex.asStateFlow()

    val currentModelSupportsReasoning: Boolean
        get() = currentModel?.supportsReasoning == true

    /**
     * [T-android-thinking-level-arch] The thinking ceiling the currently-bound
     * model actually supports. Prefers the active ModelEntry's
     * effectiveMaxThinkingLevel (so a user override on the entry is honored);
     * falls back to the resolved model's catalog default when no entry is
     * pinned (e.g. a group-resolved turn) or the model isn't known.
     */
    private val currentModelMaxThinkingLevel: ThinkingLevel
        get() {
            val entry = _activeEntryId.value?.let { id ->
                providerRepository.config.value.modelEntries.find { it.id == id }
            }
            if (entry != null) {
                return entry.effectiveMaxThinkingLevel
            }
            val model = currentModel ?: return ThinkingLevel.XHIGH
            return model.catalogMaxThinkingLevel
        }

    /**
     * [T-android-thinking-level-arch] Levels the chat composer picker should
     * offer: everything up to the current model's ceiling, EXCLUDING OFF —
     * mirrors iOS availableThinkingLevels (`filter { $0 != .off && $0 <= max }`).
     * There is no standalone "Off" capsule; tapping the already-selected level
     * toggles thinking off (see ThinkingLevelPicker). setThinkingLevel
     * additionally clamps as a belt-and-suspenders defense.
     */
    val availableThinkingLevels: List<ThinkingLevel>
        get() {
            val ceiling = currentModelMaxThinkingLevel
            return ThinkingLevel.entries.filter { it != ThinkingLevel.OFF && it.rank <= ceiling.rank }
        }

    // [T-anthropic-context-window] Token Usage sheet's context-window row.
    // Route through contextWindowTokens (heuristic-backed) so models without an
    // explicit contextWindow — e.g. heuristic-only Claude/Gemini — still report
    // their real 1M window instead of showing blank.
    val currentModelContextWindow: Int?
        get() = effectiveContextWindowTokens()

    /**
     * [T-context-window-live-read] Effective context window for capacity
     * judgment (compaction warnings, tool-output offload, empty-response
     * heuristic, Token Usage sheet). Reads LIVE state on every call instead of
     * the `currentModel` snapshot, so editing the model's context window or
     * the bound group's `contextLimitTokens` takes effect on the very next
     * judgment without re-picking the model/group (mirrors iOS fcc22b66):
     *   1. the active entry's model is re-resolved from the current repository
     *      config (folds ModelOverrides live), falling back to the snapshot
     *      only when the entry can't be found (e.g. synced sessions before
     *      config finished loading);
     *   2. the result is clamped by the bound group's `contextLimitTokens`
     *      (null / <=0 = unlimited). Pre-fix that group field was write-only
     *      on Android — persisted by the group editor but never consulted at
     *      runtime.
     */
    internal fun effectiveContextWindowTokens(): Int? {
        val config = providerRepository.config.value
        val liveModel = _activeEntryId.value
            ?.let { id -> config.modelEntries.find { it.id == id }?.model }
            ?: currentModel
        val window = liveModel?.contextWindowTokens ?: return null
        val groupLimit = _selectedGroupId.value
            ?.let { gid -> config.modelGroups.find { it.id == gid }?.contextLimitTokens }
            ?.takeIf { it > 0 }
        return if (groupLimit != null) minOf(window, groupLimit) else window
    }

    val currentModelMaxOutputTokens: Int?
        get() {
            val m = currentModel ?: return null
            m.maxOutputTokens?.takeIf { it > 0 }?.let { return it }
            return com.openminis.app.provider.ModelsDevApi.enrichModel(m).maxOutputTokens
                ?: com.openminis.app.data.model.inferredMaxOutputTokens(m.id, m.displayName)
        }

    // [T-context-ring] Live context token counter for the session-menu ring.
    val contextUsage = MutableStateFlow(com.openminis.app.data.model.ContextUsage(0L, 0L))

    /** Recompute contextUsage from the latest agent history. Called after appending messages. */
    fun refreshContextUsage() {
        var usedChars = 0L
        for (msg in agentHistory) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> usedChars += part.text.length
                    is AgentContentPart.ToolUse -> usedChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        usedChars += part.content.length
                    }
                    is AgentContentPart.ImageData -> {}
                }
            }
        }
        val estTokens = (usedChars / 3.5).toLong()
        val window = currentModelContextWindow?.toLong() ?: 0L
        contextUsage.value = com.openminis.app.data.model.ContextUsage(estTokens, window)
    }

    // ── Session token usage (iOS parity: TokenUsageSheet data) ─────────────

    /**
     * Aggregated token usage for this session, computed from all persisted
     * `token_usage` JSON rows. Mirrors iOS [sessionTokenStats].
     *
     * @param context the most recent [LLMUsage.latestContextTokens] — reflects
     * how much of the model's context window was consumed at the last turn.
     * @param loopCount number of agent loop iterations (approximated by
     * max(tool_use blocks, assistant message count), matching iOS).
     */
    data class SessionTokenStats(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val context: Int,
        val loopCount: Int,
    )

    data class ThinkingInfo(
        val supported: Boolean,
        val enabled: Boolean,
        val level: String,
    )

    /** Read-only view of the current thinking configuration for the model. */
    fun thinkingInfo(): ThinkingInfo? {
        val model = currentModel ?: return null
        val supported = model.supportsReasoning == true
        val level = _thinkingLevel.value
        val enabled = supported && level.isEnabled
        val levelText = if (enabled) level.displayName else "—"
        return ThinkingInfo(supported, enabled, levelText)
    }

    /**
     * Load session-level token aggregates from the database. Suspend so the
     * Token Usage sheet can fetch on demand without keeping a live subscription
     * — token data rarely changes mid-view, and we want to avoid reactive
     * overhead per token chunk.
     */
    suspend fun loadSessionTokenStats(): SessionTokenStats {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return SessionTokenStats(0, 0, 0, 0, 0, 0)
        val usages = chatRepository.sessionTokenUsages(sid)
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var context = 0
        for (json in usages) {
            try {
                val obj = org.json.JSONObject(json)
                input += obj.optLong("inputTokens", 0L)
                output += obj.optLong("outputTokens", 0L)
                cacheRead += obj.optLong("cacheReadTokens", 0L)
                cacheWrite += obj.optLong("cacheCreationTokens", 0L)
                val ctx = obj.optInt("latestContextTokens", 0)
                if (ctx > 0) context = ctx
            } catch (_: Exception) { /* skip malformed row */ }
        }
        val snapshot = _messages.value
        val assistantCount = snapshot.count { it.role == "assistant" }
        val toolCalls = snapshot.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return SessionTokenStats(input, output, cacheRead, cacheWrite, context, loops)
    }

    // [T-android-split-chat] toggleMemorySheet / dismissMemorySheet moved to ChatViewModelUiStateExt.kt.

    // ── Slash command API (mirrors iOS AIChatViewModel) ─────────────────

    /** Static catalogue of available slash commands, in display order.
     *  Subtitles are placeholders here — [filteredSlashCommands] always
     *  rebuilds them with the current localized state. */
    internal val availableSlashCommands: List<SlashCommand> = listOf(
        SlashCommand(
            id = "clear",
            icon = Icons.Default.Delete,
            title = "Clear",
            subtitle = "",
        ),
        SlashCommand(
            id = "compact",
            icon = Icons.Default.Compress,
            title = "Compact",
            subtitle = "",
        ),
        SlashCommand(
            id = "memory",
            icon = Icons.Default.Psychology,
            title = "Memory",
            subtitle = "",
        ),
        SlashCommand(
            id = "thinking",
            icon = Icons.Default.Lightbulb,
            title = "Thinking",
            subtitle = "",
        ),
    )

    // [T-android-split-chat] filteredSlashCommands / updateSlashMenuState /
    // showSlashMenuOverInput / dismissSlashMenu / slashMenuSetSelectedIndex moved
    // to ChatViewModelSlashExt.kt as ChatViewModel extension functions.

    // ── @ file-mention picker driver ──────────────────────────────────────
    // [T-android-split-chat] updateMentionMenuState / dismissMentionMenu /
    // mentionMenuUp / mentionMenuDown / executeSelectedMention / selectMention
    // moved to ChatViewModelMentionExt.kt as ChatViewModel extension functions.

    /**
     * Execute a slash command. Returns the text the composer should hold
     * afterward (caret via [pendingCaret] when relevant).
     *
     * [T-android-slash-menu-align-ios-prepend] Over-content (the menu was
     * opened via the "/" button, so [savedInputBeforeSlash] holds the user's
     * original text): a skill row prepends "/<skill> " to the original; an
     * action command (clear/compact/…) runs as a side effect and restores the
     * original (stripping the injected "/ "). Typed-"/" (no saved original):
     * a skill fills "/<skill> ", an action clears the input. The original body
     * is always preserved — never discarded (no regression of e48fe7a0).
     *
     * [currentInput] is retained for call-site compatibility; the body text is
     * sourced from [savedInputBeforeSlash], not the live string.
     */
    fun executeSlashCommand(cmd: SlashCommand, currentInput: String = ""): String {
        val saved = savedInputBeforeSlash
        // [T-skill-slash a88ea8f9] Skill rows aren't directly executable —
        // they're a typing aid. Fill the composer with the literal slash
        // command; the user then taps Send and the model handles the skill via
        // the existing SKILL.md fragment injection in runAgentLoop.
        if (cmd.isSkill) {
            AppLogger.info(TAG, "[Slash] tap skill id=${cmd.id} title=${cmd.title} → composer fill only")
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            val prefix = "/${cmd.title} "
            // [T-android-slash-menu-align-ios-prepend] iOS parity: over-content
            // (saved != null) → PREPEND "/<skill> " to the original, so the
            // composer reads "/<skill> <original>" with the original as args,
            // caret right after the prefix (before the original). Typed-"/"
            // (saved == null) → just "/<skill> " (the input WAS the partial
            // command). Trailing space lets the user type "/<skill> <args>".
            return if (saved != null) {
                _pendingCaret.value = prefix.length
                prefix + saved
            } else {
                prefix
            }
        }
        AppLogger.info(TAG, "[Slash] tap id=${cmd.id} title=${cmd.title} streaming=${_isStreaming.value} compacting=${_isCompacting.value}")
        savedInputBeforeSlash = null
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1

        when (cmd.id) {
            "compact" -> compactAll()
            "memory" -> toggleMemoryEnabled()
            "thinking" -> toggleThinking()
            "clear" -> _clearChatConfirmRequested.value = true
            else -> AppLogger.info(TAG, "[Slash] unrecognized id=${cmd.id} — no dispatch")
        }
        // [T-android-slash-menu-align-ios-prepend] Action command: restore the
        // saved ORIGINAL (stripping the injected "/ " prefix) so the body text
        // survives — never the live "/ <original>". Typed-"/" path → clear.
        if (saved != null) {
            _pendingCaret.value = saved.length
            return saved
        }
        return ""
    }

    /** Toggle memory writes on/off, persist to DB, and append a system-info message. */
    private fun toggleMemoryEnabled() {
        val newValue = !_memoryEnabled.value
        _memoryEnabled.value = newValue
        viewModelScope.launch {
            // Toggling before the first message means the row doesn't exist
            // yet — materialize the session row so the preference lands on
            // the persisted id instead of silently updating zero rows under
            // the draft key.
            val sid = ensureSession()
            chatRepository.dao.updateMemoryEnabled(sid, if (newValue) 1 else 0)
        }
        appendSystemInfo(
            text = "Memory writes ${if (newValue) "enabled" else "disabled"}. Reads are unaffected.",
            iconKind = "memory",
        )
    }

    /** Toggle thinking between OFF and MEDIUM (matches iOS default toggle semantics). */
    private fun toggleThinking() {
        if (!currentModelSupportsReasoning) {
            appendSystemInfo(
                text = "The current model does not support deep thinking.",
                iconKind = "thinking",
            )
            return
        }
        val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
        _thinkingLevel.value = newLevel
        persistThinkingOverride(newLevel)
        appendSystemInfo(
            text = "Thinking set to ${newLevel.displayName.lowercase()}.",
            iconKind = "thinking",
        )
    }

    /**
     * Set thinking level explicitly. Used by the inline level picker in the
     * `/thinking` slash row. Mirrors iOS `setThinkingLevel(_:)` — silently
     * ignored when the current model doesn't support reasoning.
     */
    override fun setThinkingLevel(level: ThinkingLevel) {
        if (!currentModelSupportsReasoning) return
        // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
        // already filters to availableThinkingLevels, but never fully trust the
        // caller — cap to the current model's ceiling so a stale/over-range
        // request can't persist a level the model can't reach.
        val ceiling = currentModelMaxThinkingLevel
        val clamped = if (level.rank > ceiling.rank) ceiling else level
        if (_thinkingLevel.value == clamped) return
        _thinkingLevel.value = clamped
        persistThinkingOverride(clamped)
    }

    /**
     * T239: write the user's explicit thinking-level choice back to the
     * sessions row so it survives cold-start. Stored as enum name; null
     * means "no override" (legacy behaviour). We always store a non-null
     * value here — including OFF — because the user's explicit "turn it
     * off for this session" must persist as distinct from "never set".
     *
     * Uses [ensureSession] so toggling on a draft (no DB row yet) first
     * materialises the row, mirroring how toggleMemoryEnabled lands its
     * preference on the persisted id rather than the `__new__…` draft key.
     */
    private fun persistThinkingOverride(level: ThinkingLevel) {
        viewModelScope.launch {
            val sid = ensureSession()
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    /**
     * If `text` is a slash command literal (e.g. "/compact"), run it and
     * return true so the caller can skip the normal send path. Mirrors iOS
     * `tryExecuteInputAsSlashCommand()`. Recognized titles are matched
     * case-insensitively against [availableSlashCommands].
     *
     * Accepts both ASCII `/` and the full-width `／` (U+FF0F): some Chinese/
     * Japanese IMEs auto-substitute the full-width form when the user types
     * `/` while a CJK keyboard layout is active. We treat them identically.
     */
    fun tryExecuteInputAsSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val first = trimmed[0]
        if (first != '/' && first != '／') return false
        val name = trimmed.drop(1).lowercase()
        val cmd = availableSlashCommands.firstOrNull { it.title.lowercase() == name }
            ?: return false
        executeSlashCommand(cmd)
        return true
    }

    /**
     * Append a system-info block to the conversation. Not persisted — matches the
     * iOS `appendSystemInfo` behavior which surfaces a local notice in the chat
     * stream. Future work: wire real conversation compaction through the LLM.
     */
    internal fun appendSystemInfo(text: String, iconKind: String, payload: String? = null) {
        val block = AssistantBlock(
            id = "sysinfo_${System.currentTimeMillis()}",
            kind = "info",
            content = text,
            toolName = iconKind,
            // Reuse toolArgs as a freeform payload slot — for `iconKind="compact"`
            // this carries the full summary text so the UI can show an info-icon
            // affordance opening a detail sheet (mirrors iOS CompactSummarySheet).
            toolArgs = payload.orEmpty(),
        )
        _messages.value = trimLoadedWindow(_messages.value + ChatMessage(
            id = "sysinfo_${System.currentTimeMillis()}",
            role = "system",
            content = "",
            toolBlocks = listOf(block),
        ))
    }

    /** Replace an assistant bubble's visible text with a translation. Tool cards stay. */
    fun replaceAssistantOutput(messageId: String, translated: String) {
        val text = translated.trim()
        if (text.isEmpty()) return
        val cur = _messages.value
        val idx = cur.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val msg = cur[idx]
        val lastTextId = msg.toolBlocks.lastOrNull { it.kind == "text" }?.id
        val newBlocks = if (lastTextId == null) {
            msg.toolBlocks + AssistantBlock(id = "tr-${msg.id}", kind = "text", content = text)
        } else {
            msg.toolBlocks.map { block ->
                if (block.kind != "text") block
                else if (block.id == lastTextId) block.copy(content = text)
                else block.copy(content = "")
            }
        }
        val updated = msg.copy(content = text, toolBlocks = newBlocks)
        _messages.value = cur.toMutableList().also { it[idx] = updated }
        val dbIds = msg.sourceDbIds
        if (dbIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val rows = dbIds.mapNotNull { id ->
                    val raw = chatRepository.dao.messagePartsJson(id) ?: return@mapNotNull null
                    if (runCatching { org.json.JSONArray(raw) }.isFailure) return@mapNotNull null
                    id to raw
                }
                for ((id, parts) in AssistantReplyText.rewriteStoredParts(rows, text)) {
                    chatRepository.dao.updateMessageParts(id, parts)
                }
            }
        }
    }

    /** Replace one assistant text block. Other segments and tool cards stay. */
    fun replaceAssistantTextBlock(messageId: String, blockId: String, translated: String) {
        val text = translated.trim()
        if (text.isEmpty()) return
        val cur = _messages.value
        val idx = cur.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val msg = cur[idx]
        val old = msg.toolBlocks.find { it.id == blockId }?.content
        val newBlocks = msg.toolBlocks.map { block ->
            if (block.id == blockId && block.kind == "text") block.copy(content = text) else block
        }
        val joined = newBlocks.filter { it.kind == "text" }.joinToString("\n\n") { it.content }.ifBlank { text }
        val updated = msg.copy(content = joined, toolBlocks = newBlocks)
        _messages.value = cur.toMutableList().also { it[idx] = updated }
        val dbIds = msg.sourceDbIds
        if (dbIds.isEmpty() || old == null) return
        val occurrence = AssistantReplyText.textOccurrence(msg.toolBlocks, blockId, old)
        if (occurrence < 0) return
        viewModelScope.launch {
            runCatching {
                val rows = dbIds.mapNotNull { id ->
                    val raw = chatRepository.dao.messagePartsJson(id) ?: return@mapNotNull null
                    if (runCatching { org.json.JSONArray(raw) }.isFailure) return@mapNotNull null
                    id to raw
                }
                val updated = AssistantReplyText.replaceVisibleOccurrence(rows, old, text, occurrence)
                    ?: return@runCatching
                chatRepository.dao.updateMessageParts(updated.first, updated.second)
            }
        }
    }

    /**
     * Fold the current session history into a single summary stored in
     * `compact_markers`. Mirrors iOS `compactAll()` + Phase-B semantics:
     *
     *   1. Build a compact conversation transcript (role + parts preview).
     *   2. Call the **current provider's non-streaming `sendMessage`** with a
     *      hardcoded summarization system prompt that emphasises preserving
     *      paths/commands/IDs/decisions/errors/open tasks.
     *   3. Persist a `CompactMarkerEntity` via the DAO; publish via
     *      [_compactSummary] so [effectiveAgentHistory] starts injecting it.
     *   4. agentHistory itself is NOT truncated — the audit trail stays.
     *
     * Concurrency: gated by [_isCompacting] so the slash command can't
     * overlap with an in-flight streaming turn (`_isStreaming`) or another
     * compact. Runs on [Dispatchers.IO].
     */
    /**
     * Public entrypoint used by the debug RPC (`chat.session.compact`) to
     * trigger compaction without going through the ChatScreen slash-command
     * UI path. Mirrors what [executeSlashCommand]("compact") does — just
     * calls [compactAll]. RPC callers can then observe [isCompacting] flipping
     * back to false to know the run finished, and read [compactSummary] for
     * the resulting summary text.
     */
    override fun runCompactNow() {
        compactAll()
    }

    /**
     * Public entrypoint for "compact up through this message" (mirrors iOS
     * AIChatViewModel.compactBefore). The chat list's long-press menu and
     * the debug RPC `chat.compact.before` route through here.
     *
     * @param dbMessageId the DB message id to use as the new marker's
     *   anchor. agentHistory range to compact = `[prevAnchor+1, anchorIdx]`
     *   where anchorIdx is the agentHistory position of this id.
     * @param includesBoundary accepted for ABI compatibility with iOS, but
     *   in v2 the anchor IS the caller-supplied message regardless — the
     *   flag is logged and ignored. (iOS made the same simplification.)
     *
     * If the id can't be resolved to an agentHistory entry, this falls
     * back to compactAll() behaviour so the user's gesture isn't lost.
     */
    override fun compactBefore(dbMessageId: String, includesBoundary: Boolean) {
        AppLogger.info(
            TAG,
            "[Compact] compactBefore() id=${dbMessageId.take(8)} includesBoundary=$includesBoundary " +
                "(v2: includesBoundary ignored — caller-supplied id becomes the anchor)",
        )
        val history = agentHistory.toList()
        val idx = history.indexOfLast { it.dbMessageId == dbMessageId }
        if (idx < 0) {
            AppLogger.warning(
                TAG,
                "[Compact] compactBefore: id=${dbMessageId.take(8)} not in agentHistory — falling back to compactAll()",
            )
            compactAll(anchorIdxOverride = null)
            return
        }
        compactAll(anchorIdxOverride = idx)
    }


    /**
     * Revert the most recent compact on this session.
     *
     * Drops the latest CompactMarker (its summary is discarded), refreshes
     * [_cachedLatestMarker] / [_compactSummary] to whatever's left (or
     * null), and rebuilds the message list so the UI reflects the new (or
     * absent) divider. Effect by design:
     *   - If a previous (older) marker exists, divider snaps back to that
     *     marker's anchor; effectiveAgentHistory replays that summary.
     *   - If no previous marker exists, divider disappears, full history
     *     flows to the model again.
     *
     * Mirrors iOS `revertCompact()`. Refuses to run mid-stream.
     */
    override fun revertCompact() {
        if (_isStreaming.value) {
            appendSystemInfo("Cannot revert compact while a response is in progress.", "compact")
            return
        }
        if (_isCompacting.value) {
            appendSystemInfo("Cannot revert compact while compaction is in progress.", "compact")
            return
        }
        val current = _cachedLatestMarker ?: run {
            appendSystemInfo("Nothing to revert — no compact marker on this session.", "compact")
            return
        }
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            AppLogger.info(TAG, "[Compact] ━━━ REVERT ━━━ session=${sid.take(8)} markerId=${current.id.take(8)} v=${current.version}")
            val removed = runCatching { chatRepository.dao.deleteCompactMarker(current.id) }.getOrNull() ?: 0
            if (removed <= 0) {
                Log.w(TAG, "[Compact] revert: deleteCompactMarker returned 0 rows for id=${current.id.take(8)}")
                withContext(Dispatchers.Main) {
                    appendSystemInfo("Revert failed: marker not found in DB.", "compact")
                }
                return@launch
            }

            // Refresh cache to next-most-recent marker (or null).
            val next = chatRepository.dao.latestCompactMarker(sid)
            _cachedLatestMarker = next
            _compactSummary.value = next?.summary

            // Rebuild UI from DB so the previous marker's divider re-emerges
            // (or all dividers vanish if there are no remaining markers).
            // Drop any stale compact-divider system rows first; the reload
            // path will re-insert one only if the new latest marker calls
            // for it.
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.filterNot { msg ->
                    msg.role == "system" &&
                        msg.toolBlocks.firstOrNull()?.toolName == "compact"
                }
            }

            // Reload session messages — the existing path runs Phase 2.5
            // graying via applyCompactMarkerGraying() with the new cached
            // marker, so divider position falls back to the previous one
            // (or disappears entirely). loadSession() launches its own
            // viewModelScope job, so call from the Main thread.
            withContext(Dispatchers.Main) {
                reloadSessionFromDb()
            }

            if (next != null) {
                AppLogger.info(TAG, "[Compact] revert DONE: now showing previous marker id=${next.id.take(8)} v=${next.version}")
            } else {
                AppLogger.info(TAG, "[Compact] revert DONE: no remaining markers, full history active")
            }
        }
    }

    /**
     * Re-load the current session's UI message list from disk so any
     * cached-marker change (revert) gets re-applied through Phase-2.5-
     * style restore. Defers to the existing [loadSession] entry; that
     * function reads `_cachedLatestMarker` we just refreshed and routes
     * through [applyCompactMarkerGraying] to (re)position the divider.
     */
    private fun reloadSessionFromDb() {
        if (realSessionId.isEmpty() && sessionId.isEmpty()) return
        loadSession()
    }

    /**
     * Produce the LLM-facing view of agentHistory. Mirrors iOS
     * `effectiveAgentHistory` (AIChatViewModel.swift:3843-3876):
     *
     *   1) No marker / no summary → full agentHistory (zero-copy).
     *   2) Marker has a `firstKeptMessageId` (compactBefore at boundary) →
     *      `[summary] + agentHistory[boundaryIdx ...]`. The boundary message
     *      itself is the first kept entry.
     *   3) compactAll marker (`firstKeptMessageId = null`) → only summary +
     *      messages persisted AFTER the marker, located by
     *      `lastCompactedMessageId`. Messages inserted post-compact (the
     *      user's follow-up turn + the assistant's response) survive; the
     *      summary stands in for everything older.
     *   4) Marker present but no boundary resolvable in current history (e.g.
     *      the boundary message was deleted) → fall through to full history,
     *      same safety net iOS uses.
     *
     * Critically, we do NOT include `agentHistory[< boundaryIdx]` for case
     * (2/3) — that's how the model context stays clean after compact.
     * Earlier behaviour was [summary] + entire agentHistory, which both
     * over-stuffed the context AND duplicated tool_use/tool_result pairs the
     * marker had already replaced; that's what made follow-up turns appear
     * to lose continuity (the model got confused by the dual representation).
     */

    /**
     * [T-android-compact-orphan-toolcall] The outgoing history, with tool
     * call/result pairing repaired. Every request goes through here — see
     * [dropOrphanedToolParts] for why the sweep exists and what it can and
     * cannot fix.
     */
    internal fun effectiveAgentHistory(): List<LLMMessage> {
        val repaired = dropOrphanedToolParts(effectiveAgentHistoryUncounted())
        return if (personaHistorySteering) {
            com.openminis.app.agent.PersonaPromptLogic.applyHistorySteering(repaired)
        } else {
            repaired
        }
    }


    // dropOrphanedToolParts lives in ChatViewModelHistoryExt.kt

    /** Latest in-memory compact marker, used by [effectiveAgentHistory] to
     * resolve boundaries the same way iOS `cachedLatestMarker` does. Refreshed
     * on every compactAll write and on session reload. */
    @Volatile
    internal var _cachedLatestMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

    /**
     * [T-compact-detached-anchor] True when the latest v2 marker's anchor
     * message is no longer inside the bounded `agentHistory` window — the
     * session outgrew [MAX_AGENT_HISTORY_MESSAGES] (or the window moved past
     * the anchor). The summary text itself is still perfectly usable, so the
     * read side must degrade to "summary + recent tail" instead of dropping
     * the summary and sending the full history on every turn. Detected once
     * per session load so the condition is not re-probed (and re-logged) on
     * every single LLM call.
     */
    @Volatile
    internal var _compactMarkerDetached = false

    /**
     * Tail size handed to the model when a marker's anchor has been evicted:
     * summary + the newest [DETACHED_TAIL_MESSAGES] entries verbatim. Sized
     * to keep the "most recent user turns + their tool round-trips" warm
     * without re-introducing the full history the summary exists to replace.
     */
    internal val DETACHED_TAIL_MESSAGES = 60

    /**
     * Assemble the outgoing history for a DETACHED marker: the summary is
     * inlined as a `<context-summary>` text prefix on the first user message
     * of the tail (same role-alternation-safe technique the v2 branch uses),
     * followed by the tail verbatim. Mirrors
     * [effectiveAgentHistoryUncounted]'s v2 path minus the anchor-based
     * slice, which is the only part that requires a resolvable anchor.
     */
    internal fun detachedCompactHistory(summaryWrappedText: String): List<LLMMessage> =
        buildDetachedCompactHistory(
            summaryWrappedText = summaryWrappedText,
            history = agentHistory,
            tailSize = DETACHED_TAIL_MESSAGES,
        )


    internal suspend fun splitCompactHalves(
        messages: List<LLMMessage>,
        previousSummary: String?,
        depth: Int,
    ): String {
        val mid = messages.size / 2
        val firstHalf = messages.subList(0, mid).toList()
        val secondHalf = messages.subList(mid, messages.size).toList()
        val summary1 = generateCompactSummaryWithSplitting(firstHalf, previousSummary, depth + 1)
        val summary2 = generateCompactSummaryWithSplitting(secondHalf, null, depth + 1)
        return summary1 + "\n\n" + summary2
    }

    /**
     * Single-shot LLM call that turns [conversationText] into a structured
     * summary. Throws on provider error so the splitter above can detect
     * context-too-large failures and retry with halved input.
     */
    internal suspend fun generateCompactSummary(conversationText: String): String {
        // Wrap the transcript in explicit BEGIN/END framing so the model
        // treats it as material to summarize rather than as a chat turn to
        // continue. Mirrors iOS AIChatViewModel+Compaction.swift
        // `compactUserMessage` construction. Without this wrapper, fast models
        // (e.g. deepseek-v4-flash) tend to "answer" whatever the last user
        // turn in the transcript said — producing a single-line continuation
        // instead of a structured summary.
        val userMessage = buildString {
            append("Compact this conversation into a context summary:\n\n")
            append(conversationText)
            append("\n\n---\nEND OF CONVERSATION TO COMPACT.\n\n")
            append(
                "Now generate a structured context summary following the system prompt " +
                    "instructions. Do NOT continue the conversation above — summarize it. " +
                    "Write everything in past tense, framed as \"what was discussed / what " +
                    "was done\", NOT as an ongoing goal or todo list."
            )
        }
        val attempt = compactLeafAttempt ?: compactStagePlan().firstOrNull()
            ?: throw IllegalStateException("No LLM provider available for compaction")
        AppLogger.info(TAG, "[Compact] leaf via ${attempt.label}")
        val text = attempt.provider.sendMessage(
            messages = listOf(
                LLMMessage(role = LLMMessage.Role.USER, content = userMessage)
            ),
            systemPrompt = compactSummarySystemPrompt,
            maxTokens = attempt.maxOutFor(userMessage),
            temperature = attempt.temperature,
            imageParts = emptyList(),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        ).text
        if (text.isBlank()) throw IllegalStateException("empty compact summary from ${attempt.label}")
        return text
    }

    internal data class CompactAttempt(
        val kind: String,
        val label: String,
        val provider: LLMProvider,
        val model: LLMModel,
        val nextHint: String,
        val temperature: Double? = null,
    ) {
        fun maxOutFor(userMessage: String): Int {
            val estimatedInput = userMessage.length / 4
            val window = model.contextWindow?.takeIf { it > 0 } ?: 128_000
            return maxOf(1024, minOf(8192, window - estimatedInput))
        }
    }

    @Volatile
    internal var compactLeafAttempt: CompactAttempt? = null

    /** Two-shot compact: one leaf call per stage, no in-stage split fan-out. */
    @Volatile
    internal var compactAllowSplit = false

    internal fun compactStagePlan(): List<CompactAttempt> {
        val sessionProvider = currentProvider
        val sessionModel = currentModel
        if (sessionProvider == null || sessionModel == null) return emptyList()
        val sessionName = sessionModel.displayName.ifBlank { sessionModel.id }
        val fallback = providerRepository.resolveCompactFallbackCandidates()
            .firstOrNull { it.second.model.id != sessionModel.id }
            ?.let { (instance, entry) ->
                val apiKey = runCatching { providerRepository.usableApiKey(instance) }.getOrNull()
                val provider = apiKey?.let {
                    runCatching { ProviderFactory.create(instance, it, entry.model, context) }.getOrNull()
                }
                if (provider == null) null
                else CompactAttempt(
                    kind = "fallback",
                    label = entry.model.displayName.ifBlank { entry.model.id },
                    provider = provider,
                    model = entry.model,
                    nextHint = "truncate",
                    temperature = entry.overrides.temperature,
                )
            }
        val sessionTemperature = samplingTemperature(_activeEntryId.value)
        val second = fallback ?: CompactAttempt(
            kind = "session-retry",
            label = sessionName,
            provider = sessionProvider,
            model = sessionModel,
            nextHint = "truncate",
            temperature = sessionTemperature,
        )
        return listOf(
            CompactAttempt(
                kind = "session",
                label = sessionName,
                provider = sessionProvider,
                model = sessionModel,
                nextHint = if (fallback != null) "fallback" else "session-retry",
                temperature = sessionTemperature,
            ),
            second,
        )
    }


    /**
     * Should a failed summary attempt be retried by splitting the input in half?
     *
     * Ported from iOS `isSegmentRetryableError`
     * (AIChatViewModel+Compaction.swift:1010, T-compact-segment-retry-any-error).
     *
     * Everything EXCEPT the two cases where a smaller request cannot help:
     *   - cancellation — the user (or a session switch) stopped the work, so a
     *     retry would fight that and immediately throw again;
     *   - network/offline — the request never reached a model, so payload size
     *     is irrelevant and splitting just doubles the failed round-trips.
     *
     * This deliberately REPLACES [isContextTooLargeError] on the split path.
     * That substring allow-list tried to enumerate how every provider words an
     * over-length refusal and was provably incomplete — OpenMinis#133's
     * `[context_length_exceeded] Your input exceeds the context window of this
     * model` slipped past several variants — and every miss silently disabled
     * splitting, so compaction failed outright instead of retrying smaller.
     *
     * Splitting on an unclassified error is still the default, for that reason:
     * a summary built from halves beats no summary, so the burden of proof is
     * on NOT retrying.
     *
     * [T-android-compact-runaway] What changed is that "unclassified" no longer
     * means "everything". Splitting only helps when the failure was caused by
     * the SIZE of the request, and two error classes are known not to be:
     *
     *   - [LLMError.RateLimited] (429). The model is refusing on quota, not on
     *     length. Halving turns one rejected call into two rejected calls, each
     *     still subject to the provider's backoff — this is the exact shape
     *     that turned a single failure into ~15 sequential slow calls and the
     *     15-20 minute apparent hang users reported.
     *   - [LLMError.TransientError] (5xx / upstream). A server-side fault is
     *     independent of payload; retrying smaller just multiplies the outage.
     *
     * Both are better served by failing fast and letting the user retry the
     * whole compaction once conditions change.
     */
    internal fun isSegmentRetryableError(error: Throwable): Boolean =
        shouldSplitOnError(error)

    /**
     * Match provider error text against the substring set iOS
     * `isContextTooLargeError` used before T-compact-segment-retry-any-error.
     *
     * NO LONGER gates segment retry — [isSegmentRetryableError] does, for the
     * reasons documented there. Retained only for user-facing wording, where
     * guessing wrong costs a less specific message rather than a failed
     * compaction.
     */
    @Suppress("unused")
    private fun isContextTooLargeError(error: Throwable): Boolean {
        val desc = (error.message ?: error.toString()).lowercase()
        return desc.contains("too many tokens") ||
            desc.contains("context length") ||
            desc.contains("max_tokens") ||
            desc.contains("content is too long") ||
            desc.contains("exceeds the model") ||
            desc.contains("request too large") ||
            desc.contains("prompt is too long") ||
            desc.contains("token limit") ||
            desc.contains("context window")
    }

    /**
     * Consult [ContextPolicy] before sending. Returns true to proceed. The
     * Android MVP doesn't surface a "Compact before send" dialog (iOS does),
     * so we only warn via [appendSystemInfo] at the `needsCompact` /
     * `exhausted` boundaries and still allow the send. That gives the user
     * a signal to invoke `/compact` explicitly without blocking their turn.
     */
    internal fun checkContextBeforeSend(): PreSendContextAction {
        val tokens = contextTokensForPolicy()
        if (tokens <= 0) return PreSendContextAction.PROCEED
        // [T-context-window-live-read] Live window (entry re-resolved + group
        // contextLimitTokens folded in) — not the currentModel snapshot.
        val window = effectiveContextWindowTokens() ?: return PreSendContextAction.PROCEED
        val policy = ContextPolicy.forContextWindow(window, effectiveCompactPercent())
        return when (policy.check(tokens, window)) {
            ContextPolicy.CheckResult.OK -> PreSendContextAction.PROCEED

            // Mirrors iOS AIChatViewModel.swift:2224. Previously Android only
            // appended a notice here and sent anyway, which meant the very
            // request that tripped the threshold still went out over-length —
            // the warning arrived alongside the failure it was meant to avoid.
            ContextPolicy.CheckResult.NEEDS_COMPACT -> {
                if (effectiveAutoCompact()) {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — auto-compacting (pref on)",
                    )
                    PreSendContextAction.COMPACT_THEN_SEND
                } else {
                    AppLogger.info(
                        TAG,
                        "[Context] pre-send near capacity ($tokens / $window) — prompting user",
                    )
                    PreSendContextAction.ASK_USER
                }
            }

            // Exhausted tiers have compactThreshold = 0 by policy: the window is
            // too small for a summary to pay for itself, so compacting is not
            // on offer. Keep the existing advisory-and-proceed behaviour rather
            // than blocking the user out of their own chat.
            ContextPolicy.CheckResult.EXHAUSTED -> {
                appendSystemInfo(
                    text = "Context is near the model's limit ($tokens / $window tokens). Start a new chat or /compact to continue reliably.",
                    iconKind = "compact",
                )
                PreSendContextAction.PROCEED
            }
        }
    }

    /** What the pre-send context check decided. Mirrors iOS's send() branch. */
    internal enum class PreSendContextAction {
        /** Under threshold (or nothing useful to do) — send as normal. */
        PROCEED,

        /** Auto-compact is on — compact silently, then send. */
        COMPACT_THEN_SEND,

        /** Auto-compact is off — raise the dialog and let the user choose. */
        ASK_USER,
    }

    /**
     * Text + attachments held back while the "Context Near Capacity" dialog is
     * up. Mirrors iOS `pendingSendText` / `pendingSendAttachments`.
     */
    internal var pendingSendText: String? = null

    internal val _showCompactBeforeSendPrompt = MutableStateFlow(false)
    val showCompactBeforeSendPrompt: StateFlow<Boolean> = _showCompactBeforeSendPrompt.asStateFlow()

    /**
     * Dialog action: compact the history, then send what the user was holding.
     * [alsoEnableAutoCompact] backs iOS's one-tap opt-in button, which compacts
     * now AND remembers the choice for every future conversation.
     */
    fun compactAndSendPending(alsoEnableAutoCompact: Boolean = false) {
        if (alsoEnableAutoCompact) setAutoCompactEnabled(true)
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        viewModelScope.launch {
            val ok = awaitCompaction()
            if (!ok) {
                AppLogger.warning(TAG, "[Context] pre-send compaction failed — sending anyway")
            }
            sendMessage(text, skipContextCheck = true)
        }
    }

    /** Dialog action: send without compacting. */
    fun sendPendingWithoutCompacting() {
        _showCompactBeforeSendPrompt.value = false
        val text = pendingSendText ?: return
        pendingSendText = null
        sendMessage(text, skipContextCheck = true)
    }

    /** Dialog dismissed — restore the text to the composer so it isn't lost. */
    fun cancelCompactBeforeSend() {
        _showCompactBeforeSendPrompt.value = false
        pendingSendText?.let { _inputText.value = it }
        pendingSendText = null
    }

    /**
     * [T-android-auto-compact-inloop] What the in-loop context guard decided.
     */
    internal enum class InLoopContextAction {
        /** Under threshold — issue the next API call as normal. */
        PROCEED,

        /** History was compacted in place; re-run the iteration. */
        COMPACTED,

        /** Cannot recover — stop the turn safely and let the user resume. */
        STOP,
    }

    /**
     * [T-android-auto-compact-inloop] Run [compactAll] with the in-loop flag and
     * suspend until it settles. Returns whether it actually compacted.
     *
     * `compactAll` is fire-and-forget (it launches its own IO coroutine), so the
     * loop cannot simply call it and continue — the next API call would read the
     * pre-compaction history and the guard would fire again immediately.
     */
    internal suspend fun awaitCompaction(): Boolean =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            var resumed = false
            compactAll(allowDuringProcessing = true) { ok ->
                // compactAll guarantees exactly one callback, but guard anyway:
                // resuming a continuation twice throws.
                if (!resumed) {
                    resumed = true
                    if (cont.isActive) cont.resume(ok) { _, _, _ -> }
                }
            }
        }

    /**
     * System prompt for the single-shot summarisation call. Matches iOS
     * wording so cross-device summaries stay stylistically aligned.
     */
    private val compactSummarySystemPrompt: String = """
        You are a context compaction engine. Your summary will REPLACE the original messages in the conversation context window. The agent will read your summary as past context, then proceed based on the user's NEXT message — your summary is background, not a standing work order. Write the summary in the same language the user used in the conversation.

        MUST PRESERVE (never omit or shorten):
        - All file paths, directory names, URLs, UUIDs, and identifiers — copy verbatim
        - Commands executed and their outcomes (success/failure/output)
        - What was requested and what was done (record as past events, not as ongoing goals)
        - Key decisions made and their rationale
        - Errors encountered and how they were resolved
        - Important constraints, rules, or user preferences mentioned
        - Any tool calls and their results that affect current state

        STRUCTURE:
        1. Start with a one-line description of what the conversation was about (use past tense — "User asked X, agent did Y", NOT "Goal: X").
        2. Then a concise narrative of what happened, preserving technical details.
        3. End with a "What had been done so far" section listing completed work — NOT a "todo" or "pending" list. Do not invent ongoing objectives or carry-over tasks from old turns; if the user wants to continue, they will say so in their next message.

        PRIORITIZE recent context over older history — recent decisions and recent file/path references are most useful for continuity.

        Do NOT translate or alter code snippets, file paths, identifiers, or error messages. Be concise but never lose information the agent needs.
    """.trimIndent()

    // T203 part 2: these MUST be declared before `init { loadSession() }` below.
    // viewModelScope.launch defaults to Dispatchers.Main.immediate, which runs
    // the launch body synchronously up to the first suspend point — and the
    // launch body reads `isDraft` before its first suspend. If `isDraft` is
    // declared further down the class, its property initializer hasn't run yet,
    // so the read returns the JVM default (`false`), routing every draft
    // session through the load-from-DB branch. The DB lookup misses (no row
    // for `__new__…` keys), the function returns early, and no model name /
    // group name is ever set on the draft chat — exactly the bug T203 was
    // chasing through the wrong layer.
    /** Whether this is a draft session (not yet persisted to DB). */
    internal val isDraft: Boolean = sessionId.startsWith("__new__")

    /** Model group ID from long-press FAB, encoded in the draft session ID.
     *  substringBefore strips the folder marker in case both are present. */
    internal val initialGroupId: String? =
        sessionId.substringAfter("__grp__", "").substringBefore("__fld__")
            .takeIf { it.isNotEmpty() }

    /** Session-group (folder) id from the folder card's "New Chat in Group"
     *  menu item, encoded in the draft id. Filed at draft promotion — the
     *  folder_id row can only exist once the session does (iOS defers the
     *  same way via pendingFolderDraft). */
    internal val initialFolderId: String? =
        sessionId.substringAfter("__fld__", "").substringBefore("__grp__")
            .takeIf { it.isNotEmpty() }

    /** The real session ID (same as sessionId for existing sessions, generated on first message for drafts). */
    internal var realSessionId: String = if (isDraft) "" else sessionId

    private var unsubscribeSafeMode: (() -> Unit)? = null
    private var preserveShellOnClear = false
    private var initialScopeJobs: Set<Job> = emptySet()

    init {
        viewModelScope.launch {
            _isStreaming.collect { if (!it) ChatViewModelStore.scheduleTrim() }
        }
        loadSession()
        // [T-session-paused-badge-active-false-positive] Drive the session-list
        // PAUSED badge directly off canResume — the authoritative "this session
        // is interrupted (tap Resume)" flag. This is the single chokepoint over
        // every _canResume setter (background-suspend cleanup, cancel cleanup,
        // loadSession DB detection, …): canResume true → badge on; false
        // (resumed / new send / completed) → badge off. Replaces both the old
        // foreground heuristic AND clear-on-open, so a session the user merely
        // glanced at but didn't resume keeps its badge, and a running/resolved
        // session never shows one.
        viewModelScope.launch {
            com.openminis.app.service.OverlayComposeBus.pending.collect { req ->
                val sid = realSessionId.ifBlank { sessionId }
                if (req.sessionId != sessionId && req.sessionId != sid) return@collect
                com.openminis.app.service.OverlayComposeBus.consumeSticky(req.sessionId)
                if (_isStreaming.value) enqueuePrompt(req.text) else sendMessage(req.text)
            }
        }
        viewModelScope.launch {
            sessionLoaded.first { it }
            val sid = realSessionId.ifBlank { sessionId }
            val sticky = com.openminis.app.service.OverlayComposeBus.consumeSticky(sid)
                ?: com.openminis.app.service.OverlayComposeBus.consumeSticky(sessionId)
            if (!sticky.isNullOrBlank()) {
                if (_isStreaming.value) enqueuePrompt(sticky) else sendMessage(sticky)
            }
        }
        viewModelScope.launch {
            canResume.collect { interrupted ->
                if (interrupted) {
                    // [T-android-group-pause-badge-restamp] Only a REAL
                    // interruption re-stamps the badge's entry time. This
                    // collector is the single chokepoint over every
                    // `_canResume` setter, so it ALSO fires when loadSession
                    // merely RE-DETECTS an old interrupted tail — that is not
                    // a new entry into the paused state, and re-stamping it
                    // there is what let a days-old pause keep looking "fresh"
                    // to the group card's 24h window forever (the more often
                    // the user opened the chat, the less able it was to
                    // expire). The detecting site raises a sticky generation
                    // mark before its assignment; we consume it here, once the
                    // annotated emission has actually been observed.
                    val pendingGen = redetectingInterruptedTailGen
                    val isRedetection = pendingGen != consumedRedetectGen
                    if (isRedetection) consumedRedetectGen = pendingGen
                    com.openminis.app.service.SessionBadgeStore.push(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                        restamp = !isRedetection,
                    )
                } else {
                    com.openminis.app.service.SessionBadgeStore.remove(
                        sessionId,
                        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED,
                    )
                }
            }
        }
        // T-android-crash-safe-mode-v2: when the user dismisses the
        // safe-mode dialog, retry the restore that we skipped during
        // cold start. loadSession() is idempotent (re-checks isSafeMode
        // on entry; sessionLoaded gate prevents double-population), so
        // this is a clean "now finish the work you skipped" hook.
        unsubscribeSafeMode = com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener {
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    runCatching { loadSession() }
                        .onFailure {
                            android.util.Log.w(
                                TAG,
                                "safe-mode-cleared retry loadSession failed: ${it.message}",
                            )
                        }
                }
            }
        // Re-resolve provider when config changes (models may load async)
        viewModelScope.launch {
            // T306: wait for loadSession to finish BEFORE observing config.
            //
            // Pre-T306 we used a "skip first replay" trick that broke under
            // a real race: loadSession suspends inside `chatRepository.getSession`,
            // so when ProviderRepository finishes its async config load and
            // emits the populated value, the collector can fire BEFORE
            // loadSession's `restoreFromBinding(session.modelBinding)` runs.
            // The collector then resolves to the default group's first
            // entry (X), `_modelName` flips to X, and seconds later
            // restoreFromBinding finds Y and re-sets `_modelName` to Y —
            // exactly the "top model picker first shows X, then flickers and switches to Y"
            // the user reported after a fallback persisted Y.
            //
            // Awaiting `sessionLoaded == true` here means loadSession has
            // already had its turn at the persisted binding (success or
            // failure). After that, the `currentProvider == null` guard
            // below correctly captures BOTH the draft case (no binding,
            // currentProvider may still be null because config hadn't
            // loaded yet during loadSession) AND the existing-session
            // case where binding restore failed, while leaving alone any
            // session whose binding successfully resolved to its target.
            sessionLoaded.first { it }
            providerRepository.config.collect { config ->
                // T278: _availableGroups feeds the model picker sheet — it must
                // track the latest config on every emission, even after the user
                // has selected a model (currentProvider != null). The guard below
                // is for the fallback-resolution path which CAN trample the user's
                // selection; _availableGroups has no such risk because the sheet
                // re-reads it on each open.
                _availableGroups.value = config.modelGroups
                // [T-android-disabled-provider-still-selectable-via-group #34]
                // Runtime re-resolution when a GROUP-bound session's currently
                // active member has its provider DISABLED mid-session. The
                // selection paths (resolveProviderFromGroup → enabledMemberEntries)
                // already skip disabled members, but they only run while
                // currentProvider == null (cold start / fallback). Once a group
                // member is resolved, currentProvider is cached and the guard
                // below short-circuits — so if the user then disables that
                // member's provider (e.g. a Coding Plan whose quota ran out,
                // turned off to force fallback to the next provider), the stale
                // currentProvider keeps routing to the disabled provider's
                // pay-as-you-go model and bills them. Mirror iOS resolveCurrentEntry
                // (a306ce08): when the active entry's provider is no longer
                // enabled, re-resolve the group to its next enabled member. Only
                // for group bindings — a deliberate direct-entry pick is left
                // untouched (it has no in-group alternative to fall back to).
                val groupBound = _selectedGroupId.value
                val activeEntry = _activeEntryId.value
                if (currentProvider != null && groupBound != null && activeEntry != null &&
                    config.modelEntries.isNotEmpty() &&
                    !providerRepository.isEntryProviderEnabled(activeEntry)
                ) {
                    val before = activeEntry
                    if (resolveProviderFromGroup(groupBound)) {
                        AppLogger.info(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled — re-resolved to entry=${_activeEntryId.value} model=${currentModel?.id}",
                        )
                        // Persist the re-resolved member so a reload doesn't snap
                        // back to the disabled one. resolveProviderFromGroup set
                        // _activeEntryId to the actually-resolved member.
                        _activeEntryId.value?.let {
                            persistBinding("""{"type":"group","groupId":"$groupBound","lastEntryId":"$it"}""")
                        }
                    } else {
                        // Whole group is now unavailable (all members disabled /
                        // credential-less) — fall through to the default group /
                        // new-chat fallback chain by clearing the cached provider
                        // so the guard below re-runs the standard resolution.
                        AppLogger.warning(
                            TAG,
                            "🔀RESOLVE group=$groupBound active entry=$before provider disabled and group has no enabled member — falling back",
                        )
                        currentProvider = null
                    }
                }
                if (currentProvider == null && config.modelEntries.isNotEmpty()) {
                    // T306: re-attempt the persisted binding now that config
                    // has entries. For an existing session whose loadSession
                    // ran before config finished (so restoreFromBinding fell
                    // through), the binding pointed at the right entry all
                    // along — we just couldn't resolve it. Try it again
                    // before falling back to the default group, so the
                    // fallback target survives a cold start that races
                    // ProviderRepository's async load.
                    val sid = realSessionId.takeIf { it.isNotEmpty() }
                    if (sid != null) {
                        val session = runCatching { chatRepository.getSession(sid) }.getOrNull()
                        if (session?.modelBinding != null && restoreFromBinding(session.modelBinding)) {
                            return@collect
                        }
                    }
                    if (!applyDefaultPrimarySlot(initialGroupId, applyGroupDefaults = false)) {
                        // [T-newchat-default-model-fallback-android] Same
                        // new-chat fallback chain as the draft branch in
                        // loadSession: last-used → newest-provider/newest-text.
                        // Was allVisibleEntries().firstOrNull().
                        applyNewChatDefaultModel()
                    }
                }
            }
        }
    }

    init {
        // Long-lived initial collectors are cancelled on eviction; later jobs
        // (DB writes, sends, imports, etc.) must finish before a VM is eligible.
        initialScopeJobs = viewModelScope.coroutineContext[Job]?.children?.toSet().orEmpty()
    }

    /**
     * Session ID that disk/shell-bound resources must use. Until the user sends
     * the first message, `realSessionId` is empty and we fall back to the draft
     * key. After `ensureSession()` runs, this returns the persisted id so
     * `/var/minis/{attachments,workspace,...}` mounts, browser artifacts, and
     * the PersistentShell all land in a single directory that survives re-entry.
     */
    internal val activeSessionId: String
        get() = realSessionId.ifEmpty { sessionId }

    /** Public accessor used by ChatScreen to resolve session-scoped minis:// links. */
    val currentSessionId: String
        get() = activeSessionId

    /** T-chat-title-pill-edit: load the persisted [ChatSessionEntity] for the
     *  current session so the shared edit-title sheet (reused from the session
     *  list) can be opened from the in-chat title pill. Returns null for
     *  drafts that haven't been persisted yet. */
    suspend fun loadSessionEntity(): com.openminis.app.data.db.ChatSessionEntity? {
        val sid = realSessionId.ifEmpty { return null }
        return runCatching { chatRepository.getSession(sid) }.getOrNull()
    }

    /** T-chat-title-pill-edit: update title + category from the in-chat
     *  edit sheet. Mirrors SessionListViewModel.updateTitleAndCategory but
     *  also refreshes the local StateFlows so the pill updates immediately
     *  without waiting for a session reload. */
    fun updateTitleAndCategory(title: String, category: String?) {
        val sid = realSessionId.ifEmpty { return }
        viewModelScope.launch {
            chatRepository.updateSessionTitleAndCategory(sid, title, category)
            _sessionTitle.value = title.ifBlank { "New Chat" }
            _sessionCategory.value = category
        }
    }


    /**
     * Move every per-session disk resource from the draft directory to the
     * real one, and tear down any shell that was started against the draft id.
     *
     * The draft key leaks into persistent shells (`ExecutionCoordinator`),
     * browser artifacts (`persistBrowserArtifact`), and the `BrowserTabPool`'s
     * cookie/state store. Before this migration ran, a tool invocation that
     * happened before the user's first message would write into the draft's
     * `minis-sessions/__new__{uuid}` directory and become invisible the
     * moment the VM was recreated under the real id — exactly the symptom
     * observed with the Chinese-named TikTok download that appeared to
     * "disappear" after `yt-dlp` reported success.
     */
    internal fun migrateDraftResources(fromDraft: String, toReal: String) {
        // Stop any shell that was already spun up against the draft id; its
        // -b mount arguments were frozen to the draft directory at launch, so
        // we can't reuse it after the migration.
        runCatching { ExecutionCoordinator.sessionDidTerminate(fromDraft) }

        val base = java.io.File(context.filesDir, "minis-sessions")
        val draftBase = java.io.File(base, fromDraft)
        if (!draftBase.isDirectory) return
        val realBase = java.io.File(base, toReal).apply { mkdirs() }

        com.openminis.app.sandbox.SessionWorkspace.SESSION_SUBDIRS.forEach { subdir ->
            val src = java.io.File(draftBase, subdir)
            if (!src.isDirectory) return@forEach
            val dst = java.io.File(realBase, subdir).apply { mkdirs() }
            src.listFiles()?.forEach { child ->
                val target = java.io.File(dst, child.name)
                runCatching {
                    if (!target.exists() && !child.renameTo(target)) {
                        copyRecursive(child, target)
                    }
                }.onFailure {
                    android.util.Log.w("ChatViewModel",
                        "migrateDraftResources: failed to move ${child.absolutePath} -> ${target.absolutePath}: ${it.message}")
                }
            }
        }
        runCatching { draftBase.deleteRecursively() }

        // Also rename the BrowserTabPool saved-state file (filesDir/browser_tabs/<sid>.json).
        // Otherwise the pool will load empty state on the next re-entry and the
        // user loses their open tabs even though the URLs never truly "went away".
        val tabsDir = java.io.File(context.filesDir, "browser_tabs")
        val draftTabs = java.io.File(tabsDir, "$fromDraft.json")
        if (draftTabs.exists()) {
            val realTabs = java.io.File(tabsDir, "$toReal.json")
            runCatching {
                if (!realTabs.exists()) {
                    if (!draftTabs.renameTo(realTabs)) {
                        draftTabs.copyTo(realTabs, overwrite = false)
                        draftTabs.delete()
                    }
                }
            }
        }
    }

    private fun copyRecursive(src: java.io.File, dst: java.io.File): Boolean = runCatching {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.all { copyRecursive(it, java.io.File(dst, it.name)) } ?: true
        } else {
            src.copyTo(dst, overwrite = false)
            src.delete()
            true
        }
    }.getOrDefault(false)


    /**
     * Mark every non-system UI message that falls before [marker]'s boundary
     * as [ChatMessage.isCompactedHistory]. Mirrors iOS Phase 2.5 boundary
     * resolution (AIChatViewModel.swift:3380-3411) but with one improvement
     * over iOS for the compactAll case:
     *
     *   1) `firstKeptMessageId` — first kept message (divider goes BEFORE it)
     *   2) `boundaryMessageId`  — legacy alias of firstKeptMessageId
     *   3) Both null → compactAll. iOS naively places the divider at the end
     *      and grays every loaded UI message, which incorrectly gray-scales
     *      messages persisted AFTER the marker (e.g. follow-up turns sent
     *      between compact and reload). We instead use
     *      `lastCompactedMessageId` to find the last message included in the
     *      compacted range — anything after it stays active. The divider is
     *      placed immediately after that boundary.
     */

    /**
     * createdAt self-heal: return the LAST raw message whose
     * `createdAt < markerCreatedAt` AND whose id is still represented in
     * agentHistory (filtered via [historyDbIds]). When [historyDbIds] is
     * empty (no dbIds collected — unusual), the filter degrades to "just
     * the createdAt predicate" so we still recover SOMETHING.
     *
     * Mirrors iOS AIChatViewModel+Compaction.swift:125.
     */
    internal fun anchorByCreatedAt(
        rawMessages: List<com.openminis.app.data.db.MessageEntity>,
        markerCreatedAt: Long,
        historyDbIds: Set<String>,
    ): com.openminis.app.data.db.MessageEntity? {
        return rawMessages.lastOrNull { raw ->
            raw.createdAt < markerCreatedAt &&
                (historyDbIds.isEmpty() || raw.id in historyDbIds)
        }
    }

    /**
     * Build a healed v2 marker that preserves identity (id, sessionId,
     * summary, createdAt, compactedCount) but swaps `lastCompactedMessageId`
     * to the recomputed anchor, zeroes legacy fields, and bumps `version`
     * to 2. Future loads resolve through the corrected lcmId directly
     * without re-running the createdAt fallback.
     *
     * Mirrors iOS AIChatViewModel+Compaction.swift:150.
     */
    internal fun rewriteMarkerForHeal(
        original: com.openminis.app.data.db.CompactMarkerEntity,
        newAnchor: com.openminis.app.data.db.MessageEntity,
        lastRaw: com.openminis.app.data.db.MessageEntity?,
    ): com.openminis.app.data.db.CompactMarkerEntity {
        // Legacy sort-order fallback writes a past-end sentinel so any
        // hypothetical v1 reader sees "everything compacted, nothing
        // kept" (graceful degradation, no overlap with live tail).
        // Android's MessageEntity doesn't carry a sortOrder column —
        // use Int.MAX_VALUE like the original compactAll write path.
        return original.copy(
            firstKeptSortOrder = Int.MAX_VALUE,
            boundaryMessageId = null,
            firstKeptMessageId = null,
            lastCompactedMessageId = newAnchor.id,
            uiBoundarySortOrder = null,
            version = 2,
        )
    }

    /**
     * Apply a single model entry pin (`entry:` slot or a restored binding).
     * Does not set a group id — the pin is the model, not a group.
     */
    private fun applyPinnedModelEntry(entryId: String): Boolean {
        val entry = providerRepository.config.value.modelEntries.find { it.id == entryId } ?: return false
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
        if (!providerRepository.hasAnyCredential(instance)) return false
        val apiKey = providerRepository.usableApiKey(instance) ?: ""
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupId.value = null
        _selectedGroupName.value = ""
        _activeEntryId.value = entry.id
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
        return true
    }

    /**
     * New-chat model: an `entry:` default pin, otherwise the selected or default group.
     * Group ids that are entry pins are not passed to group lookup.
     */
    internal fun applyDefaultPrimarySlot(initialGroupId: String?, applyGroupDefaults: Boolean): Boolean {
        if (initialGroupId == null) {
            val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(providerRepository.defaultPrimaryGroupId)
            if (pinned != null) return applyPinnedModelEntry(pinned)
        }
        val effectiveGroupId = initialGroupId ?: providerRepository.defaultPrimaryGroupId?.takeUnless {
            com.openminis.app.data.model.ModelSlotRef.isEntry(it)
        }
        if (effectiveGroupId == null) return false
        val resolved = resolveProviderFromGroup(effectiveGroupId)
        if (resolved) {
            _selectedGroupId.value = effectiveGroupId
            if (applyGroupDefaults) applyGroupSessionDefaults(effectiveGroupId)
        }
        return resolved
    }

    /** Restore provider state from a JSON binding string. Returns true if successfully resolved. */
    internal fun restoreFromBinding(bindingJson: String?): Boolean {
        bindingJson ?: return false
        return try {
            val obj = org.json.JSONObject(bindingJson)
            when (obj.optString("type")) {
                "group" -> {
                    val groupId = obj.optString("groupId").takeIf { it.isNotEmpty() } ?: return false
                    val lastEntryId = obj.optString("lastEntryId").takeIf { it.isNotEmpty() }
                    val resolved = resolveProviderFromGroup(groupId, lastEntryId)
                    if (resolved) _selectedGroupId.value = groupId
                    resolved
                }
                "entry" -> {
                    val entryId = obj.optString("entryId").takeIf { it.isNotEmpty() } ?: return false
                    applyPinnedModelEntry(entryId)
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }


    fun selectGroup(groupId: String) {
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId"}""")
            applyGroupSessionDefaults(groupId)
        }
    }

    /** Select a specific entry within a group (keeps group selected). */
    fun selectGroupEntry(groupId: String, entryId: String) {
        _selectedGroupId.value = groupId
        _selectedGroupName.value = providerRepository.group(groupId)?.name ?: ""
        val resolved = resolveProviderFromGroup(groupId, entryId)
        if (resolved) {
            persistBinding("""{"type":"group","groupId":"$groupId","lastEntryId":"$entryId"}""")
            applyGroupSessionDefaults(groupId)
            // [T-newchat-default-model-fallback-android] Record the actually-
            // resolved active entry as last-used (resolveProviderFromGroup may
            // fall back off a disabled member, so _activeEntryId is the truth).
            _activeEntryId.value?.let { providerRepository.lastUsedEntryId = it }
        }
    }

    /**
     * T312: mirrors iOS `AIChatViewModel.applyGroupSessionDefaults`.
     * When a session newly binds to a group (user picks the group, or a
     * draft session resolves the default group), copy the group's
     * `defaultThinkingLevel` into the session's persisted thinking_override.
     * Context limit is in-memory only on iOS; Android has no equivalent
     * runtime field yet, so we only handle thinking level here.
     *
     * Skips when the group has no default override (null) — leaves the
     * session's existing override untouched so manual user choices on a
     * pre-bound chat aren't clobbered by a later group re-select that
     * happens to land on the same default state.
     */
    private fun applyGroupSessionDefaults(groupId: String) {
        val group = providerRepository.group(groupId) ?: return
        val level = group.defaultThinkingLevel ?: return
        if (_thinkingLevel.value == level) return
        _thinkingLevel.value = level
        viewModelScope.launch {
            val sid = ensureSession()
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }
    }

    /**
     * [T-newchat-default-model-fallback-android] Resolve and apply the default
     * model for a NEW chat when no default group produced a model. Fallback
     * chain tiers 2→3 (tier 1, the default group, is handled by the caller
     * before this runs):
     *
     *   2) last-used model — the entry the user last actively selected / used,
     *      if it still exists, is visible, and its provider is enabled.
     *   3) newest provider's newest text-output model — the final catch-all so
     *      a first-ever chat with providers but no group/last-used still gets a
     *      sensible, text-capable default (image/audio-only models excluded).
     *
     * Sets currentModel / currentProvider / the name + activeEntry state flows.
     * Returns true when a model was applied. Mirrors iOS #636. The legacy
     * behaviour here was `allVisibleEntries().firstOrNull()` (the FIRST entry),
     * which ignored both last-used and add-order — replaced by this chain.
     */
    internal fun applyNewChatDefaultModel(): Boolean {
        val entry = providerRepository.lastUsedVisibleEntry()
            ?: providerRepository.newestProviderNewestTextEntry()
            ?: return false
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return false
        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _activeEntryId.value = entry.id
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        // [T-android-group-resolve-skip-uncredentialed] Build the provider for
        // an OAuth instance too — otherwise this tier set the model name in the
        // UI but left currentProvider null, and the first send failed.
        if (providerRepository.hasAnyCredential(instance)) {
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
        }
        return true
    }

    /** Select a specific model entry (bypasses group selection). */
    override fun selectEntry(entryId: String) {
        val config = providerRepository.config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return
        // [T-android-group-resolve-skip-uncredentialed] The user explicitly
        // tapped this model; refusing it because the API-key slot is empty
        // made OAuth models unselectable from the picker.
        if (!providerRepository.hasAnyCredential(instance)) return
        val apiKey = providerRepository.usableApiKey(instance) ?: ""

        currentModel = entry.model
        _modelName.value = entry.model.displayName
        _providerName.value = instance.label.ifEmpty { entry.model.provider }
        _selectedGroupId.value = null
        _selectedGroupName.value = ""
        _activeEntryId.value = entry.id
        currentProvider = ProviderFactory.create(instance, apiKey, entry.model, context)
        persistBinding("""{"type":"entry","entryId":"$entryId"}""")
        // [T-newchat-default-model-fallback-android] Remember this as the
        // global last-used model so the NEXT new chat (when no default group
        // is set) defaults back to it. Tier 2 of the new-chat fallback chain.
        providerRepository.lastUsedEntryId = entryId
    }

    /** Persist the model binding to the DB session (no-op for draft sessions). */
    internal fun persistBinding(bindingJson: String) {
        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: return
        val modelId = currentModel?.id ?: return
        viewModelScope.launch {
            chatRepository.updateSessionBinding(sid, bindingJson, modelId)
        }
    }

    internal fun findModelEntry(modelId: String) =
        providerRepository.allVisibleEntries().find { it.model.id == modelId }

    /**
     * Build the ordered list of fallback providers for the current group,
     * starting AFTER the primary provider in the member list and cycling around.
     * This ensures that models already tried (before the primary) are at the end,
     * not the beginning — so retry doesn't re-trigger the same fallback chain.
     */
    /**
     * [T-android-fallback-entry-identity] A fallback candidate, carrying the
     * ENTRY it was built from.
     *
     * The entry id is the only unambiguous identity: two different provider
     * instances can expose the SAME `model.id` (observed in the field:
     * `deepseek-v4-flash` exists under both "DeekSeak" — api.deepseek.com — and
     * "Bailian OpenAI" — dashscope.aliyuncs.com). Recovering the entry after the
     * fact by matching `model.id` therefore picks whichever entry happens to
     * come first in `modelEntries`, which is not necessarily the one that served
     * the request.
     */
    internal data class FallbackCandidate(
        val provider: LLMProvider,
        val entryId: String,
    )

    internal fun buildFallbackProviders(primaryProvider: LLMProvider): List<FallbackCandidate> {
        // Settings → Model Groups only. A model picked under a provider
        // section never sets _selectedGroupId, so this returns empty and
        // the turn stays on that one model.
        val groupId = _selectedGroupId.value ?: return emptyList()
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val members = group.memberEntryIds
        // Find current provider's position in the group.
        // [T-android-fallback-entry-identity] Prefer the ACTIVE ENTRY id — the
        // model-id match below is ambiguous when two instances share a model id
        // and would anchor the cycle at the wrong member.
        val activeEntry = _activeEntryId.value
        val currentIdx = members.indexOfFirst { it == activeEntry }.takeIf { it >= 0 }
            ?: members.indexOfFirst { entryId ->
                config.modelEntries.find { it.id == entryId }?.model?.id == primaryProvider.model.id
            }
        val result = mutableListOf<FallbackCandidate>()
        // Iterate starting from the entry AFTER the primary, cycling around
        for (offset in 1 until members.size) {
            val idx = if (currentIdx >= 0) (currentIdx + offset) % members.size else offset
            val entryId = members[idx]
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!instance.isEnabled) continue
            // [T-android-group-resolve-skip-uncredentialed] Credential test via
            // hasAnyCredential so an OAuth-logged-in provider is kept as a
            // fallback candidate; usableApiKey alone reads only the API-key
            // slot and dropped every OAuth member from the chain.
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""
            val p = try {
                ProviderFactory.create(instance, apiKey, entry.model, context)
            } catch (_: Exception) { continue }
            // Same host+key+model is the same relay bucket; skip it so fallback
            // actually moves to another model name or another key.
            if (com.openminis.app.provider.ProviderKeyGate.sameBucket(p.callGateKey, primaryProvider.callGateKey)) continue
            result.add(FallbackCandidate(provider = p, entryId = entry.id))
        }
        return result
    }

    /**
     * Group members that fallback skipped (disabled instance / missing
     * credential / hidden entry), with reasons. Mirrors iOS
     * ModelGroupRouter.unavailableMembers: when fallback exhausts, the user
     * needs to know WHY the other group members never got tried — e.g. the
     * Claude subscription was logged out, so every Anthropic entry was
     * silently filtered and fallback kept cycling OpenAI-only.
     */
    internal fun unavailableGroupMembers(): List<String> {
        val groupId = _selectedGroupId.value ?: return emptyList()
        val config = providerRepository.config.value
        val group = config.modelGroups.find { it.id == groupId } ?: return emptyList()
        val result = mutableListOf<String>()
        for (entryId in group.memberEntryIds) {
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            val label = instance.label.ifEmpty { entry.model.provider }
            val reason = when {
                entry.isHidden -> "Hidden"
                !instance.isEnabled -> "Disabled"
                // [T-android-group-resolve-skip-uncredentialed] Must match the
                // routing filter, or a provider the user IS signed into gets
                // reported as "Not logged in".
                !providerRepository.hasAnyCredential(instance) -> "Not logged in"
                else -> continue
            }
            result.add("⚠️ ${entry.model.displayName} ($label): $reason")
        }
        return result
    }

    private fun resolveNextFallbackProvider(): LLMProvider? {
        val groupId = _selectedGroupId.value ?: return null
        val group = providerRepository.group(groupId) ?: return null
        val currentEntryId = _activeEntryId.value ?: return null
        val currentIdx = group.memberEntryIds.indexOf(currentEntryId)
        if (currentIdx < 0) return null

        val config = providerRepository.config.value
        // Try next entries in the group
        for (i in 1 until group.memberEntryIds.size) {
            val nextIdx = (currentIdx + i) % group.memberEntryIds.size
            val entryId = group.memberEntryIds[nextIdx]
            val entry = config.modelEntries.find { it.id == entryId } ?: continue
            val instance = providerRepository.instance(entry.providerInstanceId) ?: continue
            // [T-disabled-provider-via-group-android] Skip disabled
            // providers when walking the group's fallback chain so a
            // disabled provider sitting after the current entry doesn't
            // get picked up. buildFallbackProviders already does this; the
            // single-step variant here had the same bug.
            if (!instance.isEnabled) continue
            // [T-android-group-resolve-skip-uncredentialed] Same credential
            // notion as buildFallbackProviders — OAuth members belong in the
            // single-step chain too.
            if (!providerRepository.hasAnyCredential(instance)) continue
            val apiKey = providerRepository.usableApiKey(instance) ?: ""

            currentModel = entry.model
            _modelName.value = entry.model.displayName
            _activeEntryId.value = entry.id
            val provider = ProviderFactory.create(instance, apiKey, entry.model, context)
            currentProvider = provider
            return provider
        }
        return null
    }

    // [T-android-split-chat] addAttachment / removeAttachment / clearAttachments
    // moved to ChatViewModelUiStateExt.kt (extension functions).

    /**
     * T137: Wipe in-memory and on-disk message state for the current session
     * without touching the session's chat files (workspace/, attachments/,
     * offloads/). Mirrors iOS [AIChatViewModel.clearChat] — same surface area,
     * same "files survive" guarantee.
     *
     * Cancels any in-flight stream first so the UI doesn't race the wipe.
     */
    fun clearChat() {
        if (_isStreaming.value) cancelStream()
        val sid = activeSessionId
        // T-streaming-side-channel: ensure no stale stream delta survives a
        // session wipe; the messages list is about to be cleared, so any
        // pending key would be orphaned.
        // [T-android-stream-flush-review] also cancel pending trailing flushes
        // so none re-adds an orphan side-channel entry after the wipe.
        clearAllStreamFlushStates()
        _streamingById.value = emptyMap()
        // Memory state — match iOS clearChat() field list one-for-one.
        _messages.value = emptyList()
        agentHistory.clear()
        _error.value = null
        _cachedLatestMarker = null
        _compactMarkerDetached = false
        toolLoopDetector.reset()
        _canResume.value = false
        _attachments.value = emptyList()
        _promptQueue.value = emptyList()
        _hasInjectedShareContent.value = false
        // T261: tool-detail sheet is per-session UI state — clear it so a
        // newly cleared chat doesn't briefly flash a stale tool's sheet
        // before the existence-guard catches up.
        _selectedToolDetailId.value = null
        // Drop any browser tabs the agent spawned for this session, and
        // delete the persisted tab snapshot so a future open starts clean.
        // iOS calls BrowserTabPool.deletePersistedData(for:) +
        // BrowserUseOffloadBridge.releasePool(forSession:); on Android the
        // pool is per-VM (lazy), so releasing tabs here is sufficient.
        _browserTabPoolRef?.releaseAllTabs()
        runCatching {
            java.io.File(context.filesDir, "browser_tabs/$sid.json").delete()
        }
        // Persist: drop messages + compact markers. Files (workspace,
        // attachments, offloads) intentionally retained.
        viewModelScope.launch {
            chatRepository.dao.deleteMessages(sid)
            chatRepository.dao.deleteCompactMarkers(sid)
            Log.i(TAG, "clearChat: session=$sid wiped (files preserved)")
        }
    }

    // ─── Share Injection (T51) ────────────────────────────────────────────

    /**
     * Whether the current input was seeded from a system share intent.
     * The "Move to…" capsule above the chat list is gated on this — once
     * the user starts a new turn or moves the share elsewhere we flip it
     * back to false. Mirrors iOS AIChatView.hasInjectedShareContent.
     */
    internal val _hasInjectedShareContent = kotlinx.coroutines.flow.MutableStateFlow(false)
    val hasInjectedShareContent: kotlinx.coroutines.flow.StateFlow<Boolean> =
        _hasInjectedShareContent.asStateFlow()

    fun markShareInjected() { _hasInjectedShareContent.value = true }
    fun clearShareInjectedFlag() { _hasInjectedShareContent.value = false }

    /**
     * Convert a staged share file (under filesDir/share_extension/) into
     * an [InputAttachment] and add it to the composer. Called by
     * ChatScreen when draining a [com.openminis.app.share.PendingShare].
     */
    fun addAttachmentFromStagedShare(file: java.io.File): InputAttachment? {
        if (!file.exists()) return null
        val ext = file.extension.lowercase()
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
        val kind = if (mime.startsWith("image/")) com.openminis.app.session.InputAttachment.Kind.IMAGE
                   else com.openminis.app.session.InputAttachment.Kind.DOCUMENT
        // T185 fix: ChatScreen wipes the share-extension directory right
        // after this call returns (`SharedShareStore.cleanSharedFiles`),
        // so a `Uri.fromFile(<staged file>)` would dangle by the time the
        // user actually sends — the byte-read in prepareUserAttachments
        // then fails to open the stream and the image never makes it into
        // the LLM payload, leaving the model staring at "what is this?" with no
        // picture. Copy the staged bytes into our own private dir so the
        // attachment outlives the share-extension cleanup.
        val durableDir = java.io.File(context.cacheDir, "share_inbound").apply { mkdirs() }
        val durable = java.io.File(durableDir, "${java.util.UUID.randomUUID()}-${file.name}")
        try {
            file.inputStream().use { input ->
                durable.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to copy staged share file ${file.name}: ${e.message}")
            return null
        }
        val attachment = InputAttachment(
            fileName = file.name,
            uri = android.net.Uri.fromFile(durable),
            mimeType = mime,
            kind = kind,
        )
        addAttachment(attachment)
        return attachment
    }

    // ─── Message Sending & Agent Loop ─────────────────────────────────────

    /**
     * [T-android-rerun-from-tool-block-position] Resolve the live UI assistant
     * bubble id that currently owns the tool block with [blockId] (== its
     * tool_use id). Returns null when no live bubble holds it. Used by the
     * debug RPC ([com.openminis.app.debug.HeadlessChatRunner.rerunFromToolBlock])
     * because the in-memory bubble id is a volatile `assistant_<ts>` runtime id
     * (not the DB row id a caller would read from `chat.messages.list`), so the
     * harness can't supply it directly.
     */
    override fun assistantMessageIdForToolBlock(blockId: String): String? =
        _messages.value.firstOrNull { m ->
            m.role == "assistant" && m.toolBlocks.any { it.id == blockId }
        }?.id

    /**
     * [T-android-rerun-from-tool-block-position] Re-run the conversation from
     * the exact point a specific tool_use block was about to be issued —
     * BLOCK-boundary, not turn-boundary. Keeps the blocks BEFORE the target
     * tool_use in the same assistant turn; drops the target block + every
     * later block in that turn + its tool_result + all later turns, then
     * re-runs so the model re-decides from that point.
     *
     * Ported from iOS `retryFromToolBlock` (commit 0149457e). Anchor is the
     * block's tool_use id ([blockId], which for a tool_use [AssistantBlock]
     * equals its `id`) — stable + unique, NOT a positional count, so streaming
     * / merged-turn alignment can't drift the cut point.
     *
     * Degenerate case: when the target is the FIRST real block of its turn
     * (nothing precedes it), this is equivalent to truncating at the preceding
     * user message — delegate to [retryFromMessage] (the existing whole-turn
     * path) and skip the sub-message DB rewrite.
     *
     * Android does the cut DB-first (delete rows after the trimmed assistant
     * row, then rewrite that row's parts in place via
     * [ChatRepository.updateMessageParts]) and rebuilds agentHistory from the
     * trimmed DB state. The UI is trimmed in-memory (same as
     * [retryFromMessage]'s `retainedHead`, so compact-marker graying isn't
     * disturbed). Because the agent loop persists each turn as its own row and
     * `toChatMessages` merges consecutive assistant rows into one bubble, the
     * surviving trimmed turn and the new generation coalesce on the next
     * reload — no duplicate header (iOS needed an explicit resume-into-turn
     * fix for the same; Android gets it from the merge). The thinking
     * indicator shows immediately via [runAgentLoop]'s awaiting placeholder.
     *
     * No-op (returns false) when streaming, when the message/block isn't
     * found, or when the block isn't a tool_use. The caller gates the menu
     * item with the same `!isStreaming` rule, but the guard here is the source
     * of truth.
     */
    override fun rerunFromToolBlock(assistantMessageId: String, blockId: String): Boolean {
        if (_isStreaming.value) return false
        val messages = _messages.value
        val asstIdx = messages.indexOfFirst { it.id == assistantMessageId }
        if (asstIdx < 0) return false
        val asstMsg = messages[asstIdx]
        val blockIdx = asstMsg.toolBlocks.indexOfFirst { it.id == blockId }
        if (blockIdx < 0) return false
        val targetBlock = asstMsg.toolBlocks[blockIdx]
        // Only a real tool_use block anchors a block cut — its id is the
        // tool_use id we match against in agentHistory / parts_json.
        if (targetBlock.kind != "tool_use" || targetBlock.id.isBlank()) return false
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        val targetToolUseId = targetBlock.id

        // Degenerate: nothing of substance precedes the target in this turn —
        // a block cut here is identical to truncating at the preceding user
        // message, so reuse the existing whole-turn path. "Substance" = any
        // earlier block that isn't an empty text block (mirrors iOS
        // hasPrecedingContent).
        val hasPrecedingContent = asstMsg.toolBlocks.take(blockIdx).any { blk ->
            if (blk.isText) blk.content.isNotEmpty() else true
        }
        // [T-android-rerun-from-tool-deletes-earlier-turns] The degenerate
        // shortcut is ONLY equivalent to truncating at the preceding user
        // message when there is NOTHING between that user message and this
        // assistant turn. If an EARLIER assistant turn/bubble sits right before
        // this one (asstIdx-1 is also assistant), retryFromMessage(precedingUser)
        // would delete that earlier turn's tools too — exactly the "rerun from
        // the last tool wiped the tools above it / re-ran from the very start"
        // bug (logged: historySize 29 → 3 on the 2nd consecutive rerun). In
        // that case fall through to the DB-precise cut below, which keeps every
        // row before the target row (its cutPartIdx==0 branch deletes only the
        // target row onward) and preserves the earlier turns.
        val precededByUserOnly = asstIdx == 0 || messages[asstIdx - 1].role != "assistant"
        if (!hasPrecedingContent && precededByUserOnly) {
            val userMsg = (asstIdx - 1 downTo 0).asSequence()
                .map { messages[it] }
                .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                ?: return false
            Log.i(TAG, "rerunFromToolBlock degenerate → retryFromMessage(precedingUser) tuId=${targetToolUseId.take(12)}")
            retryFromMessage(userMsg.id)
            return true
        }

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return false
        }
        _canResume.value = false
        _error.value = null

        // T149 parity: revoke memory_writes in the parts we're about to drop
        // so the on-disk daily log doesn't keep entries the user rewound past.
        // The dropped range is: the target turn's blocks FROM the target
        // onward (the target tool_use itself + any later same-turn blocks) +
        // every later message. The surviving earlier blocks of the target turn
        // are kept, so they're excluded.
        val droppedTargetTail = asstMsg.copy(
            toolBlocks = asstMsg.toolBlocks.drop(blockIdx),
        )
        val deletedMessages = listOf(droppedTargetTail) +
            messages.subList(asstIdx + 1, messages.size).toList()

        // Claim the streaming flag synchronously so a rapid second tap is
        // rejected by the entry guard (same rationale as retryFromMessage T145).
        AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            var streamLaunched = false
            try {
                val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

                // Locate the DB assistant row holding the target tool_use, and
                // the parts-array index of that tool_use within it.
                val dbMessages = chatRepository.loadMessagesTail(sid, MAX_AGENT_HISTORY_MESSAGES)
                var cutRow: MessageEntity? = null
                var cutPartIdx = -1
                outer@ for (entity in dbMessages) {
                    if (entity.role != "assistant") continue
                    val arr = try { org.json.JSONArray(entity.partsJson) } catch (_: Exception) { continue }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        if (o.optString("type") != "toolUse") continue
                        val tuId = o.optJSONObject("value")?.optString("toolUseId") ?: ""
                        if (tuId == targetToolUseId) {
                            cutRow = entity
                            cutPartIdx = i
                            break@outer
                        }
                    }
                }
                val row = cutRow
                if (row == null || cutPartIdx < 0) {
                    // Anchor not in DB (shouldn't happen for a rendered tool
                    // block). Abort cleanly without a half-applied truncation.
                    Log.w(TAG, "rerunFromToolBlock: toolUseId ${targetToolUseId.take(12)} not found in DB — aborting")
                    return@launch
                }

                // Trim the row's parts to those strictly before the target
                // tool_use, preserving array order (parts_json mirrors block
                // order). An assistant turn may hold text + several tool_use
                // parts; we keep everything ahead of the matched index.
                val srcArr = org.json.JSONArray(row.partsJson)
                val keptArr = org.json.JSONArray()
                for (i in 0 until cutPartIdx) keptArr.put(srcArr.get(i))

                if (cutPartIdx == 0) {
                    // Nothing precedes the target in its DB row — trimming would
                    // leave an empty assistant row. Drop the whole row instead
                    // (keepCount = its sort_order). The UI degenerate guard
                    // above normally catches this, but a merged-bubble layout
                    // could route a first-in-row tool_use here; handle it so we
                    // never persist a phantom empty assistant message.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder)
                    Log.i(TAG, "rerunFromToolBlock cut at row start (empty trim) tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder} row=${row.id.take(8)}")
                } else {
                    // Delete every row after the trimmed assistant row, then
                    // rewrite the trimmed row in place. deleteMessagesAfter
                    // keeps rows with sort_order < keepCount, so keepCount =
                    // thisRow.sortOrder + 1 drops the following tool_result row
                    // + all later turns while keeping (then overwriting) this one.
                    chatRepository.deleteMessagesAfter(sid, row.sortOrder + 1)
                    chatRepository.updateMessageParts(row.id, keptArr.toString())
                    Log.i(TAG, "rerunFromToolBlock sub-message cut tuId=${targetToolUseId.take(12)} keepCount=${row.sortOrder + 1} partIdx=$cutPartIdx trimmedRow=${row.id.take(8)}")
                }

                // T149 parity: revoke memory writes in the dropped range.
                revokeMemoryWritesInDeletedMessages(deletedMessages)

                // Trim the UI in-memory (same approach as retryFromMessage's
                // `_messages.value = retainedHead`, which doesn't reload from
                // DB and so doesn't disturb compact-marker graying): keep the
                // target assistant message with only its blocks BEFORE the
                // target, and drop every later message. Block trim mirrors the
                // parts trim above so UI ↔ history stay in lockstep.
                withContext(Dispatchers.Main) {
                    val cur = _messages.value
                    val ai = cur.indexOfFirst { it.id == assistantMessageId }
                    if (ai >= 0) {
                        val keptBlocks = cur[ai].toolBlocks.take(blockIdx)
                        if (keptBlocks.isEmpty()) {
                            // [T-android-rerun-from-tool-deletes-earlier-turns]
                            // Target was the first block of its bubble — the DB
                            // side dropped the whole row (cutPartIdx==0). Drop
                            // the bubble in the UI too instead of leaving an
                            // empty assistant message; earlier bubbles (the
                            // turns that precede this one) are preserved by
                            // subList(0, ai).
                            _messages.value = cur.subList(0, ai).toList()
                        } else {
                            // Recompute `content` from the surviving text blocks
                            // so it doesn't keep text the renderer just dropped.
                            // The chat list renders ordering from toolBlocks, but
                            // `content` feeds previews / copy, so keep it in sync.
                            val keptText = keptBlocks.filter { it.isText }
                                .joinToString("") { it.content }
                            val trimmed = cur[ai].copy(
                                content = keptText,
                                toolBlocks = keptBlocks,
                                isStreaming = false,
                            )
                            _messages.value = cur.subList(0, ai).toList() + trimmed
                        }
                    }
                }
                val keptIds = _messages.value.mapTo(mutableSetOf()) { it.id }
                retainStreamFlushStates(keptIds)
                if (_streamingById.value.isNotEmpty()) {
                    _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
                }

                // Rebuild only the bounded model context from the persisted tail.
                awaitBoundedHistoryRebuild(sid)

                streamLaunched = runRerunStreamTail(initialProvider, "rerunFromToolBlock")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "rerunFromToolBlock _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
        return true
    }

    /**
     * Retry from a specific user message: truncate all messages after it
     * (including the assistant response), rebuild agent history, and resend.
     * Mirrors iOS's edit/retry behavior — no duplicate user messages.
     */
    override fun retryFromMessage(messageId: String) {
        if (_isStreaming.value) return
        _canResume.value = false
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val message = messages[index]
        // [T-android-tool-autoscroll] Start-of-turn snap — see resume().
        _forceScrollToBottom.tryEmit(Unit)
        if (message.role != "user" || message.content.isBlank()) return

        val initialProvider = currentProvider
        if (initialProvider == null) {
            _error.value = "No provider configured"
            return
        }
        val provider: LLMProvider = initialProvider
        _error.value = null

        // T149: snapshot messages about to be truncated so we can revoke any
        // memory_write tool blocks they contain. Without this, a retry leaves
        // the on-disk daily log with entries the user has just rewound past.
        val deletedMessages = messages.subList(index + 1, messages.size).toList()

        // Truncate UI messages: keep up to and including this user message.
        // T189: if the retried bubble was still in the queued state (manual
        // retry of a queued message before resumeQueueAfterCancel's grace
        // window — or fallback when auto-resume is disabled), flip it out of
        // queued visuals and drop its queue entry so the upcoming send
        // doesn't double up against a later auto-drain.
        val retainedHead = messages.subList(0, index + 1).map { m ->
            if (m.id == messageId && m.isQueued) {
                m.queuedPromptId?.let { pid ->
                    _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
                }
                m.copy(isQueued = false, queuedPromptId = null)
            } else m
        }
        _messages.value = retainedHead
        // T-streaming-side-channel: scrub stream deltas pointing at
        // messages we just truncated so they can't resurface later.
        val keptIds = retainedHead.mapTo(mutableSetOf()) { it.id }
        retainStreamFlushStates(keptIds)
        if (_streamingById.value.isNotEmpty()) {
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }

        revokeMemoryWritesInDeletedMessages(deletedMessages)

        // T145: claim the streaming flag SYNCHRONOUSLY so a rapid second tap
        // (or any concurrent send/retry attempt) is rejected by the entry
        // guard. Previously this was set inside the suspended outer launch,
        // leaving a multi-second window during DB cleanup + OAuth refresh
        // where two retries could slip through and spawn duplicate streamJobs.
        // The orphaned first job's `_isStreaming = false` at completion would
        // then flip the UI to "stopped" while the second job was still running.
        AppLogger.info(TAG_STREAM, "retry _isStreaming=true (sync, sid=$activeSessionId)")
        _isStreaming.value = true

        viewModelScope.launch {
            // If setup throws before the inner streamJob is launched, the
            // streaming flag would be stuck true forever. Reset on the
            // unhappy paths; happy path resets in the streamJob's tail.
            var streamLaunched = false
            try {
            val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId

            // Find the DB sort_order cutoff for this user message.
            // UI visible user messages are the N-th user msg with actual text content.
            // Count which visible user message this is (0-based).
            val visibleUserIndex = messages.subList(0, index + 1).count { it.role == "user" } - 1
            val cutoffSortOrder = visibleUserCutoff(sid, visibleUserIndex)?.let { it.sortOrder + 1 } ?: -1
            if (cutoffSortOrder >= 0) {
                chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
            }

            awaitBoundedHistoryRebuild(sid)

            streamLaunched = runRerunStreamTail(provider, "retryFromMessage")
            } finally {
                if (!streamLaunched) {
                    AppLogger.info(TAG_STREAM, "retry _isStreaming=false (setup aborted)")
                    _isStreaming.value = false
                }
            }
        }
    }


    /**
     * [T-android-delete-from-here] Resolve the DB `sort_order` to cut at so
     * that [index] and everything after it is removed.
     *
     * Anchoring is by visible-user-message ordinal rather than by row id
     * because the UI list and the persisted rows are not 1:1 — see
     * [deleteFromMessage]'s note on synthetic `<system-reminder>` rows.
     *
     * When the target is an ASSISTANT message the cut is anchored to the user
     * turn it belongs to: we find the last visible user message at or before
     * [index], cut just after it, and thereby drop the assistant reply and
     * everything following. Returns -1 when no anchor can be resolved, which
     * the caller treats as "leave the DB alone".
     */
    internal suspend fun resolveDeleteCutoffSortOrder(
        sid: String,
        messages: List<ChatMessage>,
        index: Int,
        target: ChatMessage,
    ): Int {
        val visibleUserIndex = messages.subList(0, index + 1).count { it.role == "user" } - 1
        if (visibleUserIndex < 0) return 0
        val anchor = visibleUserCutoff(sid, visibleUserIndex) ?: return -1
        return if (target.role == "user") anchor.sortOrder else anchor.sortOrder + 1
    }



    /**
     * T187: leave edit mode without sending. Just clears the id flag —
     * caller (ChatScreen) is responsible for clearing inputText. iOS
     * parity: AIChatViewModel.cancelEdit (L2522).
     */
    fun cancelEdit() {
        if (_editingMessageId.value != null) {
            AppLogger.info(TAG_STREAM, "✏️ cancelEdit")
            // [T-android-edit-loses-attachments] Drop the attachments
            // editMessage restored, mirroring how the caller clears the text.
            //
            // Symmetry with editMessage is the whole point: it REPLACES the
            // composer's attachments with the edited message's, so leaving
            // them behind on cancel would strand files the user never picked
            // in a composer they thought they had backed out of — and the next
            // ordinary send would silently attach them.
            //
            // Guarded by the same non-null check as the log so a stray call
            // outside edit mode cannot wipe a draft's real attachments.
            _attachments.value = emptyList()
        }
        _editingMessageId.value = null
    }

    /**
     * T187: drop the message at [messageId] *and* every later message
     * (in UI, in agentHistory, and on disk) so the new sendMessage()
     * call below this can persist the edited text as a fresh user
     * turn at the same position. Reuses the cutoff-search machinery
     * from retryFromMessage but offsets by `entity.sortOrder` (not
     * +1) — retry preserves the original turn, edit replaces it.
     */
    internal suspend fun truncateBeforeEdit(messageId: String) {
        val messages = _messages.value
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return

        val deletedMessages = messages.subList(index, messages.size).toList()
        // [T-android-uimessages-sublist-cme] Defensive: without `.toList()` this
        // stores a live subList VIEW as `_messages.value`.
        //
        // Reproduced on device with this `.toList()` reverted (Pixel 4a): the
        // truncation ran (8 messages → 4) and a further message was sent, and it
        // did NOT crash — the next `+` copies the view into a plain ArrayList
        // before anything can invalidate it. So this line is hardening, not the
        // proven cause of the reported CME. See the long note on `uiMessages`.
        // (`deletedMessages` above already copies; this line did not.)
        val kept = messages.subList(0, index).toList()
        _messages.value = kept
        if (_streamingById.value.isNotEmpty()) {
            val keptIds = kept.mapTo(mutableSetOf()) { it.id }
            retainStreamFlushStates(keptIds)
            _streamingById.value = _streamingById.value.filterKeys { it in keptIds }
        }
        revokeMemoryWritesInDeletedMessages(deletedMessages)

        val sid = realSessionId.takeIf { it.isNotEmpty() } ?: sessionId
        // Visible-user index of the *edited* message — count user turns
        // strictly before `index`, which is the 0-based ordinal of the
        // edited turn itself.
        val visibleUserIndex = messages.subList(0, index).count { it.role == "user" }
        val cutoffSortOrder = visibleUserCutoff(sid, visibleUserIndex)?.sortOrder ?: -1
        if (cutoffSortOrder >= 0) {
            chatRepository.deleteMessagesAfter(sid, cutoffSortOrder)
        }
        val remaining = awaitBoundedHistoryRebuild(sid)
        AppLogger.info(
            TAG_STREAM,
            "✏️ truncateBeforeEdit cutoffSortOrder=$cutoffSortOrder remaining=${remaining.size}"
        )
    }

    /**
     * Enqueue a prompt to be injected into the currently running agent loop.
     * The message appears immediately in the chat with isQueued=true; when the
     * current agent loop finishes, drainQueuedPrompts() consumes the queue.
     * Mirrors iOS AIChatViewModel.enqueuePrompt().
     */
    fun enqueuePrompt(text: String) {
        val trimmed = text.trim()
        val pendingAttachments = _attachments.value
        if ((trimmed.isBlank() && pendingAttachments.isEmpty()) || !_isStreaming.value) return

        val prompt = QueuedPrompt(
            id = "queued_${System.currentTimeMillis()}_${(Math.random() * 1_000_000).toInt()}",
            text = trimmed,
            attachments = pendingAttachments,
        )
        _promptQueue.value = _promptQueue.value + prompt

        val attachmentNames = pendingAttachments.map { it.fileName }
        val imageUris = pendingAttachments.filter { it.isImage }.map { it.uri }
        val attachmentUris = pendingAttachments.filterNot { it.isImage }.map { it.uri }
        val chatMsg = ChatMessage(
            id = "queued_msg_${prompt.id}",
            role = "user",
            content = trimmed,
            imageUris = imageUris,
            attachmentNames = attachmentNames,
            attachmentUris = attachmentUris,
            isQueued = true,
            queuedPromptId = prompt.id,
        )
        _messages.value = trimLoadedWindow(_messages.value + chatMsg)
        clearAttachments()
        Log.i(TAG, "Enqueued prompt (${trimmed.length}ch, ${pendingAttachments.size} attachments), queue=${_promptQueue.value.size}")
    }

    /** Remove a queued prompt and its chat message by prompt id. */
    fun removeQueuedPrompt(promptId: String) {
        _promptQueue.value = _promptQueue.value.filterNot { it.id == promptId }
        _messages.value = _messages.value.filterNot { it.queuedPromptId == promptId }
    }

    /** Withdraw a queued message before it gets injected into the agent loop. */
    fun withdrawQueuedMessage(messageId: String) {
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        if (!msg.isQueued) return
        val pid = msg.queuedPromptId ?: return
        _promptQueue.value = _promptQueue.value.filterNot { it.id == pid }
        _messages.value = _messages.value.filterNot { it.id == messageId }
        Log.i(TAG, "Withdrew queued message, queue=${_promptQueue.value.size}")
    }

    /**
     * [T-android-queued-message-interrupt-on-toolclose] Mid-tool-loop
     * interrupt: take everything in [_promptQueue] right now, finalize the
     * just-finished assistant bubble in the UI, persist a fresh user
     * message carrying the queued text + attachments, append an assistant
     * "bridge" entry into [agentHistory] (so Anthropic's
     * mergeConsecutiveSameRole doesn't fold the queued user msg into the
     * preceding tool_result), and spawn a new assistant placeholder for
     * the next iteration's response.
     *
     * Returns an [InjectedTurn] carrying the new assistantId (which the
     * caller swaps into its loop-scope `assistantId` before `continue`-ing
     * the agent loop), or `null` if every queued prompt was empty after
     * attachment processing (caller falls through to a normal next-turn
     * dispatch in that case).
     *
     * Mirrors iOS `injectQueuedPromptsAsNewTurn`
     * (AIChatViewModel.swift:2794). Unlike iOS we don't persist the bridge
     * entry — its sole purpose is to break up the consecutive-user run for
     * the next API call; chat history reconstruction would just hide it.
     */
    internal data class InjectedTurn(val newAssistantId: String)



    override fun sendMessage(text: String) = sendMessage(text, skipContextCheck = false)


    /** Set error inline on the last assistant message (iOS: message.error).
     *
     *  Also clears [ChatMessage.isAwaitingModelResponse] — without this, an
     *  exception thrown after a tool turn (which sets isAwaitingModelResponse=
     *  true at runAgentLoop ~4015) leaves the "Minis is thinking" indicator
     *  on screen even though streaming is over. The flag is per-message and
     *  is not implicitly cleared by isStreaming=false. */
    internal fun setInlineError(errorText: String) {
        // [T-error-persist-android] Never let an empty/blank error string reach
        // the banner. The UI gate is `message.error?.let { … }` — a non-null ""
        // would render an EMPTY error banner, and (now that errors persist) it
        // would stick across reloads. An exception with a blank `message`
        // (`e.message ?: "Unknown error"` only guards null, not "") is the
        // realistic source. Coalesce to a generic non-empty message.
        val safeError = errorText.ifBlank { context.getString(R.string.error_empty_response_generic) }
        // T-streaming-side-channel: before mutating the canonical message,
        // drain any in-flight streaming delta so the error frame carries
        // the actual accumulated content (otherwise the user sees content
        // snap back to a pre-stream prefix when the error banner appears).
        flushAllStreamingDeltas()
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx >= 0) {
            val msg = msgs[lastAssistantIdx]
            msgs[lastAssistantIdx] = msg.copy(
                error = safeError,
                isStreaming = false,
                isAwaitingModelResponse = false,
            )
            _messages.value = msgs
            // [T-error-persist-android] Persist the terminal error onto the
            // session's last assistant DB row so the inline error + Retry button
            // survive a session reload. This is a targeted UPDATE (not a fresh
            // insert): the in-memory bubble id differs from the persisted row id,
            // so we address the row by "last assistant" — matching the load-side
            // merge that keeps the last assistant row's identity. No-op when the
            // failing turn never persisted a row (first-turn failure).
            val sid = realSessionId.ifEmpty { sessionId }
            if (sid.isNotEmpty()) {
                viewModelScope.launch(Dispatchers.IO) {
                    try { chatRepository.updateLastAssistantError(sid, safeError) }
                    catch (e: Exception) { Log.w(TAG, "persist error_info failed: ${e.message}") }
                }
            }
        } else {
            // No assistant message yet — fall back to top-level error
            _error.value = safeError
        }
    }

    /**
     * Show a transient error on the last assistant message while keeping isStreaming=true
     * so the "thinking" indicator and streaming UI stay intact during auto-retry countdowns.
     * Mirrors iOS streamWithAutoRetry: `chatMessage?.error = desc` without dropping the loop.
     */
    internal fun setTransientInlineError(errorText: String) {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        msgs[lastAssistantIdx] = msg.copy(error = errorText)
        _messages.value = msgs
    }

    /** Clear any inline error on the last assistant message (used after successful retry). */
    internal fun clearInlineError() {
        val msgs = _messages.value.toMutableList()
        val lastAssistantIdx = msgs.indexOfLast { it.role == "assistant" }
        if (lastAssistantIdx < 0) return
        val msg = msgs[lastAssistantIdx]
        if (msg.error == null) return
        msgs[lastAssistantIdx] = msg.copy(error = null)
        _messages.value = msgs
        // [T-error-persist-android] Clear the persisted sticker too, so a
        // recovered turn doesn't resurrect the error banner on the next reload.
        // Clear by the message's source DB rows when known (the in-memory bubble
        // maps to one or more persisted rows via sourceDbIds); fall back to the
        // last-assistant-row update otherwise.
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isNotEmpty()) {
            val dbIds = msg.sourceDbIds
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (dbIds.isNotEmpty()) {
                        dbIds.forEach { chatRepository.updateMessageErrorInfo(it, null) }
                    } else {
                        chatRepository.updateLastAssistantError(sid, null)
                    }
                } catch (e: Exception) { Log.w(TAG, "clear error_info failed: ${e.message}") }
            }
        }
    }

    /**
     * [T-error-persist-android] Fire-and-forget: clear the persisted error
     * sticker on the session's last assistant row. Called from the resume / retry
     * entrypoints that drop the in-memory error but don't go through
     * [clearInlineError], so a recovered turn can't merge-resurrect the old
     * banner on the next reload. No-op when there's no session/row yet.
     */
    internal fun clearPersistedLastAssistantError() {
        val sid = realSessionId.ifEmpty { sessionId }
        if (sid.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try { chatRepository.updateLastAssistantError(sid, null) }
            catch (e: Exception) { Log.w(TAG, "clear error_info (persisted) failed: ${e.message}") }
        }
    }


    /**
     * Unwrap exceptions thrown inside callbackFlow.
     * callbackFlow wraps internal throws into CancellationException(cause=original).
     * This extracts the original LLMError if present.
     */

    internal fun unwrapFlowException(e: Throwable): Throwable {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.openminis.app.data.model.LLMError) return cause
            cause = cause.cause
        }
        return e
    }

    /**
     * Compute max output tokens that fits within the remaining context window.
     * Logic mirrors iOS's dynamicMaxTokens():
     *   result = min(provider.defaultMaxTokens, max(contextWindow - inputTokens, MIN_MAX_TOKENS))
     *
     * @param provider The current LLM provider (carries defaultMaxTokens).
     * @param lastContextTokens API-reported input token count from the last call (0 = first call).
     */
    internal fun dynamicMaxTokens(provider: LLMProvider, lastContextTokens: Int = 0): Int {
        val model = currentModel ?: return minOf(GLOBAL_MAX_TOKENS_CEILING, provider.defaultMaxOutputTokens)
        // Ceiling: min(global cap, model.maxOutputTokens-or-provider-default).
        // The global cap means we never send more than 128K regardless of
        // what the model claims it can output.
        val maxOutputCeiling = minOf(GLOBAL_MAX_TOKENS_CEILING, provider.effectiveMaxOutputTokens(model))
        // Context window: model.contextWindow if known, else the shared
        // model-id heuristic. [T-anthropic-context-window] Route through
        // LLMModel.contextWindowTokens so the corrected Claude-1M / Gemini-1M
        // values apply here too, instead of the stale local "everything 200K"
        // copy that under-reported modern Claude/Gemini windows.
        val contextWindow = model.contextWindowTokens
        if (contextWindow <= 0) return maxOutputCeiling
        val inputTokens = if (lastContextTokens > 0) lastContextTokens else 0
        val remaining = contextWindow - inputTokens
        val clamped = maxOf(remaining, MIN_MAX_TOKENS)
        val result = minOf(maxOutputCeiling, clamped)
        if (result < maxOutputCeiling) {
            android.util.Log.i(TAG, "dynamicMaxTokens: $result (remaining=$remaining, ceiling=$maxOutputCeiling, window=$contextWindow, input=$inputTokens, model=${model.id})")
        }
        return result
    }

    // ─── Context Window Offload ──────────────────────────────────────────────
    //
    // Mirrors iOS `AIChatViewModel.swift`:
    //   - estimateContextTokens()        (line 7451)
    //   - offloadContextIfNeeded()       (line 7481)
    // Per-tool writers live in [com.openminis.app.data.ContextOffload].
    //
    // The agent loop calls [offloadContextIfNeeded] once per turn just before
    // the next API call. When token usage crosses the policy threshold, large
    // tool outputs in older messages are written to disk under
    // `filesDir/minis-sessions/<sid>/offloads/tools/` and replaced in
    // [agentHistory] by `[CONTEXT OFFLOADED] … <linux path>` stubs. The model
    // can later `file_read` the path to retrieve the original content.
    //
    // Why this matters: without offloading, a session that runs many large
    // shell tools fills the context window and either trips compact (lossy)
    // or hits the model's context-exhausted error. Offload is lossless —
    // the data still exists, just on disk instead of in-prompt.

    /**
     * Char-based fallback estimate when the API hasn't reported a token
     * baseline yet (first call in a turn). Mirrors iOS line 7451.
     *
     * Uses ~3.5 chars per token for mixed text + adds the tokenizer's
     * image-aware count for image bytes. Underestimates JSON-heavy tool
     * inputs slightly but is adequate as a "should we offload" gate —
     * offload itself uses precise [BPETokenizer.countTokens] per-part
     * for the candidate ranking.
     */
    internal fun estimateContextTokens(): Int {
        var totalChars = 0
        var imageTokens = 0
        for (msg in agentHistory) {
            for (part in msg.contentParts) {
                when (part) {
                    is AgentContentPart.Text -> totalChars += part.text.length
                    is AgentContentPart.ToolUse -> totalChars += part.input.toString().length
                    is AgentContentPart.ToolResult -> {
                        totalChars += part.content.length
                        part.imageData?.let { imageTokens += BPETokenizer.countImageTokens(it) }
                    }
                    is AgentContentPart.ImageData -> {
                        imageTokens += BPETokenizer.countImageTokens(part.data)
                    }
                }
            }
        }
        return (totalChars / 3.5).toInt() + imageTokens
    }

    /**
     * Approximate token count for a single agent content part. Used to rank
     * offload candidates by size. Matches iOS `BPETokenizer.countPartTokens`
     * — text uses BPE, images use the grid-cell heuristic.
     */
    internal fun countPartTokens(part: AgentContentPart): Int = when (part) {
        is AgentContentPart.Text -> BPETokenizer.countTokens(part.text)
        is AgentContentPart.ToolUse -> BPETokenizer.countTokens(part.input.toString())
        is AgentContentPart.ToolResult -> {
            BPETokenizer.countTokens(part.content) +
                (part.imageData?.let { BPETokenizer.countImageTokens(it) } ?: 0)
        }
        is AgentContentPart.ImageData -> BPETokenizer.countImageTokens(part.data)
    }

    /**
     * Offload candidate descriptor. `msgIdx` and `partIdx` index back into
     * [agentHistory] so we can mutate the part in place after writing the
     * stub to disk.
     */
    internal data class OffloadCandidate(
        val msgIdx: Int,
        val partIdx: Int,
        val tokens: Int,
        val bytes: Int,
        val toolId: String,
        val toolName: String,
    )



    /**
     * Finalize the current assistant message when [runAgentLoop] hits the
     * MAX_AGENT_TURNS ceiling. Drops the streaming/awaiting flags so the
     * "thinking" indicator clears, writes an inline error explaining *why*
     * we stopped, and arms canResume so the user can continue from here.
     * Mirrors iOS AIChatViewModel.swift:4922-4929 pattern (canResume + error).
     */
    internal fun finalizeAtTurnLimit(
        assistantId: String,
        text: String,
        blocks: List<AssistantBlock>,
    ) {
        updateAssistantMessage(
            assistantId, text, false, blocks,
            isAwaitingModelResponse = false,
        )
        // [T-android-thinking-indicator-linger] updateAssistantMessage drains
        // _streamingById[assistantId] above, but the agent loop ran on
        // Dispatchers.IO while this finalize hops to Main — a late streaming
        // delta can re-add the side-channel entry AFTER the drain, and since
        // the loop has now exited no further isStreaming=false write will ever
        // clear it. mergeStreamingOverlay (ChatScreen) forces isStreaming=true
        // on any message with a side-channel entry, so that orphan keeps the
        // "thinking" row alive forever. Defensively drop the entry here as the
        // last Main-thread write of this turn.
        // [T-android-stream-flush-review] Cancel the trailing flush too, so it
        // can't re-add this orphan entry after we drop it on the error path.
        clearStreamFlushState(assistantId)
        if (_streamingById.value.containsKey(assistantId)) {
            _streamingById.value = _streamingById.value - assistantId
        }
        setInlineError(
            "Stopped after $MAX_AGENT_TURNS agent turns to prevent runaway " +
            "tool use. The model kept calling tools without finishing — tap " +
            "Resume to continue from here, or send a new message to start over.",
        )
        // [T-android-group-pause-badge-restamp] A LIVE interruption just
        // happened: this is a real entry into the paused state, so the
        // badge's 24h freshness stamp must be refreshed. Cancel any
        // unconsumed re-detection mark left by a prior load so it cannot
        // suppress the re-stamp here.
        markLiveInterruption()
        _canResume.value = true
    }

    /**
     * Instance entry point used by the tool-dispatch path. The real logic lives
     * in the companion so tests can reach it without a ChatViewModel.
     */
    internal fun preflightValidateToolCall(
        name: String,
        args: JSONObject,
        tools: List<AgentToolDefinition>,
    ): String? = preflightValidateToolCallImpl(name, args, tools)


    internal suspend fun oneShotAsk(system: String, user: String): String {
        val provider = currentProvider ?: throw IllegalStateException("no provider")
        val buf = StringBuilder()
        provider.streamMessage(
            messages = listOf(LLMMessage(LLMMessage.Role.USER, user)),
            systemPrompt = system,
            maxTokens = 2048,
            temperature = samplingTemperature(_activeEntryId.value),
            tools = emptyList(),
            thinkingLevel = ThinkingLevel.OFF,
        ).collect { chunk ->
            if (chunk is LLMStreamChunk.Text) buf.append(chunk.text)
        }
        return buf.toString().ifBlank { "(empty reasoning response)" }
    }

    /**
     * [T-android-vision-group / GH#182] Placeholder text for an image the CURRENT
     * main model can't natively see, to be carried on the outgoing image part and
     * substituted by the provider's T264 branch. Returns null when the main model
     * has native vision (pixels are attached, no placeholder needed) OR no Vision
     * Group is configured (provider falls back to its historical literal). When a
     * Vision Group IS configured, returns a hint naming [path] and steering the
     * model to call read_image — closing the loop with executeReadImageTool.
     */
    internal fun visionPlaceholderFor(path: String?): String? {
        if (currentModelHasNativeVision) return null
        if (!com.openminis.app.tools.VisionGroupResolver.isConfigured(providerRepository, context)) return null
        return com.openminis.app.tools.VisionGroupResolver.noVisionImagePlaceholder(path)
    }

    /**
     * [T-android-vision-group / GH#182] read_image dispatch.
     *
     * Native-vision main models keep the original behaviour exactly: the tool
     * returns the pixels and the provider attaches them.
     *
     * A main model WITHOUT native image input only reaches here because a Vision
     * Group is configured (that's the tool-exposure gate in [agentTools]). For
     * that case we do NOT return pixels — a text-only model can't decode them and
     * the provider (OpenAIProvider T264) silently drops them to a placeholder.
     * Instead we hand the bytes to the Vision Group, get a text DESCRIPTION back,
     * and return that as the tool output. `imageData` is left null so no pixels
     * are attached, but `imageFilePath` is preserved so the on-screen tool block
     * still shows the image the user's model "read". Mirrors iOS
     * AIChatViewModel+ConcurrentTools read_image branch.
     */
    internal suspend fun executeReadImageTool(argsJson: String): ToolExecutionResult {
        val base = ReadImageTool.execute(argsJson, activeSessionId, context)
        // [T-android-vision-group / GH#182] Optional caller instruction focusing
        // what to learn from the image.
        val customPrompt = try {
            JSONObject(argsJson).optString("prompt", "").trim().ifEmpty { null }
        } catch (_: Exception) { null }
        // Failed decode / missing file → unchanged.
        if (!base.success || base.imageData == null) return base
        if (currentModelHasNativeVision) {
            // The model sees the pixels itself; a prompt adds no routing here, but
            // echo it as context so the tool block reflects the model's intent.
            return if (customPrompt != null) {
                base.copy(output = base.output + "\n\n[Requested focus: " + customPrompt + "]")
            } else base
        }

        val bytes = base.imageData
        val mime = base.imageMimeType ?: "image/jpeg"
        val result = com.openminis.app.tools.VisionGroupResolver.describe(
            repo = providerRepository,
            context = context,
            imageData = bytes,
            mimeType = mime,
            seed = kotlin.math.abs(argsJson.hashCode()),
            customPrompt = customPrompt,
            // [T-vision-group-attribution / GH#182] iOS rewrites the tool block's
            // live content here so the card names the model as it works. Android
            // has no equivalent channel — no tool streams partial output to its
            // card, and the progress label ("Minis is reading Image",
            // ChatToolFormatting.kt:102) is a static per-tool string. Building
            // that plumbing is a separate change, so for now the per-attempt
            // signal goes to the log, where a fallback is still traceable. The
            // RESULT-side attribution (which model answered, what was tried
            // first) is fully implemented and is what the user actually reads.
            onAttempt = { a ->
                android.util.Log.i(
                    "VisionGroup",
                    "[Vision] attempt ${a.index}/${a.total} via ${a.modelName}",
                )
            },
        )
        val framed = when (result) {
            is com.openminis.app.tools.VisionGroupResolver.VisionResult.Success ->
                com.openminis.app.tools.VisionGroupResolver.framedDescription(
                    result,
                    com.openminis.app.tools.VisionGroupResolver.groupName(providerRepository),
                    question = customPrompt,
                )
            is com.openminis.app.tools.VisionGroupResolver.VisionResult.Failure ->
                com.openminis.app.tools.VisionGroupResolver.failureText(result.reason)
        }
        // Deliberately still success=true even on describe failure: an errored
        // tool result tends to make models retry in a loop, whereas this lets the
        // model plainly tell the user the image couldn't be analyzed.
        return base.copy(
            output = base.output + "\n\n" + framed,
            imageData = null,
            imageMimeType = null,
        )
    }


    // ─── UI Helpers ──────────────────────────────────────────────────────

    internal fun updateAssistantMessage(
        id: String,
        content: String,
        isStreaming: Boolean,
        toolBlocks: List<AssistantBlock>,
        isAwaitingModelResponse: Boolean = false,
    ) {
        streamSession.updateAssistantMessage(id, content, isStreaming, toolBlocks, isAwaitingModelResponse)
    }

    internal fun effectiveContent(id: String): String? = streamSession.effectiveContent(id)

    private fun flushStreamingDelta(id: String) = streamSession.flushStreamingDelta(id)

    internal fun flushAllStreamingDeltas() = streamSession.flushAllStreamingDeltas()

    /**
     * Build the ordered AgentContentPart list for this turn by walking the slice of
     * `allToolBlocks` that belongs to the current turn (from `turnStartBlockIndex` to
     * the end). Text blocks become `Text`, tool_use blocks become `ToolUse` — the
     * original stream order is preserved by the list slice order. Thinking and info
     * blocks are skipped (they're persisted via `reasoningContent` or not at all).
     */
    internal fun buildTurnParts(
        allToolBlocks: List<AssistantBlock>,
        turnStartBlockIndex: Int,
        toolCallInputs: Map<String, String>,
    ): List<AgentContentPart> {
        if (turnStartBlockIndex >= allToolBlocks.size) return emptyList()
        val out = mutableListOf<AgentContentPart>()
        for (i in turnStartBlockIndex until allToolBlocks.size) {
            val block = allToolBlocks[i]
            when (block.kind) {
                "text" -> if (block.content.isNotEmpty()) {
                    out.add(AgentContentPart.Text(block.content))
                }
                "tool_use" -> {
                    val name = block.toolName
                    if (name.isBlank()) continue
                    val inputStr = toolCallInputs[block.id] ?: "{}"
                    val inputJson = try { JSONObject(inputStr) } catch (_: Exception) { JSONObject() }
                    // [T-android-gemini3-thoughtsig / #179] Carry the block's
                    // signature into the persisted/replayed ToolUse.
                    out.add(AgentContentPart.ToolUse(block.id, name, inputJson, thoughtSignature = block.thoughtSignature))
                }
                // "thinking" / "info" → not persisted in parts
                else -> { /* skip */ }
            }
        }
        return out
    }

    /**
     * Persist a single agent turn: the ordered list of AgentContentParts produced
     * in this turn (text segments and tool_use blocks interleaved in the order they
     * were emitted). Mirrors iOS's per-turn `persistAgentMessage` — one DB row per
     * turn, no cross-turn accumulation, preserving `parts` array order.
     *
     * This is the right entry point for the agent loop; the legacy
     * `persistAssistantMessage(text, usage, toolBlocks, ...)` accumulated all history
     * on every call, which caused:
     *   - Duplicate tool_use rows across turns (crashed LazyColumn key uniqueness)
     *   - Orphan tool_result detection thrashing (sanitize injecting placeholders)
     *   - Lost chronological text ↔ tool_use ordering within a single turn
     */
    /**
     * Serialize a turn's [AgentContentPart] list into the on-disk parts_json
     * shape (text + toolUse blocks). Shared by [persistAssistantTurn] (the
     * authoritative per-turn row write) and the live session-list preview
     * update ([T-android-session-last-message-live-tool-call]) so both produce
     * an identical payload that [ChatRepository.extractTextPreview] understands.
     */
    internal fun buildAssistantPartsJson(
        parts: List<AgentContentPart>,
        toolBlockMeta: Map<String, AssistantBlock>,
    ): String = buildString {
        append("[")
        parts.forEachIndexed { index, part ->
            if (index > 0) append(",")
            when (part) {
                is AgentContentPart.Text -> {
                    append("""{"type":"text","value":${escapeJson(part.text)}}""")
                }
                is AgentContentPart.ToolUse -> {
                    // Skip tool_use with blank name — upstream bug guard.
                    val name = part.name
                    if (name.isBlank()) return@forEachIndexed
                    val inputStr = part.input.toString()
                    val meta = toolBlockMeta[part.id]
                    val desc = meta?.toolTitle ?: ""
                    val pageURL = meta?.browserURL ?: ""
                    val imgPath = meta?.imageFilePath ?: ""
                    // [T-android-gemini3-thoughtsig / #179] Persist the captured
                    // signature (null-literal when absent) so it survives a session
                    // reload and can be replayed on the historical functionCall.
                    val sigJson = part.thoughtSignature?.let { escapeJson(it) } ?: "null"
                    append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(part.id)},"name":${escapeJson(name)},"input":${escapeJson(inputStr)},"description":${escapeJson(desc)},"pageURL":${escapeJson(pageURL)},"imageFilePath":${escapeJson(imgPath)},"thoughtSignature":$sigJson}}""")
                }
                else -> { /* tool_result is persisted via persistToolResultMessage */ }
            }
        }
        append("]")
    }

    internal suspend fun persistAssistantTurn(
        parts: List<AgentContentPart>,
        usage: LLMUsage?,
        reasoningContent: String? = null,
        toolBlockMeta: Map<String, AssistantBlock> = emptyMap(),
    ): String? {
        if (parts.isEmpty()) return null
        val partsJson = buildAssistantPartsJson(parts, toolBlockMeta)
        val tokenJson = usage?.let {
            """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}}"""
        }
        val entity = chatRepository.appendMessage(
            realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
            reasoningContent = reasoningContent,
            // [T-token-attribution-snapshot] From the live request context, not
            // the session row — see currentModelSnapshot().
            modelSnapshot = currentModelSnapshot(),
        )
        return entity.id
    }

    @Deprecated("Use persistAssistantTurn(parts, ...) for per-turn delta persistence")
    private suspend fun persistAssistantMessage(
        text: String,
        usage: LLMUsage?,
        toolBlocks: List<AssistantBlock>? = null,
        toolCallInputs: Map<String, String> = emptyMap()
        ,
        reasoningContent: String? = null,
    ) {
        if (text.isEmpty() && (toolBlocks == null || toolBlocks.isEmpty())) return

        val partsJson = buildString {
            append("[")
            var first = true
            if (text.isNotEmpty()) {
                append("""{"type":"text","value":${escapeJson(text)}}""")
                first = false
            }
            toolBlocks?.forEach { block ->
                // Only persist real tool-use blocks. text / thinking / info blocks are
                // either represented via the `text` parameter (accumulatedText) or
                // reconstructed from thinking metadata; persisting them as `toolUse`
                // produces empty-name records that Anthropic rejects with
                // "messages.N.content.M.tool_use.name: String should have at least 1 character".
                if (block.kind != "tool_use") return@forEach
                if (block.toolName.isBlank()) return@forEach  // extra safety
                if (!first) append(",")
                first = false
                val inputJson = toolCallInputs[block.id]?.let { escapeJson(it) } ?: "\"\""
                val pUrl = block.browserURL ?: ""
                val iPath = block.imageFilePath ?: ""
                append("""{"type":"toolUse","value":{"toolUseId":${escapeJson(block.id)},"name":${escapeJson(block.toolName)},"input":$inputJson,"description":${escapeJson(block.toolTitle)},"pageURL":${escapeJson(pUrl)},"imageFilePath":${escapeJson(iPath)},"thoughtSignature":null}}""")
            }
            append("]")
        }
        val tokenJson = usage?.let {
            """{"inputTokens":${it.inputTokens},"outputTokens":${it.outputTokens},"cacheCreationTokens":${it.cacheCreationInputTokens ?: 0},"cacheReadTokens":${it.cacheReadInputTokens ?: 0},"latestContextTokens":${it.latestContextTokens}}"""
        }
        chatRepository.appendMessage(
            realSessionId.ifEmpty { sessionId }, "assistant", partsJson, tokenJson,
            reasoningContent = reasoningContent,
            modelSnapshot = currentModelSnapshot(),
        )
    }

    /** Persist tool results as a user-role message (mirrors iOS behavior). */
    internal suspend fun persistToolResultMessage(parts: List<AgentContentPart>): String? {
        val results = parts.filterIsInstance<AgentContentPart.ToolResult>()
        if (results.isEmpty()) return null
        val partsJson = buildString {
            append("[")
            results.forEachIndexed { index, result ->
                if (index > 0) append(",")
                val snapshotText = escapeJson(result.content.lines().takeLast(30).joinToString("\n"))
                append("""{"type":"toolResult","value":{"toolUseId":${escapeJson(result.id)},"name":${escapeJson(result.name)},"output":${escapeJson(result.content)},"success":${!result.isError},"snapshot":{"type":"text","text":$snapshotText}}}""")
            }
            append("]")
        }
        val entity = chatRepository.appendMessage(realSessionId.ifEmpty { sessionId }, "user", partsJson)
        return entity.id
    }


    // ─── Legacy tool execution methods (kept for compatibility) ───────────

    fun executeMemoryWrite(argsJson: String): MemoryTools.ToolResult {
        val repo = sessionMemoryRepo()
        if (!_memoryEnabled.value) {
            return MemoryTools.ToolResult(
                "Memory writes are disabled for this session. Reads are still available. The user can re-enable writes via the /memory slash command.",
                false,
            )
        }
        val result = MemoryTools.executeMemoryWrite(argsJson, repo)
        val content = try {
            JSONObject(argsJson).optString("content", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = true,
            preview = content.lines().firstOrNull { it.isNotBlank() }?.take(100) ?: "",
            output = result.output,
            writtenContent = content,
        )
        return result
    }

    fun executeMemoryGet(argsJson: String): MemoryTools.ToolResult {
        val repo = sessionMemoryRepo()
        val result = MemoryTools.executeMemoryGet(argsJson, repo)
        val keywords = try {
            JSONObject(argsJson).optString("keywords", "")
        } catch (_: Exception) { "" }
        _memoryToolRecords.value = _memoryToolRecords.value + MemoryToolRecord(
            title = result.toolTitle,
            isWrite = false,
            preview = if (keywords.isNotBlank()) "Search: $keywords" else result.output.take(100),
            output = result.output,
            keywords = keywords,
        )
        return result
    }

    suspend fun executeBrowserUse(argsJson: String): BrowserToolResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return BrowserToolResult(text = "Error: Invalid browser_use input. Required: 'action' parameter.", success = false)

        return try {
            val result = browserTabPool.execute(input)
            BrowserToolResult(
                text = result.text,
                success = result.success,
                base64Image = result.base64Image,
                imageFilePath = result.imageFilePath,
                pageURL = result.pageURL,
            )
        } catch (e: Exception) {
            BrowserToolResult(text = "Error: ${e.message}", success = false)
        }
    }

    data class BrowserToolResult(
        val text: String,
        val success: Boolean,
        val base64Image: String? = null,
        val imageFilePath: String? = null,
        val pageURL: String? = null,
    )

    // ─── Misc Helpers ────────────────────────────────────────────────────


    /** LLM-based title + category generation, mirrors iOS generateSessionTitleIfNeeded(). */
    internal var titleGenerationAttempts = 0
    internal var titleGenerationInFlight = false



    /**
     * [T-android-overlay-reply-status-34599] Pull the most recent
     * assistant text out of `_messages` and hand it to
     * [SessionActivityTracker.publishLastReply]. The tracker truncates
     * to a fixed-width excerpt and pairs it with [sessionId] so the
     * floating overlay can render a "tap to open this chat" capsule
     * after the stream completes. No-op when no assistant message has
     * content yet (e.g. fail during the very first turn).
     */
    internal fun publishOverlayReplyExcerpt(sessionId: String) {
        val snapshot = _messages.value
        val text = snapshot.asReversed().firstOrNull { msg ->
            msg.role == "assistant" && msg.content.isNotBlank()
        }?.content
        SessionActivityTracker.publishLastReply(sessionId, text)
    }

    fun shareConversationCard(options: com.openminis.app.share.ConversationCardOptions = com.openminis.app.share.ConversationCardOptions()) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                com.openminis.app.share.ConversationCardShare.share(
                    context = context,
                    title = _sessionTitle.value,
                    messages = uiMessages.value,
                    options = options,
                )
            }.onFailure {
                AppLogger.warning(TAG, "shareConversationCard failed: ${it.message}")
            }
        }
    }

    override fun cancelStream() {
        AppLogger.info(TAG_STREAM, "cancelStream invoked _isStreaming=false (sid=$activeSessionId)")
        dismissPendingUserQuestions("cancelled")
        streamJob?.cancel()
        _isStreaming.value = false
        // T-streaming-side-channel: flush any in-flight delta back into the
        // canonical message so the rest of cancelStream's cleanup (publish
        // overlay excerpt, persist, retry-eligible state) sees the real
        // content rather than a stale pre-stream snapshot.
        flushAllStreamingDeltas()
        // T171: drop activity tracker immediately, don't wait for the
        // streamJob's finally block. When OkHttp is wedged in a blocking
        // execute() call.cancel() may unwind eventually but the finally
        // doesn't run until then — meanwhile RPC chat.session.status would
        // still report isRunning=true and the user thinks the stop button
        // did nothing.
        // [T-android-overlay-reply-status-34599] User-initiated cancel:
        // surface any reply we already streamed + tag outcome as
        // Cancelled so the overlay's glyph reflects the actual end
        // state (⊘) instead of carrying over the prior tool's outcome.
        publishOverlayReplyExcerpt(activeSessionId)
        SessionActivityTracker.clearToolRunning(com.openminis.app.service.ToolOutcome.Cancelled)
        SessionActivityTracker.setInactive(activeSessionId)
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            SessionActivityTracker.setInactive(sessionId)
        }
        // Stop whichever shell the agent loop is actually dispatching against.
        // Before `ensureSession()` that is the draft id; after, the real id.
        // Stopping the wrong one leaves a runaway yt-dlp/ffmpeg alive.
        ExecutionCoordinator.stopCurrentCommand(activeSessionId)
        com.openminis.app.service.SubAgentActivityTracker.clearSession(activeSessionId)
        if (sessionId != activeSessionId) {
            com.openminis.app.service.SubAgentActivityTracker.clearSession(sessionId)
        }
        if (isDraft && realSessionId.isNotEmpty() && activeSessionId != sessionId) {
            // Mid-turn rename: sweep any lingering draft shell too.
            ExecutionCoordinator.stopCurrentCommand(sessionId)
        }
        handleUserCancelledCleanup()

        // T189: iOS parity (AIChatViewModel.swift L2592-2610). If the user
        // enqueued prompts during the cancelled stream, auto-resume the drain
        // instead of leaving them stuck as dashed bubbles waiting for a manual
        // long-press retry.
        val pending = _promptQueue.value
        if (pending.isNotEmpty()) {
            AppLogger.info(TAG_STREAM, "cancel — ${pending.size} queued prompt(s) remain, restarting drain")
            resumeQueueAfterCancel()
        }
    }



    /**
     * Build a JSON parts array matching the ChatRepository schema so a
     * committed interrupted-assistant turn round-trips across app restarts.
     * Only emits text parts — tool_use / tool_result paths are handled by
     * the existing persistence code in the agent loop.
     */
    internal fun buildAssistantPartsJson(parts: List<AgentContentPart>): String {
        val sb = StringBuilder("[")
        var first = true
        for (p in parts) {
            if (p !is AgentContentPart.Text) continue
            if (!first) sb.append(',') else first = false
            sb.append("""{"type":"text","value":""")
            sb.append(escapeJson(p.text))
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }


    /** Conservative eligibility: unfinished work and in-memory drafts stay pinned. */
    internal fun canEvictFromMemory(): Boolean =
        sessionLoaded.value && realSessionId.isNotBlank() && !_isStreaming.value && !_isCompacting.value &&
            streamJob?.isActive != true && compactJob?.isActive != true &&
            _inputText.value.isEmpty() && _attachments.value.isEmpty() && _pastedTexts.value.isEmpty() &&
            _editingMessageId.value == null && _promptQueue.value.isEmpty() && pendingSendText == null &&
            _pendingUserQuestions.value == null && pendingApprovals.value.isEmpty() &&
            _browserTabPoolRef?.isAgentBusy != true && _browserTabPoolRef?.isVisible != true &&
            _browserTabPoolRef?.hasActiveDownloads != true &&
            viewModelScope.coroutineContext[Job]?.children?.none { it.isActive && it !in initialScopeJobs } != false

    internal fun preserveShellOnCacheEviction() { preserveShellOnClear = true }

    // A conservative text estimate, not a process PSS measurement. Account
    // for the separately retained LLM history as well as UI tool output.
    internal fun retainedTextBytes(): Long = _messages.value.sumOf { message ->
        message.content.length.toLong() * 4 + message.toolBlocks.sumOf { it.content.length.toLong() * 4 }
    }

    internal fun trimIdleBrowser() { _browserTabPoolRef?.trimIdleTabs() }

    override fun onCleared() {
        unsubscribeSafeMode?.invoke()
        unsubscribeSafeMode = null
        _browserTabPoolRef?.dispose()
        super.onCleared()
        // Tear down whichever shell was actually serving this VM. Terminate
        // both ids when the rename happened, since a draft shell may still
        // linger if the agent ran a tool before `ensureSession()`.
        if (!preserveShellOnClear) {
            ExecutionCoordinator.sessionDidTerminate(activeSessionId)
            if (activeSessionId != sessionId) ExecutionCoordinator.sessionDidTerminate(sessionId)
        }
    }

    /**
     * T-android-new-chat-empty-residue: when the user leaves the chat screen,
     * drop sessions that were materialised in the DB (e.g. via a thinking /
     * memory toggle in `ensureSession()`) but never received a real message.
     * Without this hook, tapping "New chat" → toggling a session-scoped
     * setting → exiting leaves an empty row at the top of the session list.
     *
     * Called from ChatScreen's onDispose. Gates:
     *   - realSessionId must be non-empty (a row was actually inserted)
     *   - not currently streaming (background agent work would be lost)
     *   - persisted message count == 0 (authoritative DB check — `_messages`
     *     also contains ephemeral system-info bubbles that aren't persisted,
     *     so a state-only check would over-count).
     *
     * Safe to call multiple times; the row-existence + count gates make it
     * idempotent. After deletion we release the cached VM so a stale entry
     * doesn't linger in `ChatViewModelStore`.
     */
    fun cleanupIfEmptyOnExit() {
        val sid = realSessionId
        if (sid.isEmpty()) return
        if (_isStreaming.value) return
        if (_attachments.value.isNotEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val count = chatRepository.messageCount(sid)
                if (count > 0) return@launch
                AppLogger.info(
                    TAG,
                    "cleanupIfEmptyOnExit: deleting empty session $sid (no persisted messages)",
                )
                chatRepository.deleteSession(sid)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    ChatViewModelStore.release(sid)
                }
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "cleanupIfEmptyOnExit failed for $sid: ${t.message}")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    internal fun escapeJson(text: String): String {
        val sb = StringBuilder("\"")
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /**
     * Convert a flat list of MessageEntity into ChatMessages, merging toolResult
     * data from user-role messages back into their corresponding AssistantBlocks.
     * This mirrors iOS's toChatMessage() which reads both toolUse and toolResult parts.
     */
    /**
     * Matches a `<system-reminder>...</system-reminder>` block, including any
     * surrounding whitespace / newlines, so a part that is *only* a reminder
     * collapses to empty text instead of leaving a blank gap. DOTALL so `.`
     * spans newlines (reminders run multi-line in the cancel/resume paths).
     *
     * Only applied at the UI-render transform — agentHistory + DB rows keep
     * the raw text so the LLM continues to see the reminder on subsequent
     * turns (matches iOS, where system-reminder text is appended to
     * agentHistory/AgentMessage parts but never to the chat-list ChatMessage).
     */
    private val systemReminderRegex =
        Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

    private fun stripSystemReminders(text: String): String =
        if (!text.contains("<system-reminder>")) text
        else systemReminderRegex.replace(text, "")

    /**
     * [T-android-retry-attachment-loss] Remove the `<user-attached-files>` XML
     * inventory from a persisted text part for DISPLAY only. The XML is now
     * persisted (iOS parity) so the model keeps the file paths across retry /
     * reload, but it must never render in the user bubble — the file chips are
     * rebuilt from the mediaRef parts instead. Mirrors the index-based strip
     * already used by editMessage / the title-fallback path.
     */
    private fun stripAttachedFilesXml(text: String): String {
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx < 0) return text
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        return if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }

    internal fun List<MessageEntity>.toChatMessages(): List<ChatMessage> {
        // First pass: extract all toolResult data keyed by toolUseId
        val toolResultMap = mutableMapOf<String, ToolResultData>()
        for (entity in this) {
            if (entity.role != "user") continue
            try {
                val array = org.json.JSONArray(entity.partsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("type") == "toolResult") {
                        val value = obj.getJSONObject("value")
                        val toolUseId = value.optString("toolUseId", "")
                        if (toolUseId.isNotEmpty()) {
                            toolResultMap[toolUseId] = ToolResultData(
                                output = value.optString("output", ""),
                                success = value.optBoolean("success", true),
                            )
                        }
                    }
                }
            } catch (_: Exception) { /* skip malformed */ }
        }

        // Second pass: convert messages, merging tool results into blocks
        // Filter out user messages that only contain toolResult parts (no visible text)
        return mapNotNull { entity ->
            var text = ""
            val blocks = mutableListOf<AssistantBlock>()
            // T128: media attachments persisted under user messages as `mediaRef`
            // parts. Restored to file:// URIs (stable across app restarts) and
            // their original filenames so UserAttachmentList renders the same
            // tiles after a session reload.
            val restoredImageUris = mutableListOf<Uri>()
            // [T-android-paste-mediaref] Names are collected PER COLUMN and
            // concatenated image-first at the end, instead of appended to one
            // list in part order.
            //
            // UserAttachmentList splits with `allFileNames.drop(imageUris.size)`,
            // so the names list must be images-then-files regardless of the
            // order the parts appear in. That used to be automatic: attachment
            // mediaRefs were always written images-first. A pasted block breaks
            // it — it lives in the BODY, so it can precede an image part, and a
            // single in-order list would then start with a file name and shift
            // every image caption onto the wrong tile.
            val restoredImageNames = mutableListOf<String>()
            val restoredFileNames = mutableListOf<String>()
            // T150: file:// URIs of restored non-image attachments, in the
            // same order as the non-image suffix of the joined name list.
            // Powers the user-bubble file chip → FilePreviewScreen tap after
            // a session reload.
            val restoredAttachmentUris = mutableListOf<Uri>()

            if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
                blocks.add(AssistantBlock(
                    id = "thinking_restored_${entity.id}",
                    kind = "thinking",
                    content = entity.reasoningContent,
                    toolTitle = "Thinking",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ))
            }

            try {
                val array = org.json.JSONArray(entity.partsJson)
                var textBlockCounter = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    when (obj.optString("type")) {
                        "text" -> {
                            val raw = obj.optString("value", "")
                            // Strip <system-reminder>...</system-reminder> blocks
                            // here only — agentHistory in memory and the DB row
                            // both keep the raw text, so the LLM still sees the
                            // reminder on subsequent turns. UI just hides it.
                            // If a part was *only* a reminder, the cleaned
                            // string is empty and we skip it so we don't render
                            // a phantom blank text block.
                            // [T-android-retry-attachment-loss] Also strip the
                            // now-persisted <user-attached-files> XML so it
                            // doesn't render in the user bubble (file chips come
                            // from mediaRef parts). The DB row + agentHistory
                            // keep the raw XML so the model still sees paths.
                            val t = stripAttachedFilesXml(stripSystemReminders(raw)).let {
                                if (it != raw) it.trim() else it
                            }
                            if (t.isEmpty()) continue
                            text += t
                            // For assistant messages, also push the text as a block so
                            // the renderer can preserve the original text↔tool ordering.
                            // For user messages we keep using the `text` field only.
                            if (entity.role == "assistant") {
                                blocks.add(AssistantBlock(
                                    id = "text_restored_${entity.id}_${textBlockCounter++}",
                                    kind = "text",
                                    content = t,
                                ))
                            }
                        }
                        "toolUse" -> {
                            val value = obj.getJSONObject("value")
                            val toolId = value.optString("toolUseId", "")
                            if (toolId.startsWith("thinking_")) continue
                            val toolInput = value.optString("input", "")
                            // Merge tool result output (iOS: block.content = tr.output)
                            val result = toolResultMap[toolId]
                            val pageURL = value.optString("pageURL", "").ifEmpty { null }
                            val imgPath = value.optString("imageFilePath", "").ifEmpty { null }
                            blocks.add(AssistantBlock(
                                id = toolId,
                                kind = "tool_use",
                                toolName = value.optString("name", ""),
                                toolTitle = value.optString("description", ""),
                                toolArgs = toolInput,
                                content = result?.output?.lines()?.takeLast(80)?.joinToString("\n") ?: "",
                                toolStatus = when {
                                    result == null -> ToolBlockStatus.SUCCESS
                                    !result.success && (
                                        result.output.startsWith(CANCELLED_MARKER) ||
                                            result.output.startsWith(LEGACY_CANCELLED_MARKER)
                                    ) -> ToolBlockStatus.CANCELLED
                                    result.success -> ToolBlockStatus.SUCCESS
                                    else -> ToolBlockStatus.FAILED
                                },
                                browserURL = pageURL,
                                imageFilePath = imgPath,
                                // [T-android-gemini3-thoughtsig / #179] Restore the
                                // persisted signature onto the rebuilt block.
                                thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                            ))
                        }
                        "mediaRef" -> {
                            if (entity.role != "user") continue
                            val value = obj.optJSONObject("value") ?: continue
                            val rel = value.optString("relativePath", "")
                            if (rel.isEmpty()) continue
                            val file = java.io.File(mediaStore.mediaBaseDir, rel)
                            if (!file.exists()) continue
                            val mime = value.optString("mimeType", "")
                            val name = value.optString("originalFileName", "").ifEmpty { file.name }
                            // T150: branch on mime so non-image mediaRefs land
                            // in the file-chip column instead of polluting
                            // imageUris (which feeds the image gallery).
                            //
                            // [T-android-paste-mediaref] A pasted block needs no
                            // case of its own: it is text/plain, so it takes the
                            // non-image branch and renders as the same file card
                            // as an attached document — which is exactly the
                            // requested behaviour. Note this ALSO relies on
                            // parts being written images-first; a pasted ref
                            // sits in the body (possibly before an image part),
                            // so the names/uris pairing here is positional per
                            // COLUMN, not per part index, and stays consistent
                            // because each column is appended in part order.
                            if (mime.startsWith("image/")) {
                                restoredImageUris.add(Uri.fromFile(file))
                                restoredImageNames.add(name)
                            } else {
                                restoredAttachmentUris.add(Uri.fromFile(file))
                                restoredFileNames.add(name)
                            }
                        }
                        // toolResult in user messages handled in first pass above
                    }
                }
            } catch (e: Exception) {
                // T-PARTS-FALLBACK: previously this catch dumped the entire
                // partsJson into `text` as a degraded fallback. That meant
                // any malformed (or unexpectedly large) row rendered its
                // raw JSON — including any inlined base64 — as a plain
                // user/assistant bubble, which then locked up Compose's
                // StaticLayout for tens of seconds (see HangDetector report
                // for session e84882d7 / 820 KB partsJson). Replace with a
                // short, fixed-size placeholder so the row still appears
                // (so the user can delete or scroll past it) but no longer
                // pulls megabytes through the layout pass.
                Log.w(
                    TAG,
                    "toChatMessages: failed to parse partsJson for id=${entity.id} " +
                        "len=${entity.partsJson.length} role=${entity.role}: ${e.javaClass.simpleName}: ${e.message}",
                )
                text = "(message could not be parsed: ${e.javaClass.simpleName}, " +
                    "${entity.partsJson.length} bytes)"
            }

            // Skip user messages with no visible content (toolResult-only internal messages,
            // or messages that were entirely a system-reminder). A user message that is
            // *only* an image attachment (no caption) still has visible content and must
            // not be skipped — restoredImageUris carries it.
            if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
            // Skip assistant messages that became empty after stripping system-reminders
            // and have no tool / thinking blocks to fall back on — would otherwise
            // render as a phantom blank assistant bubble.
            if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty()) return@mapNotNull null
            ChatMessage(
                id = entity.id,
                role = entity.role,
                content = text,
                imageUris = restoredImageUris,
                // Image names first, then file names — the invariant
                // UserAttachmentList's `drop(imageUris.size)` relies on.
                attachmentNames = restoredImageNames + restoredFileNames,
                attachmentUris = restoredAttachmentUris,
                toolBlocks = blocks,
                sourceDbIds = listOf(entity.id),
                // [T-error-persist-android] Restore the persisted terminal error
                // so the inline error banner + Retry button survive a reload.
                // Coalesce a blank value to null: the UI gate is `error?.let`, so
                // a non-null "" would render an empty banner. Defends against any
                // legacy/other-writer "" row.
                error = entity.errorInfo?.takeIf { it.isNotBlank() },
            )
        }.let { messages ->
            // Merge consecutive assistant messages into one:
            // agent loop persists each turn separately, but UI should show them as a single message.
            val merged = mutableListOf<ChatMessage>()
            for (msg in messages) {
                val prev = merged.lastOrNull()
                if (msg.role == "assistant" && prev?.role == "assistant") {
                    // Merge: combine tool blocks, append text, keep the last id.
                    // Deduplicate by block.id — the agent loop may persist the same tool
                    // use in multiple consecutive turns (as it carries tool state across),
                    // and duplicated ids would crash LazyColumn's key uniqueness check.
                    // Keep the LAST occurrence so the most recent status (e.g. SUCCESS with
                    // output) wins over an earlier STREAMING placeholder.
                    val seen = mutableSetOf<String>()
                    val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                        .asReversed()
                        .filter { seen.add(it.id) }
                        .asReversed()
                    val combinedText = when {
                        prev.content.isBlank() -> msg.content
                        msg.content.isBlank() -> prev.content
                        else -> prev.content + "\n\n" + msg.content
                    }
                    merged[merged.lastIndex] = prev.copy(
                        id = msg.id,
                        content = combinedText,
                        toolBlocks = combinedBlocks,
                        // T126-marker: keep every source dbId so Phase 2.5
                        // can resolve markers that point at any of the
                        // pre-merge rows (lastCompactedMessageId is often
                        // an assistant row that gets folded into a later
                        // assistant turn).
                        sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                        // [T-error-persist-android] The error sticker is written
                        // to the LAST assistant row of the turn, so the later row
                        // (`msg`) wins; fall back to `prev` if only it carried one.
                        error = msg.error ?: prev.error,
                    )
                } else {
                    merged.add(msg)
                }
            }
            merged
        }
    }


    /**
     * Rebuild model context from the newest persisted rows only. The database
     * remains the complete transcript; no operation may parse the whole session
     * merely to continue, retry, edit, or delete it.
     */

    internal fun appendBoundedHistory(message: LLMMessage) {
        agentHistory.add(message)
        trimAgentHistory()
    }

    internal fun appendBoundedHistoryAll(messages: Collection<LLMMessage>) {
        agentHistory.addAll(messages)
        trimAgentHistory()
    }

    internal fun trimAgentHistory() {
        val overflow = agentHistory.size - MAX_AGENT_HISTORY_MESSAGES
        if (overflow <= 0) return
        // A cut boundary must never orphan a ToolResult: providers reject a
        // tool_result whose tool_use was trimmed away. Walk the cut forward
        // until the boundary message contains no ToolResult parts, then drop
        // everything before it in one contiguous slice.
        var keep = overflow
        while (keep < agentHistory.size &&
            agentHistory[keep].contentParts.any { it is AgentContentPart.ToolResult }
        ) {
            keep++
        }
        if (keep >= agentHistory.size) return
        agentHistory.subList(0, keep).clear()
        llmHistoryStartOffset += keep
    }

    internal suspend fun awaitBoundedHistoryRebuild(
        sid: String,
    ): List<com.openminis.app.data.db.MessageEntity> {
        val tail = chatRepository.loadMessagesTail(sid, MAX_AGENT_HISTORY_MESSAGES)
        val parsed = tail.map { it.toLLMMessage() }
        agentHistory.clear()
        toolLoopDetector.reset()
        appendBoundedHistoryAll(parsed)
        llmHistoryStartOffset = (
            chatRepository.dao.messageCountForSession(sid) - tail.size
        ).coerceAtLeast(0)
        return tail
    }


    private suspend fun visibleUserCutoff(
        sid: String,
        visibleUserIndex: Int,
    ): com.openminis.app.data.db.MessageAnchorRow? {
        if (visibleUserIndex < 0) return null
        val total = chatRepository.dao.messageCountForSession(sid)
        var offset = 0
        var seen = 0
        while (offset < total) {
            val page = chatRepository.dao.loadMessageAnchorsPage(sid, offset, 100, 4_096)
            if (page.isEmpty()) break
            for (row in page) {
                if (row.role != "user" || !anchorHeadHasVisibleUserText(row.headText.orEmpty())) continue
                if (seen == visibleUserIndex) return row
                seen++
            }
            offset += page.size
        }
        return null
    }

    private fun anchorHeadHasVisibleUserText(head: String): Boolean = try {
        val arr = org.json.JSONArray(head)
        (0 until arr.length()).any { i ->
            val o = arr.optJSONObject(i) ?: return@any false
            val value = o.optString("value", "")
            o.optString("type") == "text" &&
                stripAttachedFilesXml(value).isNotBlank() &&
                !value.trimStart().startsWith("<system-reminder>")
        }
    } catch (_: Exception) {
        true
    }


    internal fun trimLoadedWindow(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.size <= MAX_LOADED_MESSAGE_WINDOW) return messages
        val droppedCount = messages.size - MAX_LOADED_MESSAGE_WINDOW
        // Only persisted rows advance the database offset. System info bubbles
        // are UI-only, so counting them would skip real rows on the next page.
        val droppedRows = messages.take(droppedCount)
            .sumOf { msg -> msg.sourceDbIds?.size?.coerceAtLeast(1) ?: 1 }
        loadedMessageOffset = (loadedMessageOffset - droppedRows).coerceAtLeast(0)
        return messages.takeLast(MAX_LOADED_MESSAGE_WINDOW)
    }

    private data class ToolResultData(val output: String, val success: Boolean)

    internal fun MessageEntity.toLLMMessage(): LLMMessage {
        val r = if (role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
        val contentParts = mutableListOf<AgentContentPart>()
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        var textContent = ""

        try {
            val array = org.json.JSONArray(partsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                when (obj.optString("type")) {
                    "text" -> {
                        val value = obj.optString("value", "")
                        // [T-android-retry-attachment-loss] The persisted
                        // <user-attached-files> XML must reach the model via a
                        // contentPart (provider prefers contentParts), but it
                        // must NOT fold into `content`. On a FRESH send the
                        // `content` field is the clean caption (`trimmed`) and
                        // the XML lives only in contentParts; keep restored
                        // messages byte-identical so `.content` consumers
                        // (summary, title fallback, edit) see the same string
                        // as a fresh turn and don't get the XML twice.
                        if (value.contains("<user-attached-files>")) {
                            contentParts.add(AgentContentPart.Text(value))
                        } else {
                            textContent += value
                            contentParts.add(AgentContentPart.Text(value))
                        }
                    }
                    "toolUse" -> {
                        val v = obj.getJSONObject("value")
                        val inputStr = v.optString("input", "{}")
                        val inputJson = try {
                            JSONObject(inputStr)
                        } catch (_: Exception) {
                            JSONObject()
                        }
                        contentParts.add(AgentContentPart.ToolUse(
                            id = v.optString("toolUseId", ""),
                            name = v.optString("name", ""),
                            input = inputJson,
                            // [T-android-gemini3-thoughtsig / #179] Restore the
                            // persisted Gemini 3.x signature so a reloaded session
                            // replays it (else the next gemini-3 turn 400s).
                            thoughtSignature = v.optString("thoughtSignature", "").ifEmpty { null },
                        ))
                    }
                    "toolResult" -> {
                        val v = obj.getJSONObject("value")
                        contentParts.add(AgentContentPart.ToolResult(
                            id = v.optString("toolUseId", ""),
                            name = v.optString("name", ""),
                            content = v.optString("output", ""),
                            isError = !v.optBoolean("success", true),
                        ))
                    }
                    "mediaRef" -> {
                        // T128: load persisted user-message images so the model
                        // sees them on subsequent turns after a session reload.
                        // T150: skip non-image mediaRefs here — their bytes
                        // shouldn't be re-inlined into the LLM payload (parity
                        // with the on-send path, which only inlines images).
                        // The original turn's <user-attached-files> XML stayed
                        // in the persisted text part, and the file is still
                        // on disk under attachments/uploads, so the agent can
                        // re-fetch via shell tools.
                        val v = obj.optJSONObject("value") ?: continue
                        val rel = v.optString("relativePath", "")
                        if (rel.isEmpty()) continue
                        val mime = v.optString("mimeType", "image/jpeg")
                        // [T-android-paste-mediaref] A pasted block is stored as
                        // a mediaRef but is CONTENT, not an attachment: read it
                        // back off disk and inline it here, restoring exactly
                        // the text the original send put in the prompt.
                        //
                        // This branch is what makes every history-replay path
                        // correct at once — session reload, retry, rerun,
                        // edit-resend and compaction all rebuild through this
                        // one converter, so none of them needs its own handling.
                        //
                        // An unreadable file degrades to skipping the part
                        // rather than aborting the message: losing one pasted
                        // block is recoverable, failing to build the request is
                        // not.
                        if (PastedMedia.isPastedRef(mime, v.optString("originalFileName", null))) {
                            val pf = java.io.File(mediaStore.mediaBaseDir, rel)
                            val body = try {
                                if (pf.exists()) pf.readText(Charsets.UTF_8) else null
                            } catch (e: Exception) {
                                AppLogger.warning(TAG, "[Paste] restore failed for $rel: ${e.message}")
                                null
                            }
                            // [T-android-paste-missing-file] An unreadable
                            // pasted file degrades to an explicit marker, never
                            // to silence.
                            //
                            // The file can genuinely disappear — the user clears
                            // app storage, a sync pass prunes it as an orphan,
                            // the disk fills mid-write. Dropping the part on the
                            // floor would leave the model reading a sentence
                            // with a hole in the middle and no way to know
                            // content was ever there, so it would answer
                            // confidently about text it never saw. Saying so
                            // lets it ask, and leaves a searchable trace when a
                            // user reports a strange reply.
                            val resolved = body ?: PastedMedia.MISSING_PLACEHOLDER
                            if (body == null) {
                                AppLogger.warning(
                                    TAG,
                                    "[Paste] missing pasted file for $rel — substituting placeholder",
                                )
                            }
                            textContent += resolved
                            contentParts.add(AgentContentPart.Text(resolved))
                            continue
                        }
                        if (!mime.startsWith("image/")) continue
                        val file = java.io.File(mediaStore.mediaBaseDir, rel)
                        if (!file.exists()) continue
                        val bytes = try { file.readBytes() } catch (_: Exception) { continue }
                        val restoredPath = v.optString("linuxPath", "").ifEmpty { null }
                        // [T-android-vision-group / GH#182] Seed the read_image
                        // hint on restored images too, so a non-vision main model
                        // with a Vision Group configured gets steered to read_image
                        // on subsequent turns after a session reload (not the bare
                        // "can't see it" literal).
                        val restoredPlaceholder = visionPlaceholderFor(restoredPath)
                        imageParts.add(LLMMessage.ImagePart(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                        contentParts.add(AgentContentPart.ImageData(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                    }
                }
            }
        } catch (_: Exception) {
            textContent = partsJson
            contentParts.add(AgentContentPart.Text(partsJson))
        }

        return LLMMessage(
            role = r,
            content = textContent,
            imageParts = imageParts,
            contentParts = contentParts,
            dbMessageId = id,
            reasoningContent = reasoningContent,
        )
    }

    /**
     * Extract a string value for `key` from *partial* (possibly truncated) JSON
     * without needing a complete, parseable object. Mirrors iOS
     * `extractPartialStringValue(_:from:)` in AIChatViewModel.swift.
     *
     * Returns content up to the first unescaped `"`, or the remaining buffer
     * if the closing quote has not streamed yet.
     */
    internal fun extractPartialStringValue(key: String, json: String): String? {
        val patterns = listOf("\"$key\": \"", "\"$key\":\"")
        for (p in patterns) {
            val at = json.indexOf(p)
            if (at < 0) continue
            val after = json.substring(at + p.length)
            return unescapePartialJsonString(findUnescapedEnd(after))
        }
        return null
    }

    /** Return substring up to the first unescaped `"`, or the whole string if none. */
    private fun findUnescapedEnd(s: String): String {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\') {
                // Skip escaped character (could be `\"`, `\\`, `\n`, etc.)
                i += 2
                continue
            }
            if (c == '"') return s.substring(0, i)
            i++
        }
        return s
    }

    /** Unescape common JSON string escapes. */
    private fun unescapePartialJsonString(s: String): String =
        s.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")


}
