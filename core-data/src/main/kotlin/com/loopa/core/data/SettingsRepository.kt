package com.loopa.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class UserSettings(
    val soundEnabled: Boolean = true,
    val musicEnabled: Boolean = false,
    val vibrationEnabled: Boolean = true,
    val language: String = "tr",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val onboardingDone: Boolean = false,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.dataStore by preferencesDataStore(name = "loopa_settings")

/** Local, per-device preferences. Nothing competitive is stored here. */
class SettingsRepository(private val context: Context) {
    private object Keys {
        val sound = booleanPreferencesKey("sound")
        val music = booleanPreferencesKey("music")
        val vibration = booleanPreferencesKey("vibration")
        val language = stringPreferencesKey("language")
        val theme = stringPreferencesKey("theme")
        val onboarding = booleanPreferencesKey("onboarding_done")
    }

    val settings: Flow<UserSettings> = context.dataStore.data.map { p ->
        UserSettings(
            soundEnabled = p[Keys.sound] ?: true,
            musicEnabled = p[Keys.music] ?: false,
            vibrationEnabled = p[Keys.vibration] ?: true,
            language = p[Keys.language] ?: "tr",
            theme = runCatching { ThemeMode.valueOf(p[Keys.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            onboardingDone = p[Keys.onboarding] ?: false,
        )
    }

    suspend fun setSound(v: Boolean) = context.dataStore.edit { it[Keys.sound] = v }
    suspend fun setMusic(v: Boolean) = context.dataStore.edit { it[Keys.music] = v }
    suspend fun setVibration(v: Boolean) = context.dataStore.edit { it[Keys.vibration] = v }
    suspend fun setLanguage(v: String) = context.dataStore.edit { it[Keys.language] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[Keys.theme] = v.name }
    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[Keys.onboarding] = v }
}
