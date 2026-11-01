package net.thunderbird.feature.attachments.internal

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
import net.thunderbird.feature.attachments.OpenOutcome
import net.thunderbird.feature.attachments.SenderAttachmentRepository
import net.thunderbird.feature.attachments.SenderAttachments
import net.thunderbird.feature.attachments.StoredAttachment
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.Effect
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.Event
import net.thunderbird.feature.attachments.internal.SenderAttachmentsContract.Notice

private const val ADDRESS = "accountant@example.com"

class SenderAttachmentsViewModelTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val mainDispatcher = MainDispatcherHelper(UnconfinedTestDispatcher())

    private val invoice = attachment(key = "1", fileName = "invoice.pdf", isDownloaded = true)
    private val scan = attachment(key = "2", fileName = "scan.jpg", isDownloaded = false)

    private val repository = FakeRepository(SenderAttachments(listOf(invoice, scan), messagesNotDownloaded = 30))
    private val testSubject by lazy { SenderAttachmentsViewModel(repository) }

    @BeforeTest
    fun setUp() {
        mainDispatcher.setUp()
    }

    @AfterTest
    fun tearDown() {
        mainDispatcher.tearDown()
    }

    @Test
    fun `loading should list the sender's files and how many messages are not downloaded`() {
        testSubject.event(Event.LoadAttachments(ADDRESS))

        val state = testSubject.state.value
        assertThat(state.isLoading).isEqualTo(false)
        assertThat(state.attachments).containsExactly(invoice, scan)
        assertThat(state.messagesNotDownloaded).isEqualTo(30)
        assertThat(repository.calls).containsExactly("attachments $ADDRESS")
    }

    @Test
    fun `tapping a file should open it`() {
        testSubject.event(Event.LoadAttachments(ADDRESS))

        testSubject.event(Event.AttachmentClicked(invoice))

        assertThat(repository.calls).containsExactly("attachments $ADDRESS", "open 1")
        assertThat(testSubject.state.value.openingKey).isNull()
    }

    @Test
    fun `a file that had to be downloaded should be listed again once opened`() {
        testSubject.event(Event.LoadAttachments(ADDRESS))

        testSubject.event(Event.AttachmentClicked(scan))

        assertThat(repository.calls).containsExactly("attachments $ADDRESS", "open 2", "attachments $ADDRESS")
    }

    @Test
    fun `a file no app can show should say so`() = runTest {
        repository.openOutcome = OpenOutcome.NO_VIEWER
        testSubject.event(Event.LoadAttachments(ADDRESS))

        testSubject.effect.test {
            testSubject.event(Event.AttachmentClicked(invoice))

            assertThat(awaitItem()).isEqualTo(Effect.ShowNotice(Notice.NoViewer))
        }
    }

    @Test
    fun `the message button should open the file's message`() {
        testSubject.event(Event.ShowMessageClicked(scan))

        assertThat(repository.calls).containsExactly("show 2")
    }

    @Test
    fun `download should fetch one batch, say how many, and list the files again`() = runTest {
        testSubject.event(Event.LoadAttachments(ADDRESS))

        testSubject.effect.test {
            testSubject.event(Event.DownloadMessagesClicked)

            assertThat(awaitItem()).isEqualTo(Effect.ShowNotice(Notice.Downloaded(DOWNLOAD_BATCH_SIZE)))
        }
        assertThat(repository.calls).containsExactly(
            "attachments $ADDRESS",
            "download $ADDRESS $DOWNLOAD_BATCH_SIZE",
            "attachments $ADDRESS",
        )
        assertThat(testSubject.state.value.download).isNull()
    }

    private fun attachment(key: String, fileName: String, isDownloaded: Boolean) = StoredAttachment(
        key = key,
        fileName = fileName,
        mimeType = null,
        size = 1024,
        date = 0,
        subject = "Documents",
        isDownloaded = isDownloaded,
    )

    private class FakeRepository(private val senderAttachments: SenderAttachments) : SenderAttachmentRepository {
        val calls = mutableListOf<String>()
        var openOutcome = OpenOutcome.OPENED

        override suspend fun attachments(address: String): SenderAttachments {
            calls += "attachments $address"
            return senderAttachments
        }

        override suspend fun downloadMessages(
            address: String,
            limit: Int,
            onProgress: (done: Int, total: Int) -> Unit,
        ): Int {
            calls += "download $address $limit"
            onProgress(limit, limit)
            return limit
        }

        override suspend fun open(attachment: StoredAttachment): OpenOutcome {
            calls += "open ${attachment.key}"
            return openOutcome
        }

        override fun showMessage(attachment: StoredAttachment) {
            calls += "show ${attachment.key}"
        }
    }
}
