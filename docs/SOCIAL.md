# LOOPA social platform (0.7.0)

Migration: `supabase/migrations/20261116000001_social.sql`. It is **not applied to the hosted
project**. It is tested locally (plain PostgreSQL test suite + local Supabase stack). Until it is
applied, the app shows honest "not active on the server yet" states and announces no social
features in What's New.

## Tables

| Table | Purpose | Key relations / constraints |
|---|---|---|
| `friend_requests` | requests and their outcome (pending / accepted / declined / cancelled) | `from_user`, `to_user` → `profiles` (cascade). Unique *pending* request per pair, in either direction. |
| `friendships` | one row per pair of friends | `user_a < user_b` (one row per pair), both → `profiles` (cascade) |
| `user_blocks` | who blocked whom | PK (`blocker_id`, `blocked_id`) |
| `user_presence` | last seen, foreground flag, privacy switch (`share_presence`) | PK `user_id` |
| `room_invites` | invites to a room, 15 minutes | `room_id` → `rooms` (cascade). Unique pending invite per (room, invitee). |
| `direct_messages` | 1:1 messages (1–1000 chars), `read_at` for receipts | unique (`sender_id`, `client_id`) makes retries idempotent. Pair index for history paging. |
| `notifications` | in-app notifications: `friend_request`, `friend_accepted`, `room_invite`, `message` | messages from one friend collapse into one unread notification (partial unique index) |
| `social_signals` | one row per user, bumped on every change for that user | in the `supabase_realtime` publication |

No existing table or function is changed.

## Security model

- **RLS on every table, read-only, own rows only.** Requests and invites are visible to the sender
  and recipient. Messages are visible to the sender and recipient. Presence, blocks, notifications
  and signals are visible only to their owner.
- **No INSERT/UPDATE/DELETE grants.** Every write is a `SECURITY DEFINER` RPC that reads the
  caller from `auth.uid()`, never from a parameter. Internal helpers (`_…`) cannot be executed by
  clients.
- **Relationship checks in the database.**
  - Messages and invites are allowed only between friends who have not blocked each other.
  - Only the recipient can answer a request or an invite. Only the sender can cancel a request.
  - Only a member of the room can invite.
- **Atomic pair operations.** A transaction-level advisory lock on the user pair serialises
  simultaneous mutual requests, accept vs. cancel, and block vs. accept.
  `social_concurrency.sh` sends 40 mutual pairs at once: the result is 40 friendships, 0 left
  pending and 0 deadlocks.
- **Blocking is invisible.** Someone who blocked you is missing from your search and their profile
  answers "user not found". Blocking removes the friendship, pending requests and invites, and the
  notifications between the two users.
- **Privacy.** Online / last seen is shown only between friends, and only when *both* share it.
- **Rate limits.**
  - At most 50 pending outgoing requests.
  - 24 hours before asking again after a "no".
  - 40 messages per minute.
  - 30 invites per 10 minutes.
  - Search: at least 2 characters, at most 20 results, `%` and `_` treated as literal characters.

## RPCs

| Area | Functions |
|---|---|
| Search / profile | `search_users`, `get_user_profile` |
| Friend requests | `send_friend_request`, `respond_friend_request`, `cancel_friend_request` |
| Friends and blocks | `remove_friend`, `block_user`, `unblock_user` |
| Presence and privacy | `touch_presence`, `set_presence_sharing`, `get_social_settings` |
| Overview | `get_social_overview`, `get_social_badges` |
| Invites | `send_room_invite`, `respond_room_invite` (joins through the existing `join_room`, so capacity, kicks and spectator rules are unchanged) |
| Messages | `send_message`, `get_messages`, `mark_conversation_read`, `list_conversations` |
| Notifications | `list_notifications`, `mark_notifications_read` |

Invite outcomes:
- The room is closed or the invite has expired: the invite is closed and the app explains why.
- The room is full or you were kicked: the invite stays pending and the existing error text is
  shown.
- The game has already started: you join as a spectator, which is the existing `join_room` rule.

## Live updates

`SocialSignalListener` subscribes to the user's own `social_signals` row. A signal carries no data:
the app re-fetches the badges and the open lists through the RPCs. Bursts of signals collapse into
one refresh. Chat screens listen to `SocialRepository.changes`. Presence is refreshed every
45 seconds while the app is in the foreground and set inactive when it goes to the background.

## Not in 0.7.0

- Push notifications. There is no FCM in the project yet, so notifications appear only while the
  app is open. Settings says so.
- Groups and group chat.
- Reporting users and moderation of message text. Blocking exists.
- Deleting or editing messages.
- Media in messages.
