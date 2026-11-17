package com.example.ui

import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ai.AiCreativeMediaService
import com.example.ai.GeneratedImageOutput
import com.example.ai.ImageEditAction
import com.example.ai.ImageEngineProfile
import com.example.ai.ImageQualityModel
import com.example.ai.NewEraAiService
import com.example.ai.VideoEngineProfile
import com.example.ai.VideoStylePreset
import com.example.ai.engine.AgenticCredentialVault
import com.example.ai.engine.AutonomousArchitectService
import com.example.ai.engine.AutonomousBlueprint
import com.example.ai.engine.ComputeMode
import com.example.ai.engine.GatewayChannel
import com.example.ai.engine.GraphMemoryState
import com.example.ai.engine.HierarchicalMemoryGraphStore
import com.example.ai.engine.MemoryNodeType
import com.example.ai.engine.ScopedEphemeralToken
import com.example.auth.UserDirectorySnapshot
import com.example.auth.UserSessionManager
import com.example.crypto.E2eeCryptoManager
import com.example.data.model.AiToolKind
import com.example.data.model.ChatMessage
import com.example.data.model.DecryptedVaultItem
import com.example.data.model.DeviceTelemetry
import com.example.data.model.EncryptedRecord
import com.example.data.model.InstalledAppInfo
import com.example.data.model.PersonalWebsite
import com.example.data.model.UserProfile
import com.example.data.model.VaultBundle
import com.example.data.repository.NewEraRepository
import com.example.device.DeviceController
import com.example.googleapps.GoogleAppIntegration
import com.example.googleapps.GoogleAppsManager
import com.example.googleapps.GoogleOAuthSession
import com.example.googleapps.GoogleWorkspaceClient
import com.example.learning.DailyLearningEngine
import com.example.learning.DailyLearningState
import com.example.learning.DailyLearningStore
import com.example.learning.DailyLearningWorker
import com.example.video.EnhancedVideoRenderer
import com.example.video.GeneratedVideoResult
import com.example.video.VideoColorGrade
import com.example.video.VideoMotionStyle
import com.example.video.VideoQualityPreset
import com.example.voice.AlexaVoiceEngine
import com.example.voice.VoiceCaptureMode
import com.example.voice.VoiceProsodyProfile
import com.example.web.PersonalWebsiteServer
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import javax.crypto.SecretKey

sealed interface SyncUiState<out T> {
    data object Loading : SyncUiState<Nothing>
    data class Success<T>(val data: T) : SyncUiState<T>
    data class Error(val message: String) : SyncUiState<Nothing>
}

sealed interface ToolResultState {
    data object Idle : ToolResultState
    data class Loading(
        val statusText: String = "New Era AI is synthesizing...",
        val progress: Float? = null
    ) : ToolResultState
    data class TextSuccess(val tool: AiToolKind, val output: String) : ToolResultState
    data class ImageSuccess(val output: GeneratedImageOutput) : ToolResultState
    data class VideoSuccess(val video: GeneratedVideoResult) : ToolResultState
    data class VisionSuccess(val analysis: String) : ToolResultState
    data class BlueprintSuccess(val blueprint: AutonomousBlueprint) : ToolResultState
    data class Error(val message: String) : ToolResultState
}

class MainViewModel(
    private val currentUserId: String,
    private val currentUsername: String,
    val isOwnerSession: Boolean,
    private val authMethod: String = "google",
    private val repository: NewEraRepository,
    private val cryptoManager: E2eeCryptoManager,
    private val aiService: NewEraAiService,
    private val creativeMediaService: AiCreativeMediaService,
    private val architectService: AutonomousArchitectService,
    private val memoryGraphStore: HierarchicalMemoryGraphStore,
    private val agenticVault: AgenticCredentialVault,
    private val userSessionManager: UserSessionManager,
    private val deviceController: DeviceController,
    private val googleAppsManager: GoogleAppsManager,
    private val googleWorkspaceClient: GoogleWorkspaceClient,
    private val learningStore: DailyLearningStore,
    private val learningEngine: DailyLearningEngine,
    val voiceEngine: AlexaVoiceEngine,
    val websiteServer: PersonalWebsiteServer
) : ViewModel() {

    private val _masterKey = MutableStateFlow<SecretKey?>(null)
    private val _keyFingerprint = MutableStateFlow("")
    val keyFingerprint: StateFlow<String> = _keyFingerprint.asStateFlow()

    private val _needsPassphraseUnlock = MutableStateFlow(false)
    val needsPassphraseUnlock: StateFlow<Boolean> = _needsPassphraseUnlock.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _telemetry = MutableStateFlow(DeviceTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _flashlightOn = MutableStateFlow(false)
    val flashlightOn: StateFlow<Boolean> = _flashlightOn.asStateFlow()

    private val _chatMessages = MutableStateFlow(
        listOf(
            ChatMessage(
                id = "welcome_new_era",
                isUser = false,
                text = "Welcome back, $currentUsername. I am New Era — equipped with System 2 Tree-of-Thoughts reasoning, Hybrid Edge-Cloud routing, Hierarchical Graph Memory, 14 Image/Video engines, and Zero-Config Vault security. Tap the orb or type any command."
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isChatGenerating = MutableStateFlow(false)
    val isChatGenerating: StateFlow<Boolean> = _isChatGenerating.asStateFlow()

    private val _handsFreeLoop = MutableStateFlow(false)
    val handsFreeLoop: StateFlow<Boolean> = _handsFreeLoop.asStateFlow()

    private val _computeMode = MutableStateFlow(ComputeMode.AUTO_DYNAMIC)
    val computeMode: StateFlow<ComputeMode> = _computeMode.asStateFlow()

    private val _edgePrivacyMode = MutableStateFlow(false)
    val edgePrivacyMode: StateFlow<Boolean> = _edgePrivacyMode.asStateFlow()

    private val _toolResultState = MutableStateFlow<ToolResultState>(ToolResultState.Idle)
    val toolResultState: StateFlow<ToolResultState> = _toolResultState.asStateFlow()

    val graphMemoryState: StateFlow<GraphMemoryState> = memoryGraphStore.stateFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = GraphMemoryState()
        )

    val gatewayTokensState: StateFlow<List<ScopedEphemeralToken>> = agenticVault.tokensFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    val userDirectoryState: StateFlow<UserDirectorySnapshot> = userSessionManager.directoryFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = UserDirectorySnapshot()
        )

    val dailyLearningState: StateFlow<DailyLearningState> = learningStore.stateFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = DailyLearningState()
        )

    private val _isLearningNow = MutableStateFlow(false)
    val isLearningNow: StateFlow<Boolean> = _isLearningNow.asStateFlow()

    val permittedGoogleApps: StateFlow<Set<GoogleAppIntegration>> = googleAppsManager.permittedAppsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = GoogleAppIntegration.entries.toSet()
        )

    val googleOAuthSession: StateFlow<GoogleOAuthSession> = googleAppsManager.oauthSession

    private val _pendingAuthIntentSender = MutableStateFlow<IntentSender?>(null)
    val pendingAuthIntentSender: StateFlow<IntentSender?> = _pendingAuthIntentSender.asStateFlow()

    private val _googleAppPreviewResult = MutableStateFlow<String?>(null)
    val googleAppPreviewResult: StateFlow<String?> = _googleAppPreviewResult.asStateFlow()

    val userProfileState: StateFlow<SyncUiState<UserProfile?>> =
        repository.observeUserProfile(currentUserId)
            .map<UserProfile?, SyncUiState<UserProfile?>> { SyncUiState.Success(it) }
            .catch { error ->
                val root = unwrapFirestoreError(error) ?: error
                if (root is CancellationException) throw root
                emit(SyncUiState.Error(root.message ?: "Failed to sync profile"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = SyncUiState.Loading
            )

    val websiteState: StateFlow<SyncUiState<PersonalWebsite?>> =
        repository.observeWebsitePage(currentUserId)
            .map<PersonalWebsite?, SyncUiState<PersonalWebsite?>> { SyncUiState.Success(it) }
            .catch { error ->
                val root = unwrapFirestoreError(error) ?: error
                if (root is CancellationException) throw root
                emit(SyncUiState.Error(root.message ?: "Failed to sync website"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = SyncUiState.Loading
            )

    val vaultBundleState: StateFlow<VaultBundle?> =
        repository.observeVaultBundle(currentUserId)
            .catch { /* Handled softly */ }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = null
            )

    val decryptedRecordsState: StateFlow<SyncUiState<List<DecryptedVaultItem>>> =
        combine(
            repository.observeEncryptedRecords(currentUserId),
            _masterKey
        ) { records, key ->
            val items = records.map { rec -> decryptRecord(rec, key) }
                .sortedByDescending { it.timestampMillis }
            SyncUiState.Success(items) as SyncUiState<List<DecryptedVaultItem>>
        }
            .catch { error ->
                val root = unwrapFirestoreError(error) ?: error
                if (root is CancellationException) throw root
                emit(SyncUiState.Error(root.message ?: "Failed to sync encrypted vault"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = SyncUiState.Loading
            )

    init {
        initializeAutomatedSystem()
    }

    private fun initializeAutomatedSystem() {
        viewModelScope.launch {
            try {
                // 1. Refresh device telemetry & installed apps
                refreshDeviceAndApps()

                // 2. Automated E2EE Key setup or multi-device check
                val hasLocal = cryptoManager.hasLocalMasterKey(currentUserId)
                val cloudBundle = try {
                    repository.getVaultBundleOnce()
                } catch (_: Exception) {
                    null
                }

                val activeKey = if (hasLocal) {
                    cryptoManager.getOrInitLocalMasterKey(currentUserId)
                } else if (cloudBundle != null) {
                    // New device detected with existing cloud passphrase-wrapped bundle
                    _needsPassphraseUnlock.value = true
                    cryptoManager.getOrInitLocalMasterKey(currentUserId)
                } else {
                    cryptoManager.getOrInitLocalMasterKey(currentUserId)
                }

                _masterKey.value = activeKey
                val fp = cryptoManager.getKeyFingerprint(activeKey)
                _keyFingerprint.value = fp

                // 3. Provision profile + owner-tagged personal website automatically
                val defaultHtml = PersonalWebsiteServer.buildWebsiteHtml(
                    website = PersonalWebsite(userId = currentUserId),
                    telemetry = _telemetry.value,
                    pinnedItems = emptyList(),
                    keyFingerprint = fp
                )
                repository.ensureAutomatedSetup(fp, defaultHtml)
                websiteServer.updateHtml(defaultHtml)
                websiteServer.start(viewModelScope)

                // Auto-learn today's new internet insight if enabled & not yet learned today
                runCatching { learningEngine.ensureLearnedTodayIfEnabled() }

                // Zero-Config Just-in-Time Ephemeral Gateway Token Provisioning
                runCatching { agenticVault.ensureJustInTimeProvisioned(currentUserId) }

                // If Owner, sync any cloud directory records when Firebase Google signed in
                if (isOwnerSession) {
                    refreshOwnerDirectory()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Automated setup error", e)
                _statusBanner.value = "Sync note: ${e.localizedMessage ?: "Check connection"}"
            }
        }

        // Keep local web server HTML continuously updated with synced website & pinned items
        viewModelScope.launch {
            combine(websiteState, decryptedRecordsState, _telemetry, _keyFingerprint) { siteRes, recRes, tel, fp ->
                val site = (siteRes as? SyncUiState.Success)?.data ?: PersonalWebsite(userId = currentUserId)
                val pinned = (recRes as? SyncUiState.Success)?.data?.filter { it.pinned && it.isDecrypted } ?: emptyList()
                PersonalWebsiteServer.buildWebsiteHtml(site, tel, pinned, fp)
            }.collect { html ->
                websiteServer.updateHtml(html)
            }
        }

        // Feed accumulated daily internet learning into New Era's AI context continuously
        viewModelScope.launch {
            learningStore.stateFlow.collect { learning ->
                aiService.updateLearnedMemoryContext(
                    DailyLearningStore.buildMemoryPromptContext(learning)
                )
            }
        }
    }

    fun refreshDeviceAndApps() {
        viewModelScope.launch {
            _telemetry.value = deviceController.getDeviceTelemetry()
            _installedApps.value = deviceController.getInstalledApps()
        }
    }

    fun sendVoiceOrTextCommand(prompt: String, attachedImage: Bitmap? = null) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank() && attachedImage == null) return

        voiceEngine.cancelListening()
        val effectivePrompt = trimmed.ifBlank { "Analyze this image for Sanskar." }
        val userMsg = ChatMessage(
            id = "u_${UUID.randomUUID()}",
            isUser = true,
            text = if (attachedImage != null) "[Image Attached] $effectivePrompt" else effectivePrompt
        )
        val placeholderId = "a_${UUID.randomUUID()}"
        _chatMessages.value = _chatMessages.value + userMsg + ChatMessage(
            id = placeholderId,
            isUser = false,
            text = "Thinking with New Era...",
            isStreaming = true
        )
        _isChatGenerating.value = true

        viewModelScope.launch {
            try {
                val learningSnapshot = runCatching { learningStore.getState() }.getOrNull()
                val dailyContext = learningSnapshot?.let {
                    DailyLearningStore.buildRelevantContextForQuery(it, effectivePrompt)
                }.orEmpty()
                val graphSnapshot = runCatching { memoryGraphStore.getState() }.getOrNull()
                val graphContext = graphSnapshot?.let {
                    HierarchicalMemoryGraphStore.retrieveSubgraphContext(it, effectivePrompt)
                }.orEmpty()
                val combinedMemory = listOf(dailyContext, graphContext)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")

                val aiResult = aiService.askAssistant(
                    userPrompt = effectivePrompt,
                    attachedImage = attachedImage,
                    queryMemoryContext = combinedMemory,
                    computeMode = _computeMode.value,
                    edgePrivacyMode = _edgePrivacyMode.value
                )
                runCatching { learningStore.recordQuestionTopic(effectivePrompt) }
                runCatching { memoryGraphStore.ingestInteraction(effectivePrompt, aiResult.text) }
                logUserActivity("AI_ASSISTANT", effectivePrompt.take(120))

                _chatMessages.value = _chatMessages.value.map { msg ->
                    if (msg.id == placeholderId) {
                        msg.copy(
                            text = aiResult.text,
                            toolsUsed = aiResult.toolsUsed,
                            isStreaming = false,
                            reasoningTrace = aiResult.reasoningTrace
                        )
                    } else msg
                }

                // Speak response like Alexa if voice reply is enabled, then auto-listen in Hands-Free Loop
                val voiceEnabled = (userProfileState.value as? SyncUiState.Success)?.data?.voiceReplyEnabled ?: true
                if (voiceEnabled) {
                    voiceEngine.speak(aiResult.text) {
                        if (_handsFreeLoop.value && !_isChatGenerating.value) {
                            startVoiceListening()
                        }
                    }
                }

                // Refresh device telemetry in case an AI tool toggled flashlight or settings
                refreshDeviceAndApps()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errText = e.localizedMessage ?: "New Era encountered an error."
                _chatMessages.value = _chatMessages.value.map { msg ->
                    if (msg.id == placeholderId) {
                        msg.copy(
                            text = "Error: $errText",
                            isStreaming = false
                        )
                    } else msg
                }
            } finally {
                _isChatGenerating.value = false
            }
        }
    }

    fun startVoiceListening(preferDirectAudioCapture: Boolean = false) {
        voiceEngine.startListening(
            mode = VoiceCaptureMode.COMMAND,
            preferDirectAudioCapture = preferDirectAudioCapture,
            onAudioCaptured = { wavBytes ->
                transcribeCapturedAudio(wavBytes) { transcript ->
                    handleVoiceCommandTranscript(transcript)
                }
            },
            onFinalResult = { transcript ->
                handleVoiceCommandTranscript(transcript)
            }
        )
    }

    private fun handleVoiceCommandTranscript(transcript: String) {
        if (AlexaVoiceEngine.isStopCommand(transcript)) {
            _handsFreeLoop.value = false
            voiceEngine.stopSpeaking()
            _statusBanner.value = "Alexa voice session paused."
        } else {
            sendVoiceOrTextCommand(transcript)
        }
    }

    fun startVoiceDictation(onTranscriptUpdate: (String) -> Unit) {
        voiceEngine.startListening(
            mode = VoiceCaptureMode.DICTATION,
            onPartial = { partial -> onTranscriptUpdate(partial) },
            onAudioCaptured = { wavBytes ->
                transcribeCapturedAudio(wavBytes) { transcript ->
                    onTranscriptUpdate(transcript)
                }
            },
            onFinalResult = { finalText -> onTranscriptUpdate(finalText) }
        )
    }

    private fun transcribeCapturedAudio(wavBytes: ByteArray, onTranscribed: (String) -> Unit) {
        voiceEngine.setTranscribing(true)
        viewModelScope.launch {
            try {
                val transcript = aiService.transcribeAudio(wavBytes).trim()
                if (transcript.isBlank()) {
                    voiceEngine.setVoiceError("No spoken words detected in audio. Tap the orb and speak clearly.")
                } else {
                    onTranscribed(transcript)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                voiceEngine.setVoiceError(e.localizedMessage ?: "Audio transcription failed.")
            } finally {
                voiceEngine.setTranscribing(false)
            }
        }
    }

    fun stopVoiceListening() {
        voiceEngine.stopListening()
    }

    fun sendPartialVoiceNow() {
        voiceEngine.sendCurrentPartialNow()
    }

    fun cancelVoiceListening() {
        _handsFreeLoop.value = false
        voiceEngine.cancelListening()
    }

    fun toggleHandsFreeLoop(enabled: Boolean) {
        _handsFreeLoop.value = enabled
        _statusBanner.value = if (enabled) {
            "Hands-Free Alexa Loop enabled — New Era will keep listening after replying."
        } else {
            "Hands-Free Alexa Loop disabled."
        }
    }

    fun toggleVoiceReply(enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (!enabled) {
                    voiceEngine.stopSpeaking()
                    _handsFreeLoop.value = false
                }
                repository.updateVoiceReplyEnabled(enabled)
            } catch (e: Exception) {
                _statusBanner.value = e.localizedMessage
            }
        }
    }

    fun runAiTool(tool: AiToolKind, input: String, attachedImage: Bitmap? = null) {
        if (input.isBlank()) return
        _toolResultState.value = ToolResultState.Loading("Running ${tool.title}...")
        viewModelScope.launch {
            try {
                val output = aiService.runAiTool(tool, input, attachedImage)
                logUserActivity("AI_TOOL_${tool.name}", input.take(100))
                _toolResultState.value = ToolResultState.TextSuccess(tool, output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toolResultState.value = ToolResultState.Error(e.localizedMessage ?: "Tool failed.")
            }
        }
    }

    fun generateAiImage(
        prompt: String,
        aspectRatio: AspectRatio,
        imageSize: ImageSize = ImageSize.SIZE_2K,
        engineProfile: ImageEngineProfile = ImageEngineProfile.NANO_BANANA_2_2_1,
        editAction: ImageEditAction = ImageEditAction.CUSTOM,
        enhancePrompt: Boolean = true,
        referenceImage: Bitmap? = null
    ) {
        if (prompt.isBlank()) return
        _toolResultState.value = ToolResultState.Loading(
            statusText = if (referenceImage != null) {
                "${engineProfile.aliasName}: Editing photo..."
            } else if (enhancePrompt) {
                "${engineProfile.aliasName}: Enhancing & rendering..."
            } else {
                "${engineProfile.aliasName}: Rendering image..."
            }
        )
        viewModelScope.launch {
            try {
                val output = creativeMediaService.generateAdvancedImage(
                    prompt = prompt,
                    aspectRatio = aspectRatio,
                    imageSize = imageSize,
                    engineProfile = engineProfile,
                    editAction = editAction,
                    enhancePrompt = enhancePrompt,
                    referenceImage = referenceImage
                )
                logUserActivity("IMAGE_STUDIO", "${engineProfile.displayName}: ${prompt.take(90)}")
                _toolResultState.value = ToolResultState.ImageSuccess(output)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toolResultState.value = ToolResultState.Error(e.localizedMessage ?: "Image generation failed.")
            }
        }
    }

    fun generateEnhancedVideo(
        prompt: String,
        aspectRatio: AspectRatio = AspectRatio.LANDSCAPE_16x9,
        qualityPreset: VideoQualityPreset = VideoQualityPreset.FULL_HD_1080P,
        stylePreset: VideoStylePreset = VideoStylePreset.CINEMATIC,
        videoEngine: VideoEngineProfile = VideoEngineProfile.GOOGLE_GEMINI_OMNI_VEO_3_1,
        referenceImage: Bitmap? = null,
        secondsPerScene: Int = 3,
        forcedMotion: VideoMotionStyle? = null,
        colorGrade: VideoColorGrade = VideoColorGrade.NATURAL,
        showCaptions: Boolean = true
    ) {
        if (prompt.isBlank()) return
        _toolResultState.value = ToolResultState.Loading("Initializing ${videoEngine.aliasName}...", 0.04f)
        viewModelScope.launch {
            try {
                val videoResult = creativeMediaService.generateEnhancedVideo(
                    prompt = prompt,
                    aspectRatio = aspectRatio,
                    qualityPreset = qualityPreset,
                    stylePreset = stylePreset,
                    videoEngine = videoEngine,
                    referenceImage = referenceImage,
                    secondsPerScene = secondsPerScene,
                    forcedMotion = forcedMotion,
                    colorGrade = colorGrade,
                    showCaptions = showCaptions,
                    onStageUpdate = { stage, pct ->
                        _toolResultState.value = ToolResultState.Loading(stage, pct)
                    }
                )
                logUserActivity("VIDEO_STUDIO", "${videoEngine.displayName}: ${prompt.take(90)}")
                _toolResultState.value = ToolResultState.VideoSuccess(videoResult)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toolResultState.value = ToolResultState.Error(e.localizedMessage ?: "Video generation failed.")
            }
        }
    }

    fun synthesizeAutonomousArchitecture(
        goalPrompt: String,
        overrideInstruction: String? = null
    ) {
        if (goalPrompt.isBlank()) return
        _toolResultState.value = ToolResultState.Loading(
            "Zero-Shot Architect: Inferring stack, DB schema, UI tokens & edge cases..."
        )
        viewModelScope.launch {
            try {
                val bp = architectService.synthesizeZeroShotBlueprint(goalPrompt, overrideInstruction)
                runCatching {
                    memoryGraphStore.addOrReinforceNode(
                        nodeType = MemoryNodeType.PROJECT_STATE,
                        label = bp.projectName,
                        summary = "${bp.chosenStack} | ${bp.stackRationale}".take(240),
                        tags = HierarchicalMemoryGraphStore.extractKeywords(goalPrompt)
                    )
                }
                logUserActivity("ZERO_SHOT_ARCHITECT", "${bp.projectName}: ${goalPrompt.take(80)}")
                _toolResultState.value = ToolResultState.BlueprintSuccess(bp)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toolResultState.value = ToolResultState.Error(e.localizedMessage ?: "Autonomous synthesis failed.")
            }
        }
    }

    fun setComputeMode(mode: ComputeMode) {
        _computeMode.value = mode
        _statusBanner.value = "Compute Mode: ${mode.label}"
    }

    fun setEdgePrivacyMode(enabled: Boolean) {
        _edgePrivacyMode.value = enabled
        _statusBanner.value = if (enabled) {
            "Edge Privacy Enclave ON — local compute & graph memory only."
        } else {
            "Hybrid Edge-Cloud Router active."
        }
    }

    fun setVoiceProsody(profile: VoiceProsodyProfile) {
        voiceEngine.setProsodyProfile(profile)
        _statusBanner.value = "Voice flow set to ${profile.label}"
    }

    fun addGraphMemoryNode(type: MemoryNodeType, label: String, summary: String) {
        viewModelScope.launch {
            runCatching {
                memoryGraphStore.addOrReinforceNode(type, label, summary)
                logUserActivity("MEMORY_NODE", "Added ${type.label}: ${label.take(60)}")
                _statusBanner.value = "Added '${label.take(30)}' to Graph Memory"
            }.onFailure {
                _statusBanner.value = it.localizedMessage ?: "Failed to save memory node"
            }
        }
    }

    fun deleteGraphMemoryNode(nodeId: String) {
        viewModelScope.launch {
            memoryGraphStore.deleteNode(nodeId)
        }
    }

    fun rotateGatewayToken(channel: GatewayChannel) {
        viewModelScope.launch {
            runCatching {
                val fresh = agenticVault.rotateChannelToken(currentUserId, channel)
                logUserActivity("GATEWAY_ROTATE", "Rotated ephemeral token for ${channel.title}")
                _statusBanner.value = "Rotated ${channel.title} (${fresh.maskedToken})"
            }.onFailure {
                _statusBanner.value = it.localizedMessage ?: "Token rotation failed"
            }
        }
    }

    fun revokeGatewayToken(channel: GatewayChannel) {
        viewModelScope.launch {
            agenticVault.revokeChannelToken(channel)
            logUserActivity("GATEWAY_REVOKE", "Revoked ${channel.title}")
            _statusBanner.value = "Revoked ${channel.title} delegation"
        }
    }

    fun refreshOwnerDirectory() {
        if (!isOwnerSession) return
        viewModelScope.launch {
            runCatching {
                val (cloudUsers, cloudActivities) = repository.fetchCloudDirectoryForOwner()
                if (cloudUsers.isNotEmpty() || cloudActivities.isNotEmpty()) {
                    userSessionManager.mergeCloudDirectory(cloudUsers, cloudActivities)
                }
            }
        }
    }

    private fun logUserActivity(actionType: String, summary: String) {
        viewModelScope.launch {
            runCatching {
                userSessionManager.recordUserActivity(
                    uid = currentUserId,
                    username = currentUsername,
                    actionType = actionType,
                    summary = summary,
                    authMethod = authMethod,
                    isOwner = isOwnerSession
                )
                val snap = userSessionManager.getDirectory()
                val me = snap.users.firstOrNull { it.uid == currentUserId }
                repository.syncCloudUserActivity(
                    username = currentUsername,
                    authMethod = authMethod,
                    actionType = actionType,
                    summary = summary,
                    activityCount = me?.activityCount ?: 1
                )
            }
        }
    }

    fun analyzeVision(bitmap: Bitmap, prompt: String) {
        _toolResultState.value = ToolResultState.Loading("Analyzing image with Vision AI...")
        viewModelScope.launch {
            try {
                val analysis = aiService.analyzeVisionImage(bitmap, prompt)
                logUserActivity("VISION_LAB", prompt.ifBlank { "Analyzed photo" }.take(90))
                _toolResultState.value = ToolResultState.VisionSuccess(analysis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _toolResultState.value = ToolResultState.Error(e.localizedMessage ?: "Vision analysis failed.")
            }
        }
    }

    fun learnNewThingToday(customTopic: String? = null) {
        if (_isLearningNow.value) return
        _isLearningNow.value = true
        viewModelScope.launch {
            try {
                val entry = learningEngine.learnNewTopicFromInternet(customTopic)
                saveEncryptedVaultNote(
                    recordType = "tool_output",
                    title = "Day ${entry.dayNumber}: ${entry.headline}",
                    content = "${entry.keyTakeaway}\n\n${entry.deepSummary}",
                    pinned = false
                )
                logUserActivity("DAILY_LEARNING", "Day ${entry.dayNumber}: ${entry.headline}")
                _statusBanner.value = "Learned Day ${entry.dayNumber}: ${entry.headline}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBanner.value = "Daily learning error: ${e.localizedMessage ?: "Check internet"}"
            } finally {
                _isLearningNow.value = false
            }
        }
    }

    fun toggleAutoLearnDaily(enabled: Boolean) {
        viewModelScope.launch {
            learningStore.setAutoLearnDaily(enabled)
        }
    }

    fun toggleGoogleAppPermission(app: GoogleAppIntegration, allowed: Boolean) {
        viewModelScope.launch {
            googleAppsManager.setAppPermission(app, allowed)
            _statusBanner.value = if (allowed) {
                "${app.displayName} permission enabled. Tap Authorize to sync OAuth token."
            } else {
                "${app.displayName} access revoked by user."
            }
        }
    }

    fun toggleAllGoogleAppPermissions(allowed: Boolean) {
        viewModelScope.launch {
            googleAppsManager.setAllPermissions(allowed)
        }
    }

    fun authorizeSelectedGoogleApps() {
        viewModelScope.launch {
            try {
                val result = googleAppsManager.requestWorkspaceAuthorization()
                if (result.hasResolution()) {
                    _pendingAuthIntentSender.value = result.pendingIntent?.intentSender
                } else if (!result.accessToken.isNullOrBlank()) {
                    _statusBanner.value = "Google Apps authorized (${permittedGoogleApps.value.size} apps enabled)."
                } else {
                    _statusBanner.value = "Authorization completed without access token."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBanner.value = e.localizedMessage ?: "Google Apps authorization failed."
            }
        }
    }

    fun onGoogleAuthIntentCompleted(data: Intent?) {
        _pendingAuthIntentSender.value = null
        try {
            val session = googleAppsManager.handleAuthorizationActivityResult(data)
            if (session.isAuthorized) {
                _statusBanner.value = "Google Apps connected (${permittedGoogleApps.value.size} permitted apps)."
            } else {
                _statusBanner.value = "Google Apps authorization was not granted."
            }
        } catch (e: Exception) {
            _statusBanner.value = e.localizedMessage ?: "Google authorization failed."
        }
    }

    fun clearPendingAuthIntent() {
        _pendingAuthIntentSender.value = null
    }

    fun fetchGoogleAppPreview(app: GoogleAppIntegration) {
        viewModelScope.launch {
            _googleAppPreviewResult.value = "Fetching ${app.displayName}..."
            _googleAppPreviewResult.value = googleWorkspaceClient.listAppItems(app)
        }
    }

    fun clearGoogleAppPreview() {
        _googleAppPreviewResult.value = null
    }

    fun saveEncryptedVaultNote(
        recordType: String,
        title: String,
        content: String,
        pinned: Boolean = true
    ) {
        viewModelScope.launch {
            try {
                val key = _masterKey.value ?: cryptoManager.getOrInitLocalMasterKey(currentUserId)
                val payloadJson = JSONObject().apply {
                    put("title", title.ifBlank { "New Era Insight" })
                    put("content", content)
                    put("owner", "Sanskar")
                    put("ts", System.currentTimeMillis())
                }.toString()
                val encrypted = cryptoManager.encrypt(payloadJson, key)
                repository.saveEncryptedRecord(
                    recordType = recordType,
                    ciphertext = encrypted.ciphertextBase64,
                    iv = encrypted.ivBase64,
                    pinned = pinned
                )
                logUserActivity("VAULT_SAVE", "${recordType}: ${title.ifBlank { "New Era Insight" }.take(80)}")
                _statusBanner.value = "Encrypted with AES-256-GCM & synced across devices."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBanner.value = "Vault save failed: ${e.localizedMessage}"
            }
        }
    }

    fun toggleRecordPin(recordId: String, pinned: Boolean) {
        viewModelScope.launch {
            try {
                repository.togglePinRecord(recordId, pinned)
            } catch (e: Exception) {
                _statusBanner.value = e.localizedMessage
            }
        }
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            try {
                repository.deleteEncryptedRecord(recordId)
            } catch (e: Exception) {
                _statusBanner.value = e.localizedMessage
            }
        }
    }

    fun saveWebsiteConfig(
        siteTitle: String,
        headline: String,
        bio: String,
        accentStyle: String,
        showTelemetry: Boolean,
        showAiPortal: Boolean
    ) {
        viewModelScope.launch {
            try {
                val currentPinned = (decryptedRecordsState.value as? SyncUiState.Success)
                    ?.data?.filter { it.pinned && it.isDecrypted } ?: emptyList()
                val updated = PersonalWebsite(
                    userId = currentUserId,
                    ownerTag = "Owner: Sanskar",
                    siteTitle = siteTitle.ifBlank { "New Era — Sanskar" },
                    headline = headline.ifBlank { "Welcome to Sanskar's AI Hub" },
                    bio = bio,
                    accentStyle = accentStyle,
                    showTelemetry = showTelemetry,
                    showAiPortal = showAiPortal,
                    publishedHtml = ""
                )
                val html = PersonalWebsiteServer.buildWebsiteHtml(
                    website = updated,
                    telemetry = _telemetry.value,
                    pinnedItems = currentPinned,
                    keyFingerprint = _keyFingerprint.value
                )
                repository.saveWebsitePage(updated.copy(publishedHtml = html))
                websiteServer.updateHtml(html)
                _statusBanner.value = "Personal website saved & synced (Owner: Sanskar)."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBanner.value = "Website update failed: ${e.localizedMessage}"
            }
        }
    }

    fun toggleLocalServer(enable: Boolean) {
        if (enable) {
            websiteServer.start(viewModelScope)
            _statusBanner.value = "Local web portal live at ${websiteServer.localUrl}"
        } else {
            websiteServer.stop()
            _statusBanner.value = "Local web portal paused"
        }
    }

    fun setSyncPassphrase(passphrase: String) {
        viewModelScope.launch {
            try {
                val key = _masterKey.value ?: cryptoManager.getOrInitLocalMasterKey(currentUserId)
                val bundle = cryptoManager.wrapMasterKeyWithPassphrase(key, passphrase)
                repository.saveVaultBundle(
                    saltBase64 = bundle.saltBase64,
                    wrappedKeyBase64 = bundle.wrappedKeyBase64,
                    ivBase64 = bundle.ivBase64,
                    keyFingerprint = bundle.fingerprint
                )
                repository.updateKeyFingerprint(bundle.fingerprint)
                _needsPassphraseUnlock.value = false
                _statusBanner.value = "Cross-device E2EE Passphrase Backup synced."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBanner.value = e.localizedMessage ?: "Failed to save sync passphrase."
            }
        }
    }

    fun unlockWithPassphrase(passphrase: String) {
        viewModelScope.launch {
            try {
                val bundle = vaultBundleState.value ?: repository.getVaultBundleOnce()
                    ?: throw IllegalStateException("No cloud E2EE key backup found yet.")
                val unwrapped = cryptoManager.unwrapMasterKeyWithPassphrase(
                    saltBase64 = bundle.saltBase64,
                    wrappedKeyBase64 = bundle.wrappedKeyBase64,
                    ivBase64 = bundle.ivBase64,
                    passphrase = passphrase
                )
                cryptoManager.saveMasterKey(currentUserId, unwrapped)
                _masterKey.value = unwrapped
                _keyFingerprint.value = cryptoManager.getKeyFingerprint(unwrapped)
                _needsPassphraseUnlock.value = false
                _statusBanner.value = "All synced devices unlocked with matching E2EE key!"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _statusBanner.value = "Incorrect passphrase or incompatible key."
            }
        }
    }

    fun importRecoveryKey(code: String) {
        viewModelScope.launch {
            try {
                val key = cryptoManager.importRecoveryCode(code)
                cryptoManager.saveMasterKey(currentUserId, key)
                _masterKey.value = key
                _keyFingerprint.value = cryptoManager.getKeyFingerprint(key)
                _needsPassphraseUnlock.value = false
                _statusBanner.value = "E2EE Recovery Key imported and active."
            } catch (e: Exception) {
                _statusBanner.value = e.localizedMessage ?: "Invalid recovery key."
            }
        }
    }

    fun exportCurrentRecoveryKey(): String {
        val key = _masterKey.value ?: return ""
        return cryptoManager.exportRecoveryCode(key)
    }

    fun launchApp(packageName: String) {
        deviceController.launchPackage(packageName)
            .onFailure { _statusBanner.value = it.localizedMessage }
    }

    fun toggleFlashlight() {
        val target = !_flashlightOn.value
        deviceController.toggleFlashlight(target)
            .onSuccess {
                _flashlightOn.value = target
                _statusBanner.value = it
            }
            .onFailure { _statusBanner.value = it.localizedMessage }
    }

    fun openSettings(section: String) {
        deviceController.openSystemSettings(section)
            .onFailure { _statusBanner.value = it.localizedMessage }
    }

    fun startQuickTimer(seconds: Int, label: String) {
        deviceController.setTimer(seconds, label)
            .onSuccess { _statusBanner.value = it }
            .onFailure { _statusBanner.value = it.localizedMessage }
    }

    fun openDialer() {
        deviceController.openDialer()
            .onFailure { _statusBanner.value = it.localizedMessage }
    }

    fun openCalendarEvent() {
        deviceController.openCalendarEvent()
            .onFailure { _statusBanner.value = it.localizedMessage }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    private fun decryptRecord(record: EncryptedRecord, key: SecretKey?): DecryptedVaultItem {
        val ts = record.updatedAt?.toDate()?.time ?: record.createdAt?.toDate()?.time ?: 0L
        if (key == null) {
            return DecryptedVaultItem(
                id = record.id,
                recordType = record.recordType,
                title = "Locked E2EE Record",
                content = "Unlock with Sync Passphrase to view.",
                sourceLabel = "Encrypted",
                pinned = record.pinned,
                isDecrypted = false,
                timestampMillis = ts
            )
        }
        return try {
            val plain = cryptoManager.decrypt(record.ciphertext, record.iv, key)
            val json = JSONObject(plain)
            DecryptedVaultItem(
                id = record.id,
                recordType = record.recordType,
                title = json.optString("title", "New Era Record"),
                content = json.optString("content", plain),
                sourceLabel = "AES-256-GCM",
                pinned = record.pinned,
                isDecrypted = true,
                timestampMillis = ts
            )
        } catch (_: Exception) {
            DecryptedVaultItem(
                id = record.id,
                recordType = record.recordType,
                title = "Encrypted on Another Device",
                content = "Enter your E2EE Sync Passphrase in Sync Vault to decrypt this record.",
                sourceLabel = "Key Mismatch",
                pinned = record.pinned,
                isDecrypted = false,
                timestampMillis = ts
            )
        }
    }

    private fun unwrapFirestoreError(error: Throwable): Throwable? {
        return generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        voiceEngine.release()
        websiteServer.stop()
    }

    companion object {
        private const val TAG = "MainViewModel"

        fun provideFactory(
            currentUserId: String,
            currentUsername: String = "Sanskar",
            isOwnerSession: Boolean = false,
            authMethod: String = "google",
            appContext: android.content.Context
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val repo = NewEraRepository(context = appContext)
                val crypto = E2eeCryptoManager(appContext)
                val device = DeviceController(appContext)
                val web = com.example.web.WebSearchTool()
                val googleAppsManager = GoogleAppsManager(appContext)
                val googleWorkspaceClient = GoogleWorkspaceClient(googleAppsManager)
                val ai = NewEraAiService(web, device, googleWorkspaceClient)
                val videoRenderer = EnhancedVideoRenderer(appContext)
                val creativeMedia = AiCreativeMediaService(videoRenderer)
                val architectService = AutonomousArchitectService()
                val memoryGraphStore = HierarchicalMemoryGraphStore(appContext)
                val agenticVault = AgenticCredentialVault(appContext, crypto)
                val userSessionManager = UserSessionManager(appContext)
                val learningStore = DailyLearningStore(appContext)
                val learningEngine = DailyLearningEngine(learningStore, web, ai)
                runCatching { DailyLearningWorker.scheduleDaily(appContext) }
                val voice = AlexaVoiceEngine(appContext)
                val server = PersonalWebsiteServer()
                MainViewModel(
                    currentUserId = currentUserId,
                    currentUsername = currentUsername,
                    isOwnerSession = isOwnerSession,
                    authMethod = authMethod,
                    repository = repo,
                    cryptoManager = crypto,
                    aiService = ai,
                    creativeMediaService = creativeMedia,
                    architectService = architectService,
                    memoryGraphStore = memoryGraphStore,
                    agenticVault = agenticVault,
                    userSessionManager = userSessionManager,
                    deviceController = device,
                    googleAppsManager = googleAppsManager,
                    googleWorkspaceClient = googleWorkspaceClient,
                    learningStore = learningStore,
                    learningEngine = learningEngine,
                    voiceEngine = voice,
                    websiteServer = server
                )
            }
        }
    }
}
