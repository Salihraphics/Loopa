# LOOPA architecture

## Principles

1. **The database is the game server.** All competitive logic lives in Postgres
   functions (`supabase/migrations/20261107000002_game_functions.sql`). The client
   renders snapshots and sends intents.
2. **Clients never write tables.** RLS gives `authenticated` users `SELECT` only,
   filtered by room membership. `INSERT/UPDATE/DELETE` are revoked; internal helpers
   (prefixed `_`) are not executable by clients.
3. **One snapshot, two signals.** Realtime carries no game data, only "something changed
   in room X": Postgres CDC on `room_signals` (server side) plus a Broadcast "poke" that the
   acting client sends (covers CDC delivery gaps). The client then fetches `get_room_state(room)`, which applies visibility
   rules (e.g. opponents' answers are hidden while a round is being answered).
4. **Game-agnostic platform.** Accounts, profiles, XP, cosmetics, rooms, room players,
   signals and sessions are independent of İsim Şehir. A new party game adds its own
   round tables and RPCs, a `feature-<game>` module, and reuses everything else.

## Module graph

```
app ──► feature-* ──► core-data ──► core-network ──► core
              │             └─────────────────────────► core
              └──► core-ui ─────────────────────────► core
```
Features never depend on each other; `app` wires navigation.

## Room lifecycle

`rooms.status`: `lobby → in_game → lobby … → closed`

- `create_room` generates a code from a 32-symbol alphabet (no 0/O/1/I) with
  `pgcrypto` randomness, retrying on the partial unique index of open rooms.
- A player is in at most one active room (`_leave_all_rooms`).
- `join_room`: kicked players are refused; when a game is running, previous
  participants rejoin as players, everyone else as spectators (if allowed).
- Heartbeat every 10 s (`room_heartbeat`). A player is "online" if seen within 25 s.
  If the host is gone or silent for 30 s, `_ensure_host` transfers host to the
  longest-present connected player; a room with no active members closes.

## Round state machine

`rounds.phase`: `answering → (voting) → reveal → results → finished`

| Phase | Ends when | Then |
|---|---|---|
| answering | every connected, unsubmitted participant's `deadline_at + 1.5 s` has passed, or all submitted | answers validated; `voting` if any are uncertain, else scored |
| voting | `voting_deadline` or all eligible votes cast | votes resolved, round scored |
| reveal | `reveal_deadline` (duration scales with categories × players), or host skips | `results` (8 s) |
| results | `results_deadline` or host skips | next round, or match finish |

`started_at` is 4 s in the future so every device shows the same letter reveal.

### Individual timers
`round_players.deadline_at` per player. *Ek Süre* +10 s to self; *Zaman Hırsızı* moves
min(5, remaining) s from the target to self; *Yavaşlatma* −10 s to the target (never below
now, not when the target has ≤ 5 s left, once per target per round). Clients only display
`deadline_at − ServerClock.now()`, so they cannot disagree about anyone's remaining time.

### Answers
- Drafts autosave (`save_answers`) so typed answers count if time runs out.
- `submit_answers` locks answers. The round row is locked `FOR UPDATE`, so with
  simultaneous submissions exactly one player becomes `first_submitter_id` (+5, only if
  all categories are filled and submitted before their deadline).
- Submissions are accepted up to 1.5 s after the deadline (network grace), never later.

### Validation & voting
`_validate_answer` is deterministic: empty → `empty`; wrong first letter, < 2 chars,
invalid characters or one repeated character → `invalid`; found in `word_dictionary`
→ `valid`; otherwise → `uncertain` (custom categories are always voted on).

Voting: eligible voters are active participants except the author. One immutable vote
per voter (`PRIMARY KEY (answer_id, voter_id)`). Majority of cast votes accepts; a tie,
including 0–0 at the deadline, rejects. **Fallback:** if an answer has no eligible voters
at all (everyone else left), it is accepted.

### Scoring
Accepted unique answer 20, accepted duplicate (same normalised text, same category) 10,
otherwise 0. 2X doubles one category's points (not the early bonus). The round winner(s)
(highest round total > 0; ties all win) get +30 XP and one weighted-random joker if they
hold fewer than 3.

### Jokers
Weighted roll on the server: Ek Süre 40 %, Zaman Hırsızı 30 %, Yavaşlatma 20 %, 2X Puan 10 %.
One per player at match start, max 3 in inventory, max 1 use per round
(`UNIQUE (round_id, user_id)` on `joker_usage`). The match winner receives no joker.

## Rewards and idempotency
`xp_transactions` has `UNIQUE (user_id, source_key)` (`round_win:<round>`,
`match_complete:<match>`, `match_rank:<match>`). `_award_xp` inserts first and only
updates the profile when the insert happened, so retries can't farm XP.
`matches.rewards_processed` guards the end-of-match processing.
Levels: going from level L to L+1 costs `100 + 50·(L−1)` XP (`xp_for_level`, mirrored in
`LevelCurve`). Levels only unlock cosmetics.

## Client (`RoomSession`)
- `refreshLoop`: coalesces signals (80 ms) and fetches snapshots strictly in order.
- `heartbeatLoop`: 10 s.
- `safetyPollLoop`: 4 s in game, 8 s in lobby.
- `tickLoop`: waits until `ServerClock.now()` passes the next server deadline (+150–700 ms
  jitter) and calls `game_tick`, retrying while the server says it's not time yet.
- After every action the snapshot is refreshed.
- App restart: `get_my_active_room` brings the player back into a running match; drafts
  are re-seeded from the server.

## Security notes
- Only the anon key ships in the APK (`BuildConfig` from `local.properties`).
- Spectators are not in `round_players`, so they can't answer, use jokers or vote, and
  get no rewards.
- Host-only RPCs check `rooms.host_id = auth.uid()` under a row lock.
- `word_dictionary` is not readable by clients.
