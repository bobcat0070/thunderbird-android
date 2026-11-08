package net.thunderbird.app.common.feature.packages

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.thunderbird.feature.packages.ShippingMail

private const val FILE_NAME = "packages-shipping-mails.json"
private const val APP_UPDATED_AT = "appUpdatedAt"
private const val MAILS = "mails"

private val MAILS_SERIALIZER = MapSerializer(String.serializer(), ShippingMail.serializer().nullable)

/**
 * What each mail read for the Packages screen held, kept on the device: a mail's body does not change, and turning a
 * mail's HTML into text takes a few hundred milliseconds, which on every start added up to seconds.
 *
 * A mail that named no tracking number is kept as `null`, so it is not read again either. Everything kept is dropped
 * when the app is updated, as a new version may read mail differently, and a mail is dropped once it is no longer among
 * the mail read: deleted, or older than the screen looks back.
 *
 * @param file where the mails are kept, in storage only the app can read and that is not backed up.
 * @param appUpdatedAt when the app was installed or last updated.
 * @param ioDispatcher where the file is read and written.
 */
internal class ShippingMailCache internal constructor(
    private val file: File,
    private val appUpdatedAt: () -> Long,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this(
        file = File(context.noBackupFilesDir, FILE_NAME),
        appUpdatedAt = { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime },
    )

    /**
     * Each mail read so far, or `null` for one that turned out to name no tracking number.
     */
    private val readMails = ConcurrentHashMap<String, CachedMail>()

    class CachedMail(val mail: ShippingMail?)

    private val fileLock = Mutex()
    private var isLoaded = false

    @Volatile
    private var isChanged = false

    operator fun get(key: String): CachedMail? = readMails[key]

    operator fun set(key: String, cachedMail: CachedMail) {
        readMails[key] = cachedMail
        isChanged = true
    }

    /**
     * Reads what an earlier start kept, the first time it is called.
     */
    suspend fun load() = fileLock.withLock {
        if (isLoaded) return@withLock
        isLoaded = true
        val saved = withContext(ioDispatcher) { readFile() }
        saved?.forEach { (key, mail) -> readMails.putIfAbsent(key, CachedMail(mail)) }
    }

    /**
     * Drops the mails whose keys are not in [keys] and keeps the rest on the device, when anything changed.
     */
    suspend fun save(keys: Set<String>) = fileLock.withLock {
        val isPruned = readMails.keys.retainAll(keys)
        if (!isChanged && !isPruned) return@withLock
        isChanged = false
        val saved = buildJsonObject {
            put(APP_UPDATED_AT, JsonPrimitive(appUpdatedAt()))
            put(MAILS, JSON.encodeToJsonElement(MAILS_SERIALIZER, readMails.mapValues { it.value.mail }))
        }
        withContext(ioDispatcher) { writeFile(saved) }
    }

    /**
     * What an earlier start kept, or `null` when there is nothing, it cannot be read, or the app was updated since.
     */
    private fun readFile(): Map<String, ShippingMail?>? {
        if (!file.exists()) return null
        val mails = try {
            val saved = JSON.parseToJsonElement(file.readText()).jsonObject
            if (saved.getValue(APP_UPDATED_AT).jsonPrimitive.long == appUpdatedAt()) {
                JSON.decodeFromJsonElement(MAILS_SERIALIZER, saved.getValue(MAILS))
            } else {
                null
            }
        } catch (_: IOException) {
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: NoSuchElementException) {
            null
        }
        if (mails == null) file.delete()
        return mails
    }

    /**
     * Writes a new file and then puts it in place of the old, so a write cut short leaves the old one whole.
     */
    private fun writeFile(saved: JsonObject) {
        val newFile = File(file.path + ".new")
        try {
            newFile.writeText(saved.toString())
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
