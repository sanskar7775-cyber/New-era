package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.googleapps.GoogleAppIntegration
import com.example.googleapps.GoogleOAuthSession

@Composable
fun GoogleAppsPermissionSection(
    permittedApps: Set<GoogleAppIntegration>,
    oauthSession: GoogleOAuthSession,
    previewResult: String?,
    onToggleApp: (GoogleAppIntegration, Boolean) -> Unit,
    onToggleAll: (Boolean) -> Unit,
    onAuthorizeGoogleApps: () -> Unit,
    onPreviewApp: (GoogleAppIntegration) -> Unit,
    onClearPreview: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (oauthSession.isAuthorized) Icons.Filled.CheckCircle else Icons.Filled.Security,
                            contentDescription = "Google OAuth authorization status",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Google Workspace Access",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (oauthSession.isAuthorized) {
                                    "Authorized (${permittedApps.size}/${GoogleAppIntegration.entries.size} apps allowed)"
                                } else {
                                    "Toggle permissions below, then tap Authorize"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onAuthorizeGoogleApps,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("authorize_google_apps_button")
                    ) {
                        Text(if (oauthSession.isAuthorized) "Re-Authorize Selected" else "Authorize Google Apps")
                    }
                    OutlinedButton(onClick = { onToggleAll(true) }) {
                        Text("Allow All")
                    }
                    TextButton(onClick = { onToggleAll(false) }) {
                        Text("Deny All")
                    }
                }
            }
        }

        if (!previewResult.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = previewResult,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onClearPreview,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss Google app preview")
                    }
                }
            }
        }

        GoogleAppIntegration.entries.forEach { app ->
            val allowed = app in permittedApps
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = app.capabilitySummary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (allowed) {
                        TextButton(
                            onClick = { onPreviewApp(app) },
                            modifier = Modifier.testTag("preview_google_${app.id}")
                        ) {
                            Text("Read")
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Switch(
                        checked = allowed,
                        onCheckedChange = { onToggleApp(app, it) },
                        modifier = Modifier.testTag("permission_switch_${app.id}")
                    )
                }
            }
        }
    }
}
