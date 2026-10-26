package app.openbill.ui.more

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.openbill.ui.AppContainer
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.Session
import app.openbill.ui.components.AppTopBar
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.FormField
import app.openbill.ui.components.PickerField
import app.openbill.ui.components.PickerSheet
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.pageGutter
import app.openbill.ui.pdf.Accent
import app.openbill.ui.pdf.CopyMode
import app.openbill.ui.pdf.InvoicePdf
import app.openbill.ui.pdf.InvoiceStyle
import app.openbill.ui.pdf.InvoiceTemplate
import app.openbill.ui.pdf.Paper
import app.openbill.ui.pdf.PrintOptions
import app.openbill.ui.pdf.SampleInvoice
import app.openbill.ui.theme.Spacing

/** More → Invoice templates: live gallery, accent colour, print options and full-page preview. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InvoiceTemplatesScreen(container: AppContainer, session: Session, onBack: () -> Unit, backHandler: BackHandlerSlot = { _, _ -> }) {
    val firmId = session.firm.id
    val style by remember(firmId) { container.invoiceStyles.style(firmId) }.collectAsState()
    val imageVersion by container.invoiceStyles.imageVersion.collectAsState()
    var previewOpen by remember { mutableStateOf(false) }
    var copyPicker by remember { mutableStateOf(false) }
    val sample = remember(session.firm) { runCatching { SampleInvoice.document(session.firm, settings = session.settings) }.getOrNull() }
    fun update(t: (InvoiceStyle) -> InvoiceStyle) = container.invoiceStyles.update(firmId, t)
    fun options(t: (PrintOptions) -> PrintOptions) = update { it.copy(options = t(it.options)) }
    backHandler(previewOpen) { previewOpen = false }

    Scaffold(topBar = { AppTopBar("Invoice templates", onBack = onBack) }) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pageGutter)) {
                Text(
                    "Choose how shared and printed invoices look. The previews use a sample invoice with your business details.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                if (sample == null) {
                    Text("Previews are unavailable for this business.", color = MaterialTheme.colorScheme.error)
                }
                Paper.entries.groupBy { if (it.thermal) "Receipt printers" else "${it.label} paper" }.forEach { (group, papers) ->
                    SectionHeader(group)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = if (maxWidth < 420.dp) 2 else if (maxWidth < 640.dp) 3 else 4
                        val cardW = (maxWidth - Spacing.md * (columns - 1)) / columns
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            InvoiceTemplate.entries.filter { it.paper in papers }.forEach { t ->
                                TemplateCard(
                                    container, firmId, sample, style.copy(template = t, options = style.options.copy(copies = CopyMode.ORIGINAL)),
                                    selected = style.template == t,
                                    imageVersion = imageVersion,
                                    modifier = Modifier.width(cardW),
                                    onClick = { update { s -> s.copy(template = t) } },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                OutlinedButton(onClick = { previewOpen = true }, enabled = sample != null, modifier = Modifier.fillMaxWidth()) {
                    Text("Preview full page")
                }

                SectionHeader("Accent colour")
                Text(
                    if (style.template == InvoiceTemplate.SIMPLE || style.template == InvoiceTemplate.SIMPLE_A5 || style.template.paper.thermal) {
                        "The selected template prints in black and grey only."
                    } else {
                        style.accent.label
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(vertical = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Accent.entries.forEach { a ->
                        val selected = style.accent == a
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .padding(if (selected) 5.dp else 0.dp)
                                .clip(CircleShape)
                                .background(Color(a.argb))
                                .selectable(selected, role = Role.RadioButton) { update { s -> s.copy(accent = a) } }
                                .semantics { contentDescription = a.label },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                SectionHeader("Print options")
                val o = style.options
                OptionSwitch("Business logo", "Set it in Business details", o.showLogo) { v -> options { it.copy(showLogo = v) } }
                OptionSwitch("HSN/SAC codes", null, o.showHsn) { v -> options { it.copy(showHsn = v) } }
                OptionSwitch("Discount column", "Only when an invoice has discounts", o.showDiscount) { v -> options { it.copy(showDiscount = v) } }
                OptionSwitch("Due date", null, o.showDueDate) { v -> options { it.copy(showDueDate = v) } }
                OptionSwitch("Amount in words", null, o.showAmountInWords) { v -> options { it.copy(showAmountInWords = v) } }
                OptionSwitch("GST summary table", "Tax by rate (and by HSN/SAC on GST Detailed)", o.showTaxSummary) { v -> options { it.copy(showTaxSummary = v) } }
                OptionSwitch("Bank details", "From Business details", o.showBank) { v -> options { it.copy(showBank = v) } }
                OptionSwitch("UPI payment QR", "Needs a UPI ID below; the QR is made on this device", o.showUpiQr) { v -> options { it.copy(showUpiQr = v) } }
                OptionSwitch("Notes", null, o.showNotes) { v -> options { it.copy(showNotes = v) } }
                OptionSwitch("Terms and conditions", null, o.showTerms) { v -> options { it.copy(showTerms = v) } }
                OptionSwitch("Signature image", "Set it in Business details", o.showSignature) { v -> options { it.copy(showSignature = v) } }
                OptionSwitch("Party balance", "Previous and current balance on posted documents", o.showPartyBalance) { v -> options { it.copy(showPartyBalance = v) } }
                Spacer(Modifier.height(Spacing.sm))
                PickerField("Copy label", o.copies.label, onClick = { copyPicker = true })
                Spacer(Modifier.height(Spacing.sm))
                var upi by remember(firmId) { mutableStateOf(o.upiId) }
                FormField(
                    "UPI ID",
                    upi,
                    { v -> upi = v.trim(); options { it.copy(upiId = upi) } },
                    placeholder = "yourname@bank",
                    supporting = if (upi.isNotEmpty() && !Regex("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9.-]{1,64}$").matches(upi)) {
                        "This does not look like a UPI ID (name@bank)"
                    } else {
                        "Printed with a QR code for the invoice amount"
                    },
                    capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                )
                var terms by remember(firmId) { mutableStateOf(o.terms) }
                FormField(
                    "Terms and conditions",
                    terms,
                    { v -> terms = v; options { it.copy(terms = v) } },
                    singleLine = false,
                    supporting = "Printed at the end of every invoice",
                )
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }

    if (copyPicker) {
        PickerSheet(
            title = "Copy label",
            options = CopyMode.entries.toList(),
            label = { it.label },
            supporting = { if (it == CopyMode.ALL_THREE) "Original, duplicate and triplicate in one PDF" else null },
            selected = { it == style.options.copies },
            onSelect = { c -> options { it.copy(copies = c) }; copyPicker = false },
            onDismiss = { copyPicker = false },
            searchable = false,
        )
    }
    if (previewOpen && sample != null) {
        FullPagePreview(container, firmId, sample, style, imageVersion) { previewOpen = false }
    }
}

@Composable
private fun OptionSwitch(title: String, supporting: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun TemplateCard(
    container: AppContainer,
    firmId: String,
    sample: app.openbill.ui.invoice.InvoiceDocument?,
    style: InvoiceStyle,
    selected: Boolean,
    imageVersion: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val t = style.template
    val widthPx = with(LocalDensity.current) { 220.dp.roundToPx() }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(style, imageVersion, sample) {
        if (sample == null) return@LaunchedEffect
        bitmap = runCatching {
            val layout = InvoicePdf.layout(container, firmId, sample, style)
            container.platform.renderPagePreview(layout, 0, widthPx)
        }.getOrNull()
    }
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${t.title} template" },
    ) {
        Surface(shape = RoundedCornerShape(12.dp), border = border, color = Color.White, modifier = Modifier.fillMaxWidth().aspectRatio(1f / 1.2f)) {
            Box(contentAlignment = Alignment.Center) {
                val b = bitmap
                if (b == null) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Image(
                        b, contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().padding(horizontal = if (t.paper.thermal) 22.dp else 0.dp),
                    )
                }
                if (selected) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp)) }
                }
            }
        }
        Text(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Spacing.xs))
        Text(
            t.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Every page of the sample invoice in the current template, at screen width. */
@Composable
private fun FullPagePreview(
    container: AppContainer,
    firmId: String,
    sample: app.openbill.ui.invoice.InvoiceDocument,
    style: InvoiceStyle,
    imageVersion: Int,
    onDismiss: () -> Unit,
) {
    val pages = remember { mutableStateListOf<ImageBitmap>() }
    var failed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val widthPx = with(LocalDensity.current) { minOf(maxWidth, 820.dp).roundToPx() }
                LaunchedEffect(style, imageVersion) {
                    pages.clear()
                    runCatching {
                        val layout = InvoicePdf.layout(container, firmId, sample, style)
                        for (i in layout.pages.indices) container.platform.renderPagePreview(layout, i, widthPx)?.let { pages += it }
                    }.onFailure { failed = true }
                }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sm, vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close preview") }
                        Text(
                            "${style.template.title} · ${style.template.paper.label}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    HorizontalDivider()
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        if (failed) Text("Could not render the preview.", color = MaterialTheme.colorScheme.error)
                        if (pages.isEmpty() && !failed) CircularProgressIndicator(Modifier.padding(Spacing.xl))
                        pages.forEachIndexed { i, page ->
                            Image(
                                page,
                                contentDescription = "Page ${i + 1}",
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth().background(Color.White),
                            )
                        }
                    }
                }
            }
        }
    }
}
