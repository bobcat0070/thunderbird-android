package net.thunderbird.feature.trips.internal

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.button.ButtonFilled
import net.thunderbird.components.ui.bolt.atom.button.ButtonIcon
import net.thunderbird.components.ui.bolt.atom.icon.Icons
import net.thunderbird.components.ui.bolt.atom.text.TextBodyLarge
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextBodySmall
import net.thunderbird.components.ui.bolt.molecule.input.TextInput
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.core.ui.contract.mvi.observe
import net.thunderbird.feature.trips.internal.TravelSendersContract.Effect
import net.thunderbird.feature.trips.internal.TravelSendersContract.Event
import net.thunderbird.feature.trips.internal.TravelSendersContract.State
import org.koin.compose.viewmodel.koinViewModel

internal interface TravelSendersContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param newDomain what is being typed into the add field.
     * @param markedMailCount how many single mails the reader marked as trip mail.
     */
    data class State(
        val senders: List<ListedSender> = emptyList(),
        val newDomain: String = "",
        val markedMailCount: Int = 0,
    )

    sealed interface Event {
        data object Load : Event
        data class NewDomainChanged(val domain: String) : Event
        data object AddClicked : Event
        data class RemoveClicked(val domain: String) : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
        data object NotADomain : Effect
    }
}

/**
 * Shows the senders whose mail Trips reads for bookings, and takes new ones typed in.
 */
internal class TravelSendersViewModel(
    private val senders: TravelSenderStore,
) : BaseViewModel<State, Event, Effect>(State()), TravelSendersContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            Event.Load -> handleOneTimeEvent(event) { load() }

            is Event.NewDomainChanged -> updateState { it.copy(newDomain = event.domain) }

            Event.AddClicked -> add()

            is Event.RemoveClicked -> {
                senders.setListed(event.domain, isListed = false)
                load()
            }

            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun load() {
        updateState {
            it.copy(senders = senders.listed(), markedMailCount = senders.markedMessageIds().size)
        }
    }

    private fun add() {
        val typed = state.value.newDomain.trim().removePrefix("@").substringAfterLast('@')
        val domain = normalizedDomain(typed)
        if (domain == null) {
            emitEffect(Effect.NotADomain)
            return
        }

        senders.setListed(domain, isListed = true)
        updateState { it.copy(newDomain = "") }
        load()
    }
}

@Composable
internal fun TravelSendersScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TravelSendersContract.ViewModel = koinViewModel<TravelSendersViewModel>(),
) {
    val context = LocalContext.current
    val resources = context.resources
    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()

            Effect.NotADomain -> Toast.makeText(
                context,
                resources.getString(R.string.trips_travel_senders_not_a_domain),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        dispatch(Event.Load)
    }

    BackHandler {
        dispatch(Event.BackClicked)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBackButton(
                title = stringResource(R.string.trips_travel_senders_title),
                onBackClick = { dispatch(Event.BackClicked) },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = BoltTheme.spacings.double),
        ) {
            item(key = "intro") {
                AddSender(state = state.value, onEvent = dispatch)
                DividerHorizontal()
            }
            items(state.value.senders, key = { it.domain }) { sender ->
                SenderRow(sender = sender, onEvent = dispatch)
            }
        }
    }
}

@Composable
private fun AddSender(
    state: State,
    onEvent: (Event) -> Unit,
) {
    Column(
        modifier = Modifier.padding(BoltTheme.spacings.double),
        verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
    ) {
        TextBodyMedium(text = stringResource(R.string.trips_travel_senders_intro))
        if (state.markedMailCount > 0) {
            TextBodyMedium(
                text = pluralStringResource(
                    R.plurals.trips_travel_senders_marked_mail,
                    state.markedMailCount,
                    state.markedMailCount,
                ),
            )
        }
        TextInput(
            text = state.newDomain,
            onTextChange = { onEvent(Event.NewDomainChanged(it)) },
            label = stringResource(R.string.trips_travel_senders_domain),
            modifier = Modifier.fillMaxWidth(),
        )
        ButtonFilled(
            text = stringResource(R.string.trips_travel_senders_add),
            onClick = { onEvent(Event.AddClicked) },
            enabled = state.newDomain.isNotBlank(),
        )
    }
}

@Composable
private fun SenderRow(
    sender: ListedSender,
    onEvent: (Event) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = BoltTheme.spacings.double, top = BoltTheme.spacings.half),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TextBodyLarge(text = sender.domain)
            val kind = sender.type?.let { stringResource(typeName(it)) }
            val added = if (sender.isAddedByReader) stringResource(R.string.trips_travel_senders_added) else null
            listOfNotNull(kind, added).takeIf { it.isNotEmpty() }?.let {
                TextBodySmall(text = it.joinToString(" · "))
            }
        }
        ButtonIcon(
            onClick = { onEvent(Event.RemoveClicked(sender.domain)) },
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(R.string.trips_travel_senders_remove, sender.domain),
        )
    }
}

private fun typeName(type: ReservationType): Int = when (type) {
    ReservationType.FLIGHT -> R.string.trips_kind_flights
    ReservationType.LODGING -> R.string.trips_kind_stays
    ReservationType.CAR_RENTAL -> R.string.trips_kind_car_rentals
    ReservationType.RESTAURANT -> R.string.trips_kind_restaurants
    ReservationType.EVENT -> R.string.trips_kind_events
    ReservationType.CRUISE -> R.string.trips_kind_cruises
}
