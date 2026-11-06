package app.openbill.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** v13: recurring invoice schedules. The template voucher has no foreign key so deleting it leaves the schedule to report the problem. */
@Entity(
    tableName = "recurring_schedules",
    foreignKeys = [
        ForeignKey(
            entity = FirmEntity::class,
            parentColumns = ["id"],
            childColumns = ["firmId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["firmId"])],
)
data class RecurringScheduleEntity(
    @PrimaryKey val id: String,
    val firmId: String,
    val templateVoucherId: String,
    val name: String,
    /** Lowercase `RecurrenceFrequency` name. */
    val frequency: String,
    val repeatEvery: Int,
    val startDate: String,
    val endDate: String?,
    val maxCount: Int?,
    val nextIndex: Int,
    val generatedCount: Int,
    val autoPost: Boolean,
    val paused: Boolean,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** v17: copy the latest invoice this schedule made instead of the original; off by default. */
    @androidx.room.ColumnInfo(defaultValue = "0") val copyLatest: Boolean = false,
    /** v17: the newest invoice this schedule made (no foreign key: it may be deleted). */
    @androidx.room.ColumnInfo(defaultValue = "NULL") val lastVoucherId: String? = null,
)
