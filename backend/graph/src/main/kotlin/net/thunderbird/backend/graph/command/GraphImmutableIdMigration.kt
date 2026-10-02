package net.thunderbird.backend.graph.command

import com.fsck.k9.backend.api.BackendFolder
import kotlinx.serialization.json.jsonPrimitive
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphBatchResponseItem
import net.thunderbird.backend.graph.api.HTTP_NOT_FOUND
import net.thunderbird.backend.graph.api.batchExecute
import net.thunderbird.backend.graph.api.graphBatchItem
import net.thunderbird.backend.graph.api.pathSegment
import net.thunderbird.core.common.exception.MessagingException

/**
 * Key under which the format of the message ids stored for a folder is recorded.
 */
internal const val FOLDER_EXTRA_ID_FORMAT = "graphIdFormat"

/**
 * Value of [FOLDER_EXTRA_ID_FORMAT] for a folder whose stored messages all carry immutable ids.
 */
internal const val ID_FORMAT_IMMUTABLE = "immutable"

/**
 * Key under which a conversion that was interrupted records how far it got: the date of the oldest message it had
 * converted.
 */
internal const val FOLDER_EXTRA_ID_MIGRATION_CURSOR = "graphIdMigrationCursor"

/**
 * How many messages are converted before the progress is recorded.
 */
private const val MESSAGES_PER_STEP = 100

/**
 * Gives the messages stored for a folder the immutable ids Graph now reports for them.
 *
 * Graph's default ids change whenever a message changes folder. The backend asks for immutable ids instead, which
 * stay with a message for as long as it is in the mailbox. Mail synchronized by an earlier version is stored under
 * its default id, though, and a sync that reported the same message under another id would store it a second time.
 * So before a folder is first synchronized with immutable ids, each stored message is looked up by the id it has,
 * and stored again under the id Graph answers with.
 *
 * One request per message, twenty to a batch. Graph can convert a thousand ids in one call, but only for an app
 * that may read the user's profile, which this one does not ask for. The work is done once per folder, newest mail
 * first, and how far it got is recorded as it goes, so a conversion that is interrupted - a large folder can be
 * throttled part way - carries on from there instead of starting over.
 */
internal class GraphImmutableIdMigration(
    private val client: GraphApiClient,
) {
    /**
     * Converts the stored ids of [backendFolder], unless that has been done.
     *
     * @param removeMissing whether a stored message Graph no longer finds is removed. It has been deleted or moved
     *   since the last sync, which the sync would have reported under an id that no longer matches it.
     * @throws MessagingException when Graph could not be asked about some of the messages. Nothing may be
     *   synchronized into the folder until this has succeeded.
     */
    fun migrateIfNeeded(backendFolder: BackendFolder, removeMissing: Boolean) {
        if (backendFolder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT) == ID_FORMAT_IMMUTABLE) return

        val cursor = backendFolder.getFolderExtraNumber(FOLDER_EXTRA_ID_MIGRATION_CURSOR)
        val pending = backendFolder.getAllMessagesAndEffectiveDates()
            // A message without a date sorts first, and is looked up again by a conversion that resumes.
            .map { (messageServerId, date) -> StoredMessage(messageServerId, date ?: Long.MAX_VALUE) }
            .filter { cursor == null || it.date <= cursor }
            .sortedByDescending { it.date }

        for (step in pending.chunked(MESSAGES_PER_STEP)) {
            convert(backendFolder, step.map { it.messageServerId }, removeMissing)
            backendFolder.setFolderExtraNumber(FOLDER_EXTRA_ID_MIGRATION_CURSOR, step.last().date)
        }

        backendFolder.setFolderExtraString(FOLDER_EXTRA_ID_FORMAT, ID_FORMAT_IMMUTABLE)
    }

    private fun convert(backendFolder: BackendFolder, messageServerIds: List<String>, removeMissing: Boolean) {
        val responses = client.batchExecute(
            messageServerIds.mapIndexed { index, messageServerId ->
                graphBatchItem(index, "GET", "/me/messages/${pathSegment(messageServerId)}?\$select=id")
            },
        )

        val missing = mutableListOf<String>()
        var unanswered = 0

        messageServerIds.forEachIndexed { index, messageServerId ->
            val response = responses[index]
            val immutableId = response?.immutableId()

            when {
                immutableId != null -> {
                    if (immutableId != messageServerId) {
                        backendFolder.changeMessageServerId(messageServerId, immutableId)
                    }
                }

                response?.status == HTTP_NOT_FOUND -> missing += messageServerId

                else -> unanswered++
            }
        }

        if (removeMissing && missing.isNotEmpty()) {
            backendFolder.destroyMessages(missing)
        }

        if (unanswered > 0) {
            // Temporary, so the sync that asked is tried again and the conversion carries on.
            throw MessagingException("Microsoft Graph could not look up $unanswered message(s)", false)
        }
    }

    private fun GraphBatchResponseItem.immutableId(): String? {
        if (!isSuccess) return null

        return body?.get("id")?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
    }

    private data class StoredMessage(val messageServerId: String, val date: Long)
}
