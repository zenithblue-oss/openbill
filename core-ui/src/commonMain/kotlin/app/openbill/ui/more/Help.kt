package app.openbill.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.openbill.ui.AppContainer
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.theme.Spacing
import app.openbill.ui.settings.HELP
import kotlinx.coroutines.launch

internal const val ISSUES_URL = "https://github.com/zenithblue-oss/openbill/issues"

/** docs/help.md split into ("## " heading, body) topics; the preamble before the first heading is dropped. */
internal fun helpTopics(markdown: String = HELP): List<Pair<String, String>> =
    markdown.split(Regex("(?m)^## ")).drop(1).map { section ->
        section.substringBefore('\n').trim() to reflow(section.substringAfter('\n', ""))
    }

/** Undoes the source's hard wraps: a line that does not start a list item joins the line above; "- " becomes a bullet. */
private fun reflow(body: String): String = buildString {
    var previousBlank = true
    body.trim().lines().forEach { raw ->
        val line = raw.trim()
        val startsItem = line.startsWith("- ") || Regex("^\\d+\\. ").containsMatchIn(line)
        when {
            line.isEmpty() -> { append("\n\n"); previousBlank = true; return@forEach }
            previousBlank -> {}
            startsItem -> append('\n')
            else -> append(' ')
        }
        append(if (line.startsWith("- ")) "• " + line.drop(2) else line)
        previousBlank = false
    }
}.replace(Regex("\n{3,}"), "\n\n").trim()

/**
 * Plain-text diagnostics: app and device versions, device-local switches and recent error
 * messages. No business data (names, amounts, parties, items) is read; the user sees it all first.
 */
internal fun diagnosticsText(container: AppContainer): String {
    val p = container.platform
    val look = container.appearance.settings.value
    val inbox = container.inbox
    val tz = container.timeZone
    return buildString {
        appendLine("OpenBill diagnostics")
        appendLine("App version: ${p.appVersion}${if (p.isDebugBuild) " (debug)" else ""}")
        appendLine("Device: ${p.deviceInfo}")
        appendLine("Time zone: ${tz.id}")
        appendLine()
        appendLine("Settings on this device")
        appendLine("Theme: ${look.themeMode.name.lowercase()}, device colours ${onOff(look.useDeviceColors)}, reduce motion ${onOff(look.reduceMotion)}")
        appendLine("App lock: ${onOff(container.appLock.isEnabled)}")
        appendLine("Update check: ${onOff(container.updates.enabled)}")
        appendLine("Notifications off: ${inbox.disabled().joinToString { it.name.lowercase() }.ifEmpty { "none" }}")
        appendLine("Backup reminder: ${inbox.backupReminderDays.let { if (it == 0) "never" else "after $it days" }}")
        appendLine("Last backup: ${inbox.lastBackup?.toString() ?: "never on this device"}")
        appendLine()
        val errors = inbox.errors()
        appendLine("Recent errors (${errors.size})")
        if (errors.isEmpty()) appendLine("none")
        errors.forEach { (at, message) -> appendLine("$at  $message") }
    }.trimEnd()
}

private fun onOff(b: Boolean) = if (b) "on" else "off"

/** About rows: offline help, contact support and the diagnostics export (previewed before sharing). */
@Composable
internal fun HelpItems(container: AppContainer, colors: ListItemColors) {
    var dialog by remember { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current
    ListItem(
        headlineContent = { Text("Help") },
        supportingContent = { Text("How to use OpenBill; works offline") },
        leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = colors,
        modifier = Modifier.clickable(role = Role.Button) { dialog = "help" },
    )
    ListItem(
        headlineContent = { Text("Contact support") },
        supportingContent = { Text("Report a problem or ask a question on GitHub; opens in your browser") },
        leadingContent = { Icon(Icons.Outlined.Email, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = colors,
        modifier = Modifier.clickable(role = Role.Button) { runCatching { uri.openUri(ISSUES_URL) } },
    )
    ListItem(
        headlineContent = { Text("Diagnostics") },
        supportingContent = { Text("App version, device and recent errors for a support request. No business data; you see it before sharing.") },
        leadingContent = { Icon(Icons.Outlined.Build, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        colors = colors,
        modifier = Modifier.clickable(role = Role.Button) { dialog = "diag" },
    )
    when (dialog) {
        "help" -> HelpDialog { dialog = null }
        "diag" -> DiagnosticsDialog(container) { dialog = null }
    }
}

@Composable
private fun HelpDialog(onClose: () -> Unit) {
    val topics = remember { helpTopics() }
    var open by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Help") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                topics.forEach { (title, body) ->
                    val expanded = open == title
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse" else "Expand") {
                            open = if (expanded) null else title
                        }.padding(vertical = Spacing.md),
                    ) {
                        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Icon(if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    if (expanded) Text(body, Modifier.padding(bottom = Spacing.md), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun DiagnosticsDialog(container: AppContainer, onClose: () -> Unit) {
    val text = remember { diagnosticsText(container) }
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    fun run(block: suspend () -> String?) {
        scope.launch {
            val msg = runCatching { block() }.getOrElse { it.message ?: "Could not share the diagnostics" }
            msg?.let { messages?.show(it) }
            onClose()
        }
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Diagnostics") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("This is everything that will be shared. It has no business names, amounts or customer details.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(text, Modifier.padding(top = Spacing.sm), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { run { container.platform.shareText("OpenBill diagnostics", text) } }) { Text("Share") } },
        dismissButton = {
            Row {
                TextButton(onClick = { run { container.platform.saveTextFile("openbill-diagnostics.txt", text) } }) { Text("Save") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}
