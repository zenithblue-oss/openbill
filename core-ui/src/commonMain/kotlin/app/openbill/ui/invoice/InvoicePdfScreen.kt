package app.openbill.ui.invoice

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.openbill.ui.AppContainer
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.Session
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.pdf.CopyMode
import app.openbill.ui.pdf.InvoiceLayout
import app.openbill.ui.pdf.InvoicePdf
import app.openbill.ui.pdf.InvoiceTemplate
import app.openbill.ui.pdf.PdfAction
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-app PDF viewer. Pages are rendered from the same laid-out draw commands that the PDF is
 * painted from, and every action (share, print, save, open) uses that same layout, so what is
 * shown is exactly what is sent. Template and copy label can be changed for this document
 * only, or saved as the default.
 */
@Composable
fun InvoicePdfScreen(
    container: AppContainer,
    session: Session,
    voucherId: String,
    onBack: () -> Unit,
    backHandler: BackHandlerSlot = { _, _ -> },
) {
    val firmId = session.firm.id
    val voucher by remember(voucherId) { container.repositories.vouchers.observeVoucher(voucherId) }.collectAsState(initial = null)
    val defaultStyle by remember(firmId) { container.invoiceStyles.style(firmId) }.collectAsState()
    val imageVersion by container.invoiceStyles.imageVersion.collectAsState()
    var template by remember { mutableStateOf<InvoiceTemplate?>(null) }
    var copies by remember { mutableStateOf<CopyMode?>(null) }
    val style = defaultStyle.copy(
        template = template ?: defaultStyle.template,
        options = defaultStyle.options.copy(copies = copies ?: defaultStyle.options.copies),
    )
    var doc by remember { mutableStateOf<InvoiceDocument?>(null) }
    var layout by remember { mutableStateOf<InvoiceLayout?>(null) }
    val pages = remember { mutableStateListOf<ImageBitmap>() }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var pickTemplate by remember { mutableStateOf(false) }
    var pickCopies by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    backHandler(pickTemplate || pickCopies) { pickTemplate = false; pickCopies = false }

    LaunchedEffect(voucher) {
        voucher?.let { v -> doc = runCatching { buildInvoiceDocument(container, session, v) }.onFailure { error = it.message }.getOrNull() }
    }

    fun run(action: PdfAction) {
        val d = doc ?: return
        val l = layout ?: return
        busy = true
        scope.launch {
            runCatching { container.platform.invoicePdfAction(d, l, action) }
                .onSuccess { msg -> msg?.let { messages?.show(it) } }
                .onFailure { messages?.show("Could not ${action.label.lowercase()} the PDF: ${it.message}") }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            AppTopBar("${voucher?.number ?: "Draft"} PDF", onBack = onBack) {
                IconButton(onClick = { run(PdfAction.SHARE) }, enabled = layout != null && !busy) {
                    Icon(Icons.Filled.Share, contentDescription = "Share PDF")
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = layout != null) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        listOf(PdfAction.PRINT, PdfAction.SAVE, PdfAction.OPEN).forEach { a ->
                            DropdownMenuItem(text = { Text(a.label) }, onClick = { menu = false; run(a) }, enabled = !busy)
                        }
                    }
                }
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.md, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { pickTemplate = true },
                    label = { Text("${style.template.title} · ${style.template.paper.label}") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                )
                AssistChip(
                    onClick = { pickCopies = true },
                    label = { Text(style.options.copies.label) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                )
                if (style != defaultStyle) {
                    TextButton(onClick = {
                        container.invoiceStyles.update(firmId) { style }
                        template = null
                        copies = null
                        messages?.show("Saved as the default for new PDFs")
                    }) { Text("Set as default") }
                }
            }
            HorizontalDivider()
            BoxWithConstraints(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                val density = LocalDensity.current
                val viewport = maxWidth
                // Re-render at (page width x zoom) once zoom settles; the old bitmaps stay until the new ones are ready.
                var settledZoom by remember { mutableFloatStateOf(1f) }
                LaunchedEffect(zoom) { delay(150); settledZoom = zoom }
                val renderPx = with(density) {
                    val px = ((viewport - Spacing.md * 2).toPx() * settledZoom).toInt()
                    (((px + 255) / 256) * 256).coerceIn(512, 4096)
                }
                LaunchedEffect(doc, style, imageVersion) {
                    val d = doc ?: return@LaunchedEffect
                    error = null
                    runCatching { layout = InvoicePdf.layout(container, firmId, d, style) }
                        .onFailure { error = "Could not render the PDF: ${it.message}" }
                }
                LaunchedEffect(layout, renderPx) {
                    val l = layout ?: return@LaunchedEffect
                    runCatching {
                        val images = l.pages.indices.mapNotNull { container.platform.renderPagePreview(l, it, renderPx) }
                        pages.clear()
                        pages.addAll(images)
                    }.onFailure { error = "Could not render the PDF: ${it.message}" }
                }
                val listState = rememberLazyListState()
                val current by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }
                when {
                    error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(Spacing.lg))
                    pages.isEmpty() -> LoadingState()
                    else -> {
                        val pageWidth = (viewport - Spacing.md * 2) * zoom
                        Box(
                            Modifier.fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .pointerInput(Unit) {
                                    // Two-finger pinch zooms; one finger keeps scrolling.
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        do {
                                            val event = awaitPointerEvent()
                                            if (event.changes.count { it.pressed } >= 2) {
                                                val z = event.calculateZoom()
                                                if (z != 1f) {
                                                    zoom = (zoom * z).coerceIn(1f, 4f)
                                                    event.changes.forEach { it.consume() }
                                                }
                                            }
                                        } while (event.changes.any { it.pressed })
                                    }
                                },
                        ) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.width(pageWidth + Spacing.md * 2).fillMaxSize(),
                                contentPadding = PaddingValues(Spacing.md),
                                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                itemsIndexed(pages) { i, page ->
                                    Image(
                                        page,
                                        contentDescription = "Page ${i + 1}",
                                        contentScale = ContentScale.FillWidth,
                                        modifier = Modifier.width(pageWidth)
                                            .aspectRatio(page.width.toFloat() / page.height)
                                            .shadow(2.dp, RoundedCornerShape(2.dp))
                                            .background(Color.White),
                                    )
                                }
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(Spacing.lg),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.xs)) {
                                TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f) }, enabled = zoom > 1f) {
                                    Text("−", color = MaterialTheme.colorScheme.inverseOnSurface)
                                }
                                Text("Page $current of ${pages.size}", style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }) {
                                    Text("+", color = MaterialTheme.colorScheme.inverseOnSurface)
                                }
                            }
                        }
                        if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center).size(36.dp))
                    }
                }
            }
        }
    }

    if (pickTemplate) {
        PickerSheet(
            title = "Template for this PDF",
            options = InvoiceTemplate.entries.toList(),
            label = { it.title },
            supporting = { "${it.paper.label} · ${it.description}" },
            selected = { it == style.template },
            onSelect = { t -> template = t; pickTemplate = false },
            onDismiss = { pickTemplate = false },
            searchable = false,
        )
    }
    if (pickCopies) {
        PickerSheet(
            title = "Copy label",
            options = CopyMode.entries.toList(),
            label = { it.label },
            selected = { it == style.options.copies },
            onSelect = { c -> copies = c; pickCopies = false },
            onDismiss = { pickCopies = false },
            searchable = false,
        )
    }
}
