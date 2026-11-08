-- =====================================================================
-- LOOPA — plans, entitlements, admin allowlist and promo codes (stage 2a/2b)
-- Runs inside one transaction that is rolled back: safe on any database.
-- E-mail addresses below are reserved test domains (.invalid), not real ones.
-- =====================================================================
\set ON_ERROR_STOP 1
set client_min_messages = warning;
begin;

create or replace function pg_temp.act(p uuid) returns void language sql as $$
    select set_config('request.jwt.claim.sub', coalesce(p::text, ''), true);
$$;
create or replace function pg_temp.expect_error(p_sql text, p_code text) returns void language plpgsql as $$
begin
    execute p_sql;
    raise exception 'expected error % but statement succeeded: %', p_code, p_sql;
exception when others then
    if sqlerrm not like '%' || p_code || '%' then
        raise exception 'expected error % but got: % (statement: %)', p_code, sqlerrm, p_sql;
    end if;
end $$;
create or replace function pg_temp.check(ok boolean, what text) returns void language plpgsql as $$
begin
    if not coalesce(ok, false) then raise exception 'CHECK FAILED: %', what; end if;
end $$;

-- G1/G2: Google-linked; GUEST: anonymous; ADM: allowlisted admin; IMP: same e-mail, no verified Google
insert into auth.users(id, email, is_anonymous, email_confirmed_at) values
 ('10000000-0000-0000-0000-0000000000a1', 'g1@loopa-test.invalid', false, now()),
 ('10000000-0000-0000-0000-0000000000a2', 'g2@loopa-test.invalid', false, now()),
 ('10000000-0000-0000-0000-0000000000a3', null, true, null),
 ('10000000-0000-0000-0000-0000000000a4', 'boss@loopa-test.invalid', false, now()),
 ('10000000-0000-0000-0000-0000000000a5', 'boss@loopa-test.invalid', false, null),
 ('10000000-0000-0000-0000-0000000000a6', 'boss@loopa-test.invalid', false, now());
insert into auth.identities(id, user_id, provider, identity_data) values
 ('t-g1', '10000000-0000-0000-0000-0000000000a1', 'google', '{"email":"g1@loopa-test.invalid","email_verified":true}'),
 ('t-g2', '10000000-0000-0000-0000-0000000000a2', 'google', '{"email":"g2@loopa-test.invalid","email_verified":true}'),
 ('t-g3', '10000000-0000-0000-0000-0000000000a3', 'anonymous', '{}'),
 ('t-g4', '10000000-0000-0000-0000-0000000000a4', 'google', '{"email":"Boss@Loopa-Test.invalid","email_verified":true}'),
 -- a5: e-mail/password identity with the admin's address (not Google) => never admin
 ('t-g5', '10000000-0000-0000-0000-0000000000a5', 'email', '{"email":"boss@loopa-test.invalid","email_verified":true}'),
 -- a6: Google identity with the admin's address but unverified => never admin
 ('t-g6', '10000000-0000-0000-0000-0000000000a6', 'google', '{"email":"boss@loopa-test.invalid","email_verified":false}');

do $$
declare
    G1 uuid := '10000000-0000-0000-0000-0000000000a1';
    G2 uuid := '10000000-0000-0000-0000-0000000000a2';
    GUEST uuid := '10000000-0000-0000-0000-0000000000a3';
    ADM uuid := '10000000-0000-0000-0000-0000000000a4';
    IMP uuid := '10000000-0000-0000-0000-0000000000a5';
    IMP2 uuid := '10000000-0000-0000-0000-0000000000a6';
    v jsonb; v2 jsonb; t timestamptz; n int; eid uuid;
begin
    perform pg_temp.act(G1);    perform public.ensure_profile('Plus Gül');
    perform pg_temp.act(G2);    perform public.ensure_profile('Plus Gaye');
    perform pg_temp.act(GUEST); perform public.ensure_profile('Misafir');
    perform pg_temp.act(ADM);   perform public.ensure_profile('Yönetici');
    perform pg_temp.act(IMP);   perform public.ensure_profile('Taklitçi');
    perform pg_temp.act(IMP2);  perform public.ensure_profile('Taklitçi2');

    -- ---------------- default plan ----------------
    perform pg_temp.act(G1);
    v := public.get_my_plan();
    perform pg_temp.check(v->>'plan' = 'free' and not (v->>'is_admin')::boolean and (v->>'google_linked')::boolean, 'G1 starts free, google linked');
    perform pg_temp.act(GUEST);
    v := public.get_my_plan();
    perform pg_temp.check(v->>'plan' = 'free' and not (v->>'google_linked')::boolean, 'guest is free, not linked');

    -- ---------------- admin allowlist ----------------
    -- Nobody is admin before the console adds the address.
    perform pg_temp.act(ADM);
    perform pg_temp.check((public.get_my_plan()->>'plan') = 'free', 'ADM free before allowlist');
    perform pg_temp.expect_error($q$select public.admin_grant_plus('10000000-0000-0000-0000-0000000000a1', 3)$q$, 'LOOPA:NOT_ADMIN');

    -- Console (dashboard SQL editor, no JWT) adds the address.
    perform pg_temp.act(null);
    perform public._admin_allowlist_add(' BOSS@loopa-test.invalid ', 'test');
    perform pg_temp.check((select count(*) from public.admin_audit_log where action = 'allowlist_add') = 1, 'allowlist add logged');
    perform pg_temp.check(not exists (select 1 from public.admin_audit_log where details::text like '%boss@%'), 'audit log holds no plain e-mail');

    -- Same address without a (verified) Google identity: not admin, and does not bind.
    perform pg_temp.act(IMP);
    perform pg_temp.check((public.get_my_plan()->>'plan') = 'free', 'non-Google identity with admin e-mail is not admin');
    perform pg_temp.act(IMP2);
    perform pg_temp.check((public.get_my_plan()->>'plan') = 'free', 'unverified Google e-mail is not admin');
    perform pg_temp.check((select bound_user_id from public.admin_allowlist where email = 'boss@loopa-test.invalid') is null, 'not bound yet');

    -- The verified Google account claims it; the e-mail is now bound to that user id.
    perform pg_temp.act(ADM);
    v := public.get_my_plan();
    perform pg_temp.check(v->>'plan' = 'admin' and (v->>'is_admin')::boolean, 'ADM is admin');
    perform pg_temp.check((select bound_user_id from public.admin_allowlist where email = 'boss@loopa-test.invalid') = ADM, 'bound to ADM');

    -- Even if IMP2's Google e-mail became verified later, the entry stays bound to ADM.
    update auth.identities set identity_data = '{"email":"boss@loopa-test.invalid","email_verified":true}' where id = 't-g6';
    perform pg_temp.act(IMP2);
    perform pg_temp.check((public.get_my_plan()->>'plan') = 'free', 'second account cannot take a bound admin entry');

    -- ---------------- clients cannot write or read secrets ----------------
    perform pg_temp.act(G1);
    set local role authenticated;
    perform pg_temp.expect_error($q$insert into public.entitlements(user_id, source, starts_at, ends_at) values (auth.uid(), 'admin', now(), now() + interval '1 day')$q$, 'permission denied');
    perform pg_temp.expect_error($q$insert into public.user_roles(user_id, role) values (auth.uid(), 'admin')$q$, 'permission denied');
    perform pg_temp.expect_error($q$update public.admin_allowlist set bound_user_id = auth.uid()$q$, 'permission denied');
    perform pg_temp.expect_error($q$select * from public.promo_codes$q$, 'permission denied');
    perform pg_temp.expect_error($q$select * from public.admin_allowlist$q$, 'permission denied');
    perform pg_temp.expect_error($q$select * from public.admin_audit_log$q$, 'permission denied');
    perform pg_temp.expect_error($q$select public._grant_plus(auth.uid(), 30, 'admin', null, null)$q$, 'permission denied');
    perform pg_temp.expect_error($q$select public._admin_allowlist_add('x@loopa-test.invalid')$q$, 'permission denied');
    perform pg_temp.expect_error($q$select public._try_claim_admin(auth.uid())$q$, 'permission denied');
    perform pg_temp.check((select count(*) from public.entitlements) = 0, 'G1 sees no entitlements');
    reset role;

    -- ---------------- LOPPAWEEK ----------------
    -- Seeded inactive: cannot be used before it is switched on.
    perform pg_temp.act(G1);
    perform pg_temp.expect_error($q$select public.redeem_promo_code('LOPPAWEEK')$q$, 'LOOPA:PROMO_INVALID');
    -- Non-admins cannot switch it on.
    perform pg_temp.expect_error($q$select public.admin_set_promo_code('LOPPAWEEK', 7, true)$q$, 'LOOPA:NOT_ADMIN');
    perform pg_temp.expect_error($q$select public.admin_set_promo_code('ADMINFOREVER', 90, true)$q$, 'LOOPA:NOT_ADMIN');
    perform pg_temp.act(ADM);
    v := public.admin_set_promo_code('LOPPAWEEK', 7, true);
    perform pg_temp.check((v->>'active')::boolean and (v->>'plus_days')::int = 7, 'LOPPAWEEK active, 7 days');

    -- Wrong spellings are not accepted.
    perform pg_temp.act(G1);
    perform pg_temp.expect_error($q$select public.redeem_promo_code('LOPAWEEK')$q$, 'LOOPA:PROMO_INVALID');
    perform pg_temp.expect_error($q$select public.redeem_promo_code('LOPPA WEEK')$q$, 'LOOPA:PROMO_INVALID');
    perform pg_temp.expect_error($q$select public.redeem_promo_code('LOPPAWEEKK')$q$, 'LOOPA:PROMO_INVALID');
    perform pg_temp.expect_error($q$select public.redeem_promo_code('')$q$, 'LOOPA:PROMO_INVALID');
    perform pg_temp.expect_error($q$select public.redeem_promo_code(null)$q$, 'LOOPA:PROMO_INVALID');

    -- Guests (no Google identity) cannot redeem; nothing is recorded.
    perform pg_temp.act(GUEST);
    perform pg_temp.expect_error($q$select public.redeem_promo_code('LOPPAWEEK')$q$, 'LOOPA:GOOGLE_LINK_REQUIRED');
    perform pg_temp.act(IMP);   -- e-mail account, not Google
    perform pg_temp.expect_error($q$select public.redeem_promo_code('LOPPAWEEK')$q$, 'LOOPA:GOOGLE_LINK_REQUIRED');
    perform pg_temp.check((select count(*) from public.promo_redemptions) = 0, 'no redemption for guests');

    -- Google-linked account: exactly 7 days. Trimmed and case-insensitive input is fine.
    perform pg_temp.act(G1);
    v := public.redeem_promo_code('  loppaweek ');
    perform pg_temp.check(v->>'status' = 'redeemed', 'G1 redeemed');
    perform pg_temp.check((v->>'ends_at')::timestamptz - (v->>'starts_at')::timestamptz = interval '7 days', 'exactly 7 days');
    perform pg_temp.check((v->>'starts_at')::timestamptz = now(), 'starts now when no Plus');
    perform pg_temp.check((public.get_my_plan()->>'plan') = 'plus', 'G1 is plus');

    -- Second use: same result, no second grant (idempotent / once per account).
    v2 := public.redeem_promo_code('LOPPAWEEK');
    perform pg_temp.check(v2->>'status' = 'already_redeemed', 'second use reports already_redeemed');
    perform pg_temp.check(v2->>'plus_until' = v->>'plus_until', 'plus_until unchanged');
    perform pg_temp.check((select count(*) from public.entitlements where user_id = G1) = 1, 'one entitlement');
    perform pg_temp.check((select redemptions from public.promo_codes where code = 'LOPPAWEEK') = 1, 'counter 1');
    -- Still once per account after the code is switched off again.
    perform pg_temp.act(ADM);
    perform public.admin_set_promo_code('LOPPAWEEK', 7, false);
    perform pg_temp.act(G1);
    perform pg_temp.check((public.redeem_promo_code('LOPPAWEEK')->>'status') = 'already_redeemed', 'retry after deactivation');
    perform pg_temp.act(ADM);
    perform public.admin_set_promo_code('LOPPAWEEK', 7, true);

    -- Stacking: an existing Plus is extended at its END, not overlapped.
    perform pg_temp.act(ADM);
    v := public.admin_grant_plus(G2, 10, 'stacking test');
    perform pg_temp.act(G2);
    v2 := public.redeem_promo_code('LOPPAWEEK');
    perform pg_temp.check((v2->>'starts_at')::timestamptz = (v->>'ends_at')::timestamptz, 'promo starts when admin grant ends');
    perform pg_temp.check((v2->>'plus_until')::timestamptz = now() + interval '17 days', '10 + 7 days');
    perform pg_temp.check((public.get_my_plan()->>'plus_until')::timestamptz = now() + interval '17 days', 'get_my_plan agrees');

    -- Validity window is enforced server-side.
    perform pg_temp.act(ADM);
    perform public.admin_set_promo_code('LOPPAWEEK', 7, true, now() - interval '10 days', now() - interval '1 day');
    perform pg_temp.act(ADM);   -- ADM never redeemed: window closed => invalid
    perform pg_temp.expect_error($q$select public.redeem_promo_code('LOPPAWEEK')$q$, 'LOOPA:PROMO_INVALID');
    perform public.admin_set_promo_code('LOPPAWEEK', 7, true, now() + interval '1 day', null);
    perform pg_temp.expect_error($q$select public.redeem_promo_code('LOPPAWEEK')$q$, 'LOOPA:PROMO_INVALID');
    -- Redemption cap.
    perform public.admin_set_promo_code('CAPTEST', 3, true, null, null, 1);
    perform pg_temp.act(G1);
    perform pg_temp.check((public.redeem_promo_code('CAPTEST')->>'status') = 'redeemed', 'cap: first ok');
    perform pg_temp.act(G2);
    perform pg_temp.expect_error($q$select public.redeem_promo_code('CAPTEST')$q$, 'LOOPA:PROMO_EXHAUSTED');
    -- Admin input validation: no codes longer than 90 days, no admin-granting codes exist at all.
    perform pg_temp.act(ADM);
    perform pg_temp.expect_error($q$select public.admin_set_promo_code('BIGCODE', 365, true)$q$, 'LOOPA:INVALID_INPUT');
    perform pg_temp.expect_error($q$select public.admin_set_promo_code('ab', 7, true)$q$, 'LOOPA:INVALID_INPUT');

    -- ---------------- expiry and revocation ----------------
    -- Plus ends exactly when its window ends.
    perform pg_temp.check(public._user_plan(G1, now() + interval '7 days' + interval '3 days' - interval '1 second') = 'plus', 'G1 plus until last second (7 + 3 days)');
    perform pg_temp.check(public._user_plan(G1, now() + interval '10 days') = 'free', 'G1 free after window');
    -- Admin revocation.
    perform pg_temp.act(ADM);
    select id into eid from public.entitlements where user_id = G2 and source = 'admin';
    perform public.admin_revoke_entitlement(eid, 'test');
    -- The promo window (days 10..17) remains; G2 is free now, plus again on day 10.
    perform pg_temp.check(public._user_plan(G2) = 'free', 'G2 free after revoke');
    perform pg_temp.check(public._user_plan(G2, now() + interval '11 days') = 'plus', 'promo window kept');
    -- Open-ended admin grant.
    perform public.admin_grant_plus(G2, null, 'unlimited');
    perform pg_temp.act(G2);
    v := public.get_my_plan();
    perform pg_temp.check(v->>'plan' = 'plus' and (v->>'plus_unlimited')::boolean and v->'plus_until' = 'null'::jsonb, 'unlimited plus');

    -- ---------------- admin revocation ----------------
    perform pg_temp.act(null);
    perform public._admin_allowlist_revoke('boss@loopa-test.invalid');
    perform pg_temp.act(ADM);
    perform pg_temp.check((public.get_my_plan()->>'plan') = 'free', 'ADM no longer admin');
    perform pg_temp.expect_error($q$select public.admin_grant_plus('10000000-0000-0000-0000-0000000000a1', 3)$q$, 'LOOPA:NOT_ADMIN');
    -- Re-adding restores the same bound account only.
    perform pg_temp.act(null);
    perform public._admin_allowlist_add('boss@loopa-test.invalid');
    perform pg_temp.act(ADM);
    perform pg_temp.check((public.get_my_plan()->>'plan') = 'admin', 'ADM admin again');

    -- Every admin action was logged.
    select count(*) into n from public.admin_audit_log where actor_id = ADM and actor_kind = 'admin';
    perform pg_temp.check(n >= 8, 'admin actions logged (' || n || ')');
    perform pg_temp.check(exists (select 1 from public.admin_audit_log where action = 'grant_plus' and target_user = G2), 'grant logged');
    perform pg_temp.check(exists (select 1 from public.admin_audit_log where action = 'revoke_entitlement'), 'revoke logged');

    raise notice 'plus tests OK';
end $$;

rollback;
