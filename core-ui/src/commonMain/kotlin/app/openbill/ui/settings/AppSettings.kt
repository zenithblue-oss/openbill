package app.openbill.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tiny key-value store for per-device UI preferences (never business data, which lives in the
 * database). Android backs it with SharedPreferences, desktop with java.util.prefs.
 */
interface SettingsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class InMemorySettingsStore(initial: Map<String, String> = emptyMap()) : SettingsStore {
    private val values = initial.toMutableMap()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Android 12+ wallpaper colours. Off by default so the brand colours show first. */
    val useDeviceColors: Boolean = false,
) {
    fun isDark(systemDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /** Device colours apply only where the platform supports them. */
    fun usesDynamicColor(platformSupportsDynamic: Boolean): Boolean = useDeviceColors && platformSupportsDynamic

    companion object {
        const val KEY_THEME = "appearance.theme"
        const val KEY_DEVICE_COLORS = "appearance.deviceColors"

        /** Reads settings; unknown or missing values fall back to defaults instead of failing. */
        fun read(store: SettingsStore): AppearanceSettings = AppearanceSettings(
            themeMode = store.getString(KEY_THEME)?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } }
                ?: ThemeMode.SYSTEM,
            useDeviceColors = store.getString(KEY_DEVICE_COLORS) == "true",
        )

        fun write(store: SettingsStore, settings: AppearanceSettings) {
            store.putString(KEY_THEME, settings.themeMode.name)
            store.putString(KEY_DEVICE_COLORS, settings.useDeviceColors.toString())
        }
    }
}

/** Holds the live appearance settings and persists every change. */
class AppearanceController(private val store: SettingsStore) {
    private val _settings = MutableStateFlow(runCatching { AppearanceSettings.read(store) }.getOrDefault(AppearanceSettings()))
    val settings: StateFlow<AppearanceSettings> = _settings.asStateFlow()

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        runCatching { AppearanceSettings.write(store, next) }
    }

    fun setThemeMode(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    fun setUseDeviceColors(enabled: Boolean) = update { it.copy(useDeviceColors = enabled) }
}
