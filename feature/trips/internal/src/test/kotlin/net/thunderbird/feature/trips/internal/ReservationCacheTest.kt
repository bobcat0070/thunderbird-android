package net.thunderbird.feature.trips.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNull
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ReservationCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "reservations.json") }

    private val hints = MailHints(isListedSender = true, senderType = ReservationType.LODGING)

    private val flight = Reservation(
        type = ReservationType.FLIGHT,
        title = "Example Air EA 12",
        start = TripTime(LocalDateTime.of(2026, 11, 12, 8, 5), ZoneOffset.ofHours(-8)),
        end = TripTime(LocalDateTime.of(2026, 11, 12, 11, 20), offset = null, hasTime = false),
        details = "AAA → BBB",
        confirmation = "ABC123",
        isFromText = true,
        mailKey = "mail1",
        mailDate = 1_000L,
    )

    @Test
    fun `bookings should be read back after a restart`() = runTest {
        val before = ReservationCache(file, appUpdatedAt = { 1L })
        before.load()
        before.put("mail1", hints, listOf(flight))
        before.put("mail2", hints, emptyList())
        before.save(mailKeys = setOf("mail1", "mail2"))

        val testSubject = ReservationCache(file, appUpdatedAt = { 1L })
        testSubject.load()

        assertThat(testSubject.get("mail1", hints).orEmpty()).containsExactly(flight)
        assertThat(testSubject.get("mail2", hints).orEmpty()).isEmpty()
        assertThat(testSubject.get("mail1", MailHints())).isNull()
    }

    @Test
    fun `bookings should be dropped after the app is updated`() = runTest {
        val before = ReservationCache(file, appUpdatedAt = { 1L })
        before.put("mail1", hints, listOf(flight))
        before.save(mailKeys = setOf("mail1"))

        val testSubject = ReservationCache(file, appUpdatedAt = { 2L })
        testSubject.load()

        assertThat(testSubject.get("mail1", hints)).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `bookings of mail no longer read should not be kept`() = runTest {
        val before = ReservationCache(file, appUpdatedAt = { 1L })
        before.put("mail1", hints, listOf(flight))
        before.put("deleted", hints, listOf(flight.copy(mailKey = "deleted")))
        before.save(mailKeys = setOf("mail1"))

        val testSubject = ReservationCache(file, appUpdatedAt = { 1L })
        testSubject.load()

        assertThat(testSubject.get("deleted", hints)).isNull()
        assertThat(file.readText().contains("deleted")).isFalse()
    }

    @Test
    fun `an unreadable file should be ignored`() = runTest {
        file.writeText("{not json")

        val testSubject = ReservationCache(file, appUpdatedAt = { 1L })
        testSubject.load()

        assertThat(testSubject.get("mail1", hints)).isNull()
    }
}
