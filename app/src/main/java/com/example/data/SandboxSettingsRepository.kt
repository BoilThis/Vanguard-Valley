package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.LightingPreset
import com.example.model.WorldConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.max

private val Context.sandboxDataStore: DataStore<Preferences> by preferencesDataStore(name = "vanguard_valley_prefs")

class SandboxSettingsRepository(private val context: Context) {

    private object Keys {
        val SEED = intPreferencesKey("world_seed")
        val RIVER_WIDTH = floatPreferencesKey("river_width")
        val FOREST_DENSITY = floatPreferencesKey("forest_density")
        val TOWN_SCALE = floatPreferencesKey("town_scale")
        val BASE_FORT = floatPreferencesKey("base_fortification")
        val LIGHTING = stringPreferencesKey("lighting_preset")
        val AUDIO_MUTED = booleanPreferencesKey("audio_muted")
        val MISSION_STARS = stringPreferencesKey("mission_stars_map")
    }

    val worldConfigFlow: Flow<WorldConfig> = context.sandboxDataStore.data.map { prefs ->
        val presetName = prefs[Keys.LIGHTING] ?: LightingPreset.NOON.name
        val preset = runCatching { LightingPreset.valueOf(presetName) }.getOrDefault(LightingPreset.NOON)
        WorldConfig(
            seed = prefs[Keys.SEED] ?: 4207,
            riverWidth = prefs[Keys.RIVER_WIDTH] ?: 1.0f,
            forestDensity = prefs[Keys.FOREST_DENSITY] ?: 1.0f,
            townScale = prefs[Keys.TOWN_SCALE] ?: 1.0f,
            baseFortification = prefs[Keys.BASE_FORT] ?: 1.0f,
            lightingPreset = preset
        )
    }

    val audioMutedFlow: Flow<Boolean> = context.sandboxDataStore.data.map { prefs ->
        prefs[Keys.AUDIO_MUTED] ?: false
    }

    val missionStarsFlow: Flow<Map<String, Int>> = context.sandboxDataStore.data.map { prefs ->
        parseStarsMap(prefs[Keys.MISSION_STARS].orEmpty())
    }

    suspend fun saveWorldConfig(config: WorldConfig) {
        context.sandboxDataStore.edit { prefs ->
            prefs[Keys.SEED] = config.seed
            prefs[Keys.RIVER_WIDTH] = config.riverWidth
            prefs[Keys.FOREST_DENSITY] = config.forestDensity
            prefs[Keys.TOWN_SCALE] = config.townScale
            prefs[Keys.BASE_FORT] = config.baseFortification
            prefs[Keys.LIGHTING] = config.lightingPreset.name
        }
    }

    suspend fun setAudioMuted(muted: Boolean) {
        context.sandboxDataStore.edit { prefs ->
            prefs[Keys.AUDIO_MUTED] = muted
        }
    }

    suspend fun saveMissionStars(missionId: String, stars: Int) {
        context.sandboxDataStore.edit { prefs ->
            val current = parseStarsMap(prefs[Keys.MISSION_STARS].orEmpty()).toMutableMap()
            val best = max(current[missionId] ?: 0, stars.coerceIn(0, 3))
            current[missionId] = best
            prefs[Keys.MISSION_STARS] = current.entries.joinToString(";") { "${it.key}=${it.value}" }
        }
    }

    private fun parseStarsMap(raw: String): Map<String, Int> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (entry in raw.split(";")) {
            val parts = entry.split("=")
            if (parts.size == 2) {
                val stars = parts[1].toIntOrNull()
                if (stars != null) out[parts[0]] = stars.coerceIn(0, 3)
            }
        }
        return out
    }
}
