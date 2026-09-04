package net.thunderbird.feature.mail.message.classification.internal

import net.thunderbird.feature.mail.message.classification.api.ClassificationOverrideStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Binds the parts of classification that need a platform to store on.
 *
 * Separate from [featureMessageClassificationModule] so the rules stay usable without Android — the classifier
 * itself is a pure function and is tested as one.
 */
val featureMessageClassificationAndroidModule = module {
    single<ClassificationOverrideStore> {
        SharedPreferencesClassificationOverrideStore(
            context = androidContext(),
        )
    }
}
