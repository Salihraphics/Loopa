# LOOPA

**Play together. Think faster.**

LOOPA is a native Android multiplayer party game. The first mode is a polished
**İsim Şehir** (Name, City, Animal, Plant, Object…): friends create a room with
a 6-character code, get a random Turkish letter every round, race to fill in
categories, use jokers, vote on doubtful answers and watch a synchronized,
category-by-category answer reveal.

- Kotlin · Jetpack Compose · Material 3 with a custom LOOPA design system
- MVVM · Coroutines/Flow · Navigation Compose · Gradle Kotlin DSL (convention plugins)
- Supabase Auth (anonymous) · PostgreSQL · Realtime · Row Level Security
- **Server-authoritative**: rooms, timers, jokers, validation, votes, scores and
  XP are all decided by Postgres functions; the APK only ships the public anon key.

> Dictionary: [`docs/DICTIONARY.md`](docs/DICTIONARY.md) · Google sign-in: [`docs/GOOGLE_AUTH.md`](docs/GOOGLE_AUTH.md)
>
> Status: see [`docs/TODO.md`](docs/TODO.md) for what is done, what was tested,
> and what is still unfinished.

---

## Repository layout

```
app/                 Application, MainActivity, navigation (root, tabs, room host), launcher icon + splash
core/                Pure Kotlin: models (RoomState…), game logic (Turkish normalisation, level curve,
                     server clock, timing, error codes, achievements) + unit tests
core-network/        Supabase client (LoopaBackend), RPC wrapper + error mapping, realtime signal listener
core-data/           Repositories (auth, profile, rooms, settings), RoomSession (live room client),
                     DataContainer (DI) + live multiplayer E2E test
core-ui/             Theme/tokens, fonts, components, LOOPA logo, layered avatar renderer, sound & haptics
feature-auth         Onboarding, anonymous sign-in, profile creation, system screens
feature-home         Home dashboard
feature-lobby        Play hub (create / join / spectate), public room browser, lobby, host settings sheet
feature-game         Letter reveal, answering, jokers, waiting, voting, synchronized reveal, round results
feature-results      Podium, rankings, XP, level-up, play again / lobby / leave
feature-profile      Profile, statistics, achievements, XP history, match history
feature-avatar       Avatar creator
feature-shop         Level-unlocked cosmetic shop
feature-settings     Sound / music / vibration, theme, language, account, privacy, about, logout
build-logic/         Gradle convention plugins (loopa.android.library / compose / feature)
supabase/            config.toml, versioned SQL migrations, SQL test-suite
docs/                Architecture notes, TODO, brand reference
```

## Requirements

- JDK 17+ (built and tested with JDK 21)
- Android SDK with platform 36 and build-tools 36 (`sdk.dir` in `local.properties`)
- Gradle is provided by the wrapper (8.14.3); AGP 8.13.2, Kotlin 2.2.21
- A Supabase project (hosted) **or** the Supabase CLI + Docker for a local stack

## Backend setup

### Option A — hosted Supabase project

1. Create a project at supabase.com.
2. **Authentication → Sign In / Providers → enable "Anonymous sign-ins".**
3. Apply the migrations in order (SQL editor or CLI):
   ```bash
   supabase link --project-ref <ref>
   supabase db push            # applies supabase/migrations/*.sql
   ```
4. Copy `local.properties.example` to `local.properties` and set
   `SUPABASE_URL` and `SUPABASE_ANON_KEY` (Project Settings → API → *anon public*).
   **Never** put the `service_role` key in the app or in git.

### Option B — local stack (what the E2E test uses)

```bash
supabase start -x studio,imgproxy,storage-api,edge-runtime,logflare,vector,supavisor,postgres-meta,mailpit
# prints API_URL (http://127.0.0.1:54321) and ANON_KEY
```
`supabase/config.toml` already enables anonymous sign-ins and Realtime.
Debug builds allow plain `http://` so an emulator can use `http://10.0.2.2:54321`
(a phone on the same Wi-Fi uses your computer's LAN IP).

If the APK is built without credentials it shows a clear *"Sunucu bağlı değil"*
screen. It never pretends to be online.

## Build

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease        # minified; unsigned unless keystore.properties exists
```

Release signing: create `keystore.properties` (git-ignored) next to `settings.gradle.kts`:
```
storeFile=/absolute/path/loopa-release.jks
storePassword=…
keyAlias=loopa
keyPassword=…
```

## Tests

| Suite | Command | What it covers |
|---|---|---|
| Backend SQL (plain Postgres) | `PGHOST=… PGPORT=… PGUSER=postgres supabase/tests/run_local_tests.sh` | full 10-player room; full match with 3 players + spectator, ready/start guards, double start, kicks, settings validation, jokers (all rules), drafts & timeouts, voting (own vote, double vote, ties), duplicates, 2X, early bonus, idempotent XP, host transfer, 200 room codes, RLS |
| Dictionary | `supabase/tests/dictionary_test.sql`, `dictionary_scoring_test.sql`, `python3 tools/dictionary/test_build.py` | 600 entries × 3 spellings, aliases, wrong letter/category, Turkish cases, duplicates by entry |
| Account linking E2E | see docs/GOOGLE_AUTH.md | guest → linked identity keeps id, XP, avatar, cosmetics, history; other-device sign-in; conflict; cancel; sign-out |
| Core logic | `./gradlew :core:test` | Turkish normalisation, level curve, server clock, timestamp parsing, reveal sync, grouping, JSON decoding |
| **Live multiplayer E2E** | `LOOPA_E2E_URL=http://127.0.0.1:54321 LOOPA_E2E_ANON_KEY=<anon> ./gradlew :core-data:testDebugUnitTest` | 4 independent clients (own auth, clock, realtime socket) using the app's real `RoomSession`: realtime propagation with polling disabled, authorization, concurrent double start, jokers, simultaneous submits (exactly one bonus), disconnect mid-round + server-deadline close, reconnection, voting, synchronized reveal, results/XP, host transfer |
| Screenshots | `./gradlew :app:testDebugUnitTest` | Robolectric renders 13 real screens (light + dark) to `app/build/screenshots/` |

The E2E test is skipped automatically when the environment variables are not set.

## How multiplayer works (short version)

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for details.

- Every mutation is a `SECURITY DEFINER` RPC (`create_room`, `join_room`, `start_match`,
  `submit_answers`, `use_joker`, `cast_vote`, `game_tick`…). Tables are read-only to clients (RLS).
- Every RPC bumps `room_signals`. Clients subscribe to that one table via Realtime (plus a
  data-free Broadcast "poke" sent by the acting client) and re-fetch an authoritative
  snapshot (`get_room_state`). Duplicate, missed or out-of-order
  events cannot corrupt state; a slow safety poll (4 s in game / 8 s in lobby) heals missed signals.
- **Timers**: each player has their own server-side `deadline_at` (jokers move it). Clients
  draw countdowns from a `ServerClock` offset estimated from snapshot round-trips; nothing on
  the device is authoritative.
- Rounds advance in `game_tick` (any member may call it; it re-checks deadlines under a row
  lock and is idempotent) or eagerly when the last answer/vote arrives.

## Credits

Fonts: **Baloo 2** and **Nunito**, SIL Open Font License (see `core-ui/FONTS_OFL_*.txt`).
Sounds are synthesized at runtime (no audio assets). Logo: `docs/brand/loopa-logo-reference.png`.
