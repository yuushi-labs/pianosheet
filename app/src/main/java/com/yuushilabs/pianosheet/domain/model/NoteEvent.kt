package com.yuushilabs.pianosheet.domain.model

/**
 * State of a note event (spec §4.8), mirroring `shared-specs/note-event.schema.json`.
 */
enum class NoteState(val wireName: String) {
    PENDING("pending"),
    PROVISIONAL("provisional"),
    CONFIRMED("confirmed"),
    REVISED("revised"),
    REJECTED("rejected"),
    ;

    companion object {
        fun fromWire(value: String?): NoteState =
            values().firstOrNull { it.wireName == value } ?: PROVISIONAL
    }
}

enum class Staff(val wireName: String) {
    TREBLE("treble"),
    BASS("bass"),
    ;

    companion object {
        fun fromWire(value: String?): Staff? =
            values().firstOrNull { it.wireName == value }
    }
}

enum class NoteSource(val wireName: String) {
    MICROPHONE("microphone"),
    UPLOAD("upload"),
}

/**
 * The shared note event both transcribers return.  Live events are `PROVISIONAL`
 * and may lack a duration; the final analysis fills it in and moves them to
 * `CONFIRMED` / `REVISED`.
 */
data class NoteEvent(
    val pitchMidi: Int,
    val onsetMs: Double,
    val id: String = java.util.UUID.randomUUID().toString(),
    var durationMs: Double? = null,
    var confidence: Float = 1f,
    val onsetStrength: Float? = null,
    var state: NoteState = NoteState.PROVISIONAL,
    val sourceChunk: Int? = null,
    var sequence: Int = 0,
    var emittedAtS: Double? = null,
    var renderMs: Double? = null,
    val detector: String? = null,
    val staff: Staff? = null,
) {
    val onsetS: Double get() = onsetMs / 1000.0

    val offsetS: Double?
        get() = durationMs?.let { (onsetMs + it) / 1000.0 }

    val pitchName: String get() = Pitch.midiToName(pitchMidi)

    /** Display latency: when the step that reported it started, minus its onset. */
    val latencyMs: Double?
        get() = emittedAtS?.let { (it * 1000.0) - onsetMs }
}
