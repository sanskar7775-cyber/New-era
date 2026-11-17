package com.example.firebase

import com.google.firebase.FirebaseApp

object FirebaseAvailability {

    fun isInitialized(): Boolean = runCatching {
        FirebaseApp.getInstance()
        true
    }.getOrDefault(false)

    fun isFirebaseInitError(error: Throwable?): Boolean {
        val fullMessage = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        return fullMessage.contains("Default FirebaseApp is not initialized", ignoreCase = true) ||
            (fullMessage.contains("FirebaseApp", ignoreCase = true) &&
                fullMessage.contains("not initialized", ignoreCase = true))
    }

    const val CLOUD_AI_UNAVAILABLE_GUIDANCE =
        "Cloud Gemini AI is inactive in this APK because google-services.json was not bundled at build time. " +
            "Phone control, live web search, encrypted vault, and local web portal work normally. " +
            "To enable Cloud Gemini in GitHub-built APKs, add GOOGLE_SERVICES_JSON to your GitHub Repository Secrets."
}
