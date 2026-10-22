package app.openbill.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import app.openbill.ui.format.Fmt
import app.openbill.ui.format.PickerDates
import app.openbill.ui.theme.LocalReducedMotion
import app.openbill.ui.theme.Motion
import app.openbill.ui.theme.Spacing
import app.openbill.ui.theme.motionTween
import app.openbill.ui.theme.platformPrefersKeyboard
import kotlinx.datetime.LocalDate

/**
 * Searchable single-choice picker in a modal bottom sheet. [header] sits above the options
 * (e.g. a "New party" quick action); [emptyText] shows when there are no options at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PickerSheet(
    title: String,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    supporting: (T) -> String? = { null },
    selected: (T) -> Boolean = { false },
    key: ((T) -> Any)? = null,
    searchable: Boolean = options.size > 8,
    emptyText: String = "Nothing to choose yet.",
    header: (@Composable () -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = searchable)
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    val filtered = if (q.isEmpty()) options else options.filter {
        label(it).contains(q, ignoreCase = true) || (supporting(it)?.contains(q, ignoreCase = true) ?: false)
    }
    val searchFocus = remember { FocusRequester() }
    if (searchable && platformPrefersKeyboard) {
        LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm).semantics { heading() },
            )
            if (searchable) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") } }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.xs).focusRequester(searchFocus),
                )
            }
            header?.invoke()
            if (header != null) HorizontalDivider()
            if (filtered.isEmpty()) {
                Text(
                    if (options.isEmpty()) emptyText else "Nothing matches \"$q\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                )
            }
            LazyColumn(Modifier.fillMaxWidth()) {
                items(filtered, key = key) { option ->
                    val isSelected = selected(option)
                    ListItem(
                        headlineContent = { Text(label(option), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = supporting(option)?.let { s -> { Text(s, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                        trailingContent = if (isSelected) {
                            { Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable { onSelect(option) },
                    )
                }
            }
        }
    }
}

/** Date field that opens the Material date picker; the value is a [LocalDate]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    PickerField(
        label = label,
        value = date?.let { Fmt.dateLong(it) } ?: "",
        onClick = { open = true },
        modifier = modifier,
        error = error,
        placeholder = "Choose a date",
        trailingIcon = Icons.Filled.DateRange,
    )
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date?.let { PickerDates.toUtcMillis(it) })
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onDateChange(PickerDates.fromUtcMillis(it)) }
                        open = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}

/**
 * Full-screen dialog drawn inside the window (M3 "full-screen dialog" pattern). The confirm
 * action lives in the top bar so the keyboard can never hide it, and the body pads for the IME.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenEditor(
    visible: Boolean,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) EnterTransition.None else slideInVertically(motionTween(false, Motion.MEDIUM2, Motion.EmphasizedDecelerate)) { it / 4 } + fadeIn(motionTween(false, Motion.SHORT4)),
        exit = if (reduced) ExitTransition.None else slideOutVertically(motionTween(false, Motion.SHORT4, Motion.EmphasizedAccelerate)) { it / 4 } + fadeOut(motionTween(false, Motion.SHORT3)),
    ) {
        // Swallow taps so nothing underneath reacts while the editor is open.
        Surface(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            color = MaterialTheme.colorScheme.surface,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") } },
                        actions = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
                    )
                },
            ) { padding ->
                CenteredContent(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pageGutter, vertical = Spacing.sm)) {
                        content()
                    }
                }
            }
        }
    }
}
