package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.components.OwnerSanskarTag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSyncSheet(
    keyFingerprint: String,
    hasCloudBundle: Boolean,
    needsPassphraseUnlock: Boolean,
    recoveryKeyProvider: () -> String,
    onSetPassphrase: (String) -> Unit,
    onUnlockPassphrase: (String) -> Unit,
    onImportRecoveryKey: (String) -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var passphraseInput by remember { mutableStateOf("") }
    var recoveryInput by remember { mutableStateOf("") }
    var copiedNotice by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "E2EE Cross-Device Vault",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Fingerprint: ${keyFingerprint.ifBlank { "Initializing..." }}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                OwnerSanskarTag()
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (needsPassphraseUnlock) {
                            "Unlock Cloud Vault Key"
                        } else if (hasCloudBundle) {
                            "Cross-Device Passphrase Active (Update or Unlock)"
                        } else {
                            "Enable Cross-Device Passphrase Backup"
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Your AES-256-GCM master key is wrapped locally via PBKDF2-HMAC-SHA256 before syncing. Enter a passphrase to link all your phones and tablets seamlessly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = passphraseInput,
                        onValueChange = { passphraseInput = it },
                        label = { Text("Sync Passphrase (min 4 chars)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sync_passphrase_input")
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasCloudBundle) {
                            OutlinedButton(
                                onClick = {
                                    onUnlockPassphrase(passphraseInput)
                                    passphraseInput = ""
                                },
                                enabled = passphraseInput.length >= 4,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("unlock_passphrase_button")
                            ) {
                                Text("Unlock Device")
                            }
                        }
                        Button(
                            onClick = {
                                onSetPassphrase(passphraseInput)
                                passphraseInput = ""
                            },
                            enabled = passphraseInput.length >= 4,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_passphrase_button")
                        ) {
                            Text("Sync Key")
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Direct Recovery Key",
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedButton(
                        onClick = {
                            val code = recoveryKeyProvider()
                            if (code.isNotBlank()) {
                                clipboard.setText(AnnotatedString(code))
                                copiedNotice = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("copy_recovery_key_button")
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (copiedNotice) "Copied 256-bit Key!" else "Copy 256-bit Recovery Key")
                    }
                    OutlinedTextField(
                        value = recoveryInput,
                        onValueChange = { recoveryInput = it },
                        label = { Text("Paste Recovery Key from Another Device") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            onImportRecoveryKey(recoveryInput)
                            recoveryInput = ""
                        },
                        enabled = recoveryInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Recovery Key")
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Automated Setup & APK Distribution",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "To export and install the signed APK on your other devices, use AI Studio's Settings > Generate APK menu. Tap below to email your quick setup summary and recovery info.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            val mailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("mailto:sanskar7775@gmail.com")
                                putExtra(Intent.EXTRA_SUBJECT, "New Era AI — Setup & E2EE Sync Info (Owner: Sanskar)")
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "New Era AI Setup Summary\nOwner: sanskar7775@gmail.com\nE2EE Key Fingerprint: $keyFingerprint\nLocal Portal URL: http://127.0.0.1:8787"
                                )
                            }
                            runCatching { context.startActivity(Intent.createChooser(mailIntent, "Email Setup Summary")) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("email_setup_summary_button")
                    ) {
                        Icon(Icons.Filled.Email, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Email Setup to sanskar7775@gmail.com")
                    }
                }
            }

            HorizontalDivider()

            Button(
                onClick = onSignOut,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("sign_out_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out")
            }
        }
    }
}
