package com.openminis.app.provider.openai

import android.util.Base64
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.HttpRetryAfter
import com.openminis.app.provider.ProviderKeyGate
import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.provider.thinking.ThinkingResolveContext
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.SamplingIdentity
import com.openminis.app.provider.SamplingPolicy
import com.openminis.app.provider.VendorMedia
import com.openminis.app.provider.VendorMediaKind
import com.openminis.app.provider.applyUserAgentOverride
import com.openminis.app.provider.safeOptString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.openminis.app.provider.failOnSilentEmptyCompletion

class OpenAIProvider private constructor(
    private val apiKey: String?,
    private val oauthTokenProvider: (suspend () -> String)?,
    override var model: LLMModel = LLMModel.gpt4oMini,
    private val basePath: String = "https://api.openai.com/v1",
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** Codex account ID for OAuth mode (extracted from JWT). */
    var codexAccountId: String? = null,
    /** When true, route through /v1/responses even on API-key providers. */
    private val useResponsesAPI: Boolean = false,
    /**
     * When true, force Chat Completions even when the bearer is provided
     * via OAuth. Set by OAuth-but-not-Codex callers (xAI Grok) — without
     * it the default `usesChatCompletionsAPI = !isOAuth && !useResponsesAPI`
     * heuristic incorrectly drags an OAuth bearer onto the Codex
     * Responses backend at chatgpt.com, where it 404s.
     */
    private val forceChatCompletions: Boolean = false,
    /**
     * [T-provider-custom-user-agent] Per-provider User-Agent override.
     * null/blank → default UA; non-blank → replaces User-Agent on every
     * outbound request (chat + responses). Only set for custom-base
     * OpenAI-compat instances.
     */
    private val customUserAgent: String? = null,
    /**
     * [T-android-azure-openai] Azure OpenAI mode. When true, requests auth with
     * the `api-key:` header (not `Authorization: Bearer`) and the URL is built
     * as {azureBase}/openai/deployments/{model.id}/{path}?api-version=… from
     * [azureBase] (the raw user endpoint, which carries the ?api-version query).
     * Defaults false so every non-Azure path is byte-for-byte unchanged.
     */
    private val isAzure: Boolean = false,
    /**
     * Raw Azure endpoint the user pasted (with any ?api-version query). Only
     * used when [isAzure]; the factory passes instance.customBaseURL verbatim
     * here because [basePath] has been normalized (/v1 appended, query dropped)
     * which is wrong for Azure's deployments-path routing.
     */
    private val azureBase: String? = null,
) : LLMProvider {
    override val name = "OpenAI"
    override val callGateKey: String
        get() = ProviderKeyGate.key(
            basePath,
            apiKey ?: "oauth:${codexAccountId ?: "codex"}",
            model.id,
        )

    /**
     * [T-android-thinking-rules-phase2] Owning provider-instance id, set by
     * ProviderFactory after construction (mirrors how [codexAccountId] is a
     * post-construction var). Lets the thinking resolver look up this instance's
     * user-authored custom rules from [com.openminis.app.provider.thinking.ThinkingRuleResolver]'s
     * cache. Null → no custom rules (identical to Phase-1 built-in-only behaviour).
     */
    var thinkingRuleInstanceId: String? = null

    /**
     * [T-android-xai-priority] Whether this provider speaks xAI's Priority
     * Processing extension, i.e. whether it is eligible to carry
     * `service_tier: "priority"` when the user's global Fast Mode is on.
     *
     * This is a CAPABILITY flag, not the user's choice. The choice lives in
     * the app-level [com.openminis.app.data.FastModePrefs] toggle that Codex
     * Fast Mode already uses, and is read at request-BUILD time (see
     * [buildRequestBody]) so flipping it applies to the very next request of
     * an ongoing session — including offload / title-gen calls that never pass
     * through ChatViewModel. Storing the user's answer here instead would
     * freeze it at provider-construction time and miss those.
     *
     * False by default so every other provider's body is byte-for-byte
     * unchanged. That matters beyond tidiness: `service_tier` is an xAI
     * extension, and OpenAI-compatible relays that reject unknown body keys
     * would 400 on it, so ProviderFactory sets this for ProviderType.xAI alone.
     * A post-construction var rather than a constructor parameter for the same
     * reason as [thinkingRuleInstanceId] — xAI resolves through two different
     * constructors (API key and OAuth), and threading a flag through both
     * duplicates it.
     */
    var supportsPriorityProcessing: Boolean = false

    /**
     * [T-android-xai-priority] The effective `service_tier` for this request,
     * or null to omit the field. Consulted by both body builders.
     *
     * Omits rather than sending `"default"` when Fast Mode is off: "default" is
     * xAI's own behaviour, so leaving the key out keeps the body byte-identical
     * to before this feature existed.
     */
    internal fun resolvedServiceTier(): String? =
        if (supportsPriorityProcessing && com.openminis.app.data.FastModePrefs.isEnabled()) {
            "priority"
        } else {
            null
        }

    /** API Key constructor (Chat Completions API by default; set useResponsesAPI=true for /v1/responses). */
    constructor(
        apiKey: String,
        model: LLMModel = LLMModel.gpt4oMini,
        basePath: String = "https://api.openai.com/v1",
        extraHeaders: Map<String, String> = emptyMap(),
        useResponsesAPI: Boolean = false,
        customUserAgent: String? = null,
        isAzure: Boolean = false,
        azureBase: String? = null,
    ) : this(
        apiKey = apiKey,
        oauthTokenProvider = null,
        model = model,
        basePath = basePath,
        extraHeaders = extraHeaders,
        useResponsesAPI = useResponsesAPI,
        customUserAgent = customUserAgent,
        isAzure = isAzure,
        azureBase = azureBase,
    )

    /** OAuth constructor (Codex Responses API). */
    constructor(
        oauthTokenProvider: suspend () -> String,
        model: LLMModel = LLMModel.codexMini,
        codexAccountId: String? = null,
    ) : this(apiKey = null, oauthTokenProvider = oauthTokenProvider, model = model, codexAccountId = codexAccountId)

    companion object {
        /**
         * [T-android-thinking-level-arch] Codex OAuth client version advertised
         * in the Version / User-Agent headers. Bumped 0.142.3 → 0.144.1 to
         * match the CLIProxyAPI/sub2api upstream (fixes a gpt-5.6-luna 404 seen
         * on the older client). Shared constant so future bumps touch one place.
         */
        private const val CODEX_CLIENT_VERSION = "0.144.1"

        /**
         * [T-android-stale-conn-retry-hang] Streaming time-to-first-byte
         * budget: response HEADERS must arrive within this window. Does NOT
         * bound the SSE body — a flowing stream stays unlimited.
         *
         * [T-android-ttfb-upload-split / #188] This window now starts at
         * `requestBodyEnd` (upload complete), NOT at call start — a large
         * multimodal body over a slow proxy could burn the whole budget just
         * uploading, so a healthy-but-slow server looked like a dead
         * connection. See [STREAM_UPLOAD_CAP_MS] for the upload-phase bound.
         *
         * Raised 30s -> 120s (user report, TG soyo): complex agent turns,
         * locally-hosted large models, and slow relay endpoints can legitimately
         * take well over 30s to emit the first response header, and the old
         * budget cancelled those healthy requests as false timeouts. readTimeout
         * is 600s, so 120s stays comfortably inside it while still catching a
         * genuinely dead connection.
         */
        private const val STREAM_TTFB_TIMEOUT_MS = 120_000L

        /**
         * [T-android-ttfb-upload-split / #188] Overall ceiling for the UPLOAD
         * phase (call start → requestBodyEnd). Keeps the watchdog effective if
         * the body upload itself wedges (writeTimeout is 30s per write op, but a
         * trickling proxy can dribble bytes forever without tripping it). Chosen
         * generous so a legitimately large body over a slow link isn't cut off:
         * the writeTimeout(30s) already bounds a fully-stalled socket; this only
         * catches the slow-but-never-idle case. Once upload completes the tighter
         * [STREAM_TTFB_TIMEOUT_MS] takes over.
         */
        private const val STREAM_UPLOAD_CAP_MS = 120_000L

        /**
         * Factory for OAuth-bearer OpenAI-compatible providers that aren't
         * Codex (e.g. xAI Grok). Same dynamic bearer plumbing, but the
         * wire format stays Chat Completions and the endpoint is the
         * caller-supplied base URL — not chatgpt.com's Responses API.
         *
         * Implemented as a factory (not a secondary ctor) because the
         * JVM erases the signature down to
         * `(Function1, LLMModel, String)` which collides with the Codex
         * ctor's `(oauthTokenProvider, model, codexAccountId)` overload.
         */
        fun oauthOpenAICompat(
            oauthTokenProvider: suspend () -> String,
            model: LLMModel,
            basePath: String,
        ): OpenAIProvider = OpenAIProvider(
            apiKey = null,
            oauthTokenProvider = oauthTokenProvider,
            model = model,
            basePath = basePath,
            forceChatCompletions = true,
        )
    }

    private val isOAuth: Boolean get() = oauthTokenProvider != null

    // MARK: - Image passthrough [T-android-model-use-image-passthrough GH#62]

    /**
     * Arbitrary extra fields merged into the /images/generations JSON body, so
     * `minis-model-use` can pass provider-specific params our fixed schema never
     * modeled (e.g. Volcengine Seedream's `image` for image-to-image,
     * `watermark`, `tools`). User keys WIN over our defaults (response_format)
     * but never replace the resolved `model`. Empty = no passthrough. Set
     * per-call by ModelUseOffloadHandler on a freshly-built provider; never
     * persisted. Values are raw JSON (String/Number/Boolean/JSONObject/JSONArray).
     */
    var imageExtraBody: Map<String, Any?> = emptyMap()

    /** Test hook: Videos API poll cadence (create → first GET → later GETs). */
    internal var videoFirstPollMillis: Long = 1_500L
    internal var videoPollMillis: Long = 5_000L

    /**
     * Test hook: force a vendor media protocol on MockWebServer (localhost
     * hosts never match volces/bigmodel/dashscope). Null → [VendorMedia.detect].
     */
    internal var vendorMediaOverride: VendorMediaKind? = null

    internal fun resolvedVendorMedia(): VendorMediaKind =
        vendorMediaOverride ?: VendorMedia.detect(basePath, model.id)

    /**
     * Extra HTTP headers merged into the /images/generations request (added, not
     * replacing the ctor extraHeaders). Per-call, never persisted.
     */
    var imageExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Optional endpoint-path override for the image request (e.g. a non-standard
     * `/api/v3/images/generations`). When set, replaces the hardcoded
     * `/images/generations` path (base URL + this verbatim). null = default path.
     */
    var imagePathOverride: String? = null

    /** Optional video `mode` (std/pro/…). Null means omit until the provider says it is required. */
    var videoMode: String? = null

    /**
     * Mode actually placed on the last successful create body. Survives the
     * per-call clear of [videoMode] so the caller can report it, and is cleared
     * at the start of the next [generateVideo] so it is never sent again.
     */
    var videoModeSent: String? = null

    // MARK: - Chat passthrough [T-android-model-use-passthrough-mode / GH#72]

    /**
     * Arbitrary extra fields merged into the chat/completions AND responses
     * request bodies, mirroring [imageExtraBody] on the image path. Populated
     * per-call by ModelUseOffloadHandler from the input JSON's explicit
     * `extra_body` / `passthrough.body` envelope (never from implicit top-level
     * keys — the chat schema owns its top level). User keys WIN over our
     * defaults (e.g. `plugins`, `web_search_options`, provider-specific knobs)
     * but `model` is force-restored after the merge. Empty = no passthrough.
     * Mirrors iOS OpenAIProvider.chatExtraBody.
     */
    var chatExtraBody: Map<String, Any?> = emptyMap()

    /**
     * Extra HTTP headers merged into chat/completions and /responses requests,
     * applied AFTER the default set → same-name REPLACE semantics over every
     * default (including Authorization/Content-Type). Per-call, never persisted.
     * Mirrors iOS OpenAIProvider.extraHeaders (promoted to all endpoints).
     */
    var chatExtraHeaders: Map<String, String> = emptyMap()

    /**
     * Absolute-path endpoint override. When set (must start with "/"), it
     * replaces the ENTIRE URL path after scheme+host — unlike [imagePathOverride],
     * which is joined after `basePath` and therefore can never escape a base-URL
     * prefix like `/compatible-mode/v1` (proven by iOS device baseline p03).
     * Applies to chat/completions, responses, and images/generations builders.
     * Never applies to Codex OAuth (hardcoded backend). Per-call, never
     * persisted. Mirrors iOS OpenAIProvider.absoluteEndpointOverride.
     */
    var absoluteEndpointOverride: String? = null

    /**
     * [T-android-model-use-passthrough-mode] Build a URL from the provider's
     * scheme+host(+port) ONLY, with [path] replacing the entire URL path.
     * [path] must start with "/" and may carry a query string. Credentials stay
     * bound to the instance's host — callers can never point this at a different
     * host. Returns null if the base URL can't be parsed. Mirrors iOS
     * OpenAIProvider.hostRootURL.
     */
    fun hostRootURL(path: String): String? {
        val base = basePath.toHttpUrlOrNull() ?: return null
        val qIdx = path.indexOf('?')
        val pathPart = if (qIdx >= 0) path.substring(0, qIdx) else path
        val queryPart = if (qIdx >= 0) path.substring(qIdx + 1) else null
        val builder = base.newBuilder()
            .encodedPath(pathPart)
            .fragment(null)
        builder.encodedQuery(queryPart)
        return builder.build().toString()
    }

    /**
     * Resolve the effective URL for a modeled endpoint, honoring the
     * absolute-path override when present. [defaultPath] is joined after
     * [basePath] (which is already normalized to base + /v1). Mirrors iOS
     * OpenAIProvider.endpointURL.
     */
    private fun endpointURL(defaultPath: String): String {
        val abs = absoluteEndpointOverride
        if (abs != null && abs.startsWith("/")) {
            hostRootURL(abs)?.let { return it }
        }
        return "$basePath$defaultPath"
    }

    // MARK: - Azure helpers [T-android-azure-openai]

    /**
     * Set the API-key auth header on a request builder. Azure uses the `api-key`
     * header; every other OpenAI-compatible endpoint uses `Authorization:
     * Bearer`. Centralized so the Azure branch can't accidentally set the wrong
     * one. Mirrors iOS OpenAIProvider.applyKeyAuth.
     */
    private fun Request.Builder.applyKeyAuth(token: String): Request.Builder =
        // [T-empty-key-compat-endpoints] A keyless third-party endpoint
        // (ollama, LM Studio, LiteLLM, private relays) is a supported
        // configuration. Send NO auth header rather than a malformed
        // `Authorization: Bearer ` / empty `api-key:` — strict gateways
        // reject the empty form, an absent header is universally fine.
        if (token.isEmpty()) this
        else if (isAzure) header("api-key", token)
        else header("Authorization", "Bearer $token")

    /**
     * Build the request URL for Azure OpenAI, mirroring the official AzureOpenAI
     * SDK shape (and iOS azureURL, T-ios-azure-openai-deployments):
     *
     *   {azure_endpoint}/openai/deployments/{model.id}/{path}?api-version=…
     *
     * The user pastes the resource endpoint as the custom base — typically the
     * bare `https://x.openai.azure.com`, optionally already including `/openai`,
     * with the `?api-version=…` query on it. We (1) split off the query, (2)
     * strip a trailing `/`, a stray `/v1` (Azure has no /v1), and a trailing
     * `/openai` (re-added), then (3) assemble the deployments path. [path] is
     * e.g. "/chat/completions". Returns null when no Azure base is configured.
     */
    private fun azureUrl(path: String): String? {
        val raw = azureBase?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val qIdx = raw.indexOf('?')
        val query = if (qIdx >= 0) raw.substring(qIdx) else ""
        var p = (if (qIdx >= 0) raw.substring(0, qIdx) else raw).trimEnd('/')
        if (p.endsWith("/v1")) p = p.dropLast(3).trimEnd('/')
        if (p.endsWith("/openai")) p = p.dropLast("/openai".length).trimEnd('/')
        val endpointPath = path.removePrefix("/")
        return "$p/openai/deployments/${model.id}/$endpointPath$query"
    }

    /**
     * Whether this provider uses Chat Completions API (vs Responses API).
     * Responses API is used when OAuth (Codex) OR when the user explicitly
     * flipped the per-instance `useResponsesAPI` switch.
     */
    private val usesChatCompletionsAPI: Boolean get() = forceChatCompletions || (!isOAuth && !useResponsesAPI)

    /**
     * [T-android-tool-splits-reply-fix] Chat Completions streams ONE
     * monolithic `content` string per assistant response — qwen endpoints
     * flush trailing content chunks AFTER tool_calls deltas (chunking
     * artifact), and those must merge back into the single pre-tool text
     * block instead of becoming a post-tool block (which split sentences
     * mid-word in the chat UI). The Responses API streams genuinely ordered
     * output items, so it keeps chronological reconstruction.
     */
    override val streamTextIsMonolithic: Boolean get() = usesChatCompletionsAPI

    /**
     * [T-codex-gpt-image2-oauth-android] gpt-image-2 is a special image-
     * generation model driven through the Codex OAuth backend's built-in
     * image_generation tool (wire model gpt-5.5, tools=[{type:image_generation}]).
     * Only meaningful on the Codex OAuth path; everything else (the GPT-5.x
     * Codex models and their existing OAuth flow) is untouched by this gate.
     */
    private val isCodexImageModel: Boolean get() = isOAuth && model.id == "gpt-image-2"

    private suspend fun getToken(): String {
        oauthTokenProvider?.let { return it() }
        return apiKey ?: throw LLMError.InvalidApiKey()
    }

    // T-android-openai-codex-timeout: bump readTimeout 180s → 600s to
    // match iOS. T171 had cut it to 180s on the theory that GPT-5.x
    // thinking warm-up tops out around 60-90s, but the Codex Responses
    // OAuth path on gpt-5.5 with a real-world agent body (440KB, 20
    // messages, 8 tools) routinely sits silent on the SSE stream for
    // 2:50-3:10 between the reasoning `response.output_item.added`
    // event and the burst of text deltas after the reasoning step
    // completes — server-side it's still working, no keep-alive bytes
    // arrive in between, and OkHttp's idle-data-read counter trips.
    // The 180s cap turned that normal reasoning silence into a hard
    // SocketTimeoutException (observed in 0.10-preview, log file
    // minis-2026-05-27.log around 13:28 — 3:00 of silence then trip).
    // Going back to 600s leaves room for the longest realistic
    // reasoning bursts; the cancel-race concern T171 hedged against
    // (OkHttp call.cancel() racing a thread inside execute()) is
    // covered by the outer coroutine cancellation chain — Job.cancel
    // propagates down through the agent loop and the socket gets
    // closed via Call.cancel() from the coroutine's invokeOnCancellation,
    // so a stuck OAuth read never lingers past the agent turn.
    //
    // T-android-openai-codex-timeout: also attach an OkHttp EventListener
    // so future timeout reports show WHICH leg of the network path
    // stalled — DNS, proxy connect, TLS handshake, idle-after-headers,
    // or mid-stream silence. Previous OAuth-streaming logs only printed
    // request/response envelopes; when a SocketTimeoutException fired
    // we had no way to tell whether the upstream proxy went away
    // (idle-close after 3min, common with clash/v2ray), TLS renegotiated,
    // or the server itself stopped emitting bytes. Each milestone goes
    // through AppLogger.info at the OkHttpEvents tag with the call's
    // identity hash so concurrent streams can be disambiguated.
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // [T-android-stale-conn-retry-hang] Shared pool so NetworkMonitor's
        // network-transition eviction reaches THIS client's connections —
        // a per-client pool was never evicted, and a dead h2 tunnel through
        // a local proxy got reused on every retry (silent infinite hang).
        .connectionPool(com.openminis.app.network.NetworkMonitor.sharedLLMConnectionPool)
        .eventListenerFactory { OkHttpNetTraceListener() }
        .build()

    /** Detect OpenRouter base URL. */
    private val isOpenRouter: Boolean = basePath.contains("openrouter.ai")

    /**
     * [OpenMinis#191] OpenRouter does NOT enable Anthropic prompt caching
     * automatically — unlike the OpenAI / Grok / Moonshot / Groq models it
     * hosts, which cache with no opt-in. Claude requests must carry an explicit
     * `cache_control` breakpoint or nothing is cached at all, which is why the
     * reporter measured `cache_read_input_tokens` / `cache_write_tokens` pinned
     * at 0 across every turn and a 3-6x cost overrun.
     *
     * Matched on the `anthropic/` model-id prefix, OpenRouter's namespace for
     * the Claude family (`anthropic/claude-sonnet-4.5`, `anthropic/claude-opus-4.1`,
     * …). Scoped to OpenRouter AND that prefix so every other model on the
     * gateway keeps a byte-identical request body.
     *
     * Note the gate is the HOST-matched [isOpenRouter], never a compat flag:
     * on iOS the equivalent `useOpenRouterCompat` only selects the legacy
     * `max_tokens` / no-`stream_options` body shape and Mistral sets it too, so
     * keying on it would have leaked the field into Mistral requests. Android's
     * [isOpenRouter] is already host-matched, the same way [isDashScope] is.
     *
     * Carries the same caveat as [isMistral]: a relay or vanity domain without
     * `openrouter.ai` in its URL is not recognised, which fails safe — the
     * request simply goes out unchanged, i.e. today's behaviour.
     */
    private val needsOpenRouterAnthropicCacheControl: Boolean
        get() = isOpenRouter && model.id.lowercase().startsWith("anthropic/")

    /** Detect DashScope (Alibaba Qwen) base URL. */
    private val isDashScope: Boolean = basePath.contains("dashscope")

    /**
     * [T-android-mistral-reasoning-422] (GH OpenMinis#87, iOS 29065ca0)
     * Detect Mistral's OpenAI-compatible endpoint.
     *
     * Mistral's AssistantMessage is a CLOSED schema
     * (`additionalProperties: false`; only role/content/tool_calls/prefix), so
     * `reasoning_content` on a prior assistant turn is rejected outright with
     * HTTP 422 `extra_forbidden`. Their native reasoning representation is a
     * different, Mistral-signed mechanism (content ThinkChunks), not this
     * field. Note the REQUEST schema has no additionalProperties:false, which
     * is why only multi-turn history carrying reasoning_content ever 422'd
     * while spec-external top-level params went through fine.
     *
     * Case-insensitive to match iOS (LLMProviderFactory lowercases before the
     * same `contains("mistral.ai")` test) — hosts are case-insensitive, so a
     * user typing `API.Mistral.AI` must still be recognised.
     *
     * Known limits, both inherited from iOS's identical predicate: a relay that
     * proxies Mistral models under its own hostname is not detected (still
     * 422s), and a URL that merely mentions mistral.ai in a query string would
     * over-suppress (harmless — the field is optional for everyone else).
     */
    private val isMistral: Boolean = basePath.lowercase().contains("mistral.ai")

    /**
     * [OpenMinis#163] Talking to xAI's own API (api.x.ai), as opposed to a relay
     * that merely serves grok-named models. Mirrors iOS OpenAIProvider.isXAI.
     *
     * Scopes the "catalog declares no effort tiers → omit reasoning_effort" skip
     * to first-party xAI. The bundled catalog marks 2090 entries across many
     * vendors with the same empty-tier shape (relay-hosted Claude, GPT-5, Qwen,
     * and grok itself behind poe / fastrouter / anyapi); while omitting the
     * field is arguably more correct for some of those too, none of those routes
     * has been verified, so the skip stays where the 400 was actually observed.
     *
     * URL matching alone is sufficient: ProviderFactory always populates a base
     * for xAI, defaulting to https://api.x.ai/v1 when the user set no override.
     */
    private val isXAI: Boolean = basePath.lowercase().let {
        it.contains("api.x.ai") || it.contains("//x.ai")
    }

    /**
     * [T-unified-reasoning-effort] Whether this endpoint applies OpenAI's
     * `reasoning_effort` (Chat) / `reasoning.effort` (Responses) uniformly to
     * EVERY model it hosts — including third-party families (GLM / Kimi /
     * DeepSeek / MiniMax) that, at their vendor-native endpoint, would instead
     * use a `thinking:{}` object or self-reason with no toggle.
     *
     * Three known such gateways (mirrors iOS OpenAIProvider.usesUnifiedReasoningEffort):
     *   • Volcengine Ark (`ark.` / `volces` in the base URL) — re-exposes
     *     doubao/deepseek/glm/kimi through a single OpenAI-compatible surface
     *     where thinking is controlled ONLY by `reasoning_effort` (min tier
     *     `minimal`); the vendor-native `thinking:{}` shape is not honored.
     *   • Azure OpenAI ([isAzure]) — reasoning is `reasoning_effort` for every
     *     model surfaced through the deployment.
     *   • Venice.ai (`api.venice.ai`) — [OpenMinis#86] resells deepseek / claude /
     *     aion behind one OpenAI-compatible surface. Its ChatCompletionRequest
     *     schema is `additionalProperties: false`, so an unknown root key is
     *     rejected at validation time — BEFORE model dispatch — with
     *     `400 Unrecognized key(s) in object: 'thinking'`. That is why every
     *     model failed and why turning thinking OFF did not help: the
     *     `{"type":"disabled"}` branch still sends the key. Venice natively
     *     accepts root `reasoning_effort`, a superset of the tiers the generic
     *     path emits, so no value mapping is needed.
     *
     * Gated tightly so official direct endpoints (DeepSeek/GLM/Kimi native,
     * which DO want their own thinking shape) are never mis-routed. Caveat (same
     * class as [isMistral]): a relay or vanity domain that does not carry these
     * hosts in its URL is still exposed.
     */
    private val usesUnifiedReasoningEffort: Boolean =
        isAzure || basePath.lowercase().let {
            it.contains("volces") || it.contains("ark.") || it.contains("api.venice.ai")
        }

    /**
     * [T-thinking-off-explicit] The wire value for "thinking OFF", or null to
     * keep the historical omit-the-field behavior. ALLOWLIST, not blanket
     * (mirrors iOS OpenAIAgentProvider.explicitOffEffort): only vendors whose
     * off tier is DOCUMENTED get an explicit value —
     *   • official OpenAI base (non-Azure) → "none" (documented off tier);
     *   • Volcano Ark (volces/ark bases, seed/doubao families) → "minimal"
     *     (their smallest tier — Ark's non-off default is what motivated this).
     * Everyone else (relays, NIM, xAI, MiMo, …) keeps field omission = the
     * vendor's own default. Azure stays omission too: its off tier is
     * model-dependent ('none' on gpt-5.1+, 'minimal' on original gpt-5,
     * unsupported on o1/o3), so an explicit value risks a 400.
     */
    private fun explicitOffEffort(): String? {
        if (isAzure) return null
        val base = basePath.lowercase()
        if (base.startsWith("https://api.openai.com")) return "none"
        val lid = model.id.lowercase()
        if (base.contains("volces") || base.contains("ark.") ||
            lid.contains("seed-") || lid.contains("doubao")
        ) {
            return "minimal"
        }
        return null
    }

    /**
     * Non-streaming entry point. Some providers (e.g. GPT-5.x via certain
     * gateways, Codex Responses backend) reject `stream=false` outright with
     * `[400] Stream must be set to true`. To keep this method usable across
     * all providers we always issue a streaming request internally and
     * concatenate the deltas back into a single [LLMResponse]. Callers that
     * actually want incremental delivery should use [streamMessage] instead.
     */
    override suspend fun sendMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): LLMResponse = withContext(Dispatchers.IO) {
        val textBuf = StringBuilder()
        var stopReason: String? = null
        var usage: LLMUsage? = null
        // [T-codex-gpt-image2-oauth-android] Collect model-generated media
        // (gpt-image-2 images) so non-streaming callers — notably
        // minis-model-use (ModelUseOffloadHandler) — get them on
        // LLMResponse.mediaAttachments and can write the image to --output.
        val media = mutableListOf<LLMMediaAttachment>()
        rawStreamMessage(
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            imageParts = imageParts,
            tools = tools,
            thinkingLevel = thinkingLevel,
            stream = false,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> textBuf.append(chunk.text)
                is LLMStreamChunk.Usage -> usage = chunk.usage
                is LLMStreamChunk.Finished -> stopReason = chunk.stopReason
                is LLMStreamChunk.MediaAttachment -> media.add(chunk.attachment)
                else -> Unit
            }
        }
        LLMResponse(textBuf.toString(), stopReason, usage, media)
    }

    override fun streamMessageClamped(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
    ): Flow<LLMStreamChunk> = rawStreamMessage(
        messages, systemPrompt, maxTokens, temperature, imageParts, tools, thinkingLevel,
        stream = true,
    ).failOnSilentEmptyCompletion(name)

    private fun rawStreamMessage(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition>,
        thinkingLevel: ThinkingLevel,
        stream: Boolean,
    ): Flow<LLMStreamChunk> = callbackFlow {
        val body = if (isCodexImageModel) {
            // [T-gpt-image2-codex-backend-route-android] gpt-image-2 on an
            // OpenAI OAuth (Codex) instance is driven through the Codex backend
            // image_generation tool (chatgpt.com/backend-api/codex/responses),
            // NOT the public /v1/images/generations Images API — a Codex OAuth
            // token lacks the api.model.images.request scope and the Images API
            // 401s with "Missing scopes: api.model.images.request" (see
            // codex_oauth_image_generation_summary.md §11.1). Aligns with iOS
            // commit 2dd35a14. The Codex-backend route is selected purely by
            // `isCodexImageModel` (isOAuth + model id) — it does NOT require a
            // codexAccountId (the Chatgpt-Account-Id header is optional and its
            // absence doesn't 401), which is exactly the iOS fall-through bug
            // this avoids. Android has no Images-API path at all, so an OAuth
            // gpt-image-2 request can never reach /v1/images/generations.
            //
            // Special image-generation body — not Chat Completions, not the
            // normal Responses tool shape.
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] gpt-image-2 → route=codex-backend " +
                    "url=chatgpt.com/backend-api/codex/responses isOAuth=$isOAuth " +
                    "hasAccountId=${codexAccountId != null}",
            )
            buildCodexImageBody(messages)
        } else if (usesChatCompletionsAPI) {
            buildRequestBody(messages, systemPrompt, maxTokens, stream = stream, temperature = temperature, imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel)
        } else {
            buildResponsesAPIBody(
                messages, systemPrompt, maxTokens, stream = stream,
                imageParts = imageParts, tools = tools, thinkingLevel = thinkingLevel,
                temperature = temperature,
            )
        }
        // T302: serialize the request body exactly once. Pre-T302 we called
        // body.toString() three times per request (debug log + OAuth byte
        // build + non-OAuth RequestBody), each materialising a fresh 30+ MB
        // string for long agent loops with heavy tool outputs. Stacked, that
        // pushed memory-tight devices (HONOR PTP-AN00) past the OOM line.
        // [T-android-mem-probe-trust] Bracket the serialisation itself. The
        // 2026-08-15 field log recorded `bodyLen=2342987` on the request before
        // a process death but nothing about its memory cost, so "did building
        // this body kill us?" could not be answered from the log. We measure
        // around the call and report the realised length, so an OOM thrown here
        // now arrives with an attributed stack instead of anonymously.
        val memBefore = com.openminis.app.diagnostics.MemorySnapshot.capture()
        val serStartNs = System.nanoTime()
        val bodyStr = try {
            body.toString()
        } catch (t: Throwable) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", -1, "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = t,
            )
            throw t
        }
        if (bodyStr.length >= com.openminis.app.diagnostics.LargeAllocProbe.NOTABLE_BYTES) {
            com.openminis.app.diagnostics.LargeAllocProbe.report(
                "openai.body.toString", bodyStr.length.toLong(),
                "model=${model.id} messages=${messages.size}",
                memBefore, serStartNs, failure = null,
            )
        }
        val request = buildRequest(bodyStr)
        val headerMap = mutableMapOf<String, String>()
        for (name in request.headers.names()) {
            headerMap[name] = request.headers[name] ?: ""
        }
        val startTime = System.currentTimeMillis()

        // T321: request-side diagnostic log. Header *keys* + Authorization
        // presence (no token values), and a body summary (counts only — never
        // the message text/images/tool-result bytes).
        run {
            val authPresent = request.headers["Authorization"] != null
            val msgsLen = body.optJSONArray("messages")?.length()
                ?: body.optJSONArray("input")?.length() ?: 0
            val toolsLen = body.optJSONArray("tools")?.length() ?: 0
            val temp = if (body.has("temperature")) body.optDouble("temperature") else null
            val maxTok = body.optInt("max_completion_tokens", body.optInt("max_tokens", -1))
            val hasSystem = body.has("instructions") ||
                (body.optJSONArray("messages")?.let { arr ->
                    var found = false
                    for (i in 0 until arr.length()) {
                        if (arr.optJSONObject(i)?.optString("role") == "system") { found = true; break }
                    }
                    found
                } ?: false)
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] → REQ url=${request.url} model=${model.id} stream=${body.optBoolean("stream", false)} " +
                    "headerKeys=${request.headers.names()} authPresent=$authPresent " +
                    "messages=$msgsLen tools=$toolsLen temp=$temp maxTokens=$maxTok hasSystem=$hasSystem " +
                    "useResponsesAPI=${!usesChatCompletionsAPI} bodyLen=${bodyStr.length}"
            )
        }

        // [T-android-ttfb-upload-split / #188] Attach per-call state the trace
        // listener fills in (upload-done timestamp + physical connection) so the
        // watchdog below can (a) start the TTFB clock only after upload and
        // (b) evict THIS ONE connection on timeout.
        val watchState = CallWatchState()
        val call = client.newCall(request.newBuilder().tag(CallWatchState::class.java, watchState).build())
        // [T-android-stale-conn-retry-hang] Time-to-first-byte watchdog. A
        // request written into a dead pooled h2 tunnel (local proxy socket
        // survives a network flap) produces NO further events — no headers,
        // no failure — until the 600s read timeout, so the UI showed
        // "thinking" forever.
        //
        // [T-android-ttfb-upload-split / #188] The window is split in two so
        // slow-but-healthy uploads aren't mistaken for a dead connection:
        //   1. UPLOAD phase (call start → requestBodyEnd): bounded loosely by
        //      STREAM_UPLOAD_CAP_MS. writeTimeout(30s) already catches a fully
        //      stalled socket; this only catches slow-trickle-forever.
        //   2. TTFB phase (requestBodyEnd → response headers): the tight
        //      STREAM_TTFB_TIMEOUT_MS budget, measured FROM upload completion.
        // On timeout we cancel the call AND evict just this connection so the
        // auto-retry gets a fresh one (a sibling session's connection is never
        // touched — no evictAll). Once headers arrive the watchdog stops and a
        // flowing SSE stream has NO total-duration limit, as before.
        val ttfbTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val headersArrived = java.util.concurrent.atomic.AtomicBoolean(false)
        val callStartNanos = System.nanoTime()
        val ttfbWatchdog = launch {
            val pollMs = 250L
            var timedOutPhase: String? = null
            while (!headersArrived.get()) {
                val uploadDoneAt = watchState.uploadDoneAtNanos.get()
                val nowNanos = System.nanoTime()
                if (uploadDoneAt == 0L) {
                    // Still uploading (or connecting) — loose upload-phase cap.
                    val elapsedMs = (nowNanos - callStartNanos) / 1_000_000L
                    if (elapsedMs >= STREAM_UPLOAD_CAP_MS) { timedOutPhase = "upload"; break }
                } else {
                    // Upload finished — tight TTFB budget measured from that point.
                    val sinceUploadMs = (nowNanos - uploadDoneAt) / 1_000_000L
                    if (sinceUploadMs >= STREAM_TTFB_TIMEOUT_MS) { timedOutPhase = "ttfb"; break }
                }
                delay(pollMs)
            }
            if (timedOutPhase != null && !headersArrived.get()) {
                ttfbTimedOut.set(true)
                val budgetS = if (timedOutPhase == "ttfb") STREAM_TTFB_TIMEOUT_MS / 1000 else STREAM_UPLOAD_CAP_MS / 1000
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T-android-ttfb-upload-split] no response headers ($timedOutPhase phase, ${budgetS}s) — cancelling call + evicting connection (stale pooled connection?)",
                )
                call.cancel()
                // Targeted eviction: close ONLY this call's physical connection so
                // the retry can't reuse it. Never evictAll — concurrent sessions
                // may hold healthy connections in the shared pool.
                try {
                    watchState.connection.get()?.socket()?.close()
                } catch (_: Throwable) {
                    // Best-effort; call.cancel() already unblocks execute().
                }
            }
        }
        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (ttfbTimedOut.get()) {
                throw LLMError.TransientError(
                    "no response from server (${STREAM_TTFB_TIMEOUT_MS / 1000}s TTFB) — check network/proxy",
                )
            }
            throw e
        } finally {
            headersArrived.set(true)
            ttfbWatchdog.cancel()
        }
        // T321: response-side diagnostic log (status + select header values).
        run {
            val rh = response.headers
            val ct = rh["content-type"] ?: ""
            val rid = rh["x-request-id"] ?: rh["openai-request-id"] ?: ""
            val openAiHdrs = rh.names().filter { it.lowercase().startsWith("openai-") }
            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[T321] ← RSP status=${response.code} content-type=$ct x-request-id=$rid " +
                    "headerKeys=${rh.names()} openAiHeaders=${openAiHdrs.associateWith { rh[it] ?: "" }}"
            )
        }
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            // T321: full error body — debug-only, but kept unconditional here
            // since non-2xx is rare and the body is critical for diagnosis.
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] ← HTTP ${response.code} error body: $errorBody"
            )
            response.close()
            // T302: skip the LLMRequestLog write entirely on release builds —
            // not just to avoid the (already-truncated) retention cost, but to
            // dodge constructing the Entry / headerMap copies that go with it.
            if (com.openminis.app.BuildConfig.DEBUG) {
                com.openminis.app.debug.LLMRequestLog.add(
                    com.openminis.app.debug.LLMRequestLog.Entry(
                        provider = "openai",
                        requestURL = request.url.toString(),
                        requestHeaders = headerMap,
                        requestBody = bodyStr,
                        durationMs = System.currentTimeMillis() - startTime,
                        responseStatusCode = response.code,
                        responseBody = errorBody.take(2000),
                    )
                )
            }
            throw mapHttpError(response.code, errorBody, response.header("Retry-After"))
        }
        if (com.openminis.app.BuildConfig.DEBUG) {
            com.openminis.app.debug.LLMRequestLog.add(
                com.openminis.app.debug.LLMRequestLog.Entry(
                    provider = "openai",
                    requestURL = request.url.toString(),
                    requestHeaders = headerMap,
                    requestBody = bodyStr,
                    durationMs = System.currentTimeMillis() - startTime,
                    responseStatusCode = response.code,
                )
            )
        }

        // Some compatible gateways ignore `stream=true` and return regular
        // Chat Completions JSON. Parse it instead of feeding JSON to the SSE
        // parser and misclassifying the response as silently empty.
        val responseContentType = response.header("Content-Type").orEmpty().lowercase()
        if (usesChatCompletionsAPI && !responseContentType.contains("text/event-stream")) {
            try {
                val json = JSONObject(response.body?.string().orEmpty())
                send(LLMStreamChunk.Started)
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                val message = choice?.optJSONObject("message")
                val text = message?.optString("content", "").orEmpty()
                if (text.isNotEmpty()) send(LLMStreamChunk.Text(text))
                json.optJSONObject("usage")?.let {
                    send(LLMStreamChunk.Usage(parseChatCompletionsUsage(it)))
                }
                send(LLMStreamChunk.Finished(choice?.optString("finish_reason", null)))
            } finally {
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))

        // [T-codex-gpt-image2-oauth-android] gpt-image-2: the Codex backend
        // streams the image as a base64 blob (PNG / JPEG / WebP) inside the SSE
        // `image_generation_call` output item; there's no incremental text/tool
        // stream to parse. handleCodexImageStream parses the SSE line-by-line,
        // pulls the image (or a structured failure), emits a MediaAttachment
        // chunk, and finishes — bypassing the chat/tool SSE state machine below.
        if (isCodexImageModel) {
            try {
                handleCodexImageStream(reader) { chunk -> trySend(chunk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cancel("Image stream error", mapError(e))
            } finally {
                reader.close()
                response.close()
            }
            channel.close()
            awaitClose {
                try { call.cancel() } catch (_: Exception) {}
                try { response.close() } catch (_: Exception) {}
            }
            return@callbackFlow
        }

        // Chat Completions: tool calls are streamed as deltas keyed by index.
        data class ToolCallAccumulator(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastSentArgsLen: Int = 0)
        val toolCallAccumulators = mutableMapOf<Int, ToolCallAccumulator>()
        // Responses API: function_call items are keyed by their item_id (fc_…). We store
        // the call_id separately because the agent loop needs the call_id to correlate
        // tool results, but the next request must echo back the item_id verbatim — so we
        // emit a combined "callId|fcId" identifier that splitResponsesAPIIds() unpacks.
        data class ResponsesToolCallAccumulator(var callId: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var started: Boolean = false, var lastSentArgsLen: Int = 0)
        val responsesToolCalls = mutableMapOf<String, ResponsesToolCallAccumulator>()
        // One-shot info log the first time the Responses API streams a reasoning
        // delta — useful for confirming the Thinking pipeline is wired up when
        // diagnosing "I set thinking high but see nothing" reports.
        var sawReasoningDelta = false
        // Accumulates the opaque reasoning_content blob across SSE deltas so we
        // can echo the exact server-emitted value back on the next turn. Tracked
        // separately from ThinkingDelta concatenation because DeepSeek V4 emits
        // `reasoning_content: ""` legitimately (non-thinking turns) and the
        // empty string must round-trip — fabricating placeholder text causes
        // the model to in-context-learn it (see T249 / T257 history).
        val reasoningAccum = StringBuilder()
        var sawReasoningField = false
        // [T-android-think-prefix-stream] One parser per streaming turn. Handles
        // ALL models: a turn with no `<think>` prefix passes through verbatim, so
        // there is no vendor allowlist to keep in sync (the old `hasThinkTags`
        // heuristic only armed extraction for DashScope/qwen ids, which meant
        // MiniMax M3 relied on the mid-stream `text.contains("<think>")` fallback).
        val thinkParser = ThinkPrefixStreamParser()

        // T321: turn-level SSE counters for empty-response triage.
        var sseEventCount = 0
        var contentLen = 0
        // [T-android-incomplete-keep-partial] True once a text delta carried at
        // least one non-whitespace character. See the response.incomplete handler.
        var sawNonBlankText = false
        var reasoningLen = 0
        var toolCallEventCount = 0
        var sawFinishReason = false
        var sawUsageBlock = false
        // [T-android-responses-missing-finished] Hoisted out of the try block so
        // the stream tail can emit Finished for streams that end WITHOUT a
        // `data: [DONE]` sentinel — see the emission site after the read loop.
        var finishReason: String? = null
        // True once the [DONE] branch has emitted Finished, so the tail below
        // does not send a second one.
        var sentFinished = false

        try {
            send(LLMStreamChunk.Started)
            var line: String?

            // Branch streaming parser based on API format
            val isResponsesAPI = !usesChatCompletionsAPI

            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                // Tolerate `data:` with or without the optional space — the
                // HTML5 SSE spec only treats one leading space as ignorable,
                // and some OpenAI-compatible servers (e.g. China Telecom's
                // eaichat.ctyun.cn deepseek-v4-oc endpoint) emit `data:{...}`
                // with no space. Strict `data: ` matching dropped every
                // chunk on those providers, surfacing as empty-stream errors.
                if (!l.startsWith("data:")) continue
                val payload = l.removePrefix("data:").let {
                    if (it.startsWith(" ")) it.removePrefix(" ") else it
                }
                if (payload == "[DONE]") {
                    // [T-android-think-prefix-stream] Flush whatever the parser
                    // still holds (a cross-chunk tag tail, or an unterminated
                    // <think>). Idempotent, so the finish_reason path may also
                    // call it. Withheld trailing whitespace is dropped by design.
                    thinkParser.finishTurn().let { fin ->
                        if (fin.thinking.isNotEmpty()) {
                            reasoningAccum.append(fin.thinking)
                            send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                        }
                        if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                    }
                    // [T-android-think-prefix-stream] Persist reasoning captured
                    // EITHER from the `reasoning_content` field or from a
                    // `<think>` prefix. Gating solely on sawReasoningField would
                    // stream think-tag reasoning live and then drop it — the
                    // thinking bubble would vanish on session reload.
                    if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                        send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                    }
                    send(LLMStreamChunk.Finished(finishReason))
                    sentFinished = true
                    break
                }

                val event = try { JSONObject(payload) } catch (e: Exception) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[T321] SSE JSON parse failed: ${e.message} payload=${payload.take(300)}"
                    )
                    continue
                }
                if (android.util.Log.isLoggable("ToolChain[Provider]", android.util.Log.VERBOSE)) {
                    android.util.Log.d(
                        "ToolChain[Provider]",
                        "RAW SSE: ${com.openminis.app.text.BoundedText.clampSsePayload(payload)}",
                    )
                }
                sseEventCount++

                // T321: per-event delta-field summary. Only counts/lengths,
                // never the actual delta text — keeps log volume bounded.
                run {
                    val ev = event
                    val delta = ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    val type = ev.optString("type", "")
                    if (delta != null) {
                        val cLen = delta.optString("content", "").length
                        val rcLen = delta.optString("reasoning_content", "").length
                        val rLen = delta.optString("reasoning", "").length
                        val tcLen = delta.optJSONArray("tool_calls")?.length() ?: 0
                        val role = delta.optString("role", "")
                        if ((cLen + rcLen + rLen + tcLen > 0 || delta.has("role")) &&
                            android.util.Log.isLoggable("OpenAIProvider", android.util.Log.VERBOSE)
                        ) {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE delta: contentLen=$cLen rcLen=$rcLen rLen=$rLen toolCalls=$tcLen role='$role'"
                            )
                        }
                        contentLen += cLen
                        reasoningLen += rcLen + rLen
                        if (tcLen > 0) toolCallEventCount += tcLen
                    } else if (type.isNotEmpty()) {
                        // Responses API event-typed diagnostics
                        val dLen = ev.optString("delta", "").length
                        if (type.contains("delta") || type == "response.completed" || type == "response.output_item.added" || type == "response.output_item.done") {
                            com.openminis.app.logging.AppLogger.debug(
                                "OpenAIProvider",
                                "[T321] SSE responses type=$type deltaLen=$dLen"
                            )
                        }
                        if (type == "response.output_text.delta") {
                            contentLen += dLen
                            // [T-android-incomplete-keep-partial] contentLen alone
                            // can't distinguish " " from real text, and the
                            // response.incomplete handler needs that difference to
                            // decide between keeping a truncated answer and failing
                            // the turn. Track it here, where the delta text is in
                            // hand, rather than buffering the whole response.
                            if (!sawNonBlankText && ev.optString("delta", "").isNotBlank()) {
                                sawNonBlankText = true
                            }
                        }
                        if (type.startsWith("response.reasoning_")) reasoningLen += dLen
                    }
                }

                if (isResponsesAPI) {
                    // Responses API SSE parsing
                    val type = event.optString("type", "")
                    when {
                        // Reasoning text deltas — both event variants the API emits.
                        // For Codex OAuth the actual content is encrypted (echoed via
                        // include=reasoning.encrypted_content), so the .delta value
                        // is typically empty; for non-Codex Responses (forceResponsesAPI
                        // or custom base) it streams plaintext we can render.
                        // Mirrors iOS OpenAIAgentProvider.swift:374-382.
                        type == "response.reasoning_text.delta" ||
                            type == "response.reasoning_summary_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) {
                                if (!sawReasoningDelta) {
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Responses API: first reasoning delta arrived (type=$type) — streaming Thinking content"
                                    )
                                    sawReasoningDelta = true
                                }
                                send(LLMStreamChunk.ThinkingDelta(delta))
                            }
                        }
                        type == "response.output_text.delta" -> {
                            val delta = event.optString("delta", "")
                            if (delta.isNotEmpty()) send(LLMStreamChunk.Text(delta))
                        }
                        // function_call item announced — capture call_id + name, start accumulator.
                        type == "response.output_item.added" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val callId = item.optString("call_id", "")
                                val name = item.optString("name", "")
                                if (itemId.isNotEmpty() && callId.isNotEmpty() && name.isNotEmpty()) {
                                    responsesToolCalls[itemId] = ResponsesToolCallAccumulator(callId = callId, name = name)
                                    val combined = combineResponsesAPIIds(callId, itemId)
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart (Responses) id=$combined name=$name")
                                    send(LLMStreamChunk.ToolUseStart(combined, name))
                                    responsesToolCalls[itemId]?.started = true
                                }
                            }
                        }
                        type == "response.function_call_arguments.delta" -> {
                            val itemId = event.optString("item_id", "")
                            val delta = event.optString("delta", "")
                            val acc = responsesToolCalls[itemId]
                            if (acc != null && delta.isNotEmpty()) {
                                acc.args.append(delta)
                                val n = acc.args.length
                                if (com.openminis.app.text.BoundedText.shouldCommitLengthStride(n, acc.lastSentArgsLen)) {
                                    acc.lastSentArgsLen = n
                                    val combined = combineResponsesAPIIds(acc.callId, itemId)
                                    send(LLMStreamChunk.ToolInputDelta(combined, acc.args.toString()))
                                }
                            } else if (acc == null) {
                                // Pre-T107 this branch silently dropped the entire tool call
                                // because no accumulator was set up — leaving the model with
                                // no real tool channel and provoking <tool_call>{...} text
                                // hallucinations. Keep a warn so any future regression here
                                // surfaces in the daily log instead of a silent failure.
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API: function_call_arguments.delta for unknown item_id=$itemId — dropping"
                                )
                            }
                        }
                        // The accumulator is finalized at response.output_item.done, when the
                        // arguments stream has flushed. The completed item carries `arguments`
                        // as a JSON string — we prefer that authoritative value over our own
                        // streamed buffer in case the API ever emits a corrected payload.
                        type == "response.output_item.done" -> {
                            val item = event.optJSONObject("item") ?: continue
                            val itemType = item.optString("type", "")
                            if (itemType == "function_call") {
                                val itemId = item.optString("id", "")
                                val acc = responsesToolCalls.remove(itemId) ?: continue
                                val argsStr = item.optString("arguments", acc.args.toString())
                                val args = try { JSONObject(argsStr) } catch (_: Exception) { JSONObject() }
                                val combined = combineResponsesAPIIds(acc.callId, itemId)
                                android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete (Responses) id=$combined name=${acc.name} args=${args.toString().take(300)}")
                                send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                            }
                        }
                        type == "response.failed" -> {
                            // [T-responses-terminal-events] Explicit terminal
                            // handling instead of the generic fallthrough: pull
                            // the structured error off the response object so
                            // the thrown LLMError carries the real reason (and
                            // so retry/fallback classification can act on it).
                            // Official shape: response.status == "failed",
                            // response.error = {code, message}. Mirrors iOS 637cd890.
                            val resp = event.optJSONObject("response")
                            val err = resp?.optJSONObject("error")
                            val code = err?.optString("code")?.takeIf { it.isNotEmpty() } ?: "unknown"
                            val message = err?.optString("message")?.takeIf { it.isNotEmpty() }
                                ?: "response.failed with no error detail"
                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.failed — code=$code message=$message"
                            )
                            if (code == "server_error" || code == "rate_limit_exceeded") {
                                // Transient family: retry on the same model
                                // rather than falling back through the group.
                                throw LLMError.TransientError("[$code] $message")
                            }
                            throw LLMError.ProviderError("[$code] $message")
                        }
                        type == "response.incomplete" -> {
                            // [T-responses-terminal-events] The server ended the
                            // response early; incomplete_details.reason is
                            // "max_output_tokens" or "content_filter".
                            val reason = event.optJSONObject("response")
                                ?.optJSONObject("incomplete_details")
                                ?.optString("reason")?.takeIf { it.isNotEmpty() }
                                ?: "unknown"

                            // [T-android-incomplete-keep-partial] Only fail the
                            // turn when there is genuinely nothing to show.
                            //
                            // The old code threw unconditionally, and the comment
                            // above it ("Partial output has already been streamed
                            // — surface WHY") described an intent the code did not
                            // implement: throwing here discards the streamed text,
                            // so a truncated-but-useful answer was reported to the
                            // user as a hard failure with nothing rendered.
                            //
                            // Reported against a Responses-format relay proxying
                            // Claude: the model spent its whole budget in the
                            // reasoning phase and emitted one space of visible
                            // text, then `response.incomplete
                            // reason=max_output_tokens` with
                            // `usage.output_tokens=0`. Raising the setting could
                            // not help (the budget went to reasoning, and the
                            // relay reports output_tokens=0 regardless), so every
                            // retry failed the same way and the turn was lost.
                            //
                            // Truncation is a normal terminal condition, not an
                            // error: Chat Completions already models it as
                            // `finish_reason=length` and ends the stream
                            // normally. Treating the Responses spelling the same
                            // way keeps the two API flavours consistent and lets
                            // the agent loop persist what did arrive.
                            //
                            // `contentLen` counts text deltas actually forwarded
                            // downstream, so it is the honest test for "does the
                            // user have something to read". Whitespace-only output
                            // (the reported case) counts as nothing, since a bubble
                            // containing one space is indistinguishable from a bug.
                            val hasUsableOutput = sawNonBlankText
                            if (hasUsableOutput) {
                                com.openminis.app.logging.AppLogger.warning(
                                    "OpenAIProvider",
                                    "Responses API response.incomplete — reason=$reason; " +
                                        "keeping ${contentLen}ch of partial output (finish_reason=length)"
                                )
                                // Same terminal shape Chat Completions uses for a
                                // budget-truncated answer, so downstream code needs
                                // no new branch: the loop stops, the text persists,
                                // and the UI can mark it truncated.
                                finishReason = "length"
                                sawFinishReason = true
                                break
                            }

                            com.openminis.app.logging.AppLogger.error(
                                "OpenAIProvider",
                                "Responses API response.incomplete — reason=$reason (no usable output)"
                            )
                            throw LLMError.ProviderError(
                                "Response ended incomplete (reason: $reason)" +
                                    if (reason == "max_output_tokens") {
                                        // The old text told the user to raise Max
                                        // Output Tokens. When reasoning consumed
                                        // the budget that advice is actively
                                        // misleading — this user tried 128k, 32k
                                        // and 16k, all identical — so name the
                                        // real lever too.
                                        " — the model used its entire output budget before producing a reply" +
                                            " (often the thinking phase on a reasoning model). Try turning off or" +
                                            " lowering Deep Thinking, shortening the request, or raising the model's" +
                                            " Max Output Tokens."
                                    } else ""
                            )
                        }
                        type == "response.completed" -> {
                            val resp = event.optJSONObject("response")
                            val status = resp?.optString("status", "")
                            // When the model emitted tool calls the API returns status=completed
                            // with no stop_reason; surface "tool_use" so the agent loop knows to
                            // dispatch the calls instead of treating the turn as final.
                            val sawToolCalls = responsesToolCalls.isNotEmpty() ||
                                (resp?.optJSONArray("output")?.let { out ->
                                    var found = false
                                    for (i in 0 until out.length()) {
                                        if (out.optJSONObject(i)?.optString("type") == "function_call") { found = true; break }
                                    }
                                    found
                                } ?: false)
                            finishReason = when {
                                sawToolCalls -> "tool_use"
                                status == "completed" -> "stop"
                                else -> status
                            }
                            if (!sawFinishReason) {
                                sawFinishReason = true
                                // [T-codex-fast-mode] The response object inside
                                // response.completed echoes the EFFECTIVE
                                // service_tier — "priority" here is definitive
                                // proof Fast Mode was honored; "default"/absent
                                // means requested-but-downgraded (OpenAI silently
                                // downgrades ineligible accounts). Mirrors iOS
                                // 63a71146.
                                val serviceTier = resp?.optString("service_tier", "")
                                    ?.takeIf { it.isNotEmpty() } ?: "n/a"
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses finish_reason=$finishReason status=$status service_tier=$serviceTier contentLen=$contentLen reasoningLen=$reasoningLen toolCallAccumulators=${responsesToolCalls.size}"
                                )
                            }
                            resp?.optJSONObject("usage")?.let { usage ->
                                sawUsageBlock = true
                                com.openminis.app.logging.AppLogger.info(
                                    "OpenAIProvider",
                                    "[T321] Responses usage block: $usage"
                                )
                                send(LLMStreamChunk.Usage(parseResponsesAPIUsage(usage)))
                            }
                        }
                        type == "response.output_text.done" -> {
                            // Text output complete, no action needed
                        }
                    }
                } else {
                    // Chat Completions API SSE parsing
                    // Check for inline error (OpenRouter sends error inside SSE with empty choices)
                    val inlineError = event.optJSONObject("error")
                    if (inlineError != null) {
                        val code = inlineError.optInt("code", 0)
                        val msg = inlineError.optString("message", "Unknown SSE error")
                        val err = mapHttpError(code, event.toString())
                        throw err
                    }
                    val choices = event.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")

                        // Reasoning / thinking content (DeepSeek, Kimi, etc.)
                        delta?.let { d ->
                            // Track presence of either field — even an empty string
                            // counts so we can round-trip DeepSeek V4's `reasoning_content: ""`.
                            val hasRcKey = d.has("reasoning_content")
                            val hasReasoningKey = d.has("reasoning")
                            if (hasRcKey || hasReasoningKey) {
                                sawReasoningField = true
                            }
                            val rc = d.safeOptString("reasoning_content", "")
                                .ifEmpty { d.safeOptString("reasoning", "") }
                            if (rc.isNotEmpty()) {
                                reasoningAccum.append(rc)
                                if (!sawReasoningDelta) {
                                    sawReasoningDelta = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "Chat Completions: first reasoning_content delta arrived on ${model.id} — streaming Thinking content"
                                    )
                                }
                                send(LLMStreamChunk.ThinkingDelta(rc))
                            }
                        }

                        // [T-android-think-prefix-stream] Text content. Models that
                        // embed reasoning as a `<think>…</think>` PREFIX of
                        // `content` (MiniMax M3, some Qwen/DeepSeek deployments)
                        // are split by ThinkPrefixStreamParser, which replaced the
                        // old extractThinkTags scanner. That scanner searched for
                        // `<think>` at ANY offset, so a reply merely explaining the
                        // tag had its prose swallowed into the thinking bubble, and
                        // it passed M3's post-`</think>` "\n\n" straight through so
                        // every such body began with a blank line.
                        delta?.safeOptString("content", "")?.let { text ->
                            if (text.isNotEmpty()) {
                                val out = thinkParser.feed(text)
                                if (out.thinking.isNotEmpty()) {
                                    reasoningAccum.append(out.thinking)
                                    send(LLMStreamChunk.ThinkingDelta(out.thinking))
                                }
                                if (out.visible.isNotEmpty()) send(LLMStreamChunk.Text(out.visible))
                            }
                        }

                        // Tool calls (parallel: keyed by index)
                        val toolCalls = delta?.optJSONArray("tool_calls")
                        if (toolCalls != null) {
                            for (i in 0 until toolCalls.length()) {
                                val tc = toolCalls.getJSONObject(i)
                                val idx = tc.optInt("index", 0)
                                val acc = toolCallAccumulators.getOrPut(idx) { ToolCallAccumulator() }

                                tc.safeOptString("id", "").let { if (it.isNotEmpty()) acc.id = it }
                                tc.optJSONObject("function")?.let { fn ->
                                    fn.safeOptString("name", "").let { if (it.isNotEmpty()) acc.name = it }
                                    fn.safeOptString("arguments", "").let { if (it.isNotEmpty()) acc.args.append(it) }
                                }

                                // Emit start exactly once per tool call
                                if (!acc.started && acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                                    acc.started = true
                                    android.util.Log.d("ToolChain[Provider]", "→ ToolUseStart id=${acc.id} name=${acc.name}")
                                    send(LLMStreamChunk.ToolUseStart(acc.id, acc.name))
                                }
                                // Emit input delta
                                if (acc.id.isNotEmpty() && acc.args.isNotEmpty()) {
                                    val n = acc.args.length
                                    if (com.openminis.app.text.BoundedText.shouldCommitLengthStride(n, acc.lastSentArgsLen)) {
                                        acc.lastSentArgsLen = n
                                        if (com.openminis.app.text.BoundedText.shouldLogLengthStride(n)) {
                                            android.util.Log.d("ToolChain[Provider]", "→ ToolInputDelta id=${acc.id} accumulated=${n}chars")
                                        }
                                        send(LLMStreamChunk.ToolInputDelta(acc.id, acc.args.toString()))
                                    }
                                }
                            }
                        }

                        // Finish reason
                        choice.safeOptString("finish_reason", "").let {
                            if (it.isNotEmpty()) {
                                finishReason = it
                                if (!sawFinishReason) {
                                    sawFinishReason = true
                                    com.openminis.app.logging.AppLogger.info(
                                        "OpenAIProvider",
                                        "[T321] finish_reason=$it contentLen=$contentLen reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount accumulators=${toolCallAccumulators.size}"
                                    )
                                }
                            }
                        }
                    }

                    event.optJSONObject("usage")?.let { usage ->
                        sawUsageBlock = true
                        com.openminis.app.logging.AppLogger.info(
                            "OpenAIProvider",
                            "[T321] usage block: $usage"
                        )
                        send(LLMStreamChunk.Usage(parseChatCompletionsUsage(usage)))
                    }
                }
            }

            // [T-android-think-prefix-stream] Stream-end flush, for streams that
            // end without a `[DONE]` sentinel. finishTurn() is idempotent, so
            // running after the [DONE] path already flushed is a no-op.
            thinkParser.finishTurn().let { fin ->
                if (fin.thinking.isNotEmpty()) {
                    reasoningAccum.append(fin.thinking)
                    send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                }
                if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
            }

            // Emit ToolCallComplete for all accumulated tool calls
            for ((_, acc) in toolCallAccumulators) {
                if (acc.id.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    android.util.Log.d("ToolChain[Provider]", "→ ToolCallComplete id=${acc.id} name=${acc.name} args=${args.toString().take(300)}")
                    send(LLMStreamChunk.ToolCallComplete(acc.id, acc.name, args))
                }
            }
            // Drain Responses-API tool accumulators that didn't get an output_item.done
            // before the stream closed. Without this, mid-tool-call truncation (server
            // closes connection while function_call_arguments is still streaming) leaves
            // ChatViewModel.toolCalls empty: the agent loop sees no tool calls, exits,
            // and the UI hangs with the tool thumbnail spinning while the stop button
            // disappears (T247 root cause; same path hit by T237 DeepSeek truncation).
            for ((itemId, acc) in responsesToolCalls) {
                if (acc.callId.isNotEmpty() && acc.name.isNotEmpty()) {
                    val args = try { JSONObject(acc.args.toString()) } catch (_: Exception) { JSONObject() }
                    val combined = combineResponsesAPIIds(acc.callId, itemId)
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "Stream ended mid-tool-call id=$combined name=${acc.name} argsLen=${acc.args.length} — flushing as ToolCallComplete (T248)",
                    )
                    send(LLMStreamChunk.ToolCallComplete(combined, acc.name, args))
                }
            }
            responsesToolCalls.clear()

            // T321: stream ended — final tally + warning if we never saw a
            // finish_reason. The latter is the strongest signal of a server-
            // side truncation / connection-dropped scenario.
            // [T-android-responses-missing-finished] Emit the terminal chunk for
            // streams that end without a `data: [DONE]` sentinel.
            //
            // Finished was only ever sent from the [DONE] branch. Chat
            // Completions always sends that sentinel, but the Responses API
            // terminates with `response.completed` and many relays simply close
            // the socket afterwards — no [DONE] ever arrives. The read loop then
            // exits normally, the channel closes, and no Finished is emitted.
            //
            // Downstream, ChatViewModel.runAgentLoop only ever assigns
            // turnFinishReason from a Finished chunk, so it stayed null and the
            // turn was reported as "stream closed without a finish reason" —
            // the red "连接中断，此回复可能不完整" banner on a reply that was in
            // fact complete. It looked intermittent because it depends on the
            // relay: those that do append [DONE] worked, the rest did not, which
            // is why the same model on the same account failed only sometimes,
            // and why Chat Completions models (deepseek) never showed it.
            //
            // Gated on sawFinishReason: reaching here WITHOUT one is a genuine
            // truncation, and must keep falling through to the warning below so
            // the interrupted-reply UI still fires for real drops.
            if (sawFinishReason && !sentFinished) {
                // Mirror the [DONE] branch's ordering: flush the think-tag
                // parser and reasoning blob before the terminal chunk, or a
                // trailing <think> tail would be dropped and reasoning would
                // vanish on reload.
                thinkParser.finishTurn().let { fin ->
                    if (fin.thinking.isNotEmpty()) {
                        reasoningAccum.append(fin.thinking)
                        send(LLMStreamChunk.ThinkingDelta(fin.thinking))
                    }
                    if (fin.visible.isNotEmpty()) send(LLMStreamChunk.Text(fin.visible))
                }
                if (sawReasoningField || reasoningAccum.isNotEmpty()) {
                    send(LLMStreamChunk.ReasoningContent(reasoningAccum.toString()))
                }
                send(LLMStreamChunk.Finished(finishReason))
                sentFinished = true
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream ended without [DONE] — emitted Finished(finishReason=$finishReason) from tail"
                )
            }

            if (!sawFinishReason) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[T321] stream ended WITHOUT finish_reason: events=$sseEventCount " +
                        "contentLen=$contentLen reasoningLen=$reasoningLen " +
                        "toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock model=${model.id}"
                )
            } else {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[T321] stream complete: events=$sseEventCount contentLen=$contentLen " +
                        "reasoningLen=$reasoningLen toolCallEvents=$toolCallEventCount sawUsage=$sawUsageBlock"
                )
            }
        } catch (e: Exception) {
            // T321: never silently swallow — log message + top-3 stack frames.
            val frames = e.stackTrace.take(3).joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            com.openminis.app.logging.AppLogger.error(
                "OpenAIProvider",
                "[T321] stream parse exception: ${e.javaClass.simpleName}: ${e.message} @ $frames " +
                    "(events=$sseEventCount contentLen=$contentLen reasoningLen=$reasoningLen)"
            )
            cancel("Stream error", mapError(e))
        } finally {
            reader.close()
            response.close()
        }
        channel.close()
        // T171: when the coroutine is cancelled (user tapped stop), the
        // reader loop above is suspended inside the OkHttp source — only
        // call.cancel() will tear the socket down promptly. response.close()
        // is also explicit so connection-pool leaks are impossible if cancel
        // races with the finally block.
        awaitClose {
            try { call.cancel() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
        }
    }

    // MARK: - Raw Passthrough [T-android-model-use-passthrough-mode]

    /**
     * Result of a raw passthrough call: unparsed response bytes + HTTP status +
     * the fully-assembled URL that was actually hit (surfaced to the caller per
     * the passthrough-mode contract). Mirrors iOS RawPassthroughResult.
     */
    class RawPassthroughResult(
        val data: ByteArray,
        val status: Int,
        val contentType: String?,
        val url: String,
    )

    /**
     * Execute a verbatim request against this provider instance's base URL with
     * the instance's credentials. The response is returned UNPARSED — passthrough
     * mode's output contract is raw bytes; the caller (agent or follow-up script)
     * owns interpretation. Mirrors iOS OpenAIProvider.rawPassthroughRequest.
     *
     * - endpoint: absolute path ("/x/y?q=1", replaces the whole URL path) or
     *   relative segment (joined after basePath like modeled endpoints). null →
     *   the default chat/completions path.
     * - headers: applied LAST → same-name REPLACE semantics over every default
     *   (including Authorization/Content-Type), per design.
     */
    suspend fun rawPassthroughRequest(
        endpoint: String?,
        method: String,
        headers: Map<String, String>,
        body: HttpBody?,
    ): RawPassthroughResult = withContext(Dispatchers.IO) {
        val url: String = when {
            endpoint != null && endpoint.startsWith("/") ->
                hostRootURL(endpoint)
                    ?: throw LLMError.ProviderError("Invalid passthrough endpoint: $endpoint")
            endpoint != null -> "$basePath/${endpoint.trimStart('/')}"
            else -> endpointURL("/chat/completions")
        }

        if (body != null) RequestBodyGate.check(body, "rawPassthrough")
        val admitted = body?.estimatedBytes ?: 0L

        val verb = method.uppercase()
        val builder = Request.Builder().url(url)
        // GET never carries a body. POST/PUT/PATCH/DELETE with Empty/null still
        // need a RequestBody — OkHttp rejects method(POST, null).
        val requestBody = com.openminis.app.data.body.Admission.occupy(admitted) {
            if (verb == "GET") {
                null
            } else {
                body?.toOkHttpRequestBody()
                    ?: ByteArray(0).toRequestBody(HttpBody.JSON_MEDIA_TYPE)
            }
        }
        builder.method(verb, requestBody)
        val token = getToken()
        builder.applyKeyAuth(token)
        // Content-Type is DERIVED. Multipart returns null so OkHttp owns the
        // boundary; a user Content-Type header must not clobber that.
        val multipart = body is HttpBody.Multipart
        if (!multipart) {
            val derived = body?.contentType() ?: HttpBody.JSON_MEDIA_TYPE
            builder.header("Content-Type", derived.toString())
        }
        // ctor extraHeaders, then user headers LAST — replace semantics,
        // except Content-Type on multipart (boundary belongs to OkHttp).
        for ((k, v) in extraHeaders) {
            if (multipart && k.equals("Content-Type", ignoreCase = true)) continue
            builder.header(k, v)
        }
        for ((k, v) in headers) {
            if (multipart && k.equals("Content-Type", ignoreCase = true)) continue
            builder.header(k, v)
        }

        val bodyKeys = when (body) {
            is HttpBody.Json -> body.obj.keys().asSequence().sorted().joinToString(",")
            is HttpBody.Multipart -> body.parts.joinToString(",") { it.name }
            else -> ""
        }
        com.openminis.app.logging.AppLogger.info(
            "OpenAIProvider",
            "[ModelUseRoute] route=raw-passthrough method=$verb url=$url " +
                "bodyKeys=[$bodyKeys] " +
                "headerOverrides=[${headers.keys.sorted().joinToString(",")}]",
        )

        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            RawPassthroughResult(
                data = resp.body?.bytes() ?: ByteArray(0),
                status = resp.code,
                contentType = resp.header("Content-Type"),
                url = url,
            )
        }
    }

    /**
     * [T-android-image-endpoint-mode] Generate an image via the OpenAI Images
     * API (`POST $basePath/images/generations`). Mirrors iOS
     * OpenAIProvider.generateImage. Used only by ModelUseOffloadHandler's
     * image-output routing for API-key OpenAI-compat instances — the Codex
     * OAuth gpt-image-2 path goes through the existing isCodexImageModel branch
     * and never reaches here.
     *
     * Request body: `{ model, prompt, n, size?, quality?, response_format:
     * "b64_json" }`. Some gateways (e.g. xAI) reject `response_format` — on a
     * 400 mentioning it, we retry once without the field (iOS parity).
     *
     * On a non-2xx response throws [mapHttpError]'s result. A route-missing
     * error (404 / "got chat completions response") surfaces as
     * LLMError.ProviderError whose message the handler matches with
     * looksLikeEndpointMissing() to drive the auto-mode fallback.
     */
    override suspend fun generateImage(
        prompt: String,
        n: Int,
        size: String?,
        quality: String?,
    ): LLMResponse = withContext(Dispatchers.IO) {
        // Codex OAuth image models use the existing Responses SSE image_generation tool.
        if (isCodexImageModel) {
            return@withContext sendMessage(
                messages = listOf(LLMMessage(LLMMessage.Role.USER, prompt.trim())),
                systemPrompt = null,
                maxTokens = 1024,
            )
        }
        val vendor = resolvedVendorMedia()
        VendorMedia.unsupportedMessage(vendor, "image")?.let { throw LLMError.ProviderError(it) }
        val token = getToken()
        if (vendor == VendorMediaKind.DASHSCOPE && VendorMedia.looksLikeWanxNativeImage(model.id)) {
            return@withContext generateDashScopeImage(prompt, n, size, token)
        }
        if (vendor == VendorMediaKind.MINIMAX) {
            return@withContext generateMinimaxImage(prompt, n, token)
        }
        // [T-android-model-use-image-passthrough GH#62] Honor an explicit
        // endpoint-path override (non-standard providers); default otherwise.
        val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/generations"
        // [T-android-model-use-passthrough-mode] The absolute-path override wins
        // over the legacy relative imagePathOverride (which is joined after
        // basePath and can't escape base prefixes — iOS baseline p03).
        // [T-android-azure-openai] Azure image generation routes via the
        // deployments path + api-key header; falls back to basePath otherwise.
        val abs = absoluteEndpointOverride
        val url = when {
            abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
            isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
            // Ark Seedream and Zhipu CogView speak OpenAI Images JSON but live
            // at a host-root path that /v1 suffixing would miss.
            imagePathOverride.isNullOrBlank() && vendor == VendorMediaKind.ARK ->
                hostRootURL("/api/v3/images/generations") ?: "$basePath$imagePath"
            imagePathOverride.isNullOrBlank() && vendor == VendorMediaKind.ZHIPU ->
                hostRootURL("/api/paas/v4/images/generations") ?: "$basePath$imagePath"
            imagePathOverride.isNullOrBlank() && vendor == VendorMediaKind.DASHSCOPE ->
                hostRootURL("/compatible-mode/v1/images/generations") ?: "$basePath$imagePath"
            else -> "$basePath$imagePath"
        }

        // [T-android-model-use-image-passthrough GH#62] When the user explicitly
        // supplies response_format, respect it and skip the b64_json auto-probe.
        val userSetResponseFormat = imageExtraBody.containsKey("response_format")
        var triedWithoutFormat = userSetResponseFormat
        while (true) {
            val body = JSONObject()
                .put("model", model.id)
                .put("prompt", prompt)
                .put("n", n)
            if (size != null) body.put("size", size)
            if (quality != null) body.put("quality", quality)
            if (!triedWithoutFormat) body.put("response_format", "b64_json")
            // [T-android-model-use-image-passthrough GH#62] Merge user-supplied
            // passthrough fields. User keys WIN over our defaults (they can
            // override prompt/size or add Seedream's `image`/`watermark`), but
            // `model` is force-kept to the resolved id afterward so a stray
            // override can't misroute the request.
            for ((k, v) in imageExtraBody) body.put(k, v ?: JSONObject.NULL)
            body.put("model", model.id)

            val bodyStr = body.toString()
            val jsonMediaType = "application/json".toMediaType()
            val bodyBytes = bodyStr.toByteArray(Charsets.UTF_8)
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = jsonMediaType
                override fun contentLength() = bodyBytes.size.toLong()
                override fun writeTo(sink: okio.BufferedSink) { sink.write(bodyBytes) }
            }
            val builder = Request.Builder()
                .url(url)
                .post(requestBody)
                .applyKeyAuth(token)
                .header("Content-Type", "application/json")
            for ((key, value) in extraHeaders) {
                builder.header(key, value)
            }
            // [T-android-model-use-image-passthrough GH#62] Per-call passthrough
            // headers, merged after the ctor extraHeaders so they can add/override.
            for ((key, value) in imageExtraHeaders) {
                builder.header(key, value)
            }
            builder.applyUserAgentOverride(customUserAgent)
            val request = builder.build()

            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] → images/generations url=$url model=${model.id} n=$n " +
                    "size=$size quality=$quality respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
            )

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            // Some providers (xAI) don't support b64_json — retry without it once.
            if (!triedWithoutFormat && statusCode == 400 &&
                (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
            ) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/generations HTTP $statusCode body=${responseBody.take(300)}",
                )
                throw mapHttpError(statusCode, responseBody)
            }

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw LLMError.ProviderError("images/generations returned non-JSON body: ${e.message}")
            }
            return@withContext parseImageGenerationsResult(json)
        }
        @Suppress("UNREACHABLE_CODE")
        throw LLMError.ProviderError("images/generations: unreachable")
    }

    /**
     * [T-android-image-edit-endpoint] Call `/images/edits` for image-to-image
     * (reference-image) generation. Android previously had no such endpoint, so
     * minis-model-use returned `image_edit_not_supported` for every
     * input-image + pure-image-generator call — the gap this closes. Mirrors
     * iOS `OpenAIProvider.editImage`.
     *
     * Request: multipart/form-data with `image` (file), `prompt`, `model`, `n`,
     * plus optional `size` / `quality`.
     * Response: identical shape to `/images/generations`
     * (`{ data: [{ b64_json?, url? }] }`), so [parseImageGenerationsResult] is
     * reused verbatim.
     *
     * Multi-image: the first attachment goes in as `image`, any extras as
     * `image[]` — same field naming as iOS. Providers that only accept a single
     * reference image reject the extras themselves; nothing is silently dropped
     * on our side.
     */
    suspend fun editImage(
        prompt: String,
        images: List<LLMMessage.ImagePart>,
        n: Int = 1,
        size: String? = null,
        quality: String? = null,
    ): LLMResponse = withContext(Dispatchers.IO) {
        if (images.isEmpty()) {
            throw LLMError.ProviderError("images/edits requires at least one input image")
        }
        val token = getToken()
        // Same override precedence as generateImage: explicit path override →
        // Azure deployments path → basePath. Only the default differs.
        val imagePath = imagePathOverride?.takeIf { it.isNotBlank() } ?: "/images/edits"
        val abs = absoluteEndpointOverride
        val url = when {
            abs != null && abs.startsWith("/") -> hostRootURL(abs) ?: "$basePath$imagePath"
            isAzure -> azureUrl(imagePath) ?: "$basePath$imagePath"
            else -> "$basePath$imagePath"
        }

        // b64_json auto-probe, mirroring generateImage: some providers reject
        // response_format on the edits route, so retry once without it.
        val userSetResponseFormat = imageExtraBody.containsKey("response_format")
        var triedWithoutFormat = userSetResponseFormat
        while (true) {
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            multipart.addFormDataPart("model", model.id)
            multipart.addFormDataPart("prompt", prompt)
            multipart.addFormDataPart("n", n.toString())
            if (size != null) multipart.addFormDataPart("size", size)
            if (quality != null) multipart.addFormDataPart("quality", quality)
            if (!triedWithoutFormat) multipart.addFormDataPart("response_format", "b64_json")
            // Passthrough body fields arrive as JSON scalars; multipart carries
            // text only, so stringify. `model` is re-pinned below so a stray
            // override can't misroute the request (same rule as generateImage).
            for ((k, v) in imageExtraBody) {
                if (k == "model") continue
                multipart.addFormDataPart(k, v?.toString() ?: "")
            }

            for ((idx, img) in images.withIndex()) {
                val ext = img.mimeType.substringAfterLast('/', "").ifEmpty { "png" }
                val fieldName = if (idx == 0) "image" else "image[]"
                multipart.addFormDataPart(
                    fieldName,
                    "image$idx.$ext",
                    img.data.toRequestBody(img.mimeType.toMediaType()),
                )
            }

            val builder = Request.Builder()
                .url(url)
                .post(multipart.build())
                .applyKeyAuth(token)
            for ((key, value) in extraHeaders) {
                builder.header(key, value)
            }
            for ((key, value) in imageExtraHeaders) {
                builder.header(key, value)
            }
            builder.applyUserAgentOverride(customUserAgent)
            val request = builder.build()

            com.openminis.app.logging.AppLogger.info(
                "OpenAIProvider",
                "[ModelUseRoute] → images/edits url=$url model=${model.id} n=$n " +
                    "size=$size quality=$quality images=${images.size} " +
                    "respFormat=${if (triedWithoutFormat) "<none>" else "b64_json"}",
            )

            val response = client.newCall(request).execute()
            val statusCode = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (!triedWithoutFormat && statusCode == 400 &&
                (responseBody.lowercase().contains("response_format") || responseBody.contains("b64_json"))
            ) {
                com.openminis.app.logging.AppLogger.info(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/edits rejected b64_json — retrying without response_format",
                )
                triedWithoutFormat = true
                continue
            }

            if (statusCode !in 200..299) {
                com.openminis.app.logging.AppLogger.warning(
                    "OpenAIProvider",
                    "[ModelUseRoute] images/edits HTTP $statusCode body=${responseBody.take(300)}",
                )
                throw mapHttpError(statusCode, responseBody)
            }

            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                throw LLMError.ProviderError("images/edits returned non-JSON body: ${e.message}")
            }
            return@withContext parseImageGenerationsResult(json)
        }
        @Suppress("UNREACHABLE_CODE")
        throw LLMError.ProviderError("images/edits: unreachable")
    }

    /**
     * Parse the `/images/generations` response into an [LLMResponse] carrying
     * the decoded image bytes as [LLMMediaAttachment]s. Supports `b64_json`
     * (inline) and `url` (downloaded) item shapes. Mirrors iOS
     * parseImageGenerationsResult. When the body has no `data` array but DOES
     * carry `choices`, a proxy silently rerouted us to chat completions — throw
     * a route-missing error so auto-mode falls back instead of caching the
     * wrong endpoint.
     */
    private fun parseImageGenerationsResult(json: JSONObject): LLMResponse {
        val dataArray = json.optJSONArray("data")
        if (dataArray == null) {
            if (json.has("choices")) {
                throw LLMError.ProviderError(
                    "[404] /images/generations not supported (got chat completions response)",
                )
            }
            return LLMResponse("", "end_turn", null, emptyList())
        }

        val attachments = mutableListOf<LLMMediaAttachment>()
        val revisedPrompts = mutableListOf<String>()
        for (i in 0 until dataArray.length()) {
            val item = dataArray.optJSONObject(i) ?: continue
            val hintMime = item.safeOptString("mime_type", "").ifEmpty { null } // xAI extension
            val b64 = item.safeOptString("b64_json", "")
            if (b64.isNotEmpty()) {
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    com.openminis.app.logging.AppLogger.warning(
                        "OpenAIProvider",
                        "[ModelUseRoute] images/generations b64 decode failed: ${e.message}",
                    )
                    continue
                }
                val mime = hintMime ?: detectImageMime(bytes)
                attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, bytes))
            } else {
                val urlStr = item.safeOptString("url", "")
                if (urlStr.isNotEmpty()) {
                    try {
                        val dlReq = Request.Builder().url(urlStr).get().build()
                        val dlResp = client.newCall(dlReq).execute()
                        val dlBytes = dlResp.body?.bytes()
                        val ctMime = dlResp.header("Content-Type")
                        dlResp.close()
                        if (dlBytes != null && dlBytes.isNotEmpty()) {
                            val mime = hintMime ?: ctMime ?: detectImageMime(dlBytes)
                            attachments.add(LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, mime, dlBytes))
                        }
                    } catch (e: Exception) {
                        com.openminis.app.logging.AppLogger.warning(
                            "OpenAIProvider",
                            "[ModelUseRoute] failed to download image from $urlStr: ${e.message}",
                        )
                    }
                }
            }
            val revised = item.safeOptString("revised_prompt", "")
            if (revised.isNotEmpty()) revisedPrompts.add(revised)
        }

        val text = revisedPrompts.joinToString("\n")
        return LLMResponse(text, "end_turn", null, attachments)
    }

    /**
     * OpenAI Videos API (`POST /videos` + poll + `/content`) and common
     * OpenAI-compatible relay shapes (`/video/generations`, sync `data[].url`).
     */
    /**
     * Blocking [Call.execute] does not notice coroutine cancellation until the
     * socket times out. Stop must tear the video request down the same way the
     * streaming path does: [Call.cancel] from the cancellation handler.
     */
    private suspend fun Call.executeCancellable(): Response {
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { cancel() } }
            try {
                val response = execute()
                if (cont.isActive) cont.resume(response) else runCatching { response.close() }
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }
    }

    override suspend fun generateVideo(prompt: String): LLMResponse = try {
        videoModeSent = null
        withContext(Dispatchers.IO) {
            ProviderKeyGate.withPermit(callGateKey) {
                generateVideoLocked(prompt.trim())
            }
        }
    } finally {
        // Per-call. A later video request must not inherit std/pro from this one.
        videoMode = null
    }

    private suspend fun generateVideoLocked(prompt: String): LLMResponse {
        if (prompt.isEmpty()) throw LLMError.ProviderError("Video prompt is empty")
        val token = getToken()
        val vendor = resolvedVendorMedia()
        VendorMedia.unsupportedMessage(vendor, "video")?.let { throw LLMError.ProviderError(it) }
        when (vendor) {
            VendorMediaKind.ARK -> return generateArkVideo(prompt, token)
            VendorMediaKind.ZHIPU -> return generateZhipuVideo(prompt, token)
            VendorMediaKind.DASHSCOPE -> return generateDashScopeVideo(prompt, token)
            VendorMediaKind.MINIMAX -> return generateMinimaxVideo(prompt, token)
            else -> Unit
        }
        val abs = absoluteEndpointOverride?.takeIf { it.startsWith("/") }
        // Image calls join under basePath (`/v1/images/generations`) and the same
        // key works. Guessing host-root `/videos` first hits a different gateway
        // that answers "Invalid API key" and used to abort before `/v1/videos`.
        val candidates = linkedMapOf<String, Boolean>()
        fun addCandidate(url: String, authFatal: Boolean) {
            if (url.isNotBlank()) candidates.putIfAbsent(url, authFatal)
        }
        if (abs != null) {
            addCandidate(hostRootURL(abs) ?: "$basePath$abs", true)
        } else {
            addCandidate("$basePath/videos", true)
            hostRootURL("/v1/videos")?.let { addCandidate(it, false) }
            addCandidate("$basePath/video/generations", false)
            addCandidate("$basePath/videos/generations", false)
            hostRootURL("/videos")?.let { addCandidate(it, false) }
            hostRootURL("/video/generations")?.let { addCandidate(it, false) }
            hostRootURL("/videos/generations")?.let { addCandidate(it, false) }
        }
        var lastError: LLMError? = null
        for ((url, authFatal) in candidates) {
            val path = when {
                url.contains("/video/generations") -> "/video/generations"
                url.contains("/videos/generations") -> "/videos/generations"
                else -> "/videos"
            }
            var modeToSend = videoMode?.trim()?.takeIf { it.isNotEmpty() }
            var triedDefaultMode = false
            while (true) {
            val body = org.json.JSONObject()
                .put("model", model.id)
                .put("prompt", prompt)
            if (modeToSend != null) body.put("mode", modeToSend)
            val req = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .applyKeyAuth(token)
                .header("Content-Type", "application/json")
                .apply {
                    for ((k, v) in extraHeaders) header(k, v)
                }
                .applyUserAgentOverride(customUserAgent)
                .build()
            val response = client.newCall(req).executeCancellable()
            val code = response.code
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val contentType = response.header("Content-Type").orEmpty()
            response.close()
            if (code == 404 || code == 405 || (!authFatal && (code == 401 || code == 403))) {
                lastError = mapHttpError(code, bytes.decodeToString())
                break
            }
            if (code !in 200..299) {
                val errText = bytes.decodeToString()
                if (!triedDefaultMode && modeToSend == null &&
                    errText.contains("mode", ignoreCase = true) &&
                    errText.contains("required", ignoreCase = true)
                ) {
                    triedDefaultMode = true
                    modeToSend = "std"
                    continue
                }
                throw mapHttpError(code, errText, null)
            }
            videoModeSent = modeToSend
            if (looksLikeMp4(bytes)) {
                return LLMResponse("", "end_turn", null, listOf(videoAtt(bytes)))
            }
            val text = bytes.decodeToString()
            val json = try {
                org.json.JSONObject(text)
            } catch (_: Exception) {
                throw LLMError.ProviderError("Video create: not JSON (${contentType.take(40)})")
            }
            return resolveVideoJob(json, token, path)
            }
        }
        throw lastError ?: LLMError.ProviderError("No video endpoint on this provider")
    }

    private suspend fun resolveVideoJob(
        json: org.json.JSONObject,
        token: String,
        createPath: String,
    ): LLMResponse {
        extractVideoAttachment(json)?.let { return it }
        val err = json.optJSONObject("error")?.safeOptString("message", "")
            ?: json.safeOptString("message", "")
        val status0 = json.safeOptString("status", json.safeOptString("task_status", ""))
        if (status0.equals("failed", true) || status0.equals("error", true)) {
            throw LLMError.ProviderError(err.ifBlank { "Video generation failed" })
        }
        val id = json.safeOptString("id", json.safeOptString("task_id", json.safeOptString("taskId", "")))
        if (id.isEmpty()) {
            if (err.isNotBlank()) throw LLMError.ProviderError(err)
            throw LLMError.ProviderError("Video create returned no id and no file")
        }
        val pollPath = when {
            createPath.contains("video/generations") -> "/video/generations/$id"
            createPath.contains("videos/generations") -> "/videos/generations/$id"
            else -> "/videos/$id"
        }
        var lastJson = json
        repeat(120) { attempt ->
            kotlinx.coroutines.delay(if (attempt == 0) videoFirstPollMillis else videoPollMillis)
            val pollReq = Request.Builder()
                .url("$basePath$pollPath")
                .get()
                .applyKeyAuth(token)
                .apply {
                    for ((k, v) in extraHeaders) header(k, v)
                }
                .applyUserAgentOverride(customUserAgent)
                .build()
            val resp = client.newCall(pollReq).executeCancellable()
            val code = resp.code
            val bodyBytes = resp.body?.bytes() ?: ByteArray(0)
            resp.close()
            if (code !in 200..299) throw mapHttpError(code, bodyBytes.decodeToString())
            if (looksLikeMp4(bodyBytes)) {
                return LLMResponse("", "end_turn", null, listOf(videoAtt(bodyBytes)))
            }
            lastJson = try {
                org.json.JSONObject(bodyBytes.decodeToString())
            } catch (_: Exception) {
                throw LLMError.ProviderError("Video poll: not JSON")
            }
            extractVideoAttachment(lastJson)?.let { return it }
            val st = lastJson.safeOptString("status", lastJson.safeOptString("task_status", ""))
            if (st.equals("failed", true) || st.equals("error", true)) {
                val msg = lastJson.optJSONObject("error")?.safeOptString("message", "")
                    ?: lastJson.safeOptString("message", "Video generation failed")
                throw LLMError.ProviderError(msg)
            }
            if (st.equals("completed", true) || st.equals("success", true) || st.equals("succeeded", true)) {
                downloadVideoContent(token, id)?.let { return it }
                throw LLMError.ProviderError("Video completed but no file/url")
            }
        }
        throw LLMError.ProviderError("Video generation timed out")
    }

    private suspend fun extractVideoAttachment(json: org.json.JSONObject): LLMResponse? {
        val data = json.optJSONArray("data")
        if (data != null) {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                attachmentFromItem(item)?.let { return LLMResponse(item.safeOptString("revised_prompt", ""), "end_turn", null, listOf(it)) }
            }
        }
        attachmentFromItem(json)?.let { return LLMResponse("", "end_turn", null, listOf(it)) }
        json.optJSONObject("output")?.let { out ->
            attachmentFromItem(out)?.let { return LLMResponse("", "end_turn", null, listOf(it)) }
        }
        json.optJSONObject("result")?.let { out ->
            attachmentFromItem(out)?.let { return LLMResponse("", "end_turn", null, listOf(it)) }
        }
        return null
    }

    private suspend fun attachmentFromItem(item: org.json.JSONObject): LLMMediaAttachment? {
        val b64 = item.safeOptString("b64_json", item.safeOptString("video_b64", ""))
        if (b64.isNotEmpty()) {
            val raw = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
            if (raw != null && raw.isNotEmpty()) return videoAtt(raw)
        }
        val url = item.safeOptString(
            "url",
            item.safeOptString("video_url", item.safeOptString("output", "")),
        )
        if (url.startsWith("http")) {
            return downloadUrl(url)
        }
        return null
    }

    private suspend fun downloadUrl(url: String): LLMMediaAttachment? {
        return try {
            val resp = client.newCall(Request.Builder().url(url).get().build()).executeCancellable()
            val mime = resp.header("Content-Type")
            val raw = resp.body?.bytes()
            resp.close()
            if (raw != null && raw.isNotEmpty()) videoAtt(raw, mime) else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun downloadVideoContent(token: String, id: String): LLMResponse? {
        val url = "$basePath/videos/$id/content"
        val req = Request.Builder()
            .url(url)
            .get()
            .applyKeyAuth(token)
            .apply {
                for ((k, v) in extraHeaders) header(k, v)
            }
            .applyUserAgentOverride(customUserAgent)
            .build()
        val resp = client.newCall(req).executeCancellable()
        val code = resp.code
        val raw = resp.body?.bytes() ?: ByteArray(0)
        resp.close()
        if (code !in 200..299) return null
        if (raw.isEmpty()) return null
        if (looksLikeMp4(raw) || raw.size > 256) {
            return LLMResponse("", "end_turn", null, listOf(videoAtt(raw)))
        }
        return null
    }

    private fun videoAtt(bytes: ByteArray, mimeHint: String? = null): LLMMediaAttachment {
        val mime = when {
            mimeHint != null && mimeHint.startsWith("video/") -> mimeHint.substringBefore(';')
            else -> "video/mp4"
        }
        return LLMMediaAttachment(LLMMediaAttachment.MediaType.VIDEO, mime, bytes)
    }

    private fun looksLikeMp4(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        // ....ftyp
        return bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
    }

    private suspend fun generateArkVideo(prompt: String, token: String): LLMResponse {
        val url = requireHostPath("/api/v3/contents/generations/tasks", "Ark video")
        val body = JSONObject().put("model", model.id)
        if (VendorMedia.looksLikeSeedance(model.id)) {
            body.put(
                "content",
                JSONArray().put(JSONObject().put("type", "text").put("text", prompt)),
            )
        } else {
            body.put("prompt", prompt)
            body.put("duration", 5)
            body.put("resolution", "720p")
        }
        val created = postMediaJson(url, token, body)
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        return pollVendorVideo(
            JSONObject(created.second),
            token,
            pollUrl = { id -> requireHostPath("/api/v3/contents/generations/tasks/$id", "Ark video poll") },
            minPollMillis = 8_000L,
            label = "Ark video",
        )
    }

    private suspend fun generateZhipuVideo(prompt: String, token: String): LLMResponse {
        val url = requireHostPath("/api/paas/v4/videos/generations", "Zhipu video")
        val body = JSONObject().put("model", model.id).put("prompt", prompt)
        val created = postMediaJson(url, token, body)
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        return pollVendorVideo(
            JSONObject(created.second),
            token,
            pollUrl = { id -> requireHostPath("/api/paas/v4/async-result/$id", "Zhipu video poll") },
            minPollMillis = 0L,
            label = "Zhipu video",
        )
    }

    private suspend fun generateDashScopeVideo(prompt: String, token: String): LLMResponse {
        val url = requireHostPath(
            "/api/v1/services/aigc/video-generation/video-synthesis",
            "DashScope video",
        )
        val body = JSONObject()
            .put("model", model.id)
            .put("input", JSONObject().put("prompt", prompt))
            .put("parameters", JSONObject())
        val created = postMediaJson(url, token, body, mapOf("X-DashScope-Async" to "enable"))
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        return pollVendorVideo(
            JSONObject(created.second),
            token,
            pollUrl = { id -> requireHostPath("/api/v1/tasks/$id", "DashScope video poll") },
            minPollMillis = 0L,
            label = "DashScope video",
        )
    }

    private suspend fun generateMinimaxVideo(prompt: String, token: String): LLMResponse {
        val url = requireHostPath("/v1/video_generation", "MiniMax video")
        val body = JSONObject().put("model", model.id).put("prompt", prompt)
        val created = postMediaJson(url, token, body)
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        val createdJson = JSONObject(created.second)
        val taskId = VendorMedia.taskId(createdJson)
        if (taskId.isEmpty()) throw LLMError.ProviderError("MiniMax video create returned no task_id")
        repeat(120) { attempt ->
            kotlinx.coroutines.delay(vendorPollDelay(attempt, 0L))
            val pollUrl = requireHostPath(
                "/v1/query/video_generation?task_id=$taskId",
                "MiniMax video poll",
            )
            val poll = getMedia(pollUrl, token)
            if (poll.first !in 200..299) throw mapHttpError(poll.first, poll.second)
            val json = JSONObject(poll.second)
            val st = VendorMedia.taskStatus(json)
            if (VendorMedia.isFailedStatus(st)) {
                throw LLMError.ProviderError(VendorMedia.errorMessage(json).ifBlank { "MiniMax video failed" })
            }
            VendorMedia.extractHttpVideoUrl(json)?.let { return videoFromUrl(it) }
            val fileId = VendorMedia.minimaxFileId(json)
            if (fileId.isNotEmpty() && (VendorMedia.isSuccessStatus(st) || st.isEmpty())) {
                val fileUrl = requireHostPath("/v1/files/retrieve?file_id=$fileId", "MiniMax file")
                val fileResp = getMedia(fileUrl, token)
                if (fileResp.first !in 200..299) throw mapHttpError(fileResp.first, fileResp.second)
                val fileJson = JSONObject(fileResp.second)
                VendorMedia.extractHttpVideoUrl(fileJson)?.let { return videoFromUrl(it) }
                throw LLMError.ProviderError("MiniMax video completed but no download_url")
            }
        }
        throw LLMError.ProviderError("MiniMax video generation timed out")
    }

    private suspend fun generateDashScopeImage(
        prompt: String,
        n: Int,
        size: String?,
        token: String,
    ): LLMResponse {
        val url = requireHostPath(
            "/api/v1/services/aigc/text2image/image-synthesis",
            "DashScope image",
        )
        val parameters = JSONObject().put("n", n)
        val sizeValue = size?.replace('x', '*')?.replace('X', '*') ?: "1024*1024"
        parameters.put("size", sizeValue)
        val body = JSONObject()
            .put("model", model.id)
            .put("input", JSONObject().put("prompt", prompt))
            .put("parameters", parameters)
        val created = postMediaJson(url, token, body, mapOf("X-DashScope-Async" to "enable"))
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        var json = JSONObject(created.second)
        val immediate = imageResponseFromVendorJson(json)
        if (immediate.mediaAttachments.isNotEmpty()) return immediate
        val id = VendorMedia.taskId(json)
        if (id.isEmpty()) {
            val err = VendorMedia.errorMessage(json)
            throw LLMError.ProviderError(err.ifBlank { "DashScope image create returned no task_id" })
        }
        repeat(120) { attempt ->
            kotlinx.coroutines.delay(vendorPollDelay(attempt, 0L))
            val poll = getMedia(requireHostPath("/api/v1/tasks/$id", "DashScope image poll"), token)
            if (poll.first !in 200..299) throw mapHttpError(poll.first, poll.second)
            json = JSONObject(poll.second)
            val st = VendorMedia.taskStatus(json)
            if (VendorMedia.isFailedStatus(st)) {
                throw LLMError.ProviderError(VendorMedia.errorMessage(json).ifBlank { "DashScope image failed" })
            }
            val parsed = imageResponseFromVendorJson(json)
            if (parsed.mediaAttachments.isNotEmpty()) return parsed
            if (VendorMedia.isSuccessStatus(st)) {
                throw LLMError.ProviderError("DashScope image completed but no url")
            }
        }
        throw LLMError.ProviderError("DashScope image generation timed out")
    }

    private suspend fun generateMinimaxImage(prompt: String, n: Int, token: String): LLMResponse {
        val url = requireHostPath("/v1/image_generation", "MiniMax image")
        val body = JSONObject()
            .put("model", model.id)
            .put("prompt", prompt)
            .put("n", n)
            .put("response_format", "base64")
        val created = postMediaJson(url, token, body)
        if (created.first !in 200..299) throw mapHttpError(created.first, created.second)
        return imageResponseFromVendorJson(JSONObject(created.second))
    }

    private suspend fun pollVendorVideo(
        created: JSONObject,
        token: String,
        pollUrl: (String) -> String,
        minPollMillis: Long,
        label: String,
    ): LLMResponse {
        val err0 = VendorMedia.errorMessage(created)
        val st0 = VendorMedia.taskStatus(created)
        if (VendorMedia.isFailedStatus(st0)) {
            throw LLMError.ProviderError(err0.ifBlank { "$label failed" })
        }
        VendorMedia.extractHttpVideoUrl(created)?.let { return videoFromUrl(it) }
        val id = VendorMedia.taskId(created)
        if (id.isEmpty()) {
            throw LLMError.ProviderError(err0.ifBlank { "$label create returned no task id and no file" })
        }
        repeat(120) { attempt ->
            kotlinx.coroutines.delay(vendorPollDelay(attempt, minPollMillis))
            val poll = getMedia(pollUrl(id), token)
            if (poll.first !in 200..299) throw mapHttpError(poll.first, poll.second)
            val json = try {
                JSONObject(poll.second)
            } catch (_: Exception) {
                throw LLMError.ProviderError("$label poll: not JSON")
            }
            val st = VendorMedia.taskStatus(json)
            if (VendorMedia.isFailedStatus(st)) {
                throw LLMError.ProviderError(VendorMedia.errorMessage(json).ifBlank { "$label failed" })
            }
            VendorMedia.extractHttpVideoUrl(json)?.let { return videoFromUrl(it) }
            if (VendorMedia.isSuccessStatus(st)) {
                throw LLMError.ProviderError("$label completed but no video_url")
            }
        }
        throw LLMError.ProviderError("$label generation timed out")
    }

    private fun imageResponseFromVendorJson(json: JSONObject): LLMResponse {
        val b64s = VendorMedia.minimaxImageBase64(json)
        if (b64s.isNotEmpty()) {
            val attachments = b64s.mapNotNull { b64 ->
                val bytes = try {
                    Base64.decode(b64, Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
                if (bytes == null || bytes.isEmpty()) null
                else LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, detectImageMime(bytes), bytes)
            }
            if (attachments.isNotEmpty()) return LLMResponse("", "end_turn", null, attachments)
        }
        val urls = VendorMedia.extractHttpImageUrls(json)
        if (urls.isEmpty()) return LLMResponse("", "end_turn", null, emptyList())
        val attachments = urls.mapNotNull { url ->
            try {
                val resp = client.newCall(Request.Builder().url(url).get().build()).execute()
                val mime = resp.header("Content-Type")
                val raw = resp.body?.bytes()
                resp.close()
                if (raw == null || raw.isEmpty()) null
                else LLMMediaAttachment(
                    LLMMediaAttachment.MediaType.IMAGE,
                    mime?.substringBefore(';') ?: detectImageMime(raw),
                    raw,
                )
            } catch (_: Exception) {
                null
            }
        }
        return LLMResponse("", "end_turn", null, attachments)
    }

    private suspend fun videoFromUrl(url: String): LLMResponse {
        val att = downloadUrl(url)
            ?: throw LLMError.ProviderError("Failed to download video from temporary URL")
        return LLMResponse("", "end_turn", null, listOf(att))
    }

    private fun vendorPollDelay(attempt: Int, minMillis: Long): Long {
        val configured = if (attempt == 0) videoFirstPollMillis else videoPollMillis
        // Tests set poll to 1ms; never inflate those. Production Ark asks ≥8s.
        if (configured < 100L) return configured
        return maxOf(configured, minMillis)
    }

    private fun requireHostPath(path: String, label: String): String =
        hostRootURL(path) ?: throw LLMError.ProviderError("Cannot resolve $label endpoint from $basePath")

    private suspend fun postMediaJson(
        url: String,
        token: String,
        body: JSONObject,
        extra: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .applyKeyAuth(token)
            .header("Content-Type", "application/json")
            .apply {
                for ((k, v) in extraHeaders) header(k, v)
                for ((k, v) in extra) header(k, v)
            }
            .applyUserAgentOverride(customUserAgent)
            .build()
        val resp = client.newCall(req).executeCancellable()
        val code = resp.code
        val text = resp.body?.string() ?: ""
        resp.close()
        return code to text
    }

    private suspend fun getMedia(url: String, token: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .get()
            .applyKeyAuth(token)
            .apply {
                for ((k, v) in extraHeaders) header(k, v)
            }
            .applyUserAgentOverride(customUserAgent)
            .build()
        val resp = client.newCall(req).executeCancellable()
        val code = resp.code
        val text = resp.body?.string() ?: ""
        resp.close()
        return code to text
    }

    private val requestBodies by lazy { OpenAIRequestBodies(OpenAIRequestHost()) }

    private inner class OpenAIRequestHost : OpenAIRequestBodies.Host {
        override val model get() = this@OpenAIProvider.model
        override val basePath get() = this@OpenAIProvider.basePath
        override val extraHeaders get() = this@OpenAIProvider.extraHeaders
        override val codexAccountId get() = this@OpenAIProvider.codexAccountId
        override val useResponsesAPI get() = this@OpenAIProvider.useResponsesAPI
        override val forceChatCompletions get() = this@OpenAIProvider.forceChatCompletions
        override val customUserAgent get() = this@OpenAIProvider.customUserAgent
        override val isAzure get() = this@OpenAIProvider.isAzure
        override val isOAuth get() = this@OpenAIProvider.isOAuth
        override val chatExtraBody get() = this@OpenAIProvider.chatExtraBody
        override val chatExtraHeaders get() = this@OpenAIProvider.chatExtraHeaders
        override val absoluteEndpointOverride get() = this@OpenAIProvider.absoluteEndpointOverride
        override val isOpenRouter get() = this@OpenAIProvider.isOpenRouter
        override val needsOpenRouterAnthropicCacheControl get() =
            this@OpenAIProvider.needsOpenRouterAnthropicCacheControl
        override val isMistral get() = this@OpenAIProvider.isMistral
        override val isDashScope get() = this@OpenAIProvider.isDashScope
        override val isXAI get() = this@OpenAIProvider.isXAI
        override val usesUnifiedReasoningEffort get() = this@OpenAIProvider.usesUnifiedReasoningEffort
        override val thinkingRuleInstanceId get() = this@OpenAIProvider.thinkingRuleInstanceId
        override fun resolvedServiceTier() = this@OpenAIProvider.resolvedServiceTier()
        override fun endpointURL(defaultPath: String) = this@OpenAIProvider.endpointURL(defaultPath)
        override fun azureUrl(path: String) = this@OpenAIProvider.azureUrl(path)
        override suspend fun getToken() = this@OpenAIProvider.getToken()
        override fun explicitOffEffort() = this@OpenAIProvider.explicitOffEffort()
        override fun applyKeyAuth(builder: Request.Builder, token: String) = builder.applyKeyAuth(token)
        override val codexClientVersion get() = CODEX_CLIENT_VERSION
        override fun clampThinkingLevel(level: ThinkingLevel) = this@OpenAIProvider.clampThinkingLevel(level)
        override val provider get() = this@OpenAIProvider
    }

    internal fun buildRequestBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        temperature: Double?,
        imageParts: List<LLMMessage.ImagePart>,
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    ): JSONObject = requestBodies.buildRequestBody(
        messages, systemPrompt, maxTokens, stream, temperature, imageParts, tools, thinkingLevel,
    )

    private suspend fun buildRequest(bodyStr: String): Request =
        requestBodies.buildRequest(bodyStr)

    internal fun buildResponsesAPIBody(
        messages: List<LLMMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        stream: Boolean,
        imageParts: List<LLMMessage.ImagePart> = emptyList(),
        tools: List<AgentToolDefinition> = emptyList(),
        thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
        temperature: Double? = null,
    ): JSONObject = requestBodies.buildResponsesAPIBody(
        messages, systemPrompt, maxTokens, stream, imageParts, tools, thinkingLevel, temperature,
    )



    private fun parseChatCompletionsUsage(usage: JSONObject): LLMUsage {
        val promptTokens = usage.optInt("prompt_tokens", 0)
        // DeepSeek reports cache hits at `usage.prompt_cache_hit_tokens` instead
        // of the OpenAI-native `prompt_tokens_details.cached_tokens`. Mirrors
        // iOS OpenAIProvider.swift:629-630. Without this fallback, DeepSeek V4
        // looked like it never cached even when it did, masking T122's win.
        val cacheRead = usage.optJSONObject("prompt_tokens_details")
            ?.optInt("cached_tokens")?.takeIf { it > 0 }
            ?: usage.optInt("prompt_cache_hit_tokens", 0).takeIf { it > 0 }
        // OpenAI/DeepSeek `prompt_tokens` is the FULL input (cached + fresh), so
        // subtract the cached portion to keep `inputTokens` meaning fresh-only —
        // matching the Anthropic convention. Otherwise the cached tokens are
        // counted twice in `input + cacheRead` (deflates the cache-hit rate;
        // DeepSeek 99% hit showed as ~48%). Guard: only subtract when it stays
        // non-negative; no cache field (cacheRead == null) → unchanged.
        // latestContextTokens stays the full prompt (that IS the context size).
        val freshInput = cacheRead?.let { (promptTokens - it).takeIf { d -> d >= 0 } } ?: promptTokens
        return LLMUsage(
            inputTokens = promptTokens,
            outputTokens = usage.optInt("completion_tokens", 0),
            cacheReadInputTokens = cacheRead,
            latestContextTokens = promptTokens,
        )
    }

    /**
     * Inject provider-specific thinking parameters into the request body.
     * - OpenRouter: `reasoning: {effort: ...}` (omitted when off so
     *   forced-reasoning models keep their default)
     * - OpenAI o-series / GPT-5.x: `reasoning_effort: ...` (off → skip)
     * - Qwen3 (DashScope): `enable_thinking: true/false, thinking_budget: N`
     *   — Qwen3 thinks by default, so OFF needs an explicit disable.
     * - DeepSeek V4 (deepseek-v4-flash / deepseek-v4-pro): `thinking` object —
     *   V4 thinks by default and rejects requests without an explicit toggle
     *   when reasoning_content is missing. Distinct from deepseek-reasoner /
     *   deepseek-chat which keep the no-params path below.
     * - DeepSeek (pre-V4) / GLM / Kimi / MiniMax: no params (model decides).
     */

    /**
     * [T-reasoning-effort-data-driven] Snap an effort string onto the tiers the
     * model actually declares. Mirrors iOS
     * `OpenAIAgentProvider.clampEffort(_:to:)` — keep both in sync.
     *
     * Necessary because the catalog's effort sets are far from uniform
     * (["low","medium","high"], ["high","max"], ["high","xhigh"], …). Sending an
     * undeclared tier is the same class of failure the MiMo/Agnes xhigh clamp
     * already guards against ("Invalid reasoning_effort: xhigh" 400s).
     *
     * Nearest-tier semantics: step down to the closest declared tier at or below
     * the request; only if none exists step up to the lowest declared one.
     * Downgrading is preferred because overshooting costs money and latency the
     * user did not ask for. Null/empty values pass the string through unchanged.
     */
    internal fun clampEffort(effort: String, values: List<String>?): String {
        if (values.isNullOrEmpty()) return effort
        if (values.contains(effort)) return effort
        val ladder = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
        val want = ladder.indexOf(effort)
        if (want < 0) return effort
        val declared = values.mapNotNull { v ->
            val i = ladder.indexOf(v)
            if (i >= 0) i to v else null
        }.sortedBy { it.first }
        if (declared.isEmpty()) return effort
        return declared.lastOrNull { it.first <= want }?.second ?: declared.first().second
    }

    // MARK: - Codex image generation (gpt-image-2)

    /**
     * [T-codex-gpt-image2-oauth-android] Build the Codex image_generation
     * request body. The wire model is gpt-5.5 (the Codex backend invokes the
     * underlying gpt-image-2 via the built-in image_generation tool); the user
     * turn is the fixed "Use the image generation tool to create: <prompt>"
     * instruction. The <prompt> is the latest user text — plain string content
     * or the concatenated text parts of the last user message.
     */
    private fun buildCodexImageBody(messages: List<LLMMessage>): JSONObject {
        val lastUser = messages.lastOrNull { it.role == LLMMessage.Role.USER }
        val prompt = lastUser?.let { m ->
            m.content.takeIf { it.isNotBlank() }
                ?: m.contentParts.filterIsInstance<AgentContentPart.Text>()
                    .joinToString(" ") { it.text }.trim()
        }.orEmpty()
        return JSONObject().apply {
            put("model", "gpt-5.5")
            put("instructions", "You are a helpful assistant. Use tools when available.")
            put("input", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", "Use the image generation tool to create: $prompt")
            }))
            put("store", false)
            put("tools", JSONArray().put(JSONObject().put("type", "image_generation")))
            put("reasoning", JSONObject().put("effort", "low"))
            put("include", JSONArray())
            put("tool_choice", "auto")
            put("parallel_tool_calls", true)
            put("stream", true)
        }
    }

    /**
     * [T-android-codex-image-stream-parse-fix] Detect an image's MIME type from
     * its magic bytes. Mirrors iOS `detectImageMime`. gpt-image-2 can return
     * PNG, JPEG, or WebP, so the previous hardcoded "image/png" mislabeled
     * non-PNG output. Falls back to image/png when too short / unrecognized.
     */
    internal fun detectImageMime(data: ByteArray): String {
        if (data.size < 4) return "image/png"
        val b = data.map { it.toInt() and 0xFF }
        return when {
            b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47 -> "image/png"
            b[0] == 0xFF && b[1] == 0xD8 -> "image/jpeg"
            b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 -> "image/webp" // RIFF (WebP)
            b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 -> "image/gif"
            else -> "image/png"
        }
    }


    /**
     * Combine Responses-API call_id + item_id into a single string the agent loop
     * can carry through tool_use/tool_result blocks. The next request splits it
     * back apart so the API sees the original ids verbatim.
     */
    private fun combineResponsesAPIIds(callId: String, fcId: String): String =
        if (fcId.isEmpty()) callId else "$callId|$fcId"


    /** Parse usage from Responses API format. */
    private fun parseResponsesAPIUsage(usage: JSONObject): LLMUsage {
        val inputTokens = usage.optInt("input_tokens", 0)
        // Responses API reports cache hits at `input_tokens_details.cached_tokens`
        // (distinct from Chat Completions' `prompt_tokens_details.cached_tokens`).
        // Mirrors iOS OpenAIProvider.swift:640. Without this, even a perfectly
        // cached Responses-API request showed cacheRead=0 in usage stats —
        // making T126's prompt_cache_key wiring look like it had no effect.
        val cacheRead = usage.optJSONObject("input_tokens_details")
            ?.optInt("cached_tokens")?.takeIf { it > 0 }
        // `input_tokens` is the FULL input (cached subset included); subtract the
        // cached portion so `inputTokens` is fresh-only, matching Anthropic — else
        // the cache is counted twice in `input + cacheRead` (deflates hit rate).
        // Guard: only subtract when non-negative; no cache field → unchanged.
        // latestContextTokens stays the full input (that IS the context size).
        val freshInput = cacheRead?.let { (inputTokens - it).takeIf { d -> d >= 0 } } ?: inputTokens
        return LLMUsage(
            inputTokens = freshInput,
            outputTokens = usage.optInt("output_tokens", 0),
            cacheReadInputTokens = cacheRead,
            latestContextTokens = inputTokens,
        )
    }

    private fun mapHttpError(statusCode: Int, body: String, retryAfterHeader: String? = null): LLMError {
        if (statusCode == 401 || statusCode == 403) return LLMError.InvalidApiKey()
        if (statusCode == 429) return HttpRetryAfter.map429(body, retryAfterHeader)

        val message = try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val errorMessage = error?.safeOptString("message", "") ?: body
            "[$statusCode] $errorMessage"
        } catch (_: Exception) {
            "HTTP $statusCode: ${body.take(500)}"
        }

        val transientCodes = setOf(500, 502, 503, 504, 529)
        if (statusCode in transientCodes) {
            // 503 with permanent failure indicators → ProviderError (trigger group fallback)
            if (statusCode == 503 && HttpRetryAfter.isPermanentCapacityBody(body)) {
                return LLMError.ProviderError(message)
            }
            return LLMError.TransientError(message)
        }
        return LLMError.ProviderError(message)
    }

    private fun mapError(error: Throwable): LLMError {
        if (error is LLMError) return error
        if (error is java.io.IOException) return LLMError.NetworkError(error)
        return LLMError.Unknown(error)
    }
}

/**
 * [T-android-ttfb-upload-split / #188] Per-call state the streaming TTFB
 * watchdog shares with [OkHttpNetTraceListener]. Attached to the streaming
 * request as an OkHttp request tag; the listener fills it in from its
 * event callbacks (which run on OkHttp's I/O thread during `execute()`),
 * and the watchdog coroutine reads it.
 *
 * Why: the watchdog must NOT count request-body UPLOAD time against the
 * 30s time-to-first-byte budget (a 1.3MB body over a slow proxy took 24-27s,
 * leaving almost nothing for the server, so a healthy server looked like a
 * dead connection — #188). The listener signals [uploadDoneAtNanos] on
 * `requestBodyEnd`; only then does the real TTFB clock start. [connection]
 * is captured so the watchdog can evict THIS ONE physical connection on
 * timeout (never the whole pool — a sibling session's healthy connection
 * must survive).
 */
internal class CallWatchState {
    /** Set by requestBodyEnd — the moment upload finished (monotonic nanos). */
    val uploadDoneAtNanos = java.util.concurrent.atomic.AtomicLong(0L)
    /** Physical connection serving this call, captured at connectionAcquired. */
    val connection = java.util.concurrent.atomic.AtomicReference<okhttp3.Connection?>(null)
}

