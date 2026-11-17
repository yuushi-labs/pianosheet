package com.yuushilabs.pianosheet.domain.repository

import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.ProjectDetail
import com.yuushilabs.pianosheet.domain.model.ProjectStatus
import com.yuushilabs.pianosheet.domain.model.ProjectSummary
import com.yuushilabs.pianosheet.domain.model.RecordingInfo
import com.yuushilabs.pianosheet.domain.score.Score
import kotlinx.coroutines.flow.Flow
import java.io.File

interface ProjectRepository {
    fun observeProjects(): Flow<List<ProjectSummary>>

    suspend fun loadProject(id: String): ProjectDetail?

    suspend fun saveSession(
        project: ProjectSummary,
        recording: RecordingInfo,
        events: List<NoteEvent>,
    )

    suspend fun createUploadedProject(
        id: String,
        title: String,
        file: File,
        originalFileName: String,
    ): ProjectSummary

    /** Stores the finished score from the final analysis (Phase 2). */
    suspend fun saveScore(score: Score, denoiseProfile: String?, analysisMs: Long)

    /** Remembers the path of a take that was copied to a public folder (0.4.1). */
    suspend fun updateSharedPath(projectId: String, sharedPath: String)

    suspend fun loadScore(projectId: String): Score?

    suspend fun updateStatus(id: String, status: ProjectStatus, noteCount: Int)

    suspend fun renameProject(id: String, title: String)
}
