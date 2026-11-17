package com.yuushilabs.pianosheet.data

import com.yuushilabs.pianosheet.data.db.NoteEventDao
import com.yuushilabs.pianosheet.data.db.NoteEventEntity
import com.yuushilabs.pianosheet.data.db.ProjectDao
import com.yuushilabs.pianosheet.data.db.ProjectEntity
import com.yuushilabs.pianosheet.data.db.RecordingAssetDao
import com.yuushilabs.pianosheet.data.db.RecordingAssetEntity
import com.yuushilabs.pianosheet.data.db.ScoreDao
import com.yuushilabs.pianosheet.data.db.ScoreEntity
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.model.NoteOrdering
import com.yuushilabs.pianosheet.domain.model.NoteSource
import com.yuushilabs.pianosheet.domain.model.NoteState
import com.yuushilabs.pianosheet.domain.model.ProjectDetail
import com.yuushilabs.pianosheet.domain.model.ProjectStatus
import com.yuushilabs.pianosheet.domain.model.ProjectSummary
import com.yuushilabs.pianosheet.domain.model.RecordingInfo
import com.yuushilabs.pianosheet.domain.model.Staff
import com.yuushilabs.pianosheet.domain.repository.ProjectRepository
import com.yuushilabs.pianosheet.domain.score.Score
import com.yuushilabs.pianosheet.domain.score.ScoreCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.util.UUID

class ProjectRepositoryImpl(
    private val projectDao: ProjectDao,
    private val recordingAssetDao: RecordingAssetDao,
    private val noteEventDao: NoteEventDao,
    private val scoreDao: ScoreDao,
) : ProjectRepository {

    override fun observeProjects(): Flow<List<ProjectSummary>> =
        projectDao.observeAll().map { rows -> rows.map { it.toSummary() } }

    override suspend fun loadProject(id: String): ProjectDetail? {
        val project = projectDao.find(id) ?: return null
        val asset = recordingAssetDao.findByProject(id)
        // Onset order, then pitch: the provisional list must read like the music
        // even when the live engine numbered the events out of order.
        val notes = NoteOrdering.chronological(noteEventDao.findByProject(id).map { it.toNoteEvent() })
        return ProjectDetail(
            project = project.toSummary(),
            recording = asset?.toRecordingInfo(),
            notes = notes,
            score = loadScore(id),
        )
    }

    override suspend fun saveSession(
        project: ProjectSummary,
        recording: RecordingInfo,
        events: List<NoteEvent>,
    ) {
        projectDao.upsert(project.toEntity())
        recordingAssetDao.upsert(
            RecordingAssetEntity(
                id = UUID.randomUUID().toString(),
                projectId = project.id,
                storagePath = recording.storagePath,
                format = recording.format,
                sampleRate = recording.sampleRate,
                channels = recording.channels,
                durationMs = recording.durationMs,
                fileSize = recording.fileSize,
                createdAt = System.currentTimeMillis(),
                audioSource = recording.audioSource,
                denoiseProfile = recording.denoiseProfile,
                isUnprocessed = recording.isUnprocessed,
                sharedPath = recording.sharedPath,
            ),
        )
        noteEventDao.deleteByProject(project.id)
        noteEventDao.insertAll(
            NoteOrdering.chronological(events).mapIndexed { index, event ->
                NoteEventEntity(
                    id = event.id,
                    projectId = project.id,
                    sequence = index,
                    pitchMidi = event.pitchMidi,
                    onsetMs = event.onsetMs,
                    durationMs = event.durationMs,
                    confidence = event.confidence,
                    onsetStrength = event.onsetStrength,
                    state = event.state.wireName,
                    sourceChunk = event.sourceChunk,
                    detector = event.detector,
                    staff = event.staff?.wireName,
                )
            },
        )
    }

    override suspend fun createUploadedProject(
        id: String,
        title: String,
        file: File,
        originalFileName: String,
    ): ProjectSummary {
        val now = System.currentTimeMillis()
        val summary = ProjectSummary(
            id = id,
            title = title,
            createdAt = now,
            updatedAt = now,
            status = ProjectStatus.DRAFT,
            sourceType = NoteSource.UPLOAD,
            noteCount = 0,
            durationMs = 0,
        )
        projectDao.upsert(summary.toEntity())
        recordingAssetDao.upsert(
            RecordingAssetEntity(
                id = UUID.randomUUID().toString(),
                projectId = id,
                storagePath = file.absolutePath,
                format = originalFileName.substringAfterLast('.', "wav").lowercase(),
                sampleRate = 0,
                channels = 1,
                durationMs = 0,
                fileSize = file.length(),
                createdAt = now,
                audioSource = "upload",
                denoiseProfile = null,
                isUnprocessed = false,
            ),
        )
        return summary
    }

    override suspend fun updateSharedPath(projectId: String, sharedPath: String) {
        recordingAssetDao.updateSharedPath(projectId, sharedPath)
    }

    override suspend fun saveScore(score: Score, denoiseProfile: String?, analysisMs: Long) {
        val now = System.currentTimeMillis()
        scoreDao.upsert(
            ScoreEntity(
                projectId = score.projectId,
                payload = ScoreCodec.encode(score),
                tempoBpm = score.tempoBpm,
                timeSignature = score.timeSignature.label,
                keySignature = score.keySignature.describeJa(),
                noteCount = score.noteCount,
                durationMs = (score.durationSec * 1000).toLong(),
                denoiseProfile = denoiseProfile,
                analysisMs = analysisMs,
                updatedAt = now,
            ),
        )
        updateStatus(score.projectId, ProjectStatus.COMPLETED, score.noteCount)
    }

    override suspend fun loadScore(projectId: String): Score? {
        val entity = scoreDao.findByProject(projectId) ?: return null
        return runCatching { ScoreCodec.decode(entity.payload) }.getOrNull()
    }

    override suspend fun updateStatus(id: String, status: ProjectStatus, noteCount: Int) {
        projectDao.updateStatus(id, status.wireName, noteCount, System.currentTimeMillis())
    }

    override suspend fun renameProject(id: String, title: String) {
        projectDao.rename(id, title, System.currentTimeMillis())
    }

    private fun ProjectEntity.toSummary() = ProjectSummary(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        status = ProjectStatus.fromWire(status),
        sourceType = if (sourceType == NoteSource.UPLOAD.wireName) NoteSource.UPLOAD else NoteSource.MICROPHONE,
        noteCount = noteCount,
        durationMs = durationMs,
    )

    private fun ProjectSummary.toEntity() = ProjectEntity(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        status = status.wireName,
        sourceType = sourceType.wireName,
        noteCount = noteCount,
        durationMs = durationMs,
    )

    private fun RecordingAssetEntity.toRecordingInfo() = RecordingInfo(
        storagePath = storagePath,
        format = format,
        sampleRate = sampleRate,
        channels = channels,
        durationMs = durationMs,
        fileSize = fileSize,
        audioSource = audioSource,
        denoiseProfile = denoiseProfile,
        isUnprocessed = isUnprocessed,
        sharedPath = sharedPath,
    )

    private fun NoteEventEntity.toNoteEvent() = NoteEvent(
        id = id,
        pitchMidi = pitchMidi,
        onsetMs = onsetMs,
        durationMs = durationMs,
        confidence = confidence,
        onsetStrength = onsetStrength,
        state = NoteState.fromWire(state),
        sourceChunk = sourceChunk,
        sequence = sequence,
        detector = detector,
        staff = Staff.fromWire(staff),
    )
}
