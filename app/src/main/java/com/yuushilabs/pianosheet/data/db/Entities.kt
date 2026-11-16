package com.yuushilabs.pianosheet.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String,
    val sourceType: String,
    val noteCount: Int,
    val durationMs: Long,
)

@Entity(
    tableName = "recording_assets",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class RecordingAssetEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val storagePath: String,
    val format: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val fileSize: Long,
    val createdAt: Long,
    val audioSource: String,
    val denoiseProfile: String?,
    val isUnprocessed: Boolean,
)

@Entity(
    tableName = "note_events",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class NoteEventEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sequence: Int,
    val pitchMidi: Int,
    val onsetMs: Double,
    val durationMs: Double?,
    val confidence: Float,
    val onsetStrength: Float?,
    val state: String,
    val sourceChunk: Int?,
    val detector: String?,
    val staff: String?,
)

/**
 * The finished score of a project (Phase 2).  The payload is the versioned text
 * from `ScoreCodec`; the summary columns exist so the project list can show
 * tempo / meter / key without decoding every score.
 */
@Entity(
    tableName = "scores",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("projectId")],
)
data class ScoreEntity(
    @PrimaryKey val projectId: String,
    val payload: String,
    val tempoBpm: Double,
    val timeSignature: String,
    val keySignature: String,
    val noteCount: Int,
    val durationMs: Long,
    val denoiseProfile: String?,
    val analysisMs: Long,
    val updatedAt: Long,
)
