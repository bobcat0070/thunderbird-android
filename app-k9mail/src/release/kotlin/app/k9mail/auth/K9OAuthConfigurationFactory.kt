package app.k9mail.auth

import com.fsck.k9.BuildConfig
import net.thunderbird.core.common.oauth.OAuthConfiguration
import net.thunderbird.core.common.oauth.OAuthConfigurationFactory

@Suppress("ktlint:standard:max-line-length")
class K9OAuthConfigurationFactory : OAuthConfigurationFactory {
    override fun createConfigurations(): Map<List<String>, OAuthConfiguration> {
        return mapOf(
            createAolConfiguration(),
            createFastmailConfiguration(),
            createGmailConfiguration(),
            createMicrosoftConfiguration(),
            createMicrosoftGraphConfiguration(),
            createYahooConfiguration(),
            createThundermailConfiguration(),
            createThundermailStageConfiguration(),
        )
    }

    private fun createAolConfiguration(): Pair<List<String>, OAuthConfiguration> {
        return listOf(
            "imap.aol.com",
            "smtp.aol.com",
        ) to OAuthConfiguration(
            clientId = "dj0yJmk9dUNqYXZhYWxOYkdRJmQ9WVdrOU1YQnZVRFZoY1ZrbWNHbzlNQT09JnM9Y29uc3VtZXJzZWNyZXQmc3Y9MCZ4PWIw",
            scopes = listOf("mail-w"),
            authorizationEndpoint = "https://api.login.aol.com/oauth2/request_auth",
            tokenEndpoint = "https://api.login.aol.com/oauth2/get_token",
            redirectUri = "${BuildConfig.APPLICATION_ID}://oauth2redirect",
        )
    }

    private fun createFastmailConfiguration(): Pair<List<String>, OAuthConfiguration> {
        return listOf(
            "imap.fastmail.com",
            "smtp.fastmail.com",
        ) to OAuthConfiguration(
            clientId = "353641ae",
            scopes = listOf("https://www.fastmail.com/dev/protocol-imap", "https://www.fastmail.com/dev/protocol-smtp"),
            authorizationEndpoint = "https://api.fastmail.com/oauth/authorize",
            tokenEndpoint = "https://api.fastmail.com/oauth/refresh",
            redirectUri = "${BuildConfig.APPLICATION_ID}://oauth2redirect",
        )
    }

    private fun createGmailConfiguration(): Pair<List<String>, OAuthConfiguration> {
        return listOf(
            "imap.gmail.com",
            "imap.googlemail.com",
            "smtp.gmail.com",
            "smtp.googlemail.com",
        ) to OAuthConfiguration(
            clientId = "262622259280-hhmh92rhklkg2k1tjil69epo0o9a12jm.apps.googleusercontent.com",
            scopes = listOf("https://mail.google.com/"),
            authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            redirectUri = "${BuildConfig.APPLICATION_ID}:/oauth2redirect",
        )
    }

    private fun createMicrosoftConfiguration(): Pair<List<String>, OAuthConfiguration> {
        return listOf(
            "outlook.office365.com",
            "smtp.office365.com",
            "smtp-mail.outlook.com",
        ) to OAuthConfiguration(
            clientId = "e647013a-ada4-4114-b419-e43d250f99c5",
            scopes = listOf(
                "profile",
                "openid",
                "email",
                "https://outlook.office.com/IMAP.AccessAsUser.All",
                "https://outlook.office.com/SMTP.Send",
                "offline_access",
            ),
            authorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            redirectUri = "msauth://com.fsck.k9/Dx8yUsuhyU3dYYba1aA16Wxu5eM%3D",
        )
    }

    /**
     * Microsoft Graph configuration for Microsoft 365 and Outlook.com accounts.
     *
     * Microsoft 365 tenants commonly disable IMAP and SMTP AUTH, so Graph is the only mail protocol available to
     * them. Graph tokens are scoped to the Graph resource, which is why this is a separate configuration from the
     * IMAP/SMTP one rather than an extra scope on it: the Microsoft identity platform only issues a token for one
     * resource at a time.
     *
     * The app registration behind [clientId] must have the delegated Microsoft Graph permissions Mail.ReadWrite and
     * Mail.Send granted, otherwise sign-in fails with an invalid scope error.
     */
    private fun createMicrosoftGraphConfiguration(): Pair<List<String>, OAuthConfiguration> {
        return listOf(
            "graph.microsoft.com",
        ) to OAuthConfiguration(
            clientId = "e647013a-ada4-4114-b419-e43d250f99c5",
            scopes = listOf(
                "profile",
                "openid",
                "email",
                "https://graph.microsoft.com/Mail.ReadWrite",
                "https://graph.microsoft.com/Mail.Send",
                "offline_access",
            ),
            authorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
            tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            redirectUri = "msauth://com.fsck.k9/Dx8yUsuhyU3dYYba1aA16Wxu5eM%3D",
        )
    }

    private fun createYahooConfiguration(): Pair<List<String>, OAuthConfiguration> {
        return listOf(
            "imap.mail.yahoo.com",
            "smtp.mail.yahoo.com",
        ) to OAuthConfiguration(
            clientId = "dj0yJmk9aHNUb3d2MW5TQnpRJmQ9WVdrOWVYbHpaRWM0YkdnbWNHbzlNQT09JnM9Y29uc3VtZXJzZWNyZXQmc3Y9MCZ4PWIz",
            scopes = listOf("mail-w"),
            authorizationEndpoint = "https://api.login.yahoo.com/oauth2/request_auth",
            tokenEndpoint = "https://api.login.yahoo.com/oauth2/get_token",
            redirectUri = "${BuildConfig.APPLICATION_ID}://oauth2redirect",
        )
    }

    private fun createThundermailConfiguration(): Pair<List<String>, OAuthConfiguration> =
        listOf(
            "mail.tb.pro",
            "mail.thundermail.com",
        ) to OAuthConfiguration(
            clientId = "mobile-android-k9mail",
            scopes = listOf("openid", "profile", "email", "offline_access"),
            authorizationEndpoint = "https://auth.tb.pro/realms/tbpro/protocol/openid-connect/auth",
            tokenEndpoint = "https://auth.tb.pro/realms/tbpro/protocol/openid-connect/token",
            redirectUri = "${BuildConfig.APPLICATION_ID}://oauth2redirect",
        )

    private fun createThundermailStageConfiguration(): Pair<List<String>, OAuthConfiguration> =
        listOf(
            "mail.stage-thundermail.com",
        ) to OAuthConfiguration(
            clientId = "mobile-android-k9mail",
            scopes = listOf("openid", "profile", "email", "offline_access"),
            authorizationEndpoint = "https://auth-stage.tb.pro/realms/tbpro/protocol/openid-connect/auth",
            tokenEndpoint = "https://auth-stage.tb.pro/realms/tbpro/protocol/openid-connect/token",
            redirectUri = "${BuildConfig.APPLICATION_ID}://oauth2redirect",
        )
}
