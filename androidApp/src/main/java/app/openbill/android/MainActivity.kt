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
import androidx.compose.runtime.CompositionLocalProvider
import app.openbill.ui.OpenBillApp
import app.openbill.ui.more.LocalBackupService
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

    private var pendingFile: CompletableDeferred<Uri?>? = null
    private val createFile = registerForActivityResult(
        object : ActivityResultContracts.CreateDocument("application/octet-stream") {
            override fun createIntent(context: android.content.Context, input: String) =
                super.createIntent(context, input).setType(
                    when (input.substringAfterLast('.').lowercase()) {
                        "csv" -> "text/csv"
                        "json" -> "application/json"
                        "xml" -> "text/xml"
                        else -> "text/plain"
                    },
                )
        },
    ) { uri ->
        pendingFile?.complete(uri)
        pendingFile = null
    }

    private var pendingOpenFile: CompletableDeferred<Uri?>? = null
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingOpenFile?.complete(uri)
        pendingOpenFile = null
    }

    private var pendingBackupCreate: CompletableDeferred<Uri?>? = null
    private var pendingBackupOpen: CompletableDeferred<Uri?>? = null
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        pendingBackupCreate?.complete(uri)
        pendingBackupCreate = null
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingBackupOpen?.complete(uri)
        pendingBackupOpen = null
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
                createFile = { name ->
                    pendingFile?.complete(null)
                    val d = CompletableDeferred<Uri?>().also { pendingFile = it }
                    createFile.launch(name)
                    d.await()
                },
                openFile = {
                    pendingOpenFile?.complete(null)
                    val d = CompletableDeferred<Uri?>().also { pendingOpenFile = it }
                    openFile.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel"))
                    d.await()
                },
            ),
        )
        val backupService = AndroidBackupService(
            context = applicationContext,
            databaseName = OpenBillApplication.DATABASE_NAME,
            appVersion = app.platformServices.appVersion,
            closeDatabase = { app.database.close() },
            createDocument = { name ->
                pendingBackupCreate?.complete(null)
                val d = CompletableDeferred<Uri?>().also { pendingBackupCreate = it }
                createBackup.launch(name)
                d.await()
            },
            openDocument = {
                pendingBackupOpen?.complete(null)
                val d = CompletableDeferred<Uri?>().also { pendingBackupOpen = it }
                openBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                d.await()
            },
        )
        setContent {
            CompositionLocalProvider(LocalBackupService provides backupService) {
                OpenBillApp(
                    container = container,
                    backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
                )
            }
        }
    }

    override fun onDestroy() {
        (application as OpenBillApplication).platformServices.detach(this)
        super.onDestroy()
    }
}
