package net.thunderbird.app.common.feature.conversation

import net.thunderbird.feature.conversation.ConversationSource
import net.thunderbird.feature.conversation.internal.featureConversationModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeatureConversationModule = module {
    includes(featureConversationModule)

    factory<ConversationSource> {
        LegacyConversationSource(
            context = androidContext(),
            messageListLoader = get(),
            localStoreProvider = get(),
            messagingController = get(),
            extractorFactory = get(),
            htmlSettingsProvider = get(),
        )
    }
}
