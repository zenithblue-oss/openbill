package app.openbill.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.openbill.core.domain.model.AppUser
import app.openbill.core.domain.model.AuditEvent
import app.openbill.core.domain.model.Role
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.FormField
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.pageGutter
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Owner-only: staff who share this install, each with a role and a PIN of their own. Users are
 * deactivated, never deleted, so the activity log and by-user reports keep their names.
 */
@Composable
fun UsersScreen(container: AppContainer, session: Session, onBack: () -> Unit) {
    val users by remember { container.repositories.users.observeUsers() }.collectAsState(initial = emptyList())
    val lockOn = container.appLock.isEnabled
    var editing by remember { mutableStateOf<AppUser?>(null) }
    var adding by remember { mutableStateOf(false) }
    Scaffold(topBar = { AppTopBar("Users and roles", onBack = onBack) }, snackbarHost = { AppSnackbarHost() }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(bottom = Spacing.xl)) {
                item {
                    Column(Modifier.padding(horizontal = pageGutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            if (lockOn) {
                                "Everyone signs in with their own PIN when OpenBill opens or locks. The owner's PIN is the app lock PIN."
                            } else {
                                "Turn on App lock in More first: its PIN becomes the owner's PIN, and staff then sign in with their own."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        FilledTonalButton(enabled = lockOn, onClick = { adding = true }) { Text("Add user") }
                        SectionHeader("Users")
                    }
                }
                items(users, key = { it.id }) { u ->
                    ListItem(
                        headlineContent = { Text(u.name) },
                        supportingContent = { Text("${u.role.label}${if (u.active) "" else " · deactivated"}") },
                        modifier = Modifier.clickable(role = SemanticsRole.Button, onClickLabel = "Edit") { editing = u },
                    )
                }
                item {
                    var managersSettings by remember { mutableStateOf(container.appLock.managersChangeSettings) }
                    val scope = rememberCoroutineScope()
                    ListItem(
                        headlineContent = { Text("Managers can change business settings") },
                        supportingContent = {
                            Text("Default godown, negative stock, numbering, templates and other settings. Users, backups, app lock and adding or deleting a business stay with the owner.")
                        },
                        trailingContent = { Switch(checked = managersSettings, onCheckedChange = null) },
                        modifier = Modifier.toggleable(managersSettings, role = SemanticsRole.Switch) { on ->
                            container.appLock.managersChangeSettings = on
                            managersSettings = on
                            scope.launch {
                                runCatching {
                                    container.repositories.audit.record(
                                        AuditEvent(container.newId(), session.firm.id, "settings", "managersChangeSettings", action = "settings.managers_change_settings", detailsJson = "{\"on\":$on}", createdAt = container.now()),
                                    )
                                }
                            }
                        },
                    )
                }
                item {
                    Column(Modifier.padding(horizontal = pageGutter), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        SectionHeader("What each role can do")
                        Role.entries.forEach { r -> Text("${r.label}: ${r.summary}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        UserDialog(container, session, editing, users) { adding = false; editing = null }
    }
}

@Composable
private fun UserDialog(container: AppContainer, session: Session, existing: AppUser?, users: List<AppUser>, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val owner = existing?.isOwner == true
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var role by remember { mutableStateOf(existing?.role ?: Role.CASHIER) }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun digits(s: String) = s.filter(Char::isDigit).take(AppLockController.PIN_MAX)
    val nameTaken = users.any { it.id != existing?.id && it.name.trim().equals(name.trim(), ignoreCase = true) }
    val newPinOk = AppLockController.pinError(pin) == null && pin == confirm
    val pinOk = if (existing == null) newPinOk else pin.isEmpty() || newPinOk
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (existing == null) "Add user" else "Edit ${existing.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                FormField("Name", name, { name = it.filterNot(Char::isISOControl).take(60); error = null }, error = if (nameTaken) "Someone already has this name" else error)
                if (!owner) {
                    Column(Modifier.selectableGroup()) {
                        Role.entries.filter { it != Role.OWNER }.forEach { r ->
                            Row(
                                Modifier.fillMaxWidth().selectable(r == role, role = SemanticsRole.RadioButton) { role = r },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = r == role, onClick = null)
                                Column(Modifier.padding(start = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs)) {
                                    Text(r.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(r.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    val hidden = PasswordVisualTransformation()
                    FormField(
                        if (existing == null) "PIN" else "New PIN (leave empty to keep)", pin, { pin = digits(it) },
                        keyboardType = KeyboardType.NumberPassword, visualTransformation = hidden,
                        supporting = AppLockController.PIN_HINT,
                    )
                    FormField(
                        "Repeat PIN", confirm, { confirm = digits(it) }, keyboardType = KeyboardType.NumberPassword, visualTransformation = hidden,
                        error = "PINs do not match".takeIf { confirm.isNotEmpty() && confirm.length >= pin.length && confirm != pin },
                    )
                    if (existing != null) {
                        Row(
                            Modifier.fillMaxWidth().toggleable(active, role = SemanticsRole.Switch) { active = it },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Active", style = MaterialTheme.typography.bodyLarge)
                                Text("A deactivated user cannot sign in; their history stays", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = active, onCheckedChange = null)
                        }
                    }
                } else {
                    Text("The owner signs in with the app lock PIN and always has every permission.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !nameTaken && (owner || pinOk), onClick = {
                scope.launch {
                    val now = container.now()
                    val user = AppUser(
                        id = existing?.id ?: container.newId(),
                        name = name.trim(),
                        role = if (owner) Role.OWNER else role,
                        active = owner || active,
                        pinHash = if (owner) null else if (pin.isNotEmpty()) AppLockController.hashPin(pin) else existing?.pinHash,
                        createdAt = existing?.createdAt ?: now,
                    )
                    runCatching {
                        container.repositories.users.saveUser(user)
                        val action = when {
                            existing == null -> "user.added"
                            existing.active && !user.active -> "user.deactivated"
                            !existing.active && user.active -> "user.reactivated"
                            else -> "user.updated"
                        }
                        val details = "{\"name\":\"${user.name.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"role\":\"${user.role.name.lowercase()}\"}"
                        runCatching { container.repositories.audit.record(AuditEvent(container.newId(), session.firm.id, "user", user.id, action = action, detailsJson = details, createdAt = now)) }
                    }.onSuccess {
                        messages?.show(if (existing == null) "${user.name} added" else "Saved")
                        close()
                    }.onFailure { error = it.message ?: "Could not save" }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}
