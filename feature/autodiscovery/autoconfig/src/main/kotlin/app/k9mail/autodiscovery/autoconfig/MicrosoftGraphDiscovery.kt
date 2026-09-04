package app.k9mail.autodiscovery.autoconfig

import app.k9mail.autodiscovery.api.AutoDiscovery
import app.k9mail.autodiscovery.api.AutoDiscoveryResult
import app.k9mail.autodiscovery.api.AutoDiscoveryResult.NoUsableSettingsFound
import app.k9mail.autodiscovery.api.AutoDiscoveryRunnable
import app.k9mail.autodiscovery.api.GraphServerSettings
import java.io.IOException
import net.thunderbird.core.common.mail.EmailAddress
import net.thunderbird.core.common.mail.toDomain
import net.thunderbird.core.common.net.Domain
import net.thunderbird.core.common.net.toHostname
import net.thunderbird.legacy.logging.Log
import org.minidns.dnsname.InvalidDnsNameException

/**
 * Host name of the Microsoft Graph API. Used as the account host name and as the key for the OAuth configuration.
 */
private const val GRAPH_HOSTNAME = "graph.microsoft.com"

/**
 * Suffix of the MX host names Exchange Online assigns to a Microsoft 365 domain, e.g.
 * `contoso-com.mail.protection.outlook.com`.
 */
private const val EXCHANGE_ONLINE_MX_SUFFIX = ".mail.protection.outlook.com"

/**
 * Domains served by consumer Outlook.com accounts, which are reachable through Graph as well.
 */
private val MICROSOFT_CONSUMER_DOMAINS = setOf(
    "outlook.com",
    "hotmail.com",
    "live.com",
    "msn.com",
    "passport.com",
)

/**
 * Detects mailboxes hosted on Microsoft 365 or Outlook.com and configures them to use the Microsoft Graph API.
 *
 * Graph is preferred over IMAP and SMTP for these accounts because Microsoft 365 tenants routinely have IMAP and SMTP
 * AUTH disabled, in which case a conventional configuration cannot connect at all.
 *
 * Detection uses the domain MX records rather than an account lookup service, so only the domain is disclosed, never
 * the address being configured.
 */
class MicrosoftGraphDiscovery internal constructor(
    private val mxResolver: SuspendableMxResolver,
) : AutoDiscovery {

    override fun initDiscovery(email: EmailAddress): List<AutoDiscoveryRunnable> {
        return listOf(
            AutoDiscoveryRunnable {
                discover(email)
            },
        )
    }

    private suspend fun discover(email: EmailAddress): AutoDiscoveryResult {
        val domain = email.domain.toDomain()

        if (domain.isMicrosoftConsumerDomain()) {
            return graphSettings(email, isTrusted = true, source = "Outlook.com domain")
        }

        // A domain migrated to Microsoft 365 often keeps the MX records of its previous provider, so every MX host is
        // checked rather than only the most preferred one.
        val mxLookupResult = mxLookup(domain)
        val isExchangeOnline = mxLookupResult != null && mxLookupResult.mxNames.any { it.isExchangeOnlineMx() }

        return if (isExchangeOnline) {
            graphSettings(
                email = email,
                isTrusted = mxLookupResult.isTrusted,
                source = "MX lookup for ${domain.value}",
            )
        } else {
            NoUsableSettingsFound
        }
    }

    private fun graphSettings(email: EmailAddress, isTrusted: Boolean, source: String): AutoDiscoveryResult {
        val serverSettings = GraphServerSettings(
            hostname = GRAPH_HOSTNAME.toHostname(),
            username = email.address,
        )

        return AutoDiscoveryResult.Settings(
            incomingServerSettings = serverSettings,
            outgoingServerSettings = serverSettings,
            isTrusted = isTrusted,
            source = source,
        )
    }

    private suspend fun mxLookup(domain: Domain): MxLookupResult? {
        return try {
            mxResolver.lookup(domain).takeIf { it.mxNames.isNotEmpty() }
        } catch (e: IOException) {
            Log.d(e, "Failed to get MX record for domain: %s", domain.value)
            null
        } catch (e: InvalidDnsNameException) {
            Log.d(e, "Invalid DNS name for domain: %s", domain.value)
            null
        }
    }

    private fun Domain.isMicrosoftConsumerDomain(): Boolean = value.lowercase() in MICROSOFT_CONSUMER_DOMAINS

    private fun Domain.isExchangeOnlineMx(): Boolean = value.lowercase().endsWith(EXCHANGE_ONLINE_MX_SUFFIX)
}

fun createMicrosoftGraphDiscovery(): MicrosoftGraphDiscovery {
    return MicrosoftGraphDiscovery(
        mxResolver = SuspendableMxResolver(MiniDnsMxResolver()),
    )
}
