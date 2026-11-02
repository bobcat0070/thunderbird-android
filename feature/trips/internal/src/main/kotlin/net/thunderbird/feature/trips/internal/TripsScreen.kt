package net.thunderbird.feature.trips.internal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.button.ButtonIcon
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.icon.Icon
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.atom.text.TextTitleLarge
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.molecule.LoadingView
import net.thunderbird.components.ui.bolt.molecule.PullToRefreshBox
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.observe
import net.thunderbird.feature.trips.internal.TripsContract.Effect
import net.thunderbird.feature.trips.internal.TripsContract.Event
import net.thunderbird.feature.trips.internal.TripsContract.State
import net.thunderbird.feature.trips.internal.TripsContract.ViewModel
import org.koin.compose.viewmodel.koinViewModel

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE")

@Composable
internal fun TripsScreen(
    onBack: () -> Unit,
    onOpenTravelSenders: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ViewModel = koinViewModel<TripsViewModel>(),
) {
    val context = LocalContext.current

    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()

            Effect.OpenTravelSenders -> onOpenTravelSenders()

            is Effect.OpenCalendar -> openCalendar(context, effect.reservation)

            is Effect.Downloaded -> Toast.makeText(
                context,
                context.resources.getQuantityString(R.plurals.trips_downloaded, effect.count, effect.count),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        dispatch(Event.LoadTrips)
    }

    BackHandler {
        dispatch(Event.BackClicked)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = stringResource(R.string.trips_title),
                onBackClick = { dispatch(Event.BackClicked) },
                actions = {
                    ButtonIcon(
                        onClick = { dispatch(Event.TravelSendersClicked) },
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.trips_travel_senders_title),
                    )
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
            if (state.value.isLoading) {
                LoadingView(modifier = Modifier.fillMaxSize())
            } else {
                TripList(state = state.value, onEvent = dispatch)
            }
        }
    }
}

@Composable
private fun TripList(
    state: State,
    onEvent: (Event) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = BoltTheme.spacings.double)) {
        if (state.notDownloaded.isNotEmpty()) {
            item(key = "not_downloaded") {
                NotDownloadedNotice(state = state, onEvent = onEvent)
                DividerHorizontal()
            }
        }

        if (state.trips.isEmpty()) {
            item(key = "empty") {
                TextBodyLarge(
                    text = stringResource(R.string.trips_empty),
                    modifier = Modifier.padding(BoltTheme.spacings.double),
                )
            }
        }

        state.trips.forEach { trip ->
            item(key = "trip_${trip.firstDate}_${trip.reservations.first().mailKey}") {
                TripHeader(trip)
            }
            items(trip.reservations, key = {
                "${it.mailKey}_${it.type}_${it.start.local}_${it.title}"
            }) { reservation ->
                ReservationRow(reservation = reservation, onEvent = onEvent)
            }
            item(key = "divider_${trip.firstDate}_${trip.reservations.first().mailKey}") {
                DividerHorizontal()
            }
        }
    }
}

@Composable
private fun NotDownloadedNotice(
    state: State,
    onEvent: (Event) -> Unit,
) {
    val count = state.notDownloaded.size
    val download = state.download

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BoltTheme.spacings.double, vertical = BoltTheme.spacings.default),
    ) {
        TextBodyMedium(text = pluralStringResource(R.plurals.trips_mails_not_downloaded, count, count))
        ButtonText(
            text = if (download != null) {
                stringResource(R.string.trips_downloading, download.done, download.total)
            } else {
                pluralStringResource(R.plurals.trips_download_mails, count, count)
            },
            onClick = { onEvent(Event.DownloadMailsClicked) },
            enabled = download == null,
        )
    }
}

@Composable
private fun TripHeader(trip: Trip) {
    Column(
        modifier = Modifier.padding(
            start = BoltTheme.spacings.double,
            end = BoltTheme.spacings.double,
            top = BoltTheme.spacings.double,
            bottom = BoltTheme.spacings.half,
        ),
    ) {
        val dates = dateRange(trip.firstDate, trip.lastDate)
        if (trip.place != null) {
            TextTitleLarge(text = trip.place)
            TextBodyMedium(text = dates)
        } else {
            TextTitleLarge(text = dates)
        }
    }
}

@Composable
private fun ReservationRow(
    reservation: Reservation,
    onEvent: (Event) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(Event.OpenMail(reservation.mailKey)) }
            .padding(
                start = BoltTheme.spacings.double,
                top = BoltTheme.spacings.default,
                bottom = BoltTheme.spacings.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.double),
    ) {
        Icon(imageVector = iconOf(reservation.type))
        Column(modifier = Modifier.weight(1f)) {
            TextTitleMedium(text = reservation.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextBodyMedium(text = whenText(reservation))
            reservation.details?.let { TextBodyMedium(text = it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            reservation.location?.let { TextBodySmall(text = it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            reservation.confirmation?.let { TextBodySmall(text = stringResource(R.string.trips_confirmation, it)) }
            if (reservation.isFromText) {
                TextBodySmall(text = stringResource(R.string.trips_from_text))
            }
        }
        ButtonIcon(
            onClick = { onEvent(Event.AddToCalendar(reservation)) },
            imageVector = Icons.Outlined.EventAvailable,
            contentDescription = stringResource(R.string.trips_add_to_calendar),
        )
        ButtonIcon(
            onClick = { onEvent(Event.OpenMail(reservation.mailKey)) },
            imageVector = Icons.Outlined.Drafts,
            contentDescription = stringResource(R.string.trips_open_mail),
        )
    }
}

private fun iconOf(type: ReservationType): ImageVector = when (type) {
    ReservationType.FLIGHT -> Icons.Outlined.Flight
    ReservationType.LODGING -> Icons.Outlined.Hotel
    ReservationType.CAR_RENTAL -> Icons.Outlined.DirectionsCar
    ReservationType.RESTAURANT -> Icons.Outlined.Restaurant
    ReservationType.EVENT -> Icons.Outlined.LocalActivity
    ReservationType.CRUISE -> Icons.Outlined.DirectionsBoat
}

/**
 * When a booking happens, in the local time it states: "Thu, Nov 12, 2026 · 8:05 AM – 11:20 AM", or the days of a
 * stay.
 */
private fun whenText(reservation: Reservation): String {
    val start = reservation.start
    val end = reservation.end
    val startText = "${start.local.format(DAY_FORMAT)}, ${start.local.format(DATE_FORMAT)}" +
        if (start.hasTime) " · ${start.local.format(TIME_FORMAT)}" else ""

    return when {
        end == null -> startText

        end.date == start.date && end.hasTime -> "$startText – ${end.local.format(TIME_FORMAT)}"

        else -> "$startText – ${end.local.format(DAY_FORMAT)}, ${end.local.format(DATE_FORMAT)}" +
            if (end.hasTime) " · ${end.local.format(TIME_FORMAT)}" else ""
    }
}

private fun dateRange(first: LocalDate, last: LocalDate): String =
    if (first == last) first.format(DATE_FORMAT) else "${first.format(DATE_FORMAT)} – ${last.format(DATE_FORMAT)}"

/**
 * Opens the calendar app's new-event screen filled in with the booking; the reader saves it there. No calendar
 * permission is needed for this.
 */
private fun openCalendar(context: Context, reservation: Reservation) {
    val event = calendarEventOf(reservation, context.getString(R.string.trips_confirmation_label))
    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, event.title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.isAllDay)
        .putExtra(CalendarContract.Events.DESCRIPTION, event.description)
    event.location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }

    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.trips_no_calendar, Toast.LENGTH_SHORT).show()
    }
}
