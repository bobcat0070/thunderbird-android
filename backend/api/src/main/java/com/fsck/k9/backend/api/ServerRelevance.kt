package com.fsck.k9.backend.api

/**
 * The header a backend writes onto a message it stores to say how the provider itself sorted that message - for
 * Microsoft 365, whether Focused Inbox put it in Focused or Other.
 *
 * Read by message classification, which lists the same name among the headers it looks at. The backend replaces
 * any copy a sender wrote, so the value is always the provider's.
 */
const val SERVER_RELEVANCE_HEADER = "X-Thunderbird-Server-Relevance"

const val SERVER_RELEVANCE_FOCUSED = "focused"
const val SERVER_RELEVANCE_OTHER = "other"
