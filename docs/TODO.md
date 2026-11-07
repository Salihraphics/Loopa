# LOOPA — status and TODO

Last updated: 2026-11-07 (session 1)

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
2. Expand `word_dictionary` (today 1,752 words; ülke and şehir are near-complete, ünlü/marka/film/oyun are small, so more answers go to votes).
3. Account linking (email / Google) for anonymous users (`auth.linkIdentity`); the Settings screen states it is not available yet.
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
