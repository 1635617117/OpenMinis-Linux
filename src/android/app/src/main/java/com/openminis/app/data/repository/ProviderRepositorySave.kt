package com.openminis.app.data.repository

import com.openminis.app.data.model.ProviderConfig
import kotlinx.coroutines.runBlocking

internal fun ProviderRepository.saveConfig(config: ProviderConfig) {
        // [T-android-provider-room-store] Double-write: DB + legacy JSON
        // mirror, both produced inside the same configLock window. The
        // mirror keeps older app builds able to read current config on
        // downgrade; the DB is the new authoritative store on this build.
        //
        // Serialize + persist + emit under [configLock] so we never serialize
        // a list that another writer is mutating. The fresh `.copy(…toMutableList())`
        // wrapper alone is not enough: data-class structural equals walks the
        // inner Lists and `prev` (already mutated in place by replaceEntries /
        // addEntry / removeEntry) compares equal to `next` → MutableStateFlow
        // suppresses the emission. T273 bumps `revision` so equals always
        // returns false and 18+ collectAsState callers see the new value.
        synchronized(configLock) {
            // [T-android-provider-empty-load-wipe] No "block empty saves" guard
            // here on purpose: mutators pass the SAME object as _config.value
            // and mutate it in place, so at this point an empty `config` and an
            // empty `_config.value` are the same list — a legitimate
            // "user deleted their last provider" is indistinguishable from a
            // phantom-empty load. The wipe is prevented at the source instead
            // (loadConfigSuspending refuses to return a blank config when the
            // DB read failed rather than honestly reporting zero rows).
            // persistToDbAndMirror returns the canonicalized config (entries'
            // uuid in composite "{instanceId}/{modelId}" form). Emit that so
            // subsequent in-memory reads — which compare entry.id by string
            // equality (e.g. group.memberEntryIds.contains(it.id)) — use one
            // consistent id shape rather than mixing legacy random uuids and
            // composite keys.
            //
            // Catch persistence failures so callers stay fire-and-forget
            // (matches the legacy `apply()` contract — pre-Room saveConfig
            // never threw). DB write fails are rare in practice (disk full,
            // SQLite corruption, transaction deadlock) but uncaught they'd
            // crash whichever UI handler triggered the mutation. Still
            // emit the in-memory state so the UI reflects the user's
            // intent even when the disk write didn't land; the next
            // successful save resyncs everything.
            val canonical = try {
                runBlocking { persistToDbAndMirror(config) }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] saveConfig persistence failed; emitting in-memory only: ${e.message}",
                    e,
                )
                config
            }
            _config.value = canonical.copy(
                instances = canonical.instances.toMutableList(),
                modelEntries = canonical.modelEntries.toMutableList(),
                modelGroups = canonical.modelGroups.toMutableList(),
                agentLoopModelEntryIds = canonical.agentLoopModelEntryIds.toMutableList(),
                agentLoopGroupIds = canonical.agentLoopGroupIds.toMutableList(),
                revision = ProviderConfig.nextRevision(),
            )
        }
    }

