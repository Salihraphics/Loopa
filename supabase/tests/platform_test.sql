-- =====================================================================
-- LOOPA 0.8.0 — platform: coins, streaks, anti-farming, quests, shop, badges,
-- leaderboards, teams, preference wheel, fair teams, room setup flow, RLS.
-- One transaction, rolled back.
-- =====================================================================
\set ON_ERROR_STOP 1
set client_min_messages = warning;
begin;

create or replace function pg_temp.act(p uuid) returns void language sql as $$
    select set_config('request.jwt.claim.sub', coalesce(p::text, ''), true);
$$;
create or replace function pg_temp.expect_error(p_sql text, p_code text) returns void language plpgsql as $$
declare ok boolean := false;
begin
    begin
        execute p_sql;
        ok := true;                      -- reached only when the statement did NOT fail
    exception when others then
        if sqlerrm not like '%' || p_code || '%' then
            raise exception 'expected error % but got: % (statement: %)', p_code, sqlerrm, p_sql;
        end if;
    end;
    if ok then raise exception 'expected error % but the statement succeeded: %', p_code, p_sql; end if;
end $$;
create or replace function pg_temp.check(ok boolean, what text) returns void language plpgsql as $$
begin
    if not coalesce(ok, false) then raise exception 'CHECK FAILED: %', what; end if;
end $$;
-- A finished match of p_mode in p_room with ranks (array of users in finishing order), p_secs long.
create or replace function pg_temp.play(p_room uuid, p_mode text, p_order uuid[], p_secs int default 600, p_sides int[] default null)
returns uuid language plpgsql as $$
declare m uuid; i int;
begin
    insert into public.matches(room_id, settings, total_rounds, game_mode, started_at)
    values (p_room, '{}'::jsonb, 1, p_mode, now() - make_interval(secs => p_secs)) returning id into m;
    for i in 1..array_length(p_order, 1) loop
        insert into public.match_players(match_id, room_id, user_id, final_rank, total_points, xp_earned, team_side)
        values (m, p_room, p_order[i], case when p_sides is not null and p_sides[i] = p_sides[1] then 1 else i end,
                100 - i, 100, case when p_sides is null then null else p_sides[i] end);
    end loop;
    -- what _finish_match does before it marks the rewards as processed
    update public.player_statistics ps set matches_played = matches_played + 1,
           match_wins = match_wins + (case when mp.final_rank = 1 then 1 else 0 end)
      from public.match_players mp where mp.match_id = m and ps.user_id = mp.user_id;
    update public.matches set status = 'finished', finished_at = now(), rewards_processed = true where id = m;
    return m;
end $$;
create or replace function pg_temp.coins(p uuid) returns bigint language sql as $$
    select coalesce((select coins from public.wallets where user_id = p), 0);
$$;

insert into auth.users(id, is_anonymous)
select ('30000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid, true from generate_series(1, 8) i;

do $$
declare
    A uuid := '30000000-0000-0000-0000-000000000001'; B uuid := '30000000-0000-0000-0000-000000000002';
    C uuid := '30000000-0000-0000-0000-000000000003'; D uuid := '30000000-0000-0000-0000-000000000004';
    E uuid := '30000000-0000-0000-0000-000000000005'; F uuid := '30000000-0000-0000-0000-000000000006';
    room jsonb; rid uuid; m uuid; v jsonb; i int; c0 bigint; t jsonb; q record;
begin
    perform pg_temp.act(A); perform public.ensure_profile('Ekonomi A');
    perform pg_temp.act(B); perform public.ensure_profile('Ekonomi B');
    perform pg_temp.act(C); perform public.ensure_profile('Ekonomi C');
    perform pg_temp.act(D); perform public.ensure_profile('Ekonomi D');
    perform pg_temp.act(E); perform public.ensure_profile('Ekonomi E');
    perform pg_temp.act(F); perform public.ensure_profile('Ekonomi F');
    -- the anti-farming caps get their own section; keep them out of the way here
    update public.economy_config set value = '{"daily_rewarded_matches": 99, "same_opponents_daily": 99}' where key = 'anti_farm';
    perform pg_temp.act(A); room := public.create_room(false); rid := (room->>'id')::uuid;
    perform pg_temp.act(B); perform public.join_room(room->>'code');
    perform pg_temp.act(C); perform public.join_room(room->>'code');

    -- ------------------------------------------------------------ coins per class, once per match
    m := pg_temp.play(rid, 'bilgi_arenasi', array[A, B, C]);                 -- ffa: 300 + 300 win
    perform pg_temp.check(pg_temp.coins(A) = 600 and pg_temp.coins(B) = 300 and pg_temp.coins(C) = 300, 'ffa coins');
    perform public._progress_match(m);                                       -- replay: no double grant
    perform pg_temp.check(pg_temp.coins(A) = 600, 'no double reward');
    perform pg_temp.check((select count(*) from public.coin_transactions where user_id = A) = 1, 'one ledger row');
    perform pg_temp.check((select balance_after from public.coin_transactions where user_id = A) = 600, 'ledger balance');
    m := pg_temp.play(rid, 'kelime_zinciri', array[B, A, C]);                -- word: 300 + 250
    perform pg_temp.check(pg_temp.coins(B) = 300 + 550, 'word win coins');
    -- losses never take anything
    perform pg_temp.check(pg_temp.coins(A) = 900 and pg_temp.coins(C) = 600, 'loss gives participation only');

    -- ------------------------------------------------------------ invalid matches
    c0 := pg_temp.coins(A);
    m := pg_temp.play(rid, 'bilgi_arenasi', array[A, B, C], 30);             -- too short (min 120 s)
    perform pg_temp.check(pg_temp.coins(A) = c0, 'too short: no coins');
    perform pg_temp.check((select reason from public.match_rewards where match_id = m and user_id = A) = 'too_short', 'reason recorded');
    update public.room_players set status = 'left' where room_id = rid and user_id = C;
    c0 := pg_temp.coins(C);
    m := pg_temp.play(rid, 'bilgi_arenasi', array[C, A, B]);                 -- C left before the end
    perform pg_temp.check(pg_temp.coins(C) = c0, 'left player: no coins');
    update public.room_players set status = 'active' where room_id = rid and user_id = C;

    -- ------------------------------------------------------------ streaks and multiplier
    update public.player_streaks set current = 0 where user_id = A;
    perform pg_temp.play(rid, 'bilgi_arenasi', array[A, B, C]);
    perform pg_temp.play(rid, 'bilgi_arenasi', array[A, B, C]);
    perform pg_temp.check((select current from public.player_streaks where user_id = A) = 2, 'streak 2');
    c0 := pg_temp.coins(A);
    m := pg_temp.play(rid, 'bilgi_arenasi', array[A, B, C]);                 -- 3rd win: 1.10x
    perform pg_temp.check(pg_temp.coins(A) - c0 = 660, 'streak 3 multiplier 1.10 (600 -> 660)');
    perform pg_temp.check((select xp_bonus from public.match_rewards where match_id = m and user_id = A) = 10, 'streak xp bonus');
    perform pg_temp.check((select best from public.player_streaks where user_id = A) >= 3, 'best streak kept');
    perform pg_temp.play(rid, 'bilgi_arenasi', array[B, A, C]);
    perform pg_temp.check((select current from public.player_streaks where user_id = A) = 0, 'loss resets streak');
    perform pg_temp.check((select best from public.player_streaks where user_id = A) >= 3, 'best survives reset');
    perform pg_temp.check(public._streak_mult('streak_individual', 16) = 1.50 and public._streak_mult('streak_team', 11) = 1.60, 'tables');

    -- ------------------------------------------------------------ anti-farming: same opponents per day
    update public.economy_config set value = '{"daily_rewarded_matches": 40, "same_opponents_daily": 2}' where key = 'anti_farm';
    delete from public.match_rewards where user_id in (A, B, C);
    perform pg_temp.play(rid, 'bilgi_arenasi', array[A, B, C]);
    perform pg_temp.play(rid, 'bilgi_arenasi', array[A, B, C]);
    c0 := pg_temp.coins(A);
    m := pg_temp.play(rid, 'bilgi_arenasi', array[A, B, C]);
    perform pg_temp.check(pg_temp.coins(A) = c0 and (select reason from public.match_rewards where match_id = m and user_id = A) = 'same_opponents',
                          'same opponents capped');
    update public.economy_config set value = '{"daily_rewarded_matches": 1, "same_opponents_daily": 99}' where key = 'anti_farm';
    m := pg_temp.play(rid, 'bilgi_arenasi', array[A, B, C]);
    perform pg_temp.check((select reason from public.match_rewards where match_id = m and user_id = A) = 'daily_cap', 'daily cap');
    update public.economy_config set value = '{"daily_rewarded_matches": 99, "same_opponents_daily": 99}' where key = 'anti_farm';
    delete from public.match_rewards where user_id in (A, B, C);

    -- ------------------------------------------------------------ quests
    perform pg_temp.act(D);
    v := public.get_my_quests();
    perform pg_temp.check(jsonb_array_length(v->'quests') = 6, '3 daily + 3 weekly quests');
    -- force a known daily quest for D
    delete from public.player_quests where user_id = D;
    insert into public.player_quests(user_id, period_key, quest_id, target) values (D, public._day_key(), 'd_win_1', 1),
        (D, public._day_key(), 'd_play_3_kinds', 3), (D, public._week_key(), 'w_play_15', 15);
    perform pg_temp.expect_error('select public.claim_quest(''d_win_1'')', 'QUEST_NOT_COMPLETE');
    perform pg_temp.act(A); room := public.create_room(false);
    perform pg_temp.act(D); perform public.join_room(room->>'code');
    perform pg_temp.act(E); perform public.join_room(room->>'code');
    perform pg_temp.play((room->>'id')::uuid, 'bilgi_arenasi', array[D, A, E]);
    perform pg_temp.play((room->>'id')::uuid, 'kelime_zinciri', array[A, D, E]);
    perform pg_temp.play((room->>'id')::uuid, 'kelime_zinciri', array[A, D, E]);   -- same game again: distinct stays 2
    perform pg_temp.check((select progress from public.player_quests where user_id = D and quest_id = 'd_play_3_kinds') = 2, 'distinct games');
    perform pg_temp.check((select progress from public.player_quests where user_id = D and quest_id = 'w_play_15') = 3, 'weekly progress');
    perform pg_temp.act(D);
    c0 := pg_temp.coins(D);
    v := public.claim_quest('d_win_1');
    perform pg_temp.check(v->>'status' = 'claimed' and pg_temp.coins(D) = c0 + 600, 'quest reward');
    perform pg_temp.check(public.claim_quest('d_win_1')->>'status' = 'already_claimed' and pg_temp.coins(D) = c0 + 600, 'claim once');
    perform pg_temp.check(exists (select 1 from public.xp_transactions where user_id = D and reason = 'quest'), 'quest xp');

    -- ------------------------------------------------------------ shop
    perform pg_temp.act(E);
    update public.profiles set level = 2 where id = E;
    perform pg_temp.expect_error('select public.purchase_cosmetic(''frame_sunset'')', 'LEVEL_TOO_LOW');
    update public.profiles set level = 10 where id = E;
    perform pg_temp.expect_error('select public.purchase_cosmetic(''acc_star_pin'')', 'INSUFFICIENT_COINS');
    perform pg_temp.check(pg_temp.coins(E) > 0 and pg_temp.coins(E) < 5000, 'balance untouched on failure');
    perform public._coins_grant(E, 3000, 'admin', 'test:e');
    c0 := pg_temp.coins(E);
    v := public.purchase_cosmetic('frame_sunset');
    perform pg_temp.check(v->>'status' = 'purchased' and pg_temp.coins(E) = c0 - 2500, 'purchase debits');
    perform pg_temp.check(public.purchase_cosmetic('frame_sunset')->>'status' = 'already_owned' and pg_temp.coins(E) = c0 - 2500, 'no double charge');
    perform pg_temp.expect_error('select public.unlock_cosmetic(''frame_ocean'')', 'PURCHASE_REQUIRED');
    perform pg_temp.expect_error('select public.purchase_cosmetic(''hair_short'')', 'NOT_FOR_SALE');
    perform pg_temp.check(public._coins_spend(E, 1, 'purchase', 'purchase:frame_sunset') = pg_temp.coins(E), 'replayed spend key is a no-op');

    -- ------------------------------------------------------------ badges / showcase
    perform pg_temp.check(exists (select 1 from public.player_badges where user_id = A and badge_id = 'first_win'), 'first_win unlocked');
    perform pg_temp.check(exists (select 1 from public.player_badges where user_id = A and badge_id = 't_main_character'), 'streak title');
    perform pg_temp.act(A);
    perform public.set_showcase('t_main_character', array['first_win']);
    perform pg_temp.expect_error('select public.set_showcase(''t_efsane'', null)', 'BADGE_LOCKED');
    perform pg_temp.expect_error('select public.set_showcase(null, array[''champion''])', 'BADGE_LOCKED');
    v := public.get_progression(null);
    perform pg_temp.check(v->'showcase'->'title'->>'id' = 't_main_character' and (v->>'coins')::int > 0, 'progression');
    perform pg_temp.act(B);
    perform pg_temp.check(public.get_progression(A)->'coins' = 'null'::jsonb, 'others do not see your coins');
    perform pg_temp.check(public._prestige_of(99) = 0 and public._prestige_of(100) = 1 and public._prestige_of(150) = 3, 'prestige');

    -- ------------------------------------------------------------ leaderboards
    perform pg_temp.act(A);
    v := public.get_leaderboard('weekly', 3, 0);
    perform pg_temp.check(jsonb_array_length(v->'rows') <= 3 and (v->'me'->>'rank')::int >= 1, 'weekly board');
    perform pg_temp.check((v->'rows'->0->>'score')::bigint >= (v->'rows'->1->>'score')::bigint, 'ordered');
    update public.profiles set xp = 5000 where id in (B, C);
    v := public.get_leaderboard('total', 10, 0);
    perform pg_temp.check((v->'rows'->0->>'rank')::int = 1 and (v->'rows'->1->>'rank')::int = 1, 'ties share a rank');
    perform pg_temp.expect_error('select public.get_leaderboard(''coins'')', 'INVALID_INPUT');

    -- ------------------------------------------------------------ teams + team streak (best multiplier, never stacked)
    perform pg_temp.act(A); perform public.leave_team() where false;
    t := public.create_team('Mor Kurtlar');
    perform pg_temp.act(B); perform public.join_team(t->>'join_code');
    perform pg_temp.expect_error(format('select public.join_team(%L)', t->>'join_code'), 'ALREADY_IN_TEAM');
    update public.teams set current_streak = 2 where id = (t->>'id')::uuid;
    perform pg_temp.act(A); room := public.create_room(false);
    perform pg_temp.act(B); perform public.join_room(room->>'code');
    perform pg_temp.act(C); perform public.join_room(room->>'code');
    perform pg_temp.act(D); perform public.join_room(room->>'code');
    update public.player_streaks set current = 0 where user_id in (A, B);
    c0 := pg_temp.coins(A);
    m := pg_temp.play((room->>'id')::uuid, 'gizli_kelime', array[A, B, C, D], 600, array[1, 1, 2, 2]);   -- team class 350 + 300
    perform pg_temp.check((select current_streak from public.teams where id = (t->>'id')::uuid) = 3, 'team streak +1 once');
    perform pg_temp.check(pg_temp.coins(A) - c0 = round(650 * 1.15), 'team multiplier 1.15 (best, not stacked)');
    perform pg_temp.check((select current from public.player_streaks where user_id = C) = 0, 'losers individual streak');
    perform pg_temp.act(B); perform public.leave_team();
    perform pg_temp.check((select current_streak from public.teams where id = (t->>'id')::uuid) = 0, 'leaving resets team streak');
    perform pg_temp.check((select best_streak from public.teams where id = (t->>'id')::uuid) = 3, 'team record kept');

    -- ------------------------------------------------------------ fair teams
    v := public._balance('[{"id":"a","skill":12},{"id":"b","skill":3},{"id":"c","skill":7},{"id":"d","skill":8},{"id":"e","skill":4},{"id":"f","skill":2}]', 2);
    perform pg_temp.check((v->0->>'strength')::numeric = 18 and (v->1->>'strength')::numeric = 18, 'example from the spec: 18 / 18');
    perform pg_temp.check(v = public._balance('[{"id":"f","skill":2},{"id":"a","skill":12},{"id":"c","skill":7},{"id":"b","skill":3},{"id":"e","skill":4},{"id":"d","skill":8}]', 2),
                          'reproducible for the same input');
    v := public._balance('[{"id":"a","skill":10},{"id":"b","skill":9},{"id":"c","skill":8},{"id":"d","skill":7},{"id":"e","skill":6},{"id":"f","skill":5},{"id":"g","skill":4}]', 3);
    perform pg_temp.check(jsonb_array_length(v) = 3 and (select max(jsonb_array_length(x->'members')) - min(jsonb_array_length(x->'members')) from jsonb_array_elements(v) x) <= 1, '3 teams sizes');
    perform pg_temp.act(A);
    v := public.balance_room_teams((room->>'id')::uuid, 2);
    perform pg_temp.check(jsonb_array_length(v) = 2, 'room teams');
    perform pg_temp.act(B);
    perform pg_temp.expect_error(format('select public.balance_room_teams(%L, 2)', room->>'id'), 'NOT_HOST');

    -- ------------------------------------------------------------ preferences + wheel
    perform pg_temp.act(B); perform public.set_game_prefs(array['word'], array['drawing']);
    perform pg_temp.act(C); perform public.set_game_prefs(array['word','quiz'], array['drawing']);
    perform pg_temp.act(D); perform public.set_game_prefs(array[]::text[], array['drawing']);
    perform pg_temp.expect_error('select public.set_game_prefs(array[''word''], array[''word''])', 'INVALID_INPUT');
    perform pg_temp.expect_error('select public.set_game_prefs(array[''meme''], null)', 'INVALID_INPUT');
    update public.game_modes set status = 'available' where id in ('isim_sehir','ciz_tahmin','kelime_zinciri');
    perform public._grant_plus(A, 7, 'admin', 'test', A);          -- the host may host every game
    v := public._wheel_candidates((room->>'id')::uuid);
    perform pg_temp.check((select (x->>'weight')::numeric from jsonb_array_elements(v) x where x->>'mode' = 'ciz_tahmin')
                          < (select (x->>'weight')::numeric from jsonb_array_elements(v) x where x->>'mode' = 'isim_sehir'),
                          'disliked game weighs less than a liked one');
    perform pg_temp.act(A);
    v := public.spin_game_wheel((room->>'id')::uuid);
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(v->'candidates') x where x->>'mode' = v->>'result'), 'result is a candidate');
    perform pg_temp.check((select settings->>'game_mode' from public.rooms where id = (room->>'id')::uuid) = v->>'result', 'room switched');
    perform pg_temp.check((select (settings->>'configured')::boolean from public.rooms where id = (room->>'id')::uuid) = false, 'setup reopened');
    perform pg_temp.act(C);
    perform pg_temp.check(public.get_last_wheel_spin((room->>'id')::uuid)->>'result' = v->>'result', 'everybody sees the same result');
    perform pg_temp.expect_error(format('select public.spin_game_wheel(%L)', room->>'id'), 'NOT_HOST');

    -- ------------------------------------------------------------ setup flow
    perform pg_temp.expect_error(format('select public.set_ready(%L, true)', room->>'id'), 'SETUP_NOT_DONE');
    perform pg_temp.act(A);
    perform pg_temp.expect_error(format('select public.start_match(%L)', room->>'id'), 'SETUP_NOT_DONE');
    v := (select settings from public.rooms where id = (room->>'id')::uuid);
    perform public.update_room_settings((room->>'id')::uuid, v || '{"configured": true}', null);
    perform pg_temp.act(C); perform public.set_ready((room->>'id')::uuid, true);
    perform pg_temp.check((select is_ready from public.room_players where room_id = (room->>'id')::uuid and user_id = C), 'ready after setup');
    -- old clients (no flag) keep the old behaviour
    perform pg_temp.act(A);
    perform public.update_room_settings((room->>'id')::uuid, (v - 'configured') || '{"game_mode":"isim_sehir"}', null);
    perform pg_temp.check((select (settings->>'configured')::boolean from public.rooms where id = (room->>'id')::uuid), 'no flag = configured');
end $$;

-- ---------------------------------------------------------------------- RLS / grants
do $$
declare A uuid := '30000000-0000-0000-0000-000000000001'; B uuid := '30000000-0000-0000-0000-000000000002'; n int;
begin
    perform pg_temp.act(B);
    set local role authenticated;
    select count(*) into n from public.wallets where user_id <> B;
    perform pg_temp.check(n = 0, 'RLS: only own wallet');
    select count(*) into n from public.coin_transactions where user_id <> B;
    perform pg_temp.check(n = 0, 'RLS: only own ledger');
    begin
        update public.wallets set coins = 999999 where user_id = B;
        raise exception 'CHECK FAILED: client could change its balance';
    exception when insufficient_privilege then null;
    end;
    begin
        insert into public.coin_transactions(user_id, amount, reason, source_key, balance_after) values (B, 100, 'admin', 'x', 100);
        raise exception 'CHECK FAILED: client could write the ledger';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public._coins_grant(B, 100000, 'admin', 'hack');
        raise exception 'CHECK FAILED: client could call _coins_grant';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public._award_xp(B, 100000, 'quest', 'hack', null);
        raise exception 'CHECK FAILED: client could call _award_xp';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public.admin_set_config('match_rewards', '{}');
        raise exception 'CHECK FAILED: non-admin changed config';
    exception when others then
        if sqlerrm not like '%NOT_ADMIN%' then raise; end if;
    end;
    reset role;
    perform pg_temp.check(not has_sequence_privilege('authenticated', pg_get_serial_sequence('public.coin_transactions','id'), 'USAGE'), 'sequence rights');
end $$;

select 'PLATFORM TESTS PASSED' as result;
rollback;
