-- =====================================================================
-- LOOPA 0.9.5 — Amiral Battı v2: shoot again on a hit, 2v2 turn rotation, jokers
-- (sonar, recon, double, torpedo, salvo), inventory cap, awards, privacy, timeouts.
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
select ('5a000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid, true from generate_series(1, 6) i;

do $$
declare
    A uuid := '5a000000-0000-0000-0000-000000000001'; B uuid := '5a000000-0000-0000-0000-000000000002';
    C uuid := '5a000000-0000-0000-0000-000000000003'; D uuid := '5a000000-0000-0000-0000-000000000004';
    room uuid; s jsonb; v jsonb; x uuid; y uuid; t0 text; t1 text; free text; fx int; fy int; n int;
    fleet text := '{"fleet":[{"x":0,"y":0,"len":5,"dir":"h"},{"x":0,"y":2,"len":4,"dir":"h"},{"x":0,"y":4,"len":3,"dir":"h"},{"x":0,"y":6,"len":3,"dir":"h"},{"x":0,"y":8,"len":2,"dir":"h"}]}';
begin
    perform pg_temp.act(A); perform public.ensure_profile('Amiral A'); perform public._grant_plus(A, 30, 'admin', 'test', A);
    perform pg_temp.act(B); perform public.ensure_profile('Amiral B');
    perform pg_temp.act(C); perform public.ensure_profile('Amiral C');
    perform pg_temp.act(D); perform public.ensure_profile('Amiral D');

    -- ================================================================ 1v1, fixed fleets (same layout both sides)
    room := pg_temp.start('amiral_batti', array[A, B], '{"format":"1v1"}');
    perform pg_temp.move(room, A, 'place', fleet::jsonb);
    perform pg_temp.move(room, B, 'place', fleet::jsonb);
    s := pg_temp.st(room);
    perform pg_temp.check(s->>'phase' = 'battle', 'ab2: battle starts');
    -- start jokers: one each, type private, count public
    perform pg_temp.check(jsonb_array_length(s->'jokers'->(A::text)) = 1 and jsonb_array_length(s->'jokers'->(B::text)) = 1, 'ab2: one joker each at the start');
    perform pg_temp.check((s->'jokers'->(A::text)->0->>'t') = any(public._ab_jk_types()), 'ab2: a known joker type');
    perform pg_temp.check((select count(*) from jsonb_array_elements(s->'log') e where e->>'e' = 'joker' and e->>'src' = 'start' and not (e ? 't')) = 2,
                          'ab2: start awards are public, without the type');
    perform pg_temp.act(B); v := public.get_mode_state(room);
    perform pg_temp.check((v->'round'->'public'->'jk_count'->>(A::text))::int = 1, 'ab2: B sees how many jokers A holds');
    perform pg_temp.check(v->'me'->'jokers' = s->'jokers'->(B::text) and v->'round'->'public'->'jokers' is null, 'ab2: B sees only own joker types');
    perform pg_temp.check(not (v::text like '%"x": 0, "y": 0, "len": 5%') or v->'me'->'fleet' = s->'fleets'->1, 'ab2: A''s fleet does not leak to B');
    perform pg_temp.check(v->'round'->'public'->'fleets' = 'null'::jsonb, 'ab2: fleets hidden in public');

    -- A's turn; a miss passes the turn
    s := s || jsonb_build_object('shooting', 0, 'turn', A, 'jk_turn', null, 'starter', '[1,0]'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''fire'', ''{"x":9,"y":9}'')', room, B), 'NOT_YOUR_TURN');
    perform pg_temp.move(room, A, 'fire', '{"x":9,"y":9}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->'shots'->1->>'9,9' = 'miss' and (s->>'shooting')::int = 1 and s->>'turn' = B::text, 'ab2: miss -> the other side');
    -- B hits and keeps shooting
    perform pg_temp.move(room, B, 'fire', '{"x":0,"y":0}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->'shots'->0->>'0,0' = 'hit' and (s->>'shooting')::int = 1 and s->>'turn' = B::text, 'ab2: hit -> shoot again');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''fire'', ''{"x":0,"y":0}'')', room, B), 'ALREADY_SHOT');
    perform pg_temp.move(room, B, 'fire', '{"x":1,"y":0}');
    perform pg_temp.check(pg_temp.st(room)->>'turn' = B::text, 'ab2: second hit, still B');
    -- B sinks A's 2-ship: +1 joker, still B's turn
    perform pg_temp.move(room, B, 'fire', '{"x":0,"y":8}');
    perform pg_temp.move(room, B, 'fire', '{"x":1,"y":8}');
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'sunk'->0) = 1 and s->>'turn' = B::text, 'ab2: sunk, B keeps the turn');
    perform pg_temp.check(jsonb_array_length(s->'jokers'->(B::text)) = 2, 'ab2: sinking awards a joker');
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(s->'log') e where e->>'e' = 'joker' and e->>'src' = 'sunk' and e->>'u' = B::text and not (e ? 't')),
                          'ab2: the award is announced without the type');
    -- inventory cap: with 3 jokers a sink gives nothing
    s := jsonb_set(s, array['jokers', B::text], '[{"id":901,"t":"sonar"},{"id":902,"t":"recon"},{"id":903,"t":"salvo"}]'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, B, 'fire', '{"x":0,"y":6}'); perform pg_temp.move(room, B, 'fire', '{"x":1,"y":6}'); perform pg_temp.move(room, B, 'fire', '{"x":2,"y":6}');
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'sunk'->0) = 2 and jsonb_array_length(s->'jokers'->(B::text)) = 3, 'ab2: full inventory, no new joker');
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(s->'log') e where e->>'e' = 'joker_full' and e->>'u' = B::text), 'ab2: full inventory is logged');
    perform pg_temp.move(room, B, 'fire', '{"x":9,"y":0}');
    perform pg_temp.check(pg_temp.st(room)->>'turn' = A::text, 'ab2: B misses -> A');

    -- ================================================================ jokers (A holds one of each, test state)
    s := pg_temp.st(room);
    s := jsonb_set(s, array['jokers', A::text], '[{"id":101,"t":"sonar"},{"id":102,"t":"recon"},{"id":103,"t":"double"},{"id":104,"t":"torpedo"},{"id":105,"t":"salvo"}]'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''joker'', ''{"id":999,"x":1,"y":1}'')', room, A), 'JOKER_NOT_AVAILABLE');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''joker'', ''{"id":901,"x":1,"y":1}'')', room, A), 'JOKER_NOT_AVAILABLE');   -- B's joker
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''joker'', ''{"id":101,"x":10,"y":1}'')', room, A), 'INVALID_MOVE');
    perform pg_temp.check(jsonb_array_length(pg_temp.st(room)->'jokers'->(A::text)) = 5, 'ab2: an invalid target keeps the joker');
    -- sonar: info only, the shot is still available
    perform pg_temp.move(room, A, 'joker', '{"id":101,"x":1,"y":1}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->>'turn' = A::text and (s->'intel'->0->-1->>'found')::boolean and s->'intel'->0->-1->>'t' = 'sonar', 'ab2: sonar finds a ship, A keeps the shot');
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(s->'jokers'->(A::text)) j where j->>'id' = '101'), 'ab2: sonar is used up');
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(s->'log') e where e->>'e' = 'jk' and e->>'t' = 'sonar' and not (e ? 'found')), 'ab2: sonar use is public, result is not');
    perform pg_temp.act(B); v := public.get_mode_state(room);
    perform pg_temp.check(v->'me'->'intel' = '[]'::jsonb, 'ab2: B does not see A''s sonar result');
    perform pg_temp.act(A); v := public.get_mode_state(room);
    perform pg_temp.check(jsonb_array_length(v->'me'->'intel') = 1, 'ab2: A sees the sonar result');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''joker'', ''{"id":102,"axis":"row","i":9}'')', room, A), 'JOKER_LIMIT');
    perform pg_temp.move(room, A, 'fire', '{"x":8,"y":9}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->>'turn' = B::text, 'ab2: A misses -> B');
    -- recon (A's turn again)
    s := s || jsonb_build_object('shooting', 0, 'turn', A, 'jk_turn', null);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''joker'', ''{"id":101,"x":1,"y":1}'')', room, A), 'JOKER_NOT_AVAILABLE');   -- used once
    perform pg_temp.move(room, A, 'joker', '{"id":102,"axis":"row","i":9}');
    perform pg_temp.check(not (pg_temp.st(room)->'intel'->0->-1->>'found')::boolean and pg_temp.st(room)->>'turn' = A::text, 'ab2: recon on an empty row');
    -- double: two cells, a hit keeps the turn
    s := pg_temp.st(room) || jsonb_build_object('jk_turn', null);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''joker'', ''{"id":103,"cells":[[3,3],[3,3]]}'')', room, A), 'INVALID_MOVE');
    perform pg_temp.move(room, A, 'joker', '{"id":103,"cells":[[0,2],[9,7]]}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->'shots'->1->>'0,2' = 'hit' and s->'shots'->1->>'9,7' = 'miss' and s->>'turn' = A::text, 'ab2: double fires twice, a hit keeps the turn');
    -- torpedo along row 4 from the left: hits the first ship part, water it passes stays unmarked
    s := s || jsonb_build_object('jk_turn', null); perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'joker', '{"id":104,"axis":"row","i":4}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->'shots'->1->>'0,4' = 'hit' and (select count(*) from jsonb_object_keys(s->'shots'->1) k where k like '%,4') = 1 and s->>'turn' = A::text,
                          'ab2: torpedo hits the first ship part');
    -- salvo: invalid line keeps the joker, a full hit line sinks and awards a joker
    s := s || jsonb_build_object('jk_turn', null); perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''joker'', ''{"id":105,"x":8,"y":6,"dir":"h"}'')', room, A), 'INVALID_MOVE');
    n := jsonb_array_length(pg_temp.st(room)->'jokers'->(A::text));
    perform pg_temp.move(room, A, 'joker', '{"id":105,"x":0,"y":6,"dir":"h"}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->'shots'->1->>'0,6' = 'hit' and s->'shots'->1->>'2,6' = 'hit' and jsonb_array_length(s->'sunk'->1) = 1, 'ab2: salvo judges three cells and sinks');
    perform pg_temp.check(jsonb_array_length(s->'jokers'->(A::text)) = n - 1 + 1 and s->>'turn' = A::text, 'ab2: salvo used, sinking awarded a new joker');
    perform pg_temp.check((select count(*) from jsonb_array_elements(s->'log') e where e->>'via' = 'salvo') = 3, 'ab2: each salvo cell is reported');
    -- torpedo that finds nothing: miss, nothing marked, turn passes
    s := s || jsonb_build_object('jk_turn', null, 'jokers', jsonb_set(s->'jokers', array[A::text], '[{"id":201,"t":"torpedo"}]'::jsonb));
    perform pg_temp.setst(room, s);
    n := (select count(*) from jsonb_object_keys(s->'shots'->1));
    perform pg_temp.move(room, A, 'joker', '{"id":201,"axis":"row","i":9,"rev":true}');
    s := pg_temp.st(room);
    perform pg_temp.check((select count(*) from jsonb_object_keys(s->'shots'->1)) = n and s->>'turn' = B::text, 'ab2: empty torpedo run is a miss');
    -- timeout: the server fires for the player on turn
    n := (select count(*) from jsonb_object_keys(s->'shots'->0));
    update public.mode_rounds set deadline_at = now() - interval '1 second' where id = (pg_temp.rd(room)).id;
    perform public._mode_advance(room);
    perform pg_temp.check((select count(*) from jsonb_object_keys(pg_temp.st(room)->'shots'->0)) = n + 1, 'ab2: timeout fires one shot');
    -- the last ship sunk through a joker ends the game
    s := pg_temp.st(room);
    s := s || jsonb_build_object('shooting', 0, 'turn', A, 'jk_turn', null,
        'jokers', jsonb_set(s->'jokers', array[A::text], '[{"id":301,"t":"double"}]'::jsonb),
        'shots', jsonb_build_array(s->'shots'->0, (select jsonb_object_agg(cell, '"hit"'::jsonb) from jsonb_array_elements_text(s->'cells'->1) cell where cell not in ('3,0','4,0'))),
        'sunk', jsonb_build_array(s->'sunk'->0, jsonb_build_array(s->'fleets'->1->1, s->'fleets'->1->2, s->'fleets'->1->3, s->'fleets'->1->4)));
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'joker', '{"id":301,"cells":[[3,0],[4,0]]}');
    perform pg_temp.check((select final_rank from public.match_players where match_id = (select id from public.matches where room_id = room order by started_at desc limit 1) and user_id = A) = 1,
                          'ab2: sinking the whole fleet with a joker wins');

    -- ================================================================ 2v2: one member per team turn, members alternate
    perform pg_temp.act(A);
    room := pg_temp.start('amiral_batti', array[A, B, C, D], '{"format":"2v2"}');
    perform pg_temp.move(room, A, 'place', '{"random": true}');
    perform pg_temp.move(room, B, 'place', '{"random": true}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->>'phase' = 'battle' and (select count(*) from jsonb_object_keys(s->'jokers')) = 4, 'ab2 2v2: battle, four players with a joker');
    t0 := s->'teams'->0->>0; t1 := s->'teams'->0->>1;
    s := s || jsonb_build_object('shooting', 0, 'turn', t0, 'starter', '[1,0]'::jsonb, 'jk_turn', null);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''fire'', ''{"x":0,"y":0}'')', room, t1), 'NOT_YOUR_TURN');
    -- a hit keeps the same member
    free := (select cl from jsonb_array_elements_text(s->'cells'->1) cl limit 1);
    perform pg_temp.move(room, t0::uuid, 'fire', jsonb_build_object('x', split_part(free, ',', 1)::int, 'y', split_part(free, ',', 2)::int));
    perform pg_temp.check(pg_temp.st(room)->>'turn' = t0, 'ab2 2v2: a hit keeps the member');
    -- a miss passes to the other team, then back to the OTHER member of team 0
    select g % 14, g / 14 into fx, fy from generate_series(0, 195) g
     where not (s->'cells'->1 ? ((g % 14) || ',' || (g / 14))) and not (pg_temp.st(room)->'shots'->1 ? ((g % 14) || ',' || (g / 14))) limit 1;
    perform pg_temp.move(room, t0::uuid, 'fire', jsonb_build_object('x', fx, 'y', fy));
    s := pg_temp.st(room);
    perform pg_temp.check((s->>'shooting')::int = 1 and s->>'turn' = s->'teams'->1->>0, 'ab2 2v2: miss -> the other team''s first member');
    select g % 14, g / 14 into fx, fy from generate_series(0, 195) g
     where not (s->'cells'->0 ? ((g % 14) || ',' || (g / 14))) and not (s->'shots'->0 ? ((g % 14) || ',' || (g / 14))) limit 1;
    perform pg_temp.move(room, (s->'teams'->1->>0)::uuid, 'fire', jsonb_build_object('x', fx, 'y', fy));
    s := pg_temp.st(room);
    perform pg_temp.check((s->>'shooting')::int = 0 and s->>'turn' = t1, 'ab2 2v2: team 0''s next turn starts with the other member');
    -- privacy: the other team sees neither the fleet nor joker types
    perform pg_temp.act((s->'teams'->1->>0)::uuid); v := public.get_mode_state(room);
    perform pg_temp.check(v->'me'->'fleet' = s->'fleets'->1 and v->'round'->'public'->'fleets' = 'null'::jsonb, 'ab2 2v2: own team fleet only');
    perform pg_temp.check(not (v::text like '%"cells"%') and not (v::text like '%"intel": [{%' and v->'me'->'intel' = '[]'::jsonb), 'ab2 2v2: no hidden cells in the view');
end $$;

select 'AMIRAL BATTI TESTS PASSED' as result;
rollback;
