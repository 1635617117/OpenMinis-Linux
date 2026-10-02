package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.HttpRetryAfter
import com.openminis.app.provider.safeOptString
import org.json.JSONObject

internal fun OpenAIProvider.mapHttpError(statusCode: Int, body: String, retryAfterHeader: String? = null): LLMError {
    if (statusCode == 401) return LLMError.InvalidApiKey()
    if (statusCode == 403) {
        // [T-llm-error-403] OpenRouter / self-hosted gateways answer 403 for
        // "valid key, but this model or region is not allowed on your plan".
        // Folding that into a bare InvalidApiKey() told users to check an API
        // key that is actually fine. The detail drives both the message and
        // the actionableHint's dedicated 403 branch.
        return LLMError.InvalidApiKey(
            "HTTP 403 forbidden — the credential was accepted but this model or region is not permitted on the current plan"
        )
    }
    if (statusCode == 429) return HttpRetryAfter.map429(body, retryAfterHeader)

    val message = try {
        val json = JSONObject(body)
        val error = json.optJSONObject("error")
        val errorMessage = error?.safeOptString("message", "") ?: body
        "[$statusCode] ${maskSecrets(errorMessage)}"
    } catch (_: Exception) {
        "HTTP $statusCode: ${maskSecrets(body.take(500))}"
    }

    // [T-llm-error-413] Payload Too Large is the request-size twin of
    // context_length_exceeded — route it to the actionable class (compact /
    // offload hint) instead of a generic, non-retryable ProviderError.
    if (statusCode == 413) return LLMError.ContextLengthExceeded(message)

    // [T-llm-error-classification] 细分 OpenAI 400 类错误，不再笼统地归 ProviderError。
    val lower = message.lowercase()
    when {
        // "max_tokens too large" 是输出参数超限（max_tokens 请求过大），
        // 不是上下文超长，保持 ProviderError。
        !lower.contains("max_tokens too large") && !lower.contains("max_output_tokens") && (
            lower.contains("context_length_exceeded") || lower.contains("maximum context length") ||
            (statusCode == 400 && (
                lower.contains("too many tokens") ||
                lower.contains("reduce the length") ||
                // [T-llm-error-413-note] Excludes output-parameter errors
                // (max_output_tokens mismatches) — a request-param problem is
                // not the context-window problem this class's hint assumes.
                (lower.contains("token") && (lower.contains("max") || lower.contains("limit") || lower.contains("exceed")))
            ))
        ) -> return LLMError.ContextLengthExceeded(message)
        lower.contains("content_filter") || lower.contains("content_policy") ||
        lower.contains("safety") || statusCode == 422 && lower.contains("content") ->
            return LLMError.ContentFiltered(message)
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

internal fun OpenAIProvider.mapError(error: Throwable): LLMError = mapThrowableToLLMError(error)

/** Receiver-free so the unit tests can exercise the mapping directly. */
internal fun mapThrowableToLLMError(error: Throwable): LLMError {
    if (error is LLMError) return error
    // [T-llm-error-tls] TLS / certificate failures are permanent
    // misconfigurations (clock skew, MITM proxy, untrusted CA). They are
    // IOExceptions, so the old mapping filed them under retryable
    // NetworkError and burned the full retry budget on a failure no retry
    // can fix — and then the actionable hint told the user to "check your
    // internet connection".
    if (error is javax.net.ssl.SSLException) {
        return LLMError.ProviderError("TLS error: ${error.message ?: error.javaClass.simpleName}")
    }
    if (error is java.io.IOException) return LLMError.NetworkError(error)
    return LLMError.Unknown(error)
}

/**
 * [T-llm-error-secret-mask] Upstream error bodies occasionally echo parts of
 * the request — and with them key-shaped material. Nothing in
 * [LLMError.message] should ever be able to carry a credential into the chat
 * UI (message is truncated to 160 chars for display, but truncation is not
 * redaction).
 *
 * [T-secret-mask-single-source] The patterns moved to
 * [com.openminis.app.util.SecretMasking] so the context-assembly snapshot can
 * use the same rule. This stays as the local name the call sites and
 * LLMErrorClassificationTest already use.
 */
internal fun maskSecrets(text: String): String =
    com.openminis.app.util.SecretMasking.mask(text)
