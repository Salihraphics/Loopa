package com.loopa.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Public card of a player: what anyone signed in may see (same fields as room player lists). */
@Serializable
data class UserCard(
    @SerialName("user_id") val userId: String,
    val username: String,
    val avatar: JsonObject? = null,
    val level: Int = 1,
)

/**
 * Online / last seen of a friend. [visible] is false when the friend (or you) chose not to share it;
 * the server then sends neither value.
 */
@Serializable
data class Presence(
    val visible: Boolean = false,
    val online: Boolean = false,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class Friend(
    @SerialName("user_id") val userId: String,
    val username: String,
    val avatar: JsonObject? = null,
    val level: Int = 1,
    val presence: Presence = Presence(),
    val since: String? = null,
) {
    val card: UserCard get() = UserCard(userId, username, avatar, level)
}

@Serializable
data class FriendRequestItem(
    @SerialName("request_id") val requestId: String,
    @SerialName("created_at") val createdAt: String,
    val user: UserCard,
)

@Serializable
data class RoomInviteItem(
    @SerialName("invite_id") val inviteId: String,
    @SerialName("room_id") val roomId: String,
    @SerialName("room_code") val roomCode: String,
    @SerialName("game_mode") val gameMode: String = "isim_sehir",
    @SerialName("room_status") val roomStatus: String = "lobby",
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    val from: UserCard,
)

@Serializable
data class SocialOverview(
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequestItem> = emptyList(),
    val outgoing: List<FriendRequestItem> = emptyList(),
    val invites: List<RoomInviteItem> = emptyList(),
    val blocked: List<UserCard> = emptyList(),
    @SerialName("share_presence") val sharePresence: Boolean = true,
) {
    val onlineFriends: List<Friend> get() = friends.filter { it.presence.online }
}

@Serializable
data class SocialBadges(
    val notifications: Int = 0,
    val messages: Int = 0,
    val requests: Int = 0,
    val invites: Int = 0,
) {
    /** Number shown on the Sosyal tab: things that wait for an answer. */
    val socialTab: Int get() = messages + requests + invites
}

/** Relation of the viewer to another player, as computed by the server. */
enum class Relation { SELF, NONE, FRIEND, OUTGOING, INCOMING, BLOCKED;
    companion object { fun of(s: String?) = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: NONE }
}

@Serializable
data class UserSearchResult(
    @SerialName("user_id") val userId: String,
    val username: String,
    val avatar: JsonObject? = null,
    val level: Int = 1,
    @SerialName("relation") val relationRaw: String = "none",
) {
    val relation: Relation get() = Relation.of(relationRaw)
    val card: UserCard get() = UserCard(userId, username, avatar, level)
}

@Serializable
data class UserProfileView(
    @SerialName("user_id") val userId: String,
    val username: String,
    val avatar: JsonObject? = null,
    val level: Int = 1,
    @SerialName("relation") val relationRaw: String = "none",
    val presence: Presence = Presence(),
    @SerialName("matches_played") val matchesPlayed: Int = 0,
    @SerialName("match_wins") val matchWins: Int = 0,
    @SerialName("friend_count") val friendCount: Int = 0,
) {
    val relation: Relation get() = Relation.of(relationRaw)
}

@Serializable
data class DirectMessage(
    val id: Long,
    @SerialName("sender_id") val senderId: String,
    @SerialName("recipient_id") val recipientId: String,
    val body: String,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
)

@Serializable
data class Conversation(
    @SerialName("user_id") val userId: String,
    val username: String,
    val avatar: JsonObject? = null,
    val level: Int = 1,
    @SerialName("last_message") val lastMessage: DirectMessage? = null,
    val unread: Int = 0,
    @SerialName("can_message") val canMessage: Boolean = true,
    val presence: Presence = Presence(),
)

@Serializable
data class AppNotification(
    val id: Long,
    val type: String,
    @SerialName("ref_id") val refId: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
    val actor: UserCard? = null,
    @SerialName("invite_status") val inviteStatus: String? = null,
) {
    val unread: Boolean get() = readAt == null
    val kind: NotificationKind get() = NotificationKind.of(type)
}

enum class NotificationKind(val key: String) {
    FRIEND_REQUEST("friend_request"), FRIEND_ACCEPTED("friend_accepted"), ROOM_INVITE("room_invite"), MESSAGE("message"), OTHER("");
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: OTHER }
}

/** Result of answering a room invite. */
sealed interface InviteOutcome {
    data class Joined(val roomId: String) : InviteOutcome
    data object Declined : InviteOutcome
    data object Expired : InviteOutcome
    data object RoomGone : InviteOutcome
    data object AlreadyAnswered : InviteOutcome
}
