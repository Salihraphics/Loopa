#!/usr/bin/env bash
# Two users send each other a friend request at the same moment (and a burst of 40 parallel
# pairs): every pair must end as exactly one friendship with no pending request left, and no
# deadlocks. Uses committed transactions, so it only runs against the throw-away local test
# database (run_local_tests.sh creates it) and cleans up after itself.
set -euo pipefail
DB=${LOOPA_TEST_DB:-loopa_test}
q() { psql -v ON_ERROR_STOP=1 -qtA -d "$DB" "$@"; }
PAIRS=40
uid() { printf '40000000-0000-0000-0000-%012d' "$1"; }

setup=""
for i in $(seq 1 $((PAIRS * 2))); do
  setup+="insert into auth.users(id, is_anonymous) values ('$(uid $i)', true);
          select set_config('request.jwt.claim.sub', '$(uid $i)', false); select public.ensure_profile('Eş $i');"
done
q -c "$setup" >/dev/null

send() {  # $1 sender, $2 target, $3 seconds to hold the transaction
  q -c "begin; select set_config('request.jwt.claim.sub', '$1', true);
        select public.send_friend_request('$2')->>'status'; select pg_sleep($3); commit;" 2>&1 | grep -vE '^$|^BEGIN|^COMMIT|^set_config' || true
}
LOG=$(mktemp)
for i in $(seq 1 $PAIRS); do
  a=$(uid $((2 * i - 1))); b=$(uid $((2 * i)))
  send "$a" "$b" 0.3 >> "$LOG" &
  send "$b" "$a" 0 >> "$LOG" &
done
wait
deadlocks=$(grep -ci deadlock "$LOG" || true)
errors=$(grep -ci error "$LOG" || true)
friends=$(q -c "select count(*) from public.friendships where user_a::text like '40000000-%'")
pending=$(q -c "select count(*) from public.friend_requests where from_user::text like '40000000-%' and status = 'pending'")
sent=$(grep -c '^sent$' "$LOG" || true); accepted=$(grep -c '^accepted$' "$LOG" || true)
rm -f "$LOG"

q -c "delete from public.profiles where id::text like '40000000-%'; delete from auth.users where id::text like '40000000-%';" >/dev/null

echo "pairs: $PAIRS, friendships: $friends, pending left: $pending, sent: $sent, accepted: $accepted, deadlocks: $deadlocks, errors: $errors"
[[ "$friends" == "$PAIRS" && "$pending" == 0 && "$sent" == "$PAIRS" && "$accepted" == "$PAIRS" && "$deadlocks" == 0 && "$errors" == 0 ]] \
  || { echo "SOCIAL CONCURRENCY TEST FAILED"; exit 1; }
echo "SOCIAL CONCURRENCY TEST PASSED"
