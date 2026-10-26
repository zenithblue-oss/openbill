package app.openbill.ui.nav

import app.openbill.core.domain.model.VoucherType

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

enum class Tab(val label: String) {
    INVOICES("Invoices"),
    PARTIES("Parties"),
    ITEMS("Items"),
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
    data class StockSummary(val lowOnly: Boolean = false) : Route
    data class ItemStock(val itemId: String) : Route
    data class StockEntry(val itemId: String?, val transfer: Boolean) : Route
    data object Godowns : Route
    data class BarcodeLabels(val itemIds: List<String>) : Route
    data class InvoiceDetail(val voucherId: String) : Route
    data object BusinessEdit : Route
    data object InvoiceTemplates : Route
    data class InvoicePdf(val voucherId: String) : Route

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
