package com.loopa.core.data

import com.loopa.core.game.LoopaError
import com.loopa.core.game.LoopaException
import com.loopa.core.model.GameCatalog
import com.loopa.core.model.Membership
import com.loopa.core.model.PromoRedemption
import com.loopa.core.network.LoopaBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.put

/**
 * Server data with its loading and error state. On a failed refresh the last good [data]
 * is kept (stale but true), and [error] says why it could not be updated.
 */
data class Remote<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: LoopaError? = null,
) {
    /** The server does not have this feature yet (migrations not applied). */
    val unsupported: Boolean get() = error == LoopaError.SERVER_OUTDATED
}

private suspend fun <T> MutableStateFlow<Remote<T>>.load(lock: Mutex, fetch: suspend () -> T): Remote<T> = lock.withLock {
    update { it.copy(loading = true) }
    val next = try {
        Remote(data = fetch(), loading = false, error = null)
    } catch (e: LoopaException) {
        value.copy(loading = false, error = e.error)
    }
    value = next
    next
}

/**
 * The signed-in user's plan (Free / LOOPA Plus / Admin), read from `get_my_plan`.
 * The client has no way to grant itself a plan: this repository only reads, and promo
 * codes are validated and applied by the server (`redeem_promo_code`).
 */
class MembershipRepository(private val backend: LoopaBackend) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(Remote<Membership>())
    val state: StateFlow<Remote<Membership>> = _state.asStateFlow()

    suspend fun refresh(): Remote<Membership> =
        _state.load(lock) { backend.rpc("get_my_plan", Membership.serializer()) }

    /**
     * Redeems a promo code. Throws [LoopaException] with the server's reason
     * (PROMO_INVALID, GOOGLE_LINK_REQUIRED, PROMO_EXHAUSTED, …). The plan is re-read afterwards
     * so every screen shows the server's new end date.
     */
    suspend fun redeem(code: String): PromoRedemption {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) throw LoopaException(LoopaError.PROMO_INVALID)
        val result = backend.rpc("redeem_promo_code", PromoRedemption.serializer()) { put("p_code", trimmed) }
        refresh()
        return result
    }

    fun clear() { _state.value = Remote() }
}

/** The game catalogue with this week's rotation and what the caller may host (`get_game_catalog`). */
class GameCatalogRepository(private val backend: LoopaBackend) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(Remote<GameCatalog>())
    val state: StateFlow<Remote<GameCatalog>> = _state.asStateFlow()

    suspend fun refresh(): Remote<GameCatalog> =
        _state.load(lock) { backend.rpc("get_game_catalog", GameCatalog.serializer()) }

    fun clear() { _state.value = Remote() }
}
