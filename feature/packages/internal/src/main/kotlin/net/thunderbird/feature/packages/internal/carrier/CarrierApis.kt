package net.thunderbird.feature.packages.internal.carrier

import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.thunderbird.feature.packages.internal.ParcelStatus
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

/**
 * UPS Tracking API, version 1: OAuth client credentials, then one request per tracking number.
 */
internal object UpsApi : CarrierApi {
    private const val TOKEN_URL = "https://onlinetools.ups.com/security/v1/oauth/token"
    private const val TRACKING_URL = "https://onlinetools.ups.com/api/track/v1/details/"

    override fun tokenRequest(credentials: CarrierCredentials): Request = Request.Builder()
        .url(TOKEN_URL)
        .header("Authorization", Credentials.basic(credentials.clientId, credentials.clientSecret))
        .post(FormBody.Builder().add("grant_type", "client_credentials").build())
        .build()

    override fun trackingRequest(number: String, token: String): Request = Request.Builder()
        .url(
            (TRACKING_URL + number).toHttpUrl().newBuilder()
                .addQueryParameter("locale", "en_US")
                .addQueryParameter("returnSignature", "false")
                .build(),
        )
        .header("Authorization", "Bearer $token")
        .header("transId", UUID.randomUUID().toString())
        .header("transactionSrc", "thunderbird")
        .get()
        .build()

    /**
     * Reads `trackResponse.shipment[0].package[0]`: its `currentStatus`, or else its newest `activity`, and the
     * scheduled delivery date.
     */
    @Suppress("ReturnCount")
    override fun parseTracking(body: JsonElement): LiveStatus? {
        val parcel = body.child("trackResponse").first("shipment").first("package") ?: return null
        val latestActivity = parcel.first("activity").child("status")
        val description = parcel.child("currentStatus").string("description")
            ?: latestActivity.string("description")
            ?: return null
        val status = when (latestActivity.string("type")) {
            "D" -> ParcelStatus.DELIVERED
            "X" -> ParcelStatus.PROBLEM
            else -> statusFromDescription(description)
        }
        val expectedDelivery = parcel.list("deliveryDate")
            .firstOrNull { it.string("type") != "DEL" }
            ?.string("date")
            ?.let(::formatCompactDate)

        return LiveStatus(status, description.toSentenceCase(), expectedDelivery)
    }
}

/**
 * FedEx Track API, version 1: OAuth client credentials, then a POST naming the tracking number.
 */
internal object FedexApi : CarrierApi {
    private const val TOKEN_URL = "https://apis.fedex.com/oauth/token"
    private const val TRACKING_URL = "https://apis.fedex.com/track/v1/trackingnumbers"

    override fun tokenRequest(credentials: CarrierCredentials): Request = Request.Builder()
        .url(TOKEN_URL)
        .post(
            FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", credentials.clientId)
                .add("client_secret", credentials.clientSecret)
                .build(),
        )
        .build()

    override fun trackingRequest(number: String, token: String): Request {
        val body = buildJsonObject {
            put("includeDetailedScans", false)
            put(
                "trackingInfo",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            putJsonObject("trackingNumberInfo") { put("trackingNumber", number) }
                        },
                    )
                },
            )
        }
        return Request.Builder()
            .url(TRACKING_URL)
            .header("Authorization", "Bearer $token")
            .header("X-locale", "en_US")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * Reads `output.completeTrackResults[0].trackResults[0]`: its `latestStatusDetail` and estimated delivery.
     */
    @Suppress("ReturnCount")
    override fun parseTracking(body: JsonElement): LiveStatus? {
        val result = body.child("output").first("completeTrackResults").first("trackResults") ?: return null
        if (result.child("error") != null) return null

        val detail = result.child("latestStatusDetail")
        val description = detail.string("statusByLocale") ?: detail.string("description") ?: return null
        val status = when (detail.string("code") ?: detail.string("derivedCode")) {
            "DL" -> ParcelStatus.DELIVERED
            "OD" -> ParcelStatus.OUT_FOR_DELIVERY
            "DE", "SE", "CA", "RS" -> ParcelStatus.PROBLEM
            else -> statusFromDescription(description)
        }
        val expectedDelivery = result.list("dateAndTimes")
            .firstOrNull { it.string("type") == "ESTIMATED_DELIVERY" }
            ?.string("dateTime")
            ?.take(ISO_DATE_LENGTH)
            ?: result.child("estimatedDeliveryTimeWindow").child("window").string("ends")?.take(ISO_DATE_LENGTH)

        return LiveStatus(status, description, expectedDelivery)
    }
}

/**
 * USPS Tracking API, version 3: OAuth client credentials, then one request per tracking number.
 */
internal object UspsApi : CarrierApi {
    private const val TOKEN_URL = "https://apis.usps.com/oauth2/v3/token"
    private const val TRACKING_URL = "https://apis.usps.com/tracking/v3/tracking/"

    override fun tokenRequest(credentials: CarrierCredentials): Request {
        val body = buildJsonObject {
            put("grant_type", "client_credentials")
            put("client_id", credentials.clientId)
            put("client_secret", credentials.clientSecret)
        }
        return Request.Builder()
            .url(TOKEN_URL)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    override fun trackingRequest(number: String, token: String): Request = Request.Builder()
        .url((TRACKING_URL + number).toHttpUrl().newBuilder().addQueryParameter("expand", "SUMMARY").build())
        .header("Authorization", "Bearer $token")
        .get()
        .build()

    /**
     * Reads `statusCategory`, `status` and `expectedDeliveryDate`.
     */
    @Suppress("ReturnCount")
    override fun parseTracking(body: JsonElement): LiveStatus? {
        val description = body.string("status") ?: body.string("statusCategory") ?: return null
        val status = statusFromDescription(body.string("statusCategory") ?: description)
        val expectedDelivery = body.string("expectedDeliveryDate")?.take(ISO_DATE_LENGTH)

        return LiveStatus(status, description, expectedDelivery)
    }
}

private const val ISO_DATE_LENGTH = 10
private const val COMPACT_DATE_LENGTH = 8

/**
 * `20261103` as `2026-11-03`.
 */
@Suppress("MagicNumber")
private fun formatCompactDate(date: String): String? {
    if (date.length != COMPACT_DATE_LENGTH || !date.all(Char::isDigit)) return null
    return "${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}"
}

/**
 * UPS writes some statuses in capitals ("DELIVERED").
 */
private fun String.toSentenceCase(): String =
    if (any(Char::isLowerCase)) this else lowercase().replaceFirstChar(Char::uppercase)
