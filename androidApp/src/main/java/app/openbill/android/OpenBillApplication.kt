package app.openbill.android

import android.app.Application
import android.content.pm.ApplicationInfo
import app.openbill.core.data.roomRepositories
import app.openbill.core.database.openBillDatabase
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
    val container: AppContainer by lazy {
        val database = openBillDatabase(this, DATABASE_NAME)
        AppContainer(repositories = database.roomRepositories(), platform = platformServices)
    }

    private companion object {
        const val DATABASE_NAME = "openbill.db"
    }
}
