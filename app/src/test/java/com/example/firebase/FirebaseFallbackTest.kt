package com.example.firebase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.NewEraAiService
import com.example.ai.engine.AutonomousArchitectService
import com.example.ai.engine.ComputeMode
import com.example.data.model.AiToolKind
import com.example.device.DeviceController
import com.example.web.WebSearchTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FirebaseFallbackTest {

    @Test
    fun `firebase availability detects init errors cleanly`() {
        val sampleError = IllegalStateException(
            "Default FirebaseApp is not initialized in this process com.aistudio.sanskarai.xkmqvr."
        )
        assertTrue(FirebaseAvailability.isFirebaseInitError(sampleError))
        assertFalse(FirebaseAvailability.isFirebaseInitError(IllegalArgumentException("Bad input")))
    }

    @Test
    fun `assistant, planner, daily lesson, and architect work without crashing when FirebaseApp is absent`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // In plain Robolectric tests without FirebaseApp.initializeApp(context), FirebaseApp is uninitialized
        assertFalse(FirebaseAvailability.isInitialized())

        val aiService = NewEraAiService(WebSearchTool(), DeviceController(context))

        val statusReply = aiService.askAssistant(
            userPrompt = "check battery phone status",
            computeMode = ComputeMode.AUTO_DYNAMIC
        )
        assertFalse(statusReply.text.contains("Default FirebaseApp is not initialized", ignoreCase = true))
        assertTrue(statusReply.text.contains("Battery:", ignoreCase = true))

        val generalReply = aiService.askAssistant(
            userPrompt = "hello new era",
            computeMode = ComputeMode.AUTO_DYNAMIC
        )
        assertFalse(generalReply.text.contains("Default FirebaseApp is not initialized", ignoreCase = true))
        assertTrue(generalReply.text.contains("Local Edge Mode", ignoreCase = true))

        val plan = aiService.runAiTool(AiToolKind.ACTION_PLANNER, "Ship New Era APK")
        assertTrue(plan.contains("Action Plan", ignoreCase = true))

        val lesson = aiService.synthesizeDailyLesson(
            topic = "Android Security",
            todayDate = "2026-11-17",
            liveWebSnippets = "AES-256-GCM provides authenticated encryption on mobile devices.",
            previousHeadlines = emptyList()
        )
        assertTrue(lesson.headline.contains("Android Security"))

        val blueprint = AutonomousArchitectService().synthesizeZeroShotBlueprint("Personal encrypted task hub")
        assertFalse(blueprint.chosenStack.isBlank())
        assertTrue(blueprint.componentTree.isNotEmpty())
    }
}
