package com.yuushilabs.pianosheet.feature.project

import org.junit.Assert.assertEquals
import org.junit.Test

/** The score stores an engine id; the detail screen has to say it in words. */
class EngineLabelTest {

    @Test
    fun `the PTI engine is named`() {
        assertEquals("PTI-ONNX（ピアノ専用）", engineLabel("pti_onnx"))
    }

    @Test
    fun `the Basic Pitch fallback is named`() {
        assertEquals("Basic Pitch", engineLabel("basic_pitch_onnx"))
    }

    @Test
    fun `an unknown engine id is shown as it is`() {
        assertEquals("dsp_peak", engineLabel("dsp_peak"))
    }
}
