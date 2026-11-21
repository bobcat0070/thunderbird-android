package net.thunderbird.app.common.feature.settings

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.preferences.ExternalSettingKeys.PLUS_ADDRESS_ALLOWED_SENDERS_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.PLUS_ADDRESS_IGNORED_KEY
import net.thunderbird.core.android.testing.RobolectricTest
import net.thunderbird.feature.plusaddress.PlusAddressDecisionSnapshot
import net.thunderbird.feature.plusaddress.PlusAddressSettings
import org.junit.Test

// org.json, which the export format is written with, needs the Android runtime.
class PlusAddressExternalSettingsTest : RobolectricTest() {
    private val settings = FakePlusAddressSettings()
    private val testSubject = PlusAddressExternalSettings(settings)

    @Test
    fun `what export writes should be read back by import`() {
        settings.decisions = PlusAddressDecisionSnapshot(
            allowedSenders = mapOf("reader+shop@mail.example" to setOf("partner.example", "shop.example")),
            ignored = setOf("reader+lists@mail.example"),
        )
        val exported = testSubject.exportSettings()

        testSubject.importSettings(exported)

        assertThat(settings.imported).isEqualTo(settings.decisions)
    }

    @Test
    fun `nothing to import should leave the store alone`() {
        testSubject.importSettings(mapOf(PLUS_ADDRESS_ALLOWED_SENDERS_KEY to "{}", PLUS_ADDRESS_IGNORED_KEY to ""))

        assertThat(settings.imported).isNull()
    }

    @Test
    fun `a value that cannot be read should be skipped`() {
        testSubject.importSettings(
            mapOf(
                PLUS_ADDRESS_ALLOWED_SENDERS_KEY to "not json",
                PLUS_ADDRESS_IGNORED_KEY to """["reader+lists@mail.example"]""",
            ),
        )

        assertThat(settings.imported).isEqualTo(
            PlusAddressDecisionSnapshot(allowedSenders = emptyMap(), ignored = setOf("reader+lists@mail.example")),
        )
    }
}

private class FakePlusAddressSettings : PlusAddressSettings {
    var decisions = PlusAddressDecisionSnapshot(allowedSenders = emptyMap(), ignored = emptySet())
    var imported: PlusAddressDecisionSnapshot? = null

    override fun exportDecisions(): PlusAddressDecisionSnapshot = decisions

    override fun importDecisions(decisions: PlusAddressDecisionSnapshot) {
        imported = decisions
    }
}
