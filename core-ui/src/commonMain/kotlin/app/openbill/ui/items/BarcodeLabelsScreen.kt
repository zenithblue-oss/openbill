package app.openbill.ui.items

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.core.domain.model.Item
import app.openbill.core.model.Money
import app.openbill.ui.AppContainer
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.Session
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.EmptyState
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.SelectableChip
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.pdf.BarcodeLabel
import app.openbill.ui.pdf.BarcodeLabels
import app.openbill.ui.pdf.PdfAction
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Labels for the chosen items: each item repeated by its copy count, in list order. */
fun barcodeLabels(items: List<Item>, copies: Map<String, Int>, withPrice: Boolean): List<BarcodeLabel> =
    items.flatMap { item ->
        val code = item.barcode ?: return@flatMap emptyList()
        val price = if (withPrice && item.salePrice > Money.ZERO) Fmt.inr(item.salePrice) else null
        List(copies[item.id] ?: 0) { BarcodeLabel(item.name, code, price) }
    }

/** Prints Code 128 labels (A4, 3 x 8) for items that have a barcode; [itemIds] start selected. */
@Composable
fun BarcodeLabelsScreen(
    container: AppContainer,
    session: Session,
    itemIds: List<String>,
    onBack: () -> Unit,
    backHandler: BackHandlerSlot,
) {
    val firmId = session.firm.id
    val all by remember(firmId) { container.repositories.catalog.observeItems(firmId) }.collectAsState(initial = null)
    val items = all?.filter { !it.barcode.isNullOrBlank() }
    val copies = remember { mutableStateMapOf<String, Int>().apply { itemIds.forEach { put(it, 1) } } }
    var withPrice by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val labels = items?.let { barcodeLabels(it, copies, withPrice) }.orEmpty()
    backHandler(menu) { menu = false }

    fun run(action: PdfAction) {
        busy = true
        scope.launch {
            runCatching {
                val layout = withContext(Dispatchers.Default) { BarcodeLabels.layout(labels, container.platform.pdfFontMetrics) }
                container.platform.pdfAction("Barcode-labels", layout, action)
            }
                .onSuccess { msg -> msg?.let { messages?.show(it) } }
                .onFailure { messages?.show("Could not ${action.label.lowercase()} the labels: ${it.message}") }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            AppTopBar("Barcode labels", onBack = onBack) {
                val ready = labels.isNotEmpty() && !busy
                IconButton(onClick = { run(PdfAction.SHARE) }, enabled = ready) { Icon(Icons.Filled.Share, contentDescription = "Share PDF") }
                Box {
                    IconButton(onClick = { menu = true }, enabled = ready) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(PdfAction.SAVE, PdfAction.PRINT, PdfAction.OPEN).forEach { a ->
                            DropdownMenuItem(text = { Text(a.label) }, onClick = { menu = false; run(a) })
                        }
                    }
                }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            if (items == null) {
                LoadingState()
                return@CenteredContent
            }
            if (items.isEmpty()) {
                EmptyState(Icons.Outlined.ShoppingCart, "No barcodes yet", "Add a barcode to an item to print labels for it.", Modifier.align(Alignment.Center))
                return@CenteredContent
            }
            LazyColumn {
                item(key = "summary") {
                    Row(
                        Modifier.padding(horizontal = pageGutter, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectableChip("Print price", withPrice, { withPrice = !withPrice })
                        val pages = (labels.size + BarcodeLabels.PER_PAGE - 1) / BarcodeLabels.PER_PAGE
                        Text(
                            "${labels.size} labels · $pages ${if (pages == 1) "sheet" else "sheets"} (A4, ${BarcodeLabels.PER_PAGE} per sheet)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(items, key = { it.id }) { item ->
                    val n = copies[item.id] ?: 0
                    ListItem(
                        headlineContent = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(item.barcode.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { copies[item.id] = n - 1 }, enabled = n > 0) { Text("−", style = MaterialTheme.typography.titleLarge) }
                                Text("$n", style = MaterialTheme.typography.titleMedium)
                                IconButton(onClick = { copies[item.id] = n + 1 }, enabled = n < 999) { Text("+", style = MaterialTheme.typography.titleLarge) }
                            }
                        },
                    )
                }
            }
        }
    }
}
