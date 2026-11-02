package net.thunderbird.feature.packages.internal

import androidx.lifecycle.viewModelScope
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.ShippingMail
import net.thunderbird.feature.packages.ShippingMailSource
import net.thunderbird.feature.packages.internal.PackagesContract.DownloadProgress
import net.thunderbird.feature.packages.internal.PackagesContract.Effect
import net.thunderbird.feature.packages.internal.PackagesContract.Event
import net.thunderbird.feature.packages.internal.PackagesContract.Notice
import net.thunderbird.feature.packages.internal.PackagesContract.State
import net.thunderbird.feature.packages.internal.carrier.LiveTracker

/**
 * How far back shipping mail is read: longer than any parcel takes to arrive.
 */
internal const val SCAN_WINDOW_DAYS = 45

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/**
 * Gathers parcels from shipping mail, asks the carriers the reader has credentials for where those still on the way
 * are, and keeps the reader's word on which have arrived.
 *
 * The carriers are only asked when the screen opens and when the reader refreshes it, never in the background.
 */
@OptIn(ExperimentalTime::class)
internal class PackagesViewModel(
    private val mailSource: ShippingMailSource,
    private val marks: ParcelMarks,
    private val liveTracker: LiveTracker,
    private val clock: Clock,
) : BaseViewModel<State, Event, Effect>(State()), PackagesContract.ViewModel {

    private var mails: List<ShippingMail> = emptyList()

    override fun event(event: Event) {
        when (event) {
            Event.LoadParcels -> handleOneTimeEvent(event) { load(isRefresh = false) }
            Event.Refresh -> load(isRefresh = true)
            is Event.ParcelClicked -> updateState { it.copy(selectedNumber = event.number) }
            is Event.OpenTrackingPage -> mailSource.openTrackingPage(event.parcel.trackingUrl)
            is Event.OpenMail -> mailSource.openMail(event.key)
            is Event.SearchAllMail -> mailSource.searchAllMail(event.number)
            is Event.MarkDelivered -> markDelivered(event.number, event.isDelivered)
            is Event.RemoveParcel -> remove(event.number)
            Event.DownloadMailsClicked -> downloadMails()
            Event.CarrierAccountsClicked -> emitEffect(Effect.OpenCarrierAccounts)
            Event.BackClicked -> back()
        }
    }

    private fun back() {
        if (state.value.selectedNumber != null) {
            updateState { it.copy(selectedNumber = null) }
        } else {
            emitEffect(Effect.NavigateBack)
        }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    private fun since(): Long = now() - SCAN_WINDOW_DAYS * DAY_MILLIS

    private fun load(isRefresh: Boolean) {
        if (state.value.isRefreshing) return
        updateState { it.copy(isRefreshing = isRefresh) }

        viewModelScope.launch {
            mails = mailSource.shippingMails(since())
            val notDownloaded = mailSource.notDownloadedCount(since())
            updateState {
                it.copy(
                    isLoading = false,
                    parcels = catalog(),
                    mailsNotDownloaded = notDownloaded,
                    liveCarriers = liveTracker.carriers(),
                )
            }

            askCarriers()
            updateState { it.copy(isRefreshing = false) }
        }
    }

    private fun catalog(): List<Parcel> = catalogParcels(mails, marks.markedDelivered(), marks.removed(), now())

    /**
     * Asks about each parcel the reader is still waiting for, all at once, showing each answer as it comes.
     */
    private suspend fun askCarriers() {
        val current = state.value
        val waiting = current.parcels.filter { parcel ->
            parcel.carrier in current.liveCarriers &&
                !parcel.isMarkedDelivered &&
                parcel.mailStatus != ParcelStatus.DELIVERED
        }

        coroutineScope {
            waiting.map { parcel ->
                async {
                    val result = liveTracker.track(ParcelNumber(parcel.carrier, parcel.number, parcel.trackingUrl))
                    if (result != null) {
                        updateState { it.copy(live = it.live + (parcel.number to result)) }
                    }
                }
            }.awaitAll()
        }
    }

    private fun markDelivered(number: String, isDelivered: Boolean) {
        marks.setMarkedDelivered(number, isDelivered)
        updateState { it.copy(parcels = catalog()) }
    }

    private fun remove(number: String) {
        marks.remove(number)
        updateState { it.copy(parcels = catalog(), selectedNumber = null) }
        emitEffect(Effect.ShowNotice(Notice.Removed))
    }

    private fun downloadMails() {
        if (state.value.download != null) return
        updateState { it.copy(download = DownloadProgress(done = 0, total = 0)) }

        viewModelScope.launch {
            val count = mailSource.downloadMails(since()) { done, total ->
                updateState { it.copy(download = DownloadProgress(done, total)) }
            }
            emitEffect(Effect.ShowNotice(Notice.Downloaded(count)))
            updateState { it.copy(download = null) }
            load(isRefresh = false)
        }
    }
}
