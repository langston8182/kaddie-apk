package com.kaddie.app.data.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val groupByCategory: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("kaddie_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
                .getOrDefault(ThemeMode.SYSTEM),
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC, true),
            groupByCategory = prefs.getBoolean(KEY_GROUP, true),
        ),
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _settings.value = _settings.value.copy(themeMode = mode)
    }

    fun setGroupByCategory(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GROUP, enabled).apply()
        _settings.value = _settings.value.copy(groupByCategory = enabled)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC, enabled).apply()
        _settings.value = _settings.value.copy(dynamicColor = enabled)
    }

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_DYNAMIC = "dynamic_color"
        const val KEY_GROUP = "group_by_category"
    }
}
