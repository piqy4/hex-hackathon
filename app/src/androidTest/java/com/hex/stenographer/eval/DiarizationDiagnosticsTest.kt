package com.hex.stenographer.eval

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import com.hex.stenographer.MainActivity
import com.google.gson.GsonBuilder
import com.hex.stenographer.audio.*
import com.hex.stenographer.diarization.SherpaSpeakerDiarizer
import com.hex.stenographer.models.ModelStore
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Diagnostic sweep only; never tunes production defaults from a single unlabelled file. */
class DiarizationDiagnosticsTest {
    @Test fun compareRawBoundaries() = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.getExternalFilesDir(null), InstrumentationRegistry.getArguments().getString("audio") ?: "test1.mp3")
        val (segmentation, embedding) = ModelStore(context).require(
            SherpaSpeakerDiarizer.SEGMENTATION_MODEL, SherpaSpeakerDiarizer.EMBEDDING_MODEL,
        )
        val original = AndroidAudioImporter(context).decode(Uri.fromFile(source)) {}
        val runs = mutableListOf<Any>()
        for (rumble in listOf(false, true)) {
            val audio = PcmAudio.of(original.samples.copyOf(), original.sampleRate)
            if (rumble) audio.reduceRumbleInPlace()
            audio.normalizeLoudnessInPlace()
            for (threshold in listOf(0.5f, 0.65f, 0.75f, 0.85f)) {
                val config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentation.path),
                        numThreads = 4, provider = "cpu",
                    ),
                    embedding = SpeakerEmbeddingExtractorConfig(model = embedding.path, numThreads = 4, provider = "cpu"),
                    clustering = FastClusteringConfig(numClusters = -1, threshold = threshold),
                    minDurationOn = 0.2f, minDurationOff = 0f,
                )
                val engine = OfflineSpeakerDiarization(config = config)
                try {
                    val result = engine.processWithCallback(audio.samples, SherpaSpeakerDiarizer.ProgressCallback {})
                    runs += mapOf("rumble" to rumble, "threshold" to threshold,
                        "segments" to result.map { mapOf("start" to it.start, "end" to it.end, "cluster" to it.speaker) })
                } finally { engine.release() }
            }
        }
        File(context.filesDir, "diarization-diagnostics.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(runs))
        }
    }
}
