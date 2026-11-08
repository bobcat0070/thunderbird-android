package net.thunderbird.app.common.feature.packages

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import net.thunderbird.app.common.feature.packages.ShippingMailCache.CachedMail
import net.thunderbird.feature.packages.ParcelCarrier
import net.thunderbird.feature.packages.ParcelNumber
import net.thunderbird.feature.packages.ShippingMail
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class ShippingMailCacheTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file: File by lazy { File(folder.root, "mails.json") }

    private val mail = ShippingMail(
        key = "mail1",
        date = 1_000L,
        senderName = "Example Shop",
        subject = "Your order has shipped",
        parcels = listOf(
            ParcelNumber(
                ParcelCarrier.UPS,
                "1Z999AA10123456784",
                "https://www.ups.com/track?tracknum=1Z999AA10123456784",
            ),
        ),
        text = "Your order has shipped",
        senderAddress = "orders@shop.example",
    )

    @Test
    fun `mails should be read back after a restart, with those that named no parcel`() = runTest {
        val before = ShippingMailCache(file, appUpdatedAt = { 1L })
        before.load()
        before["mail1"] = CachedMail(mail)
        before["newsletter"] = CachedMail(null)
        before.save(keys = setOf("mail1", "newsletter"))

        val testSubject = ShippingMailCache(file, appUpdatedAt = { 1L })
        testSubject.load()

        assertThat(testSubject["mail1"]?.mail).isEqualTo(mail)
        assertThat(testSubject["newsletter"]).isNotNull().transform { it.mail }.isNull()
    }

    @Test
    fun `mails should be dropped after the app is updated`() = runTest {
        val before = ShippingMailCache(file, appUpdatedAt = { 1L })
        before["mail1"] = CachedMail(mail)
        before.save(keys = setOf("mail1"))

        val testSubject = ShippingMailCache(file, appUpdatedAt = { 2L })
        testSubject.load()

        assertThat(testSubject["mail1"]).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `mails no longer read should not be kept`() = runTest {
        val before = ShippingMailCache(file, appUpdatedAt = { 1L })
        before["mail1"] = CachedMail(mail)
        before["deleted"] = CachedMail(mail.copy(key = "deleted"))
        before.save(keys = setOf("mail1"))

        val testSubject = ShippingMailCache(file, appUpdatedAt = { 1L })
        testSubject.load()

        assertThat(testSubject["deleted"]).isNull()
        assertThat(file.readText().contains("deleted")).isFalse()
    }

    @Test
    fun `an unreadable file should be ignored`() = runTest {
        file.writeText("[]")

        val testSubject = ShippingMailCache(file, appUpdatedAt = { 1L })
        testSubject.load()

        assertThat(testSubject["mail1"]).isNull()
    }
}
