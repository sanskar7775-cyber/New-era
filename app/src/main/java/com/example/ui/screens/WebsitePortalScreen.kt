package com.example.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.DecryptedVaultItem
import com.example.data.model.DeviceTelemetry
import com.example.data.model.PersonalWebsite
import com.example.ui.SyncUiState
import com.example.ui.components.OwnerSanskarTag
import com.example.web.PersonalWebsiteServer

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebsitePortalScreen(
    websiteState: SyncUiState<PersonalWebsite?>,
    telemetry: DeviceTelemetry,
    pinnedItems: List<DecryptedVaultItem>,
    keyFingerprint: String,
    isServerRunning: Boolean,
    localServerUrl: String,
    onToggleServer: (Boolean) -> Unit,
    onSaveWebsite: (String, String, String, String, Boolean, Boolean) -> Unit
) {
    val context = LocalContext.current
    val remoteSite = (websiteState as? SyncUiState.Success)?.data ?: PersonalWebsite()

    var selectedTab by remember { mutableIntStateOf(0) }
    var siteTitle by remember(remoteSite.siteTitle) { mutableStateOf(remoteSite.siteTitle) }
    var headline by remember(remoteSite.headline) { mutableStateOf(remoteSite.headline) }
    var bio by remember(remoteSite.bio) { mutableStateOf(remoteSite.bio) }
    var accentStyle by remember(remoteSite.accentStyle) { mutableStateOf(remoteSite.accentStyle) }
    var showTelemetry by remember(remoteSite.showTelemetry) { mutableStateOf(remoteSite.showTelemetry) }
    var showAiPortal by remember(remoteSite.showAiPortal) { mutableStateOf(remoteSite.showAiPortal) }

    val previewHtml = remember(siteTitle, headline, bio, accentStyle, showTelemetry, showAiPortal, telemetry, pinnedItems, keyFingerprint) {
        PersonalWebsiteServer.buildWebsiteHtml(
            website = PersonalWebsite(
                ownerTag = "Owner: Sanskar",
                siteTitle = siteTitle,
                headline = headline,
                bio = bio,
                accentStyle = accentStyle,
                showTelemetry = showTelemetry,
                showAiPortal = showAiPortal
            ),
            telemetry = telemetry,
            pinnedItems = pinnedItems,
            keyFingerprint = keyFingerprint
        )
    }

    LaunchedEffect(Unit) {
        if (!isServerRunning) {
            onToggleServer(true)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Personal Website & Portal",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Hosted locally & synced across devices",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OwnerSanskarTag()
                }
            }

            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Language,
                                    contentDescription = "Local Web Server",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = if (isServerRunning) "Web Server Live" else "Web Server Paused",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = localServerUrl,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Switch(
                                checked = isServerRunning,
                                onCheckedChange = onToggleServer,
                                modifier = Modifier.testTag("toggle_web_server_switch")
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(localServerUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    runCatching { context.startActivity(intent) }
                                },
                                enabled = isServerRunning,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("open_website_browser_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Browser")
                            }
                            OutlinedButton(
                                onClick = {
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/html"
                                        putExtra(Intent.EXTRA_SUBJECT, "$siteTitle (Owner: Sanskar)")
                                        putExtra(Intent.EXTRA_TEXT, previewHtml)
                                    }
                                    runCatching { context.startActivity(Intent.createChooser(share, "Export Website HTML")) }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("export_website_html_button")
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Export HTML")
                            }
                        }
                    }
                }
            }

            item {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Live Website") },
                        modifier = Modifier.testTag("tab_website_preview")
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Customize & Sync") },
                        modifier = Modifier.testTag("tab_website_editor")
                    )
                }
            }

            if (selectedTab == 0) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(520.dp)
                            .clip(RoundedCornerShape(18.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    webViewClient = WebViewClient()
                                    loadDataWithBaseURL(localServerUrl, previewHtml, "text/html", "UTF-8", null)
                                }
                            },
                            update = { webView ->
                                webView.loadDataWithBaseURL(localServerUrl, previewHtml, "text/html", "UTF-8", null)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Website Attribution: Owner: Sanskar (Locked)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            OutlinedTextField(
                                value = siteTitle,
                                onValueChange = { siteTitle = it },
                                label = { Text("Website Title") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("site_title_input")
                            )
                            OutlinedTextField(
                                value = headline,
                                onValueChange = { headline = it },
                                label = { Text("Hero Headline") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("site_headline_input")
                            )
                            OutlinedTextField(
                                value = bio,
                                onValueChange = { bio = it },
                                label = { Text("Personal Bio / Overview") },
                                minLines = 3,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("site_bio_input")
                            )

                            Text(
                                text = "Portal Accent Theme",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(
                                    "cyber_cyan" to "Cyber Cyan",
                                    "obsidian_gold" to "Obsidian Gold",
                                    "aurora_violet" to "Aurora Violet"
                                ).forEach { (key, label) ->
                                    FilterChip(
                                        selected = accentStyle == key,
                                        onClick = { accentStyle = key },
                                        label = { Text(label) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Show Live Device Telemetry")
                                Switch(checked = showTelemetry, onCheckedChange = { showTelemetry = it })
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Show AI Capabilities Section")
                                Switch(checked = showAiPortal, onCheckedChange = { showAiPortal = it })
                            }

                            Button(
                                onClick = {
                                    onSaveWebsite(
                                        siteTitle,
                                        headline,
                                        bio,
                                        accentStyle,
                                        showTelemetry,
                                        showAiPortal
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("save_website_button")
                            ) {
                                Icon(Icons.Filled.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Publish & Sync Website")
                            }
                        }
                    }
                }
            }
        }
    }
}
