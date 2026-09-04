package net.thunderbird.backend.graph

import com.fsck.k9.mail.oauth.OAuth2TokenProvider

/**
 * Records how often a token was requested and invalidated, so tests can assert the client refreshed after a rejected
 * token instead of retrying with the same one.
 */
class FakeOAuth2TokenProvider(
    private val tokens: List<String> = listOf("token"),
) : OAuth2TokenProvider {
    val requestedTokens = mutableListOf<String>()
    var invalidateCount = 0
        private set

    override val usernames: Set<String> = emptySet()

    override fun getToken(timeoutMillis: Long): String {
        val token = tokens.getOrElse(requestedTokens.size) { tokens.last() }
        requestedTokens += token

        return token
    }

    override fun invalidateToken() {
        invalidateCount++
    }
}
