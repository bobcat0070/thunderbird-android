package net.thunderbird.app.common.feature.plusaddress

import com.fsck.k9.preferences.ExternalGlobalSettings
import net.thunderbird.app.common.feature.settings.PlusAddressExternalSettings
import net.thunderbird.feature.plusaddress.PlusAddressMailSource
import net.thunderbird.feature.plusaddress.internal.featurePlusAddressModule
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

internal val appCommonFeaturePlusAddressModule = module {
    includes(featurePlusAddressModule)

    factory<PlusAddressMailSource> {
        LegacyPlusAddressMailSource(accountManager = get(), messageStoreManager = get())
    }

    // Named, because several definitions bind ExternalGlobalSettings and Koin refuses a second unnamed one.
    factory(named("plusAddressExternalSettings")) {
        PlusAddressExternalSettings(settings = get())
    } bind ExternalGlobalSettings::class
}
