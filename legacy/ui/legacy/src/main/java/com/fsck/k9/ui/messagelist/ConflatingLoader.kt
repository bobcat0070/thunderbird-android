package com.fsck.k9.ui.messagelist

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Loads something each time it is asked to, but never twice at once.
 *
 * A request that arrives while a load is running does not start another: however many arrive, they are answered by a
 * single load once the running one has finished. That load starts after the last change it was asked for, so the
 * result is never older than the newest request.
 *
 * @param loadContext where [load] runs; [onLoaded] runs in the [scope]'s own context.
 * @param pauseAfterLoadMillis how long to wait after a load before starting the next, so a stream of requests does
 *   not keep the loader busy without a break.
 */
internal class ConflatingLoader<T>(
    scope: CoroutineScope,
    private val loadContext: CoroutineContext,
    private val load: () -> T,
    private val onLoaded: (T) -> Unit,
    private val pauseAfterLoadMillis: Long = 0,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                // Requests that arrived since this one woke the loop are answered by the load about to start.
                requests.tryReceive()
                onLoaded(withContext(loadContext) { load() })
                delay(pauseAfterLoadMillis)
            }
        }
    }

    fun requestLoad() {
        requests.trySend(Unit)
    }
}
