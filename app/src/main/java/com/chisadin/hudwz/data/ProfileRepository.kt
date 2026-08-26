package com.chisadin.hudwz.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chisadin.hudwz.domain.HudElementConfig
import com.chisadin.hudwz.domain.HudProfile
import com.chisadin.hudwz.domain.HudLayerMove
import com.chisadin.hudwz.domain.reorderHudElements
import com.chisadin.hudwz.domain.migrateHudProfile
import com.chisadin.hudwz.domain.emptyHudProfile
import com.chisadin.hudwz.domain.defaultPortraitProfileElements
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

class ProfileRepository(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
) {
    private val defaults = listOf(
        HudProfile.defaultProfile(), HudProfile.minimalProfile(), HudProfile.largeSpeedProfile(),
    )
    private val serializer = ListSerializer(HudProfile.serializer())

    val profiles: Flow<List<HudProfile>> = context.hudDataStore.data.map { values ->
        values[PROFILES]?.let { encoded ->
            runCatching { json.decodeFromString(serializer, encoded) }.getOrNull()
        }.orEmpty().ifEmpty { defaults }.map(::migrateHudProfile)
    }

    val activeProfileId: Flow<String> = context.hudDataStore.data.map { it[ACTIVE] ?: "default" }

    val activeProfile: Flow<HudProfile> = combine(profiles, activeProfileId) { all, active ->
        all.firstOrNull { it.id == active } ?: all.first()
    }

    suspend fun select(id: String) = context.hudDataStore.edit { it[ACTIVE] = id }

    suspend fun create(name: String): HudProfile {
        val profile = HudProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Custom" },
            layoutVersion = 4,
            elements = HudProfile.defaultProfile().elements,
            portraitElements = defaultPortraitProfileElements(),
        )
        mutate { it + profile }
        select(profile.id)
        return profile
    }

    suspend fun duplicate(id: String): HudProfile? {
        var copy: HudProfile? = null
        mutate { all ->
            val source = all.firstOrNull { it.id == id } ?: return@mutate all
            copy = source.copy(id = UUID.randomUUID().toString(), name = "${source.name} Copy")
            all + copy!!
        }
        copy?.let { select(it.id) }
        return copy
    }

    suspend fun rename(id: String, name: String) = mutate { all ->
        all.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
    }

    suspend fun delete(id: String) {
        var remaining: List<HudProfile> = emptyList()
        mutate { all -> all.filterNot { it.id == id }.ifEmpty { defaults }.also { remaining = it } }
        select(remaining.first().id)
    }

    suspend fun updateElement(profileId: String, element: HudElementConfig, isPortrait: Boolean = false) = mutate { all ->
        all.map { profile ->
            if (profile.id != profileId) profile else {
                if (isPortrait) {
                    val currentPortrait = if (profile.portraitElements.isNotEmpty()) profile.portraitElements else profile.elementsFor(true)
                    profile.copy(portraitElements = currentPortrait.map { if (it.id == element.id) element else it })
                } else {
                    profile.copy(elements = profile.elements.map { if (it.id == element.id) element else it })
                }
            }
        }
    }

    suspend fun addElement(profileId: String, element: HudElementConfig, isPortrait: Boolean = false) = mutate { all ->
        all.map { profile ->
            if (profile.id != profileId) profile else {
                if (isPortrait) {
                    val currentPortrait = if (profile.portraitElements.isNotEmpty()) profile.portraitElements else profile.elementsFor(true)
                    profile.copy(portraitElements = currentPortrait + element)
                } else {
                    profile.copy(elements = profile.elements + element)
                }
            }
        }
    }

    suspend fun removeElement(profileId: String, elementId: String, isPortrait: Boolean = false) = mutate { all ->
        all.map { profile ->
            if (profile.id != profileId) profile else {
                if (isPortrait) {
                    val currentPortrait = if (profile.portraitElements.isNotEmpty()) profile.portraitElements else profile.elementsFor(true)
                    profile.copy(portraitElements = currentPortrait.filterNot { it.id == elementId })
                } else {
                    profile.copy(elements = profile.elements.filterNot { it.id == elementId })
                }
            }
        }
    }

    suspend fun moveElement(profileId: String, elementId: String, move: HudLayerMove, isPortrait: Boolean = false) = mutate { all ->
        all.map { profile ->
            if (profile.id != profileId) profile else {
                if (isPortrait) {
                    val currentPortrait = if (profile.portraitElements.isNotEmpty()) profile.portraitElements else profile.elementsFor(true)
                    profile.copy(portraitElements = reorderHudElements(currentPortrait, elementId, move))
                } else {
                    profile.copy(elements = reorderHudElements(profile.elements, elementId, move))
                }
            }
        }
    }

    suspend fun updateScale(profileId: String, scale: Float) = mutate { all ->
        all.map { if (it.id == profileId) it.copy(hudScale = scale.coerceIn(.5f, 1.8f)) else it }
    }

    suspend fun replaceAll(imported: List<HudProfile>) {
        require(imported.isNotEmpty())
        context.hudDataStore.edit {
            it[PROFILES] = json.encodeToString(serializer, imported.map(::migrateHudProfile))
        }
    }

    suspend fun exportJson(): String {
        val values = context.hudDataStore.data.first()
        val profiles = values[PROFILES]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty().ifEmpty { defaults }.map(::migrateHudProfile)
        return json.encodeToString(serializer, profiles)
    }

    private suspend fun mutate(block: (List<HudProfile>) -> List<HudProfile>) {
        context.hudDataStore.edit { values ->
            val current = values[PROFILES]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
                .orEmpty().ifEmpty { defaults }.map(::migrateHudProfile)
            values[PROFILES] = json.encodeToString(serializer, block(current))
        }
    }

    private companion object {
        val PROFILES = stringPreferencesKey("profiles_json")
        val ACTIVE = stringPreferencesKey("active_profile")
    }
}
