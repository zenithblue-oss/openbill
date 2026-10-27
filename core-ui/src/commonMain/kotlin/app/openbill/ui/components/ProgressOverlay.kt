package app.openbill.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.openbill.core.model.Progress
import app.openbill.ui.theme.Spacing

/** "42% · 4200 of 10000" for a known total, else null. */
fun Progress.countLabel(): String? {
    val f = fraction ?: return null
    return "${(f * 100).toInt()}% · $done of $total"
}

/**
 * Shared loading screen for long operations: a determinate bar with percent and "x of y" when the
 * total is known, else an indeterminate bar with the stage label. Blocks touches to the screen
 * behind it; [onCancel] (null = not cancellable, e.g. restore) adds a Cancel button.
 */
@Composable
fun ProgressOverlay(progress: Progress?, title: String, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    Box(
        modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } },
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.widthIn(min = 280.dp, max = 440.dp).padding(Spacing.lg)) {
            Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                val stage = progress?.stage ?: "Starting…"
                Text(stage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val f = progress?.fraction
                if (f != null) {
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        progress.countLabel().orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (onCancel != null) {
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
            }
        }
    }
}
