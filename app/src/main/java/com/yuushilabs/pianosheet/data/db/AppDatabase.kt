package com.yuushilabs.pianosheet.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, RecordingAssetEntity::class, NoteEventEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun recordingAssetDao(): RecordingAssetDao
    abstract fun noteEventDao(): NoteEventDao
}
