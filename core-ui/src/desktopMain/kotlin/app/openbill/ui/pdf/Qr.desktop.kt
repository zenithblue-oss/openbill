package app.openbill.ui.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.jetbrains.skia.Image

/** QR via ZXing's encoder (Apache-2.0), entirely offline; medium error correction. */
actual fun encodeQr(text: String): QrMatrix? = runCatching {
    val code = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
    val m = code.matrix
    val n = m.width
    QrMatrix(n, BooleanArray(n * n) { i -> m.get(i % n, i / n).toInt() == 1 })
}.getOrNull()

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? = runCatching {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()
