# Hosted migration review: LOOPA 0.7.0

Date: 2026-11-14. Hosted project: `zxtrxbgphutuiizznakd` (eu-west-2), PostgreSQL 17.11.

**Status: nothing was changed on the hosted database.** Every hosted query in this review was a
single SELECT, sent inside a read-only transaction and executed by the API as
`supabase_read_only_user`.

## 0. Hosted state today (verified)

Method: function and table hashes from hosted (read-only) were compared with a database built from
the repository's migrations.

| Check | Result |
|---|---|
| Functions | 165 hosted. All 165 are byte-identical to the repo **including** `20261115000003_room_lock_order.sql` |
| Tables (columns, types, defaults, nullability) | 45, identical |
| Grants on `room_heartbeat`, `leave_room`, `_leave_all_rooms` | identical to the repo |
| Social object names (tables / functions) | none exist (0 collisions) |
| Realtime publication `supabase_realtime` | owner `postgres`, not FOR ALL TABLES, contains only `room_signals` |
| Data volume | 5 users / profiles, 18 rooms (7 open), 7 matches, 54 MB in total |
| Default privileges in `public` | new tables, sequences and functions are granted to `anon` / `authenticated` (the Supabase default). The migration revokes them; see 1. |
| Backups | **PITR off, backup list empty** (Management API `database/backups`) |

## 1. `20261116000001_social.sql` (pending, awaiting approval)

### What it creates

**8 tables** (all empty at creation):

| Table | Key columns / constraints | Foreign keys |
|---|---|---|
| `friend_requests` | `id`, `from_user`, `to_user`, status (pending / accepted / declined / cancelled), times. One pending request per pair (unique partial index on least/greatest). `from_user <> to_user` | → `profiles` (on delete cascade) |
| `friendships` | PK (`user_a`, `user_b`) with `user_a < user_b` | → `profiles` (cascade) |
| `user_blocks` | PK (`blocker_id`, `blocked_id`) | → `profiles` (cascade) |
| `user_presence` | PK `user_id`, `last_seen_at`, `is_active`, `share_presence` | → `profiles` (cascade) |
| `room_invites` | `expires_at`, status, one pending invite per (room, invitee) | → `rooms`, `profiles` (cascade) |
| `direct_messages` | identity `id`, `body` 1–1000 chars, `read_at`, unique (`sender_id`, `client_id`) | → `profiles` (cascade) |
| `notifications` | identity `id`, type (4 values), `payload` jsonb, `read_at`. One unread "message" notification per sender | → `profiles` (cascade) |
| `social_signals` | PK `user_id`, `version` | → `profiles` (cascade) |

**11 indexes** (3 of them unique partial indexes), besides the primary keys and unique constraints.

**Row level security**, enabled on all 8 tables, with one SELECT policy each ("own rows"):
- requests and invites: sender or recipient;
- friendships: either user;
- messages: sender or recipient;
- presence, blocks, notifications and signals: the owner only.

**Grants:**
- `revoke all … from anon, authenticated`, then `grant select … to authenticated` on the 8 tables.
- `revoke all` on the two identity sequences. This hardening was added during this review: on
  hosted, the default privileges would otherwise give `anon` / `authenticated` USAGE/UPDATE on
  them.

**33 functions**, all `SECURITY DEFINER` with `search_path = public, pg_temp`:
- 21 client RPCs, executable by `authenticated` only.
- 12 internal helpers (`_…`), executable by no API role.

**Changes to existing objects:** one. `social_signals` is added to the `supabase_realtime`
publication; `room_signals` stays. No existing table, column, function, policy, grant or row is
modified. New foreign keys point to `profiles` and `rooms`; nothing points back to the new tables.

### Effect on existing data

None. The migration adds objects only.

On staging, the full content of all 46 pre-0.7 tables (public schema plus `auth.users` and
`auth.identities`, 162,757 rows, compared by row count and an md5 of the content) was **identical**
before and after the apply, and again before and after the rollback.

Locking: creating the foreign keys briefly takes a SHARE ROW EXCLUSIVE lock on `profiles` and
`rooms`, which pauses writes to those two tables only. The whole migration took **90 ms** on
staging. On hosted, with 5 users, the pause will be shorter than one network round trip.

### Risks and mitigations

| Risk | Likelihood / impact | Mitigation |
|---|---|---|
| Partial apply leaves a half-created schema | low / medium | Apply as **one transaction**. Any error rolls back everything, and the migration is idempotent and can simply be run again. |
| Privacy leak through default privileges | was real for the 2 sequences (no data exposure, only ID consumption) | Fixed in the file. Verified on staging (Supabase defaults) and covered by `social_test.sql`. |
| Realtime does not deliver `social_signals` changes at first | low / low | On staging the live realtime server picked up the new table without a restart (`SocialE2ETest` passed right after the apply). The app also re-fetches on resume and on screen open, so nothing is lost. |
| Abuse or spam through the new RPCs | medium / low | Rate limits, friends-only messaging and invites, blocking. A report button is **not** included (known gap). |
| Wrong data on rollback | — | Rollback deletes all social data by design. Export the 8 tables first if any must be kept. |
| Old app versions (0.6 and earlier) | none | They never call the new RPCs. |
| 0.7 app before the migration is applied | none | Social screens show "not active on the server yet". What's New announces no social features. |

### Rollback plan

Script: `supabase/rollback/20261116000001_social_down.sql`. Run it in one transaction.
1. Removes `social_signals` from the publication.
2. Drops the 33 functions.
3. Drops the 8 tables, with no CASCADE.

**Rehearsed on staging, with social data present** (4 friendships, 2 messages, 12 notifications,
2 invites):
- The rollback took 76 ms.
- Afterwards the catalog was again byte-identical to hosted today (165/165 functions, 45/45
  tables), the publication was back to `room_signals` only, and the existing data was identical.
- The 0.6-era backend E2E tests (İsim Şehir multiplayer, all 9 engine games, Plus/promo, Google
  account linking) passed on the rolled-back staging.
- The 0.7 app talking to a rolled-back server gets `PGRST202`, which it shows as "not active on
  the server yet".
- Re-applying afterwards produced the identical catalog, and `SocialE2ETest` passed again.

**When to roll back:**
- any post-apply check differs from the expected value (see 5);
- a pre-0.7 backend test fails;
- a security finding.

## 2. `20261115000003_room_lock_order.sql` (0.5 deadlock fix)

### Content

It replaces three functions. Each one now locks the room row (`select … for update`) **before**
it touches the caller's player row:

| Function | Change |
|---|---|
| `room_heartbeat(uuid)` | room lock first |
| `leave_room(uuid)` | room lock first |
| `_leave_all_rooms(uuid)` | room lock first; rooms visited in `room_id` order |

Grants are re-stated: `authenticated` may run the first two, and the helper is not callable by
clients. All other function bodies are unchanged. Previously these three locked the player row
first and the room row second, while `set_ready`, `start_match` and the other room functions
lock in the opposite order. A heartbeat and a "Hazır" from the same player at the same moment
could therefore deadlock.

### Finding: it is already applied on hosted

The three hosted functions contain the new code (`-- new: room first`) and are **byte-identical**
to the repository version, grants included. Nothing needs to be applied.

The earlier report ("hosted still has the bug") came from a faulty heuristic: a text search for
"lock" in the function body. It was wrong and has been corrected in `TODO.md`.

### Impact, risk and rollback (for completeness)

| | |
|---|---|
| Data | none (functions only) |
| Risk | small extra lock wait on the room row per heartbeat. Stress test: 1600 parallel calls with 0 deadlocks (476 before the fix). |
| Rollback | not recommended, because it brings the deadlock back. If ever needed, the previous bodies are in `20261107000002_game_functions.sql` and are restored with CREATE OR REPLACE. |

So the planned "second approval" for this migration becomes a **read-only re-verification**
(catalog hash compare), not an apply.

## 3. Backup requirement

PITR is off and the hosted project lists **no backups**. Before the social migration is applied:

1. **Required:** take a logical dump yourself. You have the database password, and it must not
   be pasted into chat. Either:
   - `supabase db dump --linked -f loopa_schema_2026-11-14.sql` and
     `supabase db dump --linked --data-only -f loopa_data_2026-11-14.sql` (Supabase CLI, linked
     to the project), or
   - `pg_dump "<connection string from Dashboard → Connect>" -Fc -f loopa_2026-11-14.dump`.

   Keep the file outside the repository; it contains user data.
2. **Optional:** a paid plan with daily backups or PITR before user numbers grow.
3. The migration only adds objects, and the rollback script removes them without touching
   existing rows. The dump is the safety net for the unexpected, not part of the normal rollback.

## 4. Staging rehearsal

There is no hosted staging project, and creating one is a billing and organisation decision. The
local Supabase stack (Docker: Postgres, GoTrue, PostgREST, Realtime) was used instead and
verified to match hosted:
- PostgreSQL 17.11;
- `postgres` is not a superuser;
- the same default privileges;
- before the rehearsal, a catalog byte-identical to hosted (165 functions, 45 tables).

| Step | Result |
|---|---|
| Bring staging to the exact hosted state | ✅ 165/165 functions, 45/45 tables identical to hosted |
| Data fingerprint (46 tables, 162,757 rows) | taken |
| Apply in one transaction | ✅ 90 ms |
| Catalog after apply | ✅ existing objects unchanged; +33 functions, +8 tables |
| Security checks (RLS 8/8, 8 policies, publication, 0 client write privileges, 0 anon reads, 0 sequence privileges, 0 helper / anon execute) | ✅ |
| Existing data after apply | ✅ identical |
| Re-apply (idempotency) | ✅ catalog identical |
| `social_test.sql` on staging | ✅ |
| Backend E2E on staging: Social, all 9 engine games, İsim Şehir multiplayer, Plus/promo, Google linking, OAuth | ✅ 8/8 (see the note below) |
| Rollback with social data present | ✅ 76 ms, catalog = hosted, data identical |
| Pre-0.7 E2E on the rolled-back staging | ✅ 4/4 |
| Re-apply and Social E2E again | ✅ |
| Full SQL suite (plain PG, with the hardened migration) | ✅ all files plus the 3 concurrency scripts |

Note: the first E2E run had one failure in `AccountLinkingE2ETest`. The local fake Google (OIDC)
server was not running after a container restart (connection refused on port 9999). After
restarting it, the whole suite passed. This was not related to the migration.

## 5. Proposed hosted procedure (needs your approval for each step)

**Step A: re-verify the 0.5 lock-order fix.** Read-only, no change: compare the hosted catalog
hashes with the repo (`supabase/review/catalog_functions.sql`, `catalog_tables.sql`).

**Step B: apply `20261116000001_social.sql`.** Only after you confirm the dump from section 3.
1. Pre-check (read-only):
   - catalog still equals the expected state;
   - 0 social objects;
   - publication contains `room_signals` only.
2. Apply the file as one transaction, through the Management API, in a single call. This is the
   only write.
3. Post-check (read-only): `supabase/review/social_post_apply_checks.sql`. Every `*_got` must
   equal its `*_want`. Also compare again: the 165 existing functions and 45 tables unchanged.
4. Hosted backend tests:
   - **(a) No data written:** the post-check above, plus a check that the 0.7 RPCs are visible to
     PostgREST (no `PGRST202`).
   - **(b) Live E2E** (`SocialE2ETest` pointed at the hosted URL with the public anon key). It
     creates **3 temporary anonymous test accounts** on production. They make requests, send
     messages and use one room, and are deleted afterwards (their `auth.users` rows, which
     cascade to profile and social rows). This writes to production and needs a separate yes.
     The existing 9-game E2E would create rooms and matches on production, so I would not run it
     there unless you ask.
5. On any failure: run the rollback script in one transaction, then the post-check again
   (expect 0 social objects), and report.
