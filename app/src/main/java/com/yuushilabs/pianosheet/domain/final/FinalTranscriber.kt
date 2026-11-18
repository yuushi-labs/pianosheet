package com.yuushilabs.pianosheet.domain.final

import com.yuushilabs.pianosheet.core.audio.AudioResampler
import com.yuushilabs.pianosheet.core.audio.WavFileReader
import com.yuushilabs.pianosheet.core.dsp.DenoiseConfig
import com.yuushilabs.pianosheet.core.dsp.DenoiseStrength
import com.yuushilabs.pianosheet.core.dsp.Denoiser
import com.yuushilabs.pianosheet.core.dsp.MainsHumDetector
import com.yuushilabs.pianosheet.core.dsp.SilenceHead
import com.yuushilabs.pianosheet.core.dsp.StartupGuard
import com.yuushilabs.pianosheet.core.inference.HarmonicRealnessFilter
import com.yuushilabs.pianosheet.domain.live.ChunkDetector
import com.yuushilabs.pianosheet.domain.live.Detection
import com.yuushilabs.pianosheet.domain.model.NoteEvent
import com.yuushilabs.pianosheet.domain.score.QuantizationSettings
import com.yuushilabs.pianosheet.domain.score.Score
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/** Thrown when the user leaves the analysis screen (spec §3.5.3 cancel). */
class AnalysisCancelledException : Exception("解析を中止しました")

/** The three progress stages of SCR-003 (spec §3.5.1). */
enum class FinalStage {
    PREPARING,
    TRANSCRIBING,
    FINISHING,
}

data class FinalProgress(
    val stage: FinalStage,
    /** 0..1 within the whole job. */
    val fraction: Double,
    val detail: String = "",
)

data class FinalAnalysisOptions(
    /** Analysis-copy denoise settings (never applied to the saved WAV). */
    val denoise: DenoiseConfig? = DenoiseConfig(strength = DenoiseStrength.STANDARD, highpassHz = 25.0),
    val quantization: QuantizationSettings = QuantizationSettings(),
    /** Hop between analysis windows; the model window itself is 2 s. */
    val windowHopS: Double = 1.0,
    val mergeToleranceMs: Double = 80.0,
    /** Detect 50/60 Hz hum when the settings say "auto". */
    val humAutoDetect: Boolean = true,
    val minTempoBpm: Double = 50.0,
    val maxTempoBpm: Double = 200.0,
    /** Use the final engine (PTI-ONNX) when one is wired in; else Basic Pitch only. */
    val useFinalEngine: Boolean = true,
)

data class FinalTranscription(
    val score: Score,
    /** Final notes, confirmed, on the recording clock. */
    val notes: List<NoteEvent>,
    val denoiseProfile: String?,
    val humHz: Double?,
    /** Stationary tones the denoiser notched out of the analysis copy. */
    val toneNotchesHz: List<Double> = emptyList(),
    val windowCount: Int,
    val startupSuppressed: Int,
    /** Upper partials removed as octave ghosts of a stronger note. */
    val overtoneSuppressed: Int = 0,
    /** Candidates removed because they lasted less than 80 ms. */
    val shortSuppressed: Int = 0,
    /** High notes removed as pure lines of a lower note's 8th partial (v1.4). */
    val harmonicSuppressed: Int = 0,
    /** Notes at or above C5 the harmonic check looked at. */
    val harmonicExamined: Int = 0,
    val analysisMs: Long,
    val audioDurationSec: Double,
    val sampleRate: Int,
    /** Engine that produced the notes: `pti_onnx` or `basic_pitch_onnx`. */
    val engineId: String = "basic_pitch_onnx",
    /** Human-readable engine name for SCR-003. */
    val engineLabel: String = "Basic Pitch",
    /** Why the final engine was not used, when it was skipped (shown to the user). */
    val engineFallback: String? = null,
    /** PSI region count (PTI) / the window count of the Basic Pitch pass. */
    val ptiChunks: Int = 0,
)

/**
 * The final analysis (spec §5, §14 Phase 2).
 *
 * Reads the untouched WAV, cleans an analysis copy (spec §20), re-analyses the
 * *whole* take with Basic Pitch, merges the overlapping windows, and hands the
 * result to [ScoreBuilder] for tempo, meter, key, rhythm, bars and hands.
 *
 * Everything except the detector is plain Kotlin, so the whole pipeline runs on
 * the JVM in the unit tests with a synthetic detector.
 */
class FinalTranscriber(
    private val detector: ChunkDetector,
    private val targetSampleRate: Int = 22050,
    /** PTI-ONNX for the final pass; null keeps the Basic Pitch path only. */
    private val finalEngine: FinalNoteEngine? = null,
    /** Why there is no final engine (missing model file, failed session), if known. */
    private val engineUnavailable: String? = null,
) {

    fun transcribe(
        file: File,
        projectId: String,
        title: String,
        liveEvents: List<NoteEvent> = emptyList(),
        options: FinalAnalysisOptions = FinalAnalysisOptions(),
        shouldCancel: () -> Boolean = { false },
        onProgress: (FinalProgress) -> Unit = {},
    ): FinalTranscription {
        val startedAt = System.nanoTime()
        onProgress(FinalProgress(FinalStage.PREPARING, 0.0, "録音データを読み込んでいます"))

        val wav = WavFileReader.read(file)
        if (wav.samples.isEmpty()) {
            throw IllegalStateException("録音データが空です")
        }
        val resampled = if (wav.sampleRate == targetSampleRate) {
            wav.samples
        } else {
            AudioResampler.resample(wav.samples, wav.sampleRate, targetSampleRate)
        }

        onProgress(FinalProgress(FinalStage.PREPARING, 0.25, "ノイズを除去しています"))
        var config = options.denoise
        var humHz: Double? = config?.notchHz
        if (config != null && config.notchHz == null && options.humAutoDetect) {
            humHz = MainsHumDetector.detect(resampled, targetSampleRate)
            if (humHz != null) config = config.copy(notchHz = humHz)
        }
        val denoiser = Denoiser(config ?: DenoiseConfig())
        val cleaned = if (config != null && config.enabled) {
            denoiser.process(resampled)
        } else {
            resampled
        }

        // The ramp only shapes the analysis copy: it removes the microphone
        // settling step that used to become a stray note 0.1 s into the take.
        val prepared = StartupGuard.applyStartupTreatment(
            audio = cleaned,
            startSample = 0L,
            sampleRate = targetSampleRate,
        )
        onProgress(FinalProgress(FinalStage.PREPARING, 0.30, "解析の準備ができました"))

        // The final engine (PTI-ONNX) hears the whole take itself: 10 s windows,
        // 5 s hop, middle 50 % stitched.  Anything it throws — a failed session,
        // an OutOfMemoryError on a small device — falls back to the Basic Pitch
        // pass below, and the score records which engine ran.
        var engineId = detector.name
        var engineLabel = ENGINE_LABEL_BASIC
        var engineFallback: String? = if (finalEngine == null) engineUnavailable else null
        var engineNotes: List<NoteEvent>? = null
        var engineChunks = 0
        if (finalEngine != null && options.useFinalEngine) {
            engineLabel = finalEngine.displayName
            try {
                val result = finalEngine.notes(prepared, targetSampleRate, shouldCancel) { done, total ->
                    val fraction = 0.30 + 0.55 * (done.toDouble() / total.coerceAtLeast(1))
                    onProgress(
                        FinalProgress(
                            FinalStage.TRANSCRIBING,
                            fraction.coerceIn(0.30, 0.85),
                            "音符を書き起こしています（$done/$total）",
                        ),
                    )
                }
                engineNotes = result.notes
                engineId = finalEngine.id
                engineChunks = result.chunkCount
            } catch (cancelled: AnalysisCancelledException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                throw AnalysisCancelledException()
            } catch (error: Throwable) {
                // OutOfMemoryError is an Error, not an Exception: catch Throwable
                // on purpose so a 94 MB model on a small device degrades instead
                // of losing the take.
                engineFallback = describeEngineFailure(error)
            }
        }

        val windowLength = WINDOW_SAMPLES
        val hop = maxOf(1, (options.windowHopS * targetSampleRate).toInt())
        val windowStarts = windowStarts(prepared.size, windowLength, hop)
        val results = ArrayList<FinalNoteMerger.WindowResult>(windowStarts.size)
        var suppressed = 0

        for ((index, start) in windowStarts.withIndex()) {
            // The PTI pass already heard the whole take; running Basic Pitch over
            // every 2 s window as well would only waste battery and time.
            if (engineNotes != null) break

            val end = min(start + windowLength, prepared.size)
            val window = prepared.copyOfRange(start, end)
            val windowStartS = start.toDouble() / targetSampleRate
            val detections = try {
                detector.detect(window, windowStartS)
            } catch (error: Exception) {
                // One bad window must not lose the whole analysis.
                emptyList<Detection>()
            }
            val kept = ArrayList<Detection>(detections.size)
            for (detection in detections) {
                if (detection.onsetS < 0.0) continue
                if (StartupGuard.isStartupArtifact(
                        onsetS = detection.onsetS,
                        confidence = detection.confidence,
                        onsetStrength = detection.onsetStrength,
                    )
                ) {
                    suppressed += 1
                    continue
                }
                kept.add(detection)
            }
            results.add(
                FinalNoteMerger.WindowResult(
                    windowStartS = windowStartS,
                    windowEndS = end.toDouble() / targetSampleRate,
                    detections = kept,
                ),
            )
            val fraction = 0.35 + 0.5 * (index + 1).toDouble() / windowStarts.size
            onProgress(
                FinalProgress(
                    FinalStage.TRANSCRIBING,
                    fraction,
                    "音符を書き起こしています（${index + 1}/${windowStarts.size}）",
                ),
            )
            if (shouldCancel()) throw AnalysisCancelledException()
        }

        onProgress(FinalProgress(FinalStage.FINISHING, 0.88, "楽譜を仕上げています"))
        var merged: List<NoteEvent>
        var overtoneDropped = 0
        var shortDropped = 0
        var harmonicSuppressed = 0
        var harmonicExamined = 0
        val engineNotesFinal = engineNotes
        if (engineNotesFinal != null) {
            // PTI's own ghosts: on a quiet, noisy take the model reports a high
            // note one octave up as well (C6 → C7), and the doubled note is one
            // spectral line of the key below with no partials of its own.  The
            // same realness check the Basic Pitch path uses removes them, with
            // the octave interval added and a stricter "no partials" gate.
            val realness = HarmonicRealnessFilter.apply(
                notes = engineNotesFinal,
                audio = resampled,
                settings = HarmonicRealnessFilter.PTI,
            )
            merged = realness.kept
            harmonicSuppressed = realness.dropped.size
            harmonicExamined = realness.examined
        } else {
            val mergedResult = FinalNoteMerger.mergeDetailed(results, toleranceMs = options.mergeToleranceMs)
            // v1.4: a high note that carries none of its own 2nd/3rd partial is one
            // spectral line of a lower note's 8th partial, not a key.  The check reads
            // the untouched take (not the denoised copy the model heard), because the
            // denoiser's noise subtraction can hollow out exactly the partials it looks
            // for; the clock is the same, so onsets still line up.
            val harmonic = HarmonicRealnessFilter.apply(
                notes = mergedResult.notes,
                audio = resampled,
                settings = HarmonicRealnessFilter.Settings(sampleRate = targetSampleRate),
            )
            merged = harmonic.kept
            overtoneDropped = mergedResult.overtoneDropped
            shortDropped = mergedResult.shortDropped
            harmonicSuppressed = harmonic.dropped.size
            harmonicExamined = harmonic.examined
        }
        val correlation = correlate(merged, liveEvents)
        val audioDurationSec = prepared.size.toDouble() / targetSampleRate

        val score = ScoreBuilder.build(
            projectId = projectId,
            title = title,
            notes = merged.map {
                FinalNote(
                    pitch = it.pitchMidi,
                    onsetSec = it.onsetMs / 1000.0,
                    durationSec = (it.durationMs ?: 0.0) / 1000.0,
                    amplitude = it.confidence.coerceIn(0.05f, 1f),
                )
            },
            durationSec = audioDurationSec,
            options = ScoreBuildOptions(
                quantization = options.quantization,
                minTempoBpm = options.minTempoBpm,
                maxTempoBpm = options.maxTempoBpm,
                // Measured on the untouched take: the denoiser can lift a silent
                // lead into something the level test would hear.
                soundStartSec = SilenceHead.firstSoundSec(resampled, targetSampleRate),
            ),
            detector = engineId,
            liveMatched = correlation.matched,
            analysisOnly = correlation.analysisOnly,
            liveOnly = correlation.liveOnly,
        )
        onProgress(FinalProgress(FinalStage.FINISHING, 1.0, "完成しました"))

        return FinalTranscription(
            score = score,
            notes = merged,
            denoiseProfile = config?.describeProfile(denoiser.lastToneNotchesHz),
            humHz = humHz,
            toneNotchesHz = denoiser.lastToneNotchesHz,
            windowCount = if (engineNotes != null) engineChunks else windowStarts.size,
            startupSuppressed = suppressed,
            overtoneSuppressed = overtoneDropped,
            shortSuppressed = shortDropped,
            harmonicSuppressed = harmonicSuppressed,
            harmonicExamined = harmonicExamined,
            analysisMs = (System.nanoTime() - startedAt) / 1_000_000L,
            audioDurationSec = audioDurationSec,
            sampleRate = targetSampleRate,
            engineId = engineId,
            engineLabel = engineLabel,
            engineFallback = engineFallback,
            ptiChunks = if (engineNotes != null) engineChunks else 0,
        )
    }

    /** A short, user-facing reason the final engine was not used. */
    private fun describeEngineFailure(error: Throwable): String = when (error) {
        is OutOfMemoryError -> "メモリが足りないため Basic Pitch で解析しました"
        is EngineUnavailableException -> "${error.message}（Basic Pitch で解析しました）"
        else -> "PTI モデルを読み込めなかったため Basic Pitch で解析しました"
    }

    data class Correlation(val matched: Int, val analysisOnly: Int, val liveOnly: Int)

    /** How the final notes relate to what the live pass had found (spec §5.7). */
    fun correlate(finalNotes: List<NoteEvent>, liveEvents: List<NoteEvent>, toleranceMs: Double = 120.0): Correlation {
        if (liveEvents.isEmpty()) return Correlation(0, finalNotes.size, 0)
        val used = BooleanArray(liveEvents.size)
        var matched = 0
        for (note in finalNotes) {
            var found = -1
            for (index in liveEvents.indices) {
                if (used[index]) continue
                val live = liveEvents[index]
                if (live.pitchMidi != note.pitchMidi) continue
                if (abs(live.onsetMs - note.onsetMs) > toleranceMs) continue
                found = index
                break
            }
            if (found >= 0) {
                used[found] = true
                matched += 1
            }
        }
        return Correlation(
            matched = matched,
            analysisOnly = finalNotes.size - matched,
            liveOnly = used.count { !it },
        )
    }

    /** Window starts that cover every sample, with the last window anchored to the end. */
    fun windowStarts(totalSamples: Int, windowLength: Int = WINDOW_SAMPLES, hop: Int = 22050): List<Int> {
        if (totalSamples <= 0) return listOf(0)
        if (totalSamples <= windowLength) return listOf(0)
        val starts = ArrayList<Int>()
        var start = 0
        while (start + windowLength < totalSamples) {
            starts.add(start)
            start += hop
        }
        starts.add(totalSamples - windowLength)
        return starts
    }

    companion object {
        const val WINDOW_SAMPLES = 43844 // Basic Pitch: 2 s at 22.05 kHz minus one hop

        /** Label for SCR-003 when Basic Pitch produced the notes. */
        const val ENGINE_LABEL_BASIC = "Basic Pitch（フォールバック）"

        /** Number of analysis windows for a duration, for the progress UI. */
        fun windowCount(durationSec: Double, windowHopS: Double = 1.0): Int =
            ceil(durationSec / windowHopS).toInt().coerceAtLeast(1)
    }
}
