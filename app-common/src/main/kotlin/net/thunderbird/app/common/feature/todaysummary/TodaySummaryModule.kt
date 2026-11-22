package net.thunderbird.app.common.feature.todaysummary

import androidx.work.WorkerParameters
import net.thunderbird.feature.todaysummary.TodaySummaryDestinations
import net.thunderbird.feature.todaysummary.TodaySummarySource
import net.thunderbird.feature.todaysummary.internal.featureTodaySummaryModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeatureTodaySummaryModule = module {
    includes(featureTodaySummaryModule)

    factory<TodaySummarySource> {
        LegacyTodaySummarySource(
            accountManager = get(),
            messageStoreManager = get(),
            generalSettingsManager = get(),
            parcelsArrivingToday = get(),
            clock = get(),
        )
    }
    factory<TodaySummaryDestinations> { LegacyTodaySummaryDestinations(context = androidContext()) }
    factory { TodaySummaryScheduler(workManager = get(), generalSettingsManager = get(), job = get()) }
    factory { (parameters: WorkerParameters) ->
        TodaySummaryWorker(
            generalSettingsManager = get(),
            job = get(),
            context = androidContext(),
            workerParams = parameters,
        )
    }
}
