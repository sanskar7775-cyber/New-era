package com.example.learning

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DailyLearningStoreTest {

    @Test
    fun `knowledge level progresses as lessons accumulate`() {
        assertEquals("Apprentice", DailyLearningStore.computeKnowledgeLevel(0))
        assertEquals("Explorer", DailyLearningStore.computeKnowledgeLevel(1))
        assertEquals("Scholar", DailyLearningStore.computeKnowledgeLevel(3))
        assertEquals("Specialist", DailyLearningStore.computeKnowledgeLevel(7))
        assertEquals("Master", DailyLearningStore.computeKnowledgeLevel(15))
        assertEquals("Visionary", DailyLearningStore.computeKnowledgeLevel(30))
    }

    @Test
    fun `streak increments on consecutive days and resets after gap`() {
        assertEquals(1, DailyLearningStore.computeUpdatedStreak("", "2026-11-15", 0))
        assertEquals(3, DailyLearningStore.computeUpdatedStreak("2026-11-15", "2026-11-15", 3))
        assertEquals(4, DailyLearningStore.computeUpdatedStreak("2026-11-14", "2026-11-15", 3))
        assertEquals(1, DailyLearningStore.computeUpdatedStreak("2026-11-10", "2026-11-15", 5))
    }

    @Test
    fun `recordLesson persists suggestions, question topics, and prioritizes matching memory for queries`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DailyLearningStore(context)

        val quantumEntry = store.recordLesson(
            topic = "Quantum Computing",
            headline = "Fault-Tolerant Logical Qubits",
            keyTakeaway = "Surface code error correction lowers logical qubit noise.",
            deepSummary = "Modular cryogenic interconnects allow multi-chip quantum scaling.",
            sources = listOf("nature.com"),
            suggestions = listOf(
                DailySuggestion(
                    id = "s_q1",
                    title = "Explore quantum security",
                    actionPrompt = "How does post-quantum cryptography protect my data?"
                )
            ),
            dateIso = "2026-11-14"
        )

        store.recordLesson(
            topic = "Artificial Intelligence",
            headline = "Sparse Attention Scaling",
            keyTakeaway = "Sparse attention reduces memory quadratic bottleneck.",
            deepSummary = "Detailed explanation of sparse attention.",
            sources = listOf("arxiv.org"),
            suggestions = listOf(
                DailySuggestion(
                    id = "s_ai1",
                    title = "Build faster agent workflows",
                    actionPrompt = "Suggest 3 ways sparse attention speeds up local AI apps."
                )
            ),
            dateIso = "2026-11-15"
        )

        store.recordQuestionTopic("How should I architect post-quantum encryption for Android?")

        val state = store.getState()
        assertTrue(state.totalLearned >= 2)
        assertTrue(state.totalQuestionsAnswered >= 1)
        assertEquals("2026-11-15", state.lastLearnedDate)
        assertTrue(state.entries.any { it.id == quantumEntry.id })
        assertTrue(state.activeSuggestions.any { it.title == "Build faster agent workflows" })
        assertTrue(state.recentQuestionTopics.any { it.contains("post-quantum") })

        val quantumContext = DailyLearningStore.buildRelevantContextForQuery(
            state = state,
            query = "Tell me about logical qubits and quantum noise"
        )
        assertTrue(quantumContext.contains("Fault-Tolerant Logical Qubits"))
        assertTrue(quantumContext.contains("Modular cryogenic interconnects"))
        assertTrue(quantumContext.contains("Sanskar's Recent Questions"))
    }
}
