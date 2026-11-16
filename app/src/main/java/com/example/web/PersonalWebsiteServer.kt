package com.example.web

import android.util.Log
import com.example.data.model.DecryptedVaultItem
import com.example.data.model.DeviceTelemetry
import com.example.data.model.PersonalWebsite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class PersonalWebsiteServer {

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    @Volatile
    private var currentHtml: String = buildWebsiteHtml(
        website = PersonalWebsite(),
        telemetry = DeviceTelemetry(),
        pinnedItems = emptyList(),
        keyFingerprint = ""
    )

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    val localUrl: String = "http://127.0.0.1:$PORT"

    fun updateHtml(html: String) {
        currentHtml = html
    }

    fun start(scope: CoroutineScope) {
        if (_isRunning.value) return
        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16)
                }
                serverSocket = socket
                _isRunning.value = true
                Log.d(TAG, "Personal website server started at $localUrl")
                while (isActive && !socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (_: Exception) {
                        break
                    }
                    launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local server error", e)
            } finally {
                _isRunning.value = false
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
        _isRunning.value = false
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                // Consume request headers
                var line = reader.readLine()
                while (!line.isNullOrEmpty()) {
                    line = reader.readLine()
                }
                val bodyBytes = currentHtml.toByteArray(Charsets.UTF_8)
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Cache-Control: no-cache\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.UTF_8)

                val out = sock.getOutputStream()
                out.write(headers)
                out.write(bodyBytes)
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "PersonalWebsiteServer"
        const val PORT = 8787

        fun buildWebsiteHtml(
            website: PersonalWebsite,
            telemetry: DeviceTelemetry,
            pinnedItems: List<DecryptedVaultItem>,
            keyFingerprint: String
        ): String {
            val accent = when (website.accentStyle) {
                "obsidian_gold" -> "#FFD166"
                "aurora_violet" -> "#A78BFA"
                else -> "#00E5FF"
            }
            val safeTitle = escapeHtml(website.siteTitle)
            val safeOwner = escapeHtml(website.ownerTag.ifBlank { "Owner: Sanskar" })
            val safeHeadline = escapeHtml(website.headline)
            val safeBio = escapeHtml(website.bio)

            val pinnedHtml = if (pinnedItems.isEmpty()) {
                "<div class='card'><p class='muted'>Pin encrypted AI insights or notes inside New Era to showcase them on Sanskar's dashboard.</p></div>"
            } else {
                pinnedItems.take(6).joinToString("\n") { item ->
                    """
                    <div class='card'>
                      <div class='badge'>${escapeHtml(item.recordType.uppercase())} • E2EE</div>
                      <h3>${escapeHtml(item.title)}</h3>
                      <p>${escapeHtml(item.content.take(260))}</p>
                    </div>
                    """.trimIndent()
                }
            }

            val telemetryHtml = if (website.showTelemetry) {
                """
                <section class='section'>
                  <h2>Live Device Command Telemetry</h2>
                  <div class='grid'>
                    <div class='stat'><span class='label'>Device</span><strong>${escapeHtml(telemetry.deviceModel.ifBlank { "Android Device" })}</strong></div>
                    <div class='stat'><span class='label'>Battery</span><strong>${telemetry.batteryPercent}% ${if (telemetry.isCharging) "⚡" else ""}</strong></div>
                    <div class='stat'><span class='label'>Network</span><strong>${escapeHtml(telemetry.networkStatus)}</strong></div>
                    <div class='stat'><span class='label'>Installed Apps</span><strong>${telemetry.installedAppCount} Accessible</strong></div>
                  </div>
                </section>
                """.trimIndent()
            } else ""

            val aiPortalHtml = if (website.showAiPortal) {
                """
                <section class='section'>
                  <h2>New Era AI Capabilities</h2>
                  <div class='grid'>
                    <div class='card'><h3>Alexa Voice Engine</h3><p>Hands-free speech recognition &amp; natural spoken responses.</p></div>
                    <div class='card'><h3>Live Internet Grounding</h3><p>Real-time web search &amp; webpage intelligence.</p></div>
                    <div class='card'><h3>Phone &amp; App Control</h3><p>System telemetry, hardware toggles &amp; instant app launching.</p></div>
                    <div class='card'><h3>E2EE Cloud Sync</h3><p>AES-256-GCM vault synced across devices${if (keyFingerprint.isNotBlank()) " • Key ${escapeHtml(keyFingerprint)}" else ""}.</p></div>
                  </div>
                </section>
                """.trimIndent()
            } else ""

            return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8" />
                  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                  <meta name="author" content="Sanskar" />
                  <title>$safeTitle</title>
                  <style>
                    :root { --bg: #F8FAFC; --surface: #FFFFFF; --card: #F1F5F9; --accent: #1A73E8; --text: #0F172A; --muted: #475569; --border: #E2E8F0; }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body { background: var(--bg); color: var(--text); font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; line-height: 1.55; padding: 20px 16px 40px; }
                    .container { max-width: 820px; margin: 0 auto; }
                    .topbar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; padding: 14px 16px; background: var(--surface); border: 1px solid var(--border); border-radius: 12px; margin-bottom: 18px; }
                    .brand { display: flex; align-items: center; gap: 10px; font-weight: 700; font-size: 1.05rem; }
                    .logo-s { width: 34px; height: 34px; border-radius: 8px; background: var(--accent); display: grid; place-items: center; color: #FFFFFF; font-weight: 700; font-size: 1.1rem; }
                    .owner-tag { background: #E8F0FE; color: #1967D2; padding: 5px 12px; border-radius: 999px; font-size: 0.8rem; font-weight: 600; }
                    .hero { background: var(--surface); border: 1px solid var(--border); border-radius: 14px; padding: 22px; margin-bottom: 18px; }
                    .hero h1 { font-size: 1.5rem; line-height: 1.3; margin-bottom: 8px; color: var(--text); }
                    .hero p { color: var(--muted); font-size: 0.95rem; }
                    .section { margin-top: 22px; }
                    .section h2 { font-size: 1.05rem; margin-bottom: 12px; color: var(--text); }
                    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; }
                    .card, .stat { background: var(--surface); border: 1px solid var(--border); border-radius: 12px; padding: 14px; }
                    .card h3 { font-size: 0.95rem; margin-bottom: 4px; color: var(--text); }
                    .card p, .muted { color: var(--muted); font-size: 0.88rem; }
                    .stat .label { display: block; color: var(--muted); font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 4px; }
                    .stat strong { font-size: 1.05rem; color: var(--text); }
                    .badge { display: inline-block; font-size: 0.7rem; color: var(--accent); background: #E8F0FE; padding: 2px 8px; border-radius: 6px; margin-bottom: 6px; font-weight: 600; }
                    footer { margin-top: 28px; text-align: center; color: var(--muted); font-size: 0.82rem; padding-top: 14px; border-top: 1px solid var(--border); }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <header class="topbar">
                      <div class="brand">
                        <div class="logo-s">S</div>
                        <div>
                          <div>New Era AI</div>
                          <div style="font-size:0.75rem;color:#9FB0D3;">Personal Web Portal &amp; Dashboard</div>
                        </div>
                      </div>
                      <div class="owner-tag">$safeOwner</div>
                    </header>
                    <section class="hero">
                      <h1>$safeHeadline</h1>
                      <p>$safeBio</p>
                    </section>
                    $telemetryHtml
                    $aiPortalHtml
                    <section class="section">
                      <h2>Pinned Encrypted Dashboard Insights</h2>
                      <div class="grid">$pinnedHtml</div>
                    </section>
                    <footer>
                      <strong>$safeOwner</strong> • Powered by New Era AI • End-to-End Encrypted Sync
                    </footer>
                  </div>
                </body>
                </html>
            """.trimIndent()
        }

        private fun escapeHtml(input: String): String {
            return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
        }
    }
}
