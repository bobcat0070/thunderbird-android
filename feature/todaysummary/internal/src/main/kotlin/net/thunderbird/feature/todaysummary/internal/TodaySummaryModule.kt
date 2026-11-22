package net.thunderbird.feature.todaysummary.internal

import net.thunderbird.feature.todaysummary.TodaySummaryJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Binds the summary of the day. [net.thunderbird.feature.todaysummary.TodaySummarySource] and
 * [net.thunderbird.feature.todaysummary.TodaySummaryDestinations] are bound by the app.
 */
val featureTodaySummaryModule = module {
    factory<TodaySummaryJob> {
        DefaultTodaySummaryJob(
            context = androidContext(),
            source = get(),
            destinations = get(),
            clock = get(),
            logger = get(),
        )
    }
}
