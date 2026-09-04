package net.thunderbird.backend.graph

/**
 * Schedules the next poll of a [GraphBackendPusher].
 *
 * This is an abstraction so the pusher can be tested without waiting in real time, and so the app can supply a
 * scheduler that wakes the device. A poll that only runs while the device happens to be awake would defeat the point
 * of pushing.
 */
interface GraphPushScheduler {
    /**
     * Runs [action] after [delaySeconds], replacing any poll already scheduled.
     */
    fun schedule(delaySeconds: Long, action: () -> Unit)

    /**
     * Cancels a scheduled poll, if any.
     */
    fun cancel()
}
