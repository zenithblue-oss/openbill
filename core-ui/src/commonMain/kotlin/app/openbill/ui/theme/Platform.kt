package app.openbill.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import app.openbill.ui.settings.SettingsStore

/** True on platforms that can supply wallpaper-based colours (Android 12 and newer). */
expect val platformSupportsDynamicColor: Boolean

/** The platform's dynamic scheme, or null where unsupported. */
@Composable
expect fun platformDynamicColorScheme(dark: Boolean): ColorScheme?

/** Keeps status and navigation bar icons legible against the app theme (not just the OS theme). */
@Composable
expect fun SystemBarsAppearance(darkTheme: Boolean)

/** True when the user has turned animations off (Android animator duration scale 0). */
@Composable
expect fun rememberReducedMotion(): Boolean

/**
 * True where a physical keyboard is the primary input (desktop): search fields in pickers take
 * focus on open. False on touch devices so a sheet does not open half-covered by the keyboard.
 */
expect val platformPrefersKeyboard: Boolean

/** Default per-device settings store for the platform. */
expect fun defaultSettingsStore(): SettingsStore
