package net.thunderbird.core.featureflag.data

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.eygraber.uri.Uri
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import net.thunderbird.core.configstore.ConfigId
import net.thunderbird.core.configstore.backend.ConfigBackend
import net.thunderbird.core.configstore.backend.ConfigBackendProvider
import net.thunderbird.core.configstore.testing.TestConfigBackend
import net.thunderbird.core.featureflag.data.configstore.DefaultFeatureFlagConfigStore
import net.thunderbird.core.featureflag.model.AppVariantOverridesRawType
import net.thunderbird.core.featureflag.model.BaseAppVariantOverrides
import net.thunderbird.core.featureflag.model.FlagRegistryOverride
import net.thunderbird.core.featureflag.serialization.FlagRegistryOverrideSerializer
import net.thunderbird.core.file.FileSystemManager
import net.thunderbird.core.file.WriteMode
import net.thunderbird.core.logging.testing.TestLogger

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFeatureFlagCatalogDataSourceTest {
    private val fileSystemManager = InMemoryFileSystemManager()

    @Test
    fun `load should return the catalog the server sends and cache it`() = runTest {
        // Arrange
        val testSubject = createTestSubject {
            respond(content = CATALOG_JSON, status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ETag, "\"1\""))
        }

        // Act
        val result = testSubject.load()

        // Assert
        assertThat(result).isNotNull()
        assertThat(result?.version).isEqualTo("2026-07-30.1")
        assertThat(fileSystemManager.read(CACHE_FILE_URI)).isEqualTo(CATALOG_JSON)
    }

    @Test
    fun `load should return null when the device cannot resolve the host`() = runTest {
        // Arrange
        // What an offline device reports is an UnresolvedAddressException, which is an IllegalArgumentException
        // rather than an IOException. App startup waits for load() to return, so it must not escape.
        val testSubject = createTestSubject { throw IllegalArgumentException("unresolved address") }

        // Act
        val result = testSubject.load()

        // Assert
        assertThat(result).isNull()
        assertThat(currentTime).isEqualTo(0)
    }

    @Test
    fun `load should return null when the request fails with an IOException`() = runTest {
        // Arrange
        val testSubject = createTestSubject { throw IOException("connection reset") }

        // Act
        val result = testSubject.load()

        // Assert
        assertThat(result).isNull()
        assertThat(currentTime).isEqualTo(0)
    }

    @Test
    fun `load should give up when the server does not answer in time`() = runTest {
        // Arrange
        val testSubject = createTestSubject { awaitCancellation() }

        // Act
        val result = testSubject.load()

        // Assert
        assertThat(result).isNull()
        assertThat(currentTime).isEqualTo(FETCH_TIMEOUT.inWholeMilliseconds)
    }

    private fun TestScope.createTestSubject(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): RemoteFeatureFlagCatalogDataSource {
        // The engine runs on the test scheduler too. On a real thread, virtual time would run past the timeout
        // while the request is still in flight, and every case would look like the timeout.
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val engine = MockEngine.create {
            dispatcher = testDispatcher
            addHandler(handler)
        }

        return RemoteFeatureFlagCatalogDataSource(
            url = "https://flags.example/catalog.json",
            cacheFileUri = Uri.parse(CACHE_FILE_URI),
            logger = TestLogger(),
            configStore = DefaultFeatureFlagConfigStore(
                id = ConfigId(backend = "test", feature = "featureflag"),
                provider = SingleBackendConfigBackendProvider(TestConfigBackend()),
            ),
            fileSystemManager = fileSystemManager,
            json = JSON,
            httpClient = HttpClient(engine),
            ioDispatcher = testDispatcher,
            fetchTimeout = FETCH_TIMEOUT,
        )
    }

    private companion object {
        const val CACHE_FILE_URI = "file:///cache/catalog.json"
        val FETCH_TIMEOUT = 5.seconds

        // language=json
        val CATALOG_JSON = """
            {
              "version": "2026-07-30.1",
              "flags": [
                { "key": "first_flag", "default": true }
              ],
              "overrides": {
                "thunderbird": { "debug": {}, "release": {} },
                "k9": { "debug": {}, "release": {} }
              }
            }
        """.trimIndent()

        val JSON = Json {
            serializersModule = SerializersModule {
                contextual(
                    kClass = FlagRegistryOverride::class,
                    serializer = FlagRegistryOverrideSerializer(
                        k9Factory = { wrapper -> FakeOverrides(wrapper) },
                        thunderbirdFactory = { wrapper -> FakeOverrides(wrapper) },
                    ),
                )
            }
        }
    }
}

private class FakeOverrides(wrapper: AppVariantOverridesRawType) : BaseAppVariantOverrides(wrapper)

private class SingleBackendConfigBackendProvider(
    private val backend: ConfigBackend,
) : ConfigBackendProvider {
    override fun provide(id: ConfigId): ConfigBackend = backend
}

private class InMemoryFileSystemManager : FileSystemManager {
    private val files = mutableMapOf<String, Buffer>()

    fun read(uri: String): String? = files[uri]?.copy()?.readString()

    override fun openSink(uri: Uri, mode: WriteMode): RawSink {
        val file = Buffer()
        files[uri.toString()] = file

        return object : RawSink {
            override fun write(source: Buffer, byteCount: Long) = file.write(source, byteCount)
            override fun flush() = Unit
            override fun close() = Unit
        }
    }

    override fun openSource(uri: Uri): RawSource? = files[uri.toString()]?.copy()

    override fun delete(uri: Uri) {
        files.remove(uri.toString())
    }

    override fun createDirectories(uri: Uri) = Unit
}
