package com.example.learning

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ai.NewEraAiService
import com.example.web.WebSearchTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

private val Context.learningDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "new_era_daily_learning"
)

data class DailySuggestion(
    val id: String,
    val title: String,
    val actionPrompt: String,
    val category: String = "Daily Insight"
)

data class DailyLearningEntry(
    val id: String,
    val dateIso: String,
    val dayNumber: Int,
    val topic: String,
    val headline: String,
    val keyTakeaway: String,
    val deepSummary: String,
    val sources: List<String> = emptyList(),
    val suggestions: List<DailySuggestion> = emptyList(),
    val timestampMillis: Long = System.currentTimeMillis()
)

data class DailyLearningState(
    val autoLearnDaily: Boolean = true,
    val streakDays: Int = 0,
    val totalLearned: Int = 0,
    val totalQuestionsAnswered: Int = 0,
    val knowledgeLevel: String = "Apprentice",
    val lastLearnedDate: String = "",
    val preferredTopics: List<String> = DEFAULT_TOPICS,
    val masteredDomains: List<String> = emptyList(),
    val recentQuestionTopics: List<String> = emptyList(),
    val activeSuggestions: List<DailySuggestion> = DEFAULT_SUGGESTIONS,
    val entries: List<DailyLearningEntry> = emptyList()
) {
    companion object {
        val DEFAULT_TOPICS = listOf(
            "Artificial Intelligence & Autonomous Agents",
            "Software Architecture & Mobile Engineering",
            "Productivity Workflows & Decision Frameworks",
            "Cybersecurity & Applied Cryptography",
            "Quantum Computing & Future Hardware",
            "Global Technology & Science Breakthroughs",
            "Personal Finance & Smart Investing Concepts"
        )

        val DEFAULT_SUGGESTIONS = listOf(
            DailySuggestion(
                id = "def_1",
                title = "Today's top AI & tech brief",
                actionPrompt = "Search the internet for today's biggest AI & tech breakthroughs and suggest 2 practical ways I can use them.",
                category = "Web Brief"
            ),
            DailySuggestion(
                id = "def_2",
                title = "Optimize my daily workflow",
                actionPrompt = "Give me 3 smart, actionable productivity suggestions for today based on what you know.",
                category = "Suggestion"
            )
        )
    }
}

class DailyLearningStore(private val context: Context) {

    private val appContext = context.applicationContext

    val stateFlow: Flow<DailyLearningState> = appContext.learningDataStore.data.map { prefs ->
        val raw = prefs[KEY_STATE_JSON]
        if (raw.isNullOrBlank()) {
            DailyLearningState()
        } else {
            runCatching { decodeState(raw) }
                .getOrDefault(DailyLearningState())
        }
    }

    suspend fun getState(): DailyLearningState = stateFlow.first()

    suspend fun setAutoLearnDaily(enabled: Boolean) {
        updateState { it.copy(autoLearnDaily = enabled) }
    }

    suspend fun updatePreferredTopics(topics: List<String>) {
        val cleaned = topics.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (cleaned.isNotEmpty()) {
            updateState { it.copy(preferredTopics = cleaned) }
        }
    }

    suspend fun recordQuestionTopic(userPrompt: String) {
        val cleaned = extractCompactTopic(userPrompt)
        if (cleaned.isBlank()) return
        updateState { current ->
            val updatedTopics = (listOf(cleaned) + current.recentQuestionTopics.filterNot {
                it.equals(cleaned, ignoreCase = true)
            }).take(18)
            val nextQuestions = current.totalQuestionsAnswered + 1
            val nextLevel = computeKnowledgeLevel(current.totalLearned, nextQuestions)
            current.copy(
                totalQuestionsAnswered = nextQuestions,
                knowledgeLevel = nextLevel,
                recentQuestionTopics = updatedTopics
            )
        }
    }

    suspend fun recordLesson(
        topic: String,
        headline: String,
        keyTakeaway: String,
        deepSummary: String,
        sources: List<String>,
        suggestions: List<DailySuggestion> = emptyList(),
        dateIso: String = todayIsoDate()
    ): DailyLearningEntry {
        lateinit var savedEntry: DailyLearningEntry
        updateState { current ->
            val nextTotal = current.totalLearned + 1
            val nextStreak = computeUpdatedStreak(
                previousDateIso = current.lastLearnedDate,
                todayDateIso = dateIso,
                currentStreak = current.streakDays
            )
            val nextLevel = computeKnowledgeLevel(nextTotal, current.totalQuestionsAnswered)
            savedEntry = DailyLearningEntry(
                id = "learn_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                dateIso = dateIso,
                dayNumber = nextTotal,
                topic = topic,
                headline = headline,
                keyTakeaway = keyTakeaway,
                deepSummary = deepSummary,
                sources = sources,
                suggestions = suggestions,
                timestampMillis = System.currentTimeMillis()
            )
            val updatedDomains = (listOf(topic.trim()) + current.masteredDomains)
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.US) }
                .take(24)
            val nextSuggestions = if (suggestions.isNotEmpty()) {
                (suggestions + current.activeSuggestions)
                    .distinctBy { it.title.lowercase(Locale.US) }
                    .take(6)
            } else {
                current.activeSuggestions
            }
            current.copy(
                streakDays = nextStreak,
                totalLearned = nextTotal,
                knowledgeLevel = nextLevel,
                lastLearnedDate = dateIso,
                masteredDomains = updatedDomains,
                activeSuggestions = nextSuggestions,
                entries = (listOf(savedEntry) + current.entries).take(120)
            )
        }
        return savedEntry
    }

    private suspend fun updateState(transform: (DailyLearningState) -> DailyLearningState) {
        appContext.learningDataStore.edit { prefs ->
            val current = prefs[KEY_STATE_JSON]?.let { raw ->
                runCatching { decodeState(raw) }.getOrNull()
            } ?: DailyLearningState()
            val updated = transform(current)
            prefs[KEY_STATE_JSON] = encodeState(updated)
        }
    }

    private fun encodeState(state: DailyLearningState): String {
        val root = JSONObject().apply {
            put("autoLearnDaily", state.autoLearnDaily)
            put("streakDays", state.streakDays)
            put("totalLearned", state.totalLearned)
            put("totalQuestionsAnswered", state.totalQuestionsAnswered)
            put("knowledgeLevel", state.knowledgeLevel)
            put("lastLearnedDate", state.lastLearnedDate)
            put("preferredTopics", JSONArray(state.preferredTopics))
            put("masteredDomains", JSONArray(state.masteredDomains))
            put("recentQuestionTopics", JSONArray(state.recentQuestionTopics))
            put("activeSuggestions", encodeSuggestions(state.activeSuggestions))
            val entriesArray = JSONArray()
            state.entries.forEach { entry ->
                entriesArray.put(
                    JSONObject().apply {
                        put("id", entry.id)
                        put("dateIso", entry.dateIso)
                        put("dayNumber", entry.dayNumber)
                        put("topic", entry.topic)
                        put("headline", entry.headline)
                        put("keyTakeaway", entry.keyTakeaway)
                        put("deepSummary", entry.deepSummary)
                        put("sources", JSONArray(entry.sources))
                        put("suggestions", encodeSuggestions(entry.suggestions))
                        put("timestampMillis", entry.timestampMillis)
                    }
                )
            }
            put("entries", entriesArray)
        }
        return root.toString()
    }

    private fun encodeSuggestions(items: List<DailySuggestion>): JSONArray {
        return JSONArray().apply {
            items.forEach { s ->
                put(
                    JSONObject().apply {
                        put("id", s.id)
                        put("title", s.title)
                        put("actionPrompt", s.actionPrompt)
                        put("category", s.category)
                    }
                )
            }
        }
    }

    private fun decodeSuggestions(arr: JSONArray?): List<DailySuggestion> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = o.optString("title").trim()
            val prompt = o.optString("actionPrompt").trim()
            if (title.isBlank() || prompt.isBlank()) return@mapNotNull null
            DailySuggestion(
                id = o.optString("id").ifBlank { "sug_$i" },
                title = title,
                actionPrompt = prompt,
                category = o.optString("category", "Suggestion")
            )
        }
    }

    private fun decodeState(raw: String): DailyLearningState {
        val obj = JSONObject(raw)
        val topics = decodeStringList(obj.optJSONArray("preferredTopics"))
            .ifEmpty { DailyLearningState.DEFAULT_TOPICS }
        val mastered = decodeStringList(obj.optJSONArray("masteredDomains"))
        val questionTopics = decodeStringList(obj.optJSONArray("recentQuestionTopics"))
        val activeSuggestions = decodeSuggestions(obj.optJSONArray("activeSuggestions"))
            .ifEmpty { DailyLearningState.DEFAULT_SUGGESTIONS }

        val entriesArr = obj.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until entriesArr.length()).mapNotNull { i ->
            val e = entriesArr.optJSONObject(i) ?: return@mapNotNull null
            DailyLearningEntry(
                id = e.optString("id"),
                dateIso = e.optString("dateIso"),
                dayNumber = e.optInt("dayNumber", 1),
                topic = e.optString("topic"),
                headline = e.optString("headline"),
                keyTakeaway = e.optString("keyTakeaway"),
                deepSummary = e.optString("deepSummary"),
                sources = decodeStringList(e.optJSONArray("sources")),
                suggestions = decodeSuggestions(e.optJSONArray("suggestions")),
                timestampMillis = e.optLong("timestampMillis", System.currentTimeMillis())
            )
        }
        return DailyLearningState(
            autoLearnDaily = obj.optBoolean("autoLearnDaily", true),
            streakDays = obj.optInt("streakDays", 0),
            totalLearned = obj.optInt("totalLearned", 0),
            totalQuestionsAnswered = obj.optInt("totalQuestionsAnswered", 0),
            knowledgeLevel = obj.optString("knowledgeLevel", "Apprentice"),
            lastLearnedDate = obj.optString("lastLearnedDate", ""),
            preferredTopics = topics,
            masteredDomains = mastered,
            recentQuestionTopics = questionTopics,
            activeSuggestions = activeSuggestions,
            entries = entries
        )
    }

    private fun decodeStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).trim().takeIf { it.isNotBlank() }
        }
    }

    companion object {
        private val KEY_STATE_JSON = stringPreferencesKey("daily_learning_state_v1")

        fun todayIsoDate(nowMillis: Long = System.currentTimeMillis()): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return sdf.format(Date(nowMillis))
        }

        fun computeKnowledgeLevel(totalLessons: Int, totalQuestions: Int = 0): String {
            val score = totalLessons * 3 + totalQuestions
            return when {
                totalLessons >= 30 || score >= 120 -> "Visionary"
                totalLessons >= 15 || score >= 60 -> "Master"
                totalLessons >= 7 || score >= 28 -> "Specialist"
                totalLessons >= 3 || score >= 12 -> "Scholar"
                totalLessons >= 1 || score >= 3 -> "Explorer"
                else -> "Apprentice"
            }
        }

        fun computeUpdatedStreak(
            previousDateIso: String?,
            todayDateIso: String,
            currentStreak: Int
        ): Int {
            if (previousDateIso.isNullOrBlank()) return 1
            if (previousDateIso == todayDateIso) return currentStreak.coerceAtLeast(1)
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                val prev = sdf.parse(previousDateIso) ?: return 1
                val today = sdf.parse(todayDateIso) ?: return 1
                val diffDays = TimeUnit.MILLISECONDS.toDays(
                    zeroOutTime(today) - zeroOutTime(prev)
                )
                if (diffDays == 1L) currentStreak.coerceAtLeast(1) + 1 else 1
            } catch (_: Exception) {
                1
            }
        }

        private fun zeroOutTime(date: Date): Long {
            val cal = Calendar.getInstance().apply {
                time = date
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return cal.timeInMillis
        }

        fun extractCompactTopic(userPrompt: String): String {
            val clean = userPrompt.trim().replace(Regex("\\s+"), " ")
            if (clean.length < 8) return ""
            return clean.take(90)
        }

        fun buildMemoryPromptContext(state: DailyLearningState, maxEntries: Int = 5): String {
            return buildRelevantContextForQuery(state, query = "", maxEntries = maxEntries)
        }

        fun buildRelevantContextForQuery(
            state: DailyLearningState,
            query: String,
            maxEntries: Int = 5
        ): String {
            if (state.entries.isEmpty() && state.recentQuestionTopics.isEmpty()) return ""

            val queryTokens = query.lowercase(Locale.US)
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 3 }
                .toSet()

            val prioritizedEntries = if (queryTokens.isEmpty()) {
                state.entries.take(maxEntries)
            } else {
                val scored = state.entries.mapIndexed { idx, entry ->
                    val haystack = "${entry.topic} ${entry.headline} ${entry.keyTakeaway} ${entry.deepSummary}"
                        .lowercase(Locale.US)
                    val keywordMatches = queryTokens.count { token -> haystack.contains(token) }
                    val recencyBonus = if (idx < 2) 1 else 0
                    entry to (keywordMatches * 10 + recencyBonus)
                }.sortedByDescending { it.second }
                scored.take(maxEntries).map { it.first }
            }

            return buildString {
                appendLine(
                    "New Era Evolving Intelligence Profile (Level: ${state.knowledgeLevel}, " +
                        "Daily Web Lessons: ${state.totalLearned}, Questions Learned From: ${state.totalQuestionsAnswered}, Streak: ${state.streakDays}d):"
                )
                if (state.masteredDomains.isNotEmpty()) {
                    appendLine("Mastered Web Domains: ${state.masteredDomains.take(10).joinToString(", ")}")
                }
                if (state.recentQuestionTopics.isNotEmpty()) {
                    appendLine("Sanskar's Recent Questions & Interests: ${state.recentQuestionTopics.take(5).joinToString(" | ")}")
                }
                if (prioritizedEntries.isNotEmpty()) {
                    appendLine("Relevant Accumulated Knowledge:")
                    prioritizedEntries.forEach { entry ->
                        val detail = if (queryTokens.isNotEmpty() && entry.deepSummary.isNotBlank()) {
                            "${entry.keyTakeaway} — ${entry.deepSummary}"
                        } else {
                            entry.keyTakeaway
                        }
                        appendLine("- [Day ${entry.dayNumber} | ${entry.dateIso} | ${entry.topic}] ${entry.headline}: $detail")
                    }
                }
                appendLine(
                    "Guidance: Give Sanskar a sharp, accurate answer leveraging these learned insights (plus live web_search if needed), and end advisory or open-ended answers with 1 brief personalized next-step suggestion."
                )
            }.trim()
        }
    }
}

class DailyLearningEngine(
    private val store: DailyLearningStore,
    private val webSearchTool: WebSearchTool,
    private val aiService: NewEraAiService
) {
    suspend fun learnNewTopicFromInternet(
        customTopic: String? = null
    ): DailyLearningEntry {
        val state = store.getState()
        val chosenTopic = pickAdaptiveTopic(state, customTopic)

        val today = DailyLearningStore.todayIsoDate()
        val liveSnippets = webSearchTool.searchInternet("$chosenTopic latest breakthroughs trends $today")
        val previousHeadlines = state.entries.take(12).map { it.headline }

        val lesson = aiService.synthesizeDailyLesson(
            topic = chosenTopic,
            todayDate = today,
            liveWebSnippets = liveSnippets,
            previousHeadlines = previousHeadlines,
            userInterests = state.recentQuestionTopics.take(6)
        )

        val dailySuggestions = lesson.suggestions.mapIndexed { idx, pair ->
            DailySuggestion(
                id = "sug_${System.currentTimeMillis()}_$idx",
                title = pair.first,
                actionPrompt = pair.second,
                category = chosenTopic.substringBefore("&").trim().ifBlank { "Smart Idea" }
            )
        }

        return store.recordLesson(
            topic = chosenTopic,
            headline = lesson.headline,
            keyTakeaway = lesson.keyTakeaway,
            deepSummary = lesson.deepSummary,
            sources = lesson.sources,
            suggestions = dailySuggestions,
            dateIso = today
        )
    }

    suspend fun ensureLearnedTodayIfEnabled(): DailyLearningEntry? {
        val state = store.getState()
        val today = DailyLearningStore.todayIsoDate()
        if (!state.autoLearnDaily || state.lastLearnedDate == today) return null
        return learnNewTopicFromInternet()
    }

    private fun pickAdaptiveTopic(state: DailyLearningState, customTopic: String?): String {
        val explicit = customTopic?.trim()?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit

        val baseTopics = state.preferredTopics.ifEmpty { DailyLearningState.DEFAULT_TOPICS }
        // Every 2nd day, blend with Sanskar's recent question interests so the AI specifically studies what he cares about
        val recentInterest = state.recentQuestionTopics.firstOrNull()
        if (!recentInterest.isNullOrBlank() && state.totalLearned % 2 == 1) {
            return "Practical advances related to: $recentInterest"
        }
        return baseTopics[state.totalLearned % baseTopics.size]
    }
}
