package com.fsck.k9.ui.servercategories

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.fsck.k9.ui.R
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.fsck.k9.ui.base.R as BaseR

private const val ARG_ASSIGNED_CATEGORIES = "assignedCategories"
private const val ARG_KNOWN_CATEGORIES = "knownCategories"
private const val STATE_SELECTION = "selection"

/**
 * Asks which of the categories the server keeps a message should have.
 *
 * Offers the categories already in use in the account, with the ones on this message ticked, and a field for
 * a name that is not among them. The mailbox's own list of categories cannot be read, so a category that is
 * defined in Outlook but not used on any stored message has to be typed.
 */
class ServerCategoriesDialogFragment : DialogFragment() {
    private var selection = ServerCategorySelection()
    private lateinit var optionsView: LinearLayout

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        selection = savedInstanceState?.getStringArrayList(STATE_SELECTION)?.let(ServerCategorySelection::restore)
            ?: ServerCategorySelection.create(
                assigned = requireArguments().getStringArrayList(ARG_ASSIGNED_CATEGORIES).orEmpty(),
                known = requireArguments().getStringArrayList(ARG_KNOWN_CATEGORIES).orEmpty(),
            )

        val view = layoutInflater.inflate(R.layout.dialog_server_categories, null)
        optionsView = view.findViewById(R.id.server_category_options)
        val newCategoryView = view.findViewById<EditText>(R.id.server_category_new)
        val addView = view.findViewById<View>(R.id.server_category_add)

        showOptions()

        addView.setOnClickListener { addCategory(newCategoryView) }
        newCategoryView.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addCategory(newCategoryView)
                true
            } else {
                false
            }
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.server_categories_title)
            .setView(view)
            .setNegativeButton(BaseR.string.cancel_action, null)
            .setPositiveButton(BaseR.string.okay_action) { _, _ ->
                // A name still sitting in the field was meant to be added.
                selection = selection.add(newCategoryView.text.toString())

                setFragmentResult(
                    FRAGMENT_RESULT_KEY,
                    Bundle().apply { putStringArrayList(RESULT_CATEGORIES, ArrayList(selection.selected)) },
                )
            }
            .create()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTION, selection.save())
    }

    private fun addCategory(newCategoryView: EditText) {
        selection = selection.add(newCategoryView.text.toString())
        newCategoryView.text.clear()
        showOptions()
    }

    private fun showOptions() {
        optionsView.removeAllViews()

        for (category in selection.options) {
            val checkBox = MaterialCheckBox(requireContext()).apply {
                text = category
                isChecked = category in selection.selected
                setOnCheckedChangeListener { _, isChecked ->
                    selection = selection.select(category, isChecked)
                }
            }
            optionsView.addView(checkBox)
        }
    }

    companion object {
        const val FRAGMENT_RESULT_KEY = "serverCategories"
        const val RESULT_CATEGORIES = "categories"

        /**
         * @param assignedCategories the categories the message has now.
         * @param knownCategories the categories in use in the account, to choose from.
         */
        fun create(assignedCategories: List<String>, knownCategories: List<String>): ServerCategoriesDialogFragment {
            return ServerCategoriesDialogFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(ARG_ASSIGNED_CATEGORIES, ArrayList(assignedCategories))
                    putStringArrayList(ARG_KNOWN_CATEGORIES, ArrayList(knownCategories))
                }
            }
        }
    }
}
