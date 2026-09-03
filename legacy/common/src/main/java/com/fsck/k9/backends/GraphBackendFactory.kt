package com.fsck.k9.backends

import android.app.AlarmManager
import android.content.Context
import com.fsck.k9.backend.api.Backend
import java.util.concurrent.TimeUnit
import net.thunderbird.backend.api.BackendFactory
import net.thunderbird.backend.api.BackendStorageFactory
import com.fsck.k9.mail.power.PowerManager
import net.thunderbird.backend.graph.GraphPushSupport
import net.thunderbird.backend.graph.createGraphBackend
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.logging.Logger
import net.thunderbird.feature.account.AccountId
import okhttp3.OkHttpClient

/**
 * Timeouts for Graph requests.
 *
 * Graph occasionally takes several seconds to answer a large mailbox query, so the read timeout is more generous than
 * the connect timeout.
 */
private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val READ_TIMEOUT_SECONDS = 60L

interface GraphBackendFactory : BackendFactory

/**
 * Creates the Microsoft Graph backend for an account.
 *
 * Graph accounts authenticate exclusively with OAuth 2.0, so the backend is always given a token provider backed by
 * the authorization state stored with the account.
 */
class DefaultGraphBackendFactory(
    private val accountManager: LegacyAccountManager,
    private val backendStorageFactory: BackendStorageFactory,
    private val context: Context,
    private val logger: Logger,
    private val powerManager: PowerManager,
    private val alarmManager: AlarmManager,
) : GraphBackendFactory {

    /**
     * Shared across accounts so connections and thread pools are pooled rather than duplicated per account.
     */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    override fun createBackend(accountId: AccountId): Backend {
        val account = accountManager.getAccount(accountId.toString()) ?: error("Account not found: $accountId")
        val backendStorage = backendStorageFactory.createBackendStorage(accountId)
        val authStateStorage = AccountAuthStateStorage(accountManager, accountId)
        val tokenProvider = RealOAuth2TokenProvider(context, authStateStorage)

        return createGraphBackend(
            backendStorage = backendStorage,
            okHttpClient = okHttpClient,
            tokenProvider = tokenProvider,
            logger = logger,
            pushSupport = GraphPushSupport(
                powerManager = powerManager,
                scheduler = GraphPushAlarmScheduler(
                    context = context,
                    alarmManager = alarmManager,
                    schedulerId = accountId.toString(),
                    logger = logger,
                ),
                accountName = account.uuid,
            ),
        )
    }
}
