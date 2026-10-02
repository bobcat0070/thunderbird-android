package app.k9mail.legacy.mailstore

/**
 * How the categories a server keeps on a message - Outlook's, on a Microsoft 365 account - are written to the
 * `server_categories` column: one name per line.
 *
 * Not to be confused with the classification of a message, which the app decides for itself and stores in the
 * `classification` column.
 */
object ServerCategoriesColumn {
    private const val SEPARATOR = '\n'
    private val LINE_BREAKS = Regex("[\\r\\n]+")

    /**
     * @return the column value, or `null` for a message without categories.
     */
    @JvmStatic
    fun encode(categories: List<String>): String? {
        return normalize(categories).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR.toString())
    }

    @JvmStatic
    fun decode(columnValue: String?): List<String> {
        if (columnValue.isNullOrEmpty()) return emptyList()

        return columnValue.split(SEPARATOR).filter { it.isNotEmpty() }
    }

    /**
     * Category names as they are stored: without line breaks, surrounding blanks, empty names or repeats.
     */
    @JvmStatic
    fun normalize(categories: List<String>): List<String> {
        return categories
            .map { it.replace(LINE_BREAKS, " ").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
