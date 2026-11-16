package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ai.engine.ComputeMode
import com.example.data.model.ChatMessage
import com.example.learning.DailySuggestion
import com.example.ui.components.AlexaVoiceOrb
import com.example.ui.components.VoiceListeningHudCard
import com.example.voice.VoiceCaptureMode
import com.example.voice.VoiceProsodyProfile
import java.util.Locale

@Composable
fun VoiceAiScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    isListening: Boolean,
    isSpeaking: Boolean,
    isTranscribing: Boolean = false,
    usingAudioFallback: Boolean = false,
    rmsLevel: Float,
    captureMode: VoiceCaptureMode,
    partialTranscript: String,
    voiceError: String?,
    voiceReplyEnabled: Boolean,
    handsFreeLoopEnabled: Boolean,
    computeMode: ComputeMode = ComputeMode.AUTO_DYNAMIC,
    edgePrivacyMode: Boolean = false,
    prosodyProfile: VoiceProsodyProfile = VoiceProsodyProfile.BALANCED,
    onSelectComputeMode: (ComputeMode) -> Unit = {},
    onToggleEdgePrivacy: (Boolean) -> Unit = {},
    onSelectProsody: (VoiceProsodyProfile) -> Unit = {},
    onStartVoice: () -> Unit,
    onStartDirectAudioVoice: () -> Unit = {},
    onStartDictation: ((String) -> Unit) -> Unit,
    onStopVoice: () -> Unit,
    onSendPartialNow: () -> Unit,
    onCancelVoice: () -> Unit,
    onStopSpeaking: () -> Unit,
    onToggleVoiceReply: (Boolean) -> Unit,
    onToggleHandsFreeLoop: (Boolean) -> Unit,
    onSendCommand: (String, Bitmap?) -> Unit,
    onSaveMessageToVault: (String, String) -> Unit,
    onClearVoiceError: () -> Unit,
    smartSuggestions: List<DailySuggestion> = emptyList()
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var attachedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingAudioAction by remember { mutableStateOf<VoiceCaptureMode?>(null) }
    var pendingDirectAudio by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val beginDictationFlow = {
        val prefix = inputText.trim()
        onStartDictation { dictated ->
            inputText = if (prefix.isNotBlank()) "$prefix $dictated" else dictated
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (pendingDirectAudio) {
                onStartDirectAudioVoice()
            } else if (pendingAudioAction == VoiceCaptureMode.DICTATION) {
                beginDictationFlow()
            } else {
                onStartVoice()
            }
        }
        pendingAudioAction = null
        pendingDirectAudio = false
    }

    val systemSpeechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (text.isNotBlank()) {
                onClearVoiceError()
                onSendCommand(text, attachedBitmap)
                attachedBitmap = null
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            attachedBitmap = loadScaledBitmap(context, uri)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    val requestAudioThenRun: (VoiceCaptureMode) -> Unit = { mode ->
        onClearVoiceError()
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            if (mode == VoiceCaptureMode.DICTATION) beginDictationFlow() else onStartVoice()
        } else {
            pendingAudioAction = mode
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val requestDirectAudioVoice: () -> Unit = {
        onClearVoiceError()
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            onStartDirectAudioVoice()
        } else {
            pendingDirectAudio = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val triggerOrbClick = {
        onClearVoiceError()
        when {
            isListening -> onSendPartialNow()
            isSpeaking -> onStopSpeaking()
            else -> requestAudioThenRun(VoiceCaptureMode.COMMAND)
        }
    }

    val launchSystemVoicePrompt = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to New Era")
        }
        runCatching { systemSpeechLauncher.launch(intent) }
            .onFailure { requestDirectAudioVoice() }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 800.dp)
                .fillMaxSize()
        ) {
            // Simple Voice Control Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            AlexaVoiceOrb(
                                isListening = isListening,
                                isSpeaking = isSpeaking,
                                isThinking = isGenerating,
                                onClick = triggerOrbClick,
                                orbSize = 54.dp,
                                rmsLevel = rmsLevel
                            )
                            Column {
                                Text(
                                    text = when {
                                        isTranscribing -> "Transcribing..."
                                        isListening && usingAudioFallback -> "Recording voice..."
                                        isListening && captureMode == VoiceCaptureMode.DICTATION -> "Dictating..."
                                        isListening -> "Listening..."
                                        isSpeaking -> "Speaking..."
                                        isGenerating -> "Thinking..."
                                        else -> "Tap mic to speak"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isListening && partialTranscript.isNotBlank()) {
                                        partialTranscript
                                    } else {
                                        "Voice commands, web search & phone control"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isSpeaking) {
                                IconButton(
                                    onClick = onStopSpeaking,
                                    modifier = Modifier.testTag("stop_tts_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Stop,
                                        contentDescription = "Stop speaking",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Icon(
                                imageVector = if (voiceReplyEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "Voice reply toggle",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = voiceReplyEnabled,
                                onCheckedChange = onToggleVoiceReply,
                                modifier = Modifier.testTag("voice_reply_switch")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = handsFreeLoopEnabled,
                            onClick = { onToggleHandsFreeLoop(!handsFreeLoopEnabled) },
                            label = {
                                Text(if (handsFreeLoopEnabled) "Hands-Free ON" else "Hands-Free")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Autorenew,
                                    contentDescription = "Toggle continuous voice loop",
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("hands_free_loop_switch")
                        )

                        FilterChip(
                            selected = edgePrivacyMode,
                            onClick = { onToggleEdgePrivacy(!edgePrivacyMode) },
                            label = { Text(if (edgePrivacyMode) "Edge Only" else "Hybrid Cloud") },
                            modifier = Modifier.testTag("edge_privacy_chip")
                        )

                        ComputeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = computeMode == mode,
                                onClick = { onSelectComputeMode(mode) },
                                label = { Text(mode.label) }
                            )
                        }

                        VoiceProsodyProfile.entries.forEach { prosody ->
                            FilterChip(
                                selected = prosodyProfile == prosody,
                                onClick = { onSelectProsody(prosody) },
                                label = { Text("Voice: ${prosody.label}") }
                            )
                        }

                        smartSuggestions.take(3).forEach { suggestion ->
                            AssistChip(
                                onClick = { onSendCommand(suggestion.actionPrompt, null) },
                                label = { Text(suggestion.title) }
                            )
                        }

                        listOf(
                            "Check battery & storage",
                            "List installed apps",
                            "Turn on flashlight",
                            "Set a 5 minute timer",
                            "Search latest tech news"
                        ).forEach { sample ->
                            AssistChip(
                                onClick = { onSendCommand(sample, null) },
                                label = { Text(sample) }
                            )
                        }
                    }

                    if (!voiceError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = voiceError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = { requestDirectAudioVoice() },
                                    modifier = Modifier.testTag("gemini_mic_fallback_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Mic,
                                        contentDescription = "Record voice with Gemini Mic",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Record Audio")
                                }
                                TextButton(
                                    onClick = { launchSystemVoicePrompt() },
                                    modifier = Modifier.testTag("system_voice_fallback_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.RecordVoiceOver,
                                        contentDescription = "Use system voice input",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("System Mic")
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Centered Luminous Violet Voice Stage (matching middle AI voice reference when active)
            AnimatedVisibility(visible = isListening || isTranscribing) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = if (isTranscribing) "Processing voice..." else "I'm listening...",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        AlexaVoiceOrb(
                            isListening = isListening,
                            isSpeaking = isSpeaking,
                            isThinking = isTranscribing,
                            onClick = triggerOrbClick,
                            orbSize = 108.dp,
                            rmsLevel = rmsLevel
                        )
                        if (partialTranscript.isNotBlank()) {
                            Text(
                                text = partialTranscript,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Live SpeechRecognizer / Audio HUD
            VoiceListeningHudCard(
                visible = isListening,
                isTranscribing = isTranscribing,
                usingAudioFallback = usingAudioFallback,
                captureMode = captureMode,
                handsFreeLoopEnabled = handsFreeLoopEnabled,
                rmsLevel = rmsLevel,
                partialTranscript = partialTranscript,
                onSendNow = {
                    if (captureMode == VoiceCaptureMode.DICTATION && !usingAudioFallback) onStopVoice()
                    else onSendPartialNow()
                },
                onCancel = onCancelVoice
            )

            // Messages List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatBubbleItem(
                        msg = msg,
                        onSave = { onSaveMessageToVault("New Era Answer", msg.text) }
                    )
                }
            }

            if (attachedBitmap != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bitmap = attachedBitmap!!.asImageBitmap(),
                        contentDescription = "Attached photo for AI analysis",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Image attached",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { attachedBitmap = null }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove attached image")
                    }
                }
            }

            // Input Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.testTag("attach_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = "Attach image from Photo Picker",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Ask New Era anything...") },
                        singleLine = false,
                        maxLines = 3,
                        shape = RoundedCornerShape(999.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("voice_chat_input")
                    )

                    IconButton(
                        onClick = {
                            if (isListening && captureMode == VoiceCaptureMode.DICTATION) {
                                onStopVoice()
                            } else {
                                requestAudioThenRun(VoiceCaptureMode.DICTATION)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("dictate_mic_button")
                    ) {
                        Icon(
                            imageVector = if (isListening && captureMode == VoiceCaptureMode.DICTATION) {
                                Icons.Filled.Stop
                            } else {
                                Icons.Filled.Mic
                            },
                            contentDescription = "Dictate voice-to-text into message box",
                            tint = if (isListening && captureMode == VoiceCaptureMode.DICTATION) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }

                    FilledIconButton(
                        onClick = {
                            val prompt = inputText
                            val img = attachedBitmap
                            inputText = ""
                            attachedBitmap = null
                            onSendCommand(prompt, img)
                        },
                        enabled = !isGenerating && (inputText.isNotBlank() || attachedBitmap != null),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("send_chat_button")
                    ) {
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send command"
                            )
                        }
                    }
                }
            }
        }
    }
}
