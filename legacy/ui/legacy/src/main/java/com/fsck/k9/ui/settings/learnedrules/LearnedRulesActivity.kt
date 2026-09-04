package com.fsck.k9.ui.settings.learnedrules

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.fsck.k9.ui.R
import com.fsck.k9.ui.base.BaseActivity
import com.fsck.k9.ui.base.extensions.fragmentTransaction

/**
 * Hosts the screen listing what the app has been taught.
 */
class LearnedRulesActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setLayout(R.layout.general_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.learned_rules_title)

        if (savedInstanceState == null) {
            fragmentTransaction {
                replace(R.id.generalSettingsContainer, LearnedRulesFragment())
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LearnedRulesActivity::class.java))
        }
    }
}
