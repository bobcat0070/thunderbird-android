package net.thunderbird.feature.spamdigest.internal

import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.datetime.TimeZone
import net.thunderbird.feature.spamdigest.SpamDigestScheduler
import net.thunderbird.feature.spamdigest.SpamDigestSettingsRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Binds the spam digest. The accounts, spam folders and sending it relies on are bound by the app, as
 * [net.thunderbird.feature.spamdigest.SpamDigestAccounts], [net.thunderbird.feature.spamdigest.SpamFolderReader]
 * and [net.thunderbird.feature.spamdigest.SpamDigestMailer].
 */
val featureSpamDigestModule: Module = module {
    single<SpamDigestSettingsStore> {
        SharedPreferencesSpamDigestSettingsStore(context = androidContext())
    } bind SpamDigestLog::class

    single<SpamDigestSettingsRepository> {
        ReschedulingSpamDigestSettingsRepository(store = get(), scheduler = get())
    }

    single<TimeZoneProvider> { TimeZoneProvider { TimeZone.currentSystemDefault() } }

    single {
        WorkManagerSpamDigestScheduler(
            workManager = WorkManager.getInstance(androidContext()),
            settingsStore = get(),
            clock = get(),
            timeZoneProvider = get(),
        )
    } bind SpamDigestScheduler::class

    factory {
        SendSpamDigest(
            settingsRepository = get(),
            digestLog = get(),
            accounts = get(),
            spamFolderReader = get(),
            mailer = get(),
            composer = get(),
            clock = get(),
            timeZoneProvider = get(),
            logger = get(),
        )
    }

    factory { SpamDigestComposer(strings = get()) }
    factory<SpamDigestStrings> { AndroidSpamDigestStrings(context = androidContext()) }

    factory { (parameters: WorkerParameters) ->
        SpamDigestWorker(
            sendSpamDigest = get(),
            scheduler = get(),
            logger = get(),
            context = androidContext(),
            parameters = parameters,
        )
    }
}
