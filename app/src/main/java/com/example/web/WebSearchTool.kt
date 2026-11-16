package com.example.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebSearchTool {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun searchInternet(query: String): String = withContext(Dispatchers.IO) {
        val results = StringBuilder()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")

        // 1. DuckDuckGo Instant Answer API
        try {
            val ddgUrl = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder()
                .url(ddgUrl)
                .header("User-Agent", "NewEraAI/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val abstractText = json.optString("AbstractText")
                        val abstractUrl = json.optString("AbstractURL")
                        val answer = json.optString("Answer")
                        if (answer.isNotBlank()) {
                            results.appendLine("Direct Answer: $answer")
                        }
                        if (abstractText.isNotBlank()) {
                            results.appendLine("Summary: $abstractText")
                            if (abstractUrl.isNotBlank()) results.appendLine("Source: $abstractUrl")
                        }
                        val related = json.optJSONArray("RelatedTopics")
                        if (related != null) {
                            var count = 0
                            for (i in 0 until related.length()) {
                                if (count >= 4) break
                                val item = related.optJSONObject(i) ?: continue
                                val text = item.optString("Text")
                                val firstUrl = item.optString("FirstURL")
                                if (text.isNotBlank()) {
                                    results.appendLine("- $text ($firstUrl)")
                                    count++
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Wikipedia Live Search API (great for factual / current reference)
        try {
            val wikiUrl = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&format=json&srlimit=4&utf8=1"
            val req = Request.Builder()
                .url(wikiUrl)
                .header("User-Agent", "NewEraAI/1.0 (https://sanskar.local)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val searchArray = json.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
                    if (searchArray.length() > 0) {
                        results.appendLine("\nWeb Knowledge Results:")
                        for (i in 0 until searchArray.length()) {
                            val obj = searchArray.getJSONObject(i)
                            val title = obj.optString("title")
                            val snippet = obj.optString("snippet")
                                .replace(Regex("<[^>]*>"), "")
                                .replace("&quot;", "\"")
                                .replace("&amp;", "&")
                            val pageUrl = "https://en.wikipedia.org/wiki/${URLEncoder.encode(title.replace(" ", "_"), "UTF-8")}"
                            results.appendLine("* $title: $snippet [$pageUrl]")
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        val finalOut = results.toString().trim()
        if (finalOut.isNotBlank()) finalOut else "No live search snippets returned for '$query'."
    }

    suspend fun fetchWebPage(url: String): String = withContext(Dispatchers.IO) {
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        try {
            val req = Request.Builder()
                .url(normalized)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) NewEraAI/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext "Failed to fetch $normalized (HTTP ${resp.code})."
                }
                val raw = resp.body?.string().orEmpty()
                val titleMatch = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val cleaned = raw
                    .replace(Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                    .replace(Regex("<style[^>]*>.*?</style>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(5000)
                buildString {
                    if (titleMatch.isNotBlank()) appendLine("Page Title: $titleMatch")
                    appendLine("URL: $normalized")
                    appendLine("Content:")
                    append(cleaned.ifBlank { "Page returned empty readable text." })
                }
            }
        } catch (e: Exception) {
            "Error fetching webpage $normalized: ${e.localizedMessage ?: "Network error"}"
        }
    }
}
