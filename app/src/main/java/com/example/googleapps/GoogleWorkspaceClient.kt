package com.example.googleapps

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class GoogleWorkspaceClient(
    private val appsManager: GoogleAppsManager
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun listAppItems(app: GoogleAppIntegration, query: String = ""): String =
        withContext(Dispatchers.IO) {
            val tokenResult = appsManager.requireAuthorizedTokenFor(app)
            val token = tokenResult.getOrElse { return@withContext it.message ?: "Unauthorized." }

            try {
                when (app) {
                    GoogleAppIntegration.GMAIL -> fetchGmailMessages(token, query)
                    GoogleAppIntegration.CALENDAR -> fetchCalendarEvents(token, query)
                    GoogleAppIntegration.DRIVE -> fetchDriveFiles(token, query)
                    GoogleAppIntegration.DOCS -> fetchDriveFiles(token, query, mimeFilter = "application/vnd.google-apps.document")
                    GoogleAppIntegration.SHEETS -> fetchDriveFiles(token, query, mimeFilter = "application/vnd.google-apps.spreadsheet")
                    GoogleAppIntegration.SLIDES -> fetchDriveFiles(token, query, mimeFilter = "application/vnd.google-apps.presentation")
                    GoogleAppIntegration.CONTACTS -> fetchContacts(token)
                    GoogleAppIntegration.TASKS -> fetchTasks(token)
                    GoogleAppIntegration.YOUTUBE -> fetchYouTubeSubscriptions(token)
                }
            } catch (e: Exception) {
                "Error accessing ${app.displayName}: ${e.localizedMessage ?: "Network error"}"
            }
        }

    suspend fun createAppItem(
        app: GoogleAppIntegration,
        title: String,
        details: String = "",
        recipient: String = ""
    ): String = withContext(Dispatchers.IO) {
        val tokenResult = appsManager.requireAuthorizedTokenFor(app)
        val token = tokenResult.getOrElse { return@withContext it.message ?: "Unauthorized." }

        try {
            when (app) {
                GoogleAppIntegration.GMAIL -> sendGmailMessage(token, recipient, title, details)
                GoogleAppIntegration.CALENDAR -> createCalendarEvent(token, title, details)
                GoogleAppIntegration.DOCS -> createGoogleDoc(token, title)
                GoogleAppIntegration.SHEETS -> createGoogleSheet(token, title)
                GoogleAppIntegration.SLIDES -> createGoogleSlide(token, title)
                GoogleAppIntegration.TASKS -> createGoogleTask(token, title, details)
                else -> "Creating items is supported for Gmail, Calendar, Docs, Sheets, Slides, and Tasks."
            }
        } catch (e: Exception) {
            "Error creating in ${app.displayName}: ${e.localizedMessage ?: "Request failed"}"
        }
    }

    private fun fetchGmailMessages(token: String, query: String): String {
        val qParam = if (query.isNotBlank()) "&q=${URLEncoder.encode(query, "UTF-8")}" else ""
        val listJson = getJson(
            "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=5$qParam",
            token
        )
        val messages = listJson.optJSONArray("messages") ?: return "Gmail inbox is empty."
        if (messages.length() == 0) return "No matching Gmail messages found."

        val sb = StringBuilder("Recent Gmail Messages:\n")
        for (i in 0 until messages.length().coerceAtMost(5)) {
            val id = messages.getJSONObject(i).optString("id")
            val msgJson = getJson(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=metadata&metadataHeaders=Subject&metadataHeaders=From",
                token
            )
            val headers = msgJson.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
            var subject = "(No Subject)"
            var from = "Unknown"
            for (h in 0 until headers.length()) {
                val hdr = headers.getJSONObject(h)
                when (hdr.optString("name")) {
                    "Subject" -> subject = hdr.optString("value")
                    "From" -> from = hdr.optString("value")
                }
            }
            val snippet = msgJson.optString("snippet")
            sb.appendLine("• $subject — $from")
            if (snippet.isNotBlank()) sb.appendLine("  $snippet")
        }
        return sb.toString().trim()
    }

    private fun sendGmailMessage(token: String, to: String, subject: String, body: String): String {
        if (to.isBlank()) return "Please specify a recipient email address to send via Gmail."
        val rawMime = "To: $to\r\nSubject: ${subject.ifBlank { "Message from New Era" }}\r\nContent-Type: text/plain; charset=\"UTF-8\"\r\n\r\n$body"
        val encoded = Base64.encodeToString(
            rawMime.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val payload = JSONObject().put("raw", encoded)
        val res = postJson("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", token, payload)
        return "Email sent via Gmail (ID: ${res.optString("id")}) to $to."
    }

    private fun fetchCalendarEvents(token: String, query: String): String {
        val nowIso = isoNowUtc()
        val qParam = if (query.isNotBlank()) "&q=${URLEncoder.encode(query, "UTF-8")}" else ""
        val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events?maxResults=6&singleEvents=true&orderBy=startTime&timeMin=${URLEncoder.encode(nowIso, "UTF-8")}$qParam"
        val json = getJson(url, token)
        val items = json.optJSONArray("items") ?: return "No upcoming Google Calendar events."
        if (items.length() == 0) return "No upcoming Google Calendar events."

        return buildString {
            appendLine("Upcoming Google Calendar Events:")
            for (i in 0 until items.length()) {
                val ev = items.getJSONObject(i)
                val summary = ev.optString("summary", "(Untitled Event)")
                val startObj = ev.optJSONObject("start")
                val start = startObj?.optString("dateTime")?.ifBlank { startObj.optString("date") } ?: ""
                appendLine("• $summary ($start)")
            }
        }.trim()
    }

    private fun createCalendarEvent(token: String, title: String, details: String): String {
        val startMs = System.currentTimeMillis() + 3_600_000L
        val endMs = startMs + 3_600_000L
        val body = JSONObject()
            .put("summary", title.ifBlank { "New Era Event" })
            .put("description", details)
            .put("start", JSONObject().put("dateTime", isoUtc(startMs)))
            .put("end", JSONObject().put("dateTime", isoUtc(endMs)))
        val res = postJson("https://www.googleapis.com/calendar/v3/calendars/primary/events", token, body)
        return "Created Calendar event '${res.optString("summary")}'."
    }

    private fun fetchDriveFiles(token: String, query: String, mimeFilter: String? = null): String {
        val clauses = mutableListOf("trashed=false")
        if (!mimeFilter.isNullOrBlank()) clauses.add("mimeType='$mimeFilter'")
        if (query.isNotBlank()) clauses.add("name contains '${query.replace("'", "\\'")}'")
        val qParam = URLEncoder.encode(clauses.joinToString(" and "), "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?pageSize=8&q=$qParam&fields=files(id,name,mimeType,modifiedTime)"
        val json = getJson(url, token)
        val files = json.optJSONArray("files") ?: return "No files found."
        if (files.length() == 0) return "No matching files found."

        return buildString {
            appendLine("Google Drive Items:")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                appendLine("• ${f.optString("name")} (${f.optString("mimeType").substringAfterLast('.')})")
            }
        }.trim()
    }

    private fun createGoogleDoc(token: String, title: String): String {
        val res = postJson(
            "https://docs.googleapis.com/v1/documents",
            token,
            JSONObject().put("title", title.ifBlank { "New Era Document" })
        )
        return "Created Google Doc '${res.optString("title")}' (ID: ${res.optString("documentId")})."
    }

    private fun createGoogleSheet(token: String, title: String): String {
        val res = postJson(
            "https://sheets.googleapis.com/v4/spreadsheets",
            token,
            JSONObject().put("properties", JSONObject().put("title", title.ifBlank { "New Era Sheet" }))
        )
        return "Created Google Sheet '${title.ifBlank { "New Era Sheet" }}' (ID: ${res.optString("spreadsheetId")})."
    }

    private fun createGoogleSlide(token: String, title: String): String {
        val res = postJson(
            "https://slides.googleapis.com/v1/presentations",
            token,
            JSONObject().put("title", title.ifBlank { "New Era Slides" })
        )
        return "Created Google Slides presentation '${res.optString("title")}' (ID: ${res.optString("presentationId")})."
    }

    private fun fetchContacts(token: String): String {
        val json = getJson(
            "https://people.googleapis.com/v1/people/me/connections?personFields=names,emailAddresses,phoneNumbers&pageSize=10",
            token
        )
        val connections = json.optJSONArray("connections") ?: return "No Google Contacts returned."
        if (connections.length() == 0) return "No Google Contacts found."
        return buildString {
            appendLine("Google Contacts:")
            for (i in 0 until connections.length()) {
                val person = connections.getJSONObject(i)
                val name = person.optJSONArray("names")?.optJSONObject(0)?.optString("displayName") ?: "Contact"
                val email = person.optJSONArray("emailAddresses")?.optJSONObject(0)?.optString("value").orEmpty()
                val phone = person.optJSONArray("phoneNumbers")?.optJSONObject(0)?.optString("value").orEmpty()
                appendLine("• $name ${listOf(email, phone).filter { it.isNotBlank() }.joinToString(" | ")}")
            }
        }.trim()
    }

    private fun fetchTasks(token: String): String {
        val json = getJson("https://tasks.googleapis.com/tasks/v1/lists/@default/tasks?maxResults=10", token)
        val items = json.optJSONArray("items") ?: return "No Google Tasks found."
        if (items.length() == 0) return "Your Google Tasks list is clear."
        return buildString {
            appendLine("Google Tasks:")
            for (i in 0 until items.length()) {
                val t = items.getJSONObject(i)
                val statusMark = if (t.optString("status") == "completed") "[x]" else "[ ]"
                appendLine("$statusMark ${t.optString("title")}")
            }
        }.trim()
    }

    private fun createGoogleTask(token: String, title: String, notes: String): String {
        val payload = JSONObject()
            .put("title", title.ifBlank { "New Era Task" })
            .put("notes", notes)
        val res = postJson("https://tasks.googleapis.com/tasks/v1/lists/@default/tasks", token, payload)
        return "Added Google Task '${res.optString("title")}'."
    }

    private fun fetchYouTubeSubscriptions(token: String): String {
        val json = getJson(
            "https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=8",
            token
        )
        val items = json.optJSONArray("items") ?: return "No YouTube subscriptions returned."
        if (items.length() == 0) return "No YouTube subscriptions found."
        return buildString {
            appendLine("YouTube Subscriptions:")
            for (i in 0 until items.length()) {
                val snippet = items.getJSONObject(i).optJSONObject("snippet")
                appendLine("• ${snippet?.optString("title").orEmpty()}")
            }
        }.trim()
    }

    private fun getJson(url: String, token: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${extractGoogleApiError(raw)}")
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private fun postJson(url: String, token: String, payload: JSONObject): JSONObject {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${extractGoogleApiError(raw)}")
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private fun extractGoogleApiError(raw: String): String {
        return try {
            JSONObject(raw).optJSONObject("error")?.optString("message")
                ?.ifBlank { raw.take(180) } ?: raw.take(180)
        } catch (_: Exception) {
            raw.take(180)
        }
    }

    private fun isoNowUtc(): String = isoUtc(System.currentTimeMillis())

    private fun isoUtc(timeMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(timeMillis))
    }
}
