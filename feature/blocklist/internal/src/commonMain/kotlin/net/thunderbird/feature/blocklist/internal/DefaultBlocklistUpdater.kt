package net.thunderbird.feature.blocklist.internal

import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.thunderbird.components.core.logging.Logger

private const val TAG = "BlocklistUpdater"

/**
 * How old a downloaded list may get before it is fetched again. HaGeZi rebuilds them every few hours; a day keeps
 * the lists current enough for showing information without fetching megabytes more often than that.
 */
private val MAX_LIST_AGE = 20.hours

/**
 * Keeps the downloaded lists on the device.
 */
interface BlocklistStore {
    fun read(name: String): ByteArray?

    /**
     * Replaces the list called [name] in one step, so a reader never sees half of it.
     */
    fun write(name: String, bytes: ByteArray)

    fun delete(name: String)

    /**
     * @return when the list called [name] was last written, or `null` when there is none.
     */
    @OptIn(ExperimentalTime::class)
    fun writtenAt(name: String): Instant?

    fun names(): Set<String>
}

/**
 * Downloads one list and reads it into memory.
 */
fun interface BlocklistFetcher {
    /**
     * @return the list at [url], or `null` when what came back was not a list.
     * @throws Exception when it could not be fetched at all.
     */
    suspend fun fetch(url: String): HashedDomainListResult
}

/**
 * A downloaded list, kept opaque outside this module.
 */
class HashedDomainListResult internal constructor(internal val list: HashedDomainList?)

/**
 * Fetches the lists the settings ask for when they are missing or old, drops the ones no longer asked for, and
 * puts what is on the device into [blocklist].
 */
@OptIn(ExperimentalTime::class)
class DefaultBlocklistUpdater internal constructor(
    private val blocklist: DefaultDomainBlocklist,
    private val settingsProvider: BlocklistSettingsProvider,
    private val store: BlocklistStore,
    private val fetcher: BlocklistFetcher,
    private val clock: Clock,
    private val logger: Logger,
) {
    /**
     * One update at a time: the daily one and one asked for after a change of setting can start together, and both
     * write the same files. The second then finds the lists fresh and fetches nothing.
     */
    private val updateMutex = Mutex()

    /**
     * Reads the chosen lists from the device into memory, without touching the network. Called at startup.
     */
    fun loadFromDevice() {
        val loaded = wantedLists().mapNotNull { list ->
            store.read(list.fileStem)?.let(HashedDomainList::decode)?.let { list to it }
        }.toMap()
        blocklist.replace(loaded)
    }

    /**
     * @return whether a chosen list is missing from the device, so the first download should not wait for Wi-Fi.
     */
    fun isListMissing(): Boolean = wantedLists().any { store.writtenAt(it.fileStem) == null }

    /**
     * @return `false` when a list that was needed could not be fetched, so the caller can try again later.
     */
    suspend fun update(): Boolean = updateMutex.withLock { updateLists() }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun updateLists(): Boolean {
        val wanted = wantedLists()
        store.names().filter { name -> wanted.none { it.fileStem == name } }.forEach(store::delete)

        var succeeded = true
        for (list in wanted) {
            val writtenAt = store.writtenAt(list.fileStem)
            if (writtenAt != null && clock.now() - writtenAt < MAX_LIST_AGE) continue

            try {
                val fetched = fetcher.fetch(list.url).list
                if (fetched == null) {
                    logger.warn(TAG) { "Downloaded ${list.displayName} list was not a list" }
                    succeeded = false
                } else {
                    store.write(list.fileStem, fetched.encode())
                    logger.info(TAG) { "Downloaded ${list.displayName} list: ${fetched.size} domains" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(TAG, e) { "Could not download ${list.displayName} list" }
                succeeded = false
            }
        }

        loadFromDevice()
        return succeeded
    }

    private fun wantedLists(): List<HagezeiList> {
        val settings = settingsProvider.settings()
        return listOfNotNull(
            HagezeiList.forTier(settings.tier),
            HagezeiList.THREATS.takeIf { settings.isThreatListEnabled },
        )
    }
}
