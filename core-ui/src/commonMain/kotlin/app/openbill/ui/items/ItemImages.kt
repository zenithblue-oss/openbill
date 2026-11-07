package app.openbill.ui.items

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.openbill.ui.AppContainer
import app.openbill.ui.invoice.Monogram
import app.openbill.ui.pdf.decodeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Long edge of a stored item photo: small enough for lists and backups (a PNG this size stays well under 300 KB). */
const val ITEM_IMAGE_EDGE_PX = 256

/**
 * The item's photo, or its monogram when it has none. Reloads when any image changes
 * ([app.openbill.ui.invoice.InvoiceStyles.imageVersion]).
 */
// ponytail: decodes per visible row; add an in-memory cache if long lists stutter.
@Composable
fun ItemThumbnail(container: AppContainer, itemId: String, name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, monogram: Boolean = true) {
    val version by container.invoiceStyles.imageVersion.collectAsState()
    val bitmap by produceState<ImageBitmap?>(null, itemId, version) {
        value = withContext(Dispatchers.Default) {
            runCatching { container.platform.loadItemImage(itemId)?.let { decodeImageBitmap(it.bytes) } }.getOrNull()
        }
    }
    val b = bitmap
    if (b == null) {
        if (monogram) Monogram(name, modifier.size(size))
    } else {
        Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(RoundedCornerShape(8.dp)))
    }
}
