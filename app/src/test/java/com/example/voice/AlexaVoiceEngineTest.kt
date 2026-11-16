package com.example.voice

import android.content.Context
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlexaVoiceEngineTest {

    @Test
    fun `normalizeRmsDb clamps and scales speech volume into 0 to 1`() {
        assertEquals(0f, AlexaVoiceEngine.normalizeRmsDb(-5f), 0.001f)
        assertEquals(0f, AlexaVoiceEngine.normalizeRmsDb(-2f), 0.001f)
        assertEquals(0.5f, AlexaVoiceEngine.normalizeRmsDb(4f), 0.001f)
        assertEquals(1f, AlexaVoiceEngine.normalizeRmsDb(10f), 0.001f)
        assertEquals(1f, AlexaVoiceEngine.normalizeRmsDb(25f), 0.001f)
    }

    @Test
    fun `isStopCommand detects Alexa stop phrases accurately`() {
        assertTrue(AlexaVoiceEngine.isStopCommand("Stop"))
        assertTrue(AlexaVoiceEngine.isStopCommand("  stop listening! "))
        assertTrue(AlexaVoiceEngine.isStopCommand("Goodbye."))
        assertTrue(AlexaVoiceEngine.isStopCommand("cancel"))
        assertFalse(AlexaVoiceEngine.isStopCommand("Search latest AI news"))
        assertFalse(AlexaVoiceEngine.isStopCommand("Stop by the coffee shop on my calendar"))
    }

    @Test
    fun `sanitizeForSpeech strips markdown and links cleanly`() {
        val raw = "**Hello Sanskar!** Check `https://example.com/test` #NewEra _now_."
        val cleaned = AlexaVoiceEngine.sanitizeForSpeech(raw)
        assertEquals("Hello Sanskar! Check link NewEra now .", cleaned)
    }

    @Test
    fun `mapRecognizerError provides clear user guidance`() {
        val noMatch = AlexaVoiceEngine.mapRecognizerError(SpeechRecognizer.ERROR_NO_MATCH)
        val permission = AlexaVoiceEngine.mapRecognizerError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertTrue(noMatch.contains("System Voice Input"))
        assertTrue(permission.contains("Microphone permission"))
    }

    @Test
    fun `engine initializes cleanly and resets state on cancelListening`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = AlexaVoiceEngine(context)
        assertFalse(engine.isListening.value)
        assertFalse(engine.isSpeaking.value)
        assertEquals(0f, engine.rmsLevel.value, 0.001f)
        assertEquals(VoiceCaptureMode.COMMAND, engine.captureMode.value)
        assertNull(engine.voiceError.value)

        engine.cancelListening()
        assertFalse(engine.isListening.value)
        assertEquals("", engine.partialTranscript.value)
        assertEquals(0f, engine.rmsLevel.value, 0.001f)
        engine.release()
    }

    @Test
    fun `computePcmRms calculates normalized level and handles silence`() {
        val silence = ShortArray(160) { 0 }
        assertEquals(0f, AlexaVoiceEngine.computePcmRms(silence, silence.size), 0.001f)
        assertEquals(0f, AlexaVoiceEngine.computePcmRms(silence, 0), 0.001f)

        val loud = ShortArray(160) { 20000 }
        assertEquals(1f, AlexaVoiceEngine.computePcmRms(loud, loud.size), 0.001f)

        val moderate = ShortArray(160) { 3250 }
        assertEquals(0.5f, AlexaVoiceEngine.computePcmRms(moderate, moderate.size), 0.01f)
    }

    @Test
    fun `buildWavBytes writes valid 44-byte RIFF WAVE header and payload`() {
        val pcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val wav = AlexaVoiceEngine.buildWavBytes(pcm, sampleRate = 16000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12), Charsets.US_ASCII))
        assertEquals("fmt ", String(wav.copyOfRange(12, 16), Charsets.US_ASCII))
        assertEquals("data", String(wav.copyOfRange(36, 40), Charsets.US_ASCII))
    }

    @Test
    fun `isServiceFailureForAudioFallback distinguishes permission and hardware errors from service errors`() {
        assertTrue(AlexaVoiceEngine.isServiceFailureForAudioFallback(SpeechRecognizer.ERROR_CLIENT))
        assertTrue(AlexaVoiceEngine.isServiceFailureForAudioFallback(SpeechRecognizer.ERROR_SERVER))
        assertTrue(AlexaVoiceEngine.isServiceFailureForAudioFallback(SpeechRecognizer.ERROR_NETWORK))
        assertFalse(AlexaVoiceEngine.isServiceFailureForAudioFallback(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(AlexaVoiceEngine.isServiceFailureForAudioFallback(SpeechRecognizer.ERROR_AUDIO))
    }
}
