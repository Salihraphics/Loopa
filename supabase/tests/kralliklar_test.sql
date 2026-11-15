-- =====================================================================
-- LOOPA 0.8.0 — Krallıklar: map, fog of war, orders, movement, battles, production,
-- buildings, diplomacy (letters, offers, trades, alliances, pacts), elimination, victory.
-- Luck is set to 0 in the game state where a test needs an exact battle result.
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
        ok := true;
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
    select r.* from public.mode_rounds r where r.room_id = p_room order by r.created_at desc, r.round_number desc limit 1;
$$;
create or replace function pg_temp.st(p_room uuid) returns jsonb language sql as $$
    select payload from public.mode_secrets where round_id = (pg_temp.rd(p_room)).id and kind = 'g';
$$;
create or replace function pg_temp.setst(p_room uuid, s jsonb) returns void language plpgsql as $$
begin perform public._tg_commit(pg_temp.rd(p_room), s); end $$;
create or replace function pg_temp.move(p_room uuid, u uuid, k text, p jsonb) returns jsonb language plpgsql as $$
begin
    perform pg_temp.act(u);
    return public.mode_act((pg_temp.rd(p_room)).id, k, p);
end $$;
create or replace function pg_temp.view(p_room uuid, u uuid) returns jsonb language plpgsql as $$
begin
    perform pg_temp.act(u);
    return public.get_mode_state(p_room);
end $$;
create or replace function pg_temp.start(p_mode text, p_players uuid[], p_opts jsonb) returns uuid language plpgsql as $$
declare room jsonb; i int; st jsonb;
begin
    perform pg_temp.act(p_players[1]);
    room := public.create_room(false);
    perform public.update_room_settings((room->>'id')::uuid,
        (select settings from public.rooms where id = (room->>'id')::uuid) || jsonb_build_object('max_players', 20, 'game_mode', p_mode), null);
    for i in 2..array_length(p_players, 1) loop perform pg_temp.act(p_players[i]); perform public.join_room(room->>'code'); end loop;
    perform pg_temp.act(p_players[1]);
    st := (select settings from public.rooms where id = (room->>'id')::uuid);
    perform public.update_room_settings((room->>'id')::uuid, st || jsonb_build_object('mode_options', p_opts, 'configured', true), null);
    for i in 2..array_length(p_players, 1) loop perform pg_temp.act(p_players[i]); perform public.set_ready((room->>'id')::uuid, true); end loop;
    perform pg_temp.act(p_players[1]);
    perform public.start_match((room->>'id')::uuid);
    return (room->>'id')::uuid;
end $$;
create or replace function pg_temp.tick(p_room uuid) returns void language plpgsql as $$
begin
    update public.mode_rounds set deadline_at = now() - interval '1 second' where id = (pg_temp.rd(p_room)).id;
    perform public._mode_advance(p_room);
end $$;
create or replace function pg_temp.ku(s jsonb, k int) returns uuid language sql as $$ select (s->'k'->k::text->>'u')::uuid; $$;
create or replace function pg_temp.cap(s jsonb, k int) returns int language sql as $$ select (s->'k'->k::text->>'cap')::int; $$;
create or replace function pg_temp.settile(s jsonb, i int, t jsonb) returns jsonb language sql as $$
    select jsonb_set(s, array['tiles', i::text], (s->'tiles'->i) || t);
$$;
create or replace function pg_temp.owned(s jsonb, k int) returns int language sql as $$
    select count(*)::int from jsonb_array_elements(s->'tiles') t where (t->>'o')::int = k;
$$;

insert into auth.users(id, is_anonymous)
select ('90000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid, true from generate_series(1, 20) i;

do $$
declare
    us uuid[] := array(select ('90000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid from generate_series(1, 20) i);
    room uuid; s jsonb; v jsonb; mp jsonb; n int; i int; j int; c1 int; c2 int; r1 int; r2 int; far int; u1 uuid; u2 uuid; u3 uuid; u4 uuid;
    nb int[]; t0 timestamptz; w int; x jsonb;
begin
    for i in 1..20 loop perform pg_temp.act(us[i]); perform public.ensure_profile('Kral ' || i); end loop;
    perform public._grant_plus(us[1], 30, 'admin', 'test', us[1]);
    perform public._grant_plus(us[5], 30, 'admin', 'test', us[1]);

    -- ============================================================ map sizes and shape
    perform pg_temp.check(public._kr_map_size(4) = 200 and public._kr_map_size(5) = 250 and public._kr_map_size(7) = 250
        and public._kr_map_size(8) = 300 and public._kr_map_size(9) = 350 and public._kr_map_size(11) = 350 and public._kr_map_size(12) = 400
        and public._kr_map_size(13) = 450 and public._kr_map_size(15) = 450 and public._kr_map_size(16) = 500 and public._kr_map_size(17) = 550
        and public._kr_map_size(19) = 550 and public._kr_map_size(20) = 600, 'kr: map size table');
    foreach n in array array[200, 350, 600] loop
        mp := public._kr_gen_map(n, public._kr_default_balance());
        perform pg_temp.check(jsonb_array_length(mp->'tiles') = n, 'kr: exact tile count ' || n);
        perform pg_temp.check((select count(*) from jsonb_object_keys(mp->'idx')) = n, 'kr: unique coordinates');
        perform pg_temp.check(not exists (select 1 from jsonb_array_elements(mp->'tiles') t where t->>'t' not in ('plain','forest','farm','mine','gold')), 'kr: known terrains');
        -- connected: every tile but the first has a neighbour with a smaller index (grown as a blob)
        perform pg_temp.check(not exists (select 1 from generate_series(1, n - 1) g
            where not exists (select 1 from unnest(public._kr_nbrs(mp, g)) z where z < g)), 'kr: map is connected');
    end loop;
    perform pg_temp.expect_error($q$ select public._startcheck_kralliklar('{}', 3) $q$, 'WRONG_PLAYER_COUNT');
    perform pg_temp.expect_error($q$ select public._startcheck_kralliklar('{}', 21) $q$, 'WRONG_PLAYER_COUNT');

    -- ============================================================ 20-kingdom start (performance + fairness)
    t0 := clock_timestamp();
    room := pg_temp.start('kralliklar', us[1:20], '{}');
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'tiles') = 600, 'kr20: 600 tiles');
    perform pg_temp.check((select count(*) from jsonb_each(s->'k')) = 20, 'kr20: 20 kingdoms');
    perform pg_temp.check((select count(distinct value->>'color') from jsonb_each(s->'k')) = 20, 'kr20: distinct colours');
    for i in 1..20 loop
        perform pg_temp.check(pg_temp.owned(s, i) = 7, 'kr20: capital + ring for kingdom ' || i);
    end loop;
    perform pg_temp.check((select min(public._kr_tdist(s, pg_temp.cap(s, a), pg_temp.cap(s, b))) from generate_series(1, 20) a, generate_series(1, 20) b where a < b) >= 3,
                          'kr20: capitals spread apart (rings never touch)');
    -- one turn of 20 kingdoms with orders
    for i in 1..20 loop
        nb := public._kr_nbrs(s, pg_temp.cap(s, i));
        perform pg_temp.move(room, pg_temp.ku(s, i), 'orders', jsonb_build_object('moves', jsonb_build_array(jsonb_build_object('from', pg_temp.cap(s, i), 'to', nb[1], 'n', 5))));
    end loop;
    perform pg_temp.tick(room);
    perform pg_temp.check((pg_temp.st(room)->>'turn_no')::int = 2, 'kr20: a turn is processed');
    perform pg_temp.check((select bool_and((select array_agg((xe.value)::int) from jsonb_array_elements_text(pg_temp.st(room)->'seen'->kk.key) xe(value))
                                   @> public._kr_visible(pg_temp.st(room), array[kk.key::int]))
                           from jsonb_each(pg_temp.st(room)->'k') kk where (kk.value->>'alive')::boolean), 'kr20: turn end remembers what every kingdom sees');
    perform pg_temp.check(clock_timestamp() - t0 < interval '30 seconds', 'kr20: still fast with the memory');
    perform pg_temp.check(clock_timestamp() - t0 < interval '30 seconds', 'kr20: start and one turn are fast enough');

    -- terrain mix follows the configured weights (0.9.0 fix: seeds used to be all "farm")
    declare mixes jsonb := '{}'::jsonb; m jsonb; tt record;
    begin
        for i in 1..6 loop
            m := public._kr_gen_map(600, public._kr_balance());
            for tt in select t->>'t' as t, count(*) as n from jsonb_array_elements(m->'tiles') t group by 1 loop
                mixes := mixes || jsonb_build_object(tt.t, coalesce((mixes->>tt.t)::int, 0) + tt.n);
            end loop;
        end loop;
        perform pg_temp.check((select count(*) from jsonb_object_keys(mixes)) = 5, 'kr: all five terrains appear: ' || mixes::text);
        perform pg_temp.check((mixes->>'plain')::int > (mixes->>'farm')::int and (mixes->>'farm')::int < 3600 * 0.4,
                              'kr: plains outnumber farms, farms are not the bulk: ' || mixes::text);
    end;

    -- ============================================================ 4 kingdoms
    room := pg_temp.start('kralliklar', us[1:4], '{"max_turns": 60}');
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'tiles') = 200 and (s->'opts'->>'max_turns')::int = 60, 'kr: 200 tiles for 4, options kept');
    perform pg_temp.check((pg_temp.rd(room)).public->'tiles' = 'null'::jsonb or not ((pg_temp.rd(room)).public ? 'tiles') or (pg_temp.rd(room)).public->>'tiles' is null,
                          'kr: no tile contents in the public state');
    perform pg_temp.check(jsonb_array_length((pg_temp.rd(room)).public->'map') = 200, 'kr: map shape is public');
    u1 := pg_temp.ku(s, 1); u2 := pg_temp.ku(s, 2); u3 := pg_temp.ku(s, 3); u4 := pg_temp.ku(s, 4);
    c1 := pg_temp.cap(s, 1); c2 := pg_temp.cap(s, 2);
    -- the ring is the same for everybody
    for i in 1..4 loop
        perform pg_temp.check((select array_agg(s->'tiles'->z->>'t' order by s->'tiles'->z->>'t') from unnest(public._kr_nbrs(s, pg_temp.cap(s, i))) z)
                              = array['farm','farm','forest','forest','mine','plain'], 'kr: fair starting ring');
    end loop;

    -- fog of war: own tiles + one ring, nothing of other kingdoms' capitals
    v := pg_temp.view(room, u1)->'me';
    perform pg_temp.check((v->>'k')::int = 1 and (v->'res'->>'wood')::int = 40, 'kr: own kingdom and stock');
    perform pg_temp.check(jsonb_array_length(v->'tiles') = cardinality(public._kr_visible(s, array[1])), 'kr: visible tiles only');
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(v->'tiles') t where (t->>'i')::int = c2), 'kr: enemy capital hidden');
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(v->'tiles') t
                                       where public._kr_tdist(s, (t->>'i')::int, c1) > 2), 'kr: nothing beyond the ring');

    -- explored memory (0.9.0): terrain only for remembered tiles, never units or owners
    -- at the start: terrain known up to 3 steps from own land (capital + ring), units only in the visible ring
    perform pg_temp.check((select array_agg((xe.value)::int order by (xe.value)::int) from jsonb_array_elements_text(s->'seen'->'1') xe(value))
                          = (select array_agg(g order by g) from generate_series(0, jsonb_array_length(s->'tiles') - 1) g
                              where exists (select 1 from jsonb_array_elements(s->'tiles') with ordinality e(t, ord)
                                             where (e.t->>'o')::int = 1 and public._kr_tdist(s, g, (e.ord - 1)::int) <= 3)),
                          'kr: memory = every tile within 3 steps of own land');
    perform pg_temp.check(jsonb_array_length(v->'explored') + jsonb_array_length(v->'tiles') = jsonb_array_length(s->'seen'->'1'),
                          'kr: explored = memory minus the visible tiles');
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(v->'explored') t where t ?| array['o','x','b','l','cap']),
                          'kr: explored tiles carry terrain only');
    perform pg_temp.check(not ((pg_temp.rd(room)).public ? 'seen'), 'kr: memory is not public');
    perform pg_temp.setst(room, jsonb_set(s, '{seen,1}', (s->'seen'->'1') || jsonb_build_array(c2)));
    v := pg_temp.view(room, u1)->'me';
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(v->'explored') t where (t->>'i')::int = c2
                          and t->>'t' = s->'tiles'->c2->>'t' and (t->>'seen')::boolean), 'kr: remembered tile shows its terrain');
    perform pg_temp.check((select array_agg(k order by k) from jsonb_object_keys((select t from jsonb_array_elements(v->'explored') t where (t->>'i')::int = c2)) k)
                          = array['i','seen','t'], 'kr: remembered enemy capital carries no owner, troops, building, life or capital');
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(v->'tiles') t where (t->>'i')::int = c2), 'kr: remembered enemy capital stays out of the live tiles');
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(pg_temp.view(room, u2)->'me'->'explored') t where (t->>'i')::int = c1)
                          or (select (xe.value)::int = c1 from jsonb_array_elements_text(s->'seen'->'2') xe(value) where (xe.value)::int = c1 limit 1), 'kr: memory is per kingdom');
    perform pg_temp.setst(room, s);

    -- orders validation
    perform pg_temp.act(u1);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'orders', '{"moves":[{"from":%s,"to":%s,"n":3}]}') $q$, (pg_temp.rd(room)).id, c2, c1), 'NOT_YOUR_TILE');
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'orders', '{"builds":[{"tile":%s,"type":"barracks"}]}') $q$, (pg_temp.rd(room)).id, c1), 'TILE_TAKEN');
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'orders', '{"builds":[{"tile":%s,"type":"castle"}]}') $q$, (pg_temp.rd(room)).id, c1), 'INVALID_INPUT');
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'orders', '{"moves":[{"from":%s,"to":%s,"n":0}]}') $q$, (pg_temp.rd(room)).id, c1, c2), 'INVALID_INPUT');
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'orders', '{"moves":[{"from":%s,"to":99999,"n":1}]}') $q$, (pg_temp.rd(room)).id, c1), 'INVALID_INPUT');
    -- rename (first turn), colour taken
    perform pg_temp.move(room, u1, 'rename', '{"name":"Anadolu"}');
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'rename', '{"name":"Ege","color":"%s"}') $q$, (pg_temp.rd(room)).id, pg_temp.st(room)->'k'->'2'->>'color'), 'COLOR_TAKEN');
    perform pg_temp.check(pg_temp.st(room)->'k'->'1'->>'name' = 'Anadolu', 'kr: rename');

    -- ============================================================ movement timing, capture of neutral land, battles
    s := pg_temp.st(room);
    s := jsonb_set(s, '{bal,luck}', '0');
    nb := public._kr_nbrs(s, c1);
    r1 := nb[4];                                                     -- 'plain' ring tile of kingdom 1
    -- a neutral tile two steps out from the capital, next to r1
    select g into j from generate_series(0, 199) g where public._kr_tdist(s, c1, g) = 2 and (s->'tiles'->g->>'o')::int = 0 order by g limit 1;
    s := pg_temp.settile(s, j, '{"t":"plain","x":0,"l":0}');
    -- a far tile (distance >= 5) for a slow march
    select g into far from generate_series(0, 199) g where public._kr_tdist(s, c1, g) between 5 and 6 and (s->'tiles'->g->>'o')::int = 0 limit 1;
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, u1, 'orders', jsonb_build_object('moves', jsonb_build_array(
        jsonb_build_object('from', c1, 'to', j, 'n', 4), jsonb_build_object('from', c1, 'to', far, 'n', 3))));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((s->'tiles'->j->>'o')::int = 1 and (s->'tiles'->j->>'x')::int = 4, 'kr: distance 2 arrives in the same turn and takes neutral land');
    perform pg_temp.check((s->'tiles'->c1->>'x')::int = 15 - 7 + 2, 'kr: troops left the capital, capital income +2');
    perform pg_temp.check(jsonb_array_length(s->'armies') = 1 and (s->'armies'->0->>'arrive')::int = 3, 'kr: distance 5-6 at speed 2 arrives two turns later');
    perform pg_temp.check((s->'tiles'->far->>'o')::int = 0, 'kr: the slow army is still on the way');
    v := pg_temp.view(room, u2)->'me';
    perform pg_temp.check(jsonb_array_length(v->'armies') = 0, 'kr: other kingdoms do not see the army');
    perform pg_temp.tick(room);
    perform pg_temp.check((pg_temp.st(room)->'tiles'->far->>'o')::int = 0, 'kr: not yet (turn 2)');
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((s->'tiles'->far->>'o')::int = 1 and (s->'tiles'->far->>'x')::int >= 1, 'kr: arrives on turn 3');

    -- battle on plain: 10 attackers vs 5 defenders, luck 0 -> 5 survive
    s := pg_temp.settile(s, r1, '{"o":2,"x":5,"t":"plain","b":null}');
    s := pg_temp.settile(s, c1, '{"x":20}');
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, u1, 'orders', jsonb_build_object('moves', jsonb_build_array(jsonb_build_object('from', c1, 'to', r1, 'n', 10))));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((s->'tiles'->r1->>'o')::int = 1 and (s->'tiles'->r1->>'x')::int = 5, 'kr: battle won, 10*(1-5/10) = 5 left');
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(s->'events'->'2') e where e->>'e' = 'battle' and (e->>'won')::boolean), 'kr: defender gets the report');
    -- forest defence: 9 defenders * 1.2 = 10.8 > 10 -> the defender holds with round(9*(1-10/10.8)) = 1
    s := pg_temp.settile(s, r1, '{"o":2,"x":9,"t":"forest","b":null}');
    s := pg_temp.settile(s, c1, '{"x":20}');
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, u1, 'orders', jsonb_build_object('moves', jsonb_build_array(jsonb_build_object('from', c1, 'to', r1, 'n', 10))));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((s->'tiles'->r1->>'o')::int = 2 and (s->'tiles'->r1->>'x')::int = 1, 'kr: terrain bonus holds the forest');
    -- reinforcements are counted before attackers on the same tile
    s := pg_temp.settile(s, r1, '{"o":1,"x":0,"t":"plain","b":null}');
    s := pg_temp.settile(s, c1, '{"x":20}');
    r2 := (select z from unnest(public._kr_nbrs(s, r1)) z where z <> c1 and z = any(public._kr_nbrs(s, c1)) limit 1);   -- a ring neighbour
    s := pg_temp.settile(s, r2, '{"o":2,"x":8,"t":"plain"}');
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, u1, 'orders', jsonb_build_object('moves', jsonb_build_array(jsonb_build_object('from', c1, 'to', r1, 'n', 12))));
    perform pg_temp.move(room, u2, 'orders', jsonb_build_object('moves', jsonb_build_array(jsonb_build_object('from', r2, 'to', r1, 'n', 8))));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((s->'tiles'->r1->>'o')::int = 1 and (s->'tiles'->r1->>'x')::int = 4, 'kr: 12 reinforcements meet 8 attackers -> 4 left');

    -- ============================================================ production, lifetimes, buildings
    s := pg_temp.st(room);
    i := (select z from unnest(public._kr_nbrs(s, c1)) z where s->'tiles'->z->>'t' = 'forest' limit 1);
    s := pg_temp.settile(s, i, '{"o":1,"l":1}');
    -- no other resource tiles for kingdom 1 so the income is exact
    select jsonb_agg(case when (t->>'o')::int = 1 and (e.ord - 1) <> i then t || '{"l":0}' else t end order by e.ord) into x
      from jsonb_array_elements(s->'tiles') with ordinality e(t, ord);
    s := jsonb_set(s, '{tiles}', x);
    s := jsonb_set(s, '{k,1,res}', '{"wood":100,"iron":100,"gold":0,"food":2}');
    perform pg_temp.setst(room, s);
    w := (select z from unnest(public._kr_nbrs(s, c1)) z where s->'tiles'->z->>'t' = 'plain' and s->'tiles'->z->>'b' is null and (s->'tiles'->z->>'o')::int = 1 limit 1);
    perform pg_temp.move(room, u1, 'orders', jsonb_build_object('builds', jsonb_build_array(jsonb_build_object('tile', w, 'type', 'barracks'))));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((s->'tiles'->w->>'b') = 'barracks', 'kr: barracks built');
    perform pg_temp.check((s->'k'->'1'->'res'->>'wood')::int = 100 - 30 + 8 + 3 and (s->'k'->'1'->'res'->>'iron')::int = 85, 'kr: cost paid, forest + capital wood');
    perform pg_temp.check((s->'tiles'->i->>'l')::int = 0 and (s->'tiles'->i->>'o')::int = 1, 'kr: forest depleted but still owned');
    -- food 2 + 3 capital = 5 >= upkeep 3: five soldiers
    perform pg_temp.check((s->'tiles'->w->>'x')::int >= 5 and (s->'k'->'1'->'res'->>'food')::int = 2, 'kr: barracks trains 5 for 3 food');
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((s->'k'->'1'->'res'->>'wood')::int = 81 + 3, 'kr: depleted forest gives nothing more');
    -- unaffordable building fails at turn end
    s := jsonb_set(s, '{k,1,res}', '{"wood":0,"iron":0,"gold":0,"food":10}');
    perform pg_temp.setst(room, s);
    w := (select z from unnest(public._kr_nbrs(s, c1)) z where s->'tiles'->z->>'b' is null and (s->'tiles'->z->>'o')::int = 1 limit 1);
    perform pg_temp.move(room, u1, 'orders', jsonb_build_object('builds', jsonb_build_array(jsonb_build_object('tile', w, 'type', 'market'))));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check(s->'tiles'->w->>'b' is null and exists (select 1 from jsonb_array_elements(s->'events'->'1') e where e->>'e' = 'build_failed' and e->>'why' = 'RESOURCES'),
                          'kr: unaffordable building fails');

    -- ============================================================ letters
    perform pg_temp.move(room, u1, 'letter', '{"to":[2],"subject":"Barış?","body":"Sınırda savaşmayalım."}');
    perform pg_temp.check(jsonb_array_length(pg_temp.view(room, u2)->'me'->'inbox') = 1, 'kr: letter delivered');
    perform pg_temp.check(pg_temp.view(room, u2)->'me'->'inbox'->0->>'subject' = 'Barış?', 'kr: subject kept');
    perform pg_temp.check(jsonb_array_length(pg_temp.view(room, u3)->'me'->'inbox') = 0, 'kr: other kingdoms do not see the letter');
    perform pg_temp.check(jsonb_array_length(pg_temp.view(room, u1)->'me'->'sent') = 1, 'kr: sender keeps a copy');
    perform pg_temp.check(not ((pg_temp.rd(room)).public::text like '%Sınırda%'), 'kr: letters are not public');
    perform pg_temp.act(u1);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'letter', '{"to":[1],"body":"x"}') $q$, (pg_temp.rd(room)).id), 'INVALID_TARGET');
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'letter', '{"to":[2],"body":""}') $q$, (pg_temp.rd(room)).id), 'MESSAGE_EMPTY');

    -- ============================================================ trades
    s := pg_temp.st(room);
    s := jsonb_set(s, '{k,1,res}', '{"wood":50,"iron":0,"gold":0,"food":10}');
    s := jsonb_set(s, '{k,2,res}', '{"wood":0,"iron":30,"gold":0,"food":10}');
    perform pg_temp.setst(room, s);
    v := pg_temp.move(room, u1, 'offer', '{"to":2,"type":"trade","give":{"wood":20},"get":{"iron":10}}');
    n := (pg_temp.st(room)->'offers'->-1->>'id')::int;
    perform pg_temp.move(room, u2, 'respond', jsonb_build_object('id', n, 'accept', true));
    s := pg_temp.st(room);
    perform pg_temp.check((s->'k'->'1'->'res'->>'wood')::int = 30 and (s->'k'->'1'->'res'->>'iron')::int = 10
                      and (s->'k'->'2'->'res'->>'wood')::int = 20 and (s->'k'->'2'->'res'->>'iron')::int = 20, 'kr: atomic swap');
    -- short of resources: the trade fails, nothing moves
    v := pg_temp.move(room, u1, 'offer', '{"to":2,"type":"trade","give":{"wood":5},"get":{"iron":999}}');
    n := (pg_temp.st(room)->'offers'->-1->>'id')::int;
    perform pg_temp.move(room, u2, 'respond', jsonb_build_object('id', n, 'accept', true));
    s := pg_temp.st(room);
    perform pg_temp.check((s->'k'->'1'->'res'->>'wood')::int = 30 and (s->'k'->'2'->'res'->>'iron')::int = 20
                      and s->'offers'->-1->>'status' = 'failed', 'kr: trade fails when short');
    perform pg_temp.act(u3);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'respond', '{"id":%s,"accept":true}') $q$, (pg_temp.rd(room)).id, n), 'OFFER_NOT_FOUND');

    -- ============================================================ alliances (max 3), shared vision, attack breaks it
    v := pg_temp.move(room, u1, 'offer', '{"to":2,"type":"alliance"}');
    perform pg_temp.move(room, u2, 'respond', jsonb_build_object('id', (pg_temp.st(room)->'offers'->-1->>'id')::int, 'accept', true));
    v := pg_temp.move(room, u3, 'offer', '{"to":1,"type":"alliance"}');
    perform pg_temp.move(room, u1, 'respond', jsonb_build_object('id', (pg_temp.st(room)->'offers'->-1->>'id')::int, 'accept', true));
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'alliances') = 1 and jsonb_array_length(s->'alliances'->0->'m') = 3, 'kr: alliance of three');
    v := pg_temp.move(room, u4, 'offer', '{"to":2,"type":"alliance"}');
    perform pg_temp.move(room, u2, 'respond', jsonb_build_object('id', (pg_temp.st(room)->'offers'->-1->>'id')::int, 'accept', true));
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'alliances'->0->'m') = 3 and s->'offers'->-1->>'status' = 'failed', 'kr: a fourth member is refused');
    perform pg_temp.act(u1);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'offer', '{"to":2,"type":"alliance"}') $q$, (pg_temp.rd(room)).id), 'ALREADY_ALLIED');
    v := pg_temp.view(room, u1)->'me';
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(v->'tiles') t where (t->>'i')::int = c2), 'kr: allies share vision');
    perform pg_temp.check((pg_temp.rd(room)).public->'alliances'->0->'m' is not null, 'kr: alliances are public');
    -- kingdom 3 attacks ally 1: the alliance breaks for 3
    s := pg_temp.st(room);
    s := pg_temp.settile(s, pg_temp.cap(s, 3), '{"x":30}');
    i := (public._kr_nbrs(s, pg_temp.cap(s, 3)))[1];
    s := pg_temp.settile(s, i, '{"o":1,"x":0}');                  -- a tile of ally 1 next to kingdom 3
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, u3, 'orders', jsonb_build_object('moves', jsonb_build_array(jsonb_build_object('from', pg_temp.cap(s, 3), 'to', i, 'n', 1))));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check(not (s->'alliances'->0->'m' @> '3') and s->'alliances'->0->'m' @> '[1,2]', 'kr: attacking an ally breaks the alliance');
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(s->'log') l where l->>'e' = 'alliance_broken' and (l->>'k')::int = 3), 'kr: broken alliance is announced');
    -- non-aggression pact, broken by an attack
    v := pg_temp.move(room, u4, 'offer', '{"to":3,"type":"nap","turns":5}');
    perform pg_temp.move(room, u3, 'respond', jsonb_build_object('id', (pg_temp.st(room)->'offers'->-1->>'id')::int, 'accept', true));
    perform pg_temp.check(jsonb_array_length(pg_temp.view(room, u4)->'me'->'pacts') = 1, 'kr: pact recorded');
    s := pg_temp.st(room);
    s := pg_temp.settile(s, pg_temp.cap(s, 4), '{"x":30}');
    i := (public._kr_nbrs(s, pg_temp.cap(s, 4)))[1];
    s := pg_temp.settile(s, i, '{"o":3,"x":0}');                  -- a tile of kingdom 3 next to kingdom 4
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, u4, 'orders', jsonb_build_object('moves', jsonb_build_array(jsonb_build_object('from', pg_temp.cap(s, 4), 'to', i, 'n', 1))));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'pacts') = 0 and exists (select 1 from jsonb_array_elements(s->'log') l where l->>'e' = 'pact_broken'), 'kr: pact broken by attack');

    -- ============================================================ ready ends the planning early
    perform pg_temp.move(room, u1, 'ready', '{}'); perform pg_temp.move(room, u2, 'ready', '{}'); perform pg_temp.move(room, u3, 'ready', '{}');
    perform pg_temp.check((pg_temp.rd(room)).deadline_at > now() + interval '10 seconds', 'kr: still waiting for kingdom 4');
    perform pg_temp.move(room, u4, 'ready', '{}');
    perform pg_temp.check((pg_temp.rd(room)).deadline_at <= now() + interval '2 seconds', 'kr: all ready -> turn ends');
    perform pg_temp.tick(room);

    -- ============================================================ capital capture eliminates; territory passes; victory
    s := pg_temp.st(room);
    s := jsonb_set(s, '{bal,luck}', '0');
    s := pg_temp.settile(s, c2, '{"x":0}');
    r2 := (public._kr_nbrs(s, c2))[1];
    s := pg_temp.settile(s, r2, '{"o":1,"x":10}');
    s := jsonb_set(s, '{k,2,res}', '{"wood":7,"iron":7,"gold":7,"food":7}');
    s := jsonb_set(s, '{k,1,res}', '{"wood":0,"iron":0,"gold":0,"food":0}');
    perform pg_temp.setst(room, s);
    n := pg_temp.owned(s, 1) + pg_temp.owned(s, 2);
    perform pg_temp.move(room, u1, 'orders', jsonb_build_object('moves', jsonb_build_array(jsonb_build_object('from', r2, 'to', c2, 'n', 10))));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check(not (s->'k'->'2'->>'alive')::boolean and pg_temp.owned(s, 2) = 0, 'kr: capital lost -> kingdom eliminated');
    perform pg_temp.check(pg_temp.owned(s, 1) = n, 'kr: territory goes to the conqueror');
    perform pg_temp.check((s->'k'->'1'->'res'->>'iron')::int >= 7, 'kr: stock goes to the conqueror');
    perform pg_temp.act(u2);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'ready', '{}') $q$, (pg_temp.rd(room)).id), 'ELIMINATED');
    perform pg_temp.check(exists (select 1 from jsonb_array_elements((pg_temp.rd(room)).public->'log') l where l->>'e' = 'eliminated' and (l->>'k')::int = 2), 'kr: elimination is public');
    -- 3 then 4 fall: kingdom 1 is the last one standing
    s := public._kr_eliminate(pg_temp.st(room), 3, 1);
    perform pg_temp.setst(room, s);
    perform pg_temp.tick(room);
    s := public._kr_eliminate(pg_temp.st(room), 4, 1);
    perform pg_temp.setst(room, s);
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((s->>'over')::boolean and (s->>'winner')::int = 1, 'kr: last kingdom standing wins');
    perform pg_temp.check((s->'points'->>u1::text)::int = 3 and (s->'points'->>u4::text)::int = 2 and (s->'points'->>u3::text)::int = 1
                          and (s->'points'->>u2::text)::int = 0, 'kr: placement by elimination order');
    perform pg_temp.check((pg_temp.rd(room)).phase = 'done' and (pg_temp.rd(room)).public->'tiles' is not null, 'kr: map revealed at the end');
    perform pg_temp.check((select status from public.matches where room_id = room order by started_at desc limit 1) = 'finished', 'kr: match finished');
    perform pg_temp.check((select (stats->>'wins')::int from public.player_game_stats where user_id = u1 and game_mode = 'kralliklar') = 1, 'kr: stats recorded');

    -- ============================================================ safety turn limit
    room := pg_temp.start('kralliklar', us[5:8], '{"max_turns": 60}');
    s := pg_temp.st(room);
    perform pg_temp.setst(room, s || '{"turn_no": 60}');
    perform pg_temp.tick(room);
    perform pg_temp.check((pg_temp.st(room)->>'over')::boolean, 'kr: turn limit ends the game');

    -- balance is admin-configurable (merged over the defaults)
    update public.economy_config set value = '{"speed": 3, "buildings": {"barracks": {"cost": {"wood": 1}, "troops": 9, "upkeep_food": 0}}}' where key = 'kralliklar_balance';
    perform pg_temp.check((public._kr_balance()->>'speed')::int = 3 and (public._kr_balance()->'buildings'->'barracks'->>'troops')::int = 9
                          and public._kr_balance()->'buildings' ? 'market', 'kr: balance override merges');
end $$;

select 'KRALLIKLAR TESTS PASSED' as result;
rollback;
