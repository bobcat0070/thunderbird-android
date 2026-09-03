package com.fsck.k9.ui.messageview

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val messageViewUiModule = module {
    single { RemoteImageSenderStore(context = androidContext()) }

    factory {
        createMessageViewRecipientFormatter(
            contactNameProvider = get(),
            resources = get(),
            messageListPreferencesManager = get(),
        )
    }
}
