package net.thunderbird.backend.graph.command

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import net.thunderbird.backend.graph.FakeOAuth2TokenProvider
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.core.logging.testing.TestLogger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class GraphContactSyncTest {
    private val server = MockWebServer()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a first sync should read the whole address book and remember where it got to`() {
        val store = FakeContactStore()
        server.enqueue(
            MockResponse().setBody(
                """
                {"value":[
                  {"id":"1","displayName":"Sam Vimes","emailAddresses":[{"address":"sam@example.com"}]}
                ],"@odata.deltaLink":"${server.url("/v1.0/")}me/contacts/delta?${'$'}deltatoken=t1"}
                """.trimIndent(),
            ),
        )

        createTestSubject(store).syncIfDue()

        assertThat(store.changed.map { it.addresses }).containsExactly(listOf("sam@example.com"))
        assertThat(store.changed.first().displayName).isEqualTo("Sam Vimes")
        assertThat(store.savedDeltaLink).isEqualTo("${server.url("/v1.0/")}me/contacts/delta?\$deltatoken=t1")
    }

    @Test
    fun `a later sync should resume from the stored delta link`() {
        val store = FakeContactStore(deltaLink = "${server.url("/v1.0/")}me/contacts/delta?\$deltatoken=t1")
        server.enqueue(MockResponse().setBody("""{"value":[],"@odata.deltaLink":"${server.url("/v1.0/")}d?t=2"}"""))

        createTestSubject(store).syncIfDue()

        assertThat(server.takeRequest().path).isEqualTo("/v1.0/me/contacts/delta?\$deltatoken=t1")
    }

    @Test
    fun `a deleted contact should be reported as removed`() {
        val store = FakeContactStore()
        server.enqueue(
            MockResponse().setBody(
                """
                {"value":[
                  {"id":"1","emailAddresses":[{"address":"gone@example.com"}],"@removed":{"reason":"deleted"}}
                ],"@odata.deltaLink":"${server.url("/v1.0/")}d?t=2"}
                """.trimIndent(),
            ),
        )

        createTestSubject(store).syncIfDue()

        assertThat(store.reported.single().isRemoved).isTrue()
    }

    @Test
    fun `several pages should be followed before the round ends`() {
        val store = FakeContactStore()
        server.enqueue(
            MockResponse().setBody(
                """
                {"value":[{"id":"1","emailAddresses":[{"address":"one@example.com"}]}],
                 "@odata.nextLink":"${server.url("/v1.0/")}me/contacts/delta?${'$'}skiptoken=s1"}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {"value":[{"id":"2","emailAddresses":[{"address":"two@example.com"}]}],
                 "@odata.deltaLink":"${server.url("/v1.0/")}d?t=2"}
                """.trimIndent(),
            ),
        )

        createTestSubject(store).syncIfDue()

        assertThat(store.changed.flatMap { it.addresses })
            .containsExactly("one@example.com", "two@example.com")
    }

    @Test
    fun `a store that says it is not due should cause no request`() {
        val store = FakeContactStore(isDue = false)

        createTestSubject(store).syncIfDue()

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a failed sync should not move the delta link on`() {
        // Otherwise the changes this round did not read would be skipped for good.
        val store = FakeContactStore()
        server.enqueue(MockResponse().setResponseCode(500))

        createTestSubject(store).syncIfDue()

        assertThat(store.savedDeltaLink).isNull()
        assertThat(store.reported).isEmpty()
    }

    @Test
    fun `a missing contacts permission should not fail the sync`() {
        // A tenant can refuse the contacts scope while mail works perfectly well.
        val store = FakeContactStore()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"ErrorAccessDenied"}}"""))

        createTestSubject(store).syncIfDue()

        assertThat(store.reported).isEmpty()
    }

    @Test
    fun `a directory search should return the people Graph ranked`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"value":[
                  {"id":"p1","displayName":"Sam Vimes","scoredEmailAddresses":[
                    {"address":"sam@example.com","relevanceScore":9.0}
                  ]},
                  {"id":"p2","displayName":"Samuel Devries","scoredEmailAddresses":[
                    {"address":"samuel@example.com","relevanceScore":2.0}
                  ]}
                ]}
                """.trimIndent(),
            ),
        )

        val found = createTestSubject(FakeContactStore()).searchDirectory("sam")

        assertThat(found.flatMap { it.addresses }).containsExactly("sam@example.com", "samuel@example.com")
    }

    @Test
    fun `a directory search should send the term as a quoted phrase`() {
        // A bare term containing a space is a syntax error to Graph rather than a search for two words.
        server.enqueue(MockResponse().setBody("""{"value":[]}"""))

        createTestSubject(FakeContactStore()).searchDirectory("sam vimes")

        assertThat(server.takeRequest().path).isEqualTo(
            "/v1.0/me/people?%24search=%22sam%20vimes%22&%24select=displayName%2CscoredEmailAddresses&%24top=15",
        )
    }

    @Test
    fun `a failed directory search should report no matches`() {
        server.enqueue(MockResponse().setResponseCode(403))

        assertThat(createTestSubject(FakeContactStore()).searchDirectory("sam")).isEmpty()
    }

    private fun createTestSubject(store: GraphContactStore) = GraphContactSync(
        client = GraphApiClient(
            okHttpClient = OkHttpClient(),
            tokenProvider = FakeOAuth2TokenProvider(),
            baseUrl = server.url("/v1.0/").toString(),
        ),
        store = store,
        logger = TestLogger(),
    )

    private class FakeContactStore(
        private val deltaLink: String? = null,
        private val isDue: Boolean = true,
    ) : GraphContactStore {
        val reported = mutableListOf<GraphContactUpdate>()
        var savedDeltaLink: String? = null

        val changed: List<GraphContactUpdate>
            get() = reported.filterNot { it.isRemoved }

        override fun contactsDeltaLink(): String? = deltaLink

        override fun saveContactsDeltaLink(deltaLink: String?) {
            savedDeltaLink = deltaLink
        }

        override fun isContactSyncDue(): Boolean = isDue

        override fun onContactsChanged(contacts: List<GraphContactUpdate>) {
            reported += contacts
        }
    }
}
