package com.fsck.k9.contacts

import com.fsck.k9.contacts.bimi.BimiLogoLoader
import com.fsck.k9.contacts.bimi.CertificateRevocationChecker
import com.fsck.k9.contacts.bimi.DnsTxtLookup
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
    // Declared in its own right rather than built inside the loader below: it is an interface with a
    // platform implementation, which is what the graph is for, and a dependency constructed inside another
    // definition's lambda is invisible to it - nothing else can reuse it, and the dependency-tree test
    // cannot see that it was ever supplied.
    single<DnsTxtLookup> { PlatformDnsTxtLookup(executor = Executors.newCachedThreadPool()) }
    single {
        BimiLogoLoader(
            generalSettingsManager = get(),
            dnsTxtLookup = get(),
            httpClient = get(named("gravatarHttpClient")),
            cache = get(),
            // Built here rather than declared, because its trust anchors are a Set and a bare generic
            // collection is not a type the graph can name - any other Set definition would collide with it.
            // The dependency-tree test is told this parameter is supplied by hand.
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
