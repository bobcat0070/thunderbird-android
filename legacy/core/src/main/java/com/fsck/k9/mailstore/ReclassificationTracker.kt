package com.fsck.k9.mailstore

import android.content.Context

private const val PREFERENCES_NAME = "message_classification"
private const val KEY_LAST_VERSION = "last_reclassified_version"

/**
 * Never run: no pass has been recorded, so a version of 0 stands for "older than any classifier".
 */
private const val NEVER = 0

/**
 * Remembers which version of the rules the stored mail has been brought up to.
 *
 * Without this, deciding whether a pass is needed would mean scanning every message in every account on every
 * launch to find out that there is nothing to do - which is the answer almost every time, since the rules only
 * change when a new build ships. Reading one integer instead makes the common case free.
 */
class ReclassificationTracker(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun isPassNeeded(classifierVersion: Int): Boolean {
        return preferences.getInt(KEY_LAST_VERSION, NEVER) < classifierVersion
    }

    fun recordPass(classifierVersion: Int) {
        preferences.edit().putInt(KEY_LAST_VERSION, classifierVersion).apply()
    }
}
