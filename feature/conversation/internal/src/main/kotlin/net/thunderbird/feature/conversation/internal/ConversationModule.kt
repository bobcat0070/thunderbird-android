package net.thunderbird.feature.conversation.internal

import net.thunderbird.feature.conversation.ConversationNavigation
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Binds the conversation screen. [net.thunderbird.feature.conversation.ConversationSource] is bound by the app.
 */
val featureConversationModule = module {
    single<ConversationNavigation> { DefaultConversationNavigation() }
    viewModel { ConversationViewModel(source = get()) }
}
