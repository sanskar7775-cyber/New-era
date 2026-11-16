package com.example.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

object OwnerAccountPolicy {
    val ALLOWED_OWNER_EMAILS: Set<String> = setOf(
        "sanskar7775@gmail.com",
        "sanskarghogare076@gmail.com"
    )

    const val OWNER_DISPLAY_NAME = "Sanskar"
    const val OWNER_USERNAME = "legendary"
    const val OWNER_UID = "owner_legendary"

    // SHA-256 of the owner password ("sanskarisgod")
    private const val OWNER_PASSWORD_SHA256 =
        "688c1e4ef12458f48c0aa37f847949b3949c007cb87565772426e97d31e536d2"

    const val UNAUTHORIZED_OWNER_MESSAGE =
        "Access denied: Only verified owner credentials can open the Owner Monitor."

    fun isAuthorizedOwnerEmail(email: String?): Boolean {
        val normalized = email?.trim()?.lowercase(Locale.US).orEmpty()
        return normalized.isNotBlank() && normalized in ALLOWED_OWNER_EMAILS
    }

    fun isReservedOwnerUsername(username: String?): Boolean {
        val normalized = username?.trim()?.lowercase(Locale.US).orEmpty()
        return normalized == OWNER_USERNAME
    }

    fun verifyOwnerCredentials(username: String?, password: String?): Boolean {
        val cleanUser = username?.trim()?.lowercase(Locale.US).orEmpty()
        val cleanPass = password.orEmpty()
        if (cleanUser != OWNER_USERNAME || cleanPass.isEmpty()) return false
        val actualHash = sha256Hex(cleanPass)
        val expectedHash = sha256Hex("sanskarisgod")
        return constantTimeEquals(actualHash, expectedHash)
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
