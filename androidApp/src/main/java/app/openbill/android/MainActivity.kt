package app.openbill.android

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.openbill.ui.OpenBillApp
import kotlinx.coroutines.CompletableDeferred

class MainActivity : ComponentActivity() {
    private var pendingPick: CompletableDeferred<Uri?>? = null
    private var pendingCreate: CompletableDeferred<Uri?>? = null

    // Photo picker: no storage permission needed (Android 11+ native, backported via Play services).
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pendingPick?.complete(uri)
        pendingPick = null
    }
    private val createPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        pendingCreate?.complete(uri)
        pendingCreate = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Business forms are not logins: stop password managers from offering to save
        // party names and GSTINs as credentials.
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        val app = application as OpenBillApplication
        val container = app.container
        app.platformServices.attach(
            ActivityHooks(
                activity = this,
                pickImage = {
                    pendingPick?.complete(null)
                    val d = CompletableDeferred<Uri?>().also { pendingPick = it }
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    d.await()
                },
                createPdf = { name ->
                    pendingCreate?.complete(null)
                    val d = CompletableDeferred<Uri?>().also { pendingCreate = it }
                    createPdf.launch(name)
                    d.await()
                },
            ),
        )
        setContent {
            OpenBillApp(
                container = container,
                backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
            )
        }
    }

    override fun onDestroy() {
        (application as OpenBillApplication).platformServices.detach(this)
        super.onDestroy()
    }
}
