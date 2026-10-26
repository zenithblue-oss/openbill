package app.openbill.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device setting: "true" sends Print straight to the default printer (desktop). */
const val KEY_SILENT_PRINT = "print.silent"

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Android 12+ wallpaper colours. Off by default so the brand colours show first. */
    val useDeviceColors: Boolean = false,
    /**
     * Calmer motion: transitions snap and the introduction shows still frames. Adds to the
     * platform signal (Android animator scale 0); desktop has no system signal, so this is it.
     */
    val reduceMotion: Boolean = false,
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
        const val KEY_REDUCE_MOTION = "appearance.reduceMotion"

        /** Reads settings; unknown or missing values fall back to defaults instead of failing. */
        fun read(store: SettingsStore): AppearanceSettings = AppearanceSettings(
            themeMode = store.getString(KEY_THEME)?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } }
                ?: ThemeMode.SYSTEM,
            useDeviceColors = store.getString(KEY_DEVICE_COLORS) == "true",
            reduceMotion = store.getString(KEY_REDUCE_MOTION) == "true",
        )

        fun write(store: SettingsStore, settings: AppearanceSettings) {
            store.putString(KEY_THEME, settings.themeMode.name)
            store.putString(KEY_DEVICE_COLORS, settings.useDeviceColors.toString())
            store.putString(KEY_REDUCE_MOTION, settings.reduceMotion.toString())
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
    fun setReduceMotion(enabled: Boolean) = update { it.copy(reduceMotion = enabled) }
}

/** The business shown on this device (null: the first one), and whether "Add business" is open. */
class ActiveFirmController(private val store: SettingsStore) {
    val firmId = MutableStateFlow(runCatching { store.getString(KEY) }.getOrNull())
    val adding = MutableStateFlow(false)

    fun select(id: String) {
        adding.value = false
        firmId.value = id
        runCatching { store.putString(KEY, id) }
    }

    private companion object {
        const val KEY = "firm.active"
    }
}

/**
 * Remembers whether the first-run introduction has been finished or skipped on this device.
 * Read once; [markCompleted] persists immediately so a restart never shows it again.
 */
class OnboardingController(private val store: SettingsStore) {
    private val _completed = MutableStateFlow(runCatching { store.getString(KEY_COMPLETED) == "true" }.getOrDefault(false))
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    fun markCompleted() {
        if (_completed.value) return
        _completed.value = true
        runCatching { store.putString(KEY_COMPLETED, "true") }
    }

    companion object {
        const val KEY_COMPLETED = "onboarding.completed"
    }
}
