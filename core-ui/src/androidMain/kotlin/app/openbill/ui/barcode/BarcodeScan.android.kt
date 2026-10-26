package app.openbill.ui.barcode

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.openbill.ui.components.LocalAppMessages
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

actual val platformHasCameraScanner: Boolean = true

@Composable
actual fun BarcodeScanButton(onScanned: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    if (!remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }) return
    val messages = LocalAppMessages.current
    var open by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) open = true else messages?.show("Allow camera access to scan barcodes")
    }
    IconButton(
        onClick = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (granted) open = true else permission.launch(Manifest.permission.CAMERA)
        },
        modifier = modifier,
    ) { Icon(BarcodeIcon, contentDescription = "Scan barcode") }
    if (open) ScannerDialog(onResult = { open = false; onScanned(it) }, onDismiss = { open = false })
}

/** Full-screen camera preview; the first decoded code closes it. */
@Composable
private fun ScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val context = LocalContext.current
        val owner = LocalLifecycleOwner.current
        val result by rememberUpdatedState(onResult)
        val previewView = remember { PreviewView(context) }
        var failure by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(owner) {
            val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            val executor = Executors.newSingleThreadExecutor()
            val main = ContextCompat.getMainExecutor(context)
            val done = AtomicBoolean(false)
            analysis.setAnalyzer(executor, ZxingAnalyzer { text -> if (done.compareAndSet(false, true)) main.execute { result(text) } })
            try {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                failure = "Camera unavailable: ${e.message}"
            }
            try {
                awaitCancellation()
            } finally {
                provider.unbind(preview, analysis)
                executor.shutdown()
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            IconButton(onClick = onDismiss, modifier = Modifier.safeDrawingPadding().align(Alignment.TopStart)) {
                Icon(Icons.Filled.Close, contentDescription = "Close scanner", tint = Color.White)
            }
            Text(
                failure ?: "Point the camera at a barcode",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.safeDrawingPadding().align(Alignment.BottomCenter).padding(24.dp),
            )
        }
    }
}

/** Decodes the luminance (Y) plane with ZXing, trying the frame as-is and rotated 90° (portrait use). */
private class ZxingAnalyzer(private val onFound: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.CODE_128, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.QR_CODE,
                ),
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun analyze(image: ImageProxy) {
        image.use {
            val plane = it.planes[0]
            val stride = plane.rowStride
            val w = it.width
            val h = it.height
            val y = ByteArray(plane.buffer.remaining()).also { b -> plane.buffer.get(b) }
            (decode(PlanarYUVLuminanceSource(y, stride, h, 0, 0, w, h, false)) ?: decode(rotated(y, stride, w, h)))?.let(onFound)
        }
    }

    private fun rotated(y: ByteArray, stride: Int, w: Int, h: Int): PlanarYUVLuminanceSource {
        val out = ByteArray(w * h)
        for (row in 0 until h) for (col in 0 until w) out[col * h + (h - 1 - row)] = y[row * stride + col]
        return PlanarYUVLuminanceSource(out, h, w, 0, 0, h, w, false)
    }

    private fun decode(source: PlanarYUVLuminanceSource): String? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
