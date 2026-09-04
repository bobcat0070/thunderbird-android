package com.fsck.k9.contacts

import com.fsck.k9.contacts.bimi.BimiLogoLoader
import com.fsck.k9.contacts.bimi.CertificateRevocationChecker
import net.thunderbird.core.logging.Logger
import com.fsck.k9.contacts.bimi.PlatformDnsTxtLookup
import com.fsck.k9.contacts.bimi.VmcValidator
import com.fsck.k9.contacts.bimi.loadMvaRoots
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import okhttp3.OkHttpClient
import org.koin.dsl.module

/**
 * Short by design: an avatar is decoration on a list row, so a slow lookup should give up and let the letter
 * avatar be drawn rather than hold the row.
 */
private const val GRAVATAR_TIMEOUT_SECONDS = 10L

val contactsModule = module {
    single { AvatarCache(context = androidContext()) }
    single { ContactLetterExtractor() }
    factory { ContactLetterBitmapConfig(context = get(), themeManager = get(), messageListPreferencesManager = get()) }
    factory { ContactLetterBitmapCreator(letterExtractor = get(), config = get()) }
    factory { ContactPhotoLoader(contentResolver = get(), contactRepository = get()) }
    factory { ContactPictureLoader(context = get(), contactLetterBitmapCreator = get()) }
    single(named("gravatarHttpClient")) {
        OkHttpClient.Builder()
            .connectTimeout(GRAVATAR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(GRAVATAR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
    single {
        GravatarLoader(
            generalSettingsManager = get(),
            httpClient = get(named("gravatarHttpClient")),
            cache = get(),
            logger = get(),
        )
    }
    single {
        BimiLogoLoader(
            generalSettingsManager = get(),
            dnsTxtLookup = PlatformDnsTxtLookup(executor = Executors.newCachedThreadPool()),
            httpClient = get(named("gravatarHttpClient")),
            cache = get(),
            vmcValidator = VmcValidator(
                trustAnchors = loadMvaRoots(androidContext()),
                onRejected = { reason -> get<Logger>().debug("VmcValidator") { reason } },
                revocationChecker = CertificateRevocationChecker(
                    fetch = CachingUrlFetcher(
                        httpClient = get(named("gravatarHttpClient")),
                        cache = get(),
                    )::fetch,
                ),
            ),
            logger = get(),
        )
    }
    single {
        WebsiteIconLoader(
            generalSettingsManager = get(),
            httpClient = get(named("gravatarHttpClient")),
            cache = get(),
            logger = get(),
        )
    }
    factory {
        ContactImageBitmapDecoderFactory(
            contactPhotoLoader = get(),
            gravatarLoader = get(),
            bimiLogoLoader = get(),
            websiteIconLoader = get(),
        )
    }
}
