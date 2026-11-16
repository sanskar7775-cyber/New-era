package com.example.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.engine.AgenticCredentialVault
import com.example.ai.engine.ComputeMode
import com.example.ai.engine.ConstitutionalGuardrailEngine
import com.example.ai.engine.ExecutionRoute
import com.example.ai.engine.GatewayChannel
import com.example.ai.engine.HierarchicalMemoryGraphStore
import com.example.ai.engine.HybridEdgeCloudRouter
import com.example.ai.engine.MemoryNodeType
import com.example.ai.engine.System2ReasoningEngine
import com.example.crypto.E2eeCryptoManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CreativeStudioAndEngineTest {

    @Test
    fun `all requested image engines map to valid Gemini models with original names in brackets`() {
        val requiredOriginals = listOf(
            "Google Nano Banana 2.1",
            "Nano Banana 2 / 2.1",
            "ChatGPT GPT-Image-2.5",
            "Midjourney",
            "Adobe Firefly / Photoshop",
            "Recraft",
            "Canva AI",
            "Leonardo.Ai",
            "DeepAI"
        )
        val displayNames = ImageEngineProfile.entries.map { it.displayName }
        requiredOriginals.forEach { orig ->
            assertTrue(
                "Missing image engine original name ($orig)",
                displayNames.any { it.contains("($orig)") }
            )
        }
        ImageEngineProfile.entries.forEach { engine ->
            assertTrue(
                engine.modelTier.modelId in setOf("gemini-3-pro-image", "gemini-nano-banana-2.1")
            )
        }
    }

    @Test
    fun `all requested video engines map with original names in brackets`() {
        val requiredOriginals = listOf(
            "Kling 4.0",
            "Kimi Pro Max",
            "WAM 3.0",
            "Dreamina Seedance 2.5",
            "Google Gemini Omni / Veo 3.1"
        )
        val displayNames = VideoEngineProfile.entries.map { it.displayName }
        requiredOriginals.forEach { orig ->
            assertTrue(
                "Missing video engine original name ($orig)",
                displayNames.any { it.contains("($orig)") }
            )
        }
    }

    @Test
    fun `constitutional guardrails redact secrets and enforce owner privilege boundaries`() {
        val raw = "token=AIzaSyDummyKeyValue1234567890123 and password sanskarisgod"
        val redacted = ConstitutionalGuardrailEngine.redactSecrets(raw)
        assertFalse(redacted.contains("sanskarisgod"))
        assertFalse(redacted.contains("AIzaSyDummyKeyValue1234567890123"))

        assertTrue(ConstitutionalGuardrailEngine.verifyActionPermission("READ_ALL_USERS_DIRECTORY", isOwner = true).isSuccess)
        assertTrue(ConstitutionalGuardrailEngine.verifyActionPermission("READ_ALL_USERS_DIRECTORY", isOwner = false).isFailure)
    }

    @Test
    fun `hybrid edge router evaluates arithmetic locally and supports edge privacy fallback`() {
        val mathResult = HybridEdgeCloudRouter.evaluateLocalEdge(
            prompt = "25 * 4",
            edgePrivacyMode = false,
            isOnline = true,
            offlineMemoryContext = ""
        )
        assertNotNull(mathResult)
        assertTrue(mathResult!!.first.contains("100"))
        assertEquals(ExecutionRoute.EDGE_LOCAL, mathResult.second.executionRoute)

        val privacyResult = HybridEdgeCloudRouter.evaluateLocalEdge(
            prompt = "Summarize my project goals",
            edgePrivacyMode = true,
            isOnline = true,
            offlineMemoryContext = "Project Alpha uses Jetpack Compose"
        )
        assertNotNull(privacyResult)
        assertTrue(privacyResult!!.first.contains("Jetpack Compose"))
    }

    @Test
    fun `system2 complexity scorer distinguishes simple vs deep architectural queries`() {
        val engine = System2ReasoningEngine()
        val (simpleMode, _) = engine.resolveEffectiveMode(ComputeMode.AUTO_DYNAMIC, "Hi")
        val (deepMode, score) = engine.resolveEffectiveMode(
            ComputeMode.AUTO_DYNAMIC,
            "Design and optimize a distributed database schema and security architecture"
        )
        assertEquals(ComputeMode.FAST_SYSTEM1, simpleMode)
        assertEquals(ComputeMode.DEEP_SYSTEM2, deepMode)
        assertTrue(score >= 45)
    }

    @Test
    fun `hierarchical graph memory and agentic credential vault persist encrypted data`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = HierarchicalMemoryGraphStore(context)
        graph.addOrReinforceNode(
            nodeType = MemoryNodeType.CORE_PREFERENCE,
            label = "Dark Violet UI",
            summary = "User prefers obsidian midnight-violet Compose themes"
        )
        val state = graph.getState()
        val recall = HierarchicalMemoryGraphStore.retrieveSubgraphContext(state, "violet theme")
        assertTrue(recall.contains("Dark Violet UI"))

        val crypto = E2eeCryptoManager(context)
        val vault = AgenticCredentialVault(context, crypto)
        val tokens = vault.ensureJustInTimeProvisioned("test_user_vault")
        assertEquals(GatewayChannel.entries.size, tokens.size)
        assertTrue(tokens.all { it.active && it.maskedToken.contains("••") })
    }
}
