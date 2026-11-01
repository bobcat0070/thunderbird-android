package net.thunderbird.feature.packages.internal.carrier

import android.content.Context
import androidx.core.content.edit
import net.thunderbird.feature.packages.ParcelCarrier

/**
 * The carriers whose tracking APIs the reader can sign the app up to with their own developer credentials.
 */
val LIVE_TRACKING_CARRIERS = listOf(ParcelCarrier.UPS, ParcelCarrier.FEDEX, ParcelCarrier.USPS)

/**
 * A developer app's OAuth client credentials, as the carrier's developer portal issues them.
 */
data class CarrierCredentials(
    val clientId: String,
    val clientSecret: String,
)

/**
 * Keeps the reader's carrier credentials on the device. They are never exported with the settings, never logged,
 * and only sent to the carrier that issued them.
 */
interface CarrierCredentialsStore {
    fun get(carrier: ParcelCarrier): CarrierCredentials?

    /**
     * Saves [credentials] for [carrier], or forgets the carrier's when `null`.
     */
    fun set(carrier: ParcelCarrier, credentials: CarrierCredentials?)
}

/**
 * Stores the credentials in the app's private preferences, which Android keeps from other apps; the app opts out of
 * backups, so they do not leave the device either.
 */
internal class SharedPreferencesCarrierCredentialsStore(context: Context) : CarrierCredentialsStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun get(carrier: ParcelCarrier): CarrierCredentials? {
        val clientId = preferences.getString(carrier.key(CLIENT_ID), null)?.takeIf { it.isNotBlank() }
        val clientSecret = preferences.getString(carrier.key(CLIENT_SECRET), null)?.takeIf { it.isNotBlank() }
        return if (clientId != null && clientSecret != null) CarrierCredentials(clientId, clientSecret) else null
    }

    override fun set(carrier: ParcelCarrier, credentials: CarrierCredentials?) {
        preferences.edit {
            if (credentials == null) {
                remove(carrier.key(CLIENT_ID))
                remove(carrier.key(CLIENT_SECRET))
            } else {
                putString(carrier.key(CLIENT_ID), credentials.clientId.trim())
                putString(carrier.key(CLIENT_SECRET), credentials.clientSecret.trim())
            }
        }
    }

    private fun ParcelCarrier.key(field: String) = "${name.lowercase()}.$field"

    private companion object {
        const val PREFERENCES_NAME = "carrier_tracking_credentials"
        const val CLIENT_ID = "client_id"
        const val CLIENT_SECRET = "client_secret"
    }
}
