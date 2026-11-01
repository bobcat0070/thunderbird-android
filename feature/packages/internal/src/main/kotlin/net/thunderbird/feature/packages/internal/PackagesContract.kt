package net.thunderbird.feature.packages.internal

import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.internal.carrier.LiveResult

internal interface PackagesContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param parcels every parcel still worth showing, newest news first.
     * @param live what the carriers said about the parcels, by tracking number, as their answers come in.
     * @param liveCarriers the carriers the reader gave credentials for.
     * @param selectedNumber the parcel whose summary is open.
     * @param mailsNotDownloaded how many shipping-looking mails are known only by their envelope.
     */
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val parcels: List<Parcel> = emptyList(),
        val live: Map<String, LiveResult> = emptyMap(),
        val liveCarriers: Set<ParcelCarrier> = emptySet(),
        val selectedNumber: String? = null,
        val mailsNotDownloaded: Int = 0,
        val download: DownloadProgress? = null,
    ) {
        val selected: Parcel?
            get() = parcels.firstOrNull { it.number == selectedNumber }

        val onTheWay: List<Parcel>
            get() = parcels.filter { statusOf(it) != ParcelStatus.DELIVERED }

        val delivered: List<Parcel>
            get() = parcels.filter { statusOf(it) == ParcelStatus.DELIVERED }

        /**
         * The reader's word first, then the carrier's, then what the mail said.
         */
        fun statusOf(parcel: Parcel): ParcelStatus = when {
            parcel.isMarkedDelivered -> ParcelStatus.DELIVERED
            else -> (live[parcel.number] as? LiveResult.Found)?.liveStatus?.status ?: parcel.mailStatus
        }
    }

    data class DownloadProgress(val done: Int, val total: Int)

    sealed interface Event {
        data object LoadParcels : Event
        data object Refresh : Event
        data class ParcelClicked(val number: String) : Event
        data class OpenTrackingPage(val parcel: Parcel) : Event
        data class OpenMail(val key: String) : Event
        data class SearchAllMail(val number: String) : Event
        data class MarkDelivered(val number: String, val isDelivered: Boolean) : Event
        data class RemoveParcel(val number: String) : Event
        data object DownloadMailsClicked : Event
        data object CarrierAccountsClicked : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
        data object OpenCarrierAccounts : Effect
        data class ShowNotice(val notice: Notice) : Effect
    }

    sealed interface Notice {
        data class Downloaded(val count: Int) : Notice
        data object Removed : Notice
    }
}
