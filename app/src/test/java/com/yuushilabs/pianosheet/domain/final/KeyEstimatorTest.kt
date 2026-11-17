package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.domain.score.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyEstimatorTest {

    private fun scale(tonic: Int, offsets: IntArray, weight: Double = 1.0): List<KeyEstimator.PitchWeight> =
        offsets.map { KeyEstimator.PitchWeight(60 + tonic + it, weight) }

    private val MAJOR = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)

    @Test
    fun `a C major scale is C major without accidentals`() {
        val estimate = KeyEstimator.estimate(scale(0, MAJOR))
        assertEquals(0, estimate.keySignature.fifths)
        assertEquals(Mode.MAJOR, estimate.keySignature.mode)
    }

    @Test
    fun `a G major scale is one sharp`() {
        val estimate = KeyEstimator.estimate(scale(7, MAJOR))
        assertEquals(1, estimate.keySignature.fifths)
        assertEquals(Mode.MAJOR, estimate.keySignature.mode)
    }

    @Test
    fun `an F major scale with B flat is one flat`() {
        val melody = listOf(
            KeyEstimator.PitchWeight(65, 1.0), // F
            KeyEstimator.PitchWeight(67, 1.0), // G
            KeyEstimator.PitchWeight(69, 1.0), // A
            KeyEstimator.PitchWeight(70, 2.0), // Bb
            KeyEstimator.PitchWeight(72, 1.5), // C
            KeyEstimator.PitchWeight(74, 1.0), // D
            KeyEstimator.PitchWeight(65, 1.0), // F
        )
        val estimate = KeyEstimator.estimate(melody)
        assertTrue("fifths ${estimate.keySignature.fifths}", estimate.keySignature.fifths <= 0)
    }

    @Test
    fun `a minor melody is reported as minor`() {
        val aMinor = listOf(
            KeyEstimator.PitchWeight(57, 2.0), // A
            KeyEstimator.PitchWeight(59, 1.0), // B
            KeyEstimator.PitchWeight(60, 2.0), // C
            KeyEstimator.PitchWeight(62, 1.0), // D
            KeyEstimator.PitchWeight(64, 2.0), // E
            KeyEstimator.PitchWeight(65, 1.0), // F
            KeyEstimator.PitchWeight(67, 1.0), // G
            KeyEstimator.PitchWeight(69, 3.0), // A
        )
        val estimate = KeyEstimator.estimate(aMinor)
        assertEquals(Mode.MINOR, estimate.keySignature.mode)
        assertEquals(0, estimate.keySignature.fifths)
    }

    @Test
    fun `no notes is C major with zero confidence`() {
        val estimate = KeyEstimator.estimate(emptyList())
        assertEquals(0, estimate.keySignature.fifths)
        assertEquals(0.0, estimate.confidence, 1e-6)
    }

    @Test
    fun `seven sharps are written as the five-flat twin`() {
        // C# major and D♭ major are the same key; only one of them is readable.
        assertEquals(-5, KeyEstimator.simplifyEnharmonic(7))
        assertEquals(5, KeyEstimator.simplifyEnharmonic(-7))
        // Six has no twin with fewer accidentals, so it stays.
        assertEquals(6, KeyEstimator.simplifyEnharmonic(6))
        assertEquals(-6, KeyEstimator.simplifyEnharmonic(-6))
        assertEquals(2, KeyEstimator.simplifyEnharmonic(2))
    }

    @Test
    fun `a C sharp major pitch set comes back as five flats`() {
        val pitches = listOf(1, 3, 5, 6, 8, 10, 0).flatMap { pc ->
            listOf(KeyEstimator.PitchWeight(60 + pc, 1.0), KeyEstimator.PitchWeight(72 + pc, 0.6))
        }
        val estimate = KeyEstimator.estimate(pitches)
        assertEquals(com.yuushilabs.pianosheet.domain.score.Mode.MAJOR, estimate.keySignature.mode)
        assertTrue("fifths ${estimate.keySignature.fifths}", estimate.keySignature.fifths in -5..5)
    }
}
