#!/usr/bin/env bash
# Two simultaneous LOPPAWEEK redemptions by the same account must produce exactly one
# entitlement. Uses committed transactions, so it only runs against the throw-away
# local test database (run_local_tests.sh creates it) and cleans up after itself.
#   PGHOST=... PGPORT=... PGUSER=postgres ./supabase/tests/promo_concurrency.sh
set -euo pipefail
DB=${LOOPA_TEST_DB:-loopa_test}
U=30000000-0000-0000-0000-0000000000c1
q() { psql -v ON_ERROR_STOP=1 -qtA -d "$DB" "$@"; }

q -c "insert into auth.users(id, email, is_anonymous) values ('$U', 'race@loopa-test.invalid', false);
      insert into auth.identities(id, user_id, provider, identity_data)
        values ('race', '$U', 'google', '{\"email\":\"race@loopa-test.invalid\",\"email_verified\":true}');
      select set_config('request.jwt.claim.sub', '$U', false); select public.ensure_profile('Yarış');
      update public.promo_codes set active = true where code = 'LOPPAWEEK';" >/dev/null

redeem() {  # holds its transaction open for $1 seconds after redeeming
  q -c "begin; select set_config('request.jwt.claim.sub', '$U', true);
        select public.redeem_promo_code('LOPPAWEEK')->>'status'; select pg_sleep($1); commit;" | grep -E 'redeemed'
}
redeem 2 > /tmp/loopa_race_a.txt & A=$!
sleep 0.5
redeem 0 > /tmp/loopa_race_b.txt & B=$!
wait $A $B
a=$(cat /tmp/loopa_race_a.txt); b=$(cat /tmp/loopa_race_b.txt); rm -f /tmp/loopa_race_[ab].txt
n=$(q -c "select count(*) from public.entitlements where user_id = '$U'")
r=$(q -c "select count(*) from public.promo_redemptions where user_id = '$U'")

q -c "delete from public.promo_redemptions where user_id = '$U'; delete from public.entitlements where user_id = '$U';
      update public.promo_codes set active = false, redemptions = greatest(redemptions - $r, 0) where code = 'LOPPAWEEK';
      delete from public.player_statistics where user_id = '$U'; delete from public.player_cosmetics where user_id = '$U';
      delete from public.profiles where id = '$U'; delete from auth.users where id = '$U';" >/dev/null

echo "first: $a, second: $b, entitlements: $n, redemptions: $r"
[[ "$a" == "redeemed" && "$b" == "already_redeemed" && "$n" == 1 && "$r" == 1 ]] || { echo "PROMO CONCURRENCY TEST FAILED"; exit 1; }
echo "PROMO CONCURRENCY TEST PASSED"
