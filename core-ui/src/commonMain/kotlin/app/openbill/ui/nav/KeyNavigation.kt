package app.openbill.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester

/**
 * Back handling for platforms without a system back gesture (desktop). Handlers register in
 * composition order; Escape runs the most recently registered enabled one, exactly like
 * Android's OnBackPressedDispatcher, so unsaved-change guards and open overlays win over the
 * plain "pop the screen" handler at the root.
 */
class BackDispatcher {
    private class Entry(var enabled: Boolean, var onBack: () -> Unit)

    private val entries = mutableListOf<Entry>()

    /** Runs the top enabled handler; false when nothing handled the back request. */
    fun dispatch(): Boolean {
        val top = entries.lastOrNull { it.enabled } ?: return false
        top.onBack()
        return true
    }

    val hasEnabledHandler: Boolean get() = entries.any { it.enabled }

    @Composable
    fun Handler(enabled: Boolean, onBack: () -> Unit) {
        val entry = remember { Entry(enabled, onBack) }
        SideEffect {
            entry.enabled = enabled
            entry.onBack = onBack
        }
        DisposableEffect(entry) {
            entries.add(entry)
            onDispose { entries.remove(entry) }
        }
    }

    /** Test hook: register without composition. Returns an unregister function. */
    fun register(enabled: Boolean, onBack: () -> Unit): () -> Unit {
        val entry = Entry(enabled, onBack)
        entries.add(entry)
        return { entries.remove(entry) }
    }
}

/**
 * Lets a keyboard shortcut (Ctrl/Cmd+K) focus the search field of the visible list. The list's
 * search bar registers its requester while composed; [focusOnNextRegistration] covers the case
 * where the shortcut first switches tab.
 */
class SearchFocus {
    private var requester: FocusRequester? = null
    private var pending = false

    fun register(r: FocusRequester): () -> Unit {
        requester = r
        if (pending) {
            pending = false
            runCatching { r.requestFocus() }
        }
        return { if (requester === r) requester = null }
    }

    /** Focuses the current search field; false if no list with search is showing. */
    fun focus(): Boolean {
        val r = requester ?: return false
        return runCatching { r.requestFocus() }.isSuccess
    }

    fun focusOnNextRegistration() {
        pending = true
    }
}

val LocalSearchFocus = staticCompositionLocalOf<SearchFocus?> { null }

/**
 * The focusable root that receives Escape and the Ctrl shortcuts (desktop only; null on touch).
 * Overlays hand focus back to it when they close, since the field that had focus inside them
 * is gone and Compose would otherwise leave nothing focused, so no key reached the shortcuts.
 */
val LocalKeyRootFocus = staticCompositionLocalOf<FocusRequester?> { null }
