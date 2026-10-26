package app.openbill.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import app.openbill.ui.theme.AppIcons
import app.openbill.ui.theme.BrandLogo
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.ui.AppContainer
import app.openbill.ui.Session
import app.openbill.ui.components.AppSnackbarHost
import app.openbill.ui.components.CenteredContent
import app.openbill.ui.components.CollapsingTopBar
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.SectionHeader
import app.openbill.ui.components.pageGutter
import app.openbill.ui.format.Fmt
import app.openbill.ui.settings.ThemeMode
import app.openbill.ui.theme.Spacing
import app.openbill.ui.theme.platformSupportsDynamicColor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    container: AppContainer,
    session: Session,
    onEditBusiness: () -> Unit,
    onInvoiceTemplates: () -> Unit = {},
    onViewIntroduction: () -> Unit = {},
    onPurchases: () -> Unit = {},
    onStock: () -> Unit = {},
    onGodowns: () -> Unit = {},
    onReports: () -> Unit = {},
    onBooks: () -> Unit = {},
    onAudit: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val messages = LocalAppMessages.current
    val firm = session.firm
    val appearance by container.appearance.settings.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listColors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { CollapsingTopBar("More", scrollBehavior) },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        CenteredContent(Modifier.padding(padding)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pageGutter)) {
                SectionHeader("Business")
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Spacing.lg)) {
                        Text(firm.name, style = MaterialTheme.typography.titleMedium)
                        Text(firm.gstin?.let { "GSTIN $it" } ?: "Not GST registered", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${firm.addressLine1}, ${firm.city}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(Fmt.stateName(firm.stateCode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Invoice prefix ${session.settings.invoicePrefix} · " +
                                session.financialYears.joinToString { Fmt.financialYearLabel(it.startDate.year) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Edit business details") },
                        leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f)),
                        modifier = Modifier.clickable(role = Role.Button, onClick = onEditBusiness),
                    )
                }

                SectionHeader("Invoices")
                val invoiceStyle by remember(firm.id) { container.invoiceStyles.style(firm.id) }.collectAsState()
                ListItem(
                    headlineContent = { Text("Invoice templates") },
                    supportingContent = { Text("${invoiceStyle.template.title} · ${invoiceStyle.template.paper.label} · print options") },
                    leadingContent = { Icon(AppIcons.Invoice, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    colors = listColors,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onInvoiceTemplates),
                )

                SectionHeader("Reports and compliance")
                listOf(
                    Triple("Reports", "Registers, P&L, stock, GSTR-1, GSTR-3B and Tally export", onReports),
                    Triple("Lock books and close year", "Stop changes to filed periods", onBooks),
                    Triple("Audit log", "Every post, cancel, restore and lock", onAudit),
                ).forEach { (title, sub, action) ->
                    ListItem(
                        headlineContent = { Text(title) },
                        supportingContent = { Text(sub) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        colors = listColors,
                        modifier = Modifier.clickable(role = Role.Button, onClick = action),
                    )
                }

                SectionHeader("Purchases and stock")
                listOf(
                    Triple("Purchases", "Supplier bills and purchase returns", onPurchases),
                    Triple("Stock", "Stock summary, valuation, adjustments and transfers", onStock),
                    Triple("Godowns", "Stock locations", onGodowns),
                ).forEach { (title, sub, action) ->
                    ListItem(
                        headlineContent = { Text(title) },
                        supportingContent = { Text(sub) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        colors = listColors,
                        modifier = Modifier.clickable(role = Role.Button, onClick = action),
                    )
                }
                app.openbill.ui.inventory.SwitchRow(
                    "Allow negative stock",
                    if (session.settings.negativeStockAllowed) "Sales can post even when stock is short" else "Posting is blocked when stock would go below zero",
                    session.settings.negativeStockAllowed,
                    { allow -> scope.launch { container.repositories.firms.saveSettings(session.settings.copy(negativeStockAllowed = allow)) } },
                )

                SectionHeader("Appearance")
                Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = Spacing.xs))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = appearance.themeMode == mode,
                            onClick = { container.appearance.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                            label = { Text(mode.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                val dynamicSupported = platformSupportsDynamicColor
                ListItem(
                    headlineContent = { Text("Use device colors") },
                    supportingContent = {
                        Text(
                            if (dynamicSupported) {
                                "Match your wallpaper colors (Material You). Status colors stay fixed."
                            } else {
                                "Needs Android 12 or newer. OpenBill colors are used here."
                            },
                        )
                    },
                    trailingContent = {
                        Switch(checked = appearance.useDeviceColors && dynamicSupported, onCheckedChange = null, enabled = dynamicSupported)
                    },
                    colors = listColors,
                    modifier = Modifier.toggleable(
                        value = appearance.useDeviceColors,
                        enabled = dynamicSupported,
                        role = Role.Switch,
                        onValueChange = { container.appearance.setUseDeviceColors(it) },
                    ),
                )

                ListItem(
                    headlineContent = { Text("Reduce motion") },
                    supportingContent = { Text("Fewer animations: screens change without sliding and the introduction shows still pictures.") },
                    trailingContent = { Switch(checked = appearance.reduceMotion, onCheckedChange = null) },
                    colors = listColors,
                    modifier = Modifier.toggleable(
                        value = appearance.reduceMotion,
                        role = Role.Switch,
                        onValueChange = { container.appearance.setReduceMotion(it) },
                    ),
                )

                if (container.platform.isDebugBuild) {
                    SectionHeader("Developer")
                    ListItem(
                        headlineContent = { Text(if (busy) "Loading sample data…" else "Load sample data") },
                        supportingContent = { Text("Adds fictional parties, items and invoices. Debug builds only.") },
                        leadingContent = { Icon(Icons.Outlined.Build, contentDescription = null) },
                        colors = listColors,
                        modifier = Modifier.clickable(enabled = !busy, role = Role.Button) {
                            busy = true
                            scope.launch {
                                // Finish the load even if the user leaves this tab meanwhile.
                                val result = runCatching { withContext(NonCancellable) { SampleData.load(container, session) } }
                                    .getOrElse { "Sample data failed: ${it.message}" }
                                busy = false
                                messages?.show(result)
                            }
                        },
                    )
                }

                BackupSection(listColors)

                SectionHeader("About")
                ListItem(
                    headlineContent = { Text("OpenBill ${container.platform.appVersion}") },
                    supportingContent = {
                        Text("Open-source, offline-first billing. Your business data is stored on this computer or phone and never leaves it; no internet connection is needed.")
                    },
                    leadingContent = { Image(rememberVectorPainter(BrandLogo.Mark), contentDescription = null, modifier = Modifier.size(40.dp)) },
                    colors = listColors,
                )
                ListItem(
                    headlineContent = { Text("View introduction") },
                    supportingContent = { Text("A short tour of what OpenBill does") },
                    leadingContent = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    colors = listColors,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onViewIntroduction),
                )
                Spacer(Modifier.height(Spacing.xl))
            }
        }
    }
}
