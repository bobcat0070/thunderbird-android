package net.thunderbird.backend.graph

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import com.fsck.k9.backend.api.BackendPusherCallback
import com.fsck.k9.mail.power.PowerManager
import com.fsck.k9.mail.power.WakeLock
import kotlin.test.AfterTest
import kotlin.test.Test
import net.thunderbird.backend.graph.api.GraphApiClient
import net.thunderbird.core.logging.testing.TestLogger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class GraphBackendPusherTest {
    private val server = MockWebServer()
    private val scheduler = ManualScheduler()
    private val callback = RecordingPusherCallback()
    private val powerManager = CountingPowerManager()

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `first poll should establish a baseline without reporting changes`() {
        val testSubject = createTestSubject()
        testSubject.updateFolders(listOf("inbox-id"))
        enqueueFolderState("inbox-id", total = 10, unread = 2)

        testSubject.start()
        scheduler.runPending()

        // Reporting every folder as changed on the first poll would sync everything each time push starts.
        assertThat(callback.pushEvents).isEmpty()
    }

    @Test
    fun `a folder whose counts changed should be reported`() {
        val testSubject = createTestSubject()
        testSubject.updateFolders(listOf("inbox-id"))
        enqueueFolderState("inbox-id", total = 10, unread = 2)
        enqueueFolderState("inbox-id", total = 11, unread = 3)

        testSubject.start()
        scheduler.runPending()
        scheduler.runPending()

        assertThat(callback.pushEvents).containsExactly("inbox-id")
    }

    @Test
    fun `a folder that did not change should not be reported`() {
        val testSubject = createTestSubject()
        testSubject.updateFolders(listOf("inbox-id"))
        enqueueFolderState("inbox-id", total = 10, unread = 2)
        enqueueFolderState("inbox-id", total = 10, unread = 2)

        testSubject.start()
        scheduler.runPending()
        scheduler.runPending()

        assertThat(callback.pushEvents).isEmpty()
    }

    @Test
    fun `a message being read elsewhere should be noticed`() {
        val testSubject = createTestSubject()
        testSubject.updateFolders(listOf("inbox-id"))
        enqueueFolderState("inbox-id", total = 10, unread = 2)
        // Same number of messages, one of them now read.
        enqueueFolderState("inbox-id", total = 10, unread = 1)

        testSubject.start()
        scheduler.runPending()
        scheduler.runPending()

        assertThat(callback.pushEvents).containsExactly("inbox-id")
    }

    @Test
    fun `polling should keep the device awake for the request`() {
        val testSubject = createTestSubject()
        testSubject.updateFolders(listOf("inbox-id"))
        enqueueFolderState("inbox-id", total = 1, unread = 0)

        testSubject.start()
        scheduler.runPending()

        // A poll that lets the device sleep mid-request defeats the point of pushing.
        assertThat(powerManager.acquired).isEqualTo(1)
        assertThat(powerManager.released).isEqualTo(1)
    }

    @Test
    fun `a failed poll should be reported and should not stop pushing`() {
        val testSubject = createTestSubject()
        testSubject.updateFolders(listOf("inbox-id"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"InternalServerError"}}"""))

        testSubject.start()
        scheduler.runPending()

        assertThat(callback.errors).isEqualTo(1)
        // The wake lock must be released even when the request failed.
        assertThat(powerManager.released).isEqualTo(1)
        assertThat(scheduler.hasPending).isEqualTo(true)
    }

    @Test
    fun `stopping should cancel the scheduled poll`() {
        val testSubject = createTestSubject()
        testSubject.updateFolders(listOf("inbox-id"))

        testSubject.start()
        testSubject.stop()

        assertThat(scheduler.hasPending).isEqualTo(false)
    }

    @Test
    fun `with no pushed folders nothing should be requested`() {
        val testSubject = createTestSubject()

        testSubject.start()
        scheduler.runPending()

        assertThat(server.requestCount).isEqualTo(0)
        // Still scheduled, so enabling push on a folder later takes effect.
        assertThat(scheduler.hasPending).isEqualTo(true)
    }

    @Test
    fun `reconnecting should poll immediately`() {
        val testSubject = createTestSubject()
        testSubject.updateFolders(listOf("inbox-id"))

        testSubject.start()
        testSubject.reconnect()

        assertThat(scheduler.lastDelaySeconds).isEqualTo(0L)
    }

    @Test
    fun `an interval below the floor should be raised`() {
        val testSubject = createTestSubject(pollIntervalSeconds = 1)
        testSubject.updateFolders(listOf("inbox-id"))

        testSubject.start()

        // Honouring an arbitrarily small interval would turn push into a request storm.
        assertThat(scheduler.lastDelaySeconds).isNotNull().isGreaterThanOrEqualTo(MIN_POLL_INTERVAL_SECONDS)
    }

    @Test
    fun `state for a folder no longer pushed should be forgotten`() {
        val testSubject = createTestSubject()
        testSubject.updateFolders(listOf("inbox-id"))
        enqueueFolderState("inbox-id", total = 10, unread = 2)
        testSubject.start()
        scheduler.runPending()

        testSubject.updateFolders(emptyList())
        testSubject.updateFolders(listOf("inbox-id"))
        enqueueFolderState("inbox-id", total = 99, unread = 9)
        scheduler.runPending()

        // Re-enabling a folder starts a fresh baseline rather than comparing against a stale count.
        assertThat(callback.pushEvents).isEmpty()
    }

    private fun enqueueFolderState(folderServerId: String, total: Int, unread: Int) {
        server.enqueue(
            MockResponse().setBody(
                """
                {"responses":[{"id":"0","status":200,"body":{
                  "id":"$folderServerId","totalItemCount":$total,"unreadItemCount":$unread
                }}]}
                """.trimIndent(),
            ),
        )
    }

    private fun createTestSubject(pollIntervalSeconds: Long = 60) = GraphBackendPusher(
        client = GraphApiClient(
            okHttpClient = OkHttpClient(),
            tokenProvider = FakeOAuth2TokenProvider(),
            baseUrl = server.url("/v1.0/").toString(),
            sleeper = { },
        ),
        callback = callback,
        powerManager = powerManager,
        scheduler = scheduler,
        accountName = "account",
        logger = TestLogger(),
        pollIntervalSecondsProvider = { pollIntervalSeconds },
    )

    /**
     * Runs polls on demand so the tests do not wait in real time.
     */
    private class ManualScheduler : GraphPushScheduler {
        private var pending: (() -> Unit)? = null
        var lastDelaySeconds: Long? = null
            private set

        val hasPending: Boolean get() = pending != null

        override fun schedule(delaySeconds: Long, action: () -> Unit) {
            lastDelaySeconds = delaySeconds
            pending = action
        }

        override fun cancel() {
            pending = null
        }

        fun runPending() {
            val action = pending ?: return
            pending = null
            action.invoke()
        }
    }

    private class RecordingPusherCallback : BackendPusherCallback {
        val pushEvents = mutableListOf<String>()
        var errors = 0
            private set

        override fun onPushEvent(folderServerId: String) {
            pushEvents += folderServerId
        }

        override fun onPushError(exception: Exception) {
            errors++
        }

        override fun onPushNotSupported() = Unit
    }

    private class CountingPowerManager : PowerManager {
        var acquired = 0
            private set
        var released = 0
            private set

        override fun newWakeLock(tag: String): WakeLock {
            return object : WakeLock {
                override fun acquire(timeout: Long) {
                    acquired++
                }

                override fun acquire() {
                    acquired++
                }

                override fun setReferenceCounted(counted: Boolean) = Unit

                override fun release() {
                    released++
                }
            }
        }
    }
}
