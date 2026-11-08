package com.loopa.core.data

import com.loopa.core.model.CosmeticItem
import com.loopa.core.model.MatchResults
import com.loopa.core.model.ProfileOverview
import com.loopa.core.model.PublicRoom
import com.loopa.core.model.RoomRef
import com.loopa.core.network.LoopaBackend
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String) : AuthState
}

/** Server view of the signed-in account (from `get_account_status`). */
@kotlinx.serialization.Serializable
data class AccountStatus(
    @kotlinx.serialization.SerialName("user_id") val userId: String,
    @kotlinx.serialization.SerialName("is_anonymous") val isAnonymous: Boolean,
    val email: String? = null,
    val providers: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("has_profile") val hasProfile: Boolean = false,
) {
    val hasGoogle: Boolean get() = "google" in providers
}

/** Result of an OAuth round trip (Google sign-in or guest → Google linking). */
sealed interface AuthCallbackResult {
    /** Guest account now carries the Google identity. Same user id: nothing was moved or lost. */
    data class Linked(val userId: String) : AuthCallbackResult
    /** Signed in with Google. [switchedFrom] is the previous (guest) user id when the account changed. */
    data class SignedIn(val userId: String, val switchedFrom: String?) : AuthCallbackResult
    data class Failed(val error: com.loopa.core.game.LoopaError, val detail: String?) : AuthCallbackResult
}

/**
 * Accounts:
 *  - Guest: Supabase anonymous sign-in. The session persists on the device.
 *  - Google: OAuth (PKCE) through the browser, back via loopa://auth-callback.
 *  - Guest → Google: Supabase *manual identity linking* (`linkIdentity`) on the
 *    current session, so the user id — and with it XP, level, avatar, cosmetics,
 *    statistics and match history — is kept as is. If the Google identity already
 *    belongs to another account, the server refuses (`identity_already_exists`);
 *    the guest account is left untouched and nothing is merged.
 */
class AuthRepository(private val backend: LoopaBackend) {
    val state: Flow<AuthState> = backend.client?.auth?.sessionStatus?.map { s ->
        when (s) {
            is SessionStatus.Authenticated -> AuthState.SignedIn(s.session.user?.id ?: backend.currentUserId.orEmpty())
            is SessionStatus.NotAuthenticated -> AuthState.SignedOut
            is SessionStatus.RefreshFailure -> backend.currentUserId?.let { AuthState.SignedIn(it) } ?: AuthState.SignedOut
            else -> AuthState.Loading
        }
    } ?: flowOf(AuthState.SignedOut)

    val userId: String? get() = backend.currentUserId

    private enum class Pending { SIGN_IN, LINK }
    @Volatile private var pending: Pair<Pending, String?>? = null
    private val _callbacks = MutableStateFlow<AuthCallbackResult?>(null)
    /** Last OAuth result, until a screen consumes it with [consumeCallback]. */
    val callbacks: StateFlow<AuthCallbackResult?> = _callbacks.asStateFlow()
    fun consumeCallback() { _callbacks.value = null }

    suspend fun signInAnonymously() = LoopaBackend.translate {
        val auth = backend.requireClient().auth
        auth.awaitInitialization()
        if (auth.currentUserOrNull() == null) auth.signInAnonymously()
    }

    /** Opens Google sign-in. The result arrives through [handleCallback]. */
    suspend fun signInWithGoogle() = LoopaBackend.translate {
        val auth = backend.requireClient().auth
        auth.awaitInitialization()
        pending = Pending.SIGN_IN to auth.currentUserOrNull()?.id
        auth.signInWith(backend.oauthProvider, redirectUrl = LoopaBackend.OAUTH_REDIRECT)
    }

    /** Links Google to the current (guest) account. The result arrives through [handleCallback]. */
    suspend fun linkGoogle() = LoopaBackend.translate {
        val auth = backend.requireClient().auth
        auth.awaitInitialization()
        val current = auth.currentUserOrNull() ?: throw com.loopa.core.game.LoopaException(com.loopa.core.game.LoopaError.NOT_SIGNED_IN)
        pending = Pending.LINK to current.id
        auth.linkIdentity(backend.oauthProvider, redirectUrl = LoopaBackend.OAUTH_REDIRECT)
    }

    /** Whether the server has the OAuth provider (Google) enabled — read from the public /auth/v1/settings. */
    suspend fun isGoogleAvailable(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val conn = java.net.URL(backend.config.url.trimEnd('/') + "/auth/v1/settings").openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("apikey", backend.config.anonKey)
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            val body = conn.inputStream.use { it.readBytes().decodeToString() }
            val external = com.loopa.core.network.LoopaJson.parseToJsonElement(body).let { (it as JsonObject)["external"] as? JsonObject }
            (external?.get(backend.oauthProvider.name) as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        }.getOrDefault(false)
    }

    /** True when [url] is our OAuth redirect. */
    fun isAuthCallback(url: String?): Boolean = url?.startsWith(LoopaBackend.OAUTH_REDIRECT) == true

    /**
     * Completes an OAuth round trip from the deep link. Errors from the server
     * (e.g. identity already linked elsewhere) are reported without touching the
     * current session.
     */
    suspend fun handleCallback(url: String): AuthCallbackResult {
        val params = OAuthCallback.parse(url)
        val (kind, before) = pending ?: (null to backend.currentUserId)
        val result: AuthCallbackResult = when {
            params["error"] != null || params["error_code"] != null -> AuthCallbackResult.Failed(
                com.loopa.core.game.LoopaError.fromOAuthError(params["error_code"] ?: params["error"], params["error_description"]),
                params["error_description"],
            )
            params["code"] != null -> try {
                LoopaBackend.translate {
                    val auth = backend.requireClient().auth
                    auth.exchangeCodeForSession(params["code"]!!)
                }
                val now = backend.currentUserId.orEmpty()
                if (kind == Pending.LINK && now == before) AuthCallbackResult.Linked(now)
                else AuthCallbackResult.SignedIn(now, switchedFrom = before?.takeIf { it != now })
            } catch (e: com.loopa.core.game.LoopaException) {
                AuthCallbackResult.Failed(if (e.error == com.loopa.core.game.LoopaError.UNKNOWN) com.loopa.core.game.LoopaError.OAUTH_FAILED else e.error, e.cause?.message)
            }
            else -> AuthCallbackResult.Failed(com.loopa.core.game.LoopaError.OAUTH_FAILED, "missing code")
        }
        pending = null
        _callbacks.value = result
        return result
    }

    suspend fun accountStatus(): AccountStatus? {
        val element = backend.rpc("get_account_status")
        return if (element is JsonObject) com.loopa.core.network.LoopaJson.decodeFromJsonElement(AccountStatus.serializer(), element) else null
    }

    /** Display name offered by Google (used to prefill the username). */
    fun providerDisplayName(): String? {
        val meta = backend.client?.auth?.currentUserOrNull()?.userMetadata ?: return null
        return listOf("full_name", "name", "given_name").firstNotNullOfOrNull { k ->
            (meta[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }

    /** Signs out on this device only (other devices keep their sessions). */
    suspend fun signOut() = LoopaBackend.translate {
        backend.requireClient().auth.signOut(io.github.jan.supabase.auth.SignOutScope.LOCAL)
    }
}

/** Parses query and fragment parameters of an OAuth redirect (PKCE uses the query, errors may use either). */
object OAuthCallback {
    fun parse(url: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        val afterScheme = url.substringAfter("://", url)
        val query = afterScheme.substringAfter('?', "").substringBefore('#')
        val fragment = afterScheme.substringAfter('#', "")
        for (part in (query.split('&') + fragment.split('&'))) {
            if (part.isBlank() || '=' !in part) continue
            val (k, v) = part.split('=', limit = 2)
            out.putIfAbsent(java.net.URLDecoder.decode(k, "UTF-8"), java.net.URLDecoder.decode(v, "UTF-8"))
        }
        return out
    }
}

class ProfileRepository(private val backend: LoopaBackend) {
    private val _profile = MutableStateFlow<ProfileOverview?>(null)
    val profile: StateFlow<ProfileOverview?> = _profile.asStateFlow()
    private var catalogCache: List<CosmeticItem>? = null

    /** Returns null when the signed-in user has not created a profile yet. */
    suspend fun refresh(): ProfileOverview? {
        val element = backend.rpc("get_profile_overview")
        val p = if (element is JsonObject) com.loopa.core.network.LoopaJson.decodeFromJsonElement(ProfileOverview.serializer(), element) else null
        _profile.value = p
        return p
    }

    suspend fun ensureProfile(username: String, avatar: JsonObject?): ProfileOverview {
        val p = backend.rpc("ensure_profile", ProfileOverview.serializer()) {
            put("p_username", username)
            if (avatar != null) put("p_avatar", avatar)
        }
        _profile.value = p
        return p
    }

    suspend fun updateUsername(username: String) {
        backend.rpc("update_username") { put("p_username", username) }
        refresh()
    }

    suspend fun updateAvatar(avatar: JsonObject) {
        backend.rpc("update_avatar") { put("p_avatar", avatar) }
        refresh()
    }

    suspend fun unlockCosmetic(itemId: String) {
        backend.rpc("unlock_cosmetic") { put("p_item_id", itemId) }
        refresh()
    }

    suspend fun catalog(force: Boolean = false): List<CosmeticItem> {
        catalogCache?.takeIf { !force }?.let { return it }
        val list = LoopaBackend.translate {
            backend.requireClient().postgrest.from("cosmetic_items").select().decodeList<CosmeticItem>()
        }.sortedWith(compareBy({ it.slot }, { it.sortOrder }))
        catalogCache = list
        return list
    }

    fun clear() { _profile.value = null }
}

class RoomRepository(private val backend: LoopaBackend) {
    suspend fun createRoom(isPublic: Boolean): RoomRef =
        backend.rpc("create_room", RoomRef.serializer()) { put("p_is_public", isPublic) }

    suspend fun joinRoom(code: String, asSpectator: Boolean = false): RoomRef =
        backend.rpc("join_room", RoomRef.serializer()) {
            put("p_code", code.trim().uppercase())
            put("p_as_spectator", asSpectator)
        }

    suspend fun publicRooms(): List<PublicRoom> =
        backend.rpc("list_public_rooms", ListSerializer(PublicRoom.serializer()))

    suspend fun activeRoom(): RoomRef? {
        val element = backend.rpc("get_my_active_room")
        return if (element is JsonObject) com.loopa.core.network.LoopaJson.decodeFromJsonElement(RoomRef.serializer(), element) else null
    }

    suspend fun matchResults(matchId: String): MatchResults =
        backend.rpc("get_match_results", MatchResults.serializer()) { put("p_match_id", matchId) }
}
