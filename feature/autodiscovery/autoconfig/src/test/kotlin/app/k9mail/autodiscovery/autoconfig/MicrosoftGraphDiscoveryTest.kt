package app.k9mail.autodiscovery.autoconfig

import app.k9mail.autodiscovery.api.AutoDiscoveryResult
import app.k9mail.autodiscovery.api.AutoDiscoveryResult.NoUsableSettingsFound
import app.k9mail.autodiscovery.api.GraphServerSettings
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.core.common.mail.toUserEmailAddress
import net.thunderbird.core.common.net.toDomain

class MicrosoftGraphDiscoveryTest {
    private val mxResolver = MockMxResolver()
    private val testSubject = MicrosoftGraphDiscovery(
        mxResolver = SuspendableMxResolver(mxResolver),
    )

    @Test
    fun `domain hosted on Exchange Online should be configured for Graph`() = runTest {
        val emailAddress = "user@company.example".toUserEmailAddress()
        mxResolver.addResult("company-example.mail.protection.outlook.com".toDomain())

        val result = testSubject.discover(emailAddress)

        val settings = assertIsSettings(result)
        assertThat(settings.incomingServerSettings).isInstanceOf<GraphServerSettings>()
            .transform { it.hostname.value }
            .isEqualTo("graph.microsoft.com")
    }

    @Test
    fun `incoming and outgoing settings should be the same Graph configuration`() = runTest {
        val emailAddress = "user@company.example".toUserEmailAddress()
        mxResolver.addResult("company-example.mail.protection.outlook.com".toDomain())

        val result = testSubject.discover(emailAddress)

        val settings = assertIsSettings(result)
        assertThat(settings.outgoingServerSettings).isSameInstanceAs(settings.incomingServerSettings)
    }

    @Test
    fun `username should be the full email address`() = runTest {
        val emailAddress = "user@company.example".toUserEmailAddress()
        mxResolver.addResult("company-example.mail.protection.outlook.com".toDomain())

        val result = testSubject.discover(emailAddress)

        val settings = assertIsSettings(result)
        assertThat(settings.incomingServerSettings).isInstanceOf<GraphServerSettings>()
            .transform { it.username }
            .isEqualTo("user@company.example")
    }

    @Test
    fun `Exchange Online should be detected even when it is not the first MX record`() = runTest {
        // Domains migrated to Microsoft 365 often keep MX records of the previous provider.
        val emailAddress = "user@company.example".toUserEmailAddress()
        mxResolver.addResult(
            listOf(
                "mx00.previousprovider.example".toDomain(),
                "company-example.mail.protection.outlook.com".toDomain(),
            ),
        )

        val result = testSubject.discover(emailAddress)

        assertIsSettings(result)
    }

    @Test
    fun `Outlook_com address should be configured for Graph without an MX lookup`() = runTest {
        val emailAddress = "user@outlook.com".toUserEmailAddress()

        val result = testSubject.discover(emailAddress)

        assertIsSettings(result)
        assertThat(mxResolver.callCount).isEqualTo(0)
    }

    @Test
    fun `domain hosted elsewhere should not be configured for Graph`() = runTest {
        val emailAddress = "user@company.example".toUserEmailAddress()
        mxResolver.addResult("mx.emailprovider.example".toDomain())

        val result = testSubject.discover(emailAddress)

        assertThat(result).isEqualTo(NoUsableSettingsFound)
    }

    @Test
    fun `domain without MX records should not be configured for Graph`() = runTest {
        val emailAddress = "user@company.example".toUserEmailAddress()
        mxResolver.addResult(emptyList())

        val result = testSubject.discover(emailAddress)

        assertThat(result).isEqualTo(NoUsableSettingsFound)
    }

    @Test
    fun `result should not be trusted when the MX lookup was not trusted`() = runTest {
        val emailAddress = "user@company.example".toUserEmailAddress()
        mxResolver.addResult("company-example.mail.protection.outlook.com".toDomain(), isTrusted = false)

        val result = testSubject.discover(emailAddress)

        assertThat(assertIsSettings(result).isTrusted).isFalse()
    }

    @Test
    fun `result from a trusted MX lookup should be trusted`() = runTest {
        val emailAddress = "user@company.example".toUserEmailAddress()
        mxResolver.addResult("company-example.mail.protection.outlook.com".toDomain(), isTrusted = true)

        val result = testSubject.discover(emailAddress)

        assertThat(assertIsSettings(result).isTrusted).isTrue()
    }

    /**
     * Runs the single runnable the discovery provides.
     */
    private suspend fun MicrosoftGraphDiscovery.discover(
        emailAddress: net.thunderbird.core.common.mail.EmailAddress,
    ): AutoDiscoveryResult {
        val runnables = initDiscovery(emailAddress)
        assertThat(runnables).hasSize(1)

        return runnables.first().run()
    }

    private fun assertIsSettings(result: AutoDiscoveryResult): AutoDiscoveryResult.Settings {
        assertThat(result).isInstanceOf<AutoDiscoveryResult.Settings>()

        return result as AutoDiscoveryResult.Settings
    }
}
