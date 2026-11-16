package com.yuushilabs.pianosheet.domain.repository

import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.ProjectDetail
import com.yuushilabs.pianosheet.domain.model.ProjectSummary
import com.yuushilabs.pianosheet.domain.model.RecordingInfo
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
}
