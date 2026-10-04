package net.thunderbird.feature.newsletter.internal

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.ui.testing.coroutines.MainDispatcherHelper
import net.thunderbird.feature.newsletter.NewsletterActions
import net.thunderbird.feature.newsletter.NewsletterSender
import net.thunderbird.feature.newsletter.NewsletterSenderRepository
import net.thunderbird.feature.newsletter.UnsubscribeOutcome
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Action
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Effect
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Event
import net.thunderbird.feature.newsletter.internal.NewsletterSendersContract.Notice

class NewsletterSendersViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mainDispatcher = MainDispatcherHelper(UnconfinedTestDispatcher())

    private val shop = NewsletterSender("news@shop.example", "Shop", 12, latestAt = 2, canUnsubscribe = true)
    private val club = NewsletterSender("hello@club.example", null, 3, latestAt = 1, canUnsubscribe = false)

    private var senders = listOf(shop, club)
    private val repository = NewsletterSenderRepository { senders }
    private val actions = FakeActions()
    private val testSubject by lazy { NewsletterSendersViewModel(repository, actions) }

    @BeforeTest
    fun setUp() {
        mainDispatcher.setUp()
    }

    @AfterTest
    fun tearDown() {
        mainDispatcher.tearDown()
    }

    @Test
    fun `loading should list the senders`() {
        testSubject.event(Event.LoadSenders)

        assertThat(testSubject.state.value.isLoading).isEqualTo(false)
        assertThat(testSubject.state.value.senders).containsExactly(shop, club)
    }

    @Test
    fun `an action should wait for the reader to confirm it`() {
        testSubject.event(Event.LoadSenders)

        testSubject.event(Event.ActionClicked(shop, Action.DELETE_ALL))

        assertThat(actions.calls).isEmpty()
        testSubject.event(Event.DismissClicked)
        assertThat(testSubject.state.value.confirmation).isNull()
        assertThat(actions.calls).isEmpty()
    }

    @Test
    fun `a confirmed archive should archive, say how many, and read the list again`() = runTest {
        testSubject.event(Event.LoadSenders)
        testSubject.event(Event.ActionClicked(shop, Action.ARCHIVE_ALL))
        senders = listOf(club)

        testSubject.effect.test {
            testSubject.event(Event.ConfirmClicked)

            assertThat(awaitItem()).isEqualTo(Effect.ShowNotice(Notice.Archived(12)))
        }
        assertThat(actions.calls).containsExactly("archive news@shop.example")
        assertThat(testSubject.state.value.senders).containsExactly(club)
        assertThat(testSubject.state.value.busyAddress).isNull()
    }

    @Test
    fun `a confirmed unsubscribe the sender accepted should say so`() = runTest {
        testSubject.event(Event.LoadSenders)
        testSubject.event(Event.ActionClicked(shop, Action.UNSUBSCRIBE))

        testSubject.effect.test {
            testSubject.event(Event.ConfirmClicked)

            assertThat(awaitItem()).isEqualTo(Effect.ShowNotice(Notice.Unsubscribed(shop)))
        }
        assertThat(actions.calls).containsExactly("unsubscribe news@shop.example")
    }

    private inner class FakeActions : NewsletterActions {
        val calls = mutableListOf<String>()

        override fun showAllMail(address: String) {
            calls += "show $address"
        }

        override suspend fun unsubscribe(address: String): UnsubscribeOutcome {
            calls += "unsubscribe $address"
            return UnsubscribeOutcome.SENT
        }

        override suspend fun archiveAll(address: String): Int {
            calls += "archive $address"
            return senders.firstOrNull { it.address == address }?.messageCount ?: shop.messageCount
        }

        override suspend fun deleteAll(address: String): Int {
            calls += "delete $address"
            return shop.messageCount
        }
    }
}
