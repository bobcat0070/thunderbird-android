package net.thunderbird.feature.mail.message.classification.internal

import net.thunderbird.feature.mail.message.classification.api.MessageClassifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Qualifier for the header-only classifier, so the overriding classifier can depend on it without the two
 * bindings colliding on [MessageClassifier].
 */
private val ruleBased = named("ruleBasedMessageClassifier")

val featureMessageClassificationModule = module {
    factory<MessageClassifier>(ruleBased) { RuleBasedMessageClassifier() }

    factory<MessageClassifier> {
        OverridingMessageClassifier(
            overrideStore = get(),
            delegate = get(ruleBased),
        )
    }
}
