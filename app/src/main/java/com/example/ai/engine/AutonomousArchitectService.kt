package com.example.ai.engine

import com.example.firebase.FirebaseAvailability
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class AutonomousBlueprint(
    val projectName: String,
    val chosenStack: String,
    val stackRationale: String,
    val databaseAndStateDesign: String,
    val heuristicUiTokens: String,
    val componentTree: List<String>,
    val edgeCasesHandled: List<String>,
    val architecturalChangelog: List<String>,
    val starterCode: String,
    val guardrailAuditSeal: String
)

class AutonomousArchitectService {

    private val architectModel: GenerativeModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "projectName" to Schema.string("Clean app/system name"),
                "chosenStack" to Schema.string("Optimal full tech stack selected automatically"),
                "stackRationale" to Schema.string("Why this stack fits best without asking the user questions"),
                "databaseAndStateDesign" to Schema.string("Concrete schema, entities, and state management layer"),
                "heuristicUiTokens" to Schema.string("Auto-selected theme tokens, spacing, dark mode, and micro-interactions"),
                "componentTree" to Schema.array(
                    items = Schema.string(),
                    description = "Responsive navigation, dashboard grids, modal flows, and validation states"
                ),
                "edgeCasesHandled" to Schema.array(
                    items = Schema.string(),
                    description = "Proactive edge cases baked in: empty states, errors, rate limits, offline fallback, input sanitization"
                ),
                "architecturalChangelog" to Schema.array(
                    items = Schema.string(),
                    description = "Transparent post-generation changelog of key architectural decisions"
                ),
                "starterCode" to Schema.string("Clean, production-ready core code module implementing the main architecture")
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

    suspend fun synthesizeZeroShotBlueprint(
        goalPrompt: String,
        overrideInstruction: String? = null
    ): AutonomousBlueprint {
        val cleanGoal = goalPrompt.trim()
        require(cleanGoal.isNotBlank()) { "Describe what you want to architect." }

        val instruction = buildString {
            appendLine("You are New Era Zero-Shot Autonomous Software Architect.")
            appendLine("Do NOT ask clarifying questions. Infer every implicit requirement and derive the complete production architecture autonomously.")
            appendLine("Goal: \"$cleanGoal\"")
            if (!overrideInstruction.isNullOrBlank()) {
                appendLine("User override modification to apply: ${overrideInstruction.trim()}")
            }
            appendLine("Never include hardcoded real API keys or secrets; use scoped gateway/vault abstractions.")
        }

        if (!FirebaseAvailability.isInitialized()) {
            return buildLocalFallbackBlueprint(cleanGoal, overrideInstruction)
        }

        return try {
            withTimeout(60_000L) {
                val raw = architectModel.generateContent(instruction).text
                    ?: throw IllegalStateException("Architect returned empty response.")
                val json = JSONObject(raw)

                fun parseArr(key: String): List<String> {
                    val arr = json.optJSONArray(key) ?: JSONArray()
                    return (0 until arr.length()).mapNotNull { i ->
                        ConstitutionalGuardrailEngine.redactSecrets(arr.optString(i).trim())
                            .takeIf { it.isNotBlank() }
                    }
                }

                val code = ConstitutionalGuardrailEngine.redactSecrets(json.optString("starterCode", ""))
                val name = json.optString("projectName", "New Era Blueprint")
                AutonomousBlueprint(
                    projectName = name,
                    chosenStack = json.optString("chosenStack", "Kotlin + Jetpack Compose + Firestore"),
                    stackRationale = json.optString("stackRationale", ""),
                    databaseAndStateDesign = json.optString("databaseAndStateDesign", ""),
                    heuristicUiTokens = json.optString("heuristicUiTokens", ""),
                    componentTree = parseArr("componentTree"),
                    edgeCasesHandled = parseArr("edgeCasesHandled"),
                    architecturalChangelog = parseArr("architecturalChangelog"),
                    starterCode = code,
                    guardrailAuditSeal = ConstitutionalGuardrailEngine.computeAuditSeal("$name|$code")
                )
            }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Autonomous architecture synthesis timed out.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (FirebaseAvailability.isFirebaseInitError(e)) {
                buildLocalFallbackBlueprint(cleanGoal, overrideInstruction)
            } else {
                throw e
            }
        }
    }

    private fun buildLocalFallbackBlueprint(
        cleanGoal: String,
        overrideInstruction: String?
    ): AutonomousBlueprint {
        val projectTitle = cleanGoal.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString(" ")
            .replaceFirstChar { it.uppercase() }
            .ifBlank { "New Era Architecture" }
        val overrideNote = overrideInstruction?.trim()?.takeIf { it.isNotBlank() }
        val starter = """
            // On-Device Synthesized Blueprint Skeleton (Local Architect Mode)
            data class UiState(
                val isLoading: Boolean = false,
                val items: List<String> = emptyList(),
                val errorMessage: String? = null
            )

            class FeatureRepository(private val localStore: LocalWorkspaceStore) {
                suspend fun saveRecord(payload: String) = localStore.persistEncrypted(payload)
            }
        """.trimIndent()
        return AutonomousBlueprint(
            projectName = projectTitle,
            chosenStack = "Kotlin + Jetpack Compose + Material 3 + DataStore + AES-256-GCM",
            stackRationale = "Selected on-device Android stack with reactive StateFlow, local encrypted persistence, and zero external dependency bottlenecks." +
                (overrideNote?.let { " Applied override: $it" } ?: ""),
            databaseAndStateDesign = "Single source of truth via StateFlow<UiState> backed by encrypted local DataStore and optional cloud sync when Firebase is configured.",
            heuristicUiTokens = "Material 3 dark/light adaptive colorScheme, 8dp spacing grid, 16dp cards, 48dp minimum interactive targets.",
            componentTree = listOf(
                "MainShell -> NavigationBar / NavigationRail",
                "FeatureDashboard -> SummaryCards + ActionSheet",
                "DetailEditor -> ValidatedForm + EncryptedSaveAction"
            ),
            edgeCasesHandled = listOf(
                "Graceful offline/local DataStore persistence when Cloud Firebase is absent",
                "Empty states, loading indicators, and CancellationException propagation",
                "Zero hardcoded API keys via scoped vault storage"
            ),
            architecturalChangelog = listOf(
                "Synthesized locally via New Era Edge Architect (add GOOGLE_SERVICES_JSON secret to GitHub for Cloud Gemini expansion)"
            ),
            starterCode = starter,
            guardrailAuditSeal = ConstitutionalGuardrailEngine.computeAuditSeal("$projectTitle|$starter")
        )
    }
}
