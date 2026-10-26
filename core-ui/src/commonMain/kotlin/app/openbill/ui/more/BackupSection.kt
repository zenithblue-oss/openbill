package app.openbill.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import app.openbill.ui.components.FormField
import app.openbill.ui.theme.Spacing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Create
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.SectionHeader
import kotlinx.coroutines.launch

/** What a verified backup contains, shown before restoring. [handle] is platform-private. */
class BackupSummary(
    val handle: Any,
    val firms: List<String>,
    val createdAt: String,
    val appVersion: String,
    val counts: Map<String, Long>,
)

/** Backup and restore through the platform's file dialogs (SAF on Android, AWT on desktop). */
interface BackupService {
    /** Asks where to save, then writes a verified archive. A message for the user; null if cancelled. */
    suspend fun createBackup(): String?

    /** Asks for an archive and verifies it; null if cancelled. Throws with a user-facing message if invalid. */
    suspend fun pickBackup(): BackupSummary?

    /** Replaces all local data with the archive (keeping a safety copy), then restarts or closes the app. */
    suspend fun restore(summary: BackupSummary)
}

/** Provided by each app shell; null hides the section (e.g. in previews). */
val LocalBackupService = staticCompositionLocalOf<BackupService?> { null }

@Composable
fun BackupSection(listColors: ListItemColors) {
    val service = LocalBackupService.current ?: return
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<BackupSummary?>(null) }

    fun run(block: suspend () -> String?) {
        busy = true
        scope.launch {
            val msg = runCatching { block() }.getOrElse { it.message ?: "Something went wrong" }
            busy = false
            msg?.let { messages?.show(it) }
        }
    }

    SectionHeader("Backup and restore")
    ListItem(
        headlineContent = { Text("Create backup") },
        supportingContent = { Text("Save all businesses, invoices and logos to a single file") },
        leadingContent = { Icon(Icons.Outlined.Create, contentDescription = null) },
        colors = listColors,
        modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { run { service.createBackup() } },
    )
    ListItem(
        headlineContent = { Text("Restore from backup") },
        supportingContent = { Text("Replace the data on this device with a backup file") },
        leadingContent = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
        colors = listColors,
        modifier = Modifier.clickable(enabled = !busy, role = Role.Button) {
            run { service.pickBackup()?.let { pending = it }; null }
        },
    )

    pending?.let { summary ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Restore this backup?") },
            text = {
                Text(
                    "Business: ${summary.firms.joinToString().ifBlank { "none" }}\n" +
                        "Created: ${summary.createdAt.take(19).replace('T', ' ')} UTC (OpenBill ${summary.appVersion})\n" +
                        "Invoices and vouchers: ${summary.counts["vouchers"] ?: 0}, parties: ${summary.counts["parties"] ?: 0}, items: ${summary.counts["items"] ?: 0}\n\n" +
                        "All data on this device will be replaced. A safety copy of the current data is kept. OpenBill restarts afterwards.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    run { service.restore(summary); null }
                }) { Text("Replace data") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Deletes the active business: the user types its name, a backup is saved first (cancelling the
 * save dialog cancels the deletion), then the business is removed from the app.
 */
@Composable
fun DeleteFirmItem(firmName: String, listColors: ListItemColors, delete: suspend () -> Unit) {
    val service = LocalBackupService.current ?: return
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    var open by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text("Delete this business") },
        supportingContent = { Text("Backs up all data first, then removes $firmName from this device") },
        leadingContent = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        colors = listColors,
        modifier = Modifier.clickable(role = Role.Button) { typed = ""; open = true },
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { if (!busy) open = false },
        title = { Text("Delete $firmName?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text("You will be asked where to save a backup first. Type the business name to confirm.")
                FormField("Business name", typed, { typed = it })
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && typed.trim() == firmName.trim(), onClick = {
                busy = true
                scope.launch {
                    val msg = runCatching {
                        if (service.createBackup() == null) "Backup cancelled; nothing was deleted" else { delete(); null }
                    }.getOrElse { "Nothing was deleted: ${it.message}" }
                    busy = false
                    open = false
                    msg?.let { messages?.show(it) }
                }
            }) { Text("Back up and delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("Cancel") } },
    )
}
