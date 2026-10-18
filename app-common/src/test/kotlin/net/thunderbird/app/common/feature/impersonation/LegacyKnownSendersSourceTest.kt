package net.thunderbird.app.common.feature.impersonation

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.fsck.k9.mailstore.recipients.RecipientIndex
import net.thunderbird.core.android.account.fake.FakeLegacyAccountManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LegacyKnownSendersSourceTest {
    private val recipientIndex = RecipientIndex(RuntimeEnvironment.getApplication())
    private val domainStore = FakeVerifiedSenderDomainStore()

    private val testSubject = LegacyKnownSendersSource(
        accountManager = FakeLegacyAccountManager(),
        recipientIndex = recipientIndex,
        verifiedSenderDomainStore = domainStore,
    )

    @Test
    fun `verified domains should be the ones saved by the last scan`() {
        domainStore.save(setOf("shop.example", "bank.example"))

        val knownSenders = testSubject.knownSenders()

        assertThat(knownSenders.verifiedSenderDomains).isEqualTo(setOf("shop.example", "bank.example"))
    }

    @Test
    fun `verified domains should be empty before the first scan`() {
        val knownSenders = testSubject.knownSenders()

        assertThat(knownSenders.verifiedSenderDomains).isEmpty()
    }

    @Test
    fun `someone just written to should be known without waiting for a scan`() {
        recipientIndex.recordSent("Alice@Example.org", "Alice Smith", at = 1_000L)

        val knownSenders = testSubject.knownSenders()

        assertThat(knownSenders.correspondentAddresses).contains("alice@example.org")
        assertThat(knownSenders.addressesByName["Alice Smith"].orEmpty()).contains("alice@example.org")
    }
}

internal class FakeVerifiedSenderDomainStore : VerifiedSenderDomainStore {
    private var domains: Set<String>? = null

    override fun domains(): Set<String>? = domains

    override fun save(domains: Set<String>) {
        this.domains = domains
    }
}
