package app.openbill.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import app.openbill.ui.settings.InMemorySettingsStore
import app.openbill.ui.settings.SettingsStore
import java.util.prefs.Preferences

actual val platformSupportsDynamicColor: Boolean = false

actual val platformPrefersKeyboard: Boolean = true

@Composable
actual fun platformDynamicColorScheme(dark: Boolean): ColorScheme? = null

@Composable
actual fun SystemBarsAppearance(darkTheme: Boolean) {
    // Desktop windows draw their own decorations.
}

@Composable
actual fun rememberReducedMotion(): Boolean = false

/** Per-user preferences node (registry on Windows, ~/.java on Linux). */
private class PreferencesSettingsStore(private val prefs: Preferences) : SettingsStore {
    override fun getString(key: String): String? = prefs.get(key, null)
    override fun putString(key: String, value: String) {
        prefs.put(key, value)
        runCatching { prefs.flush() }
    }
}

actual fun defaultSettingsStore(): SettingsStore =
    runCatching<SettingsStore> { PreferencesSettingsStore(Preferences.userRoot().node("app/openbill/ui")) }
        .getOrElse { InMemorySettingsStore() }
