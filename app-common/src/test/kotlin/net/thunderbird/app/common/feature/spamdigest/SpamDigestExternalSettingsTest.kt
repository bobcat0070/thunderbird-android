package net.thunderbird.app.common.feature.spamdigest

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.fsck.k9.preferences.ExternalSettingKeys.SPAM_ALERT_ACCOUNTS_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.SPAM_DIGEST_EXCLUDED_ACCOUNTS_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.SPAM_DIGEST_SENDER_ACCOUNT_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.SPAM_DIGEST_SEND_TIME_KEY
import net.thunderbird.core.android.testing.RobolectricTest
import net.thunderbird.feature.spamdigest.SpamDigestAccount
import net.thunderbird.feature.spamdigest.SpamDigestAccounts
import net.thunderbird.feature.spamdigest.SpamDigestSettings
import net.thunderbird.feature.spamdigest.SpamDigestSettingsRepository
import net.thunderbird.feature.spamdigest.SpamDigestTime
import net.thunderbird.feature.spamdigest.SpamFolderBackgroundSync
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class SpamDigestExternalSettingsTest : RobolectricTest() {
    private val work = SpamDigestAccount(id = "work-id", name = "Work", email = "Me@Work.example")
    private val home = SpamDigestAccount(id = "home-id", name = "Home", email = "me@home.example")

    private var deviceAccounts = listOf(work, home)
    private val accounts = SpamDigestAccounts { deviceAccounts }
    private val repository = FakeRepository()
    private val backgroundSync = FakeBackgroundSync()
    private val pendingImport =
        PendingSpamDigestImport(RuntimeEnvironment.getApplication(), repository, accounts, backgroundSync)
    private val testSubject = SpamDigestExternalSettings(repository, accounts, pendingImport)

    @Test
    fun `export should name accounts by address, not by id`() {
        repository.current = SpamDigestSettings(
            senderAccountId = "work-id",
            excludedAccountIds = setOf("home-id"),
            sendTime = SpamDigestTime(hour = 6, minute = 30),
            alertAccountIds = setOf("work-id"),
        )

        assertThat(testSubject.exportSettings()).isEqualTo(
            mapOf(
                SPAM_DIGEST_SENDER_ACCOUNT_KEY to "me@work.example",
                SPAM_DIGEST_SEND_TIME_KEY to "06:30",
                SPAM_DIGEST_EXCLUDED_ACCOUNTS_KEY to """["me@home.example"]""",
                SPAM_ALERT_ACCOUNTS_KEY to """["me@work.example"]""",
            ),
        )
    }

    @Test
    fun `import should apply to the accounts with those addresses, whatever their ids here`() {
        deviceAccounts = listOf(work.copy(id = "new-work-id"), home.copy(id = "new-home-id"))
        backgroundSync.knownAccounts += "new-work-id"

        testSubject.importSettings(exportedValues())

        assertThat(repository.current).isEqualTo(
            SpamDigestSettings(
                senderAccountId = "new-work-id",
                excludedAccountIds = setOf("new-home-id"),
                sendTime = SpamDigestTime(hour = 6, minute = 30),
                alertAccountIds = setOf("new-work-id"),
            ),
        )
    }

    @Test
    fun `import before the accounts exist should apply once they do`() {
        deviceAccounts = emptyList()

        testSubject.importSettings(exportedValues())
        assertThat(repository.current.senderAccountId).isEqualTo(null)
        assertThat(repository.current.sendTime).isEqualTo(SpamDigestTime(hour = 6, minute = 30))

        deviceAccounts = listOf(work, home)
        pendingImport.applyAvailable()
        assertThat(repository.current.senderAccountId).isEqualTo("work-id")
        assertThat(repository.current.alertAccountIds).isEqualTo(emptySet())

        // The alert waits for the spam folder, since turning it on also has that folder synced.
        backgroundSync.knownAccounts += "work-id"
        pendingImport.applyAvailable()
        assertThat(repository.current.alertAccountIds).isEqualTo(setOf("work-id"))
    }

    @Test
    fun `values of the wrong shape should be ignored`() {
        testSubject.importSettings(
            mapOf(
                SPAM_DIGEST_SENDER_ACCOUNT_KEY to "not an address",
                SPAM_DIGEST_SEND_TIME_KEY to "25:99",
                SPAM_DIGEST_EXCLUDED_ACCOUNTS_KEY to "{broken",
            ),
        )

        assertThat(repository.current).isEqualTo(FakeRepository().current)
    }

    private fun exportedValues() = mapOf(
        SPAM_DIGEST_SENDER_ACCOUNT_KEY to "me@work.example",
        SPAM_DIGEST_SEND_TIME_KEY to "06:30",
        SPAM_DIGEST_EXCLUDED_ACCOUNTS_KEY to """["me@home.example"]""",
        SPAM_ALERT_ACCOUNTS_KEY to """["me@work.example"]""",
    )

    private class FakeRepository : SpamDigestSettingsRepository {
        var current = SpamDigestSettings(null, emptySet(), SpamDigestTime.DEFAULT)

        override fun getSettings(): SpamDigestSettings = current

        override fun setAccountIncluded(accountId: String, included: Boolean) {
            val excluded = current.excludedAccountIds
            current = current.copy(excludedAccountIds = if (included) excluded - accountId else excluded + accountId)
        }

        override fun setSenderAccount(accountId: String?) {
            current = current.copy(senderAccountId = accountId)
        }

        override fun setSendTime(time: SpamDigestTime) {
            current = current.copy(sendTime = time)
        }

        override fun setAlertEnabled(accountId: String, enabled: Boolean) {
            val alerts = current.alertAccountIds
            current = current.copy(alertAccountIds = if (enabled) alerts + accountId else alerts - accountId)
        }
    }

    private class FakeBackgroundSync : SpamFolderBackgroundSync {
        val knownAccounts = mutableSetOf<String>()

        override fun isSyncEnabled(accountId: String): Boolean? = if (accountId in knownAccounts) false else null

        override fun setSyncEnabled(accountId: String, enabled: Boolean) = Unit
    }
}
