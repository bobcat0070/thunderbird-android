package net.thunderbird.feature.trips.internal

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

private const val FILE_NAME = "trips-reservations.json"

/**
 * The bookings read from each mail, kept on the device: a mail's body does not change, and reading a year of
 * confirmations again on every start took seconds.
 *
 * What a mail reads as depends on its hints too, which change as the reader edits the travel sender list, so both are
 * the key. Everything kept is dropped when the app is updated, as a new version may read mail differently, and a
 * mail's bookings are dropped once it is no longer among the mail read: deleted, or older than [TRIPS_SCAN_DAYS].
 *
 * @param file where the bookings are kept, in storage only the app can read and that is not backed up.
 * @param appUpdatedAt when the app was installed or last updated.
 * @param ioDispatcher where the file is read and written.
 */
internal class ReservationCache internal constructor(
    private val file: File,
    private val appUpdatedAt: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        file = File(context.noBackupFilesDir, FILE_NAME),
        appUpdatedAt = { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime },
    )

    private val byMail = ConcurrentHashMap<SavedKey, List<Reservation>>()

    private val fileLock = Mutex()
    private var isLoaded = false

    @Volatile
    private var isChanged = false

    fun get(mailKey: String, hints: MailHints): List<Reservation>? = byMail[SavedKey(mailKey, hints)]

    fun put(mailKey: String, hints: MailHints, reservations: List<Reservation>) {
        byMail[SavedKey(mailKey, hints)] = reservations
        isChanged = true
    }

    /**
     * Reads what an earlier start kept, the first time it is called.
     */
    suspend fun load() = fileLock.withLock {
        if (isLoaded) return@withLock
        isLoaded = true
        val saved = withContext(ioDispatcher) { readFile() }
        saved?.forEach { entry -> byMail.putIfAbsent(entry.key, entry.reservations) }
    }

    /**
     * Drops the bookings of mail not in [mailKeys] and keeps the rest on the device, when anything changed.
     */
    suspend fun save(mailKeys: Set<String>) = fileLock.withLock {
        val isPruned = byMail.keys.removeAll { it.mailKey !in mailKeys }
        if (!isChanged && !isPruned) return@withLock
        isChanged = false
        val saved = SavedReservations(
            appUpdatedAt = appUpdatedAt(),
            entries = byMail.map { (key, reservations) -> SavedEntry(key, reservations) },
        )
        withContext(ioDispatcher) { writeFile(saved) }
    }

    /**
     * What an earlier start kept, or `null` when there is nothing, it cannot be read, or the app was updated since.
     */
    private fun readFile(): List<SavedEntry>? {
        if (!file.exists()) return null
        val entries = try {
            JSON.decodeFromString(SavedReservations.serializer(), file.readText())
                .takeIf { it.appUpdatedAt == appUpdatedAt() }
                ?.entries
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: DateTimeException) {
            null
        }
        if (entries == null) file.delete()
        return entries
    }

    /**
     * Writes a new file and then puts it in place of the old, so a write cut short leaves the old one whole.
     */
    private fun writeFile(saved: SavedReservations) {
        val newFile = File(file.path + ".new")
        try {
            newFile.writeText(JSON.encodeToString(SavedReservations.serializer(), saved))
            if (!newFile.renameTo(file)) {
                file.delete()
                newFile.renameTo(file)
            }
        } catch (_: IOException) {
            newFile.delete()
        }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class SavedKey(val mailKey: String, val hints: MailHints)

@Serializable
private class SavedEntry(val key: SavedKey, val reservations: List<Reservation>)

@Serializable
private class SavedReservations(val appUpdatedAt: Long, val entries: List<SavedEntry>)

internal object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("net.thunderbird.feature.trips.LocalDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString())
}

internal object ZoneOffsetSerializer : KSerializer<ZoneOffset> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("net.thunderbird.feature.trips.ZoneOffset", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ZoneOffset) = encoder.encodeString(value.id)

    override fun deserialize(decoder: Decoder): ZoneOffset = ZoneOffset.of(decoder.decodeString())
}
