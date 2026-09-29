package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ModelAttributionSnapshot
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.effectiveMaxThinkingLevel
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.DiscussionGraph
import com.openminis.app.tools.GroupChat
import com.openminis.app.tools.SubAgentKind
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal suspend fun ChatViewModel.runGroupChat(provider: LLMProvider, closing: Boolean) {
    if (closing) groupChatCloseRequested = true
    val analyzing = context.getString(com.openminis.app.R.string.group_chat_analyzing)
    val userText = _messages.value.lastOrNull { it.role == "user" && !it.isQueued }?.content.orEmpty()
    val prior = groupTranscript()
    if (closing && prior.isEmpty()) {
        publishGroupNotice(context.getString(com.openminis.app.R.string.group_chat_nothing_to_close))
        groupChatCloseRequested = false
        return
    }
    if (!closing && userText.isBlank()) return

    val config = providerRepository.config.value
    val mainEntry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
    val hostName = (mainEntry?.model?.displayName ?: currentModel?.displayName ?: "Host") +
        " · " + context.getString(com.openminis.app.R.string.group_chat_host_suffix)
    val hostSnapshot = snapshotFor(mainEntry)
    val members = groupMembers(config.modelEntries, mainEntry?.id)
    if (!closing && members.isEmpty()) {
        publishGroupNotice(context.getString(com.openminis.app.R.string.group_chat_need_models))
        return
    }

    val spoken = prior.toMutableList()
    if (!closing && !groupChatCloseRequested) {
        val brief = speakVisible(
            speaker = hostName,
            snapshot = hostSnapshot,
            provider = provider,
            system = GroupChat.HOST_SYSTEM,
            user = if (prior.isEmpty()) {
                GroupChat.framePrompt(userText, recentContext())
            } else {
                GroupChat.bridgePrompt(userText, GroupChat.transcript(prior))
            },
            tools = emptyList(),
            placeholder = context.getString(com.openminis.app.R.string.group_chat_framing),
        )
        if (brief.isNotBlank()) spoken += GroupChat.Line(hostName, brief)
    }
    if (!closing && !groupChatCloseRequested && members.isNotEmpty()) {
        val brief = spoken.lastOrNull { it.speaker == hostName }?.text.orEmpty()
        val firstRound = speakMembers(members, analyzing) { member ->
            GroupChat.opinionPrompt(member.name, userText, brief, GroupChat.transcript(spoken))
        }
        spoken += firstRound
        if (!groupChatCloseRequested && firstRound.size >= 2) {
            val replies = speakMembers(members, analyzing) { member ->
                GroupChat.replyPrompt(member.name, userText, GroupChat.transcript(spoken))
            }
            spoken += replies
        }
    }

    val summary = speakVisible(
        speaker = hostName,
        snapshot = hostSnapshot,
        provider = provider,
        system = GroupChat.HOST_SYSTEM,
        user = GroupChat.summaryPrompt(userText, GroupChat.transcript(spoken), groupChatCloseRequested || closing),
        tools = emptyList(),
        placeholder = context.getString(com.openminis.app.R.string.group_chat_summarizing),
    )
    if ((closing || groupChatCloseRequested) && summary.isNotBlank()) {
        groupChatClosedAfterId = _messages.value.lastOrNull { it.speakerName == hostName }?.id
        groupChatPrefs().edit().putString(closedKey(), groupChatClosedAfterId).apply()
    }
    groupChatCloseRequested = false
}

internal fun ChatViewModel.endGroupChat() {
    groupChatCloseRequested = true
    if (isStreaming.value) return
    val provider = currentProvider ?: return
    _isStreaming.value = true
    streamJob = viewModelScope.launchActiveRun(
        activeSessionId,
        Dispatchers.IO,
        ownerSessionIds = setOf(activeSessionId, sessionId, realSessionId),
        beforeStart = { streamJob = it },
    ) {
        try {
            com.openminis.app.service.SessionConcurrencyManager.acquireSlot(activeSessionId)
            runGroupChat(provider, closing = true)
        } catch (_: CancellationException) {
        } finally {
            com.openminis.app.service.SessionConcurrencyManager.releaseSlot(activeSessionId)
            if (streamJob === coroutineContext[kotlinx.coroutines.Job]) {
                _isStreaming.value = false
            }
        }
    }
}

private data class GroupMember(
    val name: String,
    val provider: LLMProvider,
    val snapshot: ModelAttributionSnapshot?,
    val maxTokens: Int,
    val temperature: Double?,
    val thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
)

private fun ChatViewModel.groupMembers(
    entries: List<ModelEntry>,
    hostEntryId: String?,
): List<GroupMember> {
    val ids = MultiAgentSettings.retainLive(
        multiAgentSettings.selectedModelEntryIds.value,
        entries.map { it.id }.toSet(),
        multiAgentSettings.maxConcurrent.value,
    )
    return ids.mapNotNull { id ->
        if (id == hostEntryId) return@mapNotNull null
        val entry = entries.find { it.id == id } ?: return@mapNotNull null
        val provider = providerForModelEntry(entry) ?: return@mapNotNull null
        GroupMember(
            name = entry.model.displayName,
            provider = provider,
            snapshot = snapshotFor(entry),
            maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 2048),
            temperature = entry.overrides.temperature,
            thinkingLevel = entry.effectiveMaxThinkingLevel,
        )
    }
}

private suspend fun ChatViewModel.speakMembers(
    members: List<GroupMember>,
    analyzing: String,
    promptFor: (GroupMember) -> String,
): List<GroupChat.Line> = supervisorScope {
    val gate = Mutex()
    members.map { member ->
        async {
            if (groupChatCloseRequested) return@async null
            val tools = AgentTools.makeAgentTools(
                supportsImageInput = member.provider.model.hasImageInput,
                visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                    providerRepository, context,
                ),
                memoryEnabled = false,
                subAgentEnabled = false,
            ).filter { !SubAgentKind.blocks(SubAgentKind.PLAN, it.name) }
            val text = speakVisible(
                speaker = member.name,
                snapshot = member.snapshot,
                provider = member.provider,
                system = GroupChat.memberSystem(member.name),
                user = promptFor(member),
                tools = tools,
                placeholder = analyzing,
                toolGate = gate,
                allowPass = true,
                thinkingLevel = member.thinkingLevel,
            )
            text.takeIf { it.isNotBlank() }?.let { GroupChat.Line(member.name, it) }
        }
    }.awaitAll().filterNotNull()
}

private suspend fun ChatViewModel.speakVisible(
    speaker: String,
    snapshot: ModelAttributionSnapshot?,
    provider: LLMProvider,
    system: String,
    user: String,
    tools: List<com.openminis.app.data.model.AgentToolDefinition>,
    placeholder: String,
    toolGate: Mutex? = null,
    allowPass: Boolean = false,
    thinkingLevel: com.openminis.app.data.model.ThinkingLevel =
        com.openminis.app.data.model.ThinkingLevel.OFF,
): String {
    if (groupChatCloseRequested && allowPass) return ""
    val id = UUID.randomUUID().toString()
    upsertGroupBubble(id, speaker, placeholder, analyzing = true)
    val text = try {
        speakModel(provider, system, user, tools, toolGate, thinkingLevel) {
            upsertGroupBubble(id, speaker, placeholder, analyzing = true)
        }
    } catch (e: CancellationException) {
        removeGroupBubble(id)
        throw e
    }
    if (text.isBlank() || (allowPass && GroupChat.isPass(text))) {
        removeGroupBubble(id)
        return ""
    }
    val dbId = persistGroupUtterance(speaker, text, snapshot)
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value.map { message ->
            if (message.id == id) message.copy(
                content = text,
                speakerName = speaker,
                isStreaming = false,
                isAwaitingModelResponse = false,
                sourceDbIds = listOfNotNull(dbId),
            ) else message
        }
    }
    return text
}

private suspend fun ChatViewModel.speakModel(
    provider: LLMProvider,
    system: String,
    user: String,
    tools: List<com.openminis.app.data.model.AgentToolDefinition>,
    toolGate: Mutex?,
    thinkingLevel: com.openminis.app.data.model.ThinkingLevel,
    onTool: suspend () -> Unit,
): String {
    val history = mutableListOf(LLMMessage(role = LLMMessage.Role.USER, content = user))
    val report = StringBuilder()
    repeat(3) {
        if (groupChatCloseRequested) return report.toString().trim()
        val textSb = StringBuilder()
        val calls = mutableListOf<Triple<String, String, JSONObject>>()
        provider.streamMessage(
            messages = history,
            systemPrompt = system,
            maxTokens = 1024,
            temperature = null,
            tools = tools,
            thinkingLevel = thinkingLevel,
        ).collect { chunk ->
            when (chunk) {
                is LLMStreamChunk.Text -> textSb.append(chunk.text)
                is LLMStreamChunk.ToolCallComplete -> calls += Triple(chunk.id, chunk.name, chunk.args)
                else -> Unit
            }
        }
        val text = textSb.toString().trim()
        if (calls.isEmpty() || tools.isEmpty()) {
            if (text.isNotEmpty()) {
                if (report.isNotEmpty()) report.append("\n\n")
                report.append(text)
            }
            return report.toString().trim()
        }
        onTool()
        val assistantParts = mutableListOf<AgentContentPart>()
        if (text.isNotEmpty()) assistantParts += AgentContentPart.Text(text)
        calls.forEach { (callId, name, args) ->
            assistantParts += AgentContentPart.ToolUse(callId, name, input = args)
        }
        history += LLMMessage(role = LLMMessage.Role.ASSISTANT, content = text, contentParts = assistantParts)
        val results = mutableListOf<AgentContentPart>()
        for ((callId, name, args) in calls) {
            val denied = DiscussionGraph.denyExecution(name, args.toString())
            val result = if (denied != null) {
                ToolExecutionResult(denied, false)
            } else if (toolGate != null) {
                toolGate.withLock { executeTool(name, args.toString(), "", mutableListOf(), "", "") }
            } else {
                executeTool(name, args.toString(), "", mutableListOf(), "", "")
            }
            results += AgentContentPart.ToolResult(callId, name, result.output, isError = !result.success)
        }
        history += LLMMessage(role = LLMMessage.Role.USER, content = "", contentParts = results)
    }
    return report.toString().trim()
}

private suspend fun ChatViewModel.upsertGroupBubble(
    id: String,
    speaker: String,
    text: String,
    analyzing: Boolean,
) {
    withContext(Dispatchers.Main) {
        val current = _messages.value
        val next = if (current.any { it.id == id }) {
            current.map {
                if (it.id == id) it.copy(
                    content = text,
                    speakerName = speaker,
                    isStreaming = true,
                    isAwaitingModelResponse = analyzing,
                ) else it
            }
        } else {
            current + ChatMessage(
                id = id,
                role = "assistant",
                content = text,
                speakerName = speaker,
                isStreaming = true,
                isAwaitingModelResponse = analyzing,
            )
        }
        _messages.value = trimLoadedWindow(next)
    }
}

private suspend fun ChatViewModel.removeGroupBubble(id: String) {
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value.filterNot { it.id == id }
    }
}

private suspend fun ChatViewModel.publishGroupNotice(text: String) {
    val id = UUID.randomUUID().toString()
    val speaker = context.getString(com.openminis.app.R.string.group_chat_host_suffix)
    upsertGroupBubble(id, speaker, text, analyzing = false)
    val dbId = persistGroupUtterance(speaker, text, snapshotFor(null))
    withContext(Dispatchers.Main) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(isStreaming = false, sourceDbIds = listOfNotNull(dbId)) else it
        }
    }
}

private suspend fun ChatViewModel.persistGroupUtterance(
    speaker: String,
    text: String,
    snapshot: ModelAttributionSnapshot?,
): String? = withContext(Dispatchers.IO) {
    val parts = JSONArray()
        .put(JSONObject().put("type", GroupChat.SPEAKER_PART).put("value", speaker))
        .put(JSONObject().put("type", "text").put("value", text))
        .toString()
    chatRepository.appendMessage(
        realSessionId.ifEmpty { sessionId },
        "assistant",
        parts,
        modelSnapshot = snapshot,
    ).id
}

private fun ChatViewModel.snapshotFor(entry: ModelEntry?): ModelAttributionSnapshot? {
    val model = entry?.model ?: currentModel ?: return null
    val instance = entry?.let { providerRepository.instance(it.providerInstanceId) }
        ?: _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        }?.let { providerRepository.instance(it.providerInstanceId) }
    return ModelAttributionSnapshot(
        modelId = model.id,
        displayName = model.displayName,
        providerTypeRaw = instance?.providerType?.name ?: "",
        providerInstanceId = entry?.providerInstanceId,
    )
}

private fun ChatViewModel.groupTranscript(): List<GroupChat.Line> {
    val messages = _messages.value
    val start = groupChatClosedAfterId?.let { marker ->
        val index = messages.indexOfLast { it.id == marker }
        if (index >= 0) index + 1 else 0
    } ?: 0
    return messages.drop(start).mapNotNull { message ->
        val speaker = message.speakerName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val text = message.content.trim()
        if (text.isBlank() || message.isAwaitingModelResponse) null
        else GroupChat.Line(speaker, text)
    }
}

private fun ChatViewModel.recentContext(): String =
    _messages.value.takeLast(8).joinToString("\n") { message ->
        val who = message.speakerName ?: message.role
        "$who: ${message.content.take(400)}"
    }
