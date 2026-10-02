package net.thunderbird.backend.graph.command

import com.fsck.k9.backend.api.BackendFolder
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.backend.graph.api.GraphCollection
import net.thunderbird.backend.graph.api.GraphMessage
import net.thunderbird.backend.graph.api.HTTP_NOT_FOUND
import net.thunderbird.backend.graph.api.batchExecute
import net.thunderbird.backend.graph.api.graphBatchItem
import net.thunderbird.backend.graph.api.pathSegment
import net.thunderbird.backend.graph.api.receivedDate
import net.thunderbird.core.common.exception.MessagingException
import okhttp3.HttpUrl

/**
 * Key under which the format of the message ids stored for a folder is recorded.
 */
internal const val FOLDER_EXTRA_ID_FORMAT = "graphIdFormat"

/**
 * Value of [FOLDER_EXTRA_ID_FORMAT] for a folder whose stored messages all carry immutable ids.
 */
internal const val ID_FORMAT_IMMUTABLE = "immutable"

/**
 * Messages per page when a folder is listed for its ids. Each entry is an id, a message id and a date.
 */
private const val LIST_PAGE_SIZE = 500

/**
 * Gives the messages stored for a folder the immutable ids Graph now reports for them.
 *
 * Graph's default ids change whenever a message changes folder. The backend asks for immutable ids instead, which
 * stay with a message for as long as it is in the mailbox. Mail synchronized by an earlier version is stored under
 * its default id, though, and a sync that reported the same message under another id would store it a second time.
 * So before a folder is first synchronized with immutable ids, its stored messages are stored again under them.
 *
 * Graph does not translate an id it is asked about: a message requested by its default id is answered with that
 * same id, whatever the request prefers. Only a listing hands out the preferred kind. So the folder is listed twice,
 * newest first - once with each kind of id - and the two listings are paired up by what identifies a message in
 * both: its `Message-ID` and when it was received. Graph can convert ids directly, a thousand at a time, but only
 * for an app that may read the user's profile, which this one does not ask for.
 *
 * The listings are read only as far back as the stored messages go. Converting again is harmless: a message that
 * already has its immutable id is found under it and left alone, so a conversion that was interrupted, or a folder
 * that received a message under a default id afterwards, is simply converted once more.
 */
internal class GraphImmutableIdMigration(
    private val client: GraphApiClient,
) {
    /**
     * Converts the stored ids of a folder, unless that has been done.
     *
     * @param removeMissing whether a stored message Graph no longer has in the folder is removed. It has been
     *   deleted or moved since the last sync, which the sync would report under an id that no longer matches it.
     * @throws MessagingException when Graph could not be asked. Nothing may be synchronized into the folder until
     *   this has succeeded.
     */
    fun migrateIfNeeded(folderServerId: String, backendFolder: BackendFolder, removeMissing: Boolean) {
        if (backendFolder.getFolderExtraString(FOLDER_EXTRA_ID_FORMAT) == ID_FORMAT_IMMUTABLE) return

        val stored = backendFolder.getAllMessagesAndEffectiveDates()
        if (stored.isNotEmpty()) {
            convert(folderServerId, backendFolder, stored, removeMissing)
        }

        backendFolder.setFolderExtraString(FOLDER_EXTRA_ID_FORMAT, ID_FORMAT_IMMUTABLE)
    }

    private fun convert(
        folderServerId: String,
        backendFolder: BackendFolder,
        stored: Map<String, Long?>,
        removeMissing: Boolean,
    ) {
        val defaultIdListing = FolderListing(client, folderServerId, immutableIds = false)
        val immutableIdListing = FolderListing(client, folderServerId, immutableIds = true)
        val pairing = IdPairing()
        val unresolved = stored.keys.toMutableSet()
        // Without a date for every stored message there is no telling how far back they go.
        val oldestStoredDate = stored.values.takeIf { dates -> dates.none { it == null } }?.filterNotNull()?.minOrNull()
        var hasCheckedForMissing = false

        while (unresolved.isNotEmpty() && (defaultIdListing.hasMore || immutableIdListing.hasMore)) {
            pairing.addDefaultIds(defaultIdListing.nextPage())
            pairing.addImmutableIds(immutableIdListing.nextPage())

            resolve(backendFolder, pairing, unresolved)

            // Past the oldest stored message, what is still unresolved is most likely gone from the folder. Asking
            // settles it, and saves listing a large folder to its end for the sake of a deleted message.
            val isPastStoredMessages = oldestStoredDate == null || defaultIdListing.isOlderThan(oldestStoredDate)
            if (unresolved.isNotEmpty() && isPastStoredMessages && !hasCheckedForMissing) {
                hasCheckedForMissing = true
                remove(backendFolder, findMissing(unresolved), unresolved, removeMissing)
            }
        }

        // Listed to its end, the folder holds nothing that has not been seen.
        remove(backendFolder, unresolved.toSet(), unresolved, removeMissing)
    }

    private fun resolve(backendFolder: BackendFolder, pairing: IdPairing, unresolved: MutableSet<String>) {
        for (messageServerId in unresolved.toList()) {
            val immutableId = pairing.immutableIdOf(messageServerId) ?: continue

            if (immutableId != messageServerId) {
                backendFolder.changeMessageServerId(messageServerId, immutableId)
            }
            unresolved -= messageServerId
        }
    }

    private fun remove(
        backendFolder: BackendFolder,
        missing: Set<String>,
        unresolved: MutableSet<String>,
        removeMissing: Boolean,
    ) {
        if (missing.isEmpty()) return

        if (removeMissing) {
            backendFolder.destroyMessages(missing.toList())
        }
        unresolved -= missing
    }

    /**
     * Asks Graph which of the messages it no longer has.
     */
    private fun findMissing(messageServerIds: Set<String>): Set<String> {
        val asked = messageServerIds.toList()
        val responses = client.batchExecute(
            asked.mapIndexed { index, messageServerId ->
                graphBatchItem(index, "GET", "/me/messages/${pathSegment(messageServerId)}?\$select=id")
            },
        )

        val unanswered = asked.indices.count { index ->
            val response = responses[index]

            response == null || (!response.isSuccess && response.status != HTTP_NOT_FOUND)
        }
        if (unanswered > 0) {
            // Temporary, so the sync that asked is tried again and the conversion with it.
            throw MessagingException("Microsoft Graph could not look up $unanswered message(s)", false)
        }

        return asked.filterIndexedTo(HashSet()) { index, _ -> responses[index]?.status == HTTP_NOT_FOUND }
    }
}

/**
 * Reads a folder's messages newest first, a page at a time, with the ids in one of the two formats.
 */
private class FolderListing(
    private val client: GraphApiClient,
    folderServerId: String,
    private val immutableIds: Boolean,
) {
    private var nextUrl: HttpUrl? = client.url("me/mailFolders/${pathSegment(folderServerId)}/messages") {
        addQueryParameter("\$select", "id,internetMessageId,receivedDateTime")
        addQueryParameter("\$orderby", "receivedDateTime desc")
        addQueryParameter("\$top", LIST_PAGE_SIZE.toString())
    }
    private var oldestListedDate: Long? = null

    val hasMore: Boolean
        get() = nextUrl != null

    fun nextPage(): List<GraphMessage> {
        val url = nextUrl ?: return emptyList()
        val body = client.getString(url, immutableIds = immutableIds)
        val collection = client.json.decodeFromString<GraphCollection<GraphMessage>>(body)

        nextUrl = collection.nextLink?.let(client::absoluteUrl)
        collection.value.mapNotNull { it.receivedDate()?.time }.minOrNull()?.let { oldestListedDate = it }

        return collection.value
    }

    /**
     * Whether the listing has gone further back than [date], or has reached the end of the folder.
     */
    fun isOlderThan(date: Long): Boolean {
        return !hasMore || oldestListedDate?.let { it < date } == true
    }
}

/**
 * Pairs the default id of a message with its immutable id, from two listings of the same folder.
 *
 * A message is recognized in both by its `Message-ID` and the time it was received. Two messages can share both -
 * a mail filed twice - so messages with the same key are paired in the order they were listed, which for copies of
 * one mail is as good as any.
 */
private class IdPairing {
    private val keyByDefaultId = mutableMapOf<String, PairingKey>()
    private val defaultIdCountByKey = mutableMapOf<String, Int>()
    private val immutableIdsByKey = mutableMapOf<String, MutableList<String>>()
    private val immutableIds = mutableSetOf<String>()

    // Mail arriving while a folder is being listed pushes messages onto the next page, where they are listed a
    // second time. A message is only counted the first time it is seen.

    fun addDefaultIds(messages: List<GraphMessage>) {
        for (message in messages) {
            if (message.id in keyByDefaultId) continue

            val key = message.pairingKey()
            val occurrence = defaultIdCountByKey.getOrDefault(key, 0)

            defaultIdCountByKey[key] = occurrence + 1
            keyByDefaultId[message.id] = PairingKey(key, occurrence)
        }
    }

    fun addImmutableIds(messages: List<GraphMessage>) {
        for (message in messages) {
            if (immutableIds.add(message.id)) {
                immutableIdsByKey.getOrPut(message.pairingKey()) { mutableListOf() } += message.id
            }
        }
    }

    /**
     * @return the immutable id of the message stored under [messageServerId] - which is [messageServerId] itself
     *   when that already is one - or `null` when the listings have not shown the message yet.
     */
    fun immutableIdOf(messageServerId: String): String? {
        if (messageServerId in immutableIds) return messageServerId

        return keyByDefaultId[messageServerId]?.let { pairingKey ->
            immutableIdsByKey[pairingKey.key]?.getOrNull(pairingKey.occurrence)
        }
    }

    private fun GraphMessage.pairingKey(): String = "${internetMessageId.orEmpty()}|${receivedDateTime.orEmpty()}"

    private data class PairingKey(val key: String, val occurrence: Int)
}
