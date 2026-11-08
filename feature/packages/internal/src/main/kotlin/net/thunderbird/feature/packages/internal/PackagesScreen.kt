package net.thunderbird.feature.packages.internal

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.button.ButtonIcon
import net.thunderbird.components.ui.bolt.atom.button.ButtonOutlined
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.icon.Icon
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextLabelLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.LoadingView
import net.thunderbird.components.ui.bolt.molecule.PullToRefreshBox
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.observe
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.internal.PackagesContract.Effect
import net.thunderbird.feature.packages.internal.PackagesContract.Event
import net.thunderbird.feature.packages.internal.PackagesContract.Notice
import net.thunderbird.feature.packages.internal.PackagesContract.State
import net.thunderbird.feature.packages.internal.PackagesContract.ViewModel
import net.thunderbird.feature.packages.internal.carrier.LiveResult
import org.koin.compose.viewmodel.koinViewModel

@Composable
@Suppress("LongMethod")
internal fun PackagesScreen(
    onBack: () -> Unit,
    onOpenCarrierAccounts: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewModel = koinViewModel<PackagesViewModel>(),
) {
    val context = LocalContext.current
    val resources = context.resources

    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()

            Effect.OpenCarrierAccounts -> onOpenCarrierAccounts()

            is Effect.ShowNotice -> {
                val text = when (val notice = effect.notice) {
                    is Notice.Downloaded -> resources.getQuantityString(
                        R.plurals.packages_downloaded,
                        notice.count,
                        notice.count,
                    )

                    Notice.Removed -> resources.getString(R.string.packages_removed)
                }
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        dispatch(Event.LoadParcels)
    }

    BackHandler {
        dispatch(Event.BackClicked)
    }

    val selected = state.value.selected
    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = if (selected != null) {
                    selected.shop ?: carrierName(selected.carrier)
                } else {
                    stringResource(R.string.packages_title)
                },
                onBackClick = { dispatch(Event.BackClicked) },
                actions = {
                    if (selected == null) {
                        ButtonIcon(
                            onClick = { dispatch(Event.CarrierAccountsClicked) },
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.packages_carrier_accounts_title),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.value.isRefreshing,
            onRefresh = { dispatch(Event.Refresh) },
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
        ) {
            when {
                state.value.isLoading -> LoadingView(modifier = Modifier.fillMaxSize())
                selected != null -> ParcelSummary(parcel = selected, state = state.value, onEvent = dispatch)
                else -> ParcelList(state = state.value, onEvent = dispatch)
            }
        }
    }
}

@Composable
private fun ParcelList(
    state: State,
    onEvent: (Event) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BoltTheme.spacings.double)) {
        if (state.mailsNotDownloaded > 0) {
            item(key = "not_downloaded") {
                NotDownloadedNotice(state = state, onEvent = onEvent)
                DividerHorizontal()
            }
        }

        if (state.parcels.isEmpty()) {
            item(key = "empty") {
                TextBodyLarge(
                    text = stringResource(R.string.packages_empty, SCAN_WINDOW_DAYS),
                    modifier = Modifier.padding(BoltTheme.spacings.double),
                )
            }
        }

        if (state.onTheWay.isNotEmpty()) {
            item(key = "on_the_way") { SectionHeader(stringResource(R.string.packages_section_on_the_way)) }
            items(state.onTheWay, key = { it.number }) { parcel ->
                ParcelRow(parcel = parcel, state = state, onEvent = onEvent)
                DividerHorizontal()
            }
        }

        if (state.delivered.isNotEmpty()) {
            item(key = "delivered") { SectionHeader(stringResource(R.string.packages_section_delivered)) }
            items(state.delivered, key = { it.number }) { parcel ->
                ParcelRow(parcel = parcel, state = state, onEvent = onEvent)
                DividerHorizontal()
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    TextLabelLarge(
        text = text,
        modifier = Modifier.padding(
            start = BoltTheme.spacings.double,
            top = BoltTheme.spacings.double,
            bottom = BoltTheme.spacings.half,
        ),
    )
}

@Composable
private fun NotDownloadedNotice(
    state: State,
    onEvent: (Event) -> Unit,
) {
    val count = state.mailsNotDownloaded
    val download = state.download

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
    ) {
        // Opening the screen starts the download itself, so while it runs only its progress is shown.
        if (download != null) {
            val total = if (download.total > 0) download.total else count
            TextBodyMedium(text = stringResource(R.string.packages_downloading, download.done, total))
        } else {
            TextBodyMedium(text = pluralStringResource(R.plurals.packages_mails_not_downloaded, count, count))
            ButtonText(
                text = pluralStringResource(R.plurals.packages_download_mails, count, count),
                onClick = { onEvent(Event.DownloadMailsClicked) },
            )
        }
    }
}

@Composable
private fun ParcelRow(
    parcel: Parcel,
    state: State,
    onEvent: (Event) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(Event.ParcelClicked(parcel.number)) }
            .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.double),
    ) {
        Icon(imageVector = Icons.Outlined.LocalShipping)
        Column(modifier = Modifier.weight(1f)) {
            TextTitleMedium(
                text = parcel.shop ?: carrierName(parcel.carrier),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextBodyMedium(text = statusLine(parcel, state), maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextBodyMedium(
                text = stringResource(R.string.packages_carrier_and_number, carrierName(parcel.carrier), parcel.number),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@Suppress("LongMethod")
private fun ParcelSummary(
    parcel: Parcel,
    state: State,
    onEvent: (Event) -> Unit,
) {
    val context = LocalContext.current
    val status = state.statusOf(parcel)
    val live = state.live[parcel.number]

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(BoltTheme.spacings.double)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.half)) {
                TextTitleLarge(text = statusLine(parcel, state))
                TextBodyMedium(
                    text = stringResource(
                        R.string.packages_carrier_and_number,
                        carrierName(parcel.carrier),
                        parcel.number,
                    ),
                )
                parcel.title?.let { TextBodyMedium(text = it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                TextBodyMedium(text = liveNote(parcel, state, live))
            }
        }

        item(key = "actions") {
            FlowRow(
                modifier = Modifier.padding(vertical = BoltTheme.spacings.double),
                horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
            ) {
                ButtonOutlined(
                    text = stringResource(R.string.packages_track_on, carrierName(parcel.carrier)),
                    onClick = { onEvent(Event.OpenTrackingPage(parcel)) },
                    icon = Icons.Outlined.OpenInNew,
                )
                ButtonOutlined(
                    text = stringResource(R.string.packages_search_all_mail),
                    onClick = { onEvent(Event.SearchAllMail(parcel.number)) },
                )
                ButtonText(
                    text = stringResource(
                        if (parcel.isMarkedDelivered) {
                            R.string.packages_mark_not_delivered
                        } else {
                            R.string.packages_mark_delivered
                        },
                    ),
                    onClick = { onEvent(Event.MarkDelivered(parcel.number, !parcel.isMarkedDelivered)) },
                    enabled = parcel.isMarkedDelivered || status != ParcelStatus.DELIVERED,
                )
                ButtonText(
                    text = stringResource(R.string.packages_remove),
                    onClick = { onEvent(Event.RemoveParcel(parcel.number)) },
                )
            }
            DividerHorizontal()
        }

        item(key = "mails_header") {
            SectionHeaderInline(
                text = pluralStringResource(R.plurals.packages_mails_about, parcel.mails.size, parcel.mails.size),
            )
        }

        items(parcel.mails, key = { it.key }) { mail ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEvent(Event.OpenMail(mail.key)) }
                    .padding(vertical = BoltTheme.spacings.default),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.double),
            ) {
                Icon(imageVector = Icons.Outlined.Drafts)
                Column(modifier = Modifier.weight(1f)) {
                    TextTitleMedium(text = mail.subject.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextBodyMedium(
                        text = listOfNotNull(
                            mail.senderName,
                            DateUtils.formatDateTime(
                                context,
                                mail.date,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
                            ),
                        ).joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DividerHorizontal()
        }
    }
}

@Composable
private fun SectionHeaderInline(text: String) {
    TextLabelLarge(text = text, modifier = Modifier.padding(top = BoltTheme.spacings.double))
}

/**
 * What the reader most wants to know: the carrier's own words when it was asked, with when it expects to deliver,
 * else what the mail said.
 */
@Composable
private fun statusLine(parcel: Parcel, state: State): String {
    val live = (state.live[parcel.number] as? LiveResult.Found)?.liveStatus
    if (live != null && !parcel.isMarkedDelivered) {
        val expected = live.expectedDelivery
        return if (expected != null && live.status != ParcelStatus.DELIVERED) {
            stringResource(R.string.packages_status_with_expected, live.description, expected)
        } else {
            live.description
        }
    }
    return statusName(state.statusOf(parcel))
}

@Composable
private fun liveNote(parcel: Parcel, state: State, live: LiveResult?): String = when {
    live is LiveResult.Found -> stringResource(R.string.packages_live_from, carrierName(parcel.carrier))
    live is LiveResult.Unavailable -> stringResource(R.string.packages_live_unavailable, carrierName(parcel.carrier))
    parcel.carrier in state.liveCarriers -> stringResource(R.string.packages_live_not_asked)
    else -> stringResource(R.string.packages_status_from_mail)
}

@Composable
private fun statusName(status: ParcelStatus): String = stringResource(
    when (status) {
        ParcelStatus.ON_ITS_WAY -> R.string.packages_status_on_its_way
        ParcelStatus.OUT_FOR_DELIVERY -> R.string.packages_status_out_for_delivery
        ParcelStatus.DELIVERED -> R.string.packages_status_delivered
        ParcelStatus.PROBLEM -> R.string.packages_status_problem
    },
)

internal fun carrierName(carrier: ParcelCarrier): String = when (carrier) {
    ParcelCarrier.UPS -> "UPS"
    ParcelCarrier.USPS -> "USPS"
    ParcelCarrier.FEDEX -> "FedEx"
    ParcelCarrier.DHL -> "DHL"
}
