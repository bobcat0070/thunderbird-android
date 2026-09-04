package net.thunderbird.backend.graph

import com.fsck.k9.mail.power.PowerManager

/**
 * Default interval between push polls.
 *
 * Frequent enough that mail feels immediate, while each poll is a single request reading only message counts. IMAP
 * push holds a connection open continuously, so this is not the more expensive of the two.
 */
const val DEFAULT_PUSH_POLL_INTERVAL_SECONDS = 60L

/**
 * What the app must supply for a Graph account to support push.
 *
 * Polling has to wake the device and hold it awake for the request, neither of which the backend module can arrange
 * on its own. An account without this runs on periodic background sync instead, and reports itself as not push
 * capable rather than pretending otherwise.
 */
data class GraphPushSupport(
    val powerManager: PowerManager,
    val scheduler: GraphPushScheduler,
    val accountName: String,
    val pollIntervalSecondsProvider: () -> Long = { DEFAULT_PUSH_POLL_INTERVAL_SECONDS },
)
