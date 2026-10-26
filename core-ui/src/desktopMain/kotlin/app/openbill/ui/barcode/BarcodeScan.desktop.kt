package app.openbill.ui.barcode

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop scanners are keyboard-wedge (HID) devices that type into the focused field. */
actual val platformHasCameraScanner: Boolean = false

@Composable
actual fun BarcodeScanButton(onScanned: (String) -> Unit, modifier: Modifier) = Unit
