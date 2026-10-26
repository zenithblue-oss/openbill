package app.openbill.ui.barcode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** True where [BarcodeScanButton] opens a camera scanner (Android); desktop relies on HID scanners. */
expect val platformHasCameraScanner: Boolean

/** Icon button that scans one barcode with the camera and passes its text; renders nothing without a camera scanner. */
@Composable
expect fun BarcodeScanButton(onScanned: (String) -> Unit, modifier: Modifier = Modifier)

/** Barcode glyph (icons-core has none); 24 dp, tinted by Icon. */
val BarcodeIcon: ImageVector by lazy {
    ImageVector.Builder("Barcode", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
        // (x, width) of each bar, between y 5 and 19.
        listOf(2f to 2f, 5f to 1f, 7f to 2f, 10f to 1f, 12f to 3f, 16f to 1f, 18f to 1f, 20f to 2f).forEach { (x, w) ->
            moveTo(x, 5f)
            horizontalLineToRelative(w)
            verticalLineTo(19f)
            horizontalLineTo(x)
            close()
        }
    }.build()
}
