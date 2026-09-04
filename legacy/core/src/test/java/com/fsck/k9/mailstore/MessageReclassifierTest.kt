package com.fsck.k9.mailstore

import app.k9mail.legacy.mailstore.ListenableMessageStore
import app.k9mail.legacy.mailstore.MessageStore
import app.k9mail.legacy.mailstore.MessageStoreManager
import app.k9mail.legacy.mailstore.StoredClassificationEvidence
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import net.thunderbird.core.android.account.LegacyAccountDto
import net.thunderbird.core.android.account.LegacyAccountDtoManager
import net.thunderbird.feature.mail.message.classification.api.CLASSIFIER_VERSION
import net.thunderbird.feature.mail.message.classification.api.ClassificationSignal
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.MessageClassification
import net.thunderbird.feature.mail.message.classification.api.MessageClassifier
import net.thunderbird.feature.mail.message.classification.api.MessageEvidence
import net.thunderbird.core.logging.testing.TestLogger
import net.thunderbird.legacy.logging.Log
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

private const val BATCH_SIZE = 200

class MessageReclassifierTest {
    private val messageStore = FakeMessageStore()
    private val knownContacts = mock<KnownContacts>()
    private val knownCorrespondents = mock<KnownCorrespondents>()
    private val tracker = mock<ReclassificationTracker> {
        on { isPassNeeded(any()) } doReturn true
    }
    private var classified = mutableListOf<MessageEvidence>()
    private val classifier = MessageClassifier { evidence ->
        classified += evidence
        MessageClassification(MessageClass.NEWSLETTER, ClassificationSignal.BULK_HEADER)
    }

    @Before
    fun setUp() {
        Log.logger = TestLogger()
    }

    @Test
    fun `nothing should be read when the rules have not changed`() {
        whenever(tracker.isPassNeeded(any())).doReturn(false)
        messageStore.pending = listOf(evidenceFor(messageId = 1))

        assertThat(reclassifier().reclassifyIfRulesChanged()).isEqualTo(0)

        assertThat(messageStore.wasRead).isFalse()
    }

    @Test
    fun `stored messages should be re-classified and the pass recorded`() {
        messageStore.pending = listOf(evidenceFor(messageId = 1), evidenceFor(messageId = 2))

        assertThat(reclassifier().reclassifyIfRulesChanged()).isEqualTo(2)

        assertThat(messageStore.written).isEqualTo(
            mapOf(
                1L to MessageClassification(MessageClass.NEWSLETTER, ClassificationSignal.BULK_HEADER),
                2L to MessageClassification(MessageClass.NEWSLETTER, ClassificationSignal.BULK_HEADER),
            ),
        )
        assertThat(messageStore.writtenVersion).isEqualTo(CLASSIFIER_VERSION)
    }

    @Test
    fun `a mailbox larger than a batch should be worked through in batches`() {
        messageStore.pending = (1L..BATCH_SIZE + 30L).map { evidenceFor(messageId = it) }

        assertThat(reclassifier().reclassifyIfRulesChanged()).isEqualTo(BATCH_SIZE + 30)

        assertThat(messageStore.reads).isEqualTo(3)
    }

    @Test
    fun `a batch that writes nothing should end the pass`() {
        // Otherwise the same batch comes back unchanged forever, on a thread nothing is watching.
        messageStore.pending = listOf(evidenceFor(messageId = 1))
        messageStore.refuseWrites = true

        assertThat(reclassifier().reclassifyIfRulesChanged()).isEqualTo(0)

        assertThat(messageStore.reads).isEqualTo(1)
    }

    @Test
    fun `the behavioural signals should be attached to stored evidence`() {
        // The store cannot know them, so a pass that did not add them would judge stored mail on less
        // evidence than mail arriving now - and quietly undo the classification of every correspondent.
        whenever(knownContacts.isKnown("sam@example.com")).doReturn(true)
        whenever(knownCorrespondents.isKnown("sam@example.com")).doReturn(true)
        messageStore.pending = listOf(evidenceFor(messageId = 1, fromAddress = "sam@example.com"))

        reclassifier().reclassifyIfRulesChanged()

        assertThat(classified.single().isKnownContact).isTrue()
        assertThat(classified.single().hasCorresponded).isTrue()
    }

    @Test
    fun `an account that cannot be read should still record the pass`() {
        // The failure will not fix itself by the next launch, and retrying a full mailbox scan on every
        // start would be a permanent cost for a permanent failure.
        messageStore.failReads = true

        reclassifier().reclassifyIfRulesChanged()

        verify(tracker).recordPass(CLASSIFIER_VERSION)
    }

    private fun reclassifier(): MessageReclassifier {
        val account = mock<LegacyAccountDto>()
        val accountManager = mock<LegacyAccountDtoManager> {
            on { getAccounts() } doReturn listOf(account)
        }
        val messageStoreManager = mock<MessageStoreManager> {
            on { getMessageStore(account) } doAnswer { ListenableMessageStore(messageStore) }
        }

        return MessageReclassifier(
            accountManager = accountManager,
            messageStoreManager = messageStoreManager,
            messageClassifier = classifier,
            knownContacts = knownContacts,
            knownCorrespondents = knownCorrespondents,
            tracker = tracker,
        )
    }

    private fun evidenceFor(messageId: Long, fromAddress: String = "news@shop.example") =
        StoredClassificationEvidence(
            messageId = messageId,
            evidence = MessageEvidence(headers = emptyMap(), fromAddress = fromAddress),
        )
}

/**
 * Only the two calls the pass makes are real; everything else on [MessageStore] would be noise here.
 */
private class FakeMessageStore(
    private val delegate: MessageStore = mock(),
) : MessageStore by delegate {
    var pending: List<StoredClassificationEvidence> = emptyList()
    var refuseWrites = false
    var failReads = false
    var reads = 0
    var wasRead = false
    val written = mutableMapOf<Long, MessageClassification>()
    var writtenVersion: Int? = null

    override fun getMessagesToReclassify(classifierVersion: Int, limit: Int): List<StoredClassificationEvidence> {
        wasRead = true
        if (failReads) throw IllegalStateException("database unavailable")

        reads++

        return pending.take(limit)
    }

    override fun setClassifications(
        classifications: Map<Long, MessageClassification>,
        classifierVersion: Int,
    ): Int {
        if (refuseWrites) return 0

        written += classifications
        writtenVersion = classifierVersion
        pending = pending.filterNot { it.messageId in classifications }

        return classifications.size
    }
}
