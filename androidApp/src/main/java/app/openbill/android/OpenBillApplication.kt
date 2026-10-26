package app.openbill.android

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import android.content.pm.ApplicationInfo
import app.openbill.core.data.roomRepositories
import app.openbill.core.backup.OpenBillBackup
import app.openbill.core.database.OPENBILL_DATABASE_VERSION
import app.openbill.core.database.openBillDatabase
import java.io.File
import app.openbill.ui.AppContainer

class OpenBillApplication : Application() {
    val platformServices: AndroidPlatformServices by lazy {
        AndroidPlatformServices(
            context = this,
            isDebugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            appVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "dev",
        )
    }

    /** Manual DI root: one database and one container per process. */
    val database by lazy {
        // Automatic pre-migration backup (docs/03-architecture.md section 14); best effort.
        runCatching {
            OpenBillBackup.backupBeforeMigration(
                androidDataLocation(this, DATABASE_NAME), File(filesDir, "backups"),
                OPENBILL_DATABASE_VERSION, platformServices.appVersion,
            )
        }
        openBillDatabase(this, DATABASE_NAME)
    }

    override fun onCreate() {
        super.onCreate()
        // Daily automatic backup into app storage; the newest 7 are kept (OpenBillBackup.autoBackup).
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "auto-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS).build(),
        )
    }

    val container: AppContainer by lazy {
        AppContainer(repositories = database.roomRepositories(), platform = platformServices)
    }

    internal companion object {
        const val DATABASE_NAME = "openbill.db"
    }
}

/** Runs [OpenBillBackup.autoBackup]; it is a no-op when the last automatic backup is recent. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as OpenBillApplication
        return runCatching {
            OpenBillBackup.autoBackup(
                androidDataLocation(app, OpenBillApplication.DATABASE_NAME), File(app.filesDir, "backups"), app.platformServices.appVersion,
            )
        }.fold({ Result.success() }, { Result.retry() })
    }
}
