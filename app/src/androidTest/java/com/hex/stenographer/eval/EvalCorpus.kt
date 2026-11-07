package com.hex.stenographer.eval

import android.content.Context
import android.net.Uri
import com.hex.stenographer.audio.AndroidAudioImporter
import com.hex.stenographer.audio.PcmAudio
import com.hex.stenographer.transcript.Utterance
import java.io.File
import java.io.FileNotFoundException
import java.util.Random
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** One reference clip: a single speaker saying [text]. Speech-active region is [speechStart, speechEnd). */
class EvalClip(
    val name: String,
    val speaker: String,
    val source: String,
    val text: String,
    val samples: FloatArray,
    val speechStart: Int,
    val speechEnd: Int,
)

/** A recording with a reference transcript in export format. */
class EvalRecording(val name: String, val audio: PcmAudio, val reference: List<Utterance>)

/**
 * Loads evaluation clips (test assets `audio/clips.tsv`, `audio/irina.tsv`) and team recordings
 * (`audio/<name>.wav` + `audio/<name>.json` in the export format) and composes deterministic
 * dialogs for scenarios A/B/C of the spec.
 */
class EvalCorpus(private val testContext: Context, private val appContext: Context) {

    private val importer = AndroidAudioImporter(appContext)

    suspend fun loadClips(): List<EvalClip> {
        val rows = listOf("clips.tsv", "irina.tsv").flatMap { readAsset("audio/$it")?.lines().orEmpty() }
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
            .filter { it.size >= 4 }
        return rows.mapNotNull { (file, speaker, source, text) ->
            val audio = decodeAsset("audio/$file") ?: return@mapNotNull null
            val (a, b) = speechRegion(audio.samples, audio.sampleRate)
            if (b <= a) null else EvalClip(file, speaker, source, text, audio.samples, a, b)
        }
    }

    /** Team recordings: every `audio/X.wav` that has an `audio/X.json` reference next to it. */
    suspend fun loadRecordings(): List<EvalRecording> {
        val names = testContext.assets.list("audio").orEmpty()
        return names.filter { it.endsWith(".json") }.mapNotNull { json ->
            val base = json.removeSuffix(".json")
            val audio = decodeAsset("audio/$base.wav") ?: return@mapNotNull null
            EvalRecording(base, audio, parseReference(readAsset("audio/$json")!!))
        }
    }

    // ---------------------------------------------------------------- dialog composition

    /** Scenario A: clean A–B–A, a human reader and a synthetic voice alternating, 0.5–0.8 s pauses. */
    fun scenarioA(clips: List<EvalClip>): EvalRecording? {
        val human = clips.filter { it.speaker == "rulib" }.take(6)
        val tts = clips.filter { it.speaker == "irina" }.take(6)
        if (human.size < 3 || tts.size < 3) return null
        val turns = human.zip(tts).flatMap { (h, t) -> listOf(h, t) }
        return compose("A-clean-ABA", turns, Random(1), pauseMs = 500..800, edgeMs = 500)
    }

    /** Scenario B: fast turn-taking: short real-world phrases and short replies, 150–350 ms pauses. */
    fun scenarioB(clips: List<EvalClip>): EvalRecording? {
        val people = clips.filter { it.speaker.startsWith("sova-") }.take(14)
        val replies = clips.filter { it.speaker == "irina" }.drop(9)
        if (people.size < 4 || replies.isEmpty()) return null
        val turns = ArrayList<EvalClip>()
        people.forEachIndexed { i, p ->
            turns += p
            if (i % 2 == 1) turns += replies[(i / 2) % replies.size]
        }
        return compose("B-fast-changes", turns, Random(2), pauseMs = 150..350, edgeMs = 300, levelJitterDb = 3.0)
    }

    /** Scenario C: long pauses, silence at the edges and moderate background noise. */
    fun scenarioC(clips: List<EvalClip>): EvalRecording? {
        val far = clips.filter { it.speaker.startsWith("golosfar-") }.take(8)
        val human = clips.filter { it.speaker == "rulib" }.drop(6).take(5)
        if (far.size < 3 || human.size < 2) return null
        val turns = ArrayList<EvalClip>()
        far.forEachIndexed { i, f ->
            turns += f
            if (i % 2 == 1 && i / 2 < human.size) turns += human[i / 2]
        }
        // Speakers 0-10 dB below the others (farther from the phone) over -45 dBFS noise: ~15-22 dB SNR.
        return compose(
            "C-pauses-noise", turns, Random(3), pauseMs = 1500..4000, edgeMs = 2000,
            levelJitterDb = 5.0, levelOffsetDb = -5.0, noiseDbfs = -45.0,
        )
    }

    /**
     * Places [turns] on one timeline. Every clip is brought to [TARGET_DBFS] speech RMS (as if all
     * were recorded by one phone), shifted by [levelOffsetDb] ± [levelJitterDb]; optional white noise.
     */
    private fun compose(
        name: String,
        turns: List<EvalClip>,
        random: Random,
        pauseMs: IntRange,
        edgeMs: Int,
        levelJitterDb: Double = 0.0,
        levelOffsetDb: Double = 0.0,
        noiseDbfs: Double? = null,
    ): EvalRecording {
        val rate = PcmAudio.TARGET_SAMPLE_RATE
        val gaps = turns.indices.map { i ->
            if (i == 0) edgeMs * rate / 1000 else (pauseMs.first + random.nextInt(pauseMs.last - pauseMs.first + 1)) * rate / 1000
        }
        val total = turns.sumOf { it.samples.size } + gaps.sum() + edgeMs * rate / 1000
        val out = FloatArray(total)
        val reference = ArrayList<Utterance>(turns.size)
        var pos = 0
        turns.forEachIndexed { i, clip ->
            pos += gaps[i]
            val db = TARGET_DBFS + levelOffsetDb + (random.nextDouble() * 2 - 1) * levelJitterDb
            val gain = (dbToAmplitude(db) / speechRms(clip)).toFloat()
            for (k in clip.samples.indices) out[pos + k] = (clip.samples[k] * gain).coerceIn(-1f, 1f)
            reference += Utterance(
                speakerId = clip.speaker,
                startMs = (pos + clip.speechStart) * 1000L / rate,
                endMs = (pos + clip.speechEnd) * 1000L / rate,
                text = clip.text,
            )
            pos += clip.samples.size
        }
        noiseDbfs?.let { dbfs ->
            val sigma = dbToAmplitude(dbfs)
            for (i in out.indices) out[i] += (random.nextGaussian() * sigma).toFloat()
        }
        return EvalRecording(name, PcmAudio.of(out, rate), reference)
    }

    private fun speechRms(clip: EvalClip): Double {
        var acc = 0.0
        for (k in clip.speechStart until clip.speechEnd) acc += clip.samples[k].toDouble() * clip.samples[k]
        return max(sqrt(acc / (clip.speechEnd - clip.speechStart)), 1e-6)
    }

    private fun dbToAmplitude(db: Double): Double = 10.0.pow(db / 20)

    /**
     * Speech-active part of a clip: first/last 20 ms frame within 35 dB of the loudest frame.
     * Dataset clips carry their own leading/trailing silence, which must not count as a boundary error.
     */
    private fun speechRegion(x: FloatArray, rate: Int): Pair<Int, Int> {
        val frame = rate / 50
        val n = x.size / frame
        if (n == 0) return 0 to x.size
        val energy = FloatArray(n) { f ->
            var acc = 0f
            for (k in f * frame until (f + 1) * frame) acc += x[k] * x[k]
            acc / frame
        }
        val threshold = max(energy.max() * 3.16e-4f, 1e-7f) // -35 dB
        val first = energy.indexOfFirst { it >= threshold }
        val last = energy.indexOfLast { it >= threshold }
        return if (first < 0) 0 to 0 else first * frame to minOf(x.size, (last + 1) * frame)
    }

    // ---------------------------------------------------------------- assets

    private suspend fun decodeAsset(path: String): PcmAudio? {
        val file = File(appContext.cacheDir, "eval-" + path.substringAfterLast('/'))
        try {
            testContext.assets.open(path).use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (_: FileNotFoundException) {
            return null
        }
        return try {
            importer.decode(Uri.fromFile(file))
        } finally {
            file.delete()
        }
    }

    private fun readAsset(path: String): String? = try {
        testContext.assets.open(path).bufferedReader().use { it.readText() }
    } catch (_: FileNotFoundException) {
        null
    }

    private fun parseReference(json: String): List<Utterance> {
        val items = org.json.JSONObject(json).getJSONArray("transcript")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Utterance(o.getString("speaker_id"), o.getLong("start_ms"), o.getLong("end_ms"), o.getString("text"))
        }
    }

    private companion object {
        /** Typical speech level of a phone recording. */
        const val TARGET_DBFS = -23.0
    }
}
