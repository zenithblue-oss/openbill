package app.openbill.core.database

import androidx.room.migration.Migration

object DatabaseMigrations {
    val all: List<Migration> = emptyList()

    fun migrationsFor(
        startVersion: Int,
        endVersion: Int = OPENBILL_DATABASE_VERSION,
    ): List<Migration> {
        require(startVersion in 1..endVersion) {
            "Invalid migration range: $startVersion..$endVersion"
        }
        if (startVersion == endVersion) return emptyList()

        val selected = all
            .filter { it.startVersion >= startVersion && it.endVersion <= endVersion }
            .sortedBy { it.startVersion }

        var current = startVersion
        for (migration in selected) {
            require(migration.startVersion == current) {
                "Migration chain has a gap at version $current"
            }
            current = migration.endVersion
        }
        require(current == endVersion) {
            "Migration chain stops at version $current, expected $endVersion"
        }
        return selected
    }
}
