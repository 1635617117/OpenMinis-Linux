package com.openminis.app.data.repository

import com.openminis.app.data.db.ProviderConfigMetaKeys
import com.openminis.app.data.db.ProviderConfigSnapshot
import com.openminis.app.data.db.compositeEntryKey
import com.openminis.app.data.db.toProviderConfig
import com.openminis.app.data.model.ProviderConfig

    /**
     * [T-android-provider-room-store] DB-first load with three-way
     * reconciliation between provider.db and the legacy JSON mirror:
     *
     *   - DB has rows AND meta.json_sync_hash matches the live mirror's
     *     hash → DB is in sync with what we last wrote. Use DB.
     *   - DB has rows but the hash mismatches → an older app build was
     *     installed at some point, wrote through the JSON path, and
     *     bypassed our DB. The JSON is fresher. Re-import JSON → rewrite
     *     DB → resync hash.
     *   - DB is empty but JSON exists → first launch on a build that
     *     knows about the DB. One-shot import from JSON → DB.
     *   - Both empty → empty config (fresh install).
     *
     * The JSON mirror is the durable downgrade safety net: we keep
     * writing it on every save so the old build always sees current
     * config; if the user round-trips through an old build, the
     * hash check above re-syncs DB to whatever JSON looks like now.
     */
internal suspend fun ProviderRepository.loadConfigSuspending(): ProviderConfig {
        val rawJson = prefs.getString("config", null)
        // [T-android-provider-empty-load-wipe] Distinguish "the DB says zero"
        // from "we could not ask the DB". Swallowing the exception as 0 made a
        // transient DAO failure (locked/mid-write file after a crash) look
        // exactly like a fresh install: the loader fell through to an empty
        // ProviderConfig(), and the next mutation's persistToDbAndMirror wrote
        // that emptiness over a fully populated store — wiping every provider.
        // Observed on Pixel 6 after a ConcurrentModificationException crash
        // left provider.db mid-write: 18 instances became 5.
        var daoReadFailed = false
        val instanceCount = try {
            providerDao.instanceCount()
        } catch (e: Exception) {
            android.util.Log.w("ProviderRepo", "[ProviderStore] DAO instanceCount failed: ${e.message}")
            daoReadFailed = true
            0
        }
        android.util.Log.i(
            "ProviderRepo",
            "[ProviderStore] load: dbInstances=$instanceCount daoFailed=$daoReadFailed " +
                "mirrorBytes=${rawJson?.length ?: -1}",
        )

        val (dbConfig, dbHashStored) = if (instanceCount > 0) {
            try {
                val snapshot = ProviderConfigSnapshot(
                    instances = providerDao.loadInstances(),
                    entries = providerDao.loadEntries(),
                    groups = providerDao.loadGroups(),
                    loopIds = providerDao.loadAgentLoopIds(),
                    meta = providerDao.loadMeta(),
                )
                val cfg = snapshot.toProviderConfig(json)
                val storedHash = snapshot.meta.firstOrNull {
                    it.key == ProviderConfigMetaKeys.JSON_SYNC_HASH
                }?.value
                cfg to storedHash
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] DB load failed, falling back to JSON: ${e.message}")
                daoReadFailed = true
                null to null
            }
        } else {
            null to null
        }

        if (dbConfig != null) {
            val liveHash = rawJson?.let(::hashJsonMirror)
            if (liveHash == dbHashStored) {
                return dbConfig
            }
            // Hash mismatch: JSON has been written by an older build during
            // a downgrade window. Re-import JSON → reseed DB so DB catches
            // up to the user's actual current config.
            android.util.Log.i(
                "ProviderRepo",
                "[ProviderStore] hash mismatch (stored=${dbHashStored?.take(8)} live=${liveHash?.take(8)}) — re-importing JSON mirror",
            )
        }

        if (rawJson != null) {
            val parsed = try {
                json.decodeFromString<ProviderConfig>(rawJson)
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[ProviderStore] JSON decode failed: ${e.message}")
                null
            }
            if (parsed != null) {
                val mirrored = try {
                    persistToDbAndMirror(parsed)
                } catch (e: Exception) {
                    android.util.Log.w("ProviderRepo", "[ProviderStore] JSON→DB import failed: ${e.message}")
                    parsed
                }
                // Migrate the per-user lastUsedEntryId SharedPreferences key
                // from the legacy random-uuid entry id form to the new
                // composite "{instanceId}/{modelId}" shape, using the
                // pre-canonicalization `parsed` entries as the uuid→composite
                // dictionary. Without this, the user's last-picked model on
                // the upgrade-first-launch isn't recognized by
                // lastUsedVisibleEntry() and the next new chat falls through
                // to the newest-provider fallback — i.e. it looks like the
                // upgrade "forgot" the user's recent selection.
                val legacyLastUsed = prefs.getString(ProviderRepository.KEY_LAST_USED_ENTRY, null)
                if (legacyLastUsed != null && !legacyLastUsed.contains('/')) {
                    val rewritten = parsed.modelEntries
                        .firstOrNull { it.uuid == legacyLastUsed }
                        ?.let { compositeEntryKey(it.providerInstanceId, it.baseModel.id) }
                    if (rewritten != null) {
                        prefs.edit().putString(ProviderRepository.KEY_LAST_USED_ENTRY, rewritten).apply()
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] lastUsedEntryId rewritten ${legacyLastUsed.take(8)} → $rewritten",
                        )
                    }
                }
                android.util.Log.i(
                    "ProviderRepo",
                    "[ProviderStore] migrated/synced ${mirrored.instances.size} instances " +
                        "${mirrored.modelEntries.size} entries from JSON → Room",
                )
                return mirrored
            }
        }

        // [T-android-provider-room-store] Last-resort fallback. If DB had
        // rows but the live JSON mirror is unparseable (disk corruption,
        // interrupted write, etc.) AND we couldn't re-import, KEEP THE DB
        // — losing user config is worse than running with a stale mirror.
        // The next successful save will rewrite the mirror and resync the
        // hash. Returning ProviderConfig() here would let the very next
        // mutator's persistToDbAndMirror overwrite the populated DB with
        // an empty config, silently wiping the user's providers.
        if (dbConfig != null) {
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] mirror unreadable + re-import failed; keeping " +
                    "${dbConfig.instances.size} DB instances as authoritative",
            )
            return dbConfig
        }

        // [T-android-provider-empty-load-wipe] Reaching here means BOTH stores
        // came back empty. That is legitimate on a fresh install — but if the
        // DB read actually FAILED (rather than honestly reporting zero rows),
        // an empty config is a lie we are about to persist over real data.
        // Refuse: throwing keeps _configLoaded false, so ensureConfigLoaded
        // retries on the next access instead of caching the empty value, and
        // no mutation can run against a phantom-empty config.
        if (daoReadFailed) {
            android.util.Log.e(
                "ProviderRepo",
                "[ProviderStore] REFUSING empty config — DB read failed and JSON mirror " +
                    "unusable; not overwriting a possibly-populated store",
            )
            throw IllegalStateException(
                "Provider store unreadable (DB read failed, JSON mirror unusable) — " +
                    "refusing to load an empty config that would overwrite existing providers",
            )
        }
        return ProviderConfig()
    }

