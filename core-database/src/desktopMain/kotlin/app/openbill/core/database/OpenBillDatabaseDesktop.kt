package app.openbill.core.database

import androidx.room.Room

fun openBillDatabase(path: String): OpenBillDatabase =
    openBillDatabase(
        Room.databaseBuilder<OpenBillDatabase>(name = path),
    )
