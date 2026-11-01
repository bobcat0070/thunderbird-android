package net.thunderbird.feature.packages.internal.carrier

import java.io.IOException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.internal.ParcelStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Where the carrier says a parcel is.
 *
 * @param description the carrier's own words, as in "On the way" or "Delivered, Front Door/Porch".
 * @param expectedDelivery when the carrier expects to deliver it, in its own format, if it says.
 */
data class LiveStatus(
    val status: ParcelStatus,
    val description: String,
    val expectedDelivery: String? = null,
)

sealed interface LiveResult {
    data class Found(val liveStatus: LiveStatus) : LiveResult

    /** The carrier could not be reached, refused the credentials, or does not know the number. */
    data object Unavailable : LiveResult
}

/**
 * Asks carriers where parcels are.
 */
interface LiveTracker {
    /**
     * The carriers the reader has given credentials for.
     */
    fun carriers(): Set<ParcelCarrier>

    /**
     * @return where the carrier says the parcel is, or `null` when the reader has given no credentials for it.
     */
    suspend fun track(parcel: ParcelNumber): LiveResult?
}

private const val MILLIS_PER_SECOND = 1000L

/**
 * A token is renewed this long before the carrier says it expires, so it never runs out mid-request.
 */
private const val TOKEN_MARGIN_MILLIS = 60_000L

private class AccessToken(val clientId: String, val value: String, val expiresAt: Long)

/**
 * One carrier's OAuth token endpoint and tracking API.
 */
internal interface CarrierApi {
    fun tokenRequest(credentials: CarrierCredentials): Request

    fun trackingRequest(number: String, token: String): Request

    fun parseTracking(body: JsonElement): LiveStatus?
}

/**
 * Tracks parcels through the carriers' own APIs with the reader's credentials. Only the tracking number is sent, and
 * only to the carrier that ships the parcel; nothing is logged.
 */
@OptIn(ExperimentalTime::class)
internal class CarrierApiTracker(
    private val httpClient: OkHttpClient,
    private val credentialsStore: CarrierCredentialsStore,
    private val clock: Clock,
) : LiveTracker {
    private val apis: Map<ParcelCarrier, CarrierApi> = mapOf(
        ParcelCarrier.UPS to UpsApi,
        ParcelCarrier.FEDEX to FedexApi,
        ParcelCarrier.USPS to UspsApi,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = mutableMapOf<ParcelCarrier, AccessToken>()
    private val tokenMutex = Mutex()

    override fun carriers(): Set<ParcelCarrier> =
        apis.keys.filterTo(mutableSetOf()) { credentialsStore.get(it) != null }

    @Suppress("ReturnCount")
    override suspend fun track(parcel: ParcelNumber): LiveResult? {
        val api = apis[parcel.carrier] ?: return null
        val credentials = credentialsStore.get(parcel.carrier) ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val token = token(parcel.carrier, api, credentials)
                val body = execute(api.trackingRequest(parcel.number, token))
                api.parseTracking(body)?.let { LiveResult.Found(it) } ?: LiveResult.Unavailable
            } catch (_: IOException) {
                LiveResult.Unavailable
            } catch (_: SerializationException) {
                LiveResult.Unavailable
            } catch (_: IllegalArgumentException) {
                LiveResult.Unavailable
            }
        }
    }

    private suspend fun token(carrier: ParcelCarrier, api: CarrierApi, credentials: CarrierCredentials): String {
        return tokenMutex.withLock {
            val cached = tokens[carrier]
            if (cached != null && cached.clientId == credentials.clientId && cached.expiresAt > now()) {
                return@withLock cached.value
            }

            val body = execute(api.tokenRequest(credentials)).jsonObjectOrNull()
            val value = body?.string("access_token") ?: throw IOException("No access token")
            val expiresIn = body.string("expires_in")?.toLongOrNull() ?: 0
            tokens[carrier] = AccessToken(
                clientId = credentials.clientId,
                value = value,
                expiresAt = now() + expiresIn * MILLIS_PER_SECOND - TOKEN_MARGIN_MILLIS,
            )
            value
        }
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()

    private fun execute(request: Request): JsonElement {
        httpClient.newCall(request).execute().use { response: Response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return json.parseToJsonElement(response.body.string())
        }
    }
}

internal fun JsonElement?.jsonObjectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.child(name: String): JsonElement? = jsonObjectOrNull()?.get(name)

internal fun JsonElement?.first(name: String): JsonElement? = (child(name) as? JsonArray)?.firstOrNull()

internal fun JsonElement?.list(name: String): List<JsonElement> = (child(name) as? JsonArray).orEmpty()

internal fun JsonElement?.string(name: String): String? =
    (child(name) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

/**
 * Reads a status from a carrier's own words, for carriers whose codes are not documented well enough to rely on.
 */
internal fun statusFromDescription(description: String): ParcelStatus {
    val text = description.lowercase()
    return when {
        "out for delivery" in text -> ParcelStatus.OUT_FOR_DELIVERY
        "delivered" in text && "not delivered" !in text && "be delivered" !in text -> ParcelStatus.DELIVERED
        PROBLEM_WORDS.any { it in text } -> ParcelStatus.PROBLEM
        else -> ParcelStatus.ON_ITS_WAY
    }
}

private val PROBLEM_WORDS = listOf("exception", "delay", "attempt", "held", "return", "alert", "undeliverable")
