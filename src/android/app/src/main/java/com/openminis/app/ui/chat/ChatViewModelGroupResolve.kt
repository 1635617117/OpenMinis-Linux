package com.openminis.app.ui.chat

import com.openminis.app.data.CapabilityRouter
import com.openminis.app.data.ModelCapability
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.service.SessionActivityTracker

internal fun ChatViewModel.resolveProviderFromGroup(
        groupId: String,
        preferredEntryId: String? = null,
        needed: Set<ModelCapability> = emptySet(),
        pinActiveEntry: Boolean = true,
    ): Boolean {
        val group = providerRepository.group(groupId) ?: return false
        // [T-android-group-resolve-skip-uncredentialed] FILTER FIRST, THEN
        // PICK — mirroring iOS `ModelGroupRouter.resolve`.
        //
        // This used to take `enabledMemberEntries.first()` and only THEN check
        // the credential, bailing out of the whole group with `?: return false`
        // when that one member had none. So a group whose first member sat on a
        // provider without a credential resolved to nothing at all, even when
        // later members were perfectly usable — and the caller fell through to
        // the new-chat default chain, which picks by "most recently added
        // provider" and therefore landed on a model that was not in the group
        // the user had selected (field report: a group of Claude+GPT entries
        // resolving to an unrelated self-hosted model on every new chat).
        //
        // The same repo already got this right one layer down:
        // buildFallbackProviders skips an uncredentialed member with
        // `?: continue`. Selection and fallback now agree.
        //
        // Note availableMemberEntries also drops HIDDEN entries, matching iOS.
        // enabledMemberEntries (still used by the settings UI) deliberately
        // does not — "switched on" is the right question there, "usable right
        // now" is the right question here.
        val decision = CapabilityRouter.decide(
            providerRepository.availableMemberEntries(group),
            needed,
            preferredEntryId,
        )
        decision.reason?.let { SessionActivityTracker.updateToolStatus(it.take(80)) }
        val available = decision.members
        if (available.isEmpty()) return false

        // preferredEntryId comes from a prior session binding ("user picked
        // this entry inside the group last time"). Honor it only if it is
        // still available; otherwise fall through to the strategy so the
        // session can still proceed on a now-degraded group.
        val targetEntry = available.firstOrNull { it.id == preferredEntryId }
            ?: when (group.strategy) {
                // [T-android-group-resolve-skip-uncredentialed] Honor the
                // group's routing strategy, which initial selection previously
                // ignored entirely — loadBalance silently behaved as fallback.
                // Hashing the session id keeps the choice STABLE for a given
                // session (re-entering it must not reshuffle the model) while
                // spreading distinct sessions across members, matching iOS.
                RoutingStrategy.loadBalance ->
                    available[
                        Math.floorMod(
                            realSessionId.ifEmpty { sessionId }.hashCode(),
                            available.size,
                        ),
                    ]
                RoutingStrategy.fallback -> available.first()
            }

        val instance = providerRepository.instance(targetEntry.providerInstanceId) ?: return false
        // Non-null by construction: availableMemberEntries already required a
        // credential. An OAuth instance has no API key to pass — the factory
        // reads its token from storage — so "" is the correct argument there.
        val apiKey = providerRepository.usableApiKey(instance) ?: ""

        currentModel = targetEntry.model
        _modelName.value = targetEntry.model.displayName
        _providerName.value = instance.label.ifEmpty { targetEntry.model.provider }
        _selectedGroupName.value = group.name
        if (pinActiveEntry) {
            _activeEntryId.value = targetEntry.id
        }
        currentProvider = ProviderFactory.create(instance, apiKey, targetEntry.model, context)
        return true
    }
