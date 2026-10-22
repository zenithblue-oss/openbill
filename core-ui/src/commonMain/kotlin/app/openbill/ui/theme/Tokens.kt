package app.openbill.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale on a 4 dp grid. */
object Spacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp

    /** Horizontal page gutter on compact windows; medium/expanded use [gutterWide]. */
    val gutter: Dp = 16.dp
    val gutterWide: Dp = 24.dp

    /** Readable maximum width for forms and detail pages on wide windows. */
    val contentMaxWidth: Dp = 720.dp

    /** Bottom padding for lists under an extended FAB. */
    val fabClearance: Dp = 96.dp
}

/** Tonal elevation levels (M3 levels 0-5). Shadows are reserved for FABs and menus. */
object Elevation {
    val level0: Dp = 0.dp
    val level1: Dp = 1.dp
    val level2: Dp = 3.dp
    val level3: Dp = 6.dp
    val level4: Dp = 8.dp
    val level5: Dp = 12.dp
}

val OpenBillShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The M3 baseline type scale with tabular (fixed-width) figures switched on, so amounts, dates,
 * quantities and invoice numbers line up in columns and do not jitter while they animate.
 */
val OpenBillTypography: Typography = Typography().let { t ->
    fun TextStyle.tnum() = copy(fontFeatureSettings = "tnum")
    t.copy(
        displayLarge = t.displayLarge.tnum(),
        displayMedium = t.displayMedium.tnum(),
        displaySmall = t.displaySmall.tnum(),
        headlineLarge = t.headlineLarge.tnum(),
        headlineMedium = t.headlineMedium.tnum(),
        headlineSmall = t.headlineSmall.tnum(),
        titleLarge = t.titleLarge.tnum(),
        titleMedium = t.titleMedium.tnum(),
        titleSmall = t.titleSmall.tnum(),
        bodyLarge = t.bodyLarge.tnum(),
        bodyMedium = t.bodyMedium.tnum(),
        bodySmall = t.bodySmall.tnum(),
        labelLarge = t.labelLarge.tnum(),
        labelMedium = t.labelMedium.tnum(),
        labelSmall = t.labelSmall.tnum(),
    )
}

/** Motion tokens (M3 easing and duration set, capped at 300 ms for this app). */
object Motion {
    const val SHORT1 = 50
    const val SHORT2 = 100
    const val SHORT3 = 150
    const val SHORT4 = 200
    const val MEDIUM1 = 250
    const val MEDIUM2 = 300

    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** Horizontal travel for shared-axis X transitions. */
    val SharedAxisOffset: Dp = 30.dp
}

/** True when the platform asks for reduced motion (Android animator scale 0). */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Duration-aware tween that collapses to a snap when reduced motion is on. */
@Composable
@ReadOnlyComposable
fun <T> motionTween(durationMillis: Int, easing: Easing = Motion.Standard, delayMillis: Int = 0): FiniteAnimationSpec<T> =
    if (LocalReducedMotion.current) snap() else tween(durationMillis, delayMillis, easing)

/** Non-composable variant for transition specs that receive the flag explicitly. */
fun <T> motionTween(reduced: Boolean, durationMillis: Int, easing: Easing = Motion.Standard, delayMillis: Int = 0): FiniteAnimationSpec<T> =
    if (reduced) snap() else tween(durationMillis, delayMillis, easing)
