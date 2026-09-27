package com.fsck.k9.mailstore

import com.fsck.k9.Preferences
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.core.common.mail.Protocols

/**
 * How many recent messages the learned server name is judged over.
 */
private const val SAMPLE_SIZE = 50

/**
 * The fewest messages a name is learned from.
 */
private const val MINIMUM_SAMPLES = 20

/**
 * The share of recent messages whose topmost header must carry one name for it to be learned. A server that
 * stamps its mail stamps every message; the headers a sender writes, or a list server added on the way, are a
 * scattering of other names well below this.
 */
private const val REQUIRED_SHARE = 0.9

private const val KEY_SUFFIX = "authenticationServerId"

private const val GOOGLE_AUTHENTICATION_SERVER = "mx.google.com"

/**
 * Which receiving server's `Authentication-Results` an account believes.
 *
 * Any sender can write that header into their own message, and a mailbox server that adds none of its own leaves
 * the forgery on top. RFC 8601 section 5 answers this by having the reader trust only headers naming a server it
 * was told about, by its authserv-id. Nobody tells the app that, so it is known up front for the providers whose
 * name is fixed and otherwise learned from the mail itself: a server that stamps its mail puts the same name on
 * top of every message. A server that stamps nothing never produces such a name, and then nothing is believed -
 * no brand logos and no ticks, which is the safe way round.
 */
interface AuthenticationServerTrust {
    /**
     * @return the authserv-id this account's server writes, or `null` while none is known.
     */
    fun trustedServerId(accountUuid: String): String?

    /**
     * Records the headers of a message that just arrived, to learn from.
     */
    fun observe(accountUuid: String, headerValues: List<String>)
}

internal class DefaultAuthenticationServerTrust(
    private val preferences: Preferences,
    private val accountManager: LegacyAccountDtoManager,
) : AuthenticationServerTrust {
    private val lock = Any()

    /**
     * Recent topmost server names per account, `null` for a message without the header. Kept in memory: after a
     * restart learning simply starts again, and only a change of the learned name is ever written.
     */
    private val samples = mutableMapOf<String, ArrayDeque<String?>>()

    override fun trustedServerId(accountUuid: String): String? {
        return preferences.storage.getStringOrNull(key(accountUuid)) ?: knownServerId(accountUuid)
    }

    override fun observe(accountUuid: String, headerValues: List<String>) {
        val learned = synchronized(lock) {
            val recent = samples.getOrPut(accountUuid) { ArrayDeque() }
            recent.addLast(headerValues.firstOrNull()?.let(::authenticationServerIdOf))
            if (recent.size > SAMPLE_SIZE) recent.removeFirst()

            recent.dominantName()
        } ?: return

        if (learned != preferences.storage.getStringOrNull(key(accountUuid))) {
            preferences.createStorageEditor().putString(key(accountUuid), learned).commit()
        }
    }

    private fun ArrayDeque<String?>.dominantName(): String? {
        val mostCommon = filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }

        return mostCommon
            ?.takeIf { size >= MINIMUM_SAMPLES && it.value >= size * REQUIRED_SHARE }
            ?.key
    }

    /**
     * The name for providers where it is fixed, so their accounts are trusted from the first message rather than
     * after the fiftieth.
     */
    private fun knownServerId(accountUuid: String): String? {
        val server = accountManager.getAccount(accountUuid)?.incomingServerSettings ?: return null
        val host = server.host?.lowercase().orEmpty()

        return when {
            server.type == Protocols.GRAPH -> UNNAMED_AUTHENTICATION_SERVER
            MICROSOFT_HOSTS.any { host == it || host.endsWith(".$it") } -> UNNAMED_AUTHENTICATION_SERVER
            GOOGLE_HOSTS.any { host == it || host.endsWith(".$it") } -> GOOGLE_AUTHENTICATION_SERVER
            else -> null
        }
    }

    private fun key(accountUuid: String) = "$accountUuid.$KEY_SUFFIX"

    private companion object {
        val MICROSOFT_HOSTS = listOf("office365.com", "outlook.com", "hotmail.com", "live.com")
        val GOOGLE_HOSTS = listOf("gmail.com", "googlemail.com")
    }
}
