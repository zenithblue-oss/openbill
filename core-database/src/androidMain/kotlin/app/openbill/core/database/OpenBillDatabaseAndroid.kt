package app.openbill.core.database

import android.content.Context
import androidx.room.Room

fun openBillDatabase(context: Context, name: String): OpenBillDatabase =
    openBillDatabase(
        Room.databaseBuilder<OpenBillDatabase>(
            context.applicationContext,
            name,
        ),
    )
