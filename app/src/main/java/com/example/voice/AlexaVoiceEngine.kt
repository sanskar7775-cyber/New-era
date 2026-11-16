package com.example.voice

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.math.sqrt

enum class VoiceCaptureMode {
    COMMAND,
    DICTATION
}

enum class VoiceProsodyProfile(
    val label: String,
    val speechRate: Float,
    val pitch: Float
) {
    BALANCED("Natural", 1.03f, 1.0f),
    CRISP_FAST("Fast Duplex", 1.22f, 1.02f),
    CALM_DEEP("Calm", 0.92f, 0.95f)
}

class AlexaVoiceEngine(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var activeUtteranceId: String? = null
    private var onSpeakCompleted: (() -> Unit)? = null
    private var activeFinalCallback: ((String) -> Unit)? = null
    private var activePartialCallback: ((String) -> Unit)? = null
    private var activeAudioCallback: ((ByteArray) -> Unit)? = null
    private var hasDeliveredSessionResult = false
    private var activeSessionMode = VoiceCaptureMode.COMMAND

    private var audioRecordJob: Job? = null
    @Volatile
    private var stopAudioRecordingRequested = false
    @Volatile
    private var cancelAudioRecordingRequested = false

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    private val _usingAudioFallback = MutableStateFlow(false)
    val usingAudioFallback: StateFlow<Boolean> = _usingAudioFallback.asStateFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    private val _captureMode = MutableStateFlow(VoiceCaptureMode.COMMAND)
    val captureMode: StateFlow<VoiceCaptureMode> = _captureMode.asStateFlow()

    private val _prosodyProfile = MutableStateFlow(VoiceProsodyProfile.BALANCED)
    val prosodyProfile: StateFlow<VoiceProsodyProfile> = _prosodyProfile.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val res = tts?.setLanguage(Locale.US)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
                tts?.setSpeechRate(_prosodyProfile.value.speechRate)
                tts?.setPitch(_prosodyProfile.value.pitch)
                isTtsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnMain {
                            if (utteranceId == activeUtteranceId) {
                                _isSpeaking.value = true
                            }
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        runOnMain {
                            if (utteranceId == activeUtteranceId) {
                                _isSpeaking.value = false
                                activeUtteranceId = null
                                val callback = onSpeakCompleted
                                onSpeakCompleted = null
                                callback?.invoke()
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnMain {
                            if (utteranceId == activeUtteranceId) {
                                _isSpeaking.value = false
                                activeUtteranceId = null
                                onSpeakCompleted = null
                            }
                        }
                    }
                })
            }
        }
    }

    fun isSpeechRecognitionAvailable(): Boolean {
        val standard = SpeechRecognizer.isRecognitionAvailable(appContext)
        val onDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)
        } else {
            false
        }
        return standard || onDevice
    }

    fun startListening(
        mode: VoiceCaptureMode = VoiceCaptureMode.COMMAND,
        preferDirectAudioCapture: Boolean = false,
        onPartial: (String) -> Unit = {},
        onAudioCaptured: ((ByteArray) -> Unit)? = null,
        onFinalResult: (String) -> Unit
    ) {
        runOnMain {
            _voiceError.value = null
            _captureMode.value = mode
            activeSessionMode = mode
            _partialTranscript.value = ""
            _rmsLevel.value = 0f

            stopSpeakingInternal()
            stopAudioCaptureInternal(cancel = true)

            activePartialCallback = onPartial
            activeFinalCallback = onFinalResult
            activeAudioCallback = onAudioCaptured
            hasDeliveredSessionResult = false

            if (preferDirectAudioCapture && onAudioCaptured != null) {
                startDirectAudioCapture(onAudioCaptured)
                return@runOnMain
            }

            val standardAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
            val onDeviceAvailable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)
            } else {
                false
            }

            if (!standardAvailable && !onDeviceAvailable) {
                if (onAudioCaptured != null) {
                    Log.i(TAG, "SpeechRecognizer unavailable; starting direct Gemini mic capture.")
                    startDirectAudioCapture(onAudioCaptured)
                } else {
                    _voiceError.value = "Speech recognition service unavailable on this device."
                }
                return@runOnMain
            }

            try {
                _usingAudioFallback.value = false
                speechRecognizer?.destroy()
                speechRecognizer = if (standardAvailable) {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }.apply {
                    setRecognitionListener(createRecognitionListener())
                }

                val langTag = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    // Android RecognizerIntent expects String language tag, not Locale object
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langTag)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
                }
                _isListening.value = true
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                Log.w(TAG, "SpeechRecognizer start failed, falling back if possible", e)
                if (onAudioCaptured != null) {
                    startDirectAudioCapture(onAudioCaptured)
                } else {
                    _isListening.value = false
                    _rmsLevel.value = 0f
                    _voiceError.value = e.localizedMessage ?: "Failed to start voice listener."
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDirectAudioCapture(onAudioCaptured: (ByteArray) -> Unit) {
        _usingAudioFallback.value = true
        _isListening.value = true
        _partialTranscript.value = ""
        stopAudioRecordingRequested = false
        cancelAudioRecordingRequested = false

        audioRecordJob?.cancel()
        audioRecordJob = audioScope.launch {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                .coerceAtLeast(3200)

            var recorder: AudioRecord? = null
            try {
                recorder = createAudioRecordWithFallback(sampleRate, channelConfig, audioFormat, minBuf * 2)
                if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) {
                        _isListening.value = false
                        _usingAudioFallback.value = false
                        _rmsLevel.value = 0f
                        _voiceError.value = "Could not initialize microphone audio capture. Check mic permission."
                    }
                    return@launch
                }

                recorder.startRecording()
                val shortBuf = ShortArray(minBuf / 2)
                val pcmStream = ByteArrayOutputStream()
                val byteBuf = ByteBuffer.allocate(shortBuf.size * 2).order(ByteOrder.LITTLE_ENDIAN)

                val startTime = System.currentTimeMillis()
                var speechDetected = false
                var lastLoudTime = startTime
                var peakRms = 0f

                while (isActive && !stopAudioRecordingRequested && !cancelAudioRecordingRequested) {
                    val read = recorder.read(shortBuf, 0, shortBuf.size)
                    if (read > 0) {
                        byteBuf.clear()
                        for (i in 0 until read) {
                            byteBuf.putShort(shortBuf[i])
                        }
                        pcmStream.write(byteBuf.array(), 0, read * 2)

                        val rms = computePcmRms(shortBuf, read)
                        _rmsLevel.value = rms
                        if (rms > peakRms) peakRms = rms

                        val now = System.currentTimeMillis()
                        if (rms > 0.14f) {
                            speechDetected = true
                            lastLoudTime = now
                        }
                        val elapsed = now - startTime
                        // Auto-stop ~1.5s after user stops speaking, or after 12s max
                        if (speechDetected && (now - lastLoudTime) > 1500L && elapsed > 1200L) {
                            break
                        }
                        if (elapsed > 12_000L) {
                            break
                        }
                    } else if (read < 0) {
                        break
                    }
                }

                try {
                    recorder.stop()
                } catch (_: Exception) {
                }

                val cancelled = cancelAudioRecordingRequested
                val pcmBytes = pcmStream.toByteArray()

                withContext(Dispatchers.Main) {
                    _isListening.value = false
                    _rmsLevel.value = 0f
                    if (!cancelled) {
                        if (pcmBytes.size < 3200) {
                            _voiceError.value = "Recording too short. Speak clearly or tap Send Now after speaking."
                        } else {
                            val wavBytes = buildWavBytes(pcmBytes, sampleRate)
                            onAudioCaptured(wavBytes)
                        }
                    }
                    _usingAudioFallback.value = false
                }
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    _isListening.value = false
                    _usingAudioFallback.value = false
                    _rmsLevel.value = 0f
                    _voiceError.value = "Microphone permission is required for voice commands."
                }
            } catch (e: Exception) {
                Log.e(TAG, "Direct audio capture failed", e)
                withContext(Dispatchers.Main) {
                    _isListening.value = false
                    _usingAudioFallback.value = false
                    _rmsLevel.value = 0f
                    _voiceError.value = e.localizedMessage ?: "Audio capture error."
                }
            } finally {
                try {
                    recorder?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun createAudioRecordWithFallback(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int,
        bufferSize: Int
    ): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
        for (source in sources) {
            try {
                @SuppressLint("MissingPermission")
                val rec = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    return rec
                }
                rec.release()
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun setTranscribing(transcribing: Boolean) {
        _isTranscribing.value = transcribing
    }

    fun setVoiceError(message: String?) {
        _voiceError.value = message
    }

    fun sendCurrentPartialNow() {
        runOnMain {
            if (_usingAudioFallback.value) {
                stopAudioRecordingRequested = true
                return@runOnMain
            }
            val text = _partialTranscript.value.trim()
            if (text.isNotBlank() && !hasDeliveredSessionResult) {
                hasDeliveredSessionResult = true
                val callback = activeFinalCallback
                activeFinalCallback = null
                activePartialCallback = null
                _isListening.value = false
                _rmsLevel.value = 0f
                try {
                    speechRecognizer?.cancel()
                } catch (_: Exception) {
                }
                callback?.invoke(text)
            } else {
                stopListening()
            }
        }
    }

    fun stopListening() {
        runOnMain {
            if (_usingAudioFallback.value) {
                stopAudioRecordingRequested = true
                return@runOnMain
            }
            _rmsLevel.value = 0f
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {
                _isListening.value = false
            }
        }
    }

    fun cancelListening() {
        runOnMain {
            hasDeliveredSessionResult = true
            activeFinalCallback = null
            activePartialCallback = null
            activeAudioCallback = null
            stopAudioCaptureInternal(cancel = true)
            _isListening.value = false
            _usingAudioFallback.value = false
            _rmsLevel.value = 0f
            _partialTranscript.value = ""
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopAudioCaptureInternal(cancel: Boolean) {
        if (cancel) {
            cancelAudioRecordingRequested = true
            audioRecordJob?.cancel()
            audioRecordJob = null
        } else {
            stopAudioRecordingRequested = true
        }
    }

    fun speak(text: String, onFinished: (() -> Unit)? = null) {
        runOnMain {
            val cleanText = sanitizeForSpeech(text)
            if (!isTtsReady || cleanText.isBlank()) {
                onFinished?.invoke()
                return@runOnMain
            }
            val utteranceId = UUID.randomUUID().toString()
            activeUtteranceId = utteranceId
            onSpeakCompleted = onFinished
            _isSpeaking.value = true
            val result = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                _isSpeaking.value = false
                activeUtteranceId = null
                onSpeakCompleted = null
            }
        }
    }

    fun setProsodyProfile(profile: VoiceProsodyProfile) {
        _prosodyProfile.value = profile
        runOnMain {
            tts?.setSpeechRate(profile.speechRate)
            tts?.setPitch(profile.pitch)
        }
    }

    fun stopSpeaking() {
        runOnMain {
            stopSpeakingInternal()
        }
    }

    fun clearError() {
        _voiceError.value = null
    }

    fun release() {
        runOnMain {
            activeFinalCallback = null
            activePartialCallback = null
            activeAudioCallback = null
            onSpeakCompleted = null
            stopAudioCaptureInternal(cancel = true)
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {
            }
            speechRecognizer = null
            try {
                tts?.stop()
                tts?.shutdown()
            } catch (_: Exception) {
            }
            tts = null
        }
    }

    private fun stopSpeakingInternal() {
        activeUtteranceId = null
        onSpeakCompleted = null
        _isSpeaking.value = false
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    private fun createRecognitionListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _isListening.value = true
            _voiceError.value = null
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            _rmsLevel.value = normalizeRmsDb(rmsdB)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Keep _isListening true until onResults/onError delivers final text or error
            _rmsLevel.value = 0f
        }

        override fun onError(error: Int) {
            _rmsLevel.value = 0f
            if (hasDeliveredSessionResult) {
                _isListening.value = false
                return
            }

            // Use buffered partial text if speech timed out after capturing words
            val bufferedPartial = _partialTranscript.value.trim()
            if (bufferedPartial.isNotBlank() &&
                (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)
            ) {
                _isListening.value = false
                deliverFinalResult(bufferedPartial)
                return
            }

            // If SpeechRecognizer service failed (common on emulators / offline devices),
            // automatically switch to direct AudioRecord + Gemini speech transcription!
            val audioCb = activeAudioCallback
            if (audioCb != null && isServiceFailureForAudioFallback(error)) {
                Log.w(TAG, "SpeechRecognizer failed ($error); switching to direct Gemini mic capture.")
                try {
                    speechRecognizer?.destroy()
                } catch (_: Exception) {
                }
                speechRecognizer = null
                startDirectAudioCapture(audioCb)
                return
            }

            _isListening.value = false
            _voiceError.value = mapRecognizerError(error)
            activeFinalCallback = null
            activePartialCallback = null
            activeAudioCallback = null
        }

        override fun onResults(results: Bundle?) {
            _isListening.value = false
            _rmsLevel.value = 0f
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull().orEmpty().trim()
            if (text.isNotBlank()) {
                _partialTranscript.value = text
                deliverFinalResult(text)
            } else {
                val fallback = _partialTranscript.value.trim()
                if (fallback.isNotBlank()) {
                    deliverFinalResult(fallback)
                } else {
                    _voiceError.value = "Didn't catch that. Tap the orb or Gemini Mic to try again."
                    activeFinalCallback = null
                    activePartialCallback = null
                    activeAudioCallback = null
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (hasDeliveredSessionResult) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (partial.isNotBlank()) {
                _partialTranscript.value = partial
                activePartialCallback?.invoke(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun deliverFinalResult(text: String) {
        if (hasDeliveredSessionResult) return
        hasDeliveredSessionResult = true
        val callback = activeFinalCallback
        activeFinalCallback = null
        activePartialCallback = null
        activeAudioCallback = null
        callback?.invoke(text)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "AlexaVoiceEngine"

        private val STOP_PHRASES = setOf(
            "stop",
            "stop listening",
            "cancel",
            "exit",
            "goodbye",
            "bye",
            "bye new era",
            "never mind",
            "shut up"
        )

        fun normalizeRmsDb(rmsdB: Float): Float {
            return ((rmsdB.coerceIn(-2f, 10f) + 2f) / 12f).coerceIn(0f, 1f)
        }

        fun computePcmRms(buffer: ShortArray, count: Int): Float {
            if (count <= 0) return 0f
            var sum = 0.0
            for (i in 0 until count) {
                val sample = buffer[i].toDouble()
                sum += sample * sample
            }
            val rms = sqrt(sum / count)
            // Normalize 16-bit PCM RMS (~0..10000) into 0..1 visual meter
            return (rms / 6500.0).toFloat().coerceIn(0f, 1f)
        }

        fun buildWavBytes(pcmData: ByteArray, sampleRate: Int = 16000): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val totalDataLen = pcmData.size + 36
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalDataLen)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // PCM chunk size
                putShort(1.toShort()) // AudioFormat = 1 (PCM)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort((channels * bitsPerSample / 8).toShort())
                putShort(bitsPerSample.toShort())
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(pcmData.size)
            }.array()
            return header + pcmData
        }

        fun isServiceFailureForAudioFallback(errorCode: Int): Boolean {
            return errorCode != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS &&
                errorCode != SpeechRecognizer.ERROR_AUDIO
        }

        fun isStopCommand(transcript: String): Boolean {
            val normalized = transcript.trim().lowercase(Locale.US)
                .replace(Regex("[^a-z\\s]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
            return normalized in STOP_PHRASES
        }

        fun sanitizeForSpeech(text: String): String {
            return text
                .replace(Regex("https?://\\S+"), "link")
                .replace(Regex("[*#`_]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(1500)
        }

        fun mapRecognizerError(errorCode: Int): String = when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Tap the orb or System Voice Input."
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out. Tap the orb when ready."
            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required for voice commands."
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech network error. Check connection or try System Voice Input."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer is busy. Please try again in a moment."
            SpeechRecognizer.ERROR_CLIENT -> "Voice client reset. Tap the orb to retry."
            SpeechRecognizer.ERROR_SERVER -> "Speech server unavailable. Try System Voice Input."
            else -> "Voice input error ($errorCode). Try System Voice Input."
        }
    }
}
