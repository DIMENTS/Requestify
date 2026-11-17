package nl.diments.requestify.core.auth

/** What the token provider answers for an API call. */
sealed interface TokenResult {
    data class Ok(val token: String) : TokenResult

    /** Not logged in (never, or logged out). */
    data object LoggedOut : TokenResult

    /** The saved login is dead (Spotify refused it): only a new login helps. */
    data object LoginNeeded : TokenResult

    /** No token right now (offline, Spotify down or busy); the login is kept and a later call tries again. */
    data object Unavailable : TokenResult
}
