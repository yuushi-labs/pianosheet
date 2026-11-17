package com.yuushilabs.pianosheet.benchmark

import com.yuushilabs.pianosheet.core.audio.WavFileReader
import com.yuushilabs.pianosheet.core.dsp.StartupGuard
import com.yuushilabs.pianosheet.core.inference.HarmonicRealnessFilter
import com.yuushilabs.pianosheet.core.inference.NoteCandidateFilter
import com.yuushilabs.pianosheet.domain.final.FinalNote
import com.yuushilabs.pianosheet.domain.final.FinalNoteMerger
import com.yuushilabs.pianosheet.domain.final.ScoreBuilder
import com.yuushilabs.pianosheet.domain.export.MusicXmlExporter
import com.yuushilabs.pianosheet.domain.final.ScoreBuildOptions
import com.yuushilabs.pianosheet.domain.live.Detection
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Replays the PianoVAM benchmark through the app's own Kotlin.
 *
 * `onnxruntime-android` cannot run on a desktop JVM, so the model half is run by
 * `work/pianovam/bench/export_kotlin.py` (same ONNX model, same 2 s window / 1 s
 * hop plan, same decoder port) and every stage after inference is executed here
 * by the production classes:
 *
 *   NoteCandidateFilter -> StartupGuard.isStartupArtifact -> FinalNoteMerger
 *   -> HarmonicRealnessFilter -> ScoreBuilder
 *
 * Point `PIANOSHEET_PIANOVAM_DIR` at the exported folder; without it (CI) the
 * test is skipped. Results are written next to the inputs as `<id>.kotlin.json`.
 */
class PianoVamBenchmarkTest {

    private val dir: String? = System.getenv("PIANOSHEET_PIANOVAM_DIR")

    @Test
    fun replayPianoVamCandidates() {
        assumeTrue("PIANOSHEET_PIANOVAM_DIR not set", dir != null)
        val folder = File(dir)
        assumeTrue("missing folder $folder", folder.isDirectory)
        val cases = folder.listFiles { f -> f.name.endsWith(".candidates.csv") }?.sorted().orEmpty()
        assumeTrue("no candidates.csv in $folder", cases.isNotEmpty())

        val summaries = ArrayList<String>()
        for (csv in cases) {
            val id = csv.name.removeSuffix(".candidates.csv")
            val windows = readWindows(csv)
            var startupSuppressed = 0
            val checked = ArrayList<FinalNoteMerger.WindowResult>(windows.size)
            for (window in windows) {
                val filtered = NoteCandidateFilter.filter(window.detections).kept
                val kept = ArrayList<Detection>(filtered.size)
                for (detection in filtered) {
                    if (StartupGuard.isStartupArtifact(
                            onsetS = detection.onsetS,
                            confidence = detection.confidence,
                            onsetStrength = detection.onsetStrength,
                        )
                    ) {
                        startupSuppressed += 1
                        continue
                    }
                    kept.add(detection)
                }
                checked.add(window.copy(detections = kept))
            }
            val merged = FinalNoteMerger.mergeDetailed(checked, toleranceMs = 80.0)

            val wav = File(folder, "$id.resampled.wav")
            val audio = if (wav.exists()) WavFileReader.read(wav).samples else FloatArray(0)
            val harmonic = HarmonicRealnessFilter.apply(merged.notes, audio)
            val notes = harmonic.kept

            val durationSec = checked.maxOfOrNull { it.windowEndS } ?: 0.0
            val score = ScoreBuilder.build(
                projectId = id,
                title = id,
                notes = notes.map {
                    FinalNote(
                        pitch = it.pitchMidi,
                        onsetSec = it.onsetMs / 1000.0,
                        durationSec = (it.durationMs ?: 0.0) / 1000.0,
                        amplitude = it.confidence.coerceIn(0.05f, 1f),
                    )
                },
                durationSec = durationSec,
                options = ScoreBuildOptions(),
            )

            val out = File(folder, "$id.kotlin.json")
            val body = buildString {
                append("{\n")
                append("  \"id\": \"$id\",\n")
                append("  \"windows\": ${checked.size},\n")
                append("  \"startupSuppressed\": $startupSuppressed,\n")
                append("  \"shortDropped\": ${merged.shortDropped},\n")
                append("  \"overtoneDropped\": ${merged.overtoneDropped},\n")
                append("  \"harmonicExamined\": ${harmonic.examined},\n")
                append("  \"harmonicDropped\": ${harmonic.dropped.size},\n")
                append("  \"tempoBpm\": ${score.tempoBpm},\n")
                append("  \"tempoConfidence\": ${score.tempoConfidence},\n")
                append("  \"timeSignature\": \"${score.timeSignature.label}\",\n")
                append("  \"timeSignatureConfidence\": ${score.timeSignatureConfidence},\n")
                append("  \"fifths\": ${score.keySignature.fifths},\n")
                append("  \"mode\": \"${score.keySignature.mode}\",\n")
                append("  \"keyConfidence\": ${score.keyConfidence},\n")
                append("  \"measures\": ${score.measures.size},\n")
                append("  \"measureStarts\": [" + score.measures.joinToString(",") { "%.4f".format(it.startBeat) } + "],\n")
                append("  \"measureBeats\": [" + score.measures.joinToString(",") { "%.4f".format(it.beats) } + "],\n")
                append("  \"scoreStartSec\": ${score.scoreStartSec},\n")
                append("  \"notes\": [\n")
                notes.forEachIndexed { index, n ->
                    val comma = if (index == notes.lastIndex) "" else ","
                    append(
                        "    {\"pitch\": ${n.pitchMidi}, \"onset\": ${"%.6f".format(n.onsetMs / 1000.0)}, " +
                            "\"duration\": ${"%.6f".format((n.durationMs ?: 0.0) / 1000.0)}}$comma\n",
                    )
                }
                append("  ]\n}\n")
            }
            out.writeText(body)
            // The finished score, so the report can point at a real file and an
            // outside reader (music21, OSMD) can check it.
            File(folder, "$id.musicxml").writeText(MusicXmlExporter.toMusicXml(score))
            summaries.add(
                "$id notes=${notes.size} tempo=${score.tempoBpm} meter=${score.timeSignature.label} " +
                    "key=${score.keySignature.fifths}/${score.keySignature.mode} measures=${score.measures.size} " +
                    "harmonic=${harmonic.dropped.size}/${harmonic.examined}",
            )
        }
        println("PianoVAM replay: " + summaries.joinToString(" | "))
        assertTrue(summaries.isNotEmpty())
    }

    private fun readWindows(csv: File): List<FinalNoteMerger.WindowResult> {
        val byWindow = LinkedHashMap<Double, MutableList<Detection>>()
        csv.readLines().drop(1).filter { it.isNotBlank() }.forEach { line ->
            val p = line.split(",")
            val windowStart = p[0].trim().toDouble()
            byWindow.getOrPut(windowStart) { ArrayList() }.add(
                Detection(
                    pitchMidi = p[3].trim().toInt(),
                    onsetS = p[1].trim().toDouble(),
                    endS = p[2].trim().toDouble(),
                    confidence = p[4].trim().toFloat(),
                    onsetStrength = p[5].trim().toFloat(),
                ),
            )
        }
        return byWindow.map { (start, detections) ->
            val end = detections.maxOfOrNull { it.endS } ?: (start + 2.0)
            FinalNoteMerger.WindowResult(windowStartS = start, windowEndS = maxOf(start + 2.0, end), detections = detections)
        }
    }
}
