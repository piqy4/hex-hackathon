package com.hex.stenographer.diarization

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hex.stenographer.audio.AndroidAudioImporter
import com.hex.stenographer.audio.normalizePeakInPlace
import com.hex.stenographer.models.ModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end on a device/emulator: APK assets -> ModelStore install -> WAV decode ->
 * sherpa-onnx diarization -> SpeakerTimeline. Uses the official 4-speaker sample in which the
 * first speaker talks again at ~22 s (the A–B–A check from the spec).
 */
@RunWith(AndroidJUnit4::class)
class SherpaDiarizationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext = instrumentation.targetContext

    @Test
    fun diarizesFourSpeakerSample() = runBlocking {
        val wav = File(appContext.cacheDir, "four-speakers.wav")
        instrumentation.context.assets.open("audio/0-four-speakers-zh.wav").use { input ->
            wav.outputStream().use { input.copyTo(it) }
        }

        val models = ModelStore(appContext)
        val t0 = SystemClock.elapsedRealtime()
        models.require(SherpaSpeakerDiarizer.SEGMENTATION_MODEL, SherpaSpeakerDiarizer.EMBEDDING_MODEL)
        val t1 = SystemClock.elapsedRealtime()
        val audio = AndroidAudioImporter(appContext).decode(Uri.fromFile(wav))
        audio.normalizePeakInPlace()
        val t2 = SystemClock.elapsedRealtime()
        val spans = SherpaSpeakerDiarizer(models).diarize(audio)
        val t3 = SystemClock.elapsedRealtime()

        spans.forEach {
            Log.i(TAG, "%s %.3f -- %.3f".format(it.speakerId, it.startSample / 16000f, it.endSample / 16000f))
        }
        Log.i(TAG, "models=${t1 - t0}ms decode=${t2 - t1}ms diarize=${t3 - t2}ms audio=${audio.durationMs}ms " +
            "rtf=${(t3 - t1).toDouble() / audio.durationMs}")

        assertTrue("expected speech", spans.isNotEmpty())
        for (i in 1 until spans.size) assertTrue(spans[i - 1].endSample <= spans[i].startSample)
        assertTrue(spans.last().endSample <= audio.sampleCount)
        val speakers = spans.map { it.speaker }.distinct()
        assertEquals((1..speakers.size).toList(), speakers)
        assertEquals("speaker count", 4, speakers.size)

        // A–B–A: speaker_1 comes back after someone else has spoken.
        val firstOther = spans.indexOfFirst { it.speaker != 1 }
        assertTrue("speaker_1 must return", spans.drop(firstOther).any { it.speaker == 1 })

        // Installing again is a cheap no-op (marker check, no re-hash).
        val t4 = SystemClock.elapsedRealtime()
        models.require(SherpaSpeakerDiarizer.SEGMENTATION_MODEL, SherpaSpeakerDiarizer.EMBEDDING_MODEL)
        assertTrue(SystemClock.elapsedRealtime() - t4 < 500)
    }

    private companion object {
        const val TAG = "SherpaDiarizationTest"
    }
}
