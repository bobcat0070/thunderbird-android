package net.thunderbird.feature.conversation.internal

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsOnly
import assertk.assertions.isEqualTo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.thunderbird.components.ui.testing.coroutines.MainDispatcherHelper
import net.thunderbird.feature.conversation.ConversationMessage
import net.thunderbird.feature.conversation.ConversationSource
import net.thunderbird.feature.conversation.MessageBody
import net.thunderbird.feature.conversation.internal.ConversationContract.Body
import net.thunderbird.feature.conversation.internal.ConversationContract.Event

class ConversationViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mainDispatcher = MainDispatcherHelper(UnconfinedTestDispatcher())

    private val first = message("1", isRead = true)
    private val second = message("2", isRead = false)
    private val third = message("3", isRead = true)
    private val source = FakeSource(listOf(first, second, third))
    private val testSubject by lazy { ConversationViewModel(source) }

    @BeforeTest
    fun setUp() {
        mainDispatcher.setUp()
    }

    @AfterTest
    fun tearDown() {
        mainDispatcher.tearDown()
    }

    @Test
    fun `unread messages and the newest should open, and be marked read`() {
        testSubject.event(Event.LoadConversation("account", 7))

        val state = testSubject.state.value
        assertThat(state.expanded).containsOnly("2", "3")
        assertThat(state.bodies).isEqualTo(mapOf("2" to Body.Html("<p>2</p>"), "3" to Body.Html("<p>3</p>")))
        assertThat(source.calls).containsExactly("markRead [2]", "body 2", "body 3")
        assertThat(state.messages.single { it.key == "2" }.isRead).isEqualTo(true)
    }

    @Test
    fun `tapping a closed message should open it and load its text once`() {
        testSubject.event(Event.LoadConversation("account", 7))

        testSubject.event(Event.MessageClicked(first))
        testSubject.event(Event.MessageClicked(first))
        testSubject.event(Event.MessageClicked(first))

        assertThat(source.calls.filter { it == "body 1" }).containsExactly("body 1")
        assertThat(testSubject.state.value.expanded).containsOnly("1", "2", "3")
    }

    @Test
    fun `reply and links should go to the source`() {
        testSubject.event(Event.LoadConversation("account", 7))
        source.calls.clear()

        testSubject.event(Event.ReplyClicked(third, toAll = true))
        testSubject.event(Event.LinkClicked(third, "https://shop.example/"))

        assertThat(source.calls).containsExactly("reply 3 true", "link https://shop.example/ sender3@example.com")
    }

    private fun message(key: String, isRead: Boolean) = ConversationMessage(
        key = key,
        senderName = "Sender $key",
        senderAddress = "sender$key@example.com",
        date = key.toLong(),
        subject = "Plans",
        preview = "Preview $key",
        isRead = isRead,
        hasAttachments = false,
        hasWarning = false,
    )

    private class FakeSource(private val messages: List<ConversationMessage>) : ConversationSource {
        val calls = mutableListOf<String>()

        override suspend fun messages(accountId: String, threadRoot: Long) = messages

        override suspend fun body(key: String): MessageBody {
            calls += "body $key"
            return MessageBody.Html("<p>$key</p>")
        }

        override fun markRead(keys: List<String>) {
            calls += "markRead $keys"
        }

        override fun reply(key: String, toAll: Boolean) {
            calls += "reply $key $toAll"
        }

        override fun forward(key: String) {
            calls += "forward $key"
        }

        override fun openMessage(key: String) {
            calls += "open $key"
        }

        override fun openLink(url: String, senderAddress: String?) {
            calls += "link $url $senderAddress"
        }

        override fun showAsList(accountId: String, threadRoot: Long) {
            calls += "list $accountId $threadRoot"
        }
    }
}
