package app.openbill.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * Third-party components shipped in the apps (runtime dependencies from gradle/libs.versions.toml
 * and the Compose plugin, plus bundled assets). Keep in sync when a dependency is added.
 */
internal val OPEN_SOURCE_LICENSES: List<Pair<String, String>> = listOf(
    "Kotlin standard library, kotlinx.coroutines, kotlinx-datetime, kotlinx.serialization (JetBrains)" to "Apache-2.0",
    "Compose Multiplatform, Material 3 and Material icons (JetBrains, Google)" to "Apache-2.0",
    "AndroidX Activity, Room, SQLite, CameraX, WorkManager (Google)" to "Apache-2.0",
    "Apache PDFBox (desktop PDF output and printing)" to "Apache-2.0",
    "ZXing core (QR and barcodes)" to "Apache-2.0",
    "Clikt (command-line interface)" to "Apache-2.0",
    "Noto Sans fonts (The Noto Project Authors)" to "OFL-1.1",
    "HSN/SAC code list (CBIC, Government of India; see NOTICE)" to "Public classification data",
)

@Composable
internal fun LicensesItem(appVersion: String, colors: ListItemColors) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Licenses and app info") },
        supportingContent = { Text("OpenBill is Apache-2.0; open-source components it uses") },
        leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
        colors = colors,
        modifier = Modifier.clickable(role = Role.Button) { open = true },
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("OpenBill $appVersion") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Licensed under the Apache License 2.0. Source: https://github.com/zenithblue-oss/openbill")
                OPEN_SOURCE_LICENSES.forEach { (name, license) -> ListItem(headlineContent = { Text(name) }, supportingContent = { Text(license) }) }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
    )
}
