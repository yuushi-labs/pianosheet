package com.yuushilabs.pianosheet.domain.model

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
)
