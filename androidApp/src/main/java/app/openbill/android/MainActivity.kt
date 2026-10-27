package app.openbill.android

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import app.openbill.ui.OpenBillApp
import app.openbill.ui.more.LocalBackupService
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.openbill.ui.components.ProgressOverlay
import app.openbill.ui.theme.OpenBillTheme
import kotlinx.coroutines.CompletableDeferred

class MainActivity : FragmentActivity() {
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

    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
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
                openFile = { types ->
                    pendingOpenFile?.complete(null)
                    val d = CompletableDeferred<Uri?>().also { pendingOpenFile = it }
                    openFile.launch(types)
                    d.await()
                },
                requestPermission = { permission ->
                    if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        true
                    } else {
                        pendingPermission?.complete(false)
                        val d = CompletableDeferred<Boolean>().also { pendingPermission = it }
                        requestPermission.launch(permission)
                        d.await()
                    }
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
            var ready by remember { mutableStateOf(app.startUp.isCompleted) }
            LaunchedEffect(Unit) { app.startUp.await(); ready = true }
            if (!ready) {
                OpenBillTheme { ProgressOverlay(app.startUpProgress.collectAsState().value, "Starting OpenBill") }
            } else {
                CompositionLocalProvider(LocalBackupService provides backupService) {
                    OpenBillApp(
                        container = app.container,
                        backHandler = { enabled, onBack -> BackHandler(enabled = enabled, onBack = onBack) },
                    )
                }
            }
        }
    }

    // Null while the database is still starting up (the lock cannot be engaged yet).
    private fun appLock() = (application as OpenBillApplication).takeIf { it.startUp.isCompleted }?.container?.appLock

    // App lock: relock after a minute away (short trips to a picker or share sheet do not count).
    override fun onStart() {
        super.onStart()
        appLock()?.onForeground(System.currentTimeMillis())
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) appLock()?.onBackground(System.currentTimeMillis())
    }

    override fun onDestroy() {
        (application as OpenBillApplication).platformServices.detach(this)
        super.onDestroy()
    }
}
