package app.openbill.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.openbill.ui.OpenBillApp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "OpenBill",
    ) {
        OpenBillApp()
    }
}
