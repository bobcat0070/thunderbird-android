package com.fsck.k9.ui.servercategories

/**
 * What the category dialog is showing: the categories on offer and the ones that are ticked.
 *
 * @param options every category on offer, in the order shown.
 * @param selected the ones that are ticked, in the order of [options].
 */
data class ServerCategorySelection(
    val options: List<String> = emptyList(),
    val selected: List<String> = emptyList(),
) {
    /**
     * Ticks or unticks [category].
     */
    fun select(category: String, isSelected: Boolean): ServerCategorySelection {
        val nowSelected = if (isSelected) selected + category else selected - category

        return copy(selected = options.filter { it in nowSelected })
    }

    /**
     * Offers a category the user typed and ticks it. A name that is already on offer, in whatever case, is
     * ticked instead of being offered twice; Outlook treats such names as the same category.
     */
    fun add(name: String): ServerCategorySelection {
        val category = name.trim()
        if (category.isEmpty()) return this

        val existing = options.firstOrNull { it.equals(category, ignoreCase = true) }
        if (existing != null) return select(existing, isSelected = true)

        return ServerCategorySelection(options = options + category, selected = selected + category)
    }

    /**
     * Packs the selection into a list of strings, for the dialog's saved state.
     */
    fun save(): ArrayList<String> {
        return ArrayList(options.map { category -> (if (category in selected) SELECTED else NOT_SELECTED) + category })
    }

    companion object {
        private const val SELECTED = "+"
        private const val NOT_SELECTED = "-"

        /**
         * @param assigned the categories the message has. They are offered even when no other stored message
         *   has them.
         * @param known the categories in use in the account.
         */
        fun create(assigned: List<String>, known: List<String>): ServerCategorySelection {
            val options = (known + assigned).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

            return ServerCategorySelection(options = options, selected = options.filter { it in assigned })
        }

        fun restore(saved: List<String>): ServerCategorySelection {
            return ServerCategorySelection(
                options = saved.map { it.drop(1) },
                selected = saved.filter { it.startsWith(SELECTED) }.map { it.drop(1) },
            )
        }
    }
}
