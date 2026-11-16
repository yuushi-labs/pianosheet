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
