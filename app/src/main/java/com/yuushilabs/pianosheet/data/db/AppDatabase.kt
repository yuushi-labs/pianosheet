package com.yuushilabs.pianosheet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProjectEntity::class, RecordingAssetEntity::class, NoteEventEntity::class, ScoreEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun recordingAssetDao(): RecordingAssetDao
    abstract fun noteEventDao(): NoteEventDao
    abstract fun scoreDao(): ScoreDao

    companion object {

        /**
         * v1 -> v2: the raw-recording flag added with spec §10.2.  Existing takes
         * were captured before the flag existed; they are treated as unprocessed
         * because v1 only ever wrote the captured samples.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE recording_assets ADD COLUMN isUnprocessed INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        /**
         * v2 -> v3: the finished score (Phase 2).  Existing recordings and their
         * live events stay untouched — this only adds a table.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `scores` (
                        `projectId` TEXT NOT NULL,
                        `payload` TEXT NOT NULL,
                        `tempoBpm` REAL NOT NULL,
                        `timeSignature` TEXT NOT NULL,
                        `keySignature` TEXT NOT NULL,
                        `noteCount` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `denoiseProfile` TEXT,
                        `analysisMs` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`projectId`),
                        FOREIGN KEY(`projectId`) REFERENCES `projects`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_scores_projectId` ON `scores` (`projectId`)")
            }
        }

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
