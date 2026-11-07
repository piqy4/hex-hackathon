package com.example.hex.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** No mock model. Run in airplane mode on an arm64 Android device. */
@RunWith(AndroidJUnit4::class)
class RealLlmTest {
    @Test fun realOfflineInferenceAcceptsAtLeastOneCorrection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val start = System.nanoTime()
        val model = ModelStore(context).prepare()
        val input = listOf(
            Utterance("speaker_1", 0, 3000, "привет как дела"),
            Utterance("speaker_2", 4000, 7000, "добрый день коллеги"),
            Utterance("speaker_1", 8000, 11000, "спасибо до свидания")
        )
        val events = mutableListOf<PolishEvent>()
        val output = SafeTextPolisher({LlamaSession(model)}, {events += it}, timeoutMs = 60_000,
            totalBudgetMs = 240_000).polish(input)
        val elapsed = (System.nanoTime() - start) / 1_000_000
        val evidence = mapOf("kind" to "real_local_llm_module_test", "device" to android.os.Build.MODEL,
            "android" to android.os.Build.VERSION.RELEASE, "model_sha256" to ModelStore.SHA256,
            "elapsed_ms_including_model_prepare" to elapsed, "events" to events,
            "accepted_changes" to events.sumOf { it.changed }, "input" to input, "output" to output,
            "note" to "Manual test text and synthetic times; not ASR accuracy or end-to-end RTF evidence")
        File(context.filesDir,"llm-evidence.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(evidence))
        File(context.filesDir,"llm-test-export.json").writeText(TranscriptJson.encode(output,11000))
        assertEquals(input.size,output.size)
        input.zip(output).forEach { (a,b) ->
            assertEquals(a.copy(text=b.text), b)
            assertEquals(SafeTextPolisher.lexicalTokens(a.text),SafeTextPolisher.lexicalTokens(b.text))
        }
        assertTrue("Model must perform at least one validated correction; inspect llm-evidence.json",
            events.sumOf { it.changed } > 0)
        assertEquals(output,TranscriptJson.decode(TranscriptJson.encode(output,11000),11000))
    }
}
