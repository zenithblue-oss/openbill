package app.openbill.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.openbill.ui.AppContainer
import app.openbill.ui.components.LocalAppMessages
import app.openbill.ui.pdf.ImageKind
import app.openbill.ui.pdf.LogoImage
import app.openbill.ui.pdf.decodeImageBitmap
import app.openbill.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Same look as [FirmImagePicker] for first-run setup: the image is held in memory until the firm exists. */
@Composable
fun PendingFirmImagePicker(container: AppContainer, image: LogoImage?, onChange: (LogoImage?) -> Unit, title: String, hint: String) {
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    var busy by remember { mutableStateOf(false) }
    val bitmap = remember(image) { image?.let { runCatching { decodeImageBitmap(it.bytes) }.getOrNull() } }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.padding(6.dp))
            } else {
                Text("No ${title.lowercase()}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF5B6569), textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { container.platform.pickImage(600) }
                            .onSuccess { if (it != null) onChange(it) }
                            .onFailure { messages?.show("Could not use that image: ${it.message ?: "unsupported file"}") }
                        busy = false
                    }
                }, enabled = !busy) { Text(if (image == null) "Choose image" else "Replace") }
                if (image != null) TextButton(onClick = { onChange(null) }, enabled = !busy) { Text("Remove") }
            }
        }
    }
}

/**
 * Business logo or signature: preview with choose/replace and remove. The image is stored
 * right away in app-private storage (not the database) and used by every invoice template.
 */
@Composable
fun FirmImagePicker(container: AppContainer, firmId: String, kind: ImageKind, title: String, hint: String) {
    val scope = rememberCoroutineScope()
    val messages = LocalAppMessages.current
    val version by container.invoiceStyles.imageVersion.collectAsState()
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(firmId, kind, version) {
        bitmap = runCatching { container.platform.loadFirmImage(firmId, kind)?.let { decodeImageBitmap(it.bytes) } }.getOrNull()
    }
    fun change(remove: Boolean) {
        busy = true
        scope.launch {
            runCatching {
                if (remove) {
                    container.platform.saveFirmImage(firmId, kind, null)
                    true
                } else {
                    val picked = container.platform.pickImage(600)
                    if (picked != null) container.platform.saveFirmImage(firmId, kind, picked)
                    picked != null
                }
            }.onSuccess { changed ->
                if (changed) {
                    container.invoiceStyles.imageVersion.value++
                    messages?.show(if (remove) "$title removed" else "$title saved")
                }
            }.onFailure { messages?.show("Could not use that image: ${it.message ?: "unsupported file"}") }
            busy = false
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(12.dp)).background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val b = bitmap
            if (b != null) {
                Image(b, contentDescription = title, contentScale = ContentScale.Fit, modifier = Modifier.padding(6.dp))
            } else {
                Text("No ${title.lowercase()}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF5B6569), textAlign = TextAlign.Center)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                OutlinedButton(onClick = { change(false) }, enabled = !busy) { Text(if (bitmap == null) "Choose image" else "Replace") }
                if (bitmap != null) TextButton(onClick = { change(true) }, enabled = !busy) { Text("Remove") }
            }
        }
    }
}
