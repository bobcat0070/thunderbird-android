package net.thunderbird.feature.blocklist.internal

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import net.thunderbird.core.preference.blocklist.DomainBlocklistSettings
import net.thunderbird.core.preference.blocklist.DomainBlocklistTier
import net.thunderbird.feature.blocklist.BlocklistKind
import net.thunderbird.feature.blocklist.BlocklistMatch

class DefaultDomainBlocklistTest {
    private var settings = DomainBlocklistSettings()
    private val testSubject = DefaultDomainBlocklist { settings }

    private val normal = requireNotNull(
        HashedDomainList.fromLines(
            """
            # Title: test list
            ads.example.com
            tracker.example
            Telemetry.Shop.Example
            """.trimIndent().lineSequence(),
        ),
    )
    private val threats = requireNotNull(HashedDomainList.fromLines(sequenceOf("phish.example", "tracker.example")))

    @Test
    fun `a listed domain and its subdomains should match`() {
        testSubject.replace(mapOf(HagezeiList.NORMAL to normal))

        assertThat(testSubject.match("tracker.example")).isEqualTo(
            BlocklistMatch("tracker.example", BlocklistKind.GENERAL, "Normal"),
        )
        assertThat(testSubject.match("A.B.Tracker.Example.")).isEqualTo(
            BlocklistMatch("tracker.example", BlocklistKind.GENERAL, "Normal"),
        )
        assertThat(testSubject.match("telemetry.shop.example")).isNotNull()
    }

    @Test
    fun `a parent of a listed domain should not match`() {
        testSubject.replace(mapOf(HagezeiList.NORMAL to normal))

        assertThat(testSubject.match("example.com")).isNull()
        assertThat(testSubject.match("shop.example")).isNull()
        assertThat(testSubject.match("other.example.com")).isNull()
    }

    @Test
    fun `an email address should be looked up by its domain`() {
        testSubject.replace(mapOf(HagezeiList.NORMAL to normal))

        assertThat(testSubject.match("news@mail.tracker.example")?.listedDomain).isEqualTo("tracker.example")
    }

    @Test
    fun `a domain on the threat list should be reported as a threat even when also on the tier list`() {
        testSubject.replace(mapOf(HagezeiList.NORMAL to normal, HagezeiList.THREATS to threats))

        assertThat(testSubject.match("x.tracker.example")).isEqualTo(
            BlocklistMatch("tracker.example", BlocklistKind.THREAT, "Threat Intelligence Feeds"),
        )
    }

    @Test
    fun `lists the settings turned off should not be consulted`() {
        testSubject.replace(mapOf(HagezeiList.NORMAL to normal, HagezeiList.THREATS to threats))

        settings = DomainBlocklistSettings(tier = DomainBlocklistTier.OFF, isThreatListEnabled = false)
        assertThat(testSubject.match("phish.example")).isNull()
        assertThat(testSubject.match("ads.example.com")).isNull()

        settings = DomainBlocklistSettings(tier = DomainBlocklistTier.PRO, isThreatListEnabled = true)
        assertThat(testSubject.match("ads.example.com")).isNull()
        assertThat(testSubject.match("phish.example")?.kind).isEqualTo(BlocklistKind.THREAT)
    }

    @Test
    fun `nothing should match before any list is loaded`() {
        assertThat(testSubject.match("tracker.example")).isNull()
    }

    @Test
    fun `hosts that are not domain names should not match`() {
        testSubject.replace(mapOf(HagezeiList.NORMAL to normal))

        assertThat(testSubject.match("localhost")).isNull()
        assertThat(testSubject.match("")).isNull()
        assertThat(testSubject.match("tracker..example")).isNull()
    }

    @Test
    fun `a list should survive being written and read back`() {
        val decoded = requireNotNull(HashedDomainList.decode(normal.encode()))

        assertThat(decoded.size).isEqualTo(3)
        assertThat("ads.example.com" in decoded).isTrue()
        assertThat("example.com" in decoded).isFalse()
    }

    @Test
    fun `a damaged or foreign file should not be read as a list`() {
        val bytes = normal.encode()

        assertThat(HashedDomainList.decode(bytes.copyOf(bytes.size - 1))).isNull()
        assertThat(HashedDomainList.decode("not a list at all".encodeToByteArray())).isNull()
        assertThat(HashedDomainList.decode(ByteArray(0))).isNull()
    }

    @Test
    fun `a list with only comments should give nothing`() {
        assertThat(HashedDomainList.fromLines(sequenceOf("# Title", "", "  "))).isNull()
    }

    @Test
    fun `duplicate entries should be kept once`() {
        val list = requireNotNull(HashedDomainList.fromLines(sequenceOf("a.example", "A.example", "a.example")))

        assertThat(list.size).isEqualTo(1)
    }
}
