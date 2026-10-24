package com.fsck.k9.activity.compose

/**
 * Notices a message that talks about an attachment it does not have.
 */
object AttachmentReminder {
    private val WORD_START = Regex("""(?<![\p{L}\p{N}])""")

    /**
     * @param text what the user wrote. Lines quoted with `>` are someone else's words and are not read.
     * @param keywords the starts of the words that mention an attachment, comma-separated - "attach" covers
     *   attached, attaching and attachment. From a translated string, so each language lists its own.
     * @return whether [text] has a word starting with one of [keywords].
     */
    @JvmStatic
    fun mentionsAttachment(text: CharSequence, keywords: String): Boolean {
        val stems = keywords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (stems.isEmpty()) return false

        val pattern = Regex(
            WORD_START.pattern + "(?:" + stems.joinToString("|") { Regex.escape(it) } + ")",
            RegexOption.IGNORE_CASE,
        )

        return text.lineSequence()
            .filterNot { it.trimStart().startsWith(">") }
            .any { line -> pattern.containsMatchIn(line) }
    }
}
