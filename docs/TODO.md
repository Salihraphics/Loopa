# LOOPA — status and TODO

Last updated: 2026-11-15

## Hosted project (zxtrxbgphutuiizznakd, "Loopa", eu-west-2)

- 2026-11-07: migrations 0001–0004 applied via the Management API SQL endpoint (direct
  Postgres ports are not reachable from the build sandbox) and recorded in
  `supabase_migrations.schema_migrations`, so `supabase db push` will treat them as applied.
- Verified: 19 tables with RLS, 18 policies, 32 client RPCs / 28 internal helpers
  (not executable by clients), no client write grants, `room_signals` in the realtime
  publication, seeds (12 categories, 87 cosmetics, 1,752 words).
- Anonymous sign-ins were still off on the project; switched on (`external_anonymous_users_enabled`).
- Live 4-client E2E passed against the hosted project with the publishable key; test data was deleted afterwards.

## Hosted project changes (2026-11-13)
- Stage-2 and 0.5.0 migrations (`20261110000001`–`03`, `20261115000001`–`03`) applied through the Management API and
  recorded in `schema_migrations`; `modes_test.sql` passed on hosted (rolled back, no residue).
- Weekly rotation started: `rotation_settings.anchor_week = 2026-11-09` (week 1 = launch week), logged in
  `admin_audit_log` as a console action. LOPPAWEEK still inactive, admin allowlist empty.

## Version 0.5.0 — all 10 games playable (2026-11-15)

Details: [GAMES.md](GAMES.md).

| Item | Status | Verified by |
|---|---|---|
| Common mode engine + 9 games (server rules, scoring, timers, secrecy, disconnect / host change) | ✅ local only, **not applied to hosted** | `modes_test.sql`, `ModesE2ETest` (4 clients, local Supabase) |
| 9 game screens, lobby game picker, results and profile per game | ✅ | `ModeScreensTest`, `PlusUiTest`, screenshots |
| İsim Şehir unchanged | ✅ | `game_flow_test.sql`, `ten_players_test.sql`, dictionary suites, `MultiplayerE2ETest` |
| Deadlock between heartbeat and room actions (bug since 0.1) fixed: `20261115000003_room_lock_order.sql` | ✅ local; **hosted still has the bug** | `lock_order_stress.sh`: 476 deadlocks before, 0 after |
| On a phone / hosted backend | ❌ not tested | — |
| Report / block for user-written text (İki Doğru Bir Yalan) | ❌ missing | — |

## Version 0.4.0 — membership and 10-game UI (stage 3, 2026-11-13)

| Item | Status | Verified by |
|---|---|---|
| Membership (Free / LOOPA Plus / Admin) read from `get_my_plan`; badge on Profile, card in Settings, never shown as "Ücretsiz" while unknown | ✅ | `PlusUiTest`, `PlusRepositoriesE2ETest` (local Supabase) |
| Game list with all 10 games, lock reasons, this/next week preview, "Yakında" for unfinished games, nothing claimed while the rotation is off | ✅ | `GameCardsTest` (15), `PlusUiTest` |
| Promo code field (LOPPAWEEK: 7 days, Google-linked only, once per account), server answer shown as is | ✅ against local Supabase | `PlusRepositoriesE2ETest` (wrong spellings, guest refused, success, second use) |
| Plus info screen (6 benefits marked "Yakında", no tournaments, purchase button disabled) | ✅ | `PlusUiTest` |
| Lobby: game shown; a host whose game cannot be started is told why (re-checked after host changes) | ✅ (code); only İsim Şehir rooms exist today | compile, `MultiplayerE2ETest` |
| Older server (hosted without stage-2 migrations): app works as 0.3.0, Plus parts say "not active on this server" | ✅ | `ErrorMappingTest` (PGRST202), `PlusUiTest.olderServerWithoutPlus` |
| On a phone / against hosted with the migrations / with a real Google account | ❌ not tested | — |

## Next: game modes, LOOPA Plus, rotation, admin (stage 2, 2026-11-08)

Design and decisions: [PLUS.md](PLUS.md).

| Item | Status | Verified by |
|---|---|---|
| Migrations `20261110000001..3` (plans/entitlements/admin, promo codes, modes/rotation/gating) | ✅ prepared, **local only, not applied to hosted** | SQL suites on plain PG, local Supabase (Docker) HTTP E2E 26/26, unchanged 0.3.0 Multiplayer E2E |
| App UI (Plus status, promo field, mode picker, rotation screen) | ✅ in 0.4.0 (see above) | — |
| Common engine and the 9 new modes | ✅ in 0.5.0 (see above) | — |
| Payment | ❌ out of scope; `entitlements.source = 'store'` reserved | — |
| Real Google account claiming admin / redeeming LOPPAWEEK | ❌ not tested (Google link simulated as GoTrue stores it) | — |

## Version 0.3 (2026-11-09)

| Item | Status | Verified by |
|---|---|---|
| Dictionary v3 (130,929 entries), İ/I and Ö/O letter bug, food wrong-category rule, compound dishes, controlled fallback, suggestion model | ✅ | SQL suites locally **and on the hosted DB**, gold/held-out evaluation, re-classification of real answers |
| Room-code keyboard overlap | ✅ in Robolectric with emulated keyboard insets (4 screen sizes) | `KeyboardLayoutTest` (fails without the fix); **not yet confirmed on a phone** |
| Results consistency (totals, XP, same on all clients) | ✅ | live 4-client E2E on the hosted project |
| Guest → Google linking keeps the user id | ✅ on real Supabase Auth with an OIDC test provider; **not yet observed in production** (both real Google accounts were created by direct Google sign-in) | `AccountLinkingE2ETest`, `AuthAndroidTest`, hosted auth data |

### Known issues / limitations (0.3)
- Professions recorded on people (Wikidata P106) could not be fetched: QLever rate-limited the
  build machine (HTTP 429). Retry with `tools/dictionary/fetch.sh` and rebuild as version 4.
- Weakest held-out categories: Marka 21/29, Meslek 35/41. Unknown words go to the vote, and votes
  are now collected as suggestions.
- "Salih" has two accounts in production: a guest (level 2) and a separate Google account.
  Nothing was merged (by design). Moving progress between accounts would need a deliberate admin tool.
- Wikidata-derived İsim/Ünlü lists contain many foreign names. This is harmless for accepting
  names, but means İsim/Ünlü are never used as wrong-category evidence.

## Version 0.2 (2026-11-08)

| Feature | Status | Verified by |
|---|---|---|
| Dictionary v2: 101,223 entries, 5,033 aliases, versioned, Wikidata (CC0) + curated (CC0) | ✅ | `dictionary_test.sql` locally **and on the hosted DB**; `dictionary_scoring_test.sql`; live 4-client E2E on hosted |
| Spelling-insensitive matching, per-spelling letter check, wrong-category rejection, voting kept for unknown words | ✅ | same |
| Duplicates by dictionary entry (ABD = Amerika) | ✅ | `dictionary_scoring_test.sql` |
| Guest account, Google sign-in, guest → Google linking (same user id), sign-in on another device, sign-out | ✅ (server + app logic) | `AccountLinkingE2ETest` against real Supabase Auth with an OIDC test provider |
| Google provider configuration (client, callback URI, linking URL) | ✅ | Supabase + Google answer checks (docs/GOOGLE_AUTH.md) |
| Real Google login on a phone | ❌ not tested — needs a device; checklist in docs/GOOGLE_AUTH.md | — |
| Google button UI (onboarding + settings), conflict dialog | ✅ (renders; disabled until the provider is on) | screenshot test, compile |
| Persistent session on Android | ✅ in Robolectric with the real Android storage (not on a physical device) | `AuthAndroidTest` |

## Done and tested

| Area | Status | How it was verified |
|---|---|---|
| Gradle multi-module project (14 modules, convention plugins) | ✅ | `assembleDebug`, `assembleRelease` succeed |
| Branding: logo mark, adaptive/monochrome icon, splash, animated logo | ✅ | screenshot `01_brand` |
| Design system (palette, Baloo 2 + Nunito with full Turkish glyphs, components, light/dark) | ✅ | screenshots |
| Anonymous auth + persistent profile, onboarding | ✅ | live E2E (auth + `ensure_profile`) |
| Rooms: codes, join, leave, ready, kick, host transfer, close, public/private, spectators | ✅ | SQL suite + live E2E |
| Host settings: duration, rounds, player limit, vote time, categories, custom categories, excluded letters | ✅ | SQL suite (validation) + E2E (update + propagation) |
| Gameplay: letter, individual server timers, drafts, submit/lock, +5 early bonus | ✅ | SQL suite + E2E (simultaneous submits) |
| Jokers (4 types, weights, inventory 3, 1/round, all rules) | ✅ | SQL suite (every rule) + E2E |
| Validation (dictionary) + voting (majority, ties, own-vote ban, immutable votes, fallback) | ✅ | SQL suite + E2E |
| Scoring 20/10/0, 2X, round winners, joker reward | ✅ | SQL suite + E2E |
| Reveal synchronized by server time, round results | ✅ | E2E (same timeline on 2 clients), screenshots |
| Match results, XP (idempotent), levels, stats, history | ✅ | SQL suite + E2E (XP matches the reward table) |
| Disconnect / reconnect / app-restart recovery | ✅ | E2E (client killed mid-round, round closed by deadline, client rejoined) |
| Avatar creator (13 layered slots, 87 items), cosmetic shop (level unlocks), profile, settings | ✅ (UI) | screenshots; avatar/unlock RPCs in SQL suite |
| Sound effects (synthesized), music loop, vibration, toggles | ✅ (code) | not audible in tests |

## Not yet verified

- [ ] **Run on physical Android devices / emulator.** There was no emulator (no KVM) in
      the build environment. UI was verified via Robolectric screenshots and the network
      layer via JVM clients. A 2-device and a 10-device manual session is the next test.
- [ ] Full 10-player room with 10 *network clients* (verified at SQL level by `ten_players_test.sql`: capacity, 11th refused/spectates, 10 simultaneous submits → one bonus, voting, scoring; the live E2E used 3 players + 1 spectator).
- [ ] Release APK runtime check (R8). Build succeeds and serializers, Ktor engine and
      Supabase implementations are present in the dex, but it has not been launched.
- [ ] Sound/haptics on real hardware.

## Unfinished / next tasks

1. Device test pass (2 phones + emulator) against a hosted Supabase project; fix any UX issues.
2. Review the Wikidata-derived lists for noise (e.g. some professions) and extend the blocklist / curated lists.
3. Real-Google verification on a phone once the provider is configured (docs/GOOGLE_AUTH.md).
4. English localisation: strings are currently Turkish literals in Compose. Move them to
   `strings.xml` and add `values-en`. The language picker already exists (only Türkçe enabled).
5. Optional `pg_cron` job to call `_advance` for rooms where every client went away mid-round
   (today a round only advances when some member calls `game_tick`; nobody is waiting in that case).
6. CI workflow (build + `:core:test` + SQL suite + screenshots).
7. Rate limiting for `create_room` (a player can create many rooms; each new one closes the previous membership but not the room).
8. Grouped voting (vote once for identical uncertain answers) to shorten voting with many players.
9. Lobby chat / emoji reactions (not in the brief; needs approval).

## Known behaviours / decisions

- Uncertain answers with no votes at the deadline are **rejected** (tie 0–0). Answers with
  **no eligible voters** are accepted.
- An offline-but-not-left player still counts as an eligible voter until the voting deadline.
- Realtime: Postgres CDC alone occasionally dropped a signal while other clients were
  subscribing (seen in 1 of ~5 E2E runs). Clients now also send a data-free Broadcast
  "poke" after each action; with both paths the E2E passed 4/4 with polling disabled
  (join propagation ≈ 75–80 ms). The 4 s / 8 s safety poll remains as a last resort.
- The host can skip the reveal/results countdowns ("Atla").
