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
    const val OWNER_UID = "direct_owner_sanskar"
    const val STANDARD_DIRECT_UID = "direct_user_local"
    const val OWNER_CODE_LENGTH = 6

    const val UNAUTHORIZED_OWNER_MESSAGE =
        "Access denied: Only verified owner privileges can open the Owner Monitor."

    fun isAuthorizedOwnerEmail(email: String?): Boolean {
        val normalized = email?.trim()?.lowercase(Locale.US).orEmpty()
        return normalized.isNotBlank() && normalized in ALLOWED_OWNER_EMAILS
    }

    fun isValidSixDigitCode(code: String?): Boolean {
        val clean = code?.trim().orEmpty()
        return clean.length == OWNER_CODE_LENGTH && clean.all { it.isDigit() }
    }

    fun defaultOwnerCodeSha256(): String = sha256Hex("777777")

    fun verifyOwnerCode(code: String?, customCodeSha256: String? = null): Boolean {
        val clean = code?.trim().orEmpty()
        if (!isValidSixDigitCode(clean)) return false
        val actualHash = sha256Hex(clean)
        val expectedHash = customCodeSha256?.takeIf { it.isNotBlank() } ?: defaultOwnerCodeSha256()
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
