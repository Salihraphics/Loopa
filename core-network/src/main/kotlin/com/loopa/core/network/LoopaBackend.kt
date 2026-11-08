package com.loopa.core.network

import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** Public, non-secret project configuration (injected via BuildConfig from local.properties). */
data class SupabaseConfig(val url: String, val anonKey: String) {
    val isConfigured: Boolean
        get() = (url.startsWith("https://") || url.startsWith("http://")) && anonKey.length > 20 && !url.contains("YOUR-PROJECT")

    companion object {
        fun fromBuildConfig() = SupabaseConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)
    }
}

val LoopaJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true   // the server validates complete settings objects
    explicitNulls = false
    coerceInputValues = true
}

/**
 * Thin wrapper around the Supabase client. All game mutations are RPCs
 * that the database validates; this class never computes game results.
 */
class LoopaBackend(
    val config: SupabaseConfig,
    /** Keep the auth session in memory only (used by JVM integration tests that simulate many players). */
    inMemorySession: Boolean = false,
    /** OAuth provider used for "Google ile giriş". Tests substitute a local OIDC provider. */
    val oauthProvider: io.github.jan.supabase.auth.providers.OAuthProvider = io.github.jan.supabase.auth.providers.Google,
    /** Opens the provider's consent page. Default: the SDK's browser launcher. Tests capture the URL instead. */
    urlOpener: (suspend (String) -> Unit)? = null,
) {

    val client: SupabaseClient? = if (config.isConfigured) {
        createSupabaseClient(supabaseUrl = config.url, supabaseKey = config.anonKey) {
            defaultSerializer = io.github.jan.supabase.serializer.KotlinXSerializer(LoopaJson)
            requestTimeout = 15.seconds
            install(Auth) {
                // OAuth (Google sign-in and identity linking) uses PKCE and returns to
                // loopa://auth-callback, declared as an intent filter in the app manifest.
                flowType = io.github.jan.supabase.auth.FlowType.PKCE
                scheme = OAUTH_SCHEME
                host = OAUTH_HOST
                if (urlOpener != null) useUrlOpener(urlOpener)
                if (inMemorySession) {
                    sessionManager = io.github.jan.supabase.auth.MemorySessionManager()
                    codeVerifierCache = io.github.jan.supabase.auth.MemoryCodeVerifierCache()
                    enableLifecycleCallbacks = false
                }
            }
            install(Postgrest)
            install(Realtime) {
                reconnectDelay = 3.seconds
            }
        }
    } else {
        null
    }

    val isConfigured: Boolean get() = client != null

    fun requireClient(): SupabaseClient = client ?: throw LoopaException(LoopaError.NOT_CONFIGURED)

    val currentUserId: String? get() = client?.auth?.currentUserOrNull()?.id

    /** Calls a Postgres function and returns its raw JSON result. */
    suspend fun rpc(function: String, params: JsonObjectBuilder.() -> Unit = {}): JsonElement {
        val c = requireClient()
        val body = buildJsonObject(params)
        val text = translate { c.postgrest.rpc(function, body).data }
        return if (text.isBlank()) JsonNull else LoopaJson.parseToJsonElement(text)
    }

    suspend fun <T> rpc(function: String, serializer: KSerializer<T>, params: JsonObjectBuilder.() -> Unit = {}): T {
        val element = rpc(function, params)
        return translate { LoopaJson.decodeFromJsonElement(serializer, element) }
    }

    companion object {
        /** Converts transport / database errors into [LoopaException]s with user-facing messages. */
        suspend fun <T> translate(block: suspend () -> T): T = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LoopaException) {
            throw e
        } catch (e: Exception) {
            throw LoopaException(classify(e), e)
        }

        /**
         * Server answers (database `LOOPA:` codes, auth error codes) win over transport
         * errors, so a rejected request is never reported as "no internet".
         */
        internal fun classify(e: Throwable): LoopaError {
            val chain = generateSequence(e) { it.cause }.take(8).toList()
            val text = chain.flatMap { t -> listOfNotNull(t.message, (t as? RestException)?.error, (t as? RestException)?.description) }
            text.firstNotNullOfOrNull { LoopaError.fromAuthMessage(it) }?.let { return it }
            text.firstNotNullOfOrNull { m -> LoopaError.fromMessage(m)?.takeIf { it != LoopaError.UNKNOWN } }?.let { return it }
            if (chain.any { it is RestException }) return LoopaError.UNKNOWN
            if (chain.any { it is HttpRequestException || it is IOException }) return LoopaError.NETWORK
            return LoopaError.UNKNOWN
        }

        fun emptyParams(): JsonObject = JsonObject(emptyMap())

        const val OAUTH_SCHEME = "loopa"
        const val OAUTH_HOST = "auth-callback"
        const val OAUTH_REDIRECT = "$OAUTH_SCHEME://$OAUTH_HOST"
    }
}

@OptIn(io.github.jan.supabase.annotations.SupabaseExperimental::class)
private fun io.github.jan.supabase.auth.AuthConfig.useUrlOpener(open: suspend (String) -> Unit) {
    urlLauncher = io.github.jan.supabase.auth.UrlLauncher { _, url -> open(url) }
}
