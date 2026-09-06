package com.fsck.k9.ui.settings.learnedrules

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.lifecycle.lifecycleScope
import com.fsck.k9.mailstore.MessageReclassifier
import com.fsck.k9.ui.R
import com.fsck.k9.ui.messageview.RemoteImageScope
import com.fsck.k9.ui.messageview.RemoteImageSenderStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.thunderbird.feature.mail.message.classification.api.ClassificationOverrideStore
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import org.koin.android.ext.android.inject
import com.fsck.k9.ui.base.R as BaseR

/**
 * Lists everything the user has taught the app, and lets them take it back.
 *
 * Teaching without a way to review is a trap: the corrections accumulate invisibly, and a rule taught by
 * accident keeps working forever with nothing to point at. Both kinds of rule live here because they are the
 * same act from the user's side - telling the app something about a sender.
 */
class LearnedRulesFragment : PreferenceFragmentCompat() {
    private val classificationOverrideStore: ClassificationOverrideStore by inject()
    private val remoteImageSenderStore: RemoteImageSenderStore by inject()
    private val messageReclassifier: MessageReclassifier by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

        rebuild()
    }

    private fun rebuild() {
        val screen = preferenceScreen
        screen.removeAll()

        addCategoryRules(screen)
        addImageRules(screen)

        if (screen.preferenceCount == 0) {
            screen.addPreference(
                Preference(requireContext()).apply {
                    title = getString(R.string.learned_rules_empty)
                    isSelectable = false
                },
            )
        }

        addReclassifyAction(screen)
    }

    /**
     * Applies everything taught so far to the mail already in the mailbox.
     *
     * Corrections normally reach stored mail only when the rules themselves change, which is right for an
     * automatic pass and useless to someone who has just taught the app several things and wants to see the
     * result. This is that button.
     */
    private fun addReclassifyAction(screen: PreferenceScreen) {
        screen.addPreference(
            PreferenceCategory(requireContext()).apply {
                title = getString(R.string.learned_rules_apply_category)
                isPersistent = false
            },
        )

        screen.addPreference(
            Preference(requireContext()).apply {
                title = getString(R.string.learned_rules_reclassify_title)
                summary = getString(R.string.learned_rules_reclassify_summary)
                isPersistent = false
                setOnPreferenceClickListener {
                    reclassifyNow(this)
                    true
                }
            },
        )
    }

    private fun reclassifyNow(preference: Preference) {
        // Disabled while it runs, because a mailbox takes a moment to walk and a second tap would start a
        // second walk over the same messages.
        preference.isEnabled = false
        preference.summary = getString(R.string.learned_rules_reclassify_running)

        lifecycleScope.launch {
            val updated = withContext(Dispatchers.IO) { messageReclassifier.reclassifyEverything() }

            preference.isEnabled = true
            preference.summary = resources.getQuantityString(
                R.plurals.learned_rules_reclassify_result,
                updated,
                updated,
            )
        }
    }

    private fun addCategoryRules(screen: PreferenceScreen) {
        val rules = classificationOverrideStore.rules()
        if (rules.isEmpty()) return

        val category = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.learned_rules_categories)
            isPersistent = false
        }
        screen.addPreference(category)

        for (rule in rules) {
            category.addPreference(
                Preference(requireContext()).apply {
                    title = describe(rule.scope, rule.pattern)
                    summary = getString(classLabel(rule.messageClass))
                    isPersistent = false
                    setOnPreferenceClickListener {
                        confirmForget(title) {
                            classificationOverrideStore.remove(rule.scope, rule.pattern)
                        }
                        true
                    }
                },
            )
        }
    }

    private fun addImageRules(screen: PreferenceScreen) {
        val trusted = remoteImageSenderStore.trusted()
        if (trusted.isEmpty()) return

        val category = PreferenceCategory(requireContext()).apply {
            title = getString(R.string.learned_rules_images)
            isPersistent = false
        }
        screen.addPreference(category)

        for ((scope, pattern) in trusted) {
            category.addPreference(
                Preference(requireContext()).apply {
                    title = describeImageScope(scope, pattern)
                    summary = getString(R.string.learned_rules_images_summary)
                    isPersistent = false
                    setOnPreferenceClickListener {
                        confirmForget(title) { remoteImageSenderStore.forget(pattern, scope) }
                        true
                    }
                },
            )
        }
    }

    /**
     * Asks first: these were deliberate decisions, and a stray tap in a list should not quietly undo one.
     */
    private fun confirmForget(what: CharSequence?, forget: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.learned_rules_forget_title)
            .setMessage(getString(R.string.learned_rules_forget_message, what))
            .setNegativeButton(BaseR.string.cancel_action, null)
            .setPositiveButton(R.string.learned_rules_forget_action) { _, _ ->
                forget()
                rebuild()
            }
            .show()
    }

    private fun describe(scope: RuleScope, pattern: String): String = when (scope) {
        RuleScope.SENDER -> pattern
        RuleScope.DOMAIN -> getString(R.string.learned_rules_anyone_at, pattern)
    }

    private fun describeImageScope(scope: RemoteImageScope, pattern: String): String = when (scope) {
        RemoteImageScope.SENDER -> pattern
        RemoteImageScope.DOMAIN -> getString(R.string.learned_rules_anyone_at, pattern)
    }

    private fun classLabel(messageClass: MessageClass): Int = when (messageClass) {
        MessageClass.HUMAN -> R.string.classify_as_human
        MessageClass.NOTIFICATION -> R.string.classify_as_notification
        MessageClass.NEWSLETTER -> R.string.classify_as_newsletter
        MessageClass.UNKNOWN -> R.string.learned_rules_uncategorised
    }
}
