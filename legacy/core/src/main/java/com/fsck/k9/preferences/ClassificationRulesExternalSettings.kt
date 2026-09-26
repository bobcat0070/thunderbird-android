package com.fsck.k9.preferences

import com.fsck.k9.preferences.ExternalSettingKeys.LEARNED_CLASSIFICATION_RULES_KEY
import net.thunderbird.feature.mail.message.classification.api.ClassificationOverrideStore
import net.thunderbird.feature.mail.message.classification.api.MessageClass
import net.thunderbird.feature.mail.message.classification.api.RuleScope
import net.thunderbird.feature.mail.message.classification.api.SenderClassificationRule
import org.json.JSONArray
import org.json.JSONObject

private const val FIELD_SCOPE = "scope"
private const val FIELD_PATTERN = "pattern"
private const val FIELD_CLASS = "class"
private const val FIELD_CREATED_AT = "createdAt"

/**
 * Carries the classification corrections the user taught through settings export.
 *
 * These rules are some of the most deliberate settings there are - each one is a correction the user made by hand -
 * and they are keyed by sender address or domain, so they mean the same thing on any device.
 */
internal class ClassificationRulesExternalSettings(
    private val store: ClassificationOverrideStore,
) : ExternalGlobalSettings {

    override val keys: Set<String> = setOf(LEARNED_CLASSIFICATION_RULES_KEY)

    override fun exportSettings(): Map<String, String> {
        return mapOf(LEARNED_CLASSIFICATION_RULES_KEY to store.rules().toJson())
    }

    /**
     * A rule already taught on this device wins over an imported one for the same sender when it is newer, so
     * importing an old export cannot undo a correction made since.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    override fun importSettings(values: Map<String, String>) {
        val serialized = values[LEARNED_CLASSIFICATION_RULES_KEY] ?: return
        val imported = try {
            serialized.parseRules()
        } catch (e: Exception) {
            // An unreadable value is skipped rather than failing the whole import over one setting.
            return
        }

        val existing = store.rules().associateBy { it.scope to it.pattern }
        for (rule in imported) {
            val current = existing[rule.scope to rule.pattern]
            if (current == null || current.createdAt < rule.createdAt) {
                store.put(rule)
            }
        }
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
    if (isBlank()) return emptyList()

    val array = JSONArray(this)
    return (0 until array.length()).mapNotNull { index ->
        val entry = array.getJSONObject(index)
        val scope = RuleScope.entries.firstOrNull { it.name == entry.optString(FIELD_SCOPE) }
        val messageClass = MessageClass.entries.firstOrNull { it.name == entry.optString(FIELD_CLASS) }
        val pattern = entry.optString(FIELD_PATTERN).trim().lowercase()

        // A class or scope this version does not know was written by a newer app; the rest still import.
        if (scope == null || messageClass == null || pattern.isEmpty()) {
            null
        } else {
            SenderClassificationRule(scope, pattern, messageClass, entry.optLong(FIELD_CREATED_AT))
        }
    }
}
