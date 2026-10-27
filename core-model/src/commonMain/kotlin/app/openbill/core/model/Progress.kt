package app.openbill.core.model

/** Progress of a long operation: [done] of [total] steps in [stage]; [total] null = unknown (indeterminate). */
data class Progress(val stage: String, val done: Long = 0, val total: Long? = null) {
    /** 0..1 when [total] is known. */
    val fraction: Float? get() = total?.let { if (it <= 0) 1f else (done.toFloat() / it).coerceIn(0f, 1f) }
}

/** Receives [Progress] updates; called from the worker thread. */
typealias OnProgress = (Progress) -> Unit

/** Start-up stages shown by the progress overlay on every platform. */
const val STARTUP_BACKUP_STAGE = "Backing up before upgrade"
const val STARTUP_MIGRATE_STAGE = "Upgrading database"
