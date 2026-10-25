package net.thunderbird.feature.blocklist.internal

import android.content.Context
import java.io.File
import okhttp3.OkHttpClient
import org.koin.dsl.module

private const val BLOCKLIST_DIRECTORY = "domain_blocklists"

/**
 * Binds where the lists are kept and how they are fetched on Android.
 */
val androidBlocklistModule = module {
    single<BlocklistStore> { FileBlocklistStore(File(get<Context>().filesDir, BLOCKLIST_DIRECTORY)) }
    single<BlocklistFetcher> { OkHttpBlocklistFetcher(OkHttpClient()) }
}
