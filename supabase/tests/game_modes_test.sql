-- =====================================================================
-- LOOPA — game mode catalogue, weekly rotation and host gating (stage 2c)
-- Runs inside one transaction that is rolled back: safe on any database.
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
-- Istanbul wall-clock time -> timestamptz
create or replace function pg_temp.ist(p text) returns timestamptz language sql as $$
    select (p::timestamp) at time zone 'Europe/Istanbul';
$$;

insert into auth.users(id, email, is_anonymous) values
 ('20000000-0000-0000-0000-0000000000f1', null, true),                    -- FREE host
 ('20000000-0000-0000-0000-0000000000f2', null, true),                    -- FREE player
 ('20000000-0000-0000-0000-0000000000f3', 'p@loopa-test.invalid', false), -- PLUS host
 ('20000000-0000-0000-0000-0000000000f4', 'a@loopa-test.invalid', false); -- ADMIN
insert into auth.identities(id, user_id, provider, identity_data) values
 ('m-f3', '20000000-0000-0000-0000-0000000000f3', 'google', '{"email":"p@loopa-test.invalid","email_verified":true}'),
 ('m-f4', '20000000-0000-0000-0000-0000000000f4', 'google', '{"email":"a@loopa-test.invalid","email_verified":true}');

do $$
declare
    FH uuid := '20000000-0000-0000-0000-0000000000f1';
    FP uuid := '20000000-0000-0000-0000-0000000000f2';
    PH uuid := '20000000-0000-0000-0000-0000000000f3';
    AD uuid := '20000000-0000-0000-0000-0000000000f4';
    v jsonb; room uuid; code text; m uuid; s jsonb; mon date; n int; mm public.matches;
begin
    perform pg_temp.act(FH); perform public.ensure_profile('Bedava Host');
    perform pg_temp.act(FP); perform public.ensure_profile('Bedava Oyuncu');
    perform pg_temp.act(PH); perform public.ensure_profile('Plus Host');
    perform pg_temp.act(AD); perform public.ensure_profile('Mod Admin');
    perform pg_temp.act(null);
    perform public._admin_allowlist_add('a@loopa-test.invalid', 'test');
    perform pg_temp.act(AD);
    perform pg_temp.check((public.get_my_plan()->>'plan') = 'admin', 'AD admin');
    perform public.admin_grant_plus(PH, 30, 'test');

    -- ---------------- catalogue ----------------
    perform pg_temp.check((select count(*) from public.game_modes) = 10, '10 modes');
    perform pg_temp.check((select count(*) from public.game_modes where always_free) = 1
                          and (select always_free from public.game_modes where id = 'isim_sehir'), 'only İsim Şehir always free');
    perform pg_temp.check(not exists (select 1 from public.mode_rotation where mode_id = 'isim_sehir'), 'İsim Şehir not in rotation');
    perform pg_temp.check((select count(distinct mode_id) from public.mode_rotation) = 9, 'all 9 other modes rotate');
    perform pg_temp.check((select count(*) from public.mode_rotation group by mode_id order by 1 desc limit 1) = 1, 'each mode once per cycle');

    -- ---------------- rotation not started (anchor null) ----------------
    perform pg_temp.check(public._rotation_slot(now()) is null, 'no slot before anchor is set');
    perform pg_temp.act(FH);
    v := public.get_game_catalog();
    perform pg_temp.check(not (v#>>'{rotation,active}')::boolean and v#>'{rotation,free_modes}' = '[]'::jsonb, 'rotation inactive');
    perform pg_temp.check((select (x#>>'{access,allowed}')::boolean from jsonb_array_elements(v->'modes') x where x->>'id' = 'isim_sehir'), 'free host may host İsim Şehir');
    perform pg_temp.check((select x#>>'{access,reason}' from jsonb_array_elements(v->'modes') x where x->>'id' = 'gizli_kelime') = 'PLUS_REQUIRED', 'rotation not started: other games need Plus');
    -- 'coming_soon' still locks a game for everybody but admins (status set by an admin below).

    -- ---------------- week arithmetic (Monday 00:00 Europe/Istanbul) ----------------
    perform pg_temp.act(AD);
    perform pg_temp.expect_error($q$select public.admin_set_rotation_anchor('2026-11-11')$q$, 'LOOPA:ANCHOR_NOT_MONDAY');
    perform public.admin_set_rotation_anchor('2026-11-16');        -- a Monday
    perform pg_temp.check(public._rotation_slot(pg_temp.ist('2026-11-15 23:59:59')) is null, 'before week 1: none');
    perform pg_temp.check(public._rotation_slot(pg_temp.ist('2026-11-16 00:00:00')) = 1, 'week 1 starts Monday 00:00 Istanbul');
    perform pg_temp.check(public._rotation_slot(pg_temp.ist('2026-11-22 23:59:59')) = 1, 'Sunday 23:59 still week 1');
    perform pg_temp.check(public._rotation_slot(pg_temp.ist('2026-11-23 00:00:00')) = 2, 'next Monday week 2');
    -- 2026-11-22 21:30 UTC = Monday 00:30 in Istanbul: Istanbul time decides, not UTC.
    perform pg_temp.check(public._rotation_slot('2026-11-22 21:30:00+00') = 2, 'Istanbul, not UTC');
    perform pg_temp.check(public._rotation_slot(pg_temp.ist('2026-12-14 12:00')) = 5, 'week 5');
    perform pg_temp.check(public._rotation_slot(pg_temp.ist('2026-12-21 00:00')) = 1, 'cycle repeats after 5 weeks');
    perform pg_temp.check(public._rotation_slot(pg_temp.ist('2027-11-15 12:00')) = (52 % 5) + 1, 'a year later');
    perform pg_temp.check(public._istanbul_week_start(pg_temp.ist('2026-11-19 15:00')) = pg_temp.ist('2026-11-16 00:00'), 'week start');

    -- Week 1 = this week, so the current week is slot 1.
    mon := (public._istanbul_week_start(now()) at time zone 'Europe/Istanbul')::date;
    perform public.admin_set_rotation_anchor(mon);
    perform pg_temp.check(public._rotation_slot(now()) = 1, 'current week is 1');
    -- Make two rotating modes playable (engine readiness is separate: see start_match below).
    perform public.admin_set_mode_status('ciz_tahmin', 'available');       -- week 1
    perform public.admin_set_mode_status('gizli_kelime', 'available');     -- week 2
    perform pg_temp.expect_error($q$select public.admin_set_mode_status('isim_sehir', 'disabled')$q$, 'LOOPA:INVALID_INPUT');
    perform pg_temp.act(FH);
    perform pg_temp.expect_error($q$select public.admin_set_mode_status('gizli_kelime', 'available')$q$, 'LOOPA:NOT_ADMIN');
    perform pg_temp.expect_error($q$select public.admin_set_rotation_anchor(null)$q$, 'LOOPA:NOT_ADMIN');

    v := public.get_game_catalog();
    perform pg_temp.check(v#>'{rotation,free_modes}' = '["ciz_tahmin"]'::jsonb, 'week 1 free: ciz_tahmin');
    perform pg_temp.check(v#>'{rotation,next_free_modes}' = '["en_muhtemel_kim", "gizli_kelime"]'::jsonb, 'next week preview');
    perform pg_temp.check((v#>>'{rotation,week_ends_at}')::timestamptz - (v#>>'{rotation,week_starts_at}')::timestamptz = interval '7 days', 'week length');
    perform pg_temp.check(public._mode_access(FH, 'ciz_tahmin')->>'unlocked_by' = 'rotation', 'free host: rotation mode unlocked');
    perform pg_temp.check(public._mode_access(FH, 'gizli_kelime')->>'reason' = 'PLUS_REQUIRED', 'free host: other mode needs Plus');
    perform pg_temp.check(public._mode_access(FH, 'isim_sehir')->>'unlocked_by' = 'always_free', 'İsim Şehir always free');
    perform pg_temp.check(public._mode_access(PH, 'gizli_kelime')->>'unlocked_by' = 'plus', 'plus host: everything');
    perform pg_temp.act(AD); perform public.admin_set_mode_status('anlat_bakalim', 'coming_soon'); perform pg_temp.act(FH);
    perform pg_temp.check(public._mode_access(PH, 'anlat_bakalim')->>'reason' = 'MODE_NOT_AVAILABLE', 'plus host: coming soon still locked');
    perform pg_temp.check(public._mode_access(AD, 'anlat_bakalim')->>'unlocked_by' = 'admin', 'admin may select coming soon (testing)');
    -- Next week the rotation moves on: at week 2 the free host gets gizli_kelime, loses ciz_tahmin.
    perform pg_temp.check((public._mode_access(FH, 'gizli_kelime', now() + interval '7 days')->>'allowed')::boolean, 'week 2 gizli_kelime free');
    perform pg_temp.check(not (public._mode_access(FH, 'ciz_tahmin', now() + interval '7 days')->>'allowed')::boolean, 'week 2 ciz_tahmin locked');
    -- early_access: Plus only, even if it is in the rotation.
    perform pg_temp.act(AD);
    perform public.admin_set_mode_status('ciz_tahmin', 'early_access');
    perform pg_temp.check(public._mode_access(FH, 'ciz_tahmin')->>'reason' = 'PLUS_REQUIRED', 'early access needs Plus');
    perform pg_temp.check((public._mode_access(PH, 'ciz_tahmin')->>'allowed')::boolean, 'early access for Plus');
    perform public.admin_set_mode_status('ciz_tahmin', 'available');

    -- ---------------- rooms ----------------
    -- New rooms are İsim Şehir.
    perform pg_temp.act(FH);
    v := public.create_room(false); room := (v->>'id')::uuid; code := v->>'code';
    select settings into s from public.rooms where id = room;
    perform pg_temp.check(s->>'game_mode' = 'isim_sehir', 'default mode');
    perform pg_temp.act(FP); perform public.join_room(code);
    -- A 0.3.0 client sends settings without game_mode: accepted, mode kept.
    perform pg_temp.act(FH);
    s := public.update_room_settings(room, s - 'game_mode', false);
    perform pg_temp.check(s->>'game_mode' = 'isim_sehir', 'old client keeps mode');
    -- Free host: locked mode refused, rotation mode accepted.
    perform pg_temp.expect_error(format($q$select public.update_room_settings(%L, %L::jsonb, false)$q$, room, s || '{"game_mode":"gizli_kelime"}'), 'LOOPA:PLUS_REQUIRED');
    perform pg_temp.expect_error(format($q$select public.update_room_settings(%L, %L::jsonb, false)$q$, room, s || '{"game_mode":"anlat_bakalim"}'), 'LOOPA:MODE_NOT_AVAILABLE');
    perform pg_temp.expect_error(format($q$select public.update_room_settings(%L, %L::jsonb, false)$q$, room, s || '{"game_mode":"tavla"}'), 'LOOPA:MODE_NOT_FOUND');
    s := public.update_room_settings(room, s || '{"game_mode":"ciz_tahmin"}', false);
    perform pg_temp.check(s->>'game_mode' = 'ciz_tahmin', 'rotation mode selected');
    perform public.update_room_settings(room, s, true);   -- public, so it is listed
    perform pg_temp.check((select x->>'game_mode' from jsonb_array_elements(public.list_public_rooms()) x
                            where (x->>'id')::uuid = room) = 'ciz_tahmin', 'public list shows the mode');
    perform public.update_room_settings(room, s, false);
    -- Çiz ve Tahmin Et needs 3 players => cannot start with 2, and nothing is created.
    perform pg_temp.act(FP); perform public.set_ready(room, true);
    perform pg_temp.act(FH);
    perform pg_temp.expect_error(format($q$select public.start_match(%L)$q$, room), 'LOOPA:NOT_ENOUGH_PLAYERS');
    perform pg_temp.check(not exists (select 1 from public.matches where room_id = room), 'no match created');
    perform pg_temp.check((select status from public.rooms where id = room) = 'lobby', 'room still in lobby');

    -- Back to İsim Şehir: the existing game starts normally and stores a snapshot.
    s := public.update_room_settings(room, s || '{"game_mode":"isim_sehir"}', false);
    perform pg_temp.act(FP); perform public.set_ready(room, true);
    perform pg_temp.act(FH);
    m := public.start_match(room);
    select * into mm from public.matches where id = m;
    perform pg_temp.check(mm.game_mode = 'isim_sehir', 'match game_mode');
    perform pg_temp.check(mm.entitlement_snapshot->>'unlocked_by' = 'always_free'
                          and mm.entitlement_snapshot->>'plan' = 'free'
                          and (mm.entitlement_snapshot->>'host_id')::uuid = FH, 'snapshot: free host, always free');
    perform pg_temp.check(mm.settings->>'game_mode' = 'isim_sehir', 'settings snapshot carries mode');
    perform pg_temp.check((select count(*) from public.rounds where match_id = m) = 1, 'first round started');
    perform public.close_room(room);

    -- Plus host room: gizli_kelime selectable; start passes the plan check and stops at the player minimum (3).
    perform pg_temp.act(PH);
    v := public.create_room(false); room := (v->>'id')::uuid; code := v->>'code';
    select settings into s from public.rooms where id = room;
    s := public.update_room_settings(room, s || '{"game_mode":"gizli_kelime"}', false);
    perform pg_temp.act(FP); perform public.join_room(code); perform public.set_ready(room, true);
    perform pg_temp.act(PH);
    perform pg_temp.expect_error(format($q$select public.start_match(%L)$q$, room), 'LOOPA:NOT_ENOUGH_PLAYERS');
    -- Host transferred to a Free player: they keep the room but cannot start the locked mode
    -- and may switch it back to an unlocked one.
    perform public.transfer_host(room, FP);
    perform pg_temp.act(FP);
    perform pg_temp.expect_error(format($q$select public.start_match(%L)$q$, room), 'LOOPA:PLUS_REQUIRED');
    s := public.update_room_settings(room, s, false);   -- other settings may still change
    perform pg_temp.check(s->>'game_mode' = 'gizli_kelime', 'mode kept on unrelated change');
    s := public.update_room_settings(room, s || '{"game_mode":"isim_sehir"}', false);
    perform pg_temp.check(s->>'game_mode' = 'isim_sehir', 'free host switched back');
    -- When the Plus host's Plus ends, the same check applies to them.
    perform pg_temp.act(AD);
    update public.entitlements set ends_at = now() + interval '1 second', starts_at = now() - interval '1 day' where user_id = PH;
    perform pg_temp.check(public._mode_access(PH, 'gizli_kelime', now() + interval '2 seconds')->>'reason' = 'PLUS_REQUIRED', 'expired Plus host locked');

    -- Clients cannot change modes or the rotation directly.
    perform pg_temp.act(FH);
    set local role authenticated;
    perform pg_temp.expect_error($q$update public.game_modes set status = 'available'$q$, 'permission denied');
    perform pg_temp.expect_error($q$update public.rotation_settings set anchor_week = null$q$, 'permission denied');
    perform pg_temp.expect_error($q$insert into public.mode_rotation values (1, 'gizli_kelime')$q$, 'permission denied');
    perform pg_temp.expect_error($q$update public.matches set entitlement_snapshot = '{}'$q$, 'permission denied');
    perform pg_temp.check((select count(*) from public.game_modes) = 10, 'catalogue readable');
    reset role;

    raise notice 'game mode tests OK';
end $$;

rollback;
