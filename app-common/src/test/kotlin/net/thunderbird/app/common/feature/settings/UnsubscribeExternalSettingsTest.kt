package net.thunderbird.app.common.feature.settings

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.preferences.ExternalSettingKeys.UNSUBSCRIBED_SENDERS_KEY
import net.thunderbird.core.android.testing.RobolectricTest
import net.thunderbird.feature.newsletter.UnsubscribeHistorySettings
import org.junit.Test

// org.json, which the export format is written with, needs the Android runtime.
class UnsubscribeExternalSettingsTest : RobolectricTest() {
    private val settings = FakeUnsubscribeHistorySettings()
    private val testSubject = UnsubscribeExternalSettings(settings)

    @Test
    fun `what export writes should be read back by import`() {
        settings.unsubscribes = mapOf("news@shop.example" to 1_790_000_000_000L, "deals@store.example" to 42L)
        val exported = testSubject.exportSettings()

        testSubject.importSettings(exported)

        assertThat(settings.imported).isEqualTo(settings.unsubscribes)
    }

    @Test
    fun `nothing to import should leave the store alone`() {
        testSubject.importSettings(mapOf(UNSUBSCRIBED_SENDERS_KEY to "{}"))

        assertThat(settings.imported).isNull()
    }

    @Test
    fun `a value that cannot be read should be skipped`() {
        testSubject.importSettings(mapOf(UNSUBSCRIBED_SENDERS_KEY to "not json"))

        assertThat(settings.imported).isNull()
    }

    @Test
    fun `entries without a date should be dropped`() {
        testSubject.importSettings(
            mapOf(UNSUBSCRIBED_SENDERS_KEY to """{"news@shop.example":"soon","deals@store.example":42}"""),
        )

        assertThat(settings.imported).isEqualTo(mapOf("deals@store.example" to 42L))
    }
}

private class FakeUnsubscribeHistorySettings : UnsubscribeHistorySettings {
    var unsubscribes = emptyMap<String, Long>()
    var imported: Map<String, Long>? = null

    override fun exportUnsubscribes(): Map<String, Long> = unsubscribes

    override fun importUnsubscribes(unsubscribes: Map<String, Long>) {
        imported = unsubscribes
    }
}
