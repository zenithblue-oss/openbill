package app.openbill.ui.nav

import app.openbill.core.domain.model.VoucherType

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

enum class Tab(val label: String) {
    INVOICES("Invoices"),
    PARTIES("Parties"),
    ITEMS("Items"),
    MONEY("Money"),
    MORE("More"),
}

/** Route contracts shared by all platforms; each platform shell supplies back handling. */
sealed interface Route {
    data class Home(val tab: Tab) : Route
    data class PartyEdit(val partyId: String?) : Route
    data class ItemEdit(val itemId: String?) : Route
    /** [type] and [returnOf] apply to new documents (purchase bill, return of a posted one). */
    data class InvoiceEditor(
        val voucherId: String?,
        val type: VoucherType = VoucherType.SALE,
        val returnOf: String? = null,
    ) : Route
    data object Purchases : Route
    /** Payment in/out, expense or contra; [partyId]/[invoiceId] prefill a payment. */
    data class MoneyEditor(val type: VoucherType, val partyId: String? = null, val invoiceId: String? = null) : Route
    data class MoneyDetail(val voucherId: String) : Route
    data class AccountLedger(val accountId: String) : Route
    data class Outstanding(val receivable: Boolean) : Route
    data class PartyLedger(val partyId: String) : Route
    /** Quotations, proformas or delivery challans. */
    data class Documents(val type: VoucherType) : Route
    data class StockSummary(val lowOnly: Boolean = false) : Route
    data class ItemStock(val itemId: String) : Route
    data class StockEntry(val itemId: String?, val transfer: Boolean) : Route
    data object Godowns : Route
    data class BarcodeLabels(val itemIds: List<String>) : Route
    data class InvoiceDetail(val voucherId: String) : Route
    data object BusinessEdit : Route
    data object InvoiceTemplates : Route
    data class InvoicePdf(val voucherId: String) : Route

    data object Reports : Route
    /** A report by [ReportKind] key for an ISO date range; [partyId] for a party statement. */
    data class Report(val report: String, val from: String, val to: String, val partyId: String? = null) : Route
    data object AuditLog : Route
    data object BooksLock : Route

    /** The introduction, replayed from More. */
    data object Introduction : Route

    /** Stable string form, used as the saved-state key and to survive process death. */
    val key: String
        get() = when (this) {
            is Home -> "home:${tab.name}"
            is PartyEdit -> "party:${partyId.orEmpty()}"
            is ItemEdit -> "item:${itemId.orEmpty()}"
            is InvoiceEditor -> "editor:${voucherId.orEmpty()}" +
                if (type == VoucherType.SALE && returnOf == null) "" else "|${type.name}|${returnOf.orEmpty()}"
            Purchases -> "purchases:"
            is MoneyEditor -> "money:${type.name}|${partyId.orEmpty()}|${invoiceId.orEmpty()}"
            is MoneyDetail -> "moneydetail:$voucherId"
            is AccountLedger -> "account:$accountId"
            is Outstanding -> "outstanding:${if (receivable) "r" else "p"}"
            is PartyLedger -> "ledger:$partyId"
            is Documents -> "docs:${type.name}"
            is StockSummary -> "stock:${if (lowOnly) "low" else ""}"
            is ItemStock -> "itemstock:$itemId"
            is StockEntry -> "stockentry:${if (transfer) "t" else "a"}|${itemId.orEmpty()}"
            Godowns -> "godowns:"
            is BarcodeLabels -> "labels:${itemIds.joinToString(",")}"
            is InvoiceDetail -> "detail:$voucherId"
            BusinessEdit -> "business:"
            InvoiceTemplates -> "templates:"
            is InvoicePdf -> "pdf:$voucherId"
            Introduction -> "intro:"
            Reports -> "reports:"
            is Report -> "report:$report|$from|$to|${partyId.orEmpty()}"
            AuditLog -> "audit:"
            BooksLock -> "books:"
        }

    companion object {
        fun fromKey(key: String): Route? {
            val kind = key.substringBefore(':')
            val arg = key.substringAfter(':', "")
            return when (kind) {
                "home" -> Tab.entries.firstOrNull { it.name == arg }?.let { Home(it) }
                "party" -> PartyEdit(arg.ifEmpty { null })
                "item" -> ItemEdit(arg.ifEmpty { null })
                "editor" -> arg.split('|').let { p ->
                    InvoiceEditor(
                        p[0].ifEmpty { null },
                        p.getOrNull(1)?.let { t -> VoucherType.entries.firstOrNull { it.name == t } } ?: VoucherType.SALE,
                        p.getOrNull(2)?.ifEmpty { null },
                    )
                }
                "purchases" -> Purchases
                "money" -> arg.split('|').let { p ->
                    VoucherType.entries.firstOrNull { it.name == p[0] }?.let {
                        MoneyEditor(it, p.getOrNull(1)?.ifEmpty { null }, p.getOrNull(2)?.ifEmpty { null })
                    }
                }
                "moneydetail" -> arg.takeIf { it.isNotEmpty() }?.let { MoneyDetail(it) }
                "account" -> arg.takeIf { it.isNotEmpty() }?.let { AccountLedger(it) }
                "outstanding" -> Outstanding(arg == "r")
                "ledger" -> arg.takeIf { it.isNotEmpty() }?.let { PartyLedger(it) }
                "docs" -> VoucherType.entries.firstOrNull { it.name == arg }?.let { Documents(it) }
                "stock" -> StockSummary(arg == "low")
                "itemstock" -> arg.takeIf { it.isNotEmpty() }?.let { ItemStock(it) }
                "stockentry" -> StockEntry(arg.substringAfter('|').ifEmpty { null }, arg.startsWith("t"))
                "godowns" -> Godowns
                "labels" -> BarcodeLabels(arg.split(',').filter { it.isNotEmpty() })
                "detail" -> arg.takeIf { it.isNotEmpty() }?.let { InvoiceDetail(it) }
                "business" -> BusinessEdit
                "templates" -> InvoiceTemplates
                "pdf" -> arg.takeIf { it.isNotEmpty() }?.let { InvoicePdf(it) }
                "intro" -> Introduction
                "reports" -> Reports
                "report" -> arg.split('|').takeIf { it.size >= 3 }?.let { p -> Report(p[0], p[1], p[2], p.getOrNull(3)?.ifEmpty { null }) }
                "audit" -> AuditLog
                "books" -> BooksLock
                else -> null
            }
        }
    }
}

class Navigator(initial: List<Route> = listOf(Route.Home(Tab.INVOICES))) {
    private val stack = mutableStateListOf<Route>().apply { addAll(initial.ifEmpty { listOf(Route.Home(Tab.INVOICES)) }) }

    /** Called with each route that leaves the back stack, so its saved UI state can be dropped. */
    var onRemoved: (Route) -> Unit = {}

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Back-stack depth of the current route; transitions compare depths to pick a direction. */
    val depth: Int get() = stack.size
    val routes: List<Route> get() = stack.toList()

    fun push(route: Route) {
        stack.add(route)
    }

    fun replace(route: Route) {
        val old = stack.removeAt(stack.lastIndex)
        stack.add(route)
        if (old != route) onRemoved(old)
    }

    fun pop() {
        if (stack.size > 1) onRemoved(stack.removeAt(stack.lastIndex))
    }

    fun selectTab(tab: Tab) {
        val old = stack.toList()
        stack.clear()
        stack.add(Route.Home(tab))
        old.filter { it != Route.Home(tab) }.forEach(onRemoved)
    }

    companion object {
        val Saver: Saver<Navigator, Any> = listSaver(
            save = { nav -> nav.routes.map { it.key } },
            restore = { keys -> Navigator(keys.mapNotNull { Route.fromKey(it) }) },
        )
    }
}

/** Window width buckets from the Material 3 window size class breakpoints. */
enum class WidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED;

    /** Navigation rail instead of a bottom bar. */
    val usesRail: Boolean get() = this != COMPACT

    companion object {
        fun of(widthDp: Float): WidthClass = when {
            widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            else -> EXPANDED
        }
    }
}
