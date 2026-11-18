package com.yuushilabs.pianosheet.benchmark

import com.yuushilabs.pianosheet.core.inference.PtiFrames
import com.yuushilabs.pianosheet.core.inference.PtiNote
import com.yuushilabs.pianosheet.core.inference.PtiPostProcessor
import com.yuushilabs.pianosheet.domain.export.MusicXmlExporter
import com.yuushilabs.pianosheet.domain.final.FinalNote
import com.yuushilabs.pianosheet.domain.final.ScoreBuilder
import com.yuushilabs.pianosheet.domain.final.ScoreBuildOptions
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes the exported PianoVAM / real-take model frames with the production
 * decoder so the pedal / note-length work can be measured before and after in
 * Kotlin itself (ONNX Runtime cannot run on the desktop JVM, so the frames are
 * exported once by `work/ps048/export_bench.py`).
 *
 * `PIANOSHEET_PTI_BENCH_DIR` points at the exported folder and
 * `PIANOSHEET_PTI_BENCH_OUT` at where the note lists go.  Each case is decoded
 * once per variant, where a variant is a `name|key=value,...` spec mapped onto
 * [PtiPostProcessor.Settings].  Without the variables (CI) the test is skipped.
 *
 * `work/ps048/score_bench.py` scores the written `<case>.<variant>.json`.
 */
class PtiSustainBenchmarkTest {

    private val dir: String? = System.getenv("PIANOSHEET_PTI_BENCH_DIR")
    private val outDir: String = System.getenv("PIANOSHEET_PTI_BENCH_OUT") ?: dir.orEmpty()
    private val variantSpec: String = System.getenv("PIANOSHEET_PTI_BENCH_VARIANTS") ?: "prod|"

    @Test
    fun decodeEveryCase() {
        assumeTrue("PIANOSHEET_PTI_BENCH_DIR not set", dir != null)
        val folder = File(dir!!)
        assumeTrue("missing folder $folder", folder.isDirectory)
        val cases = folder.listFiles { f -> f.name.endsWith(".meta.json") }?.sorted().orEmpty()
        assumeTrue("no cases in $folder", cases.isNotEmpty())
        val out = File(outDir).apply { mkdirs() }

        val variants = variantSpec.split(";").filter { it.isNotBlank() }.map { spec ->
            val parts = spec.split("|")
            val name = parts[0]
            val settings = parse(if (parts.size > 1) parts[1] else "").let { base ->
                // The sustained-note parameters live on the decoder's settings.
                base
            }
            name to settings
        }

        for (metaFile in cases) {
            val id = metaFile.name.removeSuffix(".meta.json")
            val frameCount = intField(metaFile.readText(), "frameCount")
            val audioSamples = intField(metaFile.readText(), "audioSamples")
            val frames = readFrames(File(folder, "$id.frames.bin"), frameCount)
            val audio = readAudio(File(folder, "$id.audio.bin"), audioSamples)
            for ((name, settings) in variants) {
                val decoder = PtiPostProcessor(settings)
                val notes = decoder.decode(frames, audio)
                writeNotes(File(out, "$id.$name.json"), id, name, frames.frameCount, notes)
                // The engraved result, so the report can show what the user would
                // see: how many noteheads one pedal note turns into.
                val score = ScoreBuilder.build(
                    projectId = id,
                    title = id,
                    notes = notes.map {
                        FinalNote(
                            pitch = it.pitchMidi,
                            onsetSec = it.onsetS,
                            durationSec = it.durationS,
                            amplitude = it.velocity.coerceIn(0.05f, 1f),
                        )
                    },
                    durationSec = audio.size / 16_000.0,
                    options = ScoreBuildOptions(),
                )
                val xml = MusicXmlExporter.toMusicXml(score)
                File(out, "$id.$name.musicxml").writeText(xml)
                val tieStarts = Regex("<tie type=\"start\"/>|  <tie type=\"start\"/>")
                    .findAll(xml).count()
                println(
                    "$id/$name notes=${notes.size} trimmed=${notes.count { it.trimmed }} " +
                        "scoreNotes=${score.notes.size} tieStarts=$tieStarts",
                )
            }
        }
        assertTrue(true)
    }

    private fun parse(spec: String): PtiPostProcessor.Settings {
        var s = PtiPostProcessor.Settings()
        for (item in spec.split(",")) {
            if (item.isBlank()) continue
            val key = item.substringBefore('=')
            val value = item.substringAfter('=', "")
            s = when (key) {
                "trim" -> if (value == "off") s.copy(decayTrim = false) else s.copy(decayTrimRatio = value.toFloat())
                "persist" -> s.copy(decayTrimPersist = value.toInt())
                "min" -> s.copy(decayTrimMinSec = value.toDouble())
                "sustain" -> s.copy(sustainHoldSec = if (value == "0") 0.0 else value.toDouble())
                "sustainRing" -> s.copy(sustainRingDb = value.toDouble())
                "sustainDecay" -> s.copy(sustainDecayDb = value.toDouble())
                "merge" -> s.copy(mergeSamePitch = value != "0")
                "mergeGap" -> s.copy(mergeGapSec = value.toDouble())
                "mergeOff" -> s.copy(mergeOffsetPeak = value.toFloat())
                "mergeOn" -> s.copy(mergeOnsetPeak = value.toFloat())
                "mergeRise" -> s.copy(mergeReattackDb = value.toDouble())
                else -> error("unknown setting $key")
            }
        }
        return s
    }

    private fun readFrames(file: File, frameCount: Int): PtiFrames {
        val expected = frameCount * PtiFrames.CLASSES
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        fun head(): FloatArray {
            val values = FloatArray(expected)
            buffer.asFloatBuffer().get(values)
            buffer.position(buffer.position() + expected * 4)
            return values
        }
        // A fresh slice per head: asFloatBuffer() shares the buffer position.
        val all = file.readBytes()
        fun headAt(index: Int): FloatArray {
            val copy = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            copy.position(index * expected * 4)
            val values = FloatArray(expected)
            copy.asFloatBuffer().get(values)
            return values
        }
        return PtiFrames(
            frameCount = frameCount,
            onsetReg = headAt(0),
            offsetReg = headAt(1),
            frame = headAt(2),
            velocity = headAt(3),
        )
    }

    private fun readAudio(file: File, samples: Int): FloatArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val values = FloatArray(samples)
        buffer.asFloatBuffer().get(values)
        return values
    }

    private fun writeNotes(file: File, id: String, variant: String, frameCount: Int, notes: List<PtiNote>) {
        val body = buildString {
            append("{\"id\": \"$id\", \"variant\": \"$variant\", \"frames\": $frameCount, \"notes\": [\n")
            notes.forEachIndexed { index, n ->
                append(
                    "  {\"pitch\": %d, \"onset\": %.4f, \"end\": %.4f, \"velocity\": %.4f, \"trimmed\": %s}"
                        .format(n.pitchMidi, n.onsetS, n.endS, n.velocity, n.trimmed),
                )
                append(if (index == notes.lastIndex) "\n" else ",\n")
            }
            append("]}\n")
        }
        file.writeText(body)
    }

    private fun intField(json: String, name: String): Int {
        val i = json.indexOf("\"$name\"")
        require(i >= 0) { "$name missing" }
        val colon = json.indexOf(':', i)
        var j = colon + 1
        while (json[j] == ' ') j++
        var k = j
        while (k < json.length && (json[k].isDigit() || json[k] == '-')) k++
        return json.substring(j, k).toInt()
    }
}
