package net.thunderbird.app.common.feature.attachments

import com.fsck.k9.ui.messageview.StoredAttachmentViewer
import net.thunderbird.feature.attachments.SenderAttachmentRepository
import net.thunderbird.feature.attachments.internal.featureAttachmentsModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeatureAttachmentsModule = module {
    includes(featureAttachmentsModule)

    factory { StoredAttachmentViewer(context = androidContext()) }

    factory<SenderAttachmentRepository> {
        LegacySenderAttachmentRepository(
            context = androidContext(),
            accountManager = get(),
            messageStoreManager = get(),
            localStoreProvider = get(),
            messagingController = get(),
            attachmentInfoExtractor = get(),
            attachmentViewer = get(),
        )
    }
}
