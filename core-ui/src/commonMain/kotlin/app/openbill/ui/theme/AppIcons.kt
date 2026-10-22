package app.openbill.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * OpenBill's own line icons for the navigation destinations (invoice, parties, items), drawn on
 * the 24 dp Material keyline grid with a 2 dp round stroke so they sit with material-icons-core.
 * Generic actions use material-icons-core (Icons.Outlined / Icons.Filled).
 */
object AppIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    val Invoice = icon("invoice") {
        moveTo(6f, 3f); lineTo(18f, 3f); lineTo(18f, 21f); lineTo(15f, 19f); lineTo(12f, 21f)
        lineTo(9f, 19f); lineTo(6f, 21f); close()
        moveTo(9f, 8f); lineTo(15f, 8f)
        moveTo(9f, 12f); lineTo(15f, 12f)
    }
    val Parties = icon("parties") {
        moveTo(12f, 4f); arcToRelative(3.5f, 3.5f, 0f, true, true, 0f, 7f)
        arcToRelative(3.5f, 3.5f, 0f, true, true, 0f, -7f)
        moveTo(5f, 20f); curveTo(5f, 15f, 19f, 15f, 19f, 20f)
    }
    val Items = icon("items") {
        moveTo(12f, 3f); lineTo(20f, 7f); lineTo(20f, 17f); lineTo(12f, 21f); lineTo(4f, 17f)
        lineTo(4f, 7f); close()
        moveTo(4f, 7f); lineTo(12f, 11f); lineTo(20f, 7f)
        moveTo(12f, 11f); lineTo(12f, 21f)
    }
    val More = icon("more") {
        moveTo(5f, 7f); lineTo(19f, 7f)
        moveTo(5f, 12f); lineTo(19f, 12f)
        moveTo(5f, 17f); lineTo(19f, 17f)
    }
    val Add = icon("add") {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }
    val Back = icon("back") {
        moveTo(19f, 12f); lineTo(5f, 12f)
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
    }
    val Close = icon("close") {
        moveTo(6f, 6f); lineTo(18f, 18f)
        moveTo(18f, 6f); lineTo(6f, 18f)
    }
    val Share = icon("share") {
        moveTo(12f, 15f); lineTo(12f, 3f)
        moveTo(7f, 8f); lineTo(12f, 3f); lineTo(17f, 8f)
        moveTo(5f, 13f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 13f)
    }
    val Search = icon("search") {
        moveTo(10.5f, 4f); arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, 13f)
        arcToRelative(6.5f, 6.5f, 0f, true, true, 0f, -13f)
        moveTo(15.5f, 15.5f); lineTo(20f, 20f)
    }
    val Delete = icon("delete") {
        moveTo(5f, 7f); lineTo(19f, 7f)
        moveTo(10f, 7f); lineTo(10f, 4f); lineTo(14f, 4f); lineTo(14f, 7f)
        moveTo(7f, 7f); lineTo(8f, 20f); lineTo(16f, 20f); lineTo(17f, 7f)
    }
}
