package net.thunderbird.feature.trips.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.Test
import net.thunderbird.feature.trips.TravelSenderSnapshot
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesTravelSenderStoreTest {
    private val testSubject = SharedPreferencesTravelSenderStore(RuntimeEnvironment.getApplication())

    @Test
    fun `the reader's changes should survive a round trip`() {
        testSubject.setListed("example-travel.com", isListed = true)
        testSubject.setListed("united.com", isListed = false)
        testSubject.setMarked("<booking-1@example.com>", isMarked = true)
        val exported = testSubject.exportSenders()
        // A new device: no changes made yet.
        RuntimeEnvironment.getApplication().getSharedPreferences("trips", 0).edit().clear().commit()

        testSubject.importSenders(exported)

        assertThat(testSubject.isListed("example-travel.com")).isTrue()
        assertThat(testSubject.isListed("united.com")).isFalse()
        assertThat(testSubject.isMarked("<booking-1@example.com>")).isTrue()
    }

    @Test
    fun `import should add to the changes made here`() {
        testSubject.setListed("here.example", isListed = true)
        testSubject.setMarked("<here@example.com>", isMarked = true)

        testSubject.importSenders(
            TravelSenderSnapshot(
                added = setOf("there.example"),
                removed = emptySet(),
                markedMessageIds = listOf("<there@example.com>", "<here@example.com>"),
            ),
        )

        assertThat(testSubject.isListed("here.example")).isTrue()
        assertThat(testSubject.isListed("there.example")).isTrue()
        assertThat(testSubject.markedMessageIds()).isEqualTo(setOf("<here@example.com>", "<there@example.com>"))
    }

    @Test
    fun `imported values that could not have been listed here should be dropped`() {
        testSubject.importSenders(
            TravelSenderSnapshot(
                // A mail provider would take in everyone's mail; a built-in sender is listed already.
                added = setOf("gmail.com", "united.com", "not a domain", "good.example"),
                // Only built-in senders can be removed.
                removed = setOf("good.example"),
                markedMessageIds = listOf("<one@example.com>\n<two@example.com>", " ", "<ok@example.com>"),
            ),
        )

        assertThat(testSubject.exportSenders().added).isEqualTo(setOf("good.example"))
        assertThat(testSubject.isListed("good.example")).isTrue()
        assertThat(testSubject.isListed("gmail.com")).isFalse()
        assertThat(testSubject.markedMessageIds()).isEqualTo(setOf("<ok@example.com>"))
    }
}
