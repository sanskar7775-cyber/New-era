package com.example.ai.engine

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

private val Context.graphMemoryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "new_era_graph_memory"
)

enum class MemoryNodeType(val label: String) {
    CORE_PREFERENCE("Preference"),
    EPISODIC_EVENT("Episodic"),
    SEMANTIC_CONCEPT("Semantic"),
    PROCEDURAL_RULE("Rule"),
    PROJECT_STATE("Project State")
}

data class GraphMemoryNode(
    val id: String,
    val nodeType: MemoryNodeType,
    val label: String,
    val summary: String,
    val tags: List<String> = emptyList(),
    val weight: Float = 1.0f,
    val accessCount: Int = 1,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

data class GraphMemoryEdge(
    val fromId: String,
    val toId: String,
    val relation: String
)

data class GraphMemoryState(
    val nodes: List<GraphMemoryNode> = emptyList(),
    val edges: List<GraphMemoryEdge> = emptyList()
)

class HierarchicalMemoryGraphStore(private val context: Context) {

    private val appContext = context.applicationContext

    val stateFlow: Flow<GraphMemoryState> = appContext.graphMemoryDataStore.data.map { prefs ->
        val raw = prefs[KEY_GRAPH_JSON]
        if (raw.isNullOrBlank()) GraphMemoryState()
        else runCatching { decode(raw) }.getOrDefault(GraphMemoryState())
    }

    suspend fun getState(): GraphMemoryState = stateFlow.first()

    suspend fun addOrReinforceNode(
        nodeType: MemoryNodeType,
        label: String,
        summary: String,
        tags: List<String> = emptyList(),
        relatedToNodeId: String? = null,
        relation: String = "RELATES_TO"
    ): GraphMemoryNode {
        val cleanLabel = ConstitutionalGuardrailEngine.redactSecrets(label.trim()).take(80)
        val cleanSummary = ConstitutionalGuardrailEngine.redactSecrets(summary.trim()).take(320)
        require(cleanLabel.isNotBlank()) { "Memory node label cannot be blank." }

        val cleanTags = tags.map { it.trim().lowercase(Locale.US) }
            .filter { it.length >= 2 }
            .distinct()
            .take(8)
            .ifEmpty { extractKeywords("$cleanLabel $cleanSummary") }

        lateinit var savedNode: GraphMemoryNode
        appContext.graphMemoryDataStore.edit { prefs ->
            val current = prefs[KEY_GRAPH_JSON]?.let { runCatching { decode(it) }.getOrNull() }
                ?: GraphMemoryState()

            val existing = current.nodes.firstOrNull {
                it.label.equals(cleanLabel, ignoreCase = true) && it.nodeType == nodeType
            }

            val now = System.currentTimeMillis()
            savedNode = if (existing != null) {
                existing.copy(
                    summary = cleanSummary.ifBlank { existing.summary },
                    tags = (existing.tags + cleanTags).distinct().take(10),
                    weight = (existing.weight + 0.35f).coerceAtMost(10f),
                    accessCount = existing.accessCount + 1,
                    updatedAtMillis = now
                )
            } else {
                GraphMemoryNode(
                    id = "mem_${UUID.randomUUID().toString().replace("-", "").take(10)}",
                    nodeType = nodeType,
                    label = cleanLabel,
                    summary = cleanSummary,
                    tags = cleanTags,
                    weight = 1.2f,
                    accessCount = 1,
                    updatedAtMillis = now
                )
            }

            val updatedNodes = (listOf(savedNode) + current.nodes.filterNot { it.id == savedNode.id })
                .take(250)

            val updatedEdges = current.edges.toMutableList()
            if (!relatedToNodeId.isNullOrBlank() && relatedToNodeId != savedNode.id) {
                val edge = GraphMemoryEdge(savedNode.id, relatedToNodeId, relation)
                if (edge !in updatedEdges) updatedEdges.add(0, edge)
            } else {
                // Connect automatically to strongest overlapping semantic tag node
                val linked = current.nodes.firstOrNull { other ->
                    other.id != savedNode.id && other.tags.any { it in savedNode.tags }
                }
                if (linked != null) {
                    val edge = GraphMemoryEdge(savedNode.id, linked.id, "SHARES_CONTEXT")
                    if (edge !in updatedEdges) updatedEdges.add(0, edge)
                }
            }

            prefs[KEY_GRAPH_JSON] = encode(
                GraphMemoryState(
                    nodes = updatedNodes,
                    edges = updatedEdges.take(400)
                )
            )
        }
        return savedNode
    }

    suspend fun ingestInteraction(
        userPrompt: String,
        assistantAnswer: String
    ) {
        val cleanPrompt = userPrompt.trim()
        if (cleanPrompt.length < 10) return
        val lower = cleanPrompt.lowercase(Locale.US)
        val type = when {
            lower.contains("prefer") || lower.contains("remember") || lower.contains("always") ->
                MemoryNodeType.CORE_PREFERENCE
            lower.contains("build") || lower.contains("project") || lower.contains("app") ->
                MemoryNodeType.PROJECT_STATE
            lower.contains("how to") || lower.contains("rule") || lower.contains("step") ->
                MemoryNodeType.PROCEDURAL_RULE
            else -> MemoryNodeType.EPISODIC_EVENT
        }
        val compactLabel = cleanPrompt.take(56).trim()
        val compactAnswer = assistantAnswer.trim().take(200)
        addOrReinforceNode(
            nodeType = type,
            label = compactLabel,
            summary = compactAnswer,
            tags = extractKeywords(cleanPrompt)
        )
    }

    suspend fun deleteNode(nodeId: String) {
        appContext.graphMemoryDataStore.edit { prefs ->
            val current = prefs[KEY_GRAPH_JSON]?.let { runCatching { decode(it) }.getOrNull() }
                ?: return@edit
            prefs[KEY_GRAPH_JSON] = encode(
                GraphMemoryState(
                    nodes = current.nodes.filterNot { it.id == nodeId },
                    edges = current.edges.filterNot { it.fromId == nodeId || it.toId == nodeId }
                )
            )
        }
    }

    companion object {
        private val KEY_GRAPH_JSON = stringPreferencesKey("hierarchical_graph_memory_v1")

        fun extractKeywords(text: String): List<String> {
            val stop = setOf("the", "and", "for", "with", "that", "this", "what", "how", "can", "you", "are", "was")
            return text.lowercase(Locale.US)
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 3 && it !in stop }
                .distinct()
                .take(8)
        }

        fun retrieveSubgraphContext(
            state: GraphMemoryState,
            query: String,
            maxNodes: Int = 6
        ): String {
            if (state.nodes.isEmpty()) return ""
            val tokens = extractKeywords(query).toSet()
            val scored = state.nodes.map { node ->
                val text = "${node.label} ${node.summary} ${node.tags.joinToString(" ")}".lowercase(Locale.US)
                val overlap = if (tokens.isEmpty()) 1 else tokens.count { text.contains(it) }
                val priorityBoost = when (node.nodeType) {
                    MemoryNodeType.CORE_PREFERENCE, MemoryNodeType.PROCEDURAL_RULE -> 2.0f
                    MemoryNodeType.PROJECT_STATE -> 1.5f
                    else -> 1.0f
                }
                val score = (overlap * 8.0f) + (node.weight * priorityBoost)
                node to score
            }.sortedByDescending { it.second }
                .take(maxNodes)
                .map { it.first }

            return buildString {
                appendLine("Hierarchical Graph Memory (${state.nodes.size} nodes, ${state.edges.size} edges):")
                scored.forEach { n ->
                    appendLine("- [${n.nodeType.label}] ${n.label}: ${n.summary}")
                }
            }.trim()
        }
    }

    private fun encode(state: GraphMemoryState): String {
        val nodesArr = JSONArray().apply {
            state.nodes.forEach { n ->
                put(JSONObject().apply {
                    put("id", n.id)
                    put("nodeType", n.nodeType.name)
                    put("label", n.label)
                    put("summary", n.summary)
                    put("tags", JSONArray(n.tags))
                    put("weight", n.weight.toDouble())
                    put("accessCount", n.accessCount)
                    put("updatedAtMillis", n.updatedAtMillis)
                })
            }
        }
        val edgesArr = JSONArray().apply {
            state.edges.forEach { e ->
                put(JSONObject().apply {
                    put("fromId", e.fromId)
                    put("toId", e.toId)
                    put("relation", e.relation)
                })
            }
        }
        return JSONObject().apply {
            put("nodes", nodesArr)
            put("edges", edgesArr)
        }.toString()
    }

    private fun decode(raw: String): GraphMemoryState {
        val root = JSONObject(raw)
        val nArr = root.optJSONArray("nodes") ?: JSONArray()
        val nodes = (0 until nArr.length()).mapNotNull { i ->
            val o = nArr.optJSONObject(i) ?: return@mapNotNull null
            val tArr = o.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until tArr.length()).mapNotNull { t -> tArr.optString(t).takeIf { it.isNotBlank() } }
            val type = runCatching { MemoryNodeType.valueOf(o.optString("nodeType")) }
                .getOrDefault(MemoryNodeType.SEMANTIC_CONCEPT)
            GraphMemoryNode(
                id = o.optString("id"),
                nodeType = type,
                label = o.optString("label"),
                summary = o.optString("summary"),
                tags = tags,
                weight = o.optDouble("weight", 1.0).toFloat(),
                accessCount = o.optInt("accessCount", 1),
                updatedAtMillis = o.optLong("updatedAtMillis", 0L)
            )
        }
        val eArr = root.optJSONArray("edges") ?: JSONArray()
        val edges = (0 until eArr.length()).mapNotNull { i ->
            val o = eArr.optJSONObject(i) ?: return@mapNotNull null
            GraphMemoryEdge(
                fromId = o.optString("fromId"),
                toId = o.optString("toId"),
                relation = o.optString("relation", "RELATES_TO")
            )
        }
        return GraphMemoryState(nodes = nodes, edges = edges)
    }
}
