package com.hex.stenographer.eval

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hex.stenographer.asr.AsrChunk
import com.hex.stenographer.asr.AsrConfig
import com.hex.stenographer.asr.AsrModel
import com.hex.stenographer.asr.SherpaOfflineRecognizer
import com.hex.stenographer.asr.TranscriptText
import com.hex.stenographer.audio.PcmAudio
import com.hex.stenographer.audio.normalizeLoudnessInPlace
import com.hex.stenographer.diarization.DiarizationConfig
import com.hex.stenographer.diarization.SherpaSpeakerDiarizer
import com.hex.stenographer.models.ModelStore
import com.hex.stenographer.transcript.BoundaryConfig
import com.hex.stenographer.transcript.DefaultSpeechEngine
import com.hex.stenographer.transcript.SpeechStage
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device evaluation protocol (spec, section 07). Prints one report row per recording to logcat
 * (tag `Eval`) and appends it to `Android/data/com.hex.stenographer/files/eval/report.tsv`.
 *
 * Inputs: `./gradlew downloadEvalSet` and `scripts/make-tts-turns.ps1` (clips and composed
 * dialogs A/B/C), plus team recordings in `test-audio/dialogs/` (wav + export-format json).
 *
 * Arguments (`adb shell am instrument -e <name> <value> ...`):
 * - `asrModel`: AsrModel id (default: the bundled one); `asrLanguage`: Whisper only, `auto` or code;
 * - `threshold`: clustering threshold; `embedding`: embedding weights file name;
 * - `trim`: `true`/`false`, tighten utterance edges to recognized tokens; `lead`, `tail`: margins, ms;
 * - `suite`: `clips` (ASR only), `dialogs`, or `all` (default).
 */
@RunWith(AndroidJUnit4::class)
class EvaluationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    private val asr = AsrConfig(
        model = args.getString("asrModel")?.let(AsrModel::byId) ?: AsrModel.DEFAULT,
        language = args.getString("asrLanguage")?.let { if (it == "auto") "" else it } ?: "ru",
    )
    private val diarization = DiarizationConfig(
        clusteringThreshold = args.getString("threshold")?.toFloat() ?: DiarizationConfig().clusteringThreshold,
        embeddingModel = args.getString("embedding") ?: SherpaSpeakerDiarizer.EMBEDDING_MODEL,
    )
    private val boundaries = BoundaryConfig(
        trimToTokens = args.getString("trim")?.toBoolean() ?: BoundaryConfig().trimToTokens,
        leadMs = args.getString("lead")?.toInt() ?: BoundaryConfig().leadMs,
        tailMs = args.getString("tail")?.toInt() ?: BoundaryConfig().tailMs,
    )
    private val suite = args.getString("suite") ?: "all"

    @Test
    fun evaluate() = runBlocking {
        val models = ModelStore(appContext)
        val recognizer = SherpaOfflineRecognizer(models, asr)
        val diarizer = SherpaSpeakerDiarizer(models, diarization)
        assumeTrue("weights for ${asr.model.id} are not available", models.isAvailable(*recognizer.requiredModels.toTypedArray()))
        assumeTrue("weights ${diarization.embeddingModel} are not available", models.isAvailable(*diarizer.requiredModels.toTypedArray()))
        models.require(*(recognizer.requiredModels + diarizer.requiredModels).toTypedArray())

        val corpus = EvalCorpus(instrumentation.context, appContext)
        val clips = corpus.loadClips()
        assumeTrue("no evaluation clips: run ./gradlew downloadEvalSet", clips.isNotEmpty())
        log("CONFIG ${config()} clips=${clips.size}")

        if (suite == "all" || suite == "clips") evaluateClips(recognizer, clips)
        if (suite == "all" || suite == "dialogs") {
            val engine = DefaultSpeechEngine(diarizer, recognizer, boundaries = boundaries)
            val recordings = listOfNotNull(corpus.scenarioA(clips), corpus.scenarioB(clips), corpus.scenarioC(clips)) +
                corpus.loadRecordings()
            for (r in recordings) evaluateRecording(engine, r)
        }
    }

    /** Pure ASR quality: every clip is one chunk, no diarization. One recognizer for all clips. */
    private suspend fun evaluateClips(recognizer: SherpaOfflineRecognizer, clips: List<EvalClip>) {
        val usable = clips.filter { it.samples.size <= 30 * PcmAudio.TARGET_SAMPLE_RATE }
        val gap = PcmAudio.TARGET_SAMPLE_RATE / 2
        val all = FloatArray(usable.sumOf { it.samples.size + gap })
        val chunks = ArrayList<AsrChunk>(usable.size)
        var pos = 0
        for (clip in usable) {
            clip.samples.copyInto(all, pos)
            chunks += AsrChunk(1, pos, pos + clip.samples.size)
            pos += clip.samples.size + gap
        }
        val audio = PcmAudio.of(all, PcmAudio.TARGET_SAMPLE_RATE)
        val t0 = SystemClock.elapsedRealtime()
        val results = recognizer.transcribe(audio, chunks)
        val asrMs = SystemClock.elapsedRealtime() - t0
        val speechMs = usable.sumOf { it.samples.size.toLong() } * 1000 / PcmAudio.TARGET_SAMPLE_RATE

        val byGroup = usable.indices.groupBy { usable[it].speaker.substringBefore('-') }
        val total = IntArray(4)
        for ((group, indices) in byGroup.toSortedMap()) {
            val g = IntArray(4)
            for (i in indices) {
                val w = Wer.compute(usable[i].text, TranscriptText.clean(results[i].text).orEmpty())
                g[0] += w.substitutions; g[1] += w.deletions; g[2] += w.insertions; g[3] += w.referenceWords
            }
            for (k in 0..3) total[k] += g[k]
            log("CLIPS ${config()} group=$group n=${indices.size} ${werString(g)}")
        }
        log("CLIPS ${config()} group=ALL n=${usable.size} ${werString(total)} asrMs=$asrMs audioMs=$speechMs " +
            "rtf=%.3f".format(asrMs.toDouble() / speechMs))
        report("clips", speechMs, werString(total), "asrMs=$asrMs", "rtf=%.3f".format(asrMs.toDouble() / speechMs))
    }

    private suspend fun evaluateRecording(engine: DefaultSpeechEngine, recording: EvalRecording) {
        val audio = recording.audio
        audio.normalizeLoudnessInPlace()
        var diarStart = 0L
        var asrStart = 0L
        val t0 = SystemClock.elapsedRealtime()
        val hypothesis = engine.run(audio) { stage, fraction ->
            if (fraction == null) {
                if (stage == SpeechStage.Diarization) diarStart = SystemClock.elapsedRealtime()
                if (stage == SpeechStage.Recognition) asrStart = SystemClock.elapsedRealtime()
            }
        }
        val end = SystemClock.elapsedRealtime()
        val score = TranscriptScorer.score(recording.reference, hypothesis)
        val rtf = (end - t0).toDouble() / audio.durationMs
        hypothesis.forEach { log("  ${recording.name} ${it.speakerId} ${it.startMs}-${it.endMs}: ${it.text}") }
        log("DIALOG ${recording.name} ${config()} dur=${audio.durationMs}ms ${score.summary()} " +
            "diarMs=${asrStart - diarStart} asrMs=${end - asrStart} rtf=%.3f".format(rtf))
        report(
            recording.name, audio.durationMs, score.summary(),
            "diarMs=${asrStart - diarStart} asrMs=${end - asrStart}", "rtf=%.3f".format(rtf),
        )
    }

    private fun config(): String =
        "asr=${asr.model.id}${if (asr.model.isWhisper) "/" + asr.language.ifEmpty { "auto" } else ""} " +
            "emb=${diarization.embeddingModel.substringBefore("-16k")} thr=${diarization.clusteringThreshold} trim=${boundaries.trimToTokens}/${boundaries.leadMs}/${boundaries.tailMs}"

    private fun werString(c: IntArray): String {
        val wer = if (c[3] == 0) 0.0 else (c[0] + c[1] + c[2]).toDouble() / c[3]
        return "WER=%.3f (S=%d D=%d I=%d N=%d)".format(wer, c[0], c[1], c[2], c[3])
    }

    /** Row of the spec's report template: file | duration | device | build & weights | metrics | times. */
    private fun report(name: String, durationMs: Long, metrics: String, times: String, rtf: String) {
        val dir = File(appContext.getExternalFilesDir(null), "eval").apply { mkdirs() }
        val row = listOf(
            name, "${durationMs}ms", "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}",
            config(), metrics, rtf, times,
        ).joinToString(" | ")
        File(dir, "report.tsv").appendText(row + "\n")
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "Eval"
    }
}
