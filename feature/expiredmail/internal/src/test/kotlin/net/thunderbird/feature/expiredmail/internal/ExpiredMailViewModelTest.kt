package net.thunderbird.feature.expiredmail.internal

import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import net.thunderbird.components.ui.testing.coroutines.MainDispatcherHelper
import net.thunderbird.feature.expiredmail.ExpiredKind
import net.thunderbird.feature.expiredmail.ExpiredMail
import net.thunderbird.feature.expiredmail.ExpiredMailSource
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Action
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Confirmation
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Effect
import net.thunderbird.feature.expiredmail.internal.ExpiredMailContract.Event

class ExpiredMailViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mainDispatcher = MainDispatcherHelper(UnconfinedTestDispatcher())

    private val code1 = mail("c1", ExpiredKind.SIGN_IN)
    private val code2 = mail("c2", ExpiredKind.SIGN_IN)
    private val parcel = mail("p1", ExpiredKind.DELIVERED_PARCEL)
    private val source = FakeSource(listOf(code1, parcel, code2))
    private val testSubject by lazy { ExpiredMailViewModel(source) }

    @BeforeTest
    fun setUp() {
        mainDispatcher.setUp()
    }

    @AfterTest
    fun tearDown() {
        mainDispatcher.tearDown()
    }

    @Test
    fun `loading should group the mail by why it expired`() {
        testSubject.event(Event.LoadMail)

        assertThat(testSubject.state.value.groups).containsExactly(
            ExpiredKind.SIGN_IN to listOf(code1, code2),
            ExpiredKind.DELIVERED_PARCEL to listOf(parcel),
        )
    }

    @Test
    fun `one mail should be archived at a tap and leave the list`() {
        testSubject.event(Event.LoadMail)

        testSubject.event(Event.MailActionClicked(parcel, Action.ARCHIVE))

        assertThat(source.calls).containsExactly("archive [p1]")
        assertThat(testSubject.state.value.mails).containsExactly(code1, code2)
    }

    @Test
    fun `a whole group should wait for the reader to confirm`() = runTest {
        testSubject.event(Event.LoadMail)

        testSubject.event(Event.GroupActionClicked(ExpiredKind.SIGN_IN, Action.DELETE))

        assertThat(testSubject.state.value.confirmation).isEqualTo(Confirmation(ExpiredKind.SIGN_IN, Action.DELETE, 2))
        assertThat(source.calls).containsExactly()

        testSubject.effect.test {
            testSubject.event(Event.ConfirmClicked)

            assertThat(awaitItem()).isEqualTo(Effect.ShowNotice(Action.DELETE, 2))
        }
        assertThat(source.calls).containsExactly("delete [c1, c2]")
        assertThat(testSubject.state.value.mails).containsExactly(parcel)
    }

    @Test
    fun `dismissing should leave the mail alone`() {
        testSubject.event(Event.LoadMail)
        testSubject.event(Event.GroupActionClicked(ExpiredKind.SIGN_IN, Action.ARCHIVE))

        testSubject.event(Event.DismissClicked)

        assertThat(testSubject.state.value.confirmation).isNull()
        assertThat(source.calls).containsExactly()
    }

    @Test
    fun `tapping a mail should open it`() {
        testSubject.event(Event.MailClicked(code1))

        assertThat(source.calls).containsExactly("open c1")
    }

    private fun mail(key: String, kind: ExpiredKind) = ExpiredMail(
        key = key,
        kind = kind,
        date = 0L,
        senderName = "Sender",
        subject = "Subject $key",
    )

    private class FakeSource(private val mails: List<ExpiredMail>) : ExpiredMailSource {
        val calls = mutableListOf<String>()

        override suspend fun expiredMail(): List<ExpiredMail> = mails

        override suspend fun archive(keys: List<String>): Int {
            calls += "archive $keys"
            return keys.size
        }

        override suspend fun delete(keys: List<String>): Int {
            calls += "delete $keys"
            return keys.size
        }

        override fun openMail(key: String) {
            calls += "open $key"
        }
    }
}
