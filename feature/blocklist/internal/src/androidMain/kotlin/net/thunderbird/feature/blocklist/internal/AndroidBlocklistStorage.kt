package net.thunderbird.feature.blocklist.internal

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val LIST_FILE_SUFFIX = ".tbdl"

/**
 * The most a list download may be, uncompressed. HaGeZi's medium threat list is about 11 MB.
 */
private const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024

private const val CONNECT_TIMEOUT_SECONDS = 30L

/**
 * How long the download may stall between bytes. A CDN serving a cold copy can pause for longer than OkHttp's default
 * ten seconds; the whole download is still bounded by [CALL_TIMEOUT_MINUTES].
 */
private const val READ_TIMEOUT_SECONDS = 60L
private const val CALL_TIMEOUT_MINUTES = 5L

/**
 * Keeps each list as a file of its own in the app's private storage, written beside its final name and renamed
 * into place.
 */
class FileBlocklistStore(private val directory: File) : BlocklistStore {

    override fun read(name: String): ByteArray? = file(name).takeIf { it.isFile }?.readBytes()

    override fun write(name: String, bytes: ByteArray) {
        directory.mkdirs()
        val temporary = File(directory, "$name$LIST_FILE_SUFFIX.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file(name))) {
            temporary.delete()
            throw IOException("Could not save blocklist $name")
        }
    }

    override fun delete(name: String) {
        file(name).delete()
    }

    @OptIn(ExperimentalTime::class)
    override fun writtenAt(name: String): Instant? =
        file(name).takeIf { it.isFile }?.let { Instant.fromEpochMilliseconds(it.lastModified()) }

    override fun names(): Set<String> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(LIST_FILE_SUFFIX) }
        .map { it.name.removeSuffix(LIST_FILE_SUFFIX) }
        .toSet()

    private fun file(name: String) = File(directory, "$name$LIST_FILE_SUFFIX")
}

/**
 * Downloads a list over HTTPS and reads it line by line into a [HashedDomainList], never holding the text whole.
 *
 * Nothing about the user's mail is in the request; it is the same file for everyone. No cookies are kept and
 * redirects stay on HTTPS.
 */
class OkHttpBlocklistFetcher(httpClient: OkHttpClient) : BlocklistFetcher {
    private val client = httpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .followSslRedirects(false)
        .build()

    override suspend fun fetch(url: String): HashedDomainListResult = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Blocklist download failed: HTTP ${response.code}")
            if (response.body.contentLength() > MAX_DOWNLOAD_BYTES) throw IOException("Blocklist too large")

            val list = response.body.byteStream().boundedTo(MAX_DOWNLOAD_BYTES).bufferedReader().useLines { lines ->
                HashedDomainList.fromLines(lines)
            }
            HashedDomainListResult(list)
        }
    }

    private companion object {
        const val USER_AGENT = "Thunderbird-Android"
    }
}

private fun java.io.InputStream.boundedTo(limit: Long): java.io.InputStream {
    val source = this
    return object : java.io.FilterInputStream(source) {
        private var read = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count(it.toLong()) }

        private fun count(bytes: Long) {
            read += bytes
            if (read > limit) throw IOException("Blocklist too large")
        }
    }
}
