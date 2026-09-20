package com.fsck.k9.ui

import android.app.Activity
import android.content.Context
import app.k9mail.core.ui.compose.common.window.FoldableStateObserver
import app.k9mail.legacy.message.controller.MessagingControllerMailChecker
import com.fsck.k9.controller.MessagingController
import com.fsck.k9.ui.helper.DisplayHtmlUiFactory
import com.fsck.k9.ui.helper.SizeFormatter
import com.fsck.k9.ui.messagelist.LegacyMessageListFragment
import com.fsck.k9.ui.messagelist.MessageListFragment
import com.fsck.k9.ui.messagelist.MessageListFragmentBridgeContract
import com.fsck.k9.ui.messageview.LinkTextHandler
import com.fsck.k9.ui.settings.AboutViewModel
import com.fsck.k9.ui.share.ShareIntentBuilder
import net.thunderbird.core.common.inject.getList
import net.thunderbird.core.featureflag.FeatureFlagProvider
import net.thunderbird.core.featureflag.keys.GeneratedFeatureFlagKey
import org.koin.core.module.dsl.viewModel
import com.fsck.k9.activity.compose.DefaultRecipientSuggestions
import com.fsck.k9.activity.compose.RecipientSuggestions
import org.koin.core.qualifier.named
import org.koin.dsl.module

val uiModule = module {
    // Its collaborators are looked up when a question is asked rather than here, so building a recipient field
    // never constructs a mail backend.
    single<RecipientSuggestions> { DefaultRecipientSuggestions() }
    factory {
        DisplayHtmlUiFactory(
            cssClassNameProvider = get(),
            cssStyleProviders = getList(),
            messageReaderHtmlSettingsProvider = get(),
            messageComposerHtmlSettingsProvider = get(),
        )
    }
    single<MessagingControllerMailChecker> { get<MessagingController>() }
    viewModel { AboutViewModel(appVersionProvider = get()) }
    factory(named("MessageView")) { get<DisplayHtmlUiFactory>().createForMessageView() }
    factory { (context: Context) -> SizeFormatter(context.resources) }
    factory { ShareIntentBuilder(resourceProvider = get(), textPartFinder = get(), quoteDateFormatter = get()) }
    factory { LinkTextHandler(context = get(), clipboardManager = get()) }
    factory { (activity: Activity) ->
        FoldableStateObserver(activity = activity, logger = get())
    }
    factory<MessageListFragmentBridgeContract.Factory> {
        val featureFlagProvider = get<FeatureFlagProvider>()
        if (featureFlagProvider.provide(GeneratedFeatureFlagKey.ENABLE_MESSAGE_LIST_NEW_STATE).isEnabled()) {
            MessageListFragment.Factory
        } else {
            LegacyMessageListFragment.Factory
        }
    }
}
