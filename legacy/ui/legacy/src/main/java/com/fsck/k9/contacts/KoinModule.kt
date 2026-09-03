package com.fsck.k9.contacts

import java.util.concurrent.TimeUnit
import org.koin.core.qualifier.named
import okhttp3.OkHttpClient
import org.koin.dsl.module

/**
 * Short by design: an avatar is decoration on a list row, so a slow lookup should give up and let the letter
 * avatar be drawn rather than hold the row.
 */
private const val GRAVATAR_TIMEOUT_SECONDS = 10L

val contactsModule = module {
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
            logger = get(),
        )
    }
    factory { ContactImageBitmapDecoderFactory(contactPhotoLoader = get(), gravatarLoader = get()) }
}
