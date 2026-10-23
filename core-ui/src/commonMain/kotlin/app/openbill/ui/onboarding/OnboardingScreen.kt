package app.openbill.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.openbill.ui.BackHandlerSlot
import app.openbill.ui.theme.BrandLogo
import app.openbill.ui.theme.LocalReducedMotion
import app.openbill.ui.theme.Motion
import app.openbill.ui.theme.Spacing
import app.openbill.ui.theme.platformPrefersKeyboard
import kotlin.math.abs
import kotlinx.coroutines.launch

/** Copy and scene for one page of the introduction. All wording is OpenBill's own. */
private class IntroPage(
    val title: String,
    val body: String,
    val points: List<String> = emptyList(),
    val chipsLabel: String? = null,
    val chips: List<String> = emptyList(),
    val artDescription: String,
    val scene: androidx.compose.ui.graphics.drawscope.DrawScope.(Frame) -> Unit,
)

private val pages = listOf(
    IntroPage(
        title = "Welcome to OpenBill",
        body = "Free GST billing, on your device.",
        points = listOf("Free and open source", "Works offline, no internet needed", "Your data stays with you"),
        artDescription = "The OpenBill logo building itself: a paper bill with a folded corner, an open ring and a total line.",
        scene = { welcomeScene(it) },
    ),
    IntroPage(
        title = "GST invoices in seconds",
        body = "Pick a party, add items, and the tax works itself out: CGST and SGST within your state, IGST outside it.",
        points = listOf("Place of supply decides the split", "Numbered in order, like INV-0001"),
        artDescription = "An invoice filling in line by line. The tax switches between CGST plus SGST and IGST while the total stays ₹4,720.00.",
        scene = { invoiceScene(it) },
    ),
    IntroPage(
        title = "PDFs ready to send",
        body = "Twelve invoice templates, from detailed GST layouts to 58 mm receipts. Your logo goes on top.",
        points = listOf("Share, print or save as PDF"),
        artDescription = "Three invoice templates, GST Detailed, Modern and Receipt, fanning out and taking turns at the front. A logo is stamped on, then share and print buttons appear.",
        scene = { templatesScene(it) },
    ),
    IntroPage(
        title = "Parties and items at hand",
        body = "Save customers, suppliers and products once, then pick them on any invoice. Search finds them as you type.",
        artDescription = "Parties and items arrive in a list. The search box types Ka and the list narrows to Kavya Traders and Kadai, steel.",
        scene = { partiesScene(it) },
    ),
    IntroPage(
        title = "Private by design",
        body = "Your bills are stored on this device. Nothing is uploaded.",
        points = listOf("No account", "No ads", "No tracking"),
        artDescription = "A phone and a laptop lock their screens. A cloud is crossed out and a shield with a check mark pulses between them.",
        scene = { privacyScene(it) },
    ),
    IntroPage(
        title = "On your phone and computer",
        body = "OpenBill runs on Android, Windows and Linux.",
        chipsLabel = "Coming next",
        chips = listOf("Inventory", "Payments", "Reports", "Command line", "Self-hosted sync"),
        artDescription = "A phone, a desktop monitor and a laptop, labelled Android, Windows and Linux, each showing OpenBill.",
        scene = { platformsScene(it) },
    ),
)

/** Number of pages, exposed for tests and callers that need it. */
internal val IntroPageCount: Int get() = pages.size

/**
 * The animated introduction. On first launch it ends in business setup; replayed from More it
 * ends back on More. [onFinish] runs for both finishing and skipping.
 */
@Composable
fun OnboardingScreen(mode: OnboardingMode, onFinish: () -> Unit, backHandler: BackHandlerSlot) {
    val reduced = LocalReducedMotion.current
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    // Reduced motion: page changes dip through a quick fade instead of sliding.
    val fade = remember { Animatable(1f) }
    var finished by remember { mutableStateOf(false) }

    fun perform(action: OnboardingAction) {
        when (action) {
            is OnboardingAction.GoTo -> scope.launch {
                if (reduced) {
                    fade.animateTo(0f, tween(Motion.SHORT2, easing = LinearEasing))
                    pager.scrollToPage(action.page)
                    fade.animateTo(1f, tween(Motion.SHORT3, easing = LinearEasing))
                } else {
                    pager.animateScrollToPage(action.page, animationSpec = tween(520, easing = Motion.Emphasized))
                }
            }
            OnboardingAction.Finish -> if (!finished) {
                finished = true
                onFinish()
            }
            OnboardingAction.None -> Unit
        }
    }

    val keyboard = platformPrefersKeyboard
    val page = pager.currentPage
    backHandler(OnboardingPaging.interceptsBack(page, mode, keyboard)) {
        perform(OnboardingPaging.forBack(pager.currentPage, mode, keyboard))
    }

    val focus = remember { FocusRequester() }
    // Enter presses a button that has keyboard focus (after Tab); otherwise it means "Next".
    var buttonFocused by remember { mutableStateOf(false) }
    val trackFocus = Modifier.onFocusChanged { buttonFocused = it.isFocused }
    LaunchedEffect(Unit) {
        // After the frame, so this wins over the shell's own root focus request.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }

    val bg = rememberBackdrop()
    val drift = if (reduced) {
        remember { mutableFloatStateOf(0f) }
    } else {
        rememberInfiniteTransition(label = "drift").animateFloat(
            0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "drift",
        )
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val pos = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (pages.size - 1).toFloat())
                drawBackdrop(bg, pos, drift.value)
            }
            .focusRequester(focus)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val key = when (e.key) {
                    Key.DirectionLeft -> OnboardingKey.LEFT
                    Key.DirectionRight -> OnboardingKey.RIGHT
                    // Enter on a focused button presses that button instead.
                    Key.Enter, Key.NumPadEnter -> if (!buttonFocused) OnboardingKey.ENTER else return@onPreviewKeyEvent false
                    Key.Escape -> OnboardingKey.ESCAPE
                    else -> return@onPreviewKeyEvent false
                }
                perform(OnboardingPaging.forKey(key, pager.currentPage, pages.size))
                true
            }
            .focusable(),
    ) {
        val wide = maxWidth >= 720.dp && maxWidth > maxHeight
        val gutter = if (wide) 40.dp else Spacing.xl
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TopRow(onSkip = { perform(OnboardingAction.Finish) }, gutter = gutter, buttonModifier = trackFocus)
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth().graphicsLayer { alpha = fade.value },
                beyondViewportPageCount = 1,
                key = { it },
            ) { index ->
                IntroPageContent(pages[index], index, pager, reduced, wide, gutter)
            }
            Row(Modifier.fillMaxWidth()) {
                if (wide) Spacer(Modifier.weight(1f))
                Controls(
                    pager = pager,
                    mode = mode,
                    onBack = { perform(OnboardingPaging.previous(pager.currentPage)) },
                    onNext = { perform(OnboardingPaging.next(pager.currentPage, pages.size)) },
                    buttonModifier = trackFocus,
                    modifier = Modifier.weight(1f).padding(start = gutter, end = gutter, bottom = Spacing.lg, top = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun TopRow(onSkip: () -> Unit, gutter: Dp, buttonModifier: Modifier) {
    Row(
        Modifier.fillMaxWidth().padding(start = gutter, end = gutter - Spacing.sm, top = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(rememberVectorPainter(BrandLogo.Mark), contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text("OpenBill", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        TextButton(onClick = onSkip, modifier = buttonModifier) { Text("Skip") }
    }
}

@Composable
private fun IntroPageContent(spec: IntroPage, index: Int, pager: PagerState, reduced: Boolean, wide: Boolean, gutter: Dp) {
    val clock = remember { mutableFloatStateOf(0f) }
    val isCurrent by remember(index) { derivedStateOf { pager.currentPage == index } }
    // Restart the scene each time this page becomes current; freeze it when it leaves.
    LaunchedEffect(isCurrent, reduced) {
        if (!isCurrent || reduced) return@LaunchedEffect
        clock.floatValue = 0f
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> clock.floatValue = (now - start) / 1_000_000f }
        }
    }
    val readClock = { clock.floatValue }
    val offset = { (pager.currentPage - index) + pager.currentPageOffsetFraction }

    // Reduced motion: no parallax or entrances; neighbours simply cross-fade while swiping.
    val pageModifier = if (reduced) Modifier.graphicsLayer { alpha = 1f - abs(offset()).coerceIn(0f, 1f) } else Modifier

    BoxWithConstraints(Modifier.fillMaxSize().then(pageModifier)) {
        if (wide) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                ArtCanvas(
                    spec.artDescription, readClock, offset, reduced,
                    Modifier.weight(1f).fillMaxHeight().padding(start = gutter, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.lg),
                    spec.scene,
                )
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    PageCopy(
                        spec, readClock, offset, reduced, large = true,
                        modifier = Modifier
                            .widthIn(max = 520.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(start = Spacing.lg, end = gutter, top = Spacing.lg, bottom = Spacing.lg),
                    )
                }
            }
        } else {
            val copyMax = maxHeight * 0.52f
            Column(Modifier.fillMaxSize()) {
                ArtCanvas(
                    spec.artDescription, readClock, offset, reduced,
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = gutter, vertical = Spacing.sm),
                    spec.scene,
                )
                PageCopy(
                    spec, readClock, offset, reduced, large = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = copyMax)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = gutter, vertical = Spacing.sm),
                )
            }
        }
    }
}

/** Fades and lifts an element in [delay] ms after its page becomes current, with parallax. */
private fun Modifier.entrance(clock: () -> Float, offset: () -> Float, reduced: Boolean, delay: Float, parallax: Float): Modifier =
    if (reduced) {
        this
    } else {
        graphicsLayer {
            val p = easeOut(seg(clock(), delay, 480f))
            alpha = p
            translationY = (1f - p) * 20.dp.toPx()
            translationX = offset() * parallax * size.width
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageCopy(
    spec: IntroPage,
    clock: () -> Float,
    offset: () -> Float,
    reduced: Boolean,
    large: Boolean,
    modifier: Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = if (large) Alignment.Start else Alignment.CenterHorizontally) {
        val align = if (large) TextAlign.Start else TextAlign.Center
        Text(
            spec.title,
            style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = align,
            modifier = Modifier.semantics { heading() }.entrance(clock, offset, reduced, 60f, 0.12f),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            spec.body,
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurfaceVariant,
            textAlign = align,
            modifier = Modifier.entrance(clock, offset, reduced, 160f, 0.2f),
        )
        if (spec.points.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.lg))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, if (large) Alignment.Start else Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                spec.points.forEachIndexed { i, point ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = cs.secondaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.entrance(clock, offset, reduced, 300f + i * 90f, 0.26f),
                    ) {
                        Row(Modifier.padding(start = Spacing.sm, end = Spacing.md, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(point, style = MaterialTheme.typography.labelLarge, color = cs.onSecondaryContainer)
                        }
                    }
                }
            }
        }
        if (spec.chipsLabel != null) {
            Spacer(Modifier.height(Spacing.lg))
            Text(
                spec.chipsLabel,
                style = MaterialTheme.typography.labelLarge,
                color = cs.tertiary,
                modifier = Modifier.entrance(clock, offset, reduced, 300f, 0.26f),
            )
            Spacer(Modifier.height(Spacing.sm))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, if (large) Alignment.Start else Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                spec.chips.forEachIndexed { i, chip ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cs.tertiaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.entrance(clock, offset, reduced, 380f + i * 80f, 0.3f),
                    ) {
                        Text(
                            chip,
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    pager: PagerState,
    mode: OnboardingMode,
    onBack: () -> Unit,
    onNext: () -> Unit,
    buttonModifier: Modifier,
    modifier: Modifier,
) {
    val page = pager.currentPage
    val last = page == pages.size - 1
    val reduced = LocalReducedMotion.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        PageIndicator(pager, Modifier.padding(bottom = Spacing.md))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = onBack,
                enabled = page > 0,
                modifier = buttonModifier.graphicsLayer { alpha = if (page > 0) 1f else 0f },
            ) { Text("Back") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onNext, modifier = buttonModifier.weight(1f, fill = false)) {
                AnimatedContent(
                    targetState = last,
                    transitionSpec = {
                        if (reduced) {
                            fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                        } else {
                            (fadeIn(tween(Motion.SHORT4, Motion.SHORT2)) + slideInVertically(tween(Motion.MEDIUM1, easing = Motion.EmphasizedDecelerate)) { it / 2 }) togetherWith
                                (fadeOut(tween(Motion.SHORT2)) + slideOutVertically(tween(Motion.SHORT4, easing = Motion.EmphasizedAccelerate)) { -it / 2 })
                        } using SizeTransform(clip = false) { _, _ -> tween(if (reduced) 0 else Motion.MEDIUM2, easing = Motion.Emphasized) }
                    },
                    label = "cta",
                ) { isLast ->
                    if (isLast) {
                        Text(mode.finishLabel, textAlign = TextAlign.Center)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Next")
                            Spacer(Modifier.width(Spacing.sm))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Dots that morph into a pill for the current page, following the swipe continuously. */
@Composable
private fun PageIndicator(pager: PagerState, modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.outlineVariant
    val count = pages.size
    val dot = 8.dp
    val pill = 28.dp
    val gap = 8.dp
    val label = "Page ${pager.currentPage + 1} of $count"
    Canvas(
        modifier
            .size(width = dot * (count - 1) + pill + gap * (count - 1), height = dot)
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val pos = pager.currentPage + pager.currentPageOffsetFraction
        val d = dot.toPx()
        val p = pill.toPx()
        val g = gap.toPx()
        var x = 0f
        for (i in 0 until count) {
            val near = clamp01(1f - abs(pos - i))
            val w = lerpF(d, p, easeInOut(near))
            drawRoundRect(
                mix(inactive, active, near),
                topLeft = Offset(x, 0f),
                size = Size(w, d),
                cornerRadius = CornerRadius(d / 2f),
            )
            x += w + g
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Backdrop: a gradient whose colours morph between pages, plus two soft drifting glows.
// ---------------------------------------------------------------------------------------------

private class Backdrop(val surface: Color, val tints: List<Color>, val glowA: Color, val glowB: Color)

@Composable
private fun rememberBackdrop(): Backdrop {
    val cs = MaterialTheme.colorScheme
    return remember(cs) {
        val accents = listOf(cs.primaryContainer, cs.secondaryContainer, cs.tertiaryContainer, cs.primaryContainer, cs.secondaryContainer, cs.tertiaryContainer)
        Backdrop(
            surface = cs.surface,
            tints = accents.map { mix(cs.surface, it, 0.75f) },
            glowA = cs.primary,
            glowB = cs.tertiary,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(b: Backdrop, pos: Float, drift: Float) {
    val i = pos.toInt().coerceIn(0, b.tints.lastIndex)
    val j = (i + 1).coerceAtMost(b.tints.lastIndex)
    val tint = mix(b.tints[i], b.tints[j], pos - i)
    drawRect(Brush.verticalGradient(0f to tint, 0.75f to b.surface, 1f to b.surface))
    val w = size.width
    val h = size.height
    // Glows slide against the swipe at different speeds: a deep parallax layer.
    val ax = w * (0.18f + 0.1f * drift) - pos * 0.06f * w
    val bx = w * (0.85f - 0.12f * drift) - pos * 0.03f * w
    val r = maxOf(w, h) * 0.45f
    drawCircle(
        Brush.radialGradient(listOf(b.glowA.copy(alpha = 0.10f), Color.Transparent), center = Offset(ax, h * 0.18f), radius = r),
        radius = r, center = Offset(ax, h * 0.18f),
    )
    drawCircle(
        Brush.radialGradient(listOf(b.glowB.copy(alpha = 0.08f), Color.Transparent), center = Offset(bx, h * (0.42f + 0.06f * drift)), radius = r * 0.8f),
        radius = r * 0.8f, center = Offset(bx, h * (0.42f + 0.06f * drift)),
    )
}
