package com.hex.stenographer.eval

import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.hex.stenographer.MainActivity
import com.example.hex.llm.*
import com.google.gson.GsonBuilder
import com.hex.stenographer.audio.*
import com.hex.stenographer.asr.*
import com.hex.stenographer.diarization.*
import com.hex.stenographer.models.ModelStore as SpeechModels
import com.hex.stenographer.transcript.DefaultSpeechEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Explicit file argument; private recordings and resulting text are never committed. */
class IntegratedPipelineTest {
    @Test fun importedAudioThroughRealModelsAndJson() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val source = File(context.getExternalFilesDir(null), args.getString("audio") ?: "test1.mp3")
        require(source.isFile) { "Push test audio to ${source.path}" }
        val models = SpeechModels(context)
        var wordAlignment: Boolean? = null
        val nativeRecognizer = SherpaOfflineRecognizer(models)
        var recognitionCall = 0
        val diagnosticRecognizer = object : SpeechRecognizer {
            override val requiredModels = nativeRecognizer.requiredModels
            override suspend fun transcribe(audio: PcmAudio, chunks: List<AsrChunk>, onProgress: (Float) -> Unit): List<Recognition> {
                val result = nativeRecognizer.transcribe(audio, chunks, onProgress)
                File(context.filesDir, "alignment-${recognitionCall++}.json").writeText(
                    GsonBuilder().create().toJson(result))
                return result
            }
        }
        val engine = DefaultSpeechEngine(SherpaSpeakerDiarizer(models), diagnosticRecognizer,
            onWordAlignment = { wordAlignment = it })
        models.require(*engine.requiredModels.toTypedArray())
        val model = ModelStore(context).prepare()
        val started = System.nanoTime()
        val audio = AndroidAudioImporter(context).decode(Uri.fromFile(source)) {}
        if (args.getString("rumble") == "true") audio.reduceRumbleInPlace()
        audio.normalizeLoudnessInPlace()
        val decoded = System.nanoTime()
        val raw = engine.run(audio)
        val recognized = System.nanoTime()
        val events = mutableListOf<PolishEvent>()
        val final = SafeTextPolisher({ LlamaSession(model) }, { events += it }).polish(raw)
        val finished = System.nanoTime()
        val json = TranscriptJson.encode(final, audio.durationMs)
        assertEquals(final, TranscriptJson.decode(json, audio.durationMs))
        raw.zip(final).forEach { (a,b) ->
            assertEquals(a.copy(text = b.text), b)
            assertEquals(SafeTextPolisher.lexicalTokens(a.text), SafeTextPolisher.lexicalTokens(b.text))
        }
        val suffix = if (args.getString("rumble") == "true") "rumble" else "baseline"
        File(context.filesDir, "integrated-$suffix.json").writeText(json)
        File(context.filesDir, "integrated-$suffix-evidence.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "device" to Build.MODEL, "android" to Build.VERSION.RELEASE, "foreground_activity" to true,
            "duration_ms" to audio.durationMs, "decode_ms" to (decoded-started)/1e6,
            "speech_ms" to (recognized-decoded)/1e6, "llm_ms" to (finished-recognized)/1e6,
            "rtf" to (finished-started)/1e6/audio.durationMs,
            "speakers" to final.map { it.speakerId }.distinct().size,
            "events" to events, "utterances" to final.size, "word_alignment" to wordAlignment,
            "note" to "No reference transcript: WER/DER not measured; model preparation excluded"
        )))
        assertTrue("Real speech expected in this fixture", raw.isNotEmpty())
        assertTrue("No accepted real model response", events.any { it.status == "accepted" })
        }
    }
}
