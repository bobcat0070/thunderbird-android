package net.thunderbird.app.common.feature.mailboxrules

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.core.common.mail.Protocols
import net.thunderbird.core.common.oauth.OAuthConfigurationProvider
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mailboxrules.MailboxRule
import net.thunderbird.feature.mailboxrules.MailboxRulesSource
import net.thunderbird.feature.mailboxrules.RulesAccount
import net.thunderbird.feature.mailboxrules.RulesResult
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The one permission the rules sign-in asks for. Read-only: the rules can be looked at, not changed.
 */
private const val MAILBOX_SETTINGS_SCOPE = "https://graph.microsoft.com/MailboxSettings.Read"

private const val INBOX_RULES_URL = "https://graph.microsoft.com/v1.0/me/mailFolders/inbox/messageRules"

private const val CALL_TIMEOUT_SECONDS = 30L
private const val HTTP_FORBIDDEN = 403
private const val HTTP_UNAUTHORIZED = 401

/**
 * Reads the inbox rules of the app's Microsoft 365 accounts with a sign-in of their own.
 *
 * The sign-in is the account's usual Microsoft one, asking for [MAILBOX_SETTINGS_SCOPE] alone. The token it returns
 * is used for one request and dropped; the account's own sign-in and its tokens are not touched.
 */
internal class LegacyMailboxRulesSource(
    private val context: Context,
    private val accountManager: LegacyAccountManager,
    private val oAuthConfigurationProvider: OAuthConfigurationProvider,
) : MailboxRulesSource {
    private var authService: AuthorizationService? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    @Synchronized
    private fun authService(): AuthorizationService =
        authService ?: AuthorizationService(context).also { authService = it }

    override fun accounts(): List<RulesAccount> = accountManager.findAll()
        .filter { it.incomingServerSettings.type == Protocols.GRAPH }
        .map { RulesAccount(id = it.id.toString(), email = it.email) }

    @Suppress("ReturnCount")
    override fun signInIntent(accountId: String): Intent? {
        val account = accountManager.findById(AccountIdFactory.of(accountId)) ?: return null
        val configuration = oAuthConfigurationProvider.getConfiguration(account.incomingServerSettings.host)
            ?: return null

        val request = AuthorizationRequest.Builder(
            AuthorizationServiceConfiguration(
                configuration.authorizationEndpoint.toUri(),
                configuration.tokenEndpoint.toUri(),
            ),
            configuration.clientId,
            ResponseTypeValues.CODE,
            configuration.redirectUri.toUri(),
        )
            .setScope(MAILBOX_SETTINGS_SCOPE)
            .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
            .setLoginHint(account.email)
            .build()

        return authService().getAuthorizationRequestIntent(request)
    }

    @Suppress("ReturnCount")
    override suspend fun readRules(accountId: String, signInResult: Intent?): RulesResult {
        if (signInResult == null) return RulesResult.Canceled

        AuthorizationException.fromIntent(signInResult)?.let { exception ->
            // Closing the page, or answering Cancel on Microsoft's consent page, is the reader changing their mind.
            val isCanceled = exception == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW ||
                exception == AuthorizationException.AuthorizationRequestErrors.ACCESS_DENIED
            return if (isCanceled) {
                RulesResult.Canceled
            } else {
                RulesResult.Refused(exception.errorDescription ?: exception.error)
            }
        }
        val response = AuthorizationResponse.fromIntent(signInResult) ?: return RulesResult.Canceled

        val accessToken = exchangeCode(response) ?: return RulesResult.Refused(null)
        return withContext(Dispatchers.IO) { requestRules(accessToken) }
    }

    private suspend fun exchangeCode(response: AuthorizationResponse): String? =
        suspendCancellableCoroutine { continuation ->
            authService().performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, _ ->
                if (continuation.isActive) continuation.resume(tokenResponse?.accessToken)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun requestRules(accessToken: String): RulesResult {
        val request = Request.Builder()
            .url(INBOX_RULES_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> RulesResult.Success(parseMailboxRules(response.body.string()))

                    response.code == HTTP_FORBIDDEN || response.code == HTTP_UNAUTHORIZED ->
                        RulesResult.Refused("HTTP ${response.code}")

                    else -> RulesResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            RulesResult.Failed(e.message)
        } catch (e: RuntimeException) {
            // An answer that is not the JSON Graph promises.
            RulesResult.Failed(e.message)
        }
    }

    @Synchronized
    override fun close() {
        authService?.dispose()
        authService = null
    }
}

/**
 * Reads Graph's list of inbox rules, keeping what says whether each sends mail away or hides it.
 */
internal fun parseMailboxRules(json: String): List<MailboxRule> {
    val rules = Json.parseToJsonElement(json).jsonObject["value"] as? JsonArray ?: return emptyList()

    return rules.mapNotNull { element ->
        val rule = element as? JsonObject ?: return@mapNotNull null
        val actions = rule["actions"] as? JsonObject ?: JsonObject(emptyMap())

        MailboxRule(
            name = rule["displayName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            isEnabled = rule["isEnabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            forwardsTo = listOf("forwardTo", "forwardAsAttachmentTo", "redirectTo")
                .flatMap { key -> recipients(actions[key] as? JsonArray) }
                .distinct(),
            deletes = actions.isTrue("delete") || actions.isTrue("permanentDelete"),
            movesAndMarksRead = actions["moveToFolder"]?.jsonPrimitive?.contentOrNull != null &&
                actions.isTrue("markAsRead"),
        )
    }
}

private fun recipients(array: JsonArray?): List<String> = array.orEmpty().mapNotNull { recipient ->
    (recipient as? JsonObject)?.get("emailAddress")?.jsonObject?.get("address")?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.isTrue(key: String): Boolean = (this[key]?.jsonPrimitive?.booleanOrNull) == true

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
