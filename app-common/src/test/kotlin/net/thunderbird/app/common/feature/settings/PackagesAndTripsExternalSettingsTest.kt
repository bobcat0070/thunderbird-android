package net.thunderbird.app.common.feature.settings

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.fsck.k9.preferences.ExternalSettingKeys.PARCELS_MARKED_DELIVERED_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.PARCELS_REMOVED_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.TRAVEL_MAIL_MARKED_KEY
import com.fsck.k9.preferences.ExternalSettingKeys.TRAVEL_SENDERS_ADDED_KEY
import net.thunderbird.core.android.testing.RobolectricTest
import net.thunderbird.feature.packages.ParcelMarkSettings
import net.thunderbird.feature.packages.ParcelMarkSnapshot
import net.thunderbird.feature.trips.TravelSenderSettings
import net.thunderbird.feature.trips.TravelSenderSnapshot
import org.junit.Test

// org.json, which the export format is written with, needs the Android runtime.
class PackagesAndTripsExternalSettingsTest : RobolectricTest() {
    private val parcelMarks = FakeParcelMarkSettings()
    private val travelSenders = FakeTravelSenderSettings()
    private val testSubject = PackagesAndTripsExternalSettings(parcelMarks, travelSenders)

    @Test
    fun `what export writes should be read back by import`() {
        parcelMarks.marks = ParcelMarkSnapshot(
            markedDelivered = mapOf("1Z999AA10123456784" to 1_000L),
            removed = mapOf("9400100000000000000000" to 2_000L),
        )
        travelSenders.senders = TravelSenderSnapshot(
            added = setOf("example-travel.com"),
            removed = setOf("united.com"),
            markedMessageIds = listOf("<booking-1@example.com>"),
        )
        val exported = testSubject.exportSettings()

        testSubject.importSettings(exported)

        assertThat(parcelMarks.imported).isEqualTo(parcelMarks.marks)
        assertThat(travelSenders.imported).isEqualTo(travelSenders.senders)
    }

    @Test
    fun `nothing to import should leave the stores alone`() {
        testSubject.importSettings(
            mapOf(PARCELS_MARKED_DELIVERED_KEY to "", PARCELS_REMOVED_KEY to "{}", TRAVEL_SENDERS_ADDED_KEY to "[]"),
        )

        assertThat(parcelMarks.imported).isNull()
        assertThat(travelSenders.imported).isNull()
    }

    @Test
    fun `a value that cannot be read should be skipped without losing the others`() {
        testSubject.importSettings(
            mapOf(
                PARCELS_MARKED_DELIVERED_KEY to """{"123456789012":"yesterday","1234567890":5000}""",
                PARCELS_REMOVED_KEY to "not json",
                TRAVEL_MAIL_MARKED_KEY to """["<booking-2@example.com>"]""",
                TRAVEL_SENDERS_ADDED_KEY to "{",
            ),
        )

        assertThat(parcelMarks.imported).isEqualTo(
            ParcelMarkSnapshot(markedDelivered = mapOf("1234567890" to 5_000L), removed = emptyMap()),
        )
        assertThat(travelSenders.imported).isEqualTo(
            TravelSenderSnapshot(added = emptySet(), removed = emptySet(), listOf("<booking-2@example.com>")),
        )
    }
}

private class FakeParcelMarkSettings : ParcelMarkSettings {
    var marks = ParcelMarkSnapshot(markedDelivered = emptyMap(), removed = emptyMap())
    var imported: ParcelMarkSnapshot? = null

    override fun exportMarks(): ParcelMarkSnapshot = marks

    override fun importMarks(marks: ParcelMarkSnapshot) {
        imported = marks
    }
}

private class FakeTravelSenderSettings : TravelSenderSettings {
    var senders = TravelSenderSnapshot(added = emptySet(), removed = emptySet(), markedMessageIds = emptyList())
    var imported: TravelSenderSnapshot? = null

    override fun exportSenders(): TravelSenderSnapshot = senders

    override fun importSenders(senders: TravelSenderSnapshot) {
        imported = senders
    }
}
