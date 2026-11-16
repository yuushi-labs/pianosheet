package com.yuushilabs.pianosheet.domain.live

import com.yuushilabs.pianosheet.domain.model.NoteEvent
import kotlin.math.abs
import kotlin.math.min

/**
 * Streaming (live) transcription engine — the Kotlin port of the Phase 0 engine
 * (`phase0-poc/pianosheet_poc/live.py`).
 *
 * Audio chunks go in; provisional note events come out.  The four-stage
 * duplicate suppression measured in Phase 0 lives here:
 *
 *  1. onset strength floor (a decay tail has ~0, a key strike ~0.9),
 *  2. only the newly reachable part of the timeline is committed (the newest
 *     [LiveConfig.guardMs] is left for the next step, older onsets are dropped),
 *  3. same pitch + onset within [LiveConfig.onsetToleranceMs] is the same note,
 *  4. a weak attack that continues a ringing same-pitch note is a continuation,
 *     not a re-strike.
 */
class LiveTranscriptionEngine(
    val config: LiveConfig = LiveConfig(),
    private val detector: ChunkDetector,
) {
    private val chunks = ArrayDeque<FloatArray>()
    private var chunksLength = 0
    private var absoluteSamples = 0L
    private var nextStep = 0L
    private var committedUntilS = 0.0
    private var firstStep = true

    private val _events = ArrayList<NoteEvent>()
    private val _steps = ArrayList<StepStat>()

    val events: List<NoteEvent> get() = _events
    val steps: List<StepStat> get() = _steps

    var rawCandidates: Int = 0
        private set
    var duplicatesDropped: Int = 0
        private set
    var staleFiltered: Int = 0
        private set
    var weakDropped: Int = 0
        private set
    var lowConfidenceDropped: Int = 0
        private set

    private var lastComputeMs = 0.0

    val detectorName: String get() = detector.name

    fun startSession() {
        chunks.clear()
        chunksLength = 0
        absoluteSamples = 0L
        nextStep = 0L
        committedUntilS = 0.0
        firstStep = true
        _events.clear()
        _steps.clear()
        rawCandidates = 0
        duplicatesDropped = 0
        staleFiltered = 0
        weakDropped = 0
        lowConfidenceDropped = 0
        lastComputeMs = 0.0
    }

    /** Push audio (already at [LiveConfig.sampleRate]); returns events this call added. */
    fun pushAudio(chunk: FloatArray): List<NoteEvent> {
        if (chunk.isEmpty()) return emptyList()
        chunks.addLast(chunk)
        chunksLength += chunk.size
        absoluteSamples += chunk.size
        trim()

        val emitted = ArrayList<NoteEvent>()
        if (firstStep) {
            nextStep = min(config.stepSamples, absoluteSamples)
            firstStep = false
        }
        while (absoluteSamples >= nextStep) {
            emitted.addAll(runStep())
            nextStep += config.stepSamples
        }
        return emitted
    }

    /** Flush audio that no step boundary has covered yet, then close. */
    fun finishSession(): List<NoteEvent> {
        if (absoluteSamples > nextStep - config.stepSamples) {
            if (nextStep != absoluteSamples) {
                nextStep = absoluteSamples
                return runStep()
            }
        }
        return emptyList()
    }

    fun eventsSnapshot(): List<NoteEvent> = _events.toList()

    private fun trim() {
        while (chunks.size > 1 && chunksLength - chunks.first().size >= config.windowSamples) {
            chunksLength -= chunks.removeFirst().size
        }
    }

    private fun windowAudio(endSample: Long): Pair<FloatArray, Long> {
        val start = (endSample - config.windowSamples).coerceAtLeast(0L)
        val need = (endSample - start).toInt()
        val total = chunksLength
        val offset = absoluteSamples - total
        val all = FloatArray(total)
        var cursor = 0
        for (chunk in chunks) {
            chunk.copyInto(all, cursor)
            cursor += chunk.size
        }
        val lo = (start - offset).coerceAtLeast(0L).toInt()
        val hi = (endSample - offset).coerceAtLeast(lo.toLong()).toInt().coerceAtMost(all.size)
        val audio = all.copyOfRange(lo, hi)
        if (audio.size < need) {
            val padded = FloatArray(need)
            audio.copyInto(padded, need - audio.size)
            return Pair(padded, start)
        }
        return Pair(audio, start)
    }

    private fun runStep(): List<NoteEvent> {
        val endSample = nextStep
        val (audio, startSample) = windowAudio(endSample)
        val audioTimeS = endSample.toDouble() / config.sampleRate

        val started = System.nanoTime()
        val detections = detector.detect(audio, startSample.toDouble() / config.sampleRate)
        lastComputeMs = (System.nanoTime() - started) / 1_000_000.0

        val raw = ArrayList<NoteEvent>(detections.size)
        for (detection in detections) {
            if (detection.onsetS < 0.0) continue
            raw.add(
                NoteEvent(
                    pitchMidi = detection.pitchMidi,
                    onsetMs = detection.onsetS * 1000.0,
                    durationMs = (detection.endS - detection.onsetS) * 1000.0,
                    confidence = detection.confidence,
                    onsetStrength = detection.onsetStrength,
                    sourceChunk = _steps.size,
                    detector = detector.name,
                ),
            )
        }
        rawCandidates += raw.size

        val candidates = ArrayList<NoteEvent>(raw.size)
        for (candidate in raw) {
            val strength = candidate.onsetStrength
            if (strength != null && strength < config.onsetStrengthMin) {
                weakDropped += 1
                continue
            }
            if (candidate.confidence < config.confidenceFloor) {
                lowConfidenceDropped += 1
                continue
            }
            candidates.add(candidate)
        }

        val allowedMax = audioTimeS - config.guardMs / 1000.0
        val lowerBound = committedUntilS - config.onsetToleranceMs / 1000.0
        val inRegion = ArrayList<NoteEvent>(candidates.size)
        for (candidate in candidates) {
            if (candidate.onsetS > lowerBound && candidate.onsetS <= allowedMax) {
                inRegion.add(candidate)
            }
        }
        staleFiltered += candidates.size - inRegion.size
        if (allowedMax > committedUntilS) committedUntilS = allowedMax

        val newEvents = merge(inRegion, audioTimeS)
        _steps.add(
            StepStat(
                stepIndex = _steps.size,
                audioTimeS = round3(audioTimeS),
                computeMs = round2(lastComputeMs),
                nCandidates = inRegion.size,
                nNew = newEvents.size,
                bufferMs = round2(1000.0 * endSample / config.sampleRate),
            ),
        )
        _events.addAll(newEvents)
        return newEvents
    }

    private fun merge(candidates: List<NoteEvent>, audioTimeS: Double): List<NoteEvent> {
        val fresh = ArrayList<NoteEvent>()
        for (candidate in candidates) {
            var duplicate: NoteEvent? = null
            // Events added by this same step first: two candidates a few frames
            // apart in one window describe the same strike.
            for (index in fresh.indices.reversed()) {
                if (isDuplicate(fresh[index], candidate)) {
                    duplicate = fresh[index]
                    break
                }
            }
            if (duplicate == null) {
                val from = maxOf(0, _events.size - 96)
                for (index in _events.size - 1 downTo from) {
                    if (isDuplicate(_events[index], candidate)) {
                        duplicate = _events[index]
                        break
                    }
                }
            }
            if (duplicate != null) {
                duplicatesDropped += 1
                if (candidate.confidence > duplicate.confidence) {
                    duplicate.confidence = candidate.confidence
                }
                val candidateDuration = candidate.durationMs
                if (candidateDuration != null) {
                    val candidateEnd = candidate.onsetMs + candidateDuration
                    val duplicateEnd = duplicate.onsetMs + (duplicate.durationMs ?: 0.0)
                    if (candidateEnd > duplicateEnd) {
                        duplicate.durationMs = candidateEnd - duplicate.onsetMs
                    }
                }
                continue
            }
            candidate.sequence = _events.size + fresh.size
            candidate.emittedAtS = audioTimeS
            candidate.renderMs = lastComputeMs
            fresh.add(candidate)
        }
        return fresh
    }

    private fun isDuplicate(existing: NoteEvent, candidate: NoteEvent): Boolean {
        if (existing.pitchMidi != candidate.pitchMidi) return false
        if (abs(existing.onsetMs - candidate.onsetMs) <= config.onsetToleranceMs) return true
        val existingEnd = existing.onsetMs + (existing.durationMs ?: 0.0)
        val strength = candidate.onsetStrength
        val weakAttack = strength != null && strength < config.restrikeOnsetMin
        return weakAttack &&
            existingEnd > candidate.onsetMs - config.tailMergeMs &&
            candidate.onsetMs >= existing.onsetMs
    }

    /** p95 of the display latency of the events reported so far (ms), for the header. */
    fun latencyP95Ms(): Double? {
        val latencies = _events.mapNotNull { it.latencyMs }.sorted()
        if (latencies.isEmpty()) return null
        val index = ((latencies.size - 1) * 0.95).toInt()
        return latencies[index]
    }

    fun lastLatencyMs(): Double? = _events.lastOrNull()?.latencyMs

    fun lastComputeMs(): Double = lastComputeMs

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
    private fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0
}
