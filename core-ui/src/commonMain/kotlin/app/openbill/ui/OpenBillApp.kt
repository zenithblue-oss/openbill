package app.openbill.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.animation.scaleOut
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import app.openbill.ui.theme.BrandLogo
import app.openbill.ui.theme.Spacing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import app.openbill.core.domain.model.Firm
import app.openbill.core.domain.model.FirmSettings
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.domain.model.VoucherType
import app.openbill.ui.inventory.GodownsScreen
import app.openbill.ui.inventory.Inventory
import app.openbill.ui.inventory.ItemStockScreen
import app.openbill.ui.inventory.StockEntryScreen
import app.openbill.ui.inventory.StockSummaryScreen
import app.openbill.ui.items.BarcodeLabelsScreen
import app.openbill.ui.components.AppMessages
import app.openbill.ui.components.LoadingState
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.components.LocalWidthClass
import app.openbill.ui.invoice.InvoiceDetailScreen
import app.openbill.ui.invoice.InvoiceEditorScreen
import app.openbill.ui.invoice.InvoiceListScreen
import app.openbill.ui.invoice.InvoicePdfScreen
import app.openbill.ui.items.ItemEditScreen
import app.openbill.ui.items.ItemListScreen
import app.openbill.ui.more.InvoiceTemplatesScreen
import app.openbill.ui.more.MoreScreen
import app.openbill.ui.reports.AuditLogScreen
import app.openbill.ui.reports.BooksLockScreen
import app.openbill.ui.reports.ReportScreen
import app.openbill.ui.reports.ReportsScreen
import app.openbill.core.domain.report.ReportKind
import app.openbill.core.domain.report.ReportPeriod
import kotlinx.datetime.LocalDate
import app.openbill.ui.money.AccountScreen
import app.openbill.ui.money.MoneyCard
import app.openbill.ui.money.MoneyDetailScreen
import app.openbill.ui.money.MoneyEditorScreen
import app.openbill.ui.money.MoneyScreen
import app.openbill.ui.money.OutstandingScreen
import app.openbill.ui.money.PartyLedgerScreen
import app.openbill.core.domain.model.usesPaymentAccount
import app.openbill.ui.nav.BackDispatcher
import app.openbill.ui.nav.LocalKeyRootFocus
import app.openbill.ui.nav.LocalSearchFocus
import app.openbill.ui.nav.Navigator
import app.openbill.ui.nav.SearchFocus
import app.openbill.ui.nav.Route
import app.openbill.ui.nav.Tab
import app.openbill.ui.nav.WidthClass
import app.openbill.ui.onboarding.LaunchStage
import app.openbill.ui.onboarding.OnboardingMode
import app.openbill.ui.onboarding.OnboardingScreen
import app.openbill.ui.onboarding.launchStage
import app.openbill.ui.parties.PartyEditScreen
import app.openbill.ui.parties.PartyListScreen
import app.openbill.ui.setup.BusinessEditScreen
import app.openbill.ui.setup.FirmSetupModel
import app.openbill.ui.setup.SetupScreen
import app.openbill.ui.theme.AppIcons
import app.openbill.ui.theme.LocalReducedMotion
import app.openbill.ui.theme.Motion
import app.openbill.ui.theme.OpenBillTheme
import app.openbill.ui.theme.motionTween
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Platform back handling hook (Android system back; no-op on desktop). */
typealias BackHandlerSlot = @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit

/**
 * Root composable. Android passes its system [backHandler]; without one (desktop) the app
 * handles Escape itself through a [BackDispatcher], plus Ctrl/Cmd+N and Ctrl/Cmd+K.
 */
@Composable
fun OpenBillApp(container: AppContainer, backHandler: BackHandlerSlot? = null) {
    val keyBack = remember { BackDispatcher() }
    val slot: BackHandlerSlot = backHandler ?: { enabled, onBack -> keyBack.Handler(enabled, onBack) }
    val keyboardShell = backHandler == null
    val appearance by container.appearance.settings.collectAsState()
    OpenBillTheme(appearance) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalWidthClass provides WidthClass.of(maxWidth.value)) {
                    val firms by remember { container.repositories.firms.observeFirms() }.collectAsState(initial = null)
                    val onboardingDone by container.onboarding.completed.collectAsState()
                    val list = firms
                    val stage = launchStage(
                        firmsLoaded = list != null,
                        hasFirm = !list.isNullOrEmpty(),
                        onboardingCompleted = onboardingDone,
                    )
                    Crossfade(stage, animationSpec = motionTween(Motion.MEDIUM2), label = "phase") { p ->
                        when (p) {
                            LaunchStage.LOADING -> LoadingState()
                            LaunchStage.ONBOARDING -> OnboardingScreen(
                                mode = OnboardingMode.FIRST_RUN,
                                onFinish = container.onboarding::markCompleted,
                                backHandler = slot,
                            )
                            LaunchStage.SETUP -> SetupScreen(container)
                            LaunchStage.APP -> list?.firstOrNull()?.let { FirmShell(container, it, slot, if (keyboardShell) keyBack else null) }
                                ?: LoadingState()
                        }
                    }
                }
            }
        }
    }
}

private data class NavEntry(val route: Route, val depth: Int)

@Composable
private fun FirmShell(container: AppContainer, firm: Firm, backHandler: BackHandlerSlot, keyBack: BackDispatcher?) {
    LaunchedEffect(firm.id) {
        runCatching { FirmSetupModel.ensureDefaults(container, firm.id) }
        runCatching { Inventory.ensureDefaultGodown(container, firm.id) }
    }
    // Keyed by id only: editing the business re-emits the firm, which must not reset navigation.
    val parts by remember(firm.id) {
        val repos = container.repositories.firms
        combine(
            repos.observeSettings(firm.id).map { it ?: FirmSettings(firmId = firm.id) },
            repos.observeFinancialYears(firm.id),
        ) { settings, years -> if (years.isEmpty()) null else settings to years }
    }.collectAsState(initial = null)
    val p = parts
    if (p == null) {
        LoadingState()
        return
    }
    val s = Session(firm, p.first, p.second)
    val nav = rememberSaveable(saver = Navigator.Saver) { Navigator() }
    val stateHolder = rememberSaveableStateHolder()
    nav.onRemoved = { removed -> if (removed != nav.current) stateHolder.removeState(removed.key) }
    val scope = rememberCoroutineScope()
    val messages = remember { AppMessages(SnackbarHostState(), scope) }
    val route = nav.current
    backHandler(nav.canGoBack || route != Route.Home(Tab.INVOICES)) {
        if (nav.canGoBack) nav.pop() else nav.selectTab(Tab.INVOICES)
    }
    val widthClass = LocalWidthClass.current
    val reduced = LocalReducedMotion.current
    val selectedTab = (route as? Route.Home)?.tab
    val showNav = selectedTab != null
    val axisOffset = with(LocalDensity.current) { Motion.SharedAxisOffset.roundToPx() }

    val searchFocus = remember { SearchFocus() }
    val rootFocus = remember { FocusRequester() }
    var rootHasFocus by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    if (keyBack != null) {
        // Give each screen a focused root so Escape and shortcuts work before anything is clicked.
        LaunchedEffect(route) { runCatching { rootFocus.requestFocus() } }
        // The window drops Compose focus when it deactivates (e.g. behind the PDF save dialog);
        // without this, Escape and Ctrl+N/K stayed dead until the next screen change.
        LaunchedEffect(windowFocused) { if (windowFocused && !rootHasFocus) runCatching { rootFocus.requestFocus() } }
    }
    val keyModifier = if (keyBack == null) {
        Modifier
    } else {
        Modifier
            .focusRequester(rootFocus)
            .onFocusChanged { rootHasFocus = it.hasFocus }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = e.isCtrlPressed || e.isMetaPressed
                when {
                    e.key == Key.Escape -> keyBack.dispatch()
                    mod && e.key == Key.N -> {
                        if (route !is Route.InvoiceEditor) nav.push(Route.InvoiceEditor(null))
                        true
                    }
                    mod && e.key == Key.K -> {
                        if (!searchFocus.focus() && route is Route.Home) {
                            searchFocus.focusOnNextRegistration()
                            nav.selectTab(Tab.INVOICES)
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    }

    CompositionLocalProvider(
        LocalAppMessages provides messages,
        LocalSearchFocus provides searchFocus,
        LocalKeyRootFocus provides (if (keyBack != null) rootFocus else null),
    ) {
        Row(Modifier.fillMaxSize().then(keyModifier)) {
            if (widthClass.usesRail) {
                AnimatedVisibility(
                    visible = showNav,
                    enter = if (reduced) EnterTransition.None else fadeIn(motionTween(false, Motion.SHORT4)),
                    exit = if (reduced) ExitTransition.None else fadeOut(motionTween(false, Motion.SHORT3)),
                ) {
                    AppNavigationRail(
                        selectedTab = selectedTab,
                        onSelect = nav::selectTab,
                        // The rail header holds the tab's one create action; list screens drop
                        // their floating FAB whenever the rail is shown.
                        createAction = selectedTab?.createAction(nav),
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                val insetsHandledOutside = when {
                    showNav && widthClass.usesRail -> WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
                    showNav -> WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
                    else -> WindowInsets(0.dp)
                }
                Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(insetsHandledOutside)) {
                    AnimatedContent(
                        targetState = NavEntry(route, nav.depth),
                        transitionSpec = { routeTransition(reduced, axisOffset) },
                        label = "route",
                    ) { entry ->
                        stateHolder.SaveableStateProvider(entry.route.key) {
                            RouteContent(container, s, nav, entry.route, backHandler)
                        }
                    }
                }
                if (!widthClass.usesRail) {
                    AnimatedVisibility(
                        visible = showNav,
                        enter = if (reduced) EnterTransition.None else expandVertically(motionTween(false, Motion.MEDIUM1, Motion.EmphasizedDecelerate)),
                        exit = if (reduced) ExitTransition.None else shrinkVertically(motionTween(false, Motion.SHORT4, Motion.EmphasizedAccelerate)),
                    ) {
                        NavigationBar {
                            Tab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { nav.selectTab(tab) },
                                    icon = { Icon(if (selectedTab == tab) tab.selectedIcon else tab.icon, contentDescription = null) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Material 3 navigation rail: 80 dp wide on the surface colour, brand mark (and on expanded
 * windows the New invoice FAB) in the header, destinations centred vertically as a group, with
 * filled icons for the selected destination inside the active-indicator pill. Insets cover the
 * status and navigation bars and a landscape display cutout on the start side.
 */
@Composable
private fun AppNavigationRail(selectedTab: Tab?, onSelect: (Tab) -> Unit, createAction: CreateAction?) {
    val reduced = LocalReducedMotion.current
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
        header = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    rememberVectorPainter(BrandLogo.Mark),
                    contentDescription = "OpenBill",
                    modifier = Modifier.padding(top = Spacing.sm).size(32.dp),
                )
                // Collapses completely when there is no create action (no reserved gap); the item
                // group is centred in the remaining space and glides as the FAB animates.
                androidx.compose.animation.AnimatedVisibility(
                    visible = createAction != null,
                    enter = if (reduced) EnterTransition.None else fadeIn(motionTween(false, Motion.SHORT4)) + expandVertically(motionTween(false, Motion.MEDIUM1, Motion.EmphasizedDecelerate)),
                    exit = if (reduced) ExitTransition.None else fadeOut(motionTween(false, Motion.SHORT3)) + shrinkVertically(motionTween(false, Motion.SHORT4, Motion.EmphasizedAccelerate)),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(Spacing.lg))
                        // Remember the last action so the exit animation still has a label.
                        val last = remember { arrayOfNulls<CreateAction>(1) }
                        if (createAction != null) last[0] = createAction
                        val action = last[0] ?: return@AnimatedVisibility
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Right),
                            tooltip = { PlainTooltip { Text(action.tooltip) } },
                            state = rememberTooltipState(),
                        ) {
                            FloatingActionButton(
                                onClick = action.onClick,
                                elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = action.label)
                            }
                        }
                    }
                }
            }
        },
    ) {
        Spacer(Modifier.weight(1f))
        Tab.entries.forEach { tab ->
            val selected = selectedTab == tab
            NavigationRailItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                modifier = Modifier.padding(vertical = Spacing.xs),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** The primary create action of a home tab, shown in the rail header. */
private class CreateAction(val label: String, val tooltip: String, val onClick: () -> Unit)

private fun Tab.createAction(nav: Navigator): CreateAction? = when (this) {
    Tab.INVOICES -> CreateAction("New invoice", "New invoice (Ctrl+N)") { nav.push(Route.InvoiceEditor(null)) }
    Tab.PARTIES -> CreateAction("New party", "New party") { nav.push(Route.PartyEdit(null)) }
    Tab.ITEMS -> CreateAction("New item", "New item") { nav.push(Route.ItemEdit(null)) }
    Tab.MONEY -> CreateAction("Payment in", "Record payment received") { nav.push(Route.MoneyEditor(VoucherType.PAYMENT_IN)) }
    Tab.MORE -> null
}

private val Tab.selectedIcon: ImageVector
    get() = when (this) {
        Tab.INVOICES -> AppIcons.InvoiceSelected
        Tab.PARTIES -> AppIcons.PartiesSelected
        Tab.ITEMS -> AppIcons.ItemsSelected
        Tab.MONEY -> AppIcons.MoneySelected
        Tab.MORE -> AppIcons.MoreSelected
    }

private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.INVOICES -> AppIcons.Invoice
        Tab.PARTIES -> AppIcons.Parties
        Tab.ITEMS -> AppIcons.Items
        Tab.MONEY -> AppIcons.Money
        Tab.MORE -> AppIcons.More
    }

/**
 * Push/pop use a shared-axis X transition (forward slides in from the end, back from the start);
 * tab switches and in-place replacements use fade-through. Everything stays within 300 ms.
 */
private fun AnimatedContentTransitionScope<NavEntry>.routeTransition(reduced: Boolean, offsetPx: Int): ContentTransform {
    if (reduced) return EnterTransition.None togetherWith ExitTransition.None
    val from = initialState
    val to = targetState
    return when {
        to.depth != from.depth -> {
            val forward = to.depth > from.depth
            val sign = if (forward) 1 else -1
            (
                slideInHorizontally(motionTween(false, Motion.MEDIUM2, Motion.EmphasizedDecelerate)) { sign * offsetPx } +
                    fadeIn(motionTween(false, Motion.SHORT4, delayMillis = Motion.SHORT2))
                ) togetherWith (
                slideOutHorizontally(motionTween(false, Motion.MEDIUM2, Motion.EmphasizedAccelerate)) { -sign * offsetPx } +
                    fadeOut(motionTween(false, Motion.SHORT2))
                )
        }
        else ->
            (
                fadeIn(motionTween(false, Motion.SHORT4, delayMillis = Motion.SHORT2)) +
                    scaleIn(motionTween(false, Motion.SHORT4, Motion.EmphasizedDecelerate, Motion.SHORT2), initialScale = 0.94f)
                ) togetherWith fadeOut(motionTween(false, Motion.SHORT2))
    }
}

@Composable
private fun RouteContent(
    container: AppContainer,
    s: Session,
    nav: Navigator,
    route: Route,
    backHandler: BackHandlerSlot,
) {
    when (route) {
        is Route.Home -> when (route.tab) {
            Tab.INVOICES -> InvoiceListScreen(
                container, s,
                onNew = { nav.push(Route.InvoiceEditor(null)) },
                onOpen = { v ->
                    nav.push(if (v.status == VoucherStatus.DRAFT) Route.InvoiceEditor(v.id) else Route.InvoiceDetail(v.id))
                },
                onAddParty = { nav.push(Route.PartyEdit(null)) },
                onStock = { low -> nav.push(Route.StockSummary(low)) },
                onMoney = { c -> if (c == MoneyCard.CASH_BANK) nav.selectTab(Tab.MONEY) else nav.push(moneyCardRoute(c)) },
            )
            Tab.MONEY -> MoneyScreen(
                container, s,
                onNew = { t -> nav.push(Route.MoneyEditor(t)) },
                onOpenVoucher = { v -> nav.push(Route.MoneyDetail(v.id)) },
                onAccount = { id -> nav.push(Route.AccountLedger(id)) },
                onOutstanding = { r -> nav.push(Route.Outstanding(r)) },
                onDocuments = { t -> nav.push(Route.Documents(t)) },
                onReports = { nav.push(Route.Reports) },
            )
            Tab.PARTIES -> PartyListScreen(container, s) { id -> nav.push(Route.PartyEdit(id)) }
            Tab.ITEMS -> ItemListScreen(
                container, s,
                onOpen = { id -> nav.push(Route.ItemEdit(id)) },
                onStock = { low -> nav.push(Route.StockSummary(low)) },
            )
            Tab.MORE -> MoreScreen(
                container, s,
                onEditBusiness = { nav.push(Route.BusinessEdit) },
                onInvoiceTemplates = { nav.push(Route.InvoiceTemplates) },
                onViewIntroduction = { nav.push(Route.Introduction) },
                onPurchases = { nav.push(Route.Purchases) },
                onStock = { nav.push(Route.StockSummary()) },
                onGodowns = { nav.push(Route.Godowns) },
                onReports = { nav.push(Route.Reports) },
                onBooks = { nav.push(Route.BooksLock) },
                onAudit = { nav.push(Route.AuditLog) },
            )
        }
        is Route.PartyEdit -> PartyEditScreen(
            container, s, route.partyId, onDone = nav::pop, backHandler = backHandler,
            onLedger = { id -> nav.replace(Route.PartyLedger(id)) },
        )
        is Route.ItemEdit -> ItemEditScreen(
            container, s, route.itemId, onDone = nav::pop, backHandler = backHandler,
            onOpenStock = { id -> nav.replace(Route.ItemStock(id)) },
        )
        is Route.InvoiceEditor -> InvoiceEditorScreen(
            container, s, route.voucherId,
            onClose = nav::pop,
            onFinished = { id, _ -> nav.replace(Route.InvoiceDetail(id)) },
            backHandler = backHandler,
            newType = route.type,
            returnOf = route.returnOf,
        )
        Route.Purchases -> InvoiceListScreen(
            container, s,
            onNew = { nav.push(Route.InvoiceEditor(null, VoucherType.PURCHASE)) },
            onOpen = { v ->
                nav.push(if (v.status == VoucherStatus.DRAFT) Route.InvoiceEditor(v.id) else Route.InvoiceDetail(v.id))
            },
            onAddParty = { nav.push(Route.PartyEdit(null)) },
            purchases = true,
            onBack = nav::pop,
        )
        is Route.Documents -> InvoiceListScreen(
            container, s,
            onNew = { nav.push(Route.InvoiceEditor(null, route.type)) },
            onOpen = { v ->
                nav.push(if (v.status == VoucherStatus.DRAFT) Route.InvoiceEditor(v.id) else Route.InvoiceDetail(v.id))
            },
            onAddParty = { nav.push(Route.PartyEdit(null)) },
            onBack = nav::pop,
            docType = route.type,
        )
        is Route.MoneyEditor -> MoneyEditorScreen(
            container, s, route.type, route.partyId, route.invoiceId,
            onDone = { id -> if (id != null) nav.replace(Route.MoneyDetail(id)) else nav.pop() },
            backHandler = backHandler,
        )
        is Route.MoneyDetail -> MoneyDetailScreen(container, s, route.voucherId, onBack = nav::pop)
        is Route.AccountLedger -> AccountScreen(
            container, s, route.accountId,
            onBack = nav::pop,
            onOpenVoucher = { id, type -> nav.push(if (type.usesPaymentAccount()) Route.MoneyDetail(id) else Route.InvoiceDetail(id)) },
        )
        is Route.Outstanding -> OutstandingScreen(container, s, route.receivable, onBack = nav::pop, onParty = { id -> nav.push(Route.PartyLedger(id)) })
        is Route.PartyLedger -> PartyLedgerScreen(
            container, s, route.partyId,
            onBack = nav::pop,
            onRecordPayment = { t -> nav.push(Route.MoneyEditor(t, route.partyId)) },
            onStatement = {
                val fy = ReportPeriod.financialYearOf(container.today(), s.settings.financialYearStartMonth)
                nav.push(Route.Report(ReportKind.PARTY_STATEMENT.key, fy.from.toString(), fy.to.toString(), route.partyId))
            },
        )
        is Route.StockSummary -> StockSummaryScreen(
            container, s, route.lowOnly,
            onOpenItem = { id -> nav.push(Route.ItemStock(id)) },
            onAdjust = { nav.push(Route.StockEntry(null, transfer = false)) },
            onTransfer = { nav.push(Route.StockEntry(null, transfer = true)) },
            onGodowns = { nav.push(Route.Godowns) },
            onLabels = { nav.push(Route.BarcodeLabels(emptyList())) },
            onBack = nav::pop,
        )
        is Route.ItemStock -> ItemStockScreen(
            container, s, route.itemId,
            onBack = nav::pop,
            onAdjust = { nav.push(Route.StockEntry(route.itemId, transfer = false)) },
            onTransfer = { nav.push(Route.StockEntry(route.itemId, transfer = true)) },
            onEditItem = { nav.push(Route.ItemEdit(route.itemId)) },
            onLabels = { nav.push(Route.BarcodeLabels(listOf(route.itemId))) },
        )
        is Route.StockEntry -> StockEntryScreen(container, s, route.itemId, route.transfer, onDone = nav::pop, backHandler = backHandler)
        Route.Godowns -> GodownsScreen(container, s, onBack = nav::pop)
        is Route.BarcodeLabels -> BarcodeLabelsScreen(container, s, route.itemIds, onBack = nav::pop, backHandler = backHandler)
        is Route.InvoiceDetail -> InvoiceDetailScreen(
            container, s, route.voucherId,
            onBack = nav::pop,
            onEditDraft = { id -> nav.replace(Route.InvoiceEditor(id)) },
            onViewPdf = { id -> nav.push(Route.InvoicePdf(id)) },
            onReturn = { id -> nav.push(Route.InvoiceEditor(null, VoucherType.DEBIT_NOTE, returnOf = id)) },
            onConvert = { id -> nav.push(Route.InvoiceEditor(null, VoucherType.SALE, returnOf = id)) },
            onRecordPayment = { v ->
                val t = if (v.type == VoucherType.PURCHASE) VoucherType.PAYMENT_OUT else VoucherType.PAYMENT_IN
                nav.push(Route.MoneyEditor(t, v.partyId, v.id))
            },
        )
        Route.Reports -> ReportsScreen(
            container, s, onBack = nav::pop,
            onOpen = { k, p, party -> nav.push(Route.Report(k.key, p.from.toString(), p.to.toString(), party)) },
            onAudit = { nav.push(Route.AuditLog) },
            onBooks = { nav.push(Route.BooksLock) },
        )
        is Route.Report -> {
            val kind = ReportKind.of(route.report)
            val period = runCatching { ReportPeriod(LocalDate.parse(route.from), LocalDate.parse(route.to)) }.getOrNull()
            if (kind == null || period == null) nav.pop() else ReportScreen(container, s, kind, period, route.partyId, onBack = nav::pop)
        }
        Route.AuditLog -> AuditLogScreen(container, s, onBack = nav::pop)
        Route.BooksLock -> BooksLockScreen(container, s, onBack = nav::pop)
        is Route.InvoicePdf -> InvoicePdfScreen(container, s, route.voucherId, onBack = nav::pop, backHandler = backHandler)
        Route.BusinessEdit -> BusinessEditScreen(container, s, onDone = nav::pop, backHandler = backHandler)
        Route.InvoiceTemplates -> InvoiceTemplatesScreen(container, s, onBack = nav::pop, backHandler = backHandler)
        Route.Introduction -> OnboardingScreen(
            mode = OnboardingMode.REPLAY,
            onFinish = {
                container.onboarding.markCompleted()
                nav.pop()
            },
            backHandler = backHandler,
        )
    }
}

private fun moneyCardRoute(c: MoneyCard): Route = when (c) {
    MoneyCard.RECEIVABLE -> Route.Outstanding(true)
    MoneyCard.PAYABLE -> Route.Outstanding(false)
    MoneyCard.CASH_BANK -> Route.Home(Tab.MONEY)
}
