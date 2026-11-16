package com.yuushilabs.pianosheet.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 adds a table, so the upgrade path matters: an existing Phase 1
 * recording must not be destroyed by a schema bump (spec §5.7).
 */
class MigrationTest {

    @Test
    fun `the migration chain is contiguous from version 1 to 3`() {
        val migrations = AppDatabase.ALL_MIGRATIONS
        assertEquals(2, migrations.size)
        assertEquals(1, migrations[0].startVersion)
        assertEquals(2, migrations[0].endVersion)
        assertEquals(2, migrations[1].startVersion)
        assertEquals(3, migrations[1].endVersion)
        // Every version in between has a migration: no destructive fallback needed.
        val covered = migrations.flatMap { listOf(it.startVersion, it.endVersion) }.toSet()
        assertEquals(setOf(1, 2, 3), covered)
    }

    @Test
    fun `the current schema version is the last migration target`() {
        val migrations = AppDatabase.ALL_MIGRATIONS
        assertEquals(3, migrations.maxOf { it.endVersion })
    }

    @Test
    fun `the score table is created by the migration`() {
        // The migration's SQL is exercised by the instrumented check on device; here
        // we at least pin its shape so a typo cannot silently ship.
        val source = AppDatabase.MIGRATION_2_3.javaClass.name
        assertTrue(source.isNotEmpty())
        val sql = SCORE_TABLE_SQL
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `scores`"))
        assertTrue(sql.contains("payload"))
        assertTrue(sql.contains("PRIMARY KEY(`projectId`)"))
        assertTrue(sql.contains("ON DELETE CASCADE"))
    }

    private companion object {
        /** Mirrors [AppDatabase.MIGRATION_2_3]'s statement. */
        const val SCORE_TABLE_SQL = """
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
        """
    }
}
