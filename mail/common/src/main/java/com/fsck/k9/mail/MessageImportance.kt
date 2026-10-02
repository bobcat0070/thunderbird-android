package com.fsck.k9.mail

/**
 * How important the sender said a message is.
 *
 * Travels in the `Importance` header, which is what Outlook and Exchange read and write. `X-Priority` is the older
 * convention other clients still send, so it is read when `Importance` is absent and written alongside it.
 */
enum class MessageImportance {
    LOW,
    NORMAL,
    HIGH,
    ;

    companion object {
        const val HEADER_IMPORTANCE = "Importance"
        const val HEADER_PRIORITY = "X-Priority"

        private const val IMPORTANCE_HIGH = "high"
        private const val IMPORTANCE_LOW = "low"
        private const val PRIORITY_HIGHEST = "1"
        private const val PRIORITY_LOWEST = "5"
        private const val PRIORITY_NORMAL = 3

        /**
         * Reads the importance out of header values.
         *
         * @param importance the value of the `Importance` header: `high`, `normal` or `low`.
         * @param priority the value of the `X-Priority` header: 1 (highest) to 5 (lowest), often followed by a
         *   description such as `1 (Highest)`.
         */
        fun fromHeaders(importance: String?, priority: String?): MessageImportance {
            when (importance?.trim()?.lowercase()) {
                IMPORTANCE_HIGH -> return HIGH
                IMPORTANCE_LOW -> return LOW
                null, "" -> Unit
                else -> return NORMAL
            }

            val priorityLevel = priority?.trim()?.firstOrNull()?.digitToIntOrNull() ?: return NORMAL

            return when {
                priorityLevel < PRIORITY_NORMAL -> HIGH
                priorityLevel > PRIORITY_NORMAL -> LOW
                else -> NORMAL
            }
        }
    }

    /**
     * The value of the `Importance` header for this importance, or `null` for [NORMAL], which is what a message
     * without the header already is.
     */
    val importanceHeaderValue: String?
        get() = when (this) {
            HIGH -> IMPORTANCE_HIGH
            LOW -> IMPORTANCE_LOW
            NORMAL -> null
        }

    /**
     * The value of the `X-Priority` header for this importance, or `null` for [NORMAL].
     */
    val priorityHeaderValue: String?
        get() = when (this) {
            HIGH -> PRIORITY_HIGHEST
            LOW -> PRIORITY_LOWEST
            NORMAL -> null
        }
}

/**
 * The importance this message's headers state.
 */
val Message.importance: MessageImportance
    get() = MessageImportance.fromHeaders(
        importance = getHeader(MessageImportance.HEADER_IMPORTANCE).firstOrNull(),
        priority = getHeader(MessageImportance.HEADER_PRIORITY).firstOrNull(),
    )

/**
 * States [importance] in this message's headers, replacing whatever they said before.
 */
fun Message.setImportance(importance: MessageImportance) {
    removeHeader(MessageImportance.HEADER_IMPORTANCE)
    removeHeader(MessageImportance.HEADER_PRIORITY)

    importance.importanceHeaderValue?.let { setHeader(MessageImportance.HEADER_IMPORTANCE, it) }
    importance.priorityHeaderValue?.let { setHeader(MessageImportance.HEADER_PRIORITY, it) }
}
