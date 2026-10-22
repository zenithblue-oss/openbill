package app.openbill.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * OpenBill logo (receipt with folded corner and an open "O"), on a Peacock tile. Generated from
 * branding/tools/generate_brand.py; see docs/13-brand.md. Brand colours are fixed, not themed.
 */
object BrandLogo {
    private val Peacock = Color(0xFF00677A)
    private val Marigold = Color(0xFFE8A317)

    val Mark: ImageVector by lazy {
        ImageVector.Builder(
            name = "OpenBillLogo",
            defaultWidth = 64.dp,
            defaultHeight = 64.dp,
            viewportWidth = 64f,
            viewportHeight = 64f,
        ).apply {
            addPath(addPathNodes("M14 0H50A14 14 0 0 1 64 14V50A14 14 0 0 1 50 64H14A14 14 0 0 1 0 50V14A14 14 0 0 1 14 0Z"), fill = SolidColor(Peacock))
            addPath(addPathNodes("M16 8.8L38.4 8.8L48 18.4L48 48.8L40 53.6L32 48.8L24 53.6L16 48.8Z"), fill = SolidColor(Color.White))
            addPath(addPathNodes("M38.4 8.8L38.4 18.4L48 18.4Z"), fill = SolidColor(Marigold))
            addPath(addPathNodes("M40.7 23.14A9.6 9.6 0 1 1 36.06 18.5L34.37 22.12A5.6 5.6 0 1 0 37.08 24.83Z"), fill = SolidColor(Peacock))
            addPath(addPathNodes("M22.4 41.6L41.6 41.6L41.6 44.8L22.4 44.8Z"), fill = SolidColor(Peacock))
        }.build()
    }
}
