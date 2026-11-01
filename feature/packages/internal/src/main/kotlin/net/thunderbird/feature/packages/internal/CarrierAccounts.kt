package net.thunderbird.feature.packages.internal

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import net.thunderbird.components.ui.bolt.atom.DividerHorizontal
import net.thunderbird.components.ui.bolt.atom.button.ButtonFilled
import net.thunderbird.components.ui.bolt.atom.button.ButtonText
import net.thunderbird.components.ui.bolt.atom.text.TextBodyMedium
import net.thunderbird.components.ui.bolt.atom.text.TextTitleMedium
import net.thunderbird.components.ui.bolt.atom.textfield.TextFieldOutlinedPassword
import net.thunderbird.components.ui.bolt.molecule.input.TextInput
import net.thunderbird.components.ui.bolt.organism.TopAppBarWithBackButton
import net.thunderbird.components.ui.bolt.template.Scaffold
import net.thunderbird.components.ui.bolt.theme.BoltTheme
import net.thunderbird.core.ui.contract.mvi.BaseViewModel
import net.thunderbird.core.ui.contract.mvi.UnidirectionalViewModel
import net.thunderbird.core.ui.contract.mvi.observe
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.internal.CarrierAccountsContract.Effect
import net.thunderbird.feature.packages.internal.CarrierAccountsContract.Event
import net.thunderbird.feature.packages.internal.CarrierAccountsContract.Fields
import net.thunderbird.feature.packages.internal.CarrierAccountsContract.State
import net.thunderbird.feature.packages.internal.carrier.CarrierCredentials
import net.thunderbird.feature.packages.internal.carrier.CarrierCredentialsStore
import net.thunderbird.feature.packages.internal.carrier.LIVE_TRACKING_CARRIERS
import org.koin.compose.viewmodel.koinViewModel

internal interface CarrierAccountsContract {

    interface ViewModel : UnidirectionalViewModel<State, Event, Effect>

    /**
     * @param isSaved whether the carrier has credentials stored; the fields show what is being typed, and are empty
     *   for a saved carrier so the secret is never shown again.
     */
    data class Fields(
        val clientId: String = "",
        val clientSecret: String = "",
        val isSaved: Boolean = false,
    )

    data class State(
        val fields: Map<ParcelCarrier, Fields> = emptyMap(),
    )

    sealed interface Event {
        data object Load : Event
        data class ClientIdChanged(val carrier: ParcelCarrier, val clientId: String) : Event
        data class ClientSecretChanged(val carrier: ParcelCarrier, val clientSecret: String) : Event
        data class SaveClicked(val carrier: ParcelCarrier) : Event
        data class ForgetClicked(val carrier: ParcelCarrier) : Event
        data object BackClicked : Event
    }

    sealed interface Effect {
        data object NavigateBack : Effect
        data class Saved(val carrier: ParcelCarrier) : Effect
        data class Forgotten(val carrier: ParcelCarrier) : Effect
    }
}

/**
 * Takes the reader's own developer credentials for each carrier's tracking API. Nothing is sent anywhere here; the
 * credentials are used when the Packages screen asks the carrier about a parcel.
 */
internal class CarrierAccountsViewModel(
    private val credentialsStore: CarrierCredentialsStore,
) : BaseViewModel<State, Event, Effect>(State()), CarrierAccountsContract.ViewModel {

    override fun event(event: Event) {
        when (event) {
            Event.Load -> handleOneTimeEvent(event) { load() }
            is Event.ClientIdChanged -> change(event.carrier) { it.copy(clientId = event.clientId) }
            is Event.ClientSecretChanged -> change(event.carrier) { it.copy(clientSecret = event.clientSecret) }
            is Event.SaveClicked -> save(event.carrier)
            is Event.ForgetClicked -> forget(event.carrier)
            Event.BackClicked -> emitEffect(Effect.NavigateBack)
        }
    }

    private fun load() {
        updateState {
            it.copy(fields = LIVE_TRACKING_CARRIERS.associateWith { carrier -> savedFields(carrier) })
        }
    }

    private fun savedFields(carrier: ParcelCarrier) = Fields(isSaved = credentialsStore.get(carrier) != null)

    private fun change(carrier: ParcelCarrier, change: (Fields) -> Fields) {
        updateState { state ->
            val fields = state.fields[carrier] ?: Fields()
            state.copy(fields = state.fields + (carrier to change(fields)))
        }
    }

    private fun save(carrier: ParcelCarrier) {
        val fields = state.value.fields[carrier] ?: return
        if (fields.clientId.isBlank() || fields.clientSecret.isBlank()) return

        credentialsStore.set(carrier, CarrierCredentials(fields.clientId, fields.clientSecret))
        updateState { it.copy(fields = it.fields + (carrier to savedFields(carrier))) }
        emitEffect(Effect.Saved(carrier))
    }

    private fun forget(carrier: ParcelCarrier) {
        credentialsStore.set(carrier, null)
        updateState { it.copy(fields = it.fields + (carrier to savedFields(carrier))) }
        emitEffect(Effect.Forgotten(carrier))
    }
}

@Composable
internal fun CarrierAccountsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CarrierAccountsContract.ViewModel = koinViewModel<CarrierAccountsViewModel>(),
) {
    val context = LocalContext.current
    val resources = context.resources
    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            Effect.NavigateBack -> onBack()

            is Effect.Saved -> Toast.makeText(
                context,
                resources.getString(R.string.packages_carrier_saved, carrierName(effect.carrier)),
                Toast.LENGTH_SHORT,
            ).show()

            is Effect.Forgotten -> Toast.makeText(
                context,
                resources.getString(R.string.packages_carrier_forgotten, carrierName(effect.carrier)),
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
                title = stringResource(R.string.packages_carrier_accounts_title),
                onBackClick = { dispatch(Event.BackClicked) },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(BoltTheme.spacings.double),
            verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default),
        ) {
            TextBodyMedium(text = stringResource(R.string.packages_carrier_accounts_intro))

            LIVE_TRACKING_CARRIERS.forEach { carrier ->
                DividerHorizontal()
                CarrierFields(
                    carrier = carrier,
                    fields = state.value.fields[carrier] ?: Fields(),
                    onEvent = dispatch,
                )
            }
        }
    }
}

@Composable
private fun CarrierFields(
    carrier: ParcelCarrier,
    fields: Fields,
    onEvent: (Event) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BoltTheme.spacings.half)) {
        TextTitleMedium(text = carrierName(carrier))
        TextBodyMedium(
            text = if (fields.isSaved) {
                stringResource(R.string.packages_carrier_saved_state)
            } else {
                stringResource(R.string.packages_carrier_portal, developerPortal(carrier))
            },
        )
        TextInput(
            text = fields.clientId,
            onTextChange = { onEvent(Event.ClientIdChanged(carrier, it)) },
            label = stringResource(R.string.packages_carrier_client_id),
            modifier = Modifier.fillMaxWidth(),
        )
        TextFieldOutlinedPassword(
            value = fields.clientSecret,
            onValueChange = { onEvent(Event.ClientSecretChanged(carrier, it)) },
            label = stringResource(R.string.packages_carrier_client_secret),
            modifier = Modifier.fillMaxWidth().padding(horizontal = BoltTheme.spacings.double),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(BoltTheme.spacings.default)) {
            ButtonFilled(
                text = stringResource(R.string.packages_carrier_save),
                onClick = { onEvent(Event.SaveClicked(carrier)) },
                enabled = fields.clientId.isNotBlank() && fields.clientSecret.isNotBlank(),
            )
            if (fields.isSaved) {
                ButtonText(
                    text = stringResource(R.string.packages_carrier_forget),
                    onClick = { onEvent(Event.ForgetClicked(carrier)) },
                )
            }
        }
    }
}

private fun developerPortal(carrier: ParcelCarrier): String = when (carrier) {
    ParcelCarrier.UPS -> "developer.ups.com"
    ParcelCarrier.FEDEX -> "developer.fedex.com"
    ParcelCarrier.USPS -> "developers.usps.com"
    ParcelCarrier.DHL -> "developer.dhl.com"
}
