package com.yuushilabs.pianosheet.domain.model

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Pure pitch helpers shared by the live view, the piano keyboard and the tests. */
object Pitch {
    val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val BLACK_KEY_CLASSES = setOf(1, 3, 6, 8, 10)

    const val MIDI_LOW = 21 // A0
    const val MIDI_HIGH = 108 // C8

    fun midiToName(midi: Int): String {
        val name = NAMES[((midi % 12) + 12) % 12]
        val octave = midi / 12 - 1
        return "$name$octave"
    }

    fun midiToFrequency(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)

    fun frequencyToMidi(frequency: Double): Double = 69.0 + 12.0 * ln(frequency / 440.0) / ln(2.0)

    fun nearestMidi(midi: Double): Int = midi.roundToInt()

    fun isBlackKey(midi: Int): Boolean = BLACK_KEY_CLASSES.contains(((midi % 12) + 12) % 12)

    fun isWhiteKey(midi: Int): Boolean = !isBlackKey(midi)

    /** Accidental used by the provisional live score (sharps; the key is decided later). */
    fun provisionalAccidental(midi: Int): String? = if (isBlackKey(midi)) "#" else null
}
