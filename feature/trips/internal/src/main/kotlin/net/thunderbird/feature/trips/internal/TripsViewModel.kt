package net.thunderbird.feature.trips.internal

import androidx.lifecycle.viewModelScope
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.trips.TravelMailHeader
import net.thunderbird.feature.trips.TravelMailSource
import net.thunderbird.feature.trips.internal.TripsContract.DownloadProgress
import net.thunderbird.feature.trips.internal.TripsContract.Effect
import net.thunderbird.feature.trips.internal.TripsContract.Event
import net.thunderbird.feature.trips.internal.TripsContract.State
import org.jsoup.Jsoup

/**
 * How far back booking mail is read: far enough for a trip booked a year ahead.
 */
internal const val TRIPS_SCAN_DAYS = 365

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

internal interface TripsContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param trips the trips that have not ended, soonest first.
     * @param notDownloaded the booking-looking mails known only by their envelope, newest first.
     */
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val trips: List<Trip> = emptyList(),
        val notDownloaded: List<String> = emptyList(),
        val download: DownloadProgress? = null,
    )

    data class DownloadProgress(val done: Int, val total: Int)

    sealed interface Event {
        data object LoadTrips : Event
        data object Refresh : Event
        data class OpenMail(val key: String) : Event
        data class AddToCalendar(val reservation: Reservation) : Event
        data object DownloadMailsClicked : Event
        data object TravelSendersClicked : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
        data object OpenTravelSenders : Effect
        data class OpenCalendar(val reservation: Reservation) : Effect
        data class Downloaded(val count: Int) : Effect
    }
}

/**
 * The bookings read from each mail, kept for as long as the app runs: a mail's body does not change, and reading a
 * year of confirmations again on every visit would be slow.
 */
internal class ReservationCache {
    private val byMail = ConcurrentHashMap<String, List<Reservation>>()

    fun get(key: String): List<Reservation>? = byMail[key]

    fun put(key: String, reservations: List<Reservation>) {
        byMail[key] = reservations
    }
}

/**
 * Reads bookings from the reader's confirmations and gathers them into trips.
 */
@OptIn(ExperimentalTime::class)
internal class TripsViewModel(
    private val mailSource: TravelMailSource,
    private val cache: ReservationCache,
    private val senders: TravelSenderStore,
    private val clock: Clock,
) : BaseViewModel<State, Event, Effect>(State()), TripsContract.ViewModel {

    /**
     * The travel sender list's version the trips were last read with, `null` before the first read.
     */
    private var loadedVersion: Int? = null

    /**
     * Whether the mail not on the device was downloaded without asking, which is done once a visit: mail that fails
     * to download is then left to the reader's button rather than tried again on every reload.
     */
    private var hasDownloadedOnOpen = false

    override fun event(event: Event) {
        when (event) {
            // Sent each time the screen shows, as after the travel sender list, which may have changed.
            Event.LoadTrips -> if (loadedVersion != senders.version) load(isRefresh = false)

            Event.Refresh -> load(isRefresh = true)

            is Event.OpenMail -> mailSource.openMail(event.key)

            is Event.AddToCalendar -> emitEffect(Effect.OpenCalendar(event.reservation))

            Event.DownloadMailsClicked -> downloadMails(isAsked = true)

            Event.TravelSendersClicked -> emitEffect(Effect.OpenTravelSenders)

            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    private fun load(isRefresh: Boolean) {
        if (state.value.isRefreshing) return
        updateState { it.copy(isRefreshing = isRefresh) }
        loadedVersion = senders.version

        viewModelScope.launch {
            val headers = mailSource.candidates(
                since = now() - TRIPS_SCAN_DAYS * DAY_MILLIS,
                senderDomains = senders.listed().map { it.domain }.toSet(),
                markedMessageIds = senders.markedMessageIds(),
            )
            val reservations = headers.filter { it.isDownloaded }.flatMap { header -> reservationsIn(header) }
            val today = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()).toLocalDate()

            updateState {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    trips = groupTrips(reservations, today),
                    notDownloaded = headers.filterNot { header -> header.isDownloaded }
                        .sortedByDescending { header -> header.date }
                        .map { header -> header.key },
                )
            }
            if (state.value.notDownloaded.isNotEmpty() && !hasDownloadedOnOpen) {
                hasDownloadedOnOpen = true
                downloadMails(isAsked = false)
            }
        }
    }

    /**
     * Booking markup is read first; a mail without any is read for its words.
     */
    @Suppress("ReturnCount")
    private suspend fun reservationsIn(header: TravelMailHeader): List<Reservation> {
        val address = header.senderAddress.orEmpty()
        val hints = MailHints(
            isListedSender = senders.isListedSender(address),
            senderType = senders.typeOf(address),
            isMarked = header.isMarked,
        )
        // What a mail reads as depends on its hints, which change as the reader edits the list.
        val cacheKey = "${header.key}|$hints"
        cache.get(cacheKey)?.let { return it }

        val content = mailSource.content(header.key) ?: return emptyList()
        val reservations = withContext(Dispatchers.Default) {
            val html = content.html
            val fromMarkup = html?.let { reservationsFromMarkup(it, header.key, header.date) }.orEmpty()
            fromMarkup.ifEmpty {
                val text = content.text ?: html?.let { Jsoup.parse(it).wholeText() }.orEmpty()
                listOfNotNull(
                    reservationFromText(
                        subject = header.subject,
                        senderName = header.senderName,
                        text = text,
                        mailKey = header.key,
                        mailDate = header.date,
                        hints = hints,
                    ),
                )
            }
        }
        cache.put(cacheKey, reservations)
        return reservations
    }

    /**
     * @param isAsked whether the reader asked, who is then told how many were downloaded.
     */
    private fun downloadMails(isAsked: Boolean) {
        if (state.value.download != null) return
        val keys = state.value.notDownloaded
        if (keys.isEmpty()) return
        updateState { it.copy(download = DownloadProgress(done = 0, total = keys.size)) }

        viewModelScope.launch {
            val count = mailSource.download(keys) { done, total ->
                updateState { it.copy(download = DownloadProgress(done, total)) }
            }
            if (isAsked) emitEffect(Effect.Downloaded(count))
            updateState { it.copy(download = null) }
            load(isRefresh = false)
        }
    }
}
