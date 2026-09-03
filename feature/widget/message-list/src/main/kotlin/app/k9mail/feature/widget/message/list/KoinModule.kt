package app.k9mail.feature.widget.message.list

import org.koin.core.qualifier.named
import org.koin.dsl.module

val messageListWidgetModule = module {
    single {
        MessageListWidgetManager(
            context = get(),
            messageListRepository = get(),
            config = get(),
            widgetSettingsPreferenceManager = get(),
            coroutineScope = get(named("AppCoroutineScope")),
        )
    }
    factory {
        MessageListLoader(
            accountManager = get(),
            messageListRepository = get(),
            messageHelper = get(),
            messageListPreferencesManager = get(),
            outboxFolderManager = get(),
            generalSettingsManager = get(),
        )
    }
}
