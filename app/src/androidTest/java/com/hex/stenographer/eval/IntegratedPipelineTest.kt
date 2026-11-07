package com.hex.stenographer.eval

import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hex.stenographer.asr.SherpaOfflineRecognizer
import com.hex.stenographer.audio.normalizeLoudnessInPlace
import com.hex.stenographer.diarization.SherpaSpeakerDiarizer
import com.hex.stenographer.llm.*
import com.hex.stenographer.models.ModelStore
import com.hex.stenographer.transcript.DefaultSpeechEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual native inference on composed A/B/C. RTF here starts at PCM, excluding corpus decoding. */
@RunWith(AndroidJUnit4::class)
class IntegratedPipelineTest {
    @Test fun dialogsThroughLlmAndJson() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val corpus = EvalCorpus(instrumentation.context, context)
        val clips = corpus.loadClips()
        val assets = instrumentation.context.assets.list("audio").orEmpty().toSet()
        org.junit.Assume.assumeTrue("Supply evaluation corpus or -PevalFixtureDir",
            clips.isNotEmpty() || listOf("A", "B", "C").all {
                (it + "_synthetic.wav") in assets && (it + ".reference.json") in assets
            })
        val recordings = if (clips.isNotEmpty()) listOf(
            checkNotNull(corpus.scenarioA(clips)),
            checkNotNull(corpus.scenarioB(clips)),
            checkNotNull(corpus.scenarioC(clips)),
        ) else listOf("A", "B", "C").map { name ->
            val file = File(context.cacheDir, name + "_synthetic.wav")
            instrumentation.context.assets.open("audio/" + file.name).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val audio = com.hex.stenographer.audio.AndroidAudioImporter(context)
                .decode(android.net.Uri.fromFile(file)) {}
            val reference = instrumentation.context.assets.open("audio/" + name + ".reference.json")
                .bufferedReader().use { it.readText() }
            EvalRecording(name + "_synthetic", audio, TranscriptJson.decode(reference, audio.durationMs))
        }
        val models = ModelStore(context)
        val engine = DefaultSpeechEngine(SherpaSpeakerDiarizer(models), SherpaOfflineRecognizer(models))
        val llm = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        models.require(*(engine.requiredModels + llm).toTypedArray())
        val model = models.require(llm).single()
        val dir = File(context.getExternalFilesDir(null), "integrated-eval").apply { mkdirs() }
        val rows = mutableListOf<String>()
        for (recording in recordings) {
            val started = SystemClock.elapsedRealtime()
            recording.audio.normalizeLoudnessInPlace()
            val raw = engine.run(recording.audio)
            val asrDone = SystemClock.elapsedRealtime()
            val events = mutableListOf<PolishEvent>()
            val polished = SafeTextPolisher({ LlamaSession(model) }, { events += it }).polish(raw)
            val finished = SystemClock.elapsedRealtime()
            assertTrue("Expected speech in composed dialog", polished.isNotEmpty())
            assertEquals(raw.size, polished.size)
            raw.zip(polished).forEach { (a, b) ->
                assertEquals(a.copy(text = b.text), b)
                assertEquals(SafeTextPolisher.lexicalTokens(a.text), SafeTextPolisher.lexicalTokens(b.text))
            }
            val json = TranscriptJson.encode(polished, recording.audio.durationMs)
            assertEquals(polished, TranscriptJson.decode(json, recording.audio.durationMs))
            File(dir, recording.name + ".json").writeText(json, Charsets.UTF_8)
            val score = TranscriptScorer.score(recording.reference, polished)
            val row = listOf(recording.name, Build.MODEL, "Android " + Build.VERSION.RELEASE,
                "durationMs=" + recording.audio.durationMs, score.summary(),
                "pcmToAsrMs=" + (asrDone - started), "llmMs=" + (finished - asrDone),
                "rtfFromPcm=" + (finished - started).toDouble() / recording.audio.durationMs,
                "llmChanges=" + events.sumOf { it.changed }, "export=validated").joinToString(" | ")
            rows += row
            File(dir, "report.txt").writeText(rows.joinToString("\n"), Charsets.UTF_8)
            Log.i("IntegratedEval", row)
        }
    }
}
