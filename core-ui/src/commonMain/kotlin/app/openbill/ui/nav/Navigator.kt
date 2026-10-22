package app.openbill.ui.nav

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
    data class InvoiceEditor(val voucherId: String?) : Route
    data class InvoiceDetail(val voucherId: String) : Route
    data object BusinessEdit : Route

    /** Stable string form, used as the saved-state key and to survive process death. */
    val key: String
        get() = when (this) {
            is Home -> "home:${tab.name}"
            is PartyEdit -> "party:${partyId.orEmpty()}"
            is ItemEdit -> "item:${itemId.orEmpty()}"
            is InvoiceEditor -> "editor:${voucherId.orEmpty()}"
            is InvoiceDetail -> "detail:$voucherId"
            BusinessEdit -> "business:"
        }

    companion object {
        fun fromKey(key: String): Route? {
            val kind = key.substringBefore(':')
            val arg = key.substringAfter(':', "")
            return when (kind) {
                "home" -> Tab.entries.firstOrNull { it.name == arg }?.let { Home(it) }
                "party" -> PartyEdit(arg.ifEmpty { null })
                "item" -> ItemEdit(arg.ifEmpty { null })
                "editor" -> InvoiceEditor(arg.ifEmpty { null })
                "detail" -> arg.takeIf { it.isNotEmpty() }?.let { InvoiceDetail(it) }
                "business" -> BusinessEdit
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
