package net.thunderbird.feature.packages.internal

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import net.thunderbird.feature.packages.ParcelMarkSnapshot
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private const val NOW = 1_800_000_000_000L
private const val DAY = 24 * 60 * 60 * 1000L

@OptIn(ExperimentalTime::class)
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesParcelMarksTest {
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW)
    }
    private val testSubject = SharedPreferencesParcelMarks(RuntimeEnvironment.getApplication(), clock)

    @Test
    fun `marks should survive a round trip with when they were made`() {
        testSubject.setMarkedDelivered("1Z999AA10123456784", isDelivered = true)
        testSubject.remove("9400100000000000000000")
        val exported = testSubject.exportMarks()
        // A new device: nothing marked yet.
        RuntimeEnvironment.getApplication().getSharedPreferences("packages", 0).edit().clear().commit()

        testSubject.importMarks(exported)

        assertThat(testSubject.exportMarks()).isEqualTo(
            ParcelMarkSnapshot(
                markedDelivered = mapOf("1Z999AA10123456784" to NOW),
                removed = mapOf("9400100000000000000000" to NOW),
            ),
        )
    }

    @Test
    fun `import should add to the marks here and keep the later time of a number marked on both`() {
        testSubject.setMarkedDelivered("123456789012", isDelivered = true)

        testSubject.importMarks(
            ParcelMarkSnapshot(
                markedDelivered = mapOf("123456789012" to NOW - DAY, "1234567890" to NOW - DAY),
                removed = emptyMap(),
            ),
        )

        assertThat(testSubject.exportMarks().markedDelivered).isEqualTo(
            mapOf("123456789012" to NOW, "1234567890" to NOW - DAY),
        )
    }

    @Test
    fun `imported marks that are not tracking numbers or are not from the past should be dropped`() {
        testSubject.importMarks(
            ParcelMarkSnapshot(
                markedDelivered = mapOf(
                    "123456789012" to NOW - DAY,
                    "12|34567890" to NOW - DAY,
                    "short" to NOW - DAY,
                    "999999999999" to NOW + DAY,
                ),
                removed = emptyMap(),
            ),
        )

        assertThat(testSubject.markedDelivered()).containsOnly("123456789012")
    }
}
