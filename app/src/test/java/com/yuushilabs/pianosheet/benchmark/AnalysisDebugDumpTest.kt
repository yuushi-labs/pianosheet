package com.yuushilabs.pianosheet.benchmark

import com.yuushilabs.pianosheet.core.audio.AudioResampler
import com.yuushilabs.pianosheet.core.audio.WavFileReader
import com.yuushilabs.pianosheet.core.audio.WavFileWriter
import com.yuushilabs.pianosheet.core.dsp.DenoiseConfig
import com.yuushilabs.pianosheet.core.dsp.DenoiseStrength
import com.yuushilabs.pianosheet.core.dsp.Denoiser
import com.yuushilabs.pianosheet.core.dsp.StartupGuard
import com.yuushilabs.pianosheet.domain.export.MusicXmlExporter
import com.yuushilabs.pianosheet.domain.final.FinalNote
import com.yuushilabs.pianosheet.domain.final.ScoreBuildOptions
import com.yuushilabs.pianosheet.domain.final.ScoreBuilder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Debug harnesses for the Python twin of the analysis (not part of CI; each test
 * is skipped unless its environment variable is set).
 *
 * The ONNX models cannot run on a desktop JVM, so a take is analysed by
 * `work/pianovam_models` and the stages around the model run here, on the
 * production classes.  Two of them are needed to reproduce a user report:
 *
 *  - `PIANOSHEET_PREP_IN=/path/take.wav` writes the analysis copy the app would
 *    hand the model (`prepared_22050.wav`, `prepared_16000.wav` next to it);
 *  - `PIANOSHEET_USER_TAKE_CSV=/path/notes.csv` turns a note list exported from
 *    the Python side (`pitch,onset,duration,velocity`) into the score the app
 *    would build, and writes `user_take.musicxml` next to the CSV.
 */
class AnalysisDebugDumpTest {

    @Test
    fun dumpPreparedAudio() {
        val path = System.getenv("PIANOSHEET_PREP_IN")
        assumeTrue("PIANOSHEET_PREP_IN not set", path != null)
        val file = File(path!!)
        assumeTrue("missing $file", file.isFile)
        val wav = WavFileReader.read(file)
        val target = 22050
        val resampled = if (wav.sampleRate == target) {
            wav.samples
        } else {
            AudioResampler.resample(wav.samples, wav.sampleRate, target)
        }
        val denoiser = Denoiser(DenoiseConfig(strength = DenoiseStrength.STANDARD, highpassHz = 25.0))
        val cleaned = denoiser.process(resampled)
        val prepared = StartupGuard.applyStartupTreatment(cleaned, 0L, target)
        println(
            "PREP: in=${wav.samples.size}@${wav.sampleRate} out=${prepared.size}@$target " +
                "notches=${denoiser.lastToneNotchesHz}",
        )
        writeWav(File(file.parentFile, "prepared_22050.wav"), prepared, target)
        val at16k = AudioResampler.resample(prepared, target, 16000)
        writeWav(File(file.parentFile, "prepared_16000.wav"), at16k, 16000)
    }

    @Test
    fun dumpScore() {
        val path = System.getenv("PIANOSHEET_USER_TAKE_CSV")
        assumeTrue("PIANOSHEET_USER_TAKE_CSV not set", path != null)
        val file = File(path!!)
        assumeTrue("missing $file", file.isFile)
        val notes = file.readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split(",")
            FinalNote(
                pitch = p[0].trim().toInt(),
                onsetSec = p[1].trim().toDouble(),
                durationSec = p[2].trim().toDouble(),
                amplitude = p[3].trim().toFloat(),
            )
        }
        val score = ScoreBuilder.build(
            projectId = "user-take",
            title = "録音",
            notes = notes,
            durationSec = notes.maxOf { it.endSec },
            options = ScoreBuildOptions(),
        )
        println(
            "USER TAKE: notes=${notes.size} -> scoreNotes=${score.notes.size} " +
                "tempo=${score.tempoBpm} meter=${score.timeSignature.label} " +
                "key=${score.keySignature.fifths}/${score.keySignature.mode} " +
                "measures=${score.measures.size} high=${score.notes.count { it.midiPitch >= 84 }}",
        )
        File(file.parentFile, "user_take.musicxml").writeText(MusicXmlExporter.toMusicXml(score))
    }

    private fun writeWav(out: File, samples: FloatArray, rate: Int) {
        WavFileWriter(out, rate).use { it.write(samples) }
    }
}
