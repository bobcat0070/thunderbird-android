package net.thunderbird.feature.spamdigest.internal

import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.datetime.TimeZone
import net.thunderbird.feature.spamdigest.SpamArrivalListener
import net.thunderbird.feature.spamdigest.SpamDigestScheduler
import net.thunderbird.feature.spamdigest.SpamDigestSettingsRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * Binds the spam digest. The accounts, spam folders and sending it relies on are bound by the app, as
 * [net.thunderbird.feature.spamdigest.SpamDigestAccounts], [net.thunderbird.feature.spamdigest.SpamFolderReader],
 * [net.thunderbird.feature.spamdigest.SpamDigestMailer], [net.thunderbird.feature.spamdigest.SpamAlertNotifier],
 * [net.thunderbird.feature.spamdigest.SpamFolderBackgroundSync] and
 * [net.thunderbird.feature.spamdigest.SpamDigestWorkNotification].
 */
val featureSpamDigestModule: Module = module {
    single<SpamDigestSettingsStore> {
        SharedPreferencesSpamDigestSettingsStore(context = androidContext(), clock = get())
    } binds arrayOf(SpamDigestLog::class, SpamAlertLog::class)

    single<SpamDigestSettingsRepository> {
        ReschedulingSpamDigestSettingsRepository(store = get(), scheduler = get(), spamFolderBackgroundSync = get())
    }

    single<TimeZoneProvider> { TimeZoneProvider { TimeZone.currentSystemDefault() } }

    single {
        AlarmSpamDigestScheduler(
            context = androidContext(),
            workManager = WorkManager.getInstance(androidContext()),
            settingsStore = get(),
            clock = get(),
            timeZoneProvider = get(),
            logger = get(),
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

    single<SpamArrivalListener> {
        SpamArrivalAlerter(alertLog = get(), notifier = get(), clock = get(), logger = get())
    }

    factory { (parameters: WorkerParameters) ->
        SpamDigestWorker(
            sendSpamDigest = get(),
            workNotification = get(),
            logger = get(),
            context = androidContext(),
            parameters = parameters,
        )
    }
}
