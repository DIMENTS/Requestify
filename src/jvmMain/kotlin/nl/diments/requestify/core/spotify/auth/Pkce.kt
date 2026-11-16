package nl.diments.requestify.core.spotify.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object Pkce {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val VERIFIER_LENGTH = 64

    fun verifier(random: SecureRandom = SecureRandom()): String =
        buildString(VERIFIER_LENGTH) { repeat(VERIFIER_LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun challenge(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
