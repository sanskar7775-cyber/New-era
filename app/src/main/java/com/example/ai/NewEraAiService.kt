package com.example.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.ai.engine.ComputeMode
import com.example.ai.engine.ConstitutionalGuardrailEngine
import com.example.ai.engine.ExecutionRoute
import com.example.ai.engine.HybridEdgeCloudRouter
import com.example.ai.engine.ReasoningTrace
import com.example.ai.engine.System2ReasoningEngine
import com.example.data.model.AiToolKind
import com.example.device.DeviceController
import com.example.googleapps.GoogleAppIntegration
import com.example.googleapps.GoogleWorkspaceClient
import com.example.firebase.FirebaseAvailability
import com.example.web.WebSearchTool
import com.google.firebase.Firebase
import com.google.firebase.ai.Chat
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AspectRatio
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.ImageConfig
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ImageSize
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import org.json.JSONObject

data class AiAssistantResponse(
    val text: String,
    val toolsUsed: List<String>,
    val reasoningTrace: ReasoningTrace? = null
)

data class SynthesizedDailyLesson(
    val headline: String,
    val keyTakeaway: String,
    val deepSummary: String,
    val sources: List<String>,
    val suggestions: List<Pair<String, String>> = emptyList()
)

class NewEraAiService(
    private val webSearchTool: WebSearchTool,
    private val deviceController: DeviceController,
    private val googleWorkspaceClient: GoogleWorkspaceClient? = null
) {

    private val assistantTools = Tool.functionDeclarations(
        listOf(
            FunctionDeclaration(
                name = "web_search",
                description = "Search the live internet for real-time facts, news, topics, or reference info.",
                parameters = mapOf(
                    "query" to Schema.string("The search query to look up on the internet")
                )
            ),
            FunctionDeclaration(
                name = "fetch_url",
                description = "Fetch and read the text content of a specific website URL from the internet.",
                parameters = mapOf(
                    "url" to Schema.string("The full website URL to fetch, e.g. https://example.com")
                )
            ),
            FunctionDeclaration(
                name = "get_phone_status",
                description = "Get live phone telemetry including battery level, charging state, network status, storage, and installed app count.",
                parameters = emptyMap()
            ),
            FunctionDeclaration(
                name = "list_installed_apps",
                description = "List all launchable apps currently installed on Sanskar's phone.",
                parameters = emptyMap()
            ),
            FunctionDeclaration(
                name = "launch_app",
                description = "Open or launch an installed app on Sanskar's phone by name.",
                parameters = mapOf(
                    "app_name" to Schema.string("Name of the app to open, e.g. Settings, Chrome, YouTube, Clock")
                )
            ),
            FunctionDeclaration(
                name = "toggle_flashlight",
                description = "Turn the phone flashlight torch on or off.",
                parameters = mapOf(
                    "enabled" to Schema.boolean("true to turn flashlight ON, false to turn OFF")
                )
            ),
            FunctionDeclaration(
                name = "open_phone_settings",
                description = "Open a specific Android phone settings screen such as wifi, bluetooth, display, sound, battery, or apps.",
                parameters = mapOf(
                    "section" to Schema.string("Settings section: wifi, bluetooth, display, sound, battery, apps, or main")
                )
            ),
            FunctionDeclaration(
                name = "set_timer",
                description = "Set a countdown timer on the user's phone.",
                parameters = mapOf(
                    "seconds" to Schema.integer("Duration of the timer in seconds"),
                    "label" to Schema.string("Short label for the timer")
                )
            ),
            FunctionDeclaration(
                name = "list_google_workspace_items",
                description = "Read items from Sanskar's Google apps (gmail, calendar, drive, docs, sheets, slides, contacts, tasks, youtube) if user permission is granted.",
                parameters = mapOf(
                    "app" to Schema.string("Google app name: gmail, calendar, drive, docs, sheets, slides, contacts, tasks, or youtube"),
                    "query" to Schema.string("Optional search query or filter")
                )
            ),
            FunctionDeclaration(
                name = "create_google_workspace_item",
                description = "Create an item or send an email in Sanskar's Google apps (gmail, calendar, docs, sheets, slides, tasks) if user permission is granted.",
                parameters = mapOf(
                    "app" to Schema.string("Google app name: gmail, calendar, docs, sheets, slides, or tasks"),
                    "title" to Schema.string("Title, subject, or event name"),
                    "details" to Schema.string("Body text, notes, or description"),
                    "recipient" to Schema.string("Email recipient when app is gmail (empty otherwise)")
                )
            )
        )
    )

    private val assistantModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            systemInstruction = content {
                text(
                    "You are New Era, a highly capable, conversational personal AI assistant owned by Sanskar. " +
                        "You speak clearly, naturally, and concisely like an ultra-smart Alexa-grade voice assistant, " +
                        "while being a master of all AI, web research, coding, and phone automation tools. " +
                        "Whenever the user asks about live information, current events, or websites, call web_search or fetch_url. " +
                        "Whenever the user asks about their phone, installed apps, opening an app, toggling the flashlight, " +
                        "opening settings, or setting a timer, call the corresponding device tool. Always confirm actions clearly."
                )
            },
            tools = listOf(assistantTools)
        )
    }

    @Volatile
    private var learnedMemoryContext: String = ""

    fun updateLearnedMemoryContext(memory: String) {
        learnedMemoryContext = memory.trim()
    }

    @Volatile
    private var chatSession: Chat? = null

    private fun getOrCreateChatSession(): Chat {
        val existing = chatSession
        if (existing != null) return existing
        return synchronized(this) {
            chatSession ?: assistantModel.startChat().also { chatSession = it }
        }
    }

    fun resetChatSession() {
        synchronized(this) {
            chatSession = null
        }
    }

    private val system2Engine = System2ReasoningEngine()

    private val fastSystem1Model: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            systemInstruction = content {
                text(
                    "You are New Era Fast Edge/System 1 Assistant. Respond crisply, accurately, and concisely while maintaining Constitutional safety and zero-leak secret discipline."
                )
            }
        )
    }

    private val generalModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
    }

    private val webGroundedModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            tools = listOf(Tool.googleSearch(), Tool.urlContext())
        )
    }

    private val dailyLessonModel: GenerativeModel by lazy {
        val suggestionItemSchema = Schema.obj(
            properties = mapOf(
                "title" to Schema.string("Short clickable suggestion title (max 7 words)"),
                "actionPrompt" to Schema.string("Full actionable prompt New Era can execute or answer for Sanskar")
            )
        )
        val schema = Schema.obj(
            properties = mapOf(
                "headline" to Schema.string("Concise title of today's learned breakthrough or concept"),
                "keyTakeaway" to Schema.string("1-2 sentence high-signal insight New Era will remember"),
                "deepSummary" to Schema.string("Clear 3-4 sentence explanation with practical relevance for Sanskar"),
                "sources" to Schema.array(
                    items = Schema.string(),
                    description = "Reference URLs or source names used"
                ),
                "suggestions" to Schema.array(
                    items = suggestionItemSchema,
                    description = "2 to 3 proactive smart suggestions tailored to Sanskar based on this lesson and his interests"
                )
            )
        )
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            }
        )
    }

    private val structuredPlannerModel: GenerativeModel by lazy {
        val planSchema = Schema.obj(
            properties = mapOf(
                "goalTitle" to Schema.string("Clear title for the action plan"),
                "summary" to Schema.string("Executive summary of the strategy"),
                "steps" to Schema.array(
                    items = Schema.string(),
                    description = "Ordered actionable steps"
                ),
                "estimatedTime" to Schema.string("Realistic time estimate")
            )
        )
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = planSchema
            }
        )
    }

    suspend fun askAssistant(
        userPrompt: String,
        attachedImage: Bitmap? = null,
        queryMemoryContext: String? = null,
        computeMode: ComputeMode = ComputeMode.AUTO_DYNAMIC,
        edgePrivacyMode: Boolean = false
    ): AiAssistantResponse {
        val startMs = System.currentTimeMillis()
        val toolsUsed = mutableListOf<String>()
        val activeMemory = queryMemoryContext?.takeIf { it.isNotBlank() } ?: learnedMemoryContext
        val telemetry = runCatching { deviceController.getDeviceTelemetry() }.getOrNull()
        val isOnline = telemetry?.networkStatus?.contains("Offline", ignoreCase = true) != true

        // 1. Check Hybrid Edge-Cloud Router first (zero-latency on-device or Edge Privacy Enclave)
        if (attachedImage == null) {
            val edgeResult = HybridEdgeCloudRouter.evaluateLocalEdge(
                prompt = userPrompt,
                edgePrivacyMode = edgePrivacyMode,
                isOnline = isOnline,
                offlineMemoryContext = activeMemory
            )
            if (edgeResult != null) {
                return AiAssistantResponse(
                    text = edgeResult.first,
                    toolsUsed = listOf("edge_local_kernel"),
                    reasoningTrace = edgeResult.second
                )
            }
        }

        val (effectiveMode, complexityScore) = system2Engine.resolveEffectiveMode(computeMode, userPrompt)
        val sensorContext = telemetry?.let {
            "Sensorimotor Context: Device=${it.deviceModel}, Battery=${it.batteryPercent}% (charging=${it.isCharging}), Network=${it.networkStatus}, Storage=${it.availableStorageGb}"
        } ?: ""

        val effectiveInput = buildString {
            if (sensorContext.isNotBlank()) appendLine("[$sensorContext]")
            if (activeMemory.isNotBlank()) {
                appendLine("[Hierarchical & Evolving Memory Context — apply without degrading recall:")
                appendLine(activeMemory)
                appendLine("]")
            }
            append("User: ").append(userPrompt)
        }

        if (!FirebaseAvailability.isInitialized()) {
            return handleLocalAssistantFallback(
                userPrompt = userPrompt,
                attachedImage = attachedImage,
                activeMemory = activeMemory,
                startMs = startMs,
                complexityScore = complexityScore
            )
        }

        return try {
            withTimeout(60_000L) {
                if (effectiveMode == ComputeMode.FAST_SYSTEM1 && attachedImage == null && complexityScore < 30) {
                    val fastReply = fastSystem1Model.generateContent(effectiveInput).text?.trim()
                    if (!fastReply.isNullOrBlank()) {
                        val cleanFast = ConstitutionalGuardrailEngine.redactSecrets(fastReply)
                        return@withTimeout AiAssistantResponse(
                            text = cleanFast,
                            toolsUsed = listOf("system1_fast_lite"),
                            reasoningTrace = ReasoningTrace(
                                computeMode = ComputeMode.FAST_SYSTEM1,
                                executionRoute = ExecutionRoute.HYBRID_VERIFIED,
                                complexityScore = complexityScore,
                                hypothesesEvaluated = listOf("Direct System 1 fast synthesis (gemini-3.5-flash-lite)"),
                                selfCorrections = listOf("Constitutional zero-leak redaction pass"),
                                guardrailSeal = ConstitutionalGuardrailEngine.computeAuditSeal(cleanFast),
                                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                            )
                        )
                    }
                }

                val activeChat = getOrCreateChatSession()
                var response = if (attachedImage != null) {
                    activeChat.sendMessage(
                        content {
                            image(attachedImage)
                            text(effectiveInput)
                        }
                    )
                } else {
                    activeChat.sendMessage(effectiveInput)
                }

                // Multi-step Autonomous Agent Loop (up to 8 chained steps with fault-tolerant recovery)
                var loopCount = 0
                while (response.functionCalls.isNotEmpty() && loopCount < 8) {
                    loopCount++
                    val calls = response.functionCalls
                    val responseParts = mutableListOf<FunctionResponsePart>()
                    for (call in calls) {
                        toolsUsed.add(call.name)
                        val toolOutputJson = executeToolCall(call.name, call.args)
                        val part = call.id?.let {
                            FunctionResponsePart(call.name, toolOutputJson, it)
                        } ?: FunctionResponsePart(call.name, toolOutputJson)
                        responseParts.add(part)
                    }
                    response = activeChat.sendMessage(
                        content(role = "function") {
                            for (p in responseParts) {
                                part(p)
                            }
                        }
                    )
                }

                val candidateText = response.text?.trim()
                    ?: throw IllegalStateException("New Era returned an empty response.")

                // 2. Apply System 2 Tree-of-Thoughts Verification when complex or explicitly requested
                val (finalText, trace) = if (effectiveMode == ComputeMode.DEEP_SYSTEM2 && toolsUsed.isEmpty()) {
                    system2Engine.verifyWithTreeOfThoughts(
                        userPrompt = userPrompt,
                        candidateAnswer = candidateText,
                        complexityScore = complexityScore,
                        startMillis = startMs
                    )
                } else {
                    val clean = ConstitutionalGuardrailEngine.redactSecrets(candidateText)
                    clean to ReasoningTrace(
                        computeMode = effectiveMode,
                        executionRoute = ExecutionRoute.HYBRID_VERIFIED,
                        complexityScore = complexityScore,
                        hypothesesEvaluated = listOf(
                            if (toolsUsed.isNotEmpty()) "Multi-step Agentic Orchestration (${toolsUsed.size} tools)"
                            else "Native Multimodal Fusion synthesis"
                        ),
                        selfCorrections = listOf("Constitutional secret redaction & schema guardrail verified"),
                        guardrailSeal = ConstitutionalGuardrailEngine.computeAuditSeal(clean),
                        latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    )
                }

                AiAssistantResponse(
                    text = finalText,
                    toolsUsed = toolsUsed.distinct(),
                    reasoningTrace = trace
                )
            }
        } catch (e: TimeoutCancellationException) {
            resetChatSession()
            throw IllegalStateException("Request timed out after 60 seconds. Please try again.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "askAssistant failed", e)
            resetChatSession()
            if (FirebaseAvailability.isFirebaseInitError(e)) {
                return handleLocalAssistantFallback(
                    userPrompt = userPrompt,
                    attachedImage = attachedImage,
                    activeMemory = activeMemory,
                    startMs = startMs,
                    complexityScore = complexityScore
                )
            }
            throw e
        }
    }

    suspend fun runAiTool(
        toolKind: AiToolKind,
        input: String,
        attachedImage: Bitmap? = null
    ): String {
        if (!FirebaseAvailability.isInitialized()) {
            return runLocalAiToolFallback(toolKind, input)
        }
        return try {
            withTimeout(60_000L) {
                when (toolKind) {
                    AiToolKind.WEB_BRIEF -> {
                        val liveResults = webSearchTool.searchInternet(input)
                        val prompt = """
                            You are New Era AI Web Researcher for Sanskar.
                            Use live Google Search grounding plus these retrieved web snippets to synthesize an accurate, up-to-date brief for: "$input"
                            Snippets:
                            $liveResults
                        """.trimIndent()
                        try {
                            webGroundedModel.generateContent(prompt).text?.trim()
                        } catch (_: Exception) {
                            generalModel.generateContent(prompt).text?.trim()
                        } ?: throw IllegalStateException("Model returned empty research brief.")
                    }

                    AiToolKind.ACTION_PLANNER -> {
                        val response = structuredPlannerModel.generateContent(
                            "Create a detailed, realistic action plan for Sanskar's goal: $input"
                        )
                        val rawJson = response.text ?: throw IllegalStateException("Empty planner response.")
                        val json = JSONObject(rawJson)
                        val title = json.optString("goalTitle", "Action Plan")
                        val summary = json.optString("summary", "")
                        val eta = json.optString("estimatedTime", "")
                        val stepsArr = json.optJSONArray("steps")
                        val stepsText = buildString {
                            if (stepsArr != null) {
                                for (i in 0 until stepsArr.length()) {
                                    appendLine("${i + 1}. ${stepsArr.getString(i)}")
                                }
                            }
                        }
                        buildString {
                            appendLine("# $title")
                            if (eta.isNotBlank()) appendLine("Estimated Time: $eta")
                            appendLine()
                            appendLine(summary)
                            appendLine()
                            append(stepsText.trim())
                        }
                    }

                    AiToolKind.SUMMARIZER -> {
                        val maybeUrl = input.trim()
                        val sourceMaterial = if (maybeUrl.startsWith("http://") || maybeUrl.startsWith("https://")) {
                            webSearchTool.fetchWebPage(maybeUrl)
                        } else {
                            input
                        }
                        val prompt = "Summarize the key insights, bullet takeaways, and action items clearly:\n\n$sourceMaterial"
                        if (attachedImage != null) {
                            generalModel.generateContent(content {
                                image(attachedImage)
                                text(prompt)
                            }).text?.trim()
                        } else {
                            generalModel.generateContent(prompt).text?.trim()
                        } ?: throw IllegalStateException("Model returned empty summary.")
                    }

                    AiToolKind.CODE_ARCHITECT -> {
                        val prompt = "You are New Era Code Architect. Provide clean, production-grade code and clear technical explanation for:\n$input"
                        if (attachedImage != null) {
                            generalModel.generateContent(content {
                                image(attachedImage)
                                text(prompt)
                            }).text?.trim()
                        } else {
                            generalModel.generateContent(prompt).text?.trim()
                        } ?: throw IllegalStateException("Model returned empty code response.")
                    }

                    AiToolKind.TRANSLATOR -> {
                        val prompt = "Translate accurately while preserving tone, formatting, and idioms:\n$input"
                        generalModel.generateContent(prompt).text?.trim()
                            ?: throw IllegalStateException("Model returned empty translation.")
                    }

                    AiToolKind.EMAIL_WRITER -> {
                        val prompt = "Draft a polished, ready-to-send message or post for Sanskar based on:\n$input"
                        generalModel.generateContent(prompt).text?.trim()
                            ?: throw IllegalStateException("Model returned empty draft.")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("AI Tool timed out after 60 seconds.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runAiTool failed", e)
            if (FirebaseAvailability.isFirebaseInitError(e)) {
                return runLocalAiToolFallback(toolKind, input)
            }
            throw e
        }
    }

    suspend fun analyzeVisionImage(bitmap: Bitmap, prompt: String): String {
        if (!FirebaseAvailability.isInitialized()) {
            throw IllegalStateException(FirebaseAvailability.CLOUD_AI_UNAVAILABLE_GUIDANCE)
        }
        return try {
            withTimeout(60_000L) {
                val response = generalModel.generateContent(
                    content {
                        image(bitmap)
                        text(prompt.ifBlank { "Analyze this image in detail for Sanskar." })
                    }
                )
                response.text?.trim() ?: throw IllegalStateException("No analysis returned.")
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Vision analysis timed out after 60 seconds.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (FirebaseAvailability.isFirebaseInitError(e)) {
                throw IllegalStateException(FirebaseAvailability.CLOUD_AI_UNAVAILABLE_GUIDANCE, e)
            }
            throw e
        }
    }

    suspend fun synthesizeDailyLesson(
        topic: String,
        todayDate: String,
        liveWebSnippets: String,
        previousHeadlines: List<String>,
        userInterests: List<String> = emptyList()
    ): SynthesizedDailyLesson {
        if (!FirebaseAvailability.isInitialized()) {
            return buildLocalDailyLesson(topic, todayDate, liveWebSnippets)
        }
        return try {
            withTimeout(60_000L) {
                val interestHint = if (userInterests.isNotEmpty()) {
                    " Consider Sanskar's recent questions/interests: ${userInterests.joinToString("; ")}."
                } else ""
                val groundedResearch = runCatching {
                    webGroundedModel.generateContent(
                        "Research one genuinely fresh, fascinating, high-value discovery, engineering concept, or real-world update on '$topic' as of $todayDate.$interestHint Avoid repeating: ${previousHeadlines.joinToString("; ")}. Cite source domains."
                    ).text.orEmpty()
                }.getOrDefault("")

                val prompt = """
                    Synthesize today's daily self-learning lesson ($todayDate) on topic "$topic" so New Era becomes smarter every day at giving suggestions and answering Sanskar's questions.
                    Do NOT repeat any previous headline: ${previousHeadlines.joinToString(" | ")}
                    ${if (userInterests.isNotEmpty()) "Sanskar's recent topics of interest: ${userInterests.joinToString(" | ")}" else ""}
                    Live Google Grounding Research:
                    $groundedResearch

                    Additional Live Web Snippets:
                    $liveWebSnippets
                """.trimIndent()

                val rawJson = dailyLessonModel.generateContent(prompt).text
                    ?: throw IllegalStateException("Daily lesson synthesis returned empty output.")
                val obj = JSONObject(rawJson)
                val sourcesArr = obj.optJSONArray("sources")
                val sources = buildList {
                    if (sourcesArr != null) {
                        for (i in 0 until sourcesArr.length()) {
                            val s = sourcesArr.optString(i).trim()
                            if (s.isNotBlank()) add(s)
                        }
                    }
                }
                val sugArr = obj.optJSONArray("suggestions")
                val suggestions = buildList {
                    if (sugArr != null) {
                        for (i in 0 until sugArr.length()) {
                            val sObj = sugArr.optJSONObject(i) ?: continue
                            val title = sObj.optString("title").trim()
                            val actionPrompt = sObj.optString("actionPrompt").trim()
                            if (title.isNotBlank() && actionPrompt.isNotBlank()) {
                                add(title to actionPrompt)
                            }
                        }
                    }
                }
                SynthesizedDailyLesson(
                    headline = obj.optString("headline", "Daily Insight on $topic"),
                    keyTakeaway = obj.optString("keyTakeaway", "New Era synthesized a fresh web insight on $topic."),
                    deepSummary = obj.optString("deepSummary", groundedResearch.ifBlank { liveWebSnippets.take(400) }),
                    sources = sources,
                    suggestions = suggestions
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Daily internet learning timed out.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (FirebaseAvailability.isFirebaseInitError(e)) {
                buildLocalDailyLesson(topic, todayDate, liveWebSnippets)
            } else {
                throw e
            }
        }
    }

    suspend fun transcribeAudio(wavBytes: ByteArray): String {
        if (wavBytes.size <= 44) return ""
        if (!FirebaseAvailability.isInitialized()) {
            throw IllegalStateException(FirebaseAvailability.CLOUD_AI_UNAVAILABLE_GUIDANCE)
        }
        return try {
            withTimeout(30_000L) {
                val response = generalModel.generateContent(
                    content {
                        part(InlineDataPart(wavBytes, "audio/wav"))
                        text(
                            "Transcribe the user's spoken voice command accurately into plain text. " +
                                "Output ONLY the exact words spoken without quotes, labels, or extra commentary. " +
                                "If the audio is silent or unintelligible noise, output an empty string."
                        )
                    }
                )
                response.text.orEmpty().trim()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "transcribeAudio failed", e)
            if (FirebaseAvailability.isFirebaseInitError(e)) {
                throw IllegalStateException(FirebaseAvailability.CLOUD_AI_UNAVAILABLE_GUIDANCE, e)
            }
            throw e
        }
    }

    suspend fun generateAiImage(prompt: String, aspectRatio: AspectRatio = AspectRatio.SQUARE_1x1): Bitmap {
        if (!FirebaseAvailability.isInitialized()) {
            throw IllegalStateException(FirebaseAvailability.CLOUD_AI_UNAVAILABLE_GUIDANCE)
        }
        return try {
            withTimeout(60_000L) {
                val config = generationConfig {
                    responseModalities = listOf(ResponseModality.IMAGE)
                    imageConfig = ImageConfig.builder()
                        .setAspectRatio(aspectRatio)
                        .setImageSize(ImageSize.SIZE_1K)
                        .build()
                }
                val imageModel = Firebase.ai.generativeModel(
                    modelName = "gemini-nano-banana-2.1",
                    generationConfig = config
                )
                val response = imageModel.generateContent(prompt)
                val imagePart = response.candidates.firstOrNull()
                    ?.content?.parts?.filterIsInstance<ImagePart>()
                    ?.firstOrNull()
                imagePart?.image ?: throw IllegalStateException("No image was returned by the model.")
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Image generation timed out after 60 seconds.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "generateAiImage failed", e)
            if (FirebaseAvailability.isFirebaseInitError(e)) {
                throw IllegalStateException(FirebaseAvailability.CLOUD_AI_UNAVAILABLE_GUIDANCE, e)
            }
            throw e
        }
    }

    private suspend fun handleLocalAssistantFallback(
        userPrompt: String,
        attachedImage: Bitmap?,
        activeMemory: String,
        startMs: Long,
        complexityScore: Int
    ): AiAssistantResponse {
        val lower = userPrompt.trim().lowercase()
        val toolsUsed = mutableListOf<String>()

        if (attachedImage != null) {
            return buildLocalAssistantResponse(
                text = FirebaseAvailability.CLOUD_AI_UNAVAILABLE_GUIDANCE,
                toolsUsed = emptyList(),
                complexityScore = complexityScore,
                startMs = startMs,
                hypothesis = "Local edge router check (image analysis requires Cloud Gemini)"
            )
        }

        when {
            lower.contains("flashlight") || lower.contains("torch") -> {
                val enable = !lower.contains("off") && !lower.contains("disable")
                val msg = deviceController.toggleFlashlight(enable)
                    .fold(onSuccess = { it }, onFailure = { it.message ?: "Flashlight unavailable." })
                toolsUsed.add("toggle_flashlight")
                return buildLocalAssistantResponse(msg, toolsUsed, complexityScore, startMs, "On-device hardware torch command")
            }

            lower.contains("battery") || lower.contains("phone status") || lower.contains("device status") || lower.contains("storage") -> {
                val t = deviceController.getDeviceTelemetry()
                toolsUsed.add("get_phone_status")
                val msg = "Device: ${t.deviceModel} (${t.androidVersion}) • Battery: ${t.batteryPercent}% " +
                    "(charging=${t.isCharging}) • Network: ${t.networkStatus} • Free Storage: ${t.availableStorageGb} • Installed Apps: ${t.installedAppCount}."
                return buildLocalAssistantResponse(msg, toolsUsed, complexityScore, startMs, "On-device telemetry readout")
            }

            lower.contains("list apps") || lower.contains("installed apps") -> {
                val apps = deviceController.getInstalledApps()
                toolsUsed.add("list_installed_apps")
                val names = apps.take(25).joinToString(", ") { it.appName }
                val msg = if (apps.isEmpty()) "No launchable apps detected." else "Installed apps (${apps.size}): $names"
                return buildLocalAssistantResponse(msg, toolsUsed, complexityScore, startMs, "On-device package query")
            }

            lower.startsWith("open ") || lower.startsWith("launch ") -> {
                val target = userPrompt.trim()
                    .replaceFirst(Regex("^(open|launch)\\s+", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (target.contains("setting", ignoreCase = true)) {
                    val section = when {
                        lower.contains("wifi") || lower.contains("wi-fi") -> "wifi"
                        lower.contains("bluetooth") -> "bluetooth"
                        lower.contains("display") -> "display"
                        lower.contains("sound") || lower.contains("volume") -> "sound"
                        lower.contains("battery") -> "battery"
                        else -> "main"
                    }
                    val msg = deviceController.openSystemSettings(section)
                        .fold(onSuccess = { it }, onFailure = { it.message ?: "Could not open settings." })
                    toolsUsed.add("open_phone_settings")
                    return buildLocalAssistantResponse(msg, toolsUsed, complexityScore, startMs, "On-device settings navigation")
                } else if (target.isNotBlank()) {
                    val msg = deviceController.launchAppByName(target)
                        .fold(onSuccess = { it }, onFailure = { it.message ?: "Could not open $target." })
                    toolsUsed.add("launch_app")
                    return buildLocalAssistantResponse(msg, toolsUsed, complexityScore, startMs, "On-device app launcher")
                }
            }

            lower.contains("timer") -> {
                val number = Regex("""\d+""").find(lower)?.value?.toIntOrNull() ?: 60
                val seconds = if (lower.contains("min")) number * 60 else number
                val msg = deviceController.setTimer(seconds, "New Era Timer")
                    .fold(onSuccess = { it }, onFailure = { it.message ?: "Could not set timer." })
                toolsUsed.add("set_timer")
                return buildLocalAssistantResponse(msg, toolsUsed, complexityScore, startMs, "On-device alarm timer intent")
            }

            lower.startsWith("http://") || lower.startsWith("https://") -> {
                val page = webSearchTool.fetchWebPage(userPrompt.trim())
                toolsUsed.add("fetch_url")
                return buildLocalAssistantResponse(page.take(1600), toolsUsed, complexityScore, startMs, "Direct live web page fetch")
            }

            lower.startsWith("search ") || lower.contains("news") || lower.contains("weather") || lower.contains("latest") -> {
                val query = userPrompt.trim().replaceFirst(Regex("^search\\s+", RegexOption.IGNORE_CASE), "")
                val snippets = webSearchTool.searchInternet(query)
                toolsUsed.add("web_search")
                return buildLocalAssistantResponse(
                    text = "Live Web Search Results for \"$query\":\n\n$snippets",
                    toolsUsed = toolsUsed,
                    complexityScore = complexityScore,
                    startMs = startMs,
                    hypothesis = "Direct live web search (local mode)"
                )
            }
        }

        val reply = buildString {
            appendLine("New Era is running in Local Edge Mode (Cloud Gemini needs GOOGLE_SERVICES_JSON in GitHub Secrets).")
            appendLine()
            appendLine("Available right now without Google/Firebase:")
            appendLine("• Phone commands: \"battery status\", \"turn on flashlight\", \"list apps\", \"open Chrome\", \"set timer 2 minutes\"")
            appendLine("• Live Web search: \"search <topic>\" or paste any https:// URL")
            appendLine("• Math & Encrypted Vault & Local Web Portal")
            if (activeMemory.isNotBlank()) {
                appendLine()
                appendLine("Saved Memory Context:")
                appendLine(activeMemory.take(400))
            }
        }.trim()

        return buildLocalAssistantResponse(
            text = reply,
            toolsUsed = listOf("edge_local_kernel"),
            complexityScore = complexityScore,
            startMs = startMs,
            hypothesis = "On-device assistant fallback"
        )
    }

    private fun buildLocalAssistantResponse(
        text: String,
        toolsUsed: List<String>,
        complexityScore: Int,
        startMs: Long,
        hypothesis: String
    ): AiAssistantResponse {
        val clean = ConstitutionalGuardrailEngine.redactSecrets(text)
        return AiAssistantResponse(
            text = clean,
            toolsUsed = toolsUsed,
            reasoningTrace = ReasoningTrace(
                computeMode = ComputeMode.FAST_SYSTEM1,
                executionRoute = ExecutionRoute.EDGE_LOCAL,
                complexityScore = complexityScore,
                hypothesesEvaluated = listOf(hypothesis),
                selfCorrections = listOf("Local edge guardrail check passed"),
                guardrailSeal = ConstitutionalGuardrailEngine.computeAuditSeal(clean),
                latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
            )
        )
    }

    private suspend fun runLocalAiToolFallback(toolKind: AiToolKind, input: String): String {
        return when (toolKind) {
            AiToolKind.WEB_BRIEF -> {
                val results = webSearchTool.searchInternet(input)
                "# Live Web Brief: ${input.take(60)}\n\n$results"
            }

            AiToolKind.SUMMARIZER -> {
                val maybeUrl = input.trim()
                val source = if (maybeUrl.startsWith("http://") || maybeUrl.startsWith("https://")) {
                    webSearchTool.fetchWebPage(maybeUrl)
                } else {
                    input
                }
                val sentences = source.split(Regex("(?<=[.!?])\\s+|\\n+"))
                    .map { it.trim() }
                    .filter { it.length > 20 }
                    .take(5)
                if (sentences.isEmpty()) source.take(600)
                else sentences.joinToString("\n• ", prefix = "Key Highlights:\n• ")
            }

            AiToolKind.ACTION_PLANNER -> {
                """
                # Action Plan: ${input.trim().take(70)}
                1. Clarify the exact milestone and measurable outcome for "${input.trim().take(50)}".
                2. Break work into 3 focused 30-minute execution blocks.
                3. Save key notes and credentials inside New Era's encrypted vault.
                4. Review progress daily and refine next steps.
                """.trimIndent()
            }

            AiToolKind.CODE_ARCHITECT,
            AiToolKind.TRANSLATOR,
            AiToolKind.EMAIL_WRITER -> FirebaseAvailability.CLOUD_AI_UNAVAILABLE_GUIDANCE
        }
    }

    private fun buildLocalDailyLesson(
        topic: String,
        todayDate: String,
        liveWebSnippets: String
    ): SynthesizedDailyLesson {
        val firstLine = liveWebSnippets.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.length > 15 }
            ?: "Fresh live web briefing on $topic"
        return SynthesizedDailyLesson(
            headline = "$topic Brief ($todayDate)",
            keyTakeaway = firstLine.take(180),
            deepSummary = liveWebSnippets.ifBlank {
                "Collected local topic brief for $topic on $todayDate."
            }.take(900),
            sources = listOf("Live Web Search ($todayDate)"),
            suggestions = listOf(
                "Explore $topic on Web" to "search $topic latest breakthroughs",
                "Open Web Brief Tool" to "Search the latest updates on $topic"
            )
        )
    }

    private suspend fun executeToolCall(name: String, args: Map<String, Any?>): JsonObject {
        fun argString(key: String): String {
            val v = args[key]
            return (v as? JsonPrimitive)?.contentOrNull ?: v?.toString().orEmpty()
        }

        fun argBool(key: String): Boolean {
            val v = args[key]
            return (v as? JsonPrimitive)?.booleanOrNull ?: (v?.toString()?.toBooleanStrictOrNull() ?: false)
        }

        fun argInt(key: String): Int {
            val v = args[key]
            return (v as? JsonPrimitive)?.intOrNull ?: (v?.toString()?.toIntOrNull() ?: 60)
        }

        val resultString = when (name) {
            "web_search" -> webSearchTool.searchInternet(argString("query"))
            "fetch_url" -> webSearchTool.fetchWebPage(argString("url"))
            "get_phone_status" -> {
                val t = deviceController.getDeviceTelemetry()
                "Device: ${t.deviceModel}, OS: ${t.androidVersion}, Battery: ${t.batteryPercent}% " +
                    "(charging=${t.isCharging}), Network: ${t.networkStatus}, " +
                    "Free Storage: ${t.availableStorageGb}, Media Volume: ${t.mediaVolumePercent}%, " +
                    "Installed Apps: ${t.installedAppCount}"
            }
            "list_installed_apps" -> {
                val apps = deviceController.getInstalledApps()
                apps.take(60).joinToString(", ") { it.appName }
            }
            "launch_app" -> {
                deviceController.launchAppByName(argString("app_name"))
                    .fold(onSuccess = { it }, onFailure = { it.message ?: "Failed to launch app" })
            }
            "toggle_flashlight" -> {
                deviceController.toggleFlashlight(argBool("enabled"))
                    .fold(onSuccess = { it }, onFailure = { it.message ?: "Flashlight unavailable" })
            }
            "open_phone_settings" -> {
                deviceController.openSystemSettings(argString("section"))
                    .fold(onSuccess = { it }, onFailure = { it.message ?: "Could not open settings" })
            }
            "set_timer" -> {
                deviceController.setTimer(argInt("seconds"), argString("label"))
                    .fold(onSuccess = { it }, onFailure = { it.message ?: "Could not set timer" })
            }
            "list_google_workspace_items" -> {
                val app = GoogleAppIntegration.fromId(argString("app"))
                if (app == null) {
                    "Unknown Google app '${argString("app")}'."
                } else if (googleWorkspaceClient == null) {
                    "Google Workspace client is not connected."
                } else {
                    googleWorkspaceClient.listAppItems(app, argString("query"))
                }
            }
            "create_google_workspace_item" -> {
                val app = GoogleAppIntegration.fromId(argString("app"))
                if (app == null) {
                    "Unknown Google app '${argString("app")}'."
                } else if (googleWorkspaceClient == null) {
                    "Google Workspace client is not connected."
                } else {
                    googleWorkspaceClient.createAppItem(
                        app = app,
                        title = argString("title"),
                        details = argString("details"),
                        recipient = argString("recipient")
                    )
                }
            }
            else -> "Unknown tool '$name'"
        }

        return buildJsonObject {
            put("result", JsonPrimitive(resultString))
        }
    }

    companion object {
        private const val TAG = "NewEraAiService"
    }
}
