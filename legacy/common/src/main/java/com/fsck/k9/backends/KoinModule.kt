package com.fsck.k9.backends

import com.fsck.k9.backend.BackendManager
import com.fsck.k9.backend.imap.BackendIdleRefreshManager
import com.fsck.k9.backend.imap.SystemAlarmManager
import com.fsck.k9.mail.oauth.OAuth2TokenProviderFactory
import com.fsck.k9.mail.store.imap.IdleRefreshManager
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.core.common.mail.Protocols
import org.koin.core.qualifier.named
import org.koin.dsl.module

val backendsModule = module {
    single {
        val developmentBackends = get<Map<String, BackendFactory>>(named("developmentBackends"))
        BackendManager(
            backendFactories = mapOf(
                "imap" to get<ImapBackendFactory>(),
                "pop3" to get<Pop3BackendFactory>(),
                Protocols.GRAPH to get<GraphBackendFactory>(),
            ) + developmentBackends,
            accountManager = get(),
        )
    }
    single<ImapBackendFactory> {
        DefaultImapBackendFactory(
            accountManager = get(),
            powerManager = get(),
            idleRefreshManager = get(),
            backendStorageFactory = get(),
            trustedSocketFactory = get(),
            context = get(),
            clientInfoAppName = get(named("ClientInfoAppName")),
            clientInfoAppVersion = get(named("ClientInfoAppVersion")),
        )
    }
    single<SystemAlarmManager> { AndroidAlarmManager(context = get(), alarmManager = get()) }
    single<IdleRefreshManager> { BackendIdleRefreshManager(alarmManager = get()) }
    single<GraphBackendFactory> {
        DefaultGraphBackendFactory(
            accountManager = get(),
            backendStorageFactory = get(),
            context = get(),
            logger = get(),
            powerManager = get(),
            alarmManager = get(),
        )
    }
    single<Pop3BackendFactory> {
        DefaultPop3BackendFactory(
            accountManager = get(),
            backendStorageFactory = get(),
            trustedSocketFactory = get(),
        )
    }
    single<OAuth2TokenProviderFactory> { RealOAuth2TokenProviderFactory(context = get()) }
}
