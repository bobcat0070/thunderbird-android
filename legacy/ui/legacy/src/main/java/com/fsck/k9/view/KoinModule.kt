package com.fsck.k9.view

import com.fsck.k9.helper.ReplyToParser
import com.fsck.k9.mail.Message
import com.fsck.k9.mailstore.AttachmentResolver
import com.fsck.k9.message.LegacyReplyActionStrategy
import com.fsck.k9.ui.helper.RelativeDateTimeFormatter
import com.fsck.k9.view.MessageWebView.OnPageFinishedListener
import kotlin.time.ExperimentalTime
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.mail.message.reader.api.strategy.ReplyActionStrategy
import org.koin.dsl.module

val viewModule = module {
    single { WebViewConfigProvider(themeManager = get(), generalSettingsManager = get()) }
    factory {
        @OptIn(ExperimentalTime::class)
        RelativeDateTimeFormatter(context = get(), clock = get())
    }
    factory { ReplyToParser() }
    factory<ReplyActionStrategy<LegacyAccount, Message>> {
        LegacyReplyActionStrategy(
            replyRoParser = get(),
        )
    }
    factory { parameters ->
        K9WebViewClient(
            clipboardManager = get(),
            linkConfirmation = get(),
            attachmentResolver = parameters.values.getOrNull(0) as AttachmentResolver?,
            onPageFinishedListener = parameters.values.getOrNull(1) as OnPageFinishedListener?,
            displayedHtml = parameters.values.getOrNull(2) as String?,
        )
    }
    factory { WebViewClientFactory() }
    factory { LinkConfirmation(generalSettingsManager = get(), domainBlocklist = get()) }
    factory { UserInputEmailAddressParser() }
}
