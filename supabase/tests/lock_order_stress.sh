#!/usr/bin/env bash
# Parallel heartbeats, "ready" toggles and settings changes in one room must never deadlock.
# Runs against the throw-away local test database (run_local_tests.sh) and cleans up after itself.
#   PGHOST=... PGPORT=... PGUSER=postgres ./supabase/tests/lock_order_stress.sh
set -uo pipefail
DB=${LOOPA_TEST_DB:-loopa_test}
N=${LOOPA_STRESS_N:-400}
H=50000000-0000-0000-0000-0000000000a1
P=50000000-0000-0000-0000-0000000000a2
q() { psql -v ON_ERROR_STOP=1 -qtA -d "$DB" "$@"; }
TMP=$(mktemp -d)

q -c "insert into auth.users(id) values ('$H'), ('$P') on conflict do nothing;" >/dev/null
ROOM=$(q -c "select set_config('request.jwt.claim.sub', '$H', false);" -c "select public.ensure_profile('Stres Host');" \
         -c "select (public.create_room(false))->>'id';" | tail -1)
CODE=$(q -c "select code from public.rooms where id = '$ROOM'")
q -c "select set_config('request.jwt.claim.sub', '$P', false);" -c "select public.ensure_profile('Stres Oyuncu');" -c "select public.join_room('$CODE');" >/dev/null

# One statement per transaction, so each call takes and releases its locks like a real request.
for i in $(seq 1 "$N"); do echo "select set_config('request.jwt.claim.sub', '$P', false); select public.room_heartbeat('$ROOM');"; done > "$TMP/hb.sql"
for i in $(seq 1 "$N"); do echo "select set_config('request.jwt.claim.sub', '$P', false); select public.set_ready('$ROOM', $(( i % 2 )) = 1);"; done > "$TMP/ready.sql"
for i in $(seq 1 "$N"); do echo "select set_config('request.jwt.claim.sub', '$H', false); select public.update_room_settings('$ROOM', (select settings from public.rooms where id = '$ROOM'), false);"; done > "$TMP/settings.sql"
for i in $(seq 1 "$N"); do echo "select set_config('request.jwt.claim.sub', '$H', false); select public.room_heartbeat('$ROOM');"; done > "$TMP/hb2.sql"

for f in hb ready settings hb2; do psql -qtA -d "$DB" -f "$TMP/$f.sql" > /dev/null 2> "$TMP/$f.err" & done
wait
DEADLOCKS=$(cat "$TMP"/*.err | grep -c "deadlock detected" || true)
OTHER=$(cat "$TMP"/*.err | grep -v "deadlock detected" | grep -c "ERROR" || true)

q -c "delete from public.rooms where id = '$ROOM'; delete from public.player_statistics where user_id in ('$H','$P');
      delete from public.player_cosmetics where user_id in ('$H','$P'); delete from public.profiles where id in ('$H','$P');
      delete from auth.users where id in ('$H','$P');" >/dev/null
rm -rf "$TMP"
echo "parallel calls: $((N * 4)), deadlocks: $DEADLOCKS, other errors: $OTHER"
[[ "$DEADLOCKS" == 0 && "$OTHER" == 0 ]] || { echo "LOCK ORDER STRESS TEST FAILED"; exit 1; }
echo "LOCK ORDER STRESS TEST PASSED"
