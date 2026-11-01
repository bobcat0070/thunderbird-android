package net.thunderbird.feature.packages.internal.carrier

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test
import kotlinx.serialization.json.Json
import net.thunderbird.feature.packages.internal.ParcelStatus

class CarrierApisTest {
    @Test
    fun `a UPS response should give the current status and scheduled delivery`() {
        val body = Json.parseToJsonElement(
            """
            {"trackResponse":{"shipment":[{"package":[{
              "trackingNumber":"1Z999AA10123456784",
              "deliveryDate":[{"type":"SDD","date":"20261103"}],
              "currentStatus":{"description":"On the Way","code":"005"},
              "activity":[{"status":{"type":"I","description":"Departed from Facility","code":"DP"}}]
            }]}]}}
            """.trimIndent(),
        )

        val status = UpsApi.parseTracking(body)

        assertThat(status).isEqualTo(LiveStatus(ParcelStatus.ON_ITS_WAY, "On the Way", "2026-11-03"))
    }

    @Test
    fun `a delivered UPS parcel should read as delivered`() {
        val body = Json.parseToJsonElement(
            """
            {"trackResponse":{"shipment":[{"package":[{
              "activity":[{"status":{"type":"D","description":"DELIVERED","code":"KB"}}]
            }]}]}}
            """.trimIndent(),
        )

        assertThat(UpsApi.parseTracking(body)).isEqualTo(LiveStatus(ParcelStatus.DELIVERED, "Delivered"))
    }

    @Test
    fun `a FedEx response should give the latest status and estimated delivery`() {
        val body = Json.parseToJsonElement(
            """
            {"output":{"completeTrackResults":[{"trackingNumber":"123456789012","trackResults":[{
              "latestStatusDetail":{"code":"OD","statusByLocale":"On FedEx vehicle for delivery"},
              "dateAndTimes":[{"type":"ESTIMATED_DELIVERY","dateTime":"2026-11-03T20:00:00-08:00"}]
            }]}]}}
            """.trimIndent(),
        )

        assertThat(FedexApi.parseTracking(body)).isEqualTo(
            LiveStatus(ParcelStatus.OUT_FOR_DELIVERY, "On FedEx vehicle for delivery", "2026-11-03"),
        )
    }

    @Test
    fun `a FedEx error for an unknown number should give nothing`() {
        val body = Json.parseToJsonElement(
            """
            {"output":{"completeTrackResults":[{"trackResults":[{
              "error":{"code":"TRACKING.TRACKINGNUMBER.NOTFOUND","message":"not found"}
            }]}]}}
            """.trimIndent(),
        )

        assertThat(FedexApi.parseTracking(body)).isNull()
    }

    @Test
    fun `a USPS response should give the status and expected delivery`() {
        val body = Json.parseToJsonElement(
            """
            {"trackingNumber":"9400100000000000000013","statusCategory":"Delivered",
             "status":"Delivered, In/At Mailbox","expectedDeliveryDate":"2026-11-02T00:00:00Z"}
            """.trimIndent(),
        )

        assertThat(UspsApi.parseTracking(body)).isEqualTo(
            LiveStatus(ParcelStatus.DELIVERED, "Delivered, In/At Mailbox", "2026-11-02"),
        )
    }

    @Test
    fun `carrier wording should map to a status`() {
        assertThat(statusFromDescription("Out For Delivery Today")).isEqualTo(ParcelStatus.OUT_FOR_DELIVERY)
        assertThat(statusFromDescription("Delivery Exception")).isEqualTo(ParcelStatus.PROBLEM)
        assertThat(statusFromDescription("Will be delivered tomorrow")).isEqualTo(ParcelStatus.ON_ITS_WAY)
        assertThat(statusFromDescription("In Transit")).isEqualTo(ParcelStatus.ON_ITS_WAY)
    }
}
