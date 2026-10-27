package app.openbill.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** Rows per page of the growing list queries (`observe…Page(limit)`). */
const val PAGE_SIZE = 200

/**
 * Grows [limit] by [PAGE_SIZE] when the list scrolls near its end and the last query filled
 * the limit ([loaded] rows), so long lists load page by page instead of all at once.
 */
@Composable
fun GrowLimitOnScroll(state: LazyListState, loaded: Int, limit: MutableIntState) {
    LaunchedEffect(state, loaded, limit.intValue) {
        if (loaded < limit.intValue) return@LaunchedEffect
        snapshotFlow { state.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - 20 } }.first { it }
        limit.intValue += PAGE_SIZE
    }
}
