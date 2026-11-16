package com.yuushilabs.pianosheet.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffNotationTest {

    @Test
    fun `treble staff lines are E4 to F5`() {
        assertEquals(0, StaffNotation.stepOnStaff(64, Staff.TREBLE)) // E4 bottom line
        assertEquals(8, StaffNotation.stepOnStaff(77, Staff.TREBLE)) // F5 top line
        assertEquals(2, StaffNotation.stepOnStaff(67, Staff.TREBLE)) // G4 second line
    }

    @Test
    fun `bass staff starts at G2`() {
        assertEquals(0, StaffNotation.stepOnStaff(43, Staff.BASS)) // G2 bottom line
        assertEquals(8, StaffNotation.stepOnStaff(57, Staff.BASS)) // A3? G2+8 steps = A3
    }

    @Test
    fun `middle C sits a ledger below the treble staff and above the bass staff`() {
        val trebleStep = StaffNotation.stepOnStaff(60, Staff.TREBLE)
        assertEquals(-2, trebleStep)
        assertEquals(listOf(-2), StaffNotation.ledgerSteps(trebleStep))
        val bassStep = StaffNotation.stepOnStaff(60, Staff.BASS)
        assertEquals(10, bassStep)
        assertEquals(listOf(10), StaffNotation.ledgerSteps(bassStep))
    }

    @Test
    fun `spaces just outside the staff need no ledger line`() {
        assertTrue(StaffNotation.ledgerSteps(-1).isEmpty())
        assertTrue(StaffNotation.ledgerSteps(9).isEmpty())
        assertEquals(listOf(10, 12), StaffNotation.ledgerSteps(12))
        assertEquals(listOf(-2, -4), StaffNotation.ledgerSteps(-4))
    }

    @Test
    fun `clef split is around middle C`() {
        assertEquals(Staff.TREBLE, StaffNotation.clefFor(60))
        assertEquals(Staff.BASS, StaffNotation.clefFor(59))
    }
}

class PitchTest {

    @Test
    fun `names follow scientific pitch notation`() {
        assertEquals("A0", Pitch.midiToName(21))
        assertEquals("C4", Pitch.midiToName(60))
        assertEquals("C8", Pitch.midiToName(108))
        assertEquals("A4", Pitch.midiToName(69))
    }

    @Test
    fun `black keys are recognised`() {
        assertTrue(Pitch.isBlackKey(61)) // C#4
        assertFalse(Pitch.isBlackKey(60)) // C4
        assertFalse(Pitch.isBlackKey(64)) // E4
        assertEquals("#", Pitch.provisionalAccidental(61))
        assertEquals(null, Pitch.provisionalAccidental(60))
    }

    @Test
    fun `A4 is 440 Hz`() {
        assertEquals(440.0, Pitch.midiToFrequency(69.0), 1e-9)
        assertEquals(69.0, Pitch.frequencyToMidi(440.0), 1e-9)
    }
}
