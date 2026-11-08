package net.thunderbird.app.common.feature.packages

import androidx.work.WorkerParameters
import net.thunderbird.app.common.feature.mailscan.RecentMailDownloadScheduler
import net.thunderbird.app.common.feature.mailscan.RecentMailDownloadWorker
import net.thunderbird.feature.packages.ShippingMailSource
import net.thunderbird.feature.packages.internal.featurePackagesModule
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

internal val appCommonFeaturePackagesModule = module {
    includes(featurePackagesModule)

    single { ShippingMailCache(context = androidContext()) }
    single<ShippingMailSource> {
        LegacyShippingMailSource(
            context = androidContext(),
            accountManager = get(),
            messageStoreManager = get(),
            localStoreProvider = get(),
            messagingController = get(),
            linkConfirmation = get(),
            cache = get(),
        )
    }

    factory { RecentMailDownloadScheduler(workManager = get()) }
    factory { (parameters: WorkerParameters) ->
        RecentMailDownloadWorker(
            generalSettingsManager = get(),
            shippingMailDownloader = get(),
            travelMailDownloader = get(),
            context = androidContext(),
            workerParams = parameters,
        )
    }
}
