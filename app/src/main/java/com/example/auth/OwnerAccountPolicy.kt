package com.example.auth

import java.util.Locale

object OwnerAccountPolicy {
    val ALLOWED_OWNER_EMAILS: Set<String> = setOf(
        "sanskar7775@gmail.com",
        "sanskarghogare076@gmail.com"
    )

    const val OWNER_DISPLAY_NAME = "Sanskar"

    const val UNAUTHORIZED_OWNER_MESSAGE =
        "Access denied: Only verified owner Google accounts can open the Owner Monitor."

    fun isAuthorizedOwnerEmail(email: String?): Boolean {
        val normalized = email?.trim()?.lowercase(Locale.US).orEmpty()
        return normalized.isNotBlank() && normalized in ALLOWED_OWNER_EMAILS
    }
}
