package com.example.data.model

import android.graphics.drawable.Drawable
import com.example.ai.engine.ReasoningTrace
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val ownerName: String = "Sanskar",
    val aiName: String = "New Era",
    val websiteTitle: String = "New Era • Sanskar's AI Portal",
    val websiteTagline: String = "Owner: Sanskar • Encrypted AI Command Center",
    val keyFingerprint: String = "",
    val voiceReplyEnabled: Boolean = true,
    val autoSetupCompleted: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class EncryptedRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val recordType: String = "note",
    val ciphertext: String = "",
    val iv: String = "",
    val keyVersion: Long = 1L,
    val pinned: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PersonalWebsite(
    @DocumentId val id: String = "",
    val userId: String = "",
    val ownerTag: String = "Owner: Sanskar",
    val siteTitle: String = "New Era — Sanskar",
    val headline: String = "Welcome to Sanskar's Personal AI & Command Hub",
    val bio: String = "Built on New Era AI with internet grounding, full device & app access, Alexa-grade voice dialogue, and end-to-end encrypted cross-device synchronization.",
    val accentStyle: String = "cyber_cyan",
    val showTelemetry: Boolean = true,
    val showAiPortal: Boolean = true,
    val publishedHtml: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class VaultBundle(
    @DocumentId val id: String = "",
    val userId: String = "",
    val saltBase64: String = "",
    val wrappedKeyBase64: String = "",
    val ivBase64: String = "",
    val keyFingerprint: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class DecryptedVaultItem(
    val id: String,
    val recordType: String,
    val title: String,
    val content: String,
    val sourceLabel: String,
    val pinned: Boolean,
    val isDecrypted: Boolean,
    val timestampMillis: Long
)

data class ChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val toolsUsed: List<String> = emptyList(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val reasoningTrace: ReasoningTrace? = null
)

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null
)

data class DeviceTelemetry(
    val deviceModel: String = "",
    val androidVersion: String = "",
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val networkStatus: String = "Online",
    val availableStorageGb: String = "0.0 GB",
    val mediaVolumePercent: Int = 70,
    val installedAppCount: Int = 0
)

enum class AiToolKind(
    val title: String,
    val subtitle: String,
    val placeholder: String
) {
    WEB_BRIEF(
        title = "Web Research Brief",
        subtitle = "Live internet search + cited synthesis",
        placeholder = "Topic or question to research live on the web..."
    ),
    SUMMARIZER(
        title = "Smart Summarizer",
        subtitle = "Distill long text, articles, or notes",
        placeholder = "Paste text, article notes, or URL to summarize..."
    ),
    CODE_ARCHITECT(
        title = "Code & Tech Master",
        subtitle = "Debug, generate, or optimize any code",
        placeholder = "Describe the code, bug, or architecture task..."
    ),
    TRANSLATOR(
        title = "Polyglot Translator",
        subtitle = "Natural translation across 50+ languages",
        placeholder = "Enter text and target language (e.g., Translate to Hindi / Spanish)..."
    ),
    ACTION_PLANNER(
        title = "Structured Planner",
        subtitle = "JSON-validated step-by-step action plan",
        placeholder = "Goal or project (e.g., Launch my personal portfolio site)..."
    ),
    EMAIL_WRITER(
        title = "Executive Composer",
        subtitle = "Draft polished emails, posts, & replies",
        placeholder = "Who is it for and what key points should be included?"
    )
}
