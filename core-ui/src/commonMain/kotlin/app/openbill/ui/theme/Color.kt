package app.openbill.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * OpenBill colour tokens. See docs/12-design-system.md section 2.
 *
 * Brand seed: "Peacock" #00677A (HCT hue 218, chroma 36). Tonal palettes were generated with
 * the Material colour utilities and then hand-tuned:
 * - primary keeps chroma 48 so the brand reads clearly on paper-like surfaces;
 * - secondary is the seed hue at chroma 16 (quiet, for chips and selection);
 * - tertiary is an original marigold accent (hue ~75) for highlights, not for status;
 * - neutrals carry chroma 5 of the seed hue so surfaces look like slightly cool paper;
 * - "on container" roles use tone 10 / 90 for maximum legibility of small text.
 */
internal object Palette {
    // Primary (seed hue 218)
    val P10 = Color(0xFF001F26)
    val P20 = Color(0xFF003641)
    val P30 = Color(0xFF004E5D)
    val P40 = Color(0xFF00687B)
    val P80 = Color(0xFF56D6F5)
    val P90 = Color(0xFFAEECFF)
    // Secondary (seed hue, chroma 16)
    val S10 = Color(0xFF061F25)
    val S20 = Color(0xFF1D343A)
    val S30 = Color(0xFF344A51)
    val S40 = Color(0xFF4B6269)
    val S80 = Color(0xFFB2CBD3)
    val S90 = Color(0xFFCEE7EF)
    // Tertiary (marigold)
    val T10 = Color(0xFF281900)
    val T20 = Color(0xFF432C00)
    val T30 = Color(0xFF604100)
    val T40 = Color(0xFF7F5700)
    val T80 = Color(0xFFFCBB4A)
    val T90 = Color(0xFFFFDEAE)
    // Neutral (chroma 5)
    val N4 = Color(0xFF0A0F10)
    val N6 = Color(0xFF101415)
    val N10 = Color(0xFF181C1E)
    val N12 = Color(0xFF1C2022)
    val N17 = Color(0xFF262B2C)
    val N20 = Color(0xFF2D3133)
    val N22 = Color(0xFF313537)
    val N24 = Color(0xFF353A3B)
    val N87 = Color(0xFFD7DBDC)
    val N90 = Color(0xFFDFE3E5)
    val N92 = Color(0xFFE5E9EA)
    val N94 = Color(0xFFEBEEF0)
    val N95 = Color(0xFFEEF1F3)
    val N96 = Color(0xFFF1F4F6)
    val N98 = Color(0xFFF6FAFC)
    // Neutral variant (chroma 10)
    val NV30 = Color(0xFF3D494C)
    val NV50 = Color(0xFF6D797D)
    val NV60 = Color(0xFF869397)
    val NV80 = Color(0xFFBCC9CD)
    val NV90 = Color(0xFFD8E5E9)
}

val OpenBillLightColors: ColorScheme = with(Palette) {
    lightColorScheme(
        primary = P40,
        onPrimary = Color.White,
        primaryContainer = P90,
        onPrimaryContainer = P10,
        inversePrimary = P80,
        secondary = S40,
        onSecondary = Color.White,
        secondaryContainer = S90,
        onSecondaryContainer = S10,
        tertiary = T40,
        onTertiary = Color.White,
        tertiaryContainer = T90,
        onTertiaryContainer = T10,
        background = N98,
        onBackground = N10,
        surface = N98,
        onSurface = N10,
        surfaceVariant = NV90,
        onSurfaceVariant = NV30,
        surfaceTint = P40,
        inverseSurface = N20,
        inverseOnSurface = N95,
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = NV50,
        outlineVariant = NV80,
        scrim = Color.Black,
        surfaceBright = N98,
        surfaceDim = N87,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = N96,
        surfaceContainer = N94,
        surfaceContainerHigh = N92,
        surfaceContainerHighest = N90,
    )
}

val OpenBillDarkColors: ColorScheme = with(Palette) {
    darkColorScheme(
        primary = P80,
        onPrimary = P20,
        primaryContainer = P30,
        onPrimaryContainer = P90,
        inversePrimary = P40,
        secondary = S80,
        onSecondary = S20,
        secondaryContainer = S30,
        onSecondaryContainer = S90,
        tertiary = T80,
        onTertiary = T20,
        tertiaryContainer = T30,
        onTertiaryContainer = T90,
        background = N6,
        onBackground = N90,
        surface = N6,
        onSurface = N90,
        surfaceVariant = NV30,
        onSurfaceVariant = NV80,
        surfaceTint = P80,
        inverseSurface = N90,
        inverseOnSurface = N20,
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = NV60,
        outlineVariant = NV30,
        scrim = Color.Black,
        surfaceBright = N24,
        surfaceDim = N6,
        surfaceContainerLowest = N4,
        surfaceContainerLow = N10,
        surfaceContainer = N12,
        surfaceContainerHigh = N17,
        surfaceContainerHighest = N22,
    )
}

/**
 * Semantic roles Material 3 does not define. They stay fixed (not dynamic) so a posted invoice
 * always reads as "done" whatever the wallpaper; hues were harmonised toward the brand seed.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val LightExtendedColors = ExtendedColors(
    success = Color(0xFF006D43),
    onSuccess = Color.White,
    successContainer = Color(0xFF92F7BC),
    onSuccessContainer = Color(0xFF002111),
    warning = Color(0xFF7D5800),
    onWarning = Color.White,
    warningContainer = Color(0xFFFFDEA9),
    onWarningContainer = Color(0xFF271900),
)

val DarkExtendedColors = ExtendedColors(
    success = Color(0xFF76DAA1),
    onSuccess = Color(0xFF003920),
    successContainer = Color(0xFF005231),
    onSuccessContainer = Color(0xFF92F7BC),
    warning = Color(0xFFF6BD51),
    onWarning = Color(0xFF422D00),
    warningContainer = Color(0xFF5E4100),
    onWarningContainer = Color(0xFFFFDEA9),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }
