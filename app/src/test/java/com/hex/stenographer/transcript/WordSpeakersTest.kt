package com.hex.stenographer.transcript

import com.hex.stenographer.asr.AsrChunk
import com.hex.stenographer.asr.Recognition
import com.hex.stenographer.diarization.SpeechSpan
import org.junit.Assert.*
import org.junit.Test

class WordSpeakersTest {
    @Test fun keepsWordsWholeAcrossSpeakerBoundary() {
        val result = Recognition("да согласен", floatArrayOf(0f,.1f,.2f,.3f,.4f,.5f,.6f,.7f,.8f,.9f),
            arrayOf("д","а"," ","с","о","г","л","а","с","ен"))
        val actual = WordSpeakers.assign(AsrChunk(1,0,1000),result,
            listOf(SpeechSpan(1,0,450),SpeechSpan(2,450,1000)),1000)!!
        assertEquals(listOf("да","согласен"),actual.map { it.text })
        assertEquals(listOf("speaker_1","speaker_2"),actual.map { it.speakerId })
        assertEquals(300L, actual[1].startMs)
        assertEquals(1000L, actual[1].endMs)
    }
    @Test fun rejectsMissingOrMismatchedAlignment() {
        val chunk = AsrChunk(1,0,1000)
        val spans = listOf(SpeechSpan(1,0,1000))
        assertNull(WordSpeakers.assign(chunk,Recognition("да",floatArrayOf()),spans,1000))
        assertNull(WordSpeakers.assign(chunk,Recognition("нет",floatArrayOf(0f),arrayOf("да")),spans,1000))
        assertNull(WordSpeakers.assign(chunk,Recognition("да",floatArrayOf(Float.NaN),arrayOf("да")),spans,1000))
    }
    @Test fun retainsOffsetAndRepeatedWords() {
        val result = Recognition("да да",floatArrayOf(0f,.2f,.4f),arrayOf("да"," ","да"))
        val actual = WordSpeakers.assign(AsrChunk(1,1000,2000),result,
            listOf(SpeechSpan(1,1000,1300),SpeechSpan(2,1300,2000)),1000)!!
        assertEquals(listOf("да","да"),actual.map { it.text })
        assertEquals(1000L,actual.first().startMs)
        assertEquals(1520L,actual.last().endMs)
    }
    @Test fun silenceAfterWordDoesNotMoveItToNextSpeaker() {
        val result = Recognition("да нет",floatArrayOf(.1f,.8f,.9f),arrayOf("да"," ","нет"))
        val actual = WordSpeakers.assign(AsrChunk(1,0,1200),result,
            listOf(SpeechSpan(1,0,300),SpeechSpan(2,300,1200)),1000)!!
        assertEquals(listOf("speaker_1","speaker_2"),actual.map { it.speakerId })
        assertEquals(220L,actual.first().endMs)
    }
    @Test fun joinsSpeakerTransitionsButNotLongSilences() {
        assertEquals(listOf(SpeechSpan(1,0,2000),SpeechSpan(1,2500,3000)),
            WordSpeakers.regions(listOf(SpeechSpan(1,0,1000),SpeechSpan(2,1000,2000),SpeechSpan(2,2500,3000)),1000))
    }
}
