package app.openbill.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import app.openbill.ui.settings.AppearanceSettings

/**
 * Root theme. Resolves light/dark from [settings] and the OS, picks the dynamic (wallpaper)
 * scheme when the user opted in and the platform supports it, and otherwise uses the hand-tuned
 * OpenBill schemes. Semantic success/warning colours never follow the wallpaper.
 */
@Composable
fun OpenBillTheme(settings: AppearanceSettings = AppearanceSettings(), content: @Composable () -> Unit) {
    val dark = settings.isDark(isSystemInDarkTheme())
    val dynamic = if (settings.usesDynamicColor(platformSupportsDynamicColor)) platformDynamicColorScheme(dark) else null
    val colors = dynamic ?: if (dark) OpenBillDarkColors else OpenBillLightColors
    SystemBarsAppearance(darkTheme = dark)
    CompositionLocalProvider(
        LocalExtendedColors provides if (dark) DarkExtendedColors else LightExtendedColors,
        LocalReducedMotion provides (rememberReducedMotion() || settings.reduceMotion),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = OpenBillTypography,
            shapes = OpenBillShapes,
            content = content,
        )
    }
}

/** Accessor mirroring MaterialTheme.colorScheme for the semantic extension roles. */
object OpenBillThemeExt {
    val extendedColors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}
