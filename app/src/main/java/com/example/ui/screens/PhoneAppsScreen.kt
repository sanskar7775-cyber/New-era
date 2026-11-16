package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.data.model.DeviceTelemetry
import com.example.data.model.InstalledAppInfo
import com.example.googleapps.GoogleAppIntegration
import com.example.googleapps.GoogleOAuthSession

@Composable
fun PhoneAppsScreen(
    telemetry: DeviceTelemetry,
    installedApps: List<InstalledAppInfo>,
    flashlightOn: Boolean,
    permittedGoogleApps: Set<GoogleAppIntegration> = GoogleAppIntegration.entries.toSet(),
    googleOAuthSession: GoogleOAuthSession = GoogleOAuthSession(),
    googleAppPreviewResult: String? = null,
    onToggleGoogleApp: (GoogleAppIntegration, Boolean) -> Unit = { _, _ -> },
    onToggleAllGoogleApps: (Boolean) -> Unit = {},
    onAuthorizeGoogleApps: () -> Unit = {},
    onPreviewGoogleApp: (GoogleAppIntegration) -> Unit = {},
    onClearGooglePreview: () -> Unit = {},
    onRefresh: () -> Unit,
    onToggleFlashlight: () -> Unit,
    onOpenSettings: (String) -> Unit,
    onStartTimer: () -> Unit,
    onOpenDialer: () -> Unit,
    onOpenCalendar: () -> Unit,
    onLaunchApp: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeSection by remember { mutableIntStateOf(0) }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
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
                    Column {
                        Text(
                            text = "Apps & Google Permissions",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "${telemetry.deviceModel} • ${telemetry.androidVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("refresh_telemetry_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh device telemetry and apps")
                    }
                }
            }

            item {
                TabRow(selectedTabIndex = activeSection) {
                    Tab(
                        selected = activeSection == 0,
                        onClick = { activeSection = 0 },
                        text = { Text("Phone & Apps (${installedApps.size})") },
                        modifier = Modifier.testTag("tab_phone_apps")
                    )
                    Tab(
                        selected = activeSection == 1,
                        onClick = { activeSection = 1 },
                        text = { Text("Google Apps (${permittedGoogleApps.size})") },
                        modifier = Modifier.testTag("tab_google_apps")
                    )
                }
            }

            if (activeSection == 1) {
                item {
                    GoogleAppsPermissionSection(
                        permittedApps = permittedGoogleApps,
                        oauthSession = googleOAuthSession,
                        previewResult = googleAppPreviewResult,
                        onToggleApp = onToggleGoogleApp,
                        onToggleAll = onToggleAllGoogleApps,
                        onAuthorizeGoogleApps = onAuthorizeGoogleApps,
                        onPreviewApp = onPreviewGoogleApp,
                        onClearPreview = onClearGooglePreview
                    )
                }
            } else {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TelemetryStat("Battery", "${telemetry.batteryPercent}%${if (telemetry.isCharging) " ⚡" else ""}")
                            TelemetryStat("Network", telemetry.networkStatus)
                            TelemetryStat("Free Storage", telemetry.availableStorageGb)
                            TelemetryStat("Media Vol", "${telemetry.mediaVolumePercent}%")
                        }
                    }
                }

                item {
                    Text(
                        text = "Phone Hardware & System Controls",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PhoneActionChip(
                            icon = if (flashlightOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                            label = if (flashlightOn) "Torch ON" else "Torch OFF",
                            onClick = onToggleFlashlight,
                            testTag = "action_flashlight"
                        )
                        PhoneActionChip(
                            icon = Icons.Filled.Wifi,
                            label = "Wi-Fi",
                            onClick = { onOpenSettings("wifi") },
                            testTag = "action_wifi"
                        )
                        PhoneActionChip(
                            icon = Icons.Filled.Bluetooth,
                            label = "Bluetooth",
                            onClick = { onOpenSettings("bluetooth") },
                            testTag = "action_bluetooth"
                        )
                        PhoneActionChip(
                            icon = Icons.Filled.Timer,
                            label = "1m Timer",
                            onClick = onStartTimer,
                            testTag = "action_timer"
                        )
                        PhoneActionChip(
                            icon = Icons.Filled.Call,
                            label = "Dialer",
                            onClick = onOpenDialer,
                            testTag = "action_dialer"
                        )
                        PhoneActionChip(
                            icon = Icons.Filled.CalendarMonth,
                            label = "Calendar",
                            onClick = onOpenCalendar,
                            testTag = "action_calendar"
                        )
                        PhoneActionChip(
                            icon = Icons.Filled.Settings,
                            label = "Settings",
                            onClick = { onOpenSettings("main") },
                            testTag = "action_settings"
                        )
                    }
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "All Installed Apps (${installedApps.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            placeholder = { Text("Search any app on your phone...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_apps_input")
                        )
                    }
                }

                items(filteredApps, key = { it.packageName }) { app ->
                    AppRowCard(
                        app = app,
                        onClick = { onLaunchApp(app.packageName) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun PhoneActionChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    testTag: String
) {
    AssistChip(
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp)) },
        label = { Text(label) },
        modifier = Modifier.testTag(testTag)
    )
}

@Composable
private fun AppRowCard(
    app: InstalledAppInfo,
    onClick: () -> Unit
) {
    val iconBitmap = remember(app.packageName) {
        try {
            app.icon?.toBitmap(96, 96)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("installed_app_row")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = "${app.appName} icon",
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = app.appName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.Launch,
                contentDescription = "Launch ${app.appName}",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
