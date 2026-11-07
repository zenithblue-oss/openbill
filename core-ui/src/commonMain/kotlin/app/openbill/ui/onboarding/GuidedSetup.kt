package app.openbill.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.PartyType
import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.pageGutter
import app.openbill.ui.nav.Route
import app.openbill.ui.nav.Tab
import app.openbill.ui.theme.Spacing
import kotlinx.datetime.LocalDate

/** One step of the getting-started checklist and where it is done. */
data class SetupStep(val title: String, val hint: String, val done: Boolean, val route: Route, val permission: Permission?)

/** The checklist: business details, first item, first party, first posted invoice, first backup. */
fun setupSteps(firm: Firm, hasItem: Boolean, hasParty: Boolean, hasInvoice: Boolean, backedUp: Boolean): List<SetupStep> = listOf(
    SetupStep(
        "Complete the business profile", "Address, city and phone print on every invoice",
        firm.addressLine1.isNotBlank() && firm.city.isNotBlank() && !firm.phone.isNullOrBlank(), Route.BusinessEdit, Permission.SETTINGS,
    ),
    SetupStep("Add your first item", "A product or service with its price and GST rate", hasItem, Route.ItemEdit(null), Permission.MANAGE_MASTERS),
    SetupStep("Add your first party", "A customer or supplier you bill", hasParty, Route.PartyEdit(null), Permission.EDIT_PARTIES),
    SetupStep("Post your first invoice", "Create a GST invoice and post it", hasInvoice, Route.InvoiceEditor(null), Permission.CREATE_VOUCHERS),
    SetupStep("Make a backup", "Save everything to a file you keep somewhere safe", backedUp, Route.Home(Tab.MORE), Permission.BACKUP_RESTORE),
)

/**
 * Dashboard card with the getting-started checklist; tapping a step opens the screen that does
 * it. Hidden once every step is done or after "Hide" (per business, device setting).
 */
@Composable
fun GuidedSetupCard(container: AppContainer, session: Session, onOpen: (Route) -> Unit) {
    val firmId = session.firm.id
    val store = container.platform.settingsStore
    val hideKey = "guide.hidden.$firmId"
    var hidden by remember(firmId) { mutableStateOf(runCatching { store.getString(hideKey) == "true" }.getOrDefault(false)) }
    if (hidden) return
    val repos = container.repositories
    val items by remember(firmId) { repos.catalog.observeItems(firmId) }.collectAsState(initial = null)
    val parties by remember(firmId) { repos.parties.observePartyPage(firmId, PartyType.entries.toSet(), "", 1) }.collectAsState(initial = null)
    val sales by remember(firmId) { repos.vouchers.observeVoucherStats(firmId, setOf(VoucherType.SALE), LocalDate(2000, 1, 1), LocalDate(2099, 12, 31)) }.collectAsState(initial = null)
    val inboxVersion by container.inbox.version.collectAsState()
    val backedUp = remember(inboxVersion) { container.inbox.lastBackup != null }
    if (items == null || parties == null || sales == null) return
    val steps = setupSteps(
        session.firm, items!!.any { it.deletedAt == null }, parties!!.isNotEmpty(), (sales!![VoucherStatus.POSTED]?.count ?: 0) > 0, backedUp,
    )
    val done = steps.count { it.done }
    if (done == steps.size) return
    Card(Modifier.fillMaxWidth().padding(horizontal = pageGutter, vertical = Spacing.xs)) {
        Column(Modifier.padding(vertical = Spacing.sm)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Get started", style = MaterialTheme.typography.titleMedium)
                    Text("$done of ${steps.size} done", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = {
                    hidden = true
                    runCatching { store.putString(hideKey, "true") }
                }) { Text("Hide") }
            }
            LinearProgressIndicator(progress = { done / steps.size.toFloat() }, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs))
            steps.forEach { s ->
                val allowed = s.permission == null || Access.can(s.permission)
                ListItem(
                    leadingContent = {
                        Icon(
                            if (s.done) Icons.Filled.CheckCircle else Icons.Outlined.Info,
                            contentDescription = if (s.done) "Done" else "To do",
                            tint = if (s.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    headlineContent = { Text(s.title, textDecoration = if (s.done) TextDecoration.LineThrough else null) },
                    supportingContent = { Text(if (allowed || s.done) s.hint else "${s.hint} (ask the owner)") },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
                    modifier = Modifier.clickable(enabled = !s.done && allowed, role = Role.Button) { onOpen(s.route) },
                )
            }
        }
    }
}
