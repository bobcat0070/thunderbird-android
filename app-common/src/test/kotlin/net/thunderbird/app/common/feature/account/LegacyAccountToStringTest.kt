package net.thunderbird.app.common.feature.account

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import kotlin.test.Test
import net.thunderbird.app.common.feature.mail.FakeData

class LegacyAccountToStringTest {
    @Test
    fun `toString should name the account without its credentials or address`() {
        val testSubject = FakeData.legacyAccount.copy(
            oAuthState = "{\"refresh_token\":\"secret-refresh-token\"}",
            incomingServerSettings = FakeData.legacyAccount.incomingServerSettings.newPassword("secret-password"),
        )

        val result = testSubject.toString()

        assertThat(result).contains(testSubject.id.toString())
        assertThat(result).doesNotContain("secret-refresh-token")
        assertThat(result).doesNotContain("secret-password")
        assertThat(result).doesNotContain("user@example.com")
    }
}
