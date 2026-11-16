package com.yuushilabs.pianosheet.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun find(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ProjectEntity)

    @Query("UPDATE projects SET status = :status, noteCount = :noteCount, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, noteCount: Int, updatedAt: Long)

    @Query("UPDATE projects SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)
}

@Dao
interface RecordingAssetDao {
    @Query("SELECT * FROM recording_assets WHERE projectId = :projectId LIMIT 1")
    suspend fun findByProject(projectId: String): RecordingAssetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: RecordingAssetEntity)
}

@Dao
interface NoteEventDao {
    @Query(
        "SELECT * FROM note_events WHERE projectId = :projectId " +
            "ORDER BY onsetMs ASC, pitchMidi ASC, sequence ASC",
    )
    suspend fun findByProject(projectId: String): List<NoteEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<NoteEventEntity>)

    @Query("DELETE FROM note_events WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}

@Dao
interface ScoreDao {
    @Query("SELECT * FROM scores WHERE projectId = :projectId LIMIT 1")
    suspend fun findByProject(projectId: String): ScoreEntity?

    @Query("SELECT * FROM scores WHERE projectId = :projectId LIMIT 1")
    fun observeByProject(projectId: String): Flow<ScoreEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(score: ScoreEntity)

    @Query("DELETE FROM scores WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}
