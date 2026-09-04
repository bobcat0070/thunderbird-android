package net.thunderbird.feature.mail.message.classification.internal

import android.content.Context
import android.content.SharedPreferences
import net.thunderbird.feature.mail.message.classification.api.ClassificationOverrideStore
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import net.thunderbird.feature.mail.message.classification.api.SenderClassificationRule
import org.json.JSONArray
import org.json.JSONObject

private const val PREFERENCES_NAME = "message_classification_overrides"
private const val KEY_RULES = "rules"

private const val FIELD_SCOPE = "scope"
private const val FIELD_PATTERN = "pattern"
private const val FIELD_CLASS = "class"
private const val FIELD_CREATED_AT = "createdAt"

/**
 * Persists taught corrections in their own preferences file.
 *
 * A file rather than a table because these are read on the message-save path, once per message: the whole set
 * is small enough to hold in memory, and a rule lookup should not become a database round trip during a sync.
 * Its own file rather than the shared settings store so that exporting or clearing settings does not entangle
 * the two.
 */
class SharedPreferencesClassificationOverrideStore(
    context: Context,
) : ClassificationOverrideStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Guards [cachedRules] only. Writes are rare (a user teaching a rule); reads happen for every saved
     * message, so they must not hit storage.
     */
    private val lock = Any()

    private var cachedRules: List<SenderClassificationRule>? = null

    override fun rules(): List<SenderClassificationRule> {
        synchronized(lock) {
            return cachedRules ?: readRules().also { cachedRules = it }
        }
    }

    override fun put(rule: SenderClassificationRule) {
        synchronized(lock) {
            val updated = rules().filterNot { it.scope == rule.scope && it.pattern == rule.pattern } + rule

            writeRules(updated)
        }
    }

    override fun remove(scope: RuleScope, pattern: String) {
        synchronized(lock) {
            val updated = rules().filterNot { it.scope == scope && it.pattern == pattern }

            writeRules(updated)
        }
    }

    private fun writeRules(rules: List<SenderClassificationRule>) {
        val sorted = rules.sortedByDescending { it.createdAt }

        preferences.edit().putString(KEY_RULES, sorted.toJson()).apply()
        cachedRules = sorted
    }

    private fun readRules(): List<SenderClassificationRule> {
        val serialized = preferences.getString(KEY_RULES, null) ?: return emptyList()

        return runCatching { serialized.parseRules() }
            // A rule set we cannot read is a corrupt preferences file, not a reason to fail a sync. Losing
            // taught rules degrades classification back to the header rules; throwing here would stop mail.
            .getOrDefault(emptyList())
            .sortedByDescending { it.createdAt }
    }
}

private fun List<SenderClassificationRule>.toJson(): String {
    val array = JSONArray()

    for (rule in this) {
        array.put(
            JSONObject().apply {
                put(FIELD_SCOPE, rule.scope.name)
                put(FIELD_PATTERN, rule.pattern)
                put(FIELD_CLASS, rule.messageClass.name)
                put(FIELD_CREATED_AT, rule.createdAt)
            },
        )
    }

    return array.toString()
}

private fun String.parseRules(): List<SenderClassificationRule> {
    val array = JSONArray(this)

    return (0 until array.length()).mapNotNull { index ->
        val entry = array.getJSONObject(index)
        val scope = enumValueOrNull<RuleScope>(entry.optString(FIELD_SCOPE))
        val messageClass = enumValueOrNull<MessageClass>(entry.optString(FIELD_CLASS))
        val pattern = entry.optString(FIELD_PATTERN)

        // Skip rather than fail: an unknown scope or class is a rule written by a newer version of the app,
        // and the rest of the user's rules should still work after a downgrade.
        if (scope == null || messageClass == null || pattern.isEmpty()) {
            null
        } else {
            SenderClassificationRule(
                scope = scope,
                pattern = pattern,
                messageClass = messageClass,
                createdAt = entry.optLong(FIELD_CREATED_AT),
            )
        }
    }
}

private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
    enumValues<T>().firstOrNull { it.name == name }
