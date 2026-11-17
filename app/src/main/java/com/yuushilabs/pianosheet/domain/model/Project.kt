package com.yuushilabs.pianosheet.domain.model

import com.yuushilabs.pianosheet.domain.score.Score

enum class ProjectStatus(val wireName: String) {
    DRAFT("draft"),
    RECORDING("recording"),
    PROCESSING("processing"),
    COMPLETED("completed"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWire(value: String?): ProjectStatus =
            values().firstOrNull { it.wireName == value } ?: DRAFT
    }
}

data class RecordingInfo(
    val storagePath: String,
    val format: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val fileSize: Long,
    val audioSource: String,
    val denoiseProfile: String?,
    /** True when the take was captured through AudioSource.UNPROCESSED (no OS effects). */
    val isUnprocessed: Boolean,
    /**
     * Full path of the untouched copy in the public folder, e.g.
     * `/storage/emulated/0/Music/PianoSheet/rec-20261117-211530-9f3c1a2b.wav`.
     * Null when the shared write failed or the take predates 0.4.1.
     */
    val sharedPath: String? = null,
)

data class ProjectSummary(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: ProjectStatus,
    val sourceType: NoteSource,
    val noteCount: Int,
    val durationMs: Long,
)

data class ProjectDetail(
    val project: ProjectSummary,
    val recording: RecordingInfo?,
    val notes: List<NoteEvent>,
    /** Finished score, present once the final analysis has run (Phase 2). */
    val score: Score? = null,
) {
    /** True when the take can be re-analysed: the raw WAV is still there. */
    val canAnalyse: Boolean get() = !recording?.storagePath.isNullOrBlank()
}
