package app.openbill.ui.notify

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import app.openbill.core.domain.model.Access
import app.openbill.core.domain.model.Permission
import app.openbill.core.domain.report.signedOpening
import app.openbill.core.domain.service.PartyAgeing
import app.openbill.core.domain.service.StockValuation
import app.openbill.core.model.Money
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.EmptyState
import app.openbill.ui.components.LoadingState
import app.openbill.ui.format.Fmt
import app.openbill.ui.nav.Route
import app.openbill.ui.nav.Tab
import app.openbill.ui.settings.SettingsStore
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Kinds of in-app alert; each can be switched off in More > Notifications. */
enum class InboxCategory(val label: String, val description: String) {
    LOW_STOCK("Low stock", "Items at or below their low-stock level"),
    NEAR_EXPIRY("Near expiry", "Batches expiring within the business's near-expiry window"),
    OVERDUE("Overdue payments", "Customers past their credit days"),
    BACKUP("Backups", "Backup failures, and a reminder when no backup was made recently"),
    RECURRING("Recurring invoices", "Invoices made by a schedule, and schedules that failed"),
}

/** One inbox row; [key] identifies it for read state (a changed amount is a new key, so unread again). */
data class InboxEntry(
    val key: String,
    val category: InboxCategory,
    val title: String,
    val body: String,
    val route: Route?,
    val at: Instant? = null,
)

/** A stored event (backup failure, invoices made), as opposed to a live condition such as low stock. */
data class InboxEvent(val id: String, val firmId: String?, val category: InboxCategory, val at: Instant, val title: String, val body: String, val routeKey: String?)

/**
 * Device-local inbox state (settings store, never the database): stored events, which entries
 * were read, categories switched off, the last successful backup and recent error messages for
 * the diagnostics export. Every change bumps [version] so screens recompute.
 */
class InboxController(private val store: SettingsStore) {
    val version = MutableStateFlow(0)

    private fun get(key: String) = runCatching { store.getString(key) }.getOrNull()
    private fun put(key: String, value: String) {
        runCatching { store.putString(key, value) }
        version.value++
    }

    private fun clean(s: String) = s.replace(SEP, ' ').replace('\n', ' ')

    fun events(): List<InboxEvent> = get(KEY_EVENTS).orEmpty().lines().mapNotNull { line ->
        val p = line.split(SEP)
        if (p.size < 7) return@mapNotNull null
        val cat = InboxCategory.entries.firstOrNull { it.name == p[2] } ?: return@mapNotNull null
        InboxEvent(p[0], p[1].ifEmpty { null }, cat, Instant.fromEpochMilliseconds(p[3].toLongOrNull() ?: 0), p[4], p[5], p[6].ifEmpty { null })
    }

    fun addEvent(firmId: String?, category: InboxCategory, at: Instant, title: String, body: String, route: Route? = null) {
        val line = listOf("ev${at.toEpochMilliseconds()}-${events().size}", firmId.orEmpty(), category.name, at.toEpochMilliseconds().toString(), clean(title), clean(body), route?.key.orEmpty())
            .joinToString(SEP.toString())
        // Newest first; the oldest drop off past the cap.
        put(KEY_EVENTS, (listOf(line) + get(KEY_EVENTS).orEmpty().lines().filter { it.isNotBlank() }).take(MAX_EVENTS).joinToString("\n"))
    }

    fun readKeys(firmId: String): Set<String> = get("$KEY_READ.$firmId").orEmpty().lines().filter { it.isNotBlank() }.toSet()

    fun markRead(firmId: String, keys: Collection<String>) {
        val now = readKeys(firmId)
        if (now.containsAll(keys)) return
        put("$KEY_READ.$firmId", (keys.filterNot { it in now } + now).take(MAX_READ).joinToString("\n"))
    }

    fun disabled(): Set<InboxCategory> = get(KEY_OFF).orEmpty().split(',').mapNotNull { n -> InboxCategory.entries.firstOrNull { it.name == n } }.toSet()

    fun setEnabled(category: InboxCategory, enabled: Boolean) {
        val next = if (enabled) disabled() - category else disabled() + category
        put(KEY_OFF, next.joinToString(",") { it.name })
    }

    /** Days without a backup before the inbox reminds; 0 = never. */
    var backupReminderDays: Int
        get() = get(KEY_BACKUP_DAYS)?.toIntOrNull() ?: 7
        set(value) = put(KEY_BACKUP_DAYS, value.toString())

    var lastBackup: Instant?
        get() = get(KEY_LAST_BACKUP)?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) }
        set(value) = put(KEY_LAST_BACKUP, value?.toEpochMilliseconds()?.toString().orEmpty())

    /** Recent error messages (no business data is added on purpose; callers pass only the message). */
    fun recordError(at: Instant, message: String) {
        val line = "${at.toEpochMilliseconds()}$SEP${clean(message).take(300)}"
        put(KEY_ERRORS, (listOf(line) + get(KEY_ERRORS).orEmpty().lines().filter { it.isNotBlank() }).take(MAX_ERRORS).joinToString("\n"))
    }

    fun errors(): List<Pair<Instant, String>> = get(KEY_ERRORS).orEmpty().lines().mapNotNull { l ->
        val ms = l.substringBefore(SEP).toLongOrNull() ?: return@mapNotNull null
        Instant.fromEpochMilliseconds(ms) to l.substringAfter(SEP)
    }

    companion object {
        private const val SEP = '\u001F'
        private const val KEY_EVENTS = "inbox.events"
        private const val KEY_READ = "inbox.read"
        private const val KEY_OFF = "inbox.off"
        private const val KEY_BACKUP_DAYS = "inbox.backupDays"
        private const val KEY_LAST_BACKUP = "backup.last"
        private const val KEY_ERRORS = "diag.errors"
        private const val MAX_EVENTS = 100
        private const val MAX_READ = 1000
        private const val MAX_ERRORS = 30
    }
}

/**
 * The inbox of [session]'s business: live conditions (low stock, near expiry, overdue parties,
 * backup reminder) plus stored events, newest events first, minus switched-off categories and
 * anything the signed-in user may not see.
 */
suspend fun inboxEntries(container: AppContainer, session: Session): List<InboxEntry> {
    val inbox = container.inbox
    val off = inbox.disabled()
    val repos = container.repositories
    val firmId = session.firm.id
    val today = container.today()
    val out = ArrayList<InboxEntry>()
    if (InboxCategory.LOW_STOCK !in off) {
        val items = repos.catalog.observeItems(firmId).first()
        StockValuation.summarize(items, repos.stock.observeStockPositions(firmId).first())
            .filter { it.isLow && it.item.deletedAt == null }
            .sortedBy { it.item.name.lowercase() }
            .forEach { s ->
                out += InboxEntry(
                    "low:${s.item.id}:${s.quantity.scaledUnits}", InboxCategory.LOW_STOCK, "${s.item.name} is low on stock",
                    "${Fmt.qty(s.quantity)} left; low-stock level ${s.item.lowStockLevel?.let(Fmt::qty)}", Route.ItemStock(s.item.id),
                )
            }
    }
    val days = session.settings.nearExpiryDays
    if (InboxCategory.NEAR_EXPIRY !in off && days > 0) {
        val names = repos.catalog.observeItems(firmId).first().associate { it.id to it.name }
        repos.stock.observeExpiringBatches(firmId, today.plus(DatePeriod(days = days))).first().forEach { (b, qty) ->
            val expiry = b.expiryDate ?: return@forEach
            out += InboxEntry(
                "exp:${b.id}:${if (expiry < today) "x" else "n"}", InboxCategory.NEAR_EXPIRY,
                "${names[b.itemId] ?: "Item"} batch ${b.batchNumber} ${if (expiry < today) "has expired" else "expires soon"}",
                "${Fmt.qty(qty)} in stock · expiry ${Fmt.date(expiry)}", Route.ItemStock(b.itemId),
            )
        }
    }
    if (InboxCategory.OVERDUE !in off && Access.can(Permission.VIEW_REPORTS)) {
        val parties = repos.parties.observeParties(firmId).first().filter { it.creditDays != null && it.deletedAt == null }
        if (parties.isNotEmpty()) {
            val byParty = repos.ledger.observePartyMovements(firmId).first().groupBy { it.partyId }
            parties.forEach { p ->
                val due = PartyAgeing.overdue(p.signedOpening, byParty[p.id].orEmpty(), today, p.creditDays!!)
                if (due > Money.ZERO) out += InboxEntry(
                    "due:${p.id}:${due.paise}", InboxCategory.OVERDUE, "${p.name} is overdue",
                    "${Fmt.inr(due)} past ${p.creditDays} days of credit", Route.PartyLedger(p.id),
                )
            }
        }
    }
    if (InboxCategory.BACKUP !in off && Access.can(Permission.BACKUP_RESTORE) && inbox.backupReminderDays > 0) {
        val last = inbox.lastBackup
        val lastDay = last?.toLocalDateTime(TimeZone.currentSystemDefault())?.date
        if (lastDay == null || lastDay.plus(DatePeriod(days = inbox.backupReminderDays)) <= today) {
            out += InboxEntry(
                "bk:${lastDay ?: "never"}:${today}", InboxCategory.BACKUP,
                if (lastDay == null) "No backup made on this device yet" else "No backup since ${Fmt.date(lastDay)}",
                "Back up from More > Backup and restore so a lost or broken device does not lose your books", Route.Home(Tab.MORE),
            )
        }
    }
    if (InboxCategory.RECURRING !in off) {
        repos.recurring.observeSchedules(firmId).first().filter { it.lastError != null && !it.paused }.forEach { s ->
            out += InboxEntry("rf:${s.id}:${s.lastError.hashCode()}", InboxCategory.RECURRING, "Schedule \"${s.name}\" could not make an invoice", s.lastError!!, Route.Recurring)
        }
    }
    val events = inbox.events().filter { (it.firmId == null || it.firmId == firmId) && it.category !in off }
        .filter { it.category != InboxCategory.BACKUP || Access.can(Permission.BACKUP_RESTORE) }
        .map { InboxEntry(it.id, it.category, it.title, it.body, it.routeKey?.let(Route::fromKey), it.at) }
    return events + out
}

/** Bell with the unread count, for the dashboard's top bar. */
@Composable
fun InboxButton(container: AppContainer, session: Session, onOpen: () -> Unit) {
    val version by container.inbox.version.collectAsState()
    var unread by remember { mutableStateOf(0) }
    LaunchedEffect(session.firm.id, session.settings, version) {
        val read = container.inbox.readKeys(session.firm.id)
        unread = runCatching { inboxEntries(container, session).count { it.key !in read } }.getOrDefault(0)
    }
    IconButton(onClick = onOpen, modifier = Modifier.semantics { contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications" }) {
        BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) } }) {
            Icon(Icons.Outlined.Notifications, contentDescription = null)
        }
    }
}

@Composable
fun InboxScreen(container: AppContainer, session: Session, onBack: () -> Unit, onOpen: (Route) -> Unit) {
    val version by container.inbox.version.collectAsState()
    var entries by remember { mutableStateOf<List<InboxEntry>?>(null) }
    LaunchedEffect(session.firm.id, session.settings, version) {
        entries = runCatching { inboxEntries(container, session) }.getOrDefault(emptyList())
    }
    val read = remember(version) { container.inbox.readKeys(session.firm.id) }
    val list = entries
    Scaffold(
        topBar = {
            AppTopBar("Notifications", onBack = onBack, actions = {
                if (!list.isNullOrEmpty() && list.any { it.key !in read }) {
                    TextButton(onClick = { container.inbox.markRead(session.firm.id, list.map { it.key }) }) { Text("Mark all read") }
                }
            })
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            when {
                list == null -> LoadingState()
                list.isEmpty() -> EmptyState(Icons.Outlined.Notifications, "All caught up", "Low stock, expiring batches, overdue customers, backups and recurring invoices show up here.")
                else -> LazyColumn {
                    items(list, key = { it.key }) { e ->
                        val unread = e.key !in read
                        ListItem(
                            overlineContent = {
                                Text(e.category.label + (e.at?.let { " · " + Fmt.date(it.toLocalDateTime(TimeZone.currentSystemDefault()).date) } ?: "") + if (unread) " · new" else "")
                            },
                            headlineContent = { Text(e.title, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal) },
                            supportingContent = { Text(e.body) },
                            trailingContent = if (unread) {
                                { TextButton(onClick = { container.inbox.markRead(session.firm.id, listOf(e.key)) }) { Text("Mark read") } }
                            } else null,
                            colors = ListItemDefaults.colors(containerColor = if (unread) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface),
                            modifier = Modifier.clickable(onClickLabel = if (e.route != null) "Open" else "Mark read") {
                                container.inbox.markRead(session.firm.id, listOf(e.key))
                                e.route?.let(onOpen)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** More > Notifications: one switch per category and the backup reminder interval. */
@Composable
fun NotificationPreferences(container: AppContainer, colors: androidx.compose.material3.ListItemColors) {
    val version by container.inbox.version.collectAsState()
    val off = remember(version) { container.inbox.disabled() }
    InboxCategory.entries.forEach { c ->
        app.openbill.ui.inventory.SwitchRow(c.label, c.description, c !in off, { container.inbox.setEnabled(c, it) })
    }
    val days = remember(version) { container.inbox.backupReminderDays }
    ListItem(
        headlineContent = { Text("Backup reminder") },
        supportingContent = { Text(if (days == 0) "Off" else "After $days days without a backup · tap to change") },
        colors = colors,
        modifier = Modifier.clickable(onClickLabel = "Change") {
            container.inbox.backupReminderDays = BACKUP_DAY_CHOICES[(BACKUP_DAY_CHOICES.indexOf(days) + 1) % BACKUP_DAY_CHOICES.size]
        },
    )
}

private val BACKUP_DAY_CHOICES = listOf(7, 14, 30, 1, 3, 0)
