package net.thunderbird.feature.plusaddress.internal

import assertk.assertThat
import assertk.assertions.containsOnly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test
import net.thunderbird.feature.plusaddress.PlusAddressDecisionSnapshot
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesPlusAddressDecisionsTest {
    private val testSubject = SharedPreferencesPlusAddressDecisions(RuntimeEnvironment.getApplication())

    @Test
    fun `decisions should survive a round trip`() {
        testSubject.allowSender("Reader+Shop@mail.example", "Partner.example")
        testSubject.setIgnored("reader+lists@mail.example", isIgnored = true)

        val result = testSubject.exportDecisions()

        assertThat(result).isEqualTo(
            PlusAddressDecisionSnapshot(
                allowedSenders = mapOf("reader+shop@mail.example" to setOf("partner.example")),
                ignored = setOf("reader+lists@mail.example"),
            ),
        )
    }

    @Test
    fun `checking an address again should drop it from the ignored ones`() {
        testSubject.setIgnored("reader+lists@mail.example", isIgnored = true)

        testSubject.setIgnored("reader+lists@mail.example", isIgnored = false)

        assertThat(testSubject.decisions().ignored).isEmpty()
    }

    @Test
    fun `import should add to what is here and drop what is not a plus address or a domain`() {
        testSubject.allowSender("reader+shop@mail.example", "partner.example")

        testSubject.importDecisions(
            PlusAddressDecisionSnapshot(
                allowedSenders = mapOf(
                    "reader+shop@mail.example" to setOf("other.example", "not a domain"),
                    "reader@mail.example" to setOf("plain.example"),
                    "reader+x|y@mail.example" to setOf("pipe.example"),
                ),
                ignored = setOf("reader+lists@mail.example", "no-plus@mail.example"),
            ),
        )

        val result = testSubject.decisions()
        assertThat(result.allowedSenders).isEqualTo(
            mapOf("reader+shop@mail.example" to setOf("partner.example", "other.example")),
        )
        assertThat(result.ignored).containsOnly("reader+lists@mail.example")
    }
}
