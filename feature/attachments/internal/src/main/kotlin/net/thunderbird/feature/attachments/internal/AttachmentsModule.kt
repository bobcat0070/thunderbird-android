package net.thunderbird.feature.attachments.internal

import net.thunderbird.feature.attachments.AttachmentsNavigation
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Binds the attachments screen. [net.thunderbird.feature.attachments.SenderAttachmentRepository] is bound by the
 * app.
 */
val featureAttachmentsModule = module {
    single<AttachmentsNavigation> { DefaultAttachmentsNavigation() }
    viewModel { SenderAttachmentsViewModel(repository = get()) }
}
