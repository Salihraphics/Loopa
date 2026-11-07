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
import kotlinx.serialization.json.put

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String) : AuthState
}

/**
 * Anonymous Supabase auth for the MVP. The session (and therefore the
 * player's profile, XP and cosmetics) persists across app restarts. The
 * user id is stable, so a later email/OAuth identity can be linked to the
 * same account (`auth.linkIdentity` / `updateUser`) without data migration.
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

    suspend fun signInAnonymously() = LoopaBackend.translate {
        val auth = backend.requireClient().auth
        auth.awaitInitialization()
        if (auth.currentUserOrNull() == null) auth.signInAnonymously()
    }

    suspend fun signOut() = LoopaBackend.translate { backend.requireClient().auth.signOut() }
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
