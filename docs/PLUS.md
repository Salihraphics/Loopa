# LOOPA Plus, game modes, rotation and admin: design (stage 2)

Status: **migrations prepared and tested locally. NOT applied to the hosted project.**
There is no payment integration. Nothing in the app uses these functions yet (UI is a later stage).

## Decisions this design follows (2026-11-08)
1. İsim Şehir is always free. The weekly rotation covers only the other 9 modes.
2. The rotation start is not fixed. Week 1 = the week the new games ship. It is a setting
   (`rotation_settings.anchor_week`, a Monday), changed with `admin_set_rotation_anchor`.
3. `LOPPAWEEK`: exactly 7 days of Plus, once per account, only for accounts with a linked Google identity.
4. Gizli Kelime, Anlat Bakalım and Sessiz Sinema are played in the same physical place; the app
   manages the word, the timer, the guesses and the score. No camera, no voice.
5. Friends and tournaments are out of scope; the schema leaves room for them (see below) and the
   UI must not show them.
6. The admin e-mail allowlist lives only in the database and is managed from the Supabase dashboard
   SQL editor. No address is in the source, migrations or APK.
7. İsim Şehir is not rewritten. Its engine functions are untouched; `start_match`,
   `update_room_settings`, `list_public_rooms` and `_default_settings` only gained a mode check / field.

## Migrations
| File | Content |
|---|---|
| `20261110000001_plans_entitlements_admin.sql` | `plans`, `entitlements`, `admin_allowlist`, `user_roles`, `admin_audit_log`; `_user_plan`, `_plus_until`, `get_my_plan`, admin RPCs, console helpers |
| `20261110000002_promo_codes.sql` | `promo_codes`, `promo_redemptions`; `redeem_promo_code`, `admin_set_promo_code`; seeds `LOPPAWEEK` **inactive** |
| `20261110000003_game_modes_rotation.sql` | `game_modes` (10), `rotation_settings`, `mode_rotation`; `matches.game_mode`, `matches.entitlement_snapshot`; `_mode_access`, `get_game_catalog`; mode check in `update_room_settings` / `start_match` |

All three only add tables, columns and functions, and can be re-run (verified: applying them a
second time changes nothing). No row is deleted or rewritten. Existing rooms and matches read as
İsim Şehir.

## How a plan is decided
`_user_plan(user, at)` is the only place: `admin` if an active admin role exists, otherwise
`plus` if an entitlement window covers `at`, otherwise `free`. Clients cannot write any of the
inputs (no insert/update grants, RLS read-only, internal `_` functions not executable).

`entitlements` rows are time windows with a `source`:
- `promo`: written by `redeem_promo_code`.
- `admin`: written by `admin_grant_plus` (optionally open-ended).
- `store`: **reserved for Google Play Billing.** A future Edge Function (service role, never in the
  APK) verifies the purchase token with the Play Developer API and inserts/extends one row per order
  (`source_ref` = order id, unique). Renewals extend `ends_at`, refunds/revocations set
  `revoked_at`. Nothing else has to change: the plan computation already reads these rows.

New windows are **stacked after the current Plus end** (`_grant_plus`), under a per-user advisory
lock, so a promo never overlaps or shortens an existing Plus.

## LOPPAWEEK
- Exact spelling (`LOPPAWEEK`); input is trimmed and case-insensitive. `LOPAWEEK`, `LOPPA WEEK` are rejected.
- Errors: `PROMO_INVALID` (unknown, inactive or outside its window: one code, so codes cannot be
  probed), `GOOGLE_LINK_REQUIRED`, `PROMO_EXHAUSTED`, `PLUS_ALREADY_UNLIMITED`.
- Once per account: `unique(code, user_id)` plus a row lock on the code. A second call returns
  `already_redeemed` with the same `plus_until` (also after the code expired): retries are safe.
- Created **inactive** so it cannot be used before Plus features exist. Switch it on (and set a
  window if wanted) at release, as an admin in the app later, or in the SQL editor:
  `update public.promo_codes set active = true, valid_until = null where code = 'LOPPAWEEK';`
- Promo codes never grant admin. There is no code path for that.

## Admin
- Allowlist: `admin_allowlist(email)`, no client access. Managed in the **dashboard SQL editor**:
  ```sql
  select public._admin_allowlist_add('<address>', 'note');
  select public._admin_allowlist_revoke('<address>');
  ```
- The role is claimed by `get_my_plan()` when the caller has a linked **Google** identity whose
  e-mail is **verified** (`identity_data.email_verified`) and on the list. The entry is then bound
  to that user id; another account with the same address cannot take it. An e-mail/password
  identity or an unverified address never qualifies.
- Admin RPCs check `_is_admin` on the server and write `admin_audit_log` (console actions too; the
  log stores an md5 of the address, not the address): `admin_grant_plus`, `admin_revoke_entitlement`,
  `admin_user_overview`, `admin_set_promo_code`, `admin_set_rotation_anchor`, `admin_set_mode_status`.

## Modes and rotation
`game_modes.status`: `available` (normal rules), `early_access` (Plus/admin only), `coming_soon`
(admin may select, for testing), `disabled`. All new modes are `coming_soon`.

Rotation (Monday 00:00 Europe/Istanbul, database clock):

| Week | Free to host (besides İsim Şehir) |
|---|---|
| 1 | Çiz ve Tahmin Et |
| 2 | Gizli Kelime, En Muhtemel Kim? |
| 3 | Kelime Zinciri, Anlat Bakalım |
| 4 | Bilgi Arenası, Çizim Telefonu |
| 5 | İki Doğru Bir Yalan, Sessiz Sinema |

Week 1 has one rotating game because İsim Şehir left the rotation (it is free every week). If you
prefer another distribution, only the `mode_rotation` rows change.

`_mode_access(host, mode)`: İsim Şehir → always; Plus/admin host → every available mode; Free host →
modes of the current week. **The host's plan decides; Free players can join and play.**
`get_game_catalog()` returns every mode with `access` (`allowed`, `reason`, `unlocked_by`), this
week's and next week's free modes and the week boundaries.

Room rules:
- `update_room_settings` checks access only when the mode **changes**. A 0.3.0 client that sends no
  `game_mode` keeps the room's mode.
- `start_match` checks again (a new Free host, or an expired Plus, cannot start a locked mode) and
  stores `matches.entitlement_snapshot` (host, plan, how it was unlocked, week). A running match
  never re-checks the plan, so it is not interrupted when the host leaves or Plus ends.
- Modes other than İsim Şehir have no engine yet: `start_match` answers `MODE_NOT_IMPLEMENTED`.

## Verification (local only)
| Check | Result |
|---|---|
| All existing SQL suites (dictionary, fallback, scoring, game_flow, ten_players) on plain PG with the new migrations | pass |
| `plus_test.sql` (plans, admin allowlist, promo rules, stacking, expiry, revocation, grants) | pass |
| `game_modes_test.sql` (catalogue, week arithmetic incl. Istanbul vs UTC, gating, snapshot, old-client settings) | pass |
| `promo_concurrency.sh`: two simultaneous redemptions → exactly one entitlement | pass |
| Mutation checks: tests fail when the Google check, the Istanbul week or the access check is broken, and when the row lock and unique key are removed (concurrency script) | detected |
| Local Supabase (Docker: real GoTrue, PostgREST, roles): `tools/plus-test/plus_e2e.py`, 26 checks over HTTP with real user JWTs | 26/26 |
| Unchanged 0.3.0 client code: `MultiplayerE2ETest` (4 clients, full match) against local Supabase with the new migrations | pass |

Not verified: a real Google account (the Google link is simulated as GoTrue stores it), the hosted
project, any phone. The SQL tests run inside a rolled-back transaction.

## Later stages (each one small, each one runs the İsim Şehir regression suites)
| Stage | Scope | Done when |
|---|---|---|
| 3 | App: plan + catalogue in `core-data`; Plus status and promo field in Settings; mode picker with locked cards in the lobby (only İsim Şehir selectable for players) | unit + screenshot tests, Multiplayer E2E |
| 4 | Common mode engine: `game_sessions`-style tables (below); router in `_advance`/`get_room_state` that leaves İsim Şehir's path unchanged | İsim Şehir suites unchanged and passing |
| 5… | One mode per stage: Kelime Zinciri → Bilgi Arenası → En Muhtemel Kim? → İki Doğru Bir Yalan → Gizli Kelime / Anlat Bakalım / Sessiz Sinema → Çiz ve Tahmin Et → Çizim Telefonu | mode SQL suite + E2E; status `available` only after that |
| — | Rotation anchor set, `LOPPAWEEK` activated, migrations applied to hosted | only with explicit approval |

Planned common engine tables (stage 4, not created yet): `mode_rounds(match_id, round_number,
mode_state jsonb, turn_user_id, phase, deadlines)`, `mode_secrets(round_id, user_id null = server,
payload)` with **no** client policy (secret words, chains, lies; served per player by an RPC),
`mode_submissions(round_id, user_id, kind, payload, verdict, points)`, `mode_votes`. İsim Şehir keeps
`rounds`/`answers`/`answer_votes`.

## Extension points (out of scope now)
- **Friends**: `friendships(user_a, user_b, status, created_at)` + invite RPCs; rooms already
  support private codes, so an invite only delivers a code. Nothing in this design depends on it.
- **Tournaments**: `tournaments`, `tournament_entries`, `tournament_matches(match_id)` referencing
  `matches`; creation would be gated with `_user_plan(host) in ('plus','admin')`. Not shown in the UI
  until it exists.
- **Plus cosmetics / content packs**: a `requires_plus` column on `cosmetic_items` and a
  `content_packs` table, checked through `_user_plan`. Not added yet.
- **Price** (proposal only, nothing implemented): 49,99 TL / month through Google Play Billing.
