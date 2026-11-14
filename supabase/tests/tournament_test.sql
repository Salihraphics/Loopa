-- =====================================================================
-- LOOPA 0.8.0 — tournaments: duel / team / ffa, results from server matches, rewards & abuse rules
-- (create_room, update_room_settings, set_ready, start_match, mode_act, get_mode_state).
-- Randomness is pinned by editing the server state where a test needs a known position.
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
create or replace function pg_temp.rd(p_room uuid) returns public.mode_rounds language sql as $$
    select r.* from public.mode_rounds r join public.rooms ro on ro.id = r.room_id
     where r.room_id = p_room order by r.created_at desc, r.round_number desc limit 1;
$$;
create or replace function pg_temp.st(p_room uuid) returns jsonb language sql as $$
    select payload from public.mode_secrets where round_id = (pg_temp.rd(p_room)).id and kind = 'g';
$$;
create or replace function pg_temp.setst(p_room uuid, s jsonb) returns void language plpgsql as $$
begin
    perform public._tg_commit(pg_temp.rd(p_room), s);
end $$;
create or replace function pg_temp.move(p_room uuid, u uuid, k text, p jsonb) returns jsonb language plpgsql as $$
begin
    perform pg_temp.act(u);
    return public.mode_act((pg_temp.rd(p_room)).id, k, p);
end $$;
-- room for p_mode with players (host first), options, everybody ready, started
create or replace function pg_temp.start(p_mode text, p_players uuid[], p_opts jsonb, p_max int default null) returns uuid language plpgsql as $$
declare room jsonb; i int; st jsonb;
begin
    perform pg_temp.act(p_players[1]);
    room := public.create_room(false);
    for i in 2..array_length(p_players, 1) loop perform pg_temp.act(p_players[i]); perform public.join_room(room->>'code'); end loop;
    perform pg_temp.act(p_players[1]);
    st := (select settings from public.rooms where id = (room->>'id')::uuid);
    perform public.update_room_settings((room->>'id')::uuid,
        st || jsonb_build_object('game_mode', p_mode, 'mode_options', p_opts, 'configured', true,
                                 'max_players', coalesce(p_max, (select max_players from public.game_modes where id = p_mode))), null);
    for i in 2..array_length(p_players, 1) loop perform pg_temp.act(p_players[i]); perform public.set_ready((room->>'id')::uuid, true); end loop;
    perform pg_temp.act(p_players[1]);
    perform public.start_match((room->>'id')::uuid);
    return (room->>'id')::uuid;
end $$;

-- plays the current match of a tournament match room: p_winner gets the most points (null = draw)
create or replace function pg_temp.play_tm(p_tm uuid, p_winner uuid, p_order uuid[] default null) returns uuid language plpgsql as $$
declare rid uuid; host uuid; u uuid; m uuid; i int := 0; users uuid[];
begin
    select room_id into rid from public.tournament_matches where id = p_tm;
    users := public._tm_expected_users(p_tm);
    foreach u in array users loop perform pg_temp.act(u); perform public.join_tournament_match(p_tm); end loop;
    select host_id into host from public.rooms where id = rid;
    foreach u in array users loop
        if u <> host then perform pg_temp.act(u); perform public.set_ready(rid, true); end if;
    end loop;
    perform pg_temp.act(host);
    m := public.start_match(rid);
    update public.matches set started_at = now() - interval '20 minutes' where id = m;
    if p_order is not null then
        foreach u in array p_order loop i := i + 1; update public.match_players set total_points = 1000 - i * 10 where match_id = m and user_id = u; end loop;
    elsif p_winner is not null then
        update public.match_players set total_points = case when user_id = p_winner or user_id = any(coalesce(
            (select e.user_ids from public.tournament_entries e join public.tournament_matches tm on tm.id = p_tm
              where e.tournament_id = tm.tournament_id and p_winner = any(e.user_ids) limit 1), '{}')) then 500 else 100 end where match_id = m;
    else
        update public.match_players set total_points = 300 where match_id = m;
    end if;
    perform public._finish_match(m);
    return m;
end $$;

insert into auth.users(id, is_anonymous)
select ('70000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid, true from generate_series(1, 9) i;

do $$
declare
    A uuid := '70000000-0000-0000-0000-000000000001'; B uuid := '70000000-0000-0000-0000-000000000002';
    C uuid := '70000000-0000-0000-0000-000000000003'; D uuid := '70000000-0000-0000-0000-000000000004';
    X uuid := '70000000-0000-0000-0000-000000000005'; F uuid := '70000000-0000-0000-0000-000000000006';
    G uuid := '70000000-0000-0000-0000-000000000007'; H uuid := '70000000-0000-0000-0000-000000000008';
    t jsonb; tid uuid; v jsonb; tm record; fin uuid; champ uuid; c0 bigint; ta jsonb; tb jsonb; m uuid; rid uuid;
begin
    perform pg_temp.act(A); perform public.ensure_profile('Turnuva A'); perform public._grant_plus(A, 30, 'admin', 'test', A);
    perform pg_temp.act(B); perform public.ensure_profile('Turnuva B');
    perform pg_temp.act(C); perform public.ensure_profile('Turnuva C');
    perform pg_temp.act(D); perform public.ensure_profile('Turnuva D');
    perform pg_temp.act(X); perform public.ensure_profile('Davetsiz X');
    perform pg_temp.act(F); perform public.ensure_profile('Turnuva F');
    perform pg_temp.act(G); perform public.ensure_profile('Turnuva G');
    perform pg_temp.act(H); perform public.ensure_profile('Turnuva H');
    update public.economy_config set value = '{"daily_rewarded_matches": 99, "same_opponents_daily": 99}' where key = 'anti_farm';

    -- ============================================================ duel bracket
    perform pg_temp.act(B);
    perform pg_temp.expect_error('select public.create_tournament(''Kupa'', ''yirmi_bir'', ''duel'', 8)', 'PLUS_REQUIRED');
    perform pg_temp.act(A);
    perform pg_temp.expect_error('select public.create_tournament(''Kupa'', ''okey'', ''duel'', 8)', 'FORMAT_NOT_SUPPORTED');
    t := public.create_tournament('Yirmi Bir Kupası', 'yirmi_bir', 'duel', 8, '{"hands": 3}');
    tid := (t->>'id')::uuid;
    perform public.join_tournament(tid);
    perform pg_temp.expect_error(format('select public.join_tournament(%L)', tid), 'ALREADY_REGISTERED');
    perform pg_temp.act(B); perform public.join_tournament(tid);
    perform pg_temp.expect_error(format('select public.start_tournament(%L)', tid), 'NOT_HOST');
    perform pg_temp.act(C); perform public.join_tournament(tid);
    perform pg_temp.act(D); perform public.join_tournament(tid);
    perform pg_temp.act(A);
    v := public.start_tournament(tid);
    perform pg_temp.check(v->>'status' = 'running' and jsonb_array_length(v->'matches') = 2, 'duel: 2 semi-finals');
    perform pg_temp.expect_error(format('select public.join_tournament(%L)', tid), 'REGISTRATION_CLOSED');
    -- outsiders cannot use the match and cannot make it count
    for tm in select * from public.tournament_matches where tournament_id = tid and round = 1 order by slot loop
        perform pg_temp.act(X);
        perform pg_temp.expect_error(format('select public.join_tournament_match(%L)', tm.id), 'NOT_A_PARTICIPANT');
        perform pg_temp.check((select settings->>'game_mode' from public.rooms where id = tm.room_id) = 'yirmi_bir', 'duel: room locked to the game');
    end loop;
    select * into tm from public.tournament_matches where tournament_id = tid and round = 1 and slot = 1;
    rid := tm.room_id;
    perform pg_temp.act(X); perform public.join_room((select code from public.rooms where id = rid));
    perform pg_temp.check((select role from public.room_players where room_id = rid and user_id = X) = 'spectator', 'duel: outsider can only watch');
    foreach champ in array public._tm_expected_users(tm.id) loop perform pg_temp.act(champ); perform public.join_tournament_match(tm.id); end loop;
    perform pg_temp.act((select host_id from public.rooms where id = rid));
    perform pg_temp.expect_error(format('select public.update_room_settings(%L, ''{}''::jsonb, null)', rid), 'TOURNAMENT_ROOM');
    perform pg_temp.act(X); perform public.leave_room(rid);
    -- semi-final 1: a draw is replayed
    m := pg_temp.play_tm(tm.id, null);
    perform pg_temp.check((select status from public.tournament_matches where id = tm.id) = 'playing', 'duel: draw -> replay');
    -- (the room is back in its lobby; play again with a winner)
    champ := (public._tm_expected_users(tm.id))[1];
    m := pg_temp.play_tm(tm.id, champ);
    perform pg_temp.check((select status from public.tournament_matches where id = tm.id) = 'done', 'duel: semi 1 decided');
    select * into tm from public.tournament_matches where tournament_id = tid and round = 1 and slot = 2;
    m := pg_temp.play_tm(tm.id, (public._tm_expected_users(tm.id))[1]);
    -- final created automatically
    select * into tm from public.tournament_matches where tournament_id = tid and round = 2;
    perform pg_temp.check(tm.status = 'playing' and cardinality(tm.entries) = 2, 'duel: final created');
    c0 := coalesce((select coins from public.wallets where user_id = champ), 0);
    m := pg_temp.play_tm(tm.id, champ);
    v := public.get_tournament(tid);
    perform pg_temp.check(v->>'status' = 'finished', 'duel: finished');
    perform pg_temp.check(exists (select 1 from public.tournament_entries where tournament_id = tid and status = 'champion' and champ = any(user_ids)), 'duel: champion');
    perform pg_temp.check(exists (select 1 from public.coin_transactions where user_id = champ and source_key = 'tournament:' || tid and amount = 1500), 'duel: champion coins');
    perform pg_temp.check(exists (select 1 from public.reputation_events where user_id = champ and reason = 'tournament_champion'), 'duel: trophy');
    perform pg_temp.check(exists (select 1 from public.player_badges where user_id = champ and badge_id = 'champion'), 'duel: champion badge');
    perform public._tm_grant(tid);
    perform pg_temp.check((select count(*) from public.coin_transactions where user_id = champ and source_key = 'tournament:' || tid) = 1, 'duel: rewards once');

    -- same four players again within 24 h: no tournament rewards
    perform pg_temp.act(A);
    t := public.create_tournament('Rövanş', 'yirmi_bir', 'duel', 4, '{"hands": 3}'); tid := (t->>'id')::uuid;
    perform public.join_tournament(tid);
    perform pg_temp.act(B); perform public.join_tournament(tid);
    perform pg_temp.act(C); perform public.join_tournament(tid);
    perform pg_temp.act(D); perform public.join_tournament(tid);
    perform pg_temp.act(A); perform public.start_tournament(tid);
    for tm in select * from public.tournament_matches where tournament_id = tid and round = 1 loop
        perform pg_temp.play_tm(tm.id, (public._tm_expected_users(tm.id))[1]);
    end loop;
    select * into tm from public.tournament_matches where tournament_id = tid and round = 2;
    perform pg_temp.play_tm(tm.id, (public._tm_expected_users(tm.id))[1]);
    perform pg_temp.check((select status from public.tournaments where id = tid) = 'finished', 'rematch finished');
    perform pg_temp.check(not exists (select 1 from public.coin_transactions where source_key = 'tournament:' || tid), 'same participants again: no rewards');

    -- forfeit: not before 10 minutes; a forfeit final gives no rewards
    perform pg_temp.act(A);
    t := public.create_tournament('Hızlı', 'yirmi_bir', 'duel', 2); tid := (t->>'id')::uuid;
    perform public.join_tournament(tid);
    perform pg_temp.act(F); perform public.join_tournament(tid);
    perform pg_temp.act(A); perform public.start_tournament(tid);
    select * into tm from public.tournament_matches where tournament_id = tid;
    fin := (select id from public.tournament_entries where tournament_id = tid and F = any(user_ids));
    perform pg_temp.expect_error(format('select public.tournament_forfeit(%L, %L)', tm.id, fin), 'TOO_EARLY');
    update public.tournament_matches set created_at = now() - interval '11 minutes' where id = tm.id;
    perform public.tournament_forfeit(tm.id, fin);
    perform pg_temp.check((select status from public.tournaments where id = tid) = 'finished', 'forfeit: finished');
    perform pg_temp.check(not exists (select 1 from public.coin_transactions where source_key = 'tournament:' || tid), 'forfeit final: no rewards');

    -- ============================================================ team bracket (Amiral Battı 2v2, persistent teams)
    perform pg_temp.act(A); ta := public.create_team('Mavi Filo');
    perform pg_temp.act(B); perform public.join_team(ta->>'join_code');
    perform pg_temp.act(C); tb := public.create_team('Kızıl Filo');
    perform pg_temp.act(A);
    t := public.create_tournament('Filo Kupası', 'amiral_batti', 'team', 4); tid := (t->>'id')::uuid;
    perform pg_temp.check(t->>'team_size' = '2' and t->'mode_options'->>'format' = '2v2', 'team: 2v2 teams');
    perform public.join_tournament(tid);
    perform pg_temp.act(C);
    perform pg_temp.expect_error(format('select public.join_tournament(%L)', tid), 'TEAM_SIZE');
    perform pg_temp.act(D); perform public.join_team(tb->>'join_code');
    perform pg_temp.act(C); perform public.join_tournament(tid);
    perform pg_temp.act(X); perform pg_temp.expect_error(format('select public.join_tournament(%L)', tid), 'TEAM_REQUIRED');
    perform pg_temp.act(A); perform public.start_tournament(tid);
    select * into tm from public.tournament_matches where tournament_id = tid;
    perform pg_temp.check(jsonb_array_length((select settings->'teams' from public.rooms where id = tm.room_id)) = 2, 'team: room teams set');
    m := pg_temp.play_tm(tm.id, C);
    perform pg_temp.check((select team_side from public.match_players where match_id = m and user_id = C)
                          = (select team_side from public.match_players where match_id = m and user_id = D), 'team: teammates on one side');
    perform pg_temp.check(exists (select 1 from public.tournament_entries where tournament_id = tid and status = 'champion' and C = any(user_ids) and D = any(user_ids)),
                          'team: team champion');

    -- ============================================================ free-for-all: last place out each round
    perform pg_temp.act(A);
    t := public.create_tournament('Herkes Kupası', 'renk_kartlari', 'ffa', 4); tid := (t->>'id')::uuid;
    perform public.join_tournament(tid);
    perform pg_temp.act(F); perform public.join_tournament(tid);
    perform pg_temp.act(G); perform public.join_tournament(tid);
    perform pg_temp.act(H); perform public.join_tournament(tid);
    perform pg_temp.act(A); perform public.start_tournament(tid);
    select * into tm from public.tournament_matches where tournament_id = tid and round = 1;
    perform pg_temp.play_tm(tm.id, null, array[A, F, G, H]);
    perform pg_temp.check((select status from public.tournament_entries where tournament_id = tid and H = any(user_ids)) = 'eliminated', 'ffa: last place out');
    select * into tm from public.tournament_matches where tournament_id = tid and round = 2;
    perform pg_temp.check(cardinality(tm.entries) = 3, 'ffa: 3 left');
    perform pg_temp.play_tm(tm.id, null, array[F, A, G]);
    select * into tm from public.tournament_matches where tournament_id = tid and round = 3;
    perform pg_temp.play_tm(tm.id, null, array[F, A]);
    perform pg_temp.check(exists (select 1 from public.tournament_entries where tournament_id = tid and status = 'champion' and F = any(user_ids)), 'ffa: winner');
end $$;

-- clients cannot write tournament tables
do $$
begin
    perform set_config('request.jwt.claim.sub', '70000000-0000-0000-0000-000000000002', true);
    set local role authenticated;
    begin
        update public.tournaments set status = 'finished';
        raise exception 'CHECK FAILED: client wrote tournaments';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public._tm_finish('00000000-0000-0000-0000-000000000000', null);
        raise exception 'CHECK FAILED: client called _tm_finish';
    exception when insufficient_privilege then null;
    end;
    reset role;
end $$;

select 'TOURNAMENT TESTS PASSED' as result;
rollback;
