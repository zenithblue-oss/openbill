package app.openbill.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.openbill.core.domain.model.VoucherStatus
import app.openbill.core.model.Money
import app.openbill.ui.format.Fmt
import app.openbill.ui.nav.LocalSearchFocus
import app.openbill.ui.nav.WidthClass
import app.openbill.ui.theme.LocalExtendedColors
import app.openbill.ui.theme.LocalReducedMotion
import app.openbill.ui.theme.Motion
import app.openbill.ui.theme.Spacing
import app.openbill.ui.theme.motionTween
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Adaptive helpers

/** Width class of the window the app is drawn in; set once by the app shell. */
val LocalWidthClass = staticCompositionLocalOf { WidthClass.COMPACT }

/** Page gutter for the current width class. */
val pageGutter
    @Composable get() = if (LocalWidthClass.current == WidthClass.COMPACT) Spacing.gutter else Spacing.gutterWide

/** Centers [content] and caps it at a readable width on tablets and desktop windows. */
@Composable
fun CenteredContent(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = Spacing.contentMaxWidth).fillMaxSize(), content = content)
    }
}

// ---------------------------------------------------------------------------------------------
// App-wide messages (snackbars)

/** Shows snackbars from any screen; survives the screen that asked for it leaving composition. */
class AppMessages(val hostState: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String, actionLabel: String? = null, onAction: (suspend () -> Unit)? = null) {
        hostState.currentSnackbarData?.dismiss()
        scope.launch {
            val result = hostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = actionLabel == null,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }
}

val LocalAppMessages = staticCompositionLocalOf<AppMessages?> { null }

@Composable
fun AppSnackbarHost() {
    LocalAppMessages.current?.let { SnackbarHost(it.hostState) }
}

// ---------------------------------------------------------------------------------------------
// Top app bars

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}

/** Small top app bar for detail and form screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { if (onBack != null) BackButton(onBack) },
        actions = { actions() },
        scrollBehavior = scrollBehavior,
    )
}

/** Medium collapsing top app bar for the top-level lists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollapsingTopBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: @Composable () -> Unit = {},
) {
    MediumTopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = { actions() },
        scrollBehavior = scrollBehavior,
    )
}

// ---------------------------------------------------------------------------------------------
// Form fields

@Composable
fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    singleLine: Boolean = true,
    supporting: String? = null,
    placeholder: String? = null,
    prefix: String? = null,
    suffix: String? = null,
    imeAction: ImeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        isError = error != null,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        placeholder = placeholder?.let { { Text(it) } },
        prefix = prefix?.let { { Text(it) } },
        suffix = suffix?.let { { Text(it) } },
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, capitalization = capitalization, imeAction = imeAction),
        keyboardActions = if (onImeAction != null) KeyboardActions(onDone = { onImeAction() }, onGo = { onImeAction() }) else KeyboardActions.Default,
    )
}

/** A read-only field that opens a picker (sheet or dialog) when tapped. */
@Composable
fun PickerField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    supporting: String? = null,
    placeholder: String = "Tap to choose",
    trailingIcon: ImageVector = Icons.Filled.ArrowDropDown,
) {
    // The overlay is the single focus/tap target (Tab, Enter and Space open the picker); it
    // shares its interaction source with the field so the field shows the focused outline.
    val interaction = remember { MutableInteractionSource() }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { Icon(trailingIcon, contentDescription = null) },
            isError = error != null,
            supportingText = (error ?: supporting)?.let { { Text(it) } },
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().focusProperties { canFocus = false },
        )
        Box(
            Modifier
                .matchParentSize()
                .padding(top = 8.dp)
                .semantics { contentDescription = "$label: ${value.ifEmpty { placeholder }}" }
                .onPreviewKeyEvent { e ->
                    val opens = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar || e.key == Key.DirectionDown
                    if (opens && e.type == KeyEventType.KeyUp) onClick()
                    opens
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "Choose $label",
                    onClick = onClick,
                ),
        )
    }
}

/** Vertical rhythm for forms: 8 dp between fields so supporting text never touches the next field. */
val FormSpacing = Arrangement.spacedBy(Spacing.sm)

// ---------------------------------------------------------------------------------------------
// Search

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSearchBar(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val requester = remember { FocusRequester() }
    val searchFocus = LocalSearchFocus.current
    DisposableEffect(searchFocus) {
        val unregister = searchFocus?.register(requester)
        onDispose { unregister?.invoke() }
    }
    DockedSearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier.focusRequester(requester),
                placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
                } else {
                    null
                },
            )
        },
        expanded = false,
        onExpandedChange = {},
        modifier = modifier.fillMaxWidth().padding(horizontal = pageGutter, vertical = Spacing.sm),
    ) {}
}

// ---------------------------------------------------------------------------------------------
// States

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(Spacing.lg))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Spacing.xs))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Spacing.lg))
            action()
        }
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

// ---------------------------------------------------------------------------------------------
// Money

/**
 * Amount that rolls to its new value: the old figure slides out and the new one in, upward
 * when the amount grows and downward when it shrinks. Snaps when reduced motion is on.
 */
@Composable
fun AnimatedMoney(
    amount: Money?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    placeholder: String = "-",
) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = amount,
        transitionSpec = { moneyTransition(initialState, targetState, reduced) },
        contentAlignment = Alignment.CenterEnd,
        modifier = modifier,
        label = "money",
    ) { value ->
        Text(value?.let { Fmt.inr(it) } ?: placeholder, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

private fun moneyTransition(from: Money?, to: Money?, reduced: Boolean): ContentTransform {
    if (reduced || from == null || to == null) return EnterTransition.None togetherWith ExitTransition.None
    val up = to > from
    val enter = slideInVertically(motionTween(false, Motion.SHORT4, Motion.EmphasizedDecelerate)) { h -> if (up) h / 2 else -h / 2 } +
        fadeIn(motionTween(false, Motion.SHORT4))
    val exit = slideOutVertically(motionTween(false, Motion.SHORT3, Motion.EmphasizedAccelerate)) { h -> if (up) -h / 2 else h / 2 } +
        fadeOut(motionTween(false, Motion.SHORT2))
    return ContentTransform(enter, exit, sizeTransform = SizeTransform(clip = false))
}

/** Label on the left, right-aligned tabular amount on the right. Label wraps; the amount never does. */
@Composable
fun AmountRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = Spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
        Text(label, style = style, modifier = Modifier.weight(1f), color = if (emphasize) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = style, color = valueColor, maxLines = 1, softWrap = false, textAlign = TextAlign.End)
    }
}

@Composable
fun AmountRow(label: String, amount: Money, modifier: Modifier = Modifier, emphasize: Boolean = false, negative: Boolean = false) {
    AmountRow(label, (if (negative && amount.paise != 0L) "−" else "") + Fmt.inr(amount), modifier, emphasize)
}

// ---------------------------------------------------------------------------------------------
// Headings, chips, buttons

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = Spacing.lg, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/** Status pill: colour plus icon plus word, so colour is never the only signal. */
@Composable
fun StatusChip(status: VoucherStatus, modifier: Modifier = Modifier) {
    val ext = LocalExtendedColors.current
    val scheme = MaterialTheme.colorScheme
    val (label, icon, colors) = when (status) {
        VoucherStatus.DRAFT -> Triple("Draft", Icons.Filled.Edit, scheme.secondaryContainer to scheme.onSecondaryContainer)
        VoucherStatus.POSTED -> Triple("Posted", Icons.Filled.CheckCircle, ext.successContainer to ext.onSuccessContainer)
        VoucherStatus.CANCELLED -> Triple("Cancelled", Icons.Filled.Close, scheme.errorContainer to scheme.onErrorContainer)
    }
    Surface(color = colors.first, contentColor = colors.second, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(
            Modifier.padding(start = Spacing.sm - 2.dp, end = Spacing.sm, top = Spacing.xxs, bottom = Spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Filter chip whose check mark grows in when selected. */
@Composable
fun SelectableChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = {
            AnimatedVisibility(
                visible = selected,
                enter = if (reduced) EnterTransition.None else fadeIn(motionTween(false, Motion.SHORT3)) + expandHorizontally(motionTween(false, Motion.SHORT4, Motion.EmphasizedDecelerate)),
                exit = if (reduced) ExitTransition.None else fadeOut(motionTween(false, Motion.SHORT2)) + shrinkHorizontally(motionTween(false, Motion.SHORT3, Motion.EmphasizedAccelerate)),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        modifier = modifier,
    )
}

/** Shrinks slightly while pressed: tactile feedback on top of the ripple. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(if (pressed && !reduced) 0.96f else 1f, motionTween(reduced, Motion.SHORT2), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Filled button with press feedback and an inline progress indicator while [busy]. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String = text,
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        interactionSource = interaction,
        modifier = modifier.pressScale(interaction),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.size(Spacing.sm))
            Text(busyText)
        } else {
            Text(text)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Dialogs

/** The shared "unsaved changes" guard used by every editor. */
@Composable
fun DiscardChangesDialog(
    message: String,
    onDiscard: () -> Unit,
    onKeepEditing: () -> Unit,
    onSave: (() -> Unit)? = null,
    saveLabel: String = "Save",
) {
    AlertDialog(
        onDismissRequest = onKeepEditing,
        title = { Text("Discard changes?") },
        text = { Text(message) },
        confirmButton = {
            if (onSave != null) {
                TextButton(onClick = onSave) { Text(saveLabel) }
            } else {
                TextButton(onClick = onKeepEditing) { Text("Keep editing") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                if (onSave != null) TextButton(onClick = onKeepEditing) { Text("Keep editing") }
            }
        },
    )
}

/** Column that lays out form fields with [FormSpacing]. */
@Composable
fun FormColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = FormSpacing, content = content)
}
