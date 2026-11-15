-- =====================================================================
-- LOOPA 0.8.0 — Tavla, 21, Kızma Birader, Amiral Battı through the real RPCs
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

insert into auth.users(id, is_anonymous)
select ('50000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid, true from generate_series(1, 6) i;

do $$
declare
    A uuid := '50000000-0000-0000-0000-000000000001'; B uuid := '50000000-0000-0000-0000-000000000002';
    C uuid := '50000000-0000-0000-0000-000000000003'; D uuid := '50000000-0000-0000-0000-000000000004';
    room uuid; s jsonb; v jsonb; i int; turn uuid; other uuid; m uuid; guard int;
begin
    perform pg_temp.act(A); perform public.ensure_profile('Oyuncu A'); perform public._grant_plus(A, 30, 'admin', 'test', A);
    perform pg_temp.act(B); perform public.ensure_profile('Oyuncu B');
    perform pg_temp.act(C); perform public.ensure_profile('Oyuncu C');
    perform pg_temp.act(D); perform public.ensure_profile('Oyuncu D');
    update public.economy_config set value = '{"daily_rewarded_matches": 99, "same_opponents_daily": 99}' where key = 'anti_farm';

    -- =========================================================== 21
    room := pg_temp.start('yirmi_bir', array[A, B], '{"hands": 3}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->>'phase' = 'play' and jsonb_array_length(s->'hands'->A::text) = 2, '21: two cards each');
    perform pg_temp.act(B);
    v := public.get_mode_state(room);
    perform pg_temp.check(jsonb_array_length(v->'me'->'hand') = 2, '21: I see my two cards');
    perform pg_temp.check(jsonb_array_length(v->'round'->'public'->'shown'->A::text) = 1, '21: only the first card of the opponent is shown');
    perform pg_temp.check(not (v::text like '%"deck"%'), '21: the deck never leaves the server');
    turn := (s->>'turn')::uuid; other := case when turn = A then B else A end;
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''hit'', ''{}'')', room, other), 'NOT_YOUR_TURN');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''bet'', ''{"coins":100}'')', room, turn), 'INVALID_ACTION');
    -- pinned hand: turn player 10+9 = 19 stands; other 10+7 = 17 hits a 10 and busts
    s := s || jsonb_build_object('hands', jsonb_build_object(turn::text, '[{"r":10,"s":"S"},{"r":9,"s":"H"}]'::jsonb,
                                                             other::text, '[{"r":13,"s":"D"},{"r":7,"s":"C"}]'::jsonb),
                                 'deck', '[{"r":12,"s":"S"},{"r":2,"s":"S"},{"r":3,"s":"S"}]'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, turn, 'stand', '{}');
    perform pg_temp.move(room, other, 'hit', '{}');
    s := (select payload from public.mode_secrets where round_id = (select id from public.mode_rounds where room_id = room and round_number = 1) and kind = 'g');
    perform pg_temp.check(s->>'winner' = turn::text and (s->'totals'->>other::text)::int = 27, '21: bust loses the hand');
    perform pg_temp.check((pg_temp.rd(room)).round_number = 2, '21: next hand dealt');
    -- an ace counts 1 or 11
    perform pg_temp.check(public._yb_value('[{"r":1},{"r":13}]') = 21 and public._yb_value('[{"r":1},{"r":1},{"r":9}]') = 21
                          and public._yb_value('[{"r":1},{"r":9},{"r":5}]') = 15, '21: ace values');
    -- finish the match by standing (the server deals the rest)
    guard := 0;
    while (select status from public.matches where room_id = room order by started_at desc limit 1) = 'in_progress' and guard < 40 loop
        guard := guard + 1;
        s := pg_temp.st(room);
        perform pg_temp.move(room, (s->>'turn')::uuid, 'stand', '{}');
    end loop;
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select status from public.matches where id = m) = 'finished', '21: match finishes');
    perform pg_temp.check(not exists (select 1 from public.coin_transactions where amount < 0 and user_id in (A, B)), '21: nobody loses coins');

    -- =========================================================== Tavla
    room := pg_temp.start('tavla', array[A, B], '{"points": 1}');
    s := pg_temp.st(room);
    perform pg_temp.check((select sum(abs(x::int)) from jsonb_array_elements_text(s->'b') x) = 30, 'tavla: 30 checkers on the board');
    turn := (s->>'turn')::uuid; other := case when turn = A then B else A end;
    -- pinned: player 1 (A) to move with 6-1 from the opening position
    s := s || jsonb_build_object('turn', A, 'dice', '[6,1]'::jsonb, 'max_use', 2, 'blocked', false);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''move'', ''{"moves":[{"from":13,"die":6}]}'')', room, A), 'MUST_USE_DICE');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''move'', ''{"moves":[{"from":13,"die":5},{"from":8,"die":1}]}'')', room, A), 'INVALID_MOVE');
    -- 13 -> 12 is blocked (five opposing checkers on 12)
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''move'', ''{"moves":[{"from":13,"die":1},{"from":13,"die":6}]}'')', room, A), 'INVALID_MOVE');
    -- a checker that is not there
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''move'', ''{"moves":[{"from":20,"die":6},{"from":8,"die":1}]}'')', room, A), 'INVALID_MOVE');
    perform pg_temp.check(true, 'tavla: illegal sequences refused');
    perform pg_temp.move(room, A, 'move', '{"moves":[{"from":13,"die":6},{"from":8,"die":1}]}');
    s := pg_temp.st(room);
    perform pg_temp.check((s->'b'->>6)::int = 2 and (s->'b'->>12)::int = 4 and (s->'b'->>7)::int = 2, 'tavla: classic 6-1 (13/7 8/7)');
    perform pg_temp.check(s->>'turn' = B::text, 'tavla: turn passes');
    -- hit and bar entry (pinned): B single checker on A's 20, A rolls 4-1 from 24
    s := s || jsonb_build_object('b', '[0,0,0,0,0,5, 0,3,0,0,0,0, 5,0,0,0,0,0, 0,-1,0,0,0,2]'::jsonb, 'bar', '[0,0]'::jsonb,
                                 'turn', A, 'dice', '[4,1]'::jsonb, 'max_use', 2, 'off', '[0,0]'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'move', '{"moves":[{"from":24,"die":4},{"from":24,"die":1}]}');
    s := pg_temp.st(room);
    perform pg_temp.check((s->'bar'->>1)::int = 1 and (s->'b'->>19)::int = 1, 'tavla: hit sends to the bar');
    -- B must enter from the bar first: B moves in its own numbering (25 = bar)
    s := s || jsonb_build_object('turn', B, 'dice', '[3,2]'::jsonb, 'max_use', 2);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''move'', ''{"moves":[{"from":1,"die":3},{"from":1,"die":2}]}'')', room, B), 'INVALID_MOVE');
    perform pg_temp.move(room, B, 'move', '{"moves":[{"from":25,"die":3},{"from":22,"die":2}]}');
    s := pg_temp.st(room);
    perform pg_temp.check((s->'bar'->>1)::int = 0, 'tavla: entered from the bar');
    -- bearing off: A has two checkers on 1 and 2, all home; larger die bears off from the highest point
    s := s || jsonb_build_object('b', '[1,1,0,0,0,0, 0,0,0,0,0,0, 0,0,0,0,0,0, 0,0,0,-15,0,0]'::jsonb, 'bar', '[0,0]'::jsonb,
                                 'off', '[13,0]'::jsonb, 'turn', A, 'dice', '[6,5]'::jsonb, 'max_use', 2);
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'move', '{"moves":[{"from":2,"die":6},{"from":1,"die":5}]}');
    s := (select payload from public.mode_secrets where round_id = (select id from public.mode_rounds where room_id = room order by round_number limit 1) and kind = 'g');
    perform pg_temp.check(s->>'winner' = A::text and (s->>'mars')::boolean and (s->'points'->>A::text)::int = 2, 'tavla: mars counts 2');
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select status from public.matches where id = m) = 'finished'
                          and (select final_rank from public.match_players where match_id = m and user_id = A) = 1, 'tavla: match won');
    perform pg_temp.check(((select stats from public.player_game_stats where user_id = A and game_mode = 'tavla')->>'mars_won')::int = 1, 'tavla: game stats');
    -- the "must use the larger die" rule
    perform pg_temp.check(public._tv_best('{0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1}'::int[], 0, 0, 14, array[6,5])->>'use' = '2', 'tavla: search');

    -- =========================================================== Kızma Birader
    room := pg_temp.start('kizma_birader', array[A, B, C], '{}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->>'turn' = A::text and (s->'pieces'->A::text) = '[-1,-1,-1,-1]', 'kb: all in the yard');
    s := s || jsonb_build_object('die', 3, 'movable', '[]'::jsonb, 'tries', 0);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''move'', ''{"piece":0}'')', room, A), 'INVALID_MOVE');
    perform pg_temp.move(room, A, 'pass', '{}');
    perform pg_temp.check(pg_temp.st(room)->>'turn' = A::text and (pg_temp.st(room)->>'tries')::int = 1, 'kb: three tries for a 6');
    -- a 6 brings a piece out and gives another roll
    s := pg_temp.st(room) || jsonb_build_object('die', 6, 'movable', '[0,1,2,3]'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'move', '{"piece":0}');
    s := pg_temp.st(room);
    perform pg_temp.check((s->'pieces'->A::text->>0)::int = 0 and s->>'turn' = A::text, 'kb: out on 6, roll again');
    -- capture: B (seat 1, starts at abs 10) has a piece on abs 15 (prog 5); A at prog 12 (abs 12) rolls 3
    s := s || jsonb_build_object('pieces', jsonb_build_object(A::text, '[12,-1,-1,-1]'::jsonb, B::text, '[5,-1,-1,-1]'::jsonb, C::text, '[-1,-1,-1,-1]'::jsonb),
                                 'die', 3, 'movable', '[0]'::jsonb, 'turn', A);
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'move', '{"piece":0}');
    s := pg_temp.st(room);
    perform pg_temp.check((s->'pieces'->B::text->>0)::int = -1 and (s->'captures'->>A::text)::int = 1, 'kb: capture sends home');
    -- a start square is safe: B on its start (abs 10 = prog 0); A at prog 7 rolls 3 -> abs 10
    s := s || jsonb_build_object('pieces', jsonb_build_object(A::text, '[7,-1,-1,-1]'::jsonb, B::text, '[0,-1,-1,-1]'::jsonb, C::text, '[-1,-1,-1,-1]'::jsonb),
                                 'die', 3, 'movable', '[0]'::jsonb, 'turn', A);
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'move', '{"piece":0}');
    perform pg_temp.check((pg_temp.st(room)->'pieces'->B::text->>0)::int = 0, 'kb: start square is safe');
    -- win: last piece reaches home (exact)
    s := pg_temp.st(room) || jsonb_build_object('pieces', jsonb_build_object(A::text, '[40,41,42,39]'::jsonb, B::text, '[0,-1,-1,-1]'::jsonb, C::text, '[-1,-1,-1,-1]'::jsonb),
                                 'die', 4, 'movable', '[3]'::jsonb, 'turn', A);
    perform pg_temp.setst(room, s);
    perform pg_temp.check(public._kb_target(pg_temp.st(room), A::text, 3, 5) is null, 'kb: home needs an exact roll');
    perform pg_temp.move(room, A, 'move', '{"piece":3}');
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select final_rank from public.match_players where match_id = m and user_id = A) = 1, 'kb: first home wins');

    -- =========================================================== Amiral Battı 1v1
    room := pg_temp.start('amiral_batti', array[A, B], '{"format":"1v1"}');
    perform pg_temp.check(pg_temp.st(room)->>'phase' = 'placement', 'ab: placement first');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''place'', %L)', room, A,
        '{"fleet":[{"x":0,"y":0,"len":5,"dir":"h"},{"x":0,"y":0,"len":4,"dir":"v"},{"x":0,"y":5,"len":3,"dir":"h"},{"x":0,"y":7,"len":3,"dir":"h"},{"x":0,"y":9,"len":2,"dir":"h"}]}'), 'INVALID_FLEET');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''place'', %L)', room, A,
        '{"fleet":[{"x":8,"y":0,"len":5,"dir":"h"},{"x":0,"y":2,"len":4,"dir":"h"},{"x":0,"y":5,"len":3,"dir":"h"},{"x":0,"y":7,"len":3,"dir":"h"},{"x":0,"y":9,"len":2,"dir":"h"}]}'), 'INVALID_FLEET');
    perform pg_temp.move(room, A, 'place', '{"fleet":[{"x":0,"y":0,"len":5,"dir":"h"},{"x":0,"y":2,"len":4,"dir":"h"},{"x":0,"y":4,"len":3,"dir":"h"},{"x":0,"y":6,"len":3,"dir":"h"},{"x":0,"y":8,"len":2,"dir":"h"}]}');
    perform pg_temp.act(B);
    v := public.get_mode_state(room);
    perform pg_temp.check(v->'me'->'fleet' = 'null'::jsonb and not (v::text like '%"x": 0, "y": 0, "len": 5%'), 'ab: A''s fleet is hidden from B');
    perform pg_temp.move(room, B, 'place', '{"random": true}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->>'phase' = 'battle', 'ab: battle starts');
    -- B shoots at A's 2-ship (0,8),(1,8): make it B's turn
    s := s || jsonb_build_object('shooting', 1, 'turn', B, 'shots_left', 1, 'fired', '[]'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''fire'', ''{"x":0,"y":8}'')', room, A), 'NOT_YOUR_TURN');
    v := pg_temp.move(room, B, 'fire', '{"x":0,"y":8}');
    perform pg_temp.check(pg_temp.st(room)->'shots'->0->>'0,8' = 'hit', 'ab: hit');
    s := pg_temp.st(room) || jsonb_build_object('shooting', 1, 'turn', B, 'shots_left', 1, 'fired', '[]'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''fire'', ''{"x":0,"y":8}'')', room, B), 'ALREADY_SHOT');
    perform pg_temp.move(room, B, 'fire', '{"x":1,"y":8}');
    perform pg_temp.check(jsonb_array_length(pg_temp.st(room)->'sunk'->0) = 1, 'ab: sunk');
    perform pg_temp.act(A);
    v := public.get_mode_state(room);
    perform pg_temp.check(v->'round'->'public'->'fleets' = 'null'::jsonb, 'ab: fleets stay hidden during the game');
    -- B sinks everything of A
    s := pg_temp.st(room);
    s := s || jsonb_build_object('shooting', 1, 'turn', B, 'shots_left', 1, 'fired', '[]'::jsonb,
                                 'shots', jsonb_build_array((select jsonb_object_agg(cell, '"hit"'::jsonb) from jsonb_array_elements_text(s->'cells'->0) cell where cell <> '4,0'),
                                                            s->'shots'->1),
                                 'sunk', jsonb_build_array(jsonb_build_array(s->'fleets'->0->1, s->'fleets'->0->2, s->'fleets'->0->3, s->'fleets'->0->4), '[]'::jsonb));
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, B, 'fire', '{"x":4,"y":0}');
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select final_rank from public.match_players where match_id = m and user_id = B) = 1, 'ab: whole fleet sunk = win');

    -- =========================================================== Amiral Battı 2v2 (turn rules and jokers: amiral_batti_test.sql)
    perform pg_temp.act(A);
    perform pg_temp.expect_error(format('select pg_temp.start(''amiral_batti'', array[%L,%L,%L]::uuid[], ''{"format":"2v2"}'')', A, B, C), 'WRONG_PLAYER_COUNT');
    room := pg_temp.start('amiral_batti', array[A, B, C, D], '{"format":"2v2"}');
    s := pg_temp.st(room);
    perform pg_temp.check((s->>'size')::int = 14 and jsonb_array_length(s->'ships') = 8, 'ab 2v2: bigger board, more ships');
    perform pg_temp.move(room, A, 'place', '{"random": true}');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''place'', ''{"random": true}'')', room, C), 'ALREADY_SUBMITTED');   -- teammate
    perform pg_temp.move(room, B, 'place', '{"random": true}');
    perform pg_temp.check(pg_temp.st(room)->>'phase' = 'battle', 'ab 2v2: battle starts');
end $$;

select 'TURN GAMES TESTS PASSED' as result;
rollback;
