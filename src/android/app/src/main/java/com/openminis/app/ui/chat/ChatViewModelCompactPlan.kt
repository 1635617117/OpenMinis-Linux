package com.openminis.app.ui.chat

import com.openminis.app.provider.ProviderFactory
import com.openminis.app.ui.chat.ChatViewModel.CompactAttempt

internal fun ChatViewModel.compactStagePlan(): List<CompactAttempt> {
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
