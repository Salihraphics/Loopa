-- =====================================================================
-- LOOPA — Buckshot: 2/3/4 players, 3 rounds, HP 3/4/5, magazines of 8, items, privacy,
-- eliminations, money bag once, champion, replayed / unauthorised actions, timeouts.
-- Randomness is pinned by writing the server state where a test needs a known magazine.
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
    return public.mode_act((pg_temp.rd(p_room)).id, k, p || jsonb_build_object('nonce', coalesce(p->>'nonce', gen_random_uuid()::text)));
end $$;
create or replace function pg_temp.fire(p_room uuid, u uuid, t uuid) returns jsonb language sql as $$
    select pg_temp.move(p_room, u, 'fire', jsonb_build_object('target', t));
$$;
create or replace function pg_temp.item(p_room uuid, u uuid, it text, t uuid default null) returns jsonb language sql as $$
    select pg_temp.move(p_room, u, 'item', jsonb_build_object('item', it, 'target', t));
$$;
create or replace function pg_temp.view(p_room uuid, u uuid) returns jsonb language plpgsql as $$
begin perform pg_temp.act(u); return public.get_mode_state(p_room); end $$;
create or replace function pg_temp.start(p_players uuid[], p_opts jsonb default '{}') returns uuid language plpgsql as $$
declare room jsonb; i int; st jsonb;
begin
    perform pg_temp.act(p_players[1]);
    room := public.create_room(false);
    perform public.update_room_settings((room->>'id')::uuid,
        (select settings from public.rooms where id = (room->>'id')::uuid) || jsonb_build_object('game_mode', 'buckshot', 'max_players', 4, 'configured', false), null);
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
-- known state: magazine (from position 0), turn, hp / items per player
create or replace function pg_temp.pin(p_room uuid, shells boolean[], turn uuid, patch jsonb default '{}') returns jsonb language plpgsql as $$
declare s jsonb := pg_temp.st(p_room); k text;
begin
    s := s || jsonb_build_object('turn', turn::text, 'mag', jsonb_build_object('no', (s->'mag'->>'no')::int + 1, 'pos', 0,
        'loaded_live', (select count(*) from unnest(shells) x where x), 'loaded_blank', (select count(*) from unnest(shells) x where not x),
        'live_seen', 0, 'blank_seen', 0, 'inverted', 0, 'shells', to_jsonb(shells)), 'deadline', public._tg_deadline(30));
    for k in select jsonb_object_keys(patch) loop
        s := jsonb_set(s, array['p', k], (s->'p'->k) || case when jsonb_typeof(patch->k) = 'string' then (patch->>k)::jsonb else patch->k end);
    end loop;
    perform pg_temp.setst(p_room, s);
    return s;
end $$;
create or replace function pg_temp.hp(p_room uuid, u uuid) returns int language sql as $$ select (pg_temp.st(p_room)->'p'->u::text->>'hp')::int; $$;
create or replace function pg_temp.inv(p_room uuid, u uuid) returns jsonb language sql as $$ select pg_temp.st(p_room)->'p'->u::text->'inv'; $$;
create or replace function pg_temp.turn(p_room uuid) returns uuid language sql as $$ select (pg_temp.st(p_room)->>'turn')::uuid; $$;

insert into auth.users(id, is_anonymous)
select ('b0000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid, true from generate_series(1, 9) i;

do $$
declare
    us uuid[] := array(select ('b0000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid from generate_series(1, 9) i);
    room uuid; s jsonb; v jsonb; a uuid; b uuid; c uuid; d uuid; i int; n int; heal int := 0; hurt int := 0; mg jsonb; t uuid; m uuid;
    pool text[]; coins0 int; x jsonb;
begin
    for i in 1..9 loop perform pg_temp.act(us[i]); perform public.ensure_profile('Atıcı ' || i); end loop;
    perform public._grant_plus(us[1], 30, 'admin', 'test', us[1]);
    perform public._grant_plus(us[5], 30, 'admin', 'test', us[1]);

    -- ============================================================ (7) magazines: 8 shells, 1-7 live; pools
    for i in 1..300 loop
        mg := public._bs_magazine(1);
        perform pg_temp.check(jsonb_array_length(mg->'shells') = 8, 'bs: 8 shells');
        n := (select count(*) from jsonb_array_elements_text(mg->'shells') sh where sh::boolean);
        perform pg_temp.check(n between 1 and 7 and n = (mg->>'loaded_live')::int and 8 - n = (mg->>'loaded_blank')::int, 'bs: at least one live and one blank, counts match');
    end loop;
    perform pg_temp.check(not ('remote' = any(public._bs_pool(2))) and 'remote' = any(public._bs_pool(3)) and array_length(public._bs_pool(4), 1) = 9,
                          'bs: the remote is only dealt with 3+ players; 9 items');
    -- (9) capacity: never above 8, the rest is lost
    perform pg_temp.check(jsonb_array_length(public._bs_deal('["beer","beer","beer","beer","beer","beer","beer"]', 4, public._bs_pool(4), 8)) = 8, 'bs: deal stops at 8');
    perform pg_temp.check(jsonb_array_length(public._bs_deal('[]', 4, public._bs_pool(2), 8)) = 4, 'bs: 4 items into an empty inventory');
    perform pg_temp.expect_error($q$ select public._startcheck_buckshot('{}', 1) $q$, 'WRONG_PLAYER_COUNT');
    perform pg_temp.expect_error($q$ select public._startcheck_buckshot('{}', 5) $q$, 'WRONG_PLAYER_COUNT');

    -- ============================================================ (1)(6) two players, round 1 = 3 HP, 4 items each
    room := pg_temp.start(us[1:2]);
    s := pg_temp.st(room);
    a := (s->'order'->>0)::uuid; b := (s->'order'->>1)::uuid;
    perform pg_temp.check((s->>'round')::int = 1 and (s->>'max_hp')::int = 3 and pg_temp.hp(room, a) = 3 and pg_temp.hp(room, b) = 3, 'bs: round 1 starts at 3 HP');
    perform pg_temp.check(jsonb_array_length(pg_temp.inv(room, a)) = 4 and jsonb_array_length(pg_temp.inv(room, b)) = 4, 'bs: 4 items each');
    perform pg_temp.check(s->>'turn' in (a::text, b::text), 'bs: a random starter');

    -- (20) unauthorised / invalid actions
    perform pg_temp.pin(room, array[false, true, false, true, false, true, false, true], a, jsonb_build_object(a::text, '{"inv": ["beer","cigarettes","magnifier","saw"]}', b::text, '{"inv": ["inverter"]}'));
    perform pg_temp.act(b);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'fire', '{"target":"%s","nonce":"nonce-b-0001"}') $q$, (pg_temp.rd(room)).id, a), 'NOT_YOUR_TURN');
    perform pg_temp.act(us[9]);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'fire', '{"target":"%s","nonce":"nonce-x-0001"}') $q$, (pg_temp.rd(room)).id, a), 'NOT_A_PARTICIPANT');
    perform pg_temp.act(a);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'fire', '{"target":"%s"}') $q$, (pg_temp.rd(room)).id, b), 'INVALID_INPUT');            -- no nonce
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'fire', '{"target":"%s","nonce":"nonce-a-0001"}') $q$, (pg_temp.rd(room)).id, us[9]), 'INVALID_TARGET');
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'item', '{"item":"inverter","nonce":"nonce-a-0002"}') $q$, (pg_temp.rd(room)).id), 'ITEM_NOT_OWNED');
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'bet', '{"coins":100,"nonce":"nonce-a-0003"}') $q$, (pg_temp.rd(room)).id), 'INVALID_ACTION');
    -- cigarettes at full HP: refused and NOT consumed
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'item', '{"item":"cigarettes","nonce":"nonce-a-0004"}') $q$, (pg_temp.rd(room)).id), 'ITEM_NOT_USABLE');
    perform pg_temp.check(pg_temp.inv(room, a) @> '["cigarettes"]', 'bs: refused item stays');

    -- (11) magnifier: only the user learns the next shell
    perform pg_temp.item(room, a, 'magnifier');
    v := pg_temp.view(room, a)->'me';
    perform pg_temp.check(v->'known'->0->>'ahead' = '0' and (v->'known'->0->>'live')::boolean = false, 'bs: magnifier shows the next shell (blank) to its user');
    perform pg_temp.check(jsonb_array_length(pg_temp.view(room, b)->'me'->'known') = 0, 'bs: the opponent learns nothing');
    perform pg_temp.check((pg_temp.rd(room)).public::text not like '%"known"%' and (pg_temp.rd(room)).public->'mag'->'shells' is null, 'bs: no shell order or knowledge in the public state');
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements((pg_temp.rd(room)).public->'log') l where l->>'item' = 'magnifier' and l ? 'live'), 'bs: the log hides the magnifier result');
    -- (18-like) replay: blank at self keeps the turn; the same nonce again is refused, nothing happens twice
    perform pg_temp.move(room, a, 'fire', jsonb_build_object('target', a, 'nonce', 'replay-nonce-1'));
    perform pg_temp.check(pg_temp.turn(room) = a and pg_temp.hp(room, a) = 3, 'bs: blank at yourself keeps the turn');
    perform pg_temp.check((pg_temp.st(room)->'mag'->>'pos')::int = 1 and (pg_temp.st(room)->'mag'->>'blank_seen')::int = 1, 'bs: the fired shell left the magazine');
    perform pg_temp.act(a);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'fire', '{"target":"%s","nonce":"replay-nonce-1"}') $q$, (pg_temp.rd(room)).id, a), 'DUPLICATE_ACTION');
    perform pg_temp.check((pg_temp.st(room)->'mag'->>'pos')::int = 1, 'bs: a replayed request does not fire again');
    -- known shell from the magnifier is gone once fired
    perform pg_temp.check(jsonb_array_length(pg_temp.view(room, a)->'me'->'known') = 0, 'bs: fired shells drop out of the knowledge');
    -- (12) saw: next live shell does 2; shell 2 is live
    perform pg_temp.item(room, a, 'saw');
    perform pg_temp.check((pg_temp.st(room)->'p'->a::text->>'saw')::boolean, 'bs: saw active');
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'item', '{"item":"saw","nonce":"nonce-a-0005"}') $q$, (pg_temp.rd(room)).id), 'ITEM_NOT_OWNED');
    perform pg_temp.fire(room, a, b);
    perform pg_temp.check(pg_temp.hp(room, b) = 1 and not (pg_temp.st(room)->'p'->a::text->>'saw')::boolean, 'bs: saw doubles a live shell (3 -> 1) and is spent');
    perform pg_temp.check(pg_temp.turn(room) = b, 'bs: a live shot passes the turn');
    -- (12) inverter: shell 3 is blank -> live
    perform pg_temp.item(room, b, 'inverter');
    perform pg_temp.check((pg_temp.st(room)->'mag'->>'inverted')::int = 1 and (pg_temp.rd(room)).public->'mag'->>'inverted' = '1', 'bs: the inversion is public, the result is not');
    perform pg_temp.fire(room, b, a);
    perform pg_temp.check(pg_temp.hp(room, a) = 2, 'bs: the inverted blank hit as live');
    -- beer: shell 4 (live) is ejected without damage, A keeps the turn
    perform pg_temp.item(room, a, 'beer');
    perform pg_temp.check(pg_temp.turn(room) = a and pg_temp.hp(room, a) = 2 and pg_temp.hp(room, b) = 1 and (pg_temp.st(room)->'mag'->>'pos')::int = 4, 'bs: beer ejects a shell');
    -- cigarettes heal 1 below max
    perform pg_temp.item(room, a, 'cigarettes');
    perform pg_temp.check(pg_temp.hp(room, a) = 3, 'bs: cigarettes +1');
    -- (16) shell 5 blank at B, shell 6 live: B shoots A? we make A win: A fires 5 (blank) at B -> turn to B
    perform pg_temp.fire(room, a, b);
    perform pg_temp.check(pg_temp.turn(room) = b and pg_temp.hp(room, b) = 1, 'bs: blank at an opponent passes the turn');
    -- B shoots itself with shell 6 (live): B is out, A is the last alive and wins round 1
    select coalesce(sum(amount), 0) into coins0 from public.coin_transactions where user_id = a;
    perform pg_temp.fire(room, b, b);
    s := (select payload from public.mode_secrets where round_id = (select id from public.mode_rounds where room_id = room and round_number = 1) and kind = 'g');
    perform pg_temp.check((s->>'over')::boolean and s->>'winner' = a::text and (s->'points'->>a::text)::int = 10, 'bs: last alive wins the round (10 points)');
    perform pg_temp.check((select count(*) from public.coin_transactions where user_id = a and source_key like 'buckshot_bag:%') = 1
                      and (select sum(amount) from public.coin_transactions where user_id = a) - coins0 = 100, 'bs: one money bag of 100 coins');
    -- (18) the same bag cannot be paid twice
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check(public._coins_grant(a, 100, 'match', 'buckshot_bag:' || m || ':1') = 0, 'bs: a second grant of the same bag pays nothing');
    perform pg_temp.check((select count(*) from public.coin_transactions where user_id = a and source_key like 'buckshot_bag:%') = 1, 'bs: the bag is idempotent');
    -- (5)(6)(10) round 2: B is back, 4 HP, items kept + 4 new
    perform public._mode_advance(room);
    s := pg_temp.st(room);
    perform pg_temp.check((s->>'round')::int = 2 and pg_temp.hp(room, a) = 4 and pg_temp.hp(room, b) = 4 and (s->'p'->b::text->>'alive')::boolean, 'bs: round 2, everybody back at 4 HP');
    perform pg_temp.check(jsonb_array_length(pg_temp.inv(room, a)) = 4 and jsonb_array_length(pg_temp.inv(room, b)) = 4, 'bs: both used all items: 0 kept + 4 new');
    perform pg_temp.check((s->'wins'->>a::text)::int = 1, 'bs: round wins carried');
    -- (13) handcuffs in 2p: A cuffs B and keeps playing after B's skipped turn
    perform pg_temp.pin(room, array[true, false, true, false, true, false, true, false], a,
                        jsonb_build_object(a::text, '{"inv": ["handcuffs","medicine","phone"]}', b::text, '{"inv": []}'));
    perform pg_temp.item(room, a, 'handcuffs', b);
    perform pg_temp.check((pg_temp.st(room)->'p'->b::text->>'cuffed')::boolean, 'bs: B is cuffed');
    perform pg_temp.fire(room, a, b);          -- live
    perform pg_temp.check(pg_temp.turn(room) = a and pg_temp.hp(room, b) = 3 and not (pg_temp.st(room)->'p'->b::text->>'cuffed')::boolean,
                          'bs: the cuffed player skips one turn; the cuffs come off');
    -- (11) phone: a later shell, to the user only
    perform pg_temp.item(room, a, 'phone');
    v := pg_temp.view(room, a)->'me'->'known';
    perform pg_temp.check(jsonb_array_length(v) = 1 and (v->0->>'ahead')::int >= 1 and (v->0->>'shell')::int between 3 and 8, 'bs: phone reveals a later shell');
    perform pg_temp.check((v->0->>'live')::boolean = ((pg_temp.st(room)->'mag'->'shells'->>((v->0->>'shell')::int - 1))::boolean), 'bs: and tells the truth');
    perform pg_temp.check(jsonb_array_length(pg_temp.view(room, b)->'me'->'known') = 0, 'bs: phone result is private');
    -- (8) magazine runs out, nobody out: reload + 2 items for everybody alive, same round, HP kept
    s := pg_temp.st(room);
    s := s || jsonb_build_object('mag', (s->'mag') || '{"pos": 7}'::jsonb);
    s := jsonb_set(s, array['mag','shells','7'], 'false');
    s := jsonb_set(s, array['p', a::text, 'inv'], '["beer","beer","beer","beer","beer","beer","beer"]');
    s := jsonb_set(s, array['p', b::text, 'inv'], '[]');
    perform pg_temp.setst(room, s);
    perform pg_temp.fire(room, a, b);           -- last shell, blank
    s := pg_temp.st(room);
    perform pg_temp.check((s->>'round')::int = 2 and (s->'mag'->>'pos')::int = 0
                      and jsonb_array_length(s->'mag'->'shells') = 8, 'bs: a fresh magazine in the same round');
    perform pg_temp.check(jsonb_array_length(pg_temp.inv(room, a)) = 8 and jsonb_array_length(pg_temp.inv(room, b)) = 2, 'bs: +2 items, capped at 8');
    perform pg_temp.check(pg_temp.hp(room, a) = 4 and pg_temp.hp(room, b) = 3, 'bs: HP kept across the reload');
    -- (14) expired medicine: about half +2 (capped), half -1
    for i in 1..400 loop
        x := public._g_buckshot_act(jsonb_set(jsonb_set(pg_temp.st(room), array['p', a::text, 'inv'], '["medicine"]'), array['p', a::text, 'hp'], '2')
                                    || jsonb_build_object('turn', a::text, 'max_hp', 5), a, 'item', jsonb_build_object('item', 'medicine', 'nonce', 'med-' || i || '-xxxxxxxx'));
        if (x->'p'->a::text->>'hp')::int = 4 then heal := heal + 1; elsif (x->'p'->a::text->>'hp')::int = 1 then hurt := hurt + 1; end if;
    end loop;
    perform pg_temp.check(heal + hurt = 400 and heal between 150 and 250, format('bs: medicine 50/50 (got %s heal / %s hurt)', heal, hurt));
    x := public._g_buckshot_act(jsonb_set(jsonb_set(pg_temp.st(room), array['p', a::text, 'inv'], '["medicine"]'), array['p', a::text, 'hp'], '4')
                                || jsonb_build_object('turn', a::text), a, 'item', '{"item":"medicine","nonce":"med-cap-xxxxxxxx"}');
    perform pg_temp.check((x->'p'->a::text->>'hp')::int in (3, 4), 'bs: healing never goes above max HP');
    -- (19) timeout / disconnect: the server fires at the opponent, never at the player itself
    perform pg_temp.pin(room, array[false, false, false, false, true, true, true, true], b);
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(s->'log') l where l->>'e' = 'timeout' and l->>'u' = b::text)
                      and exists (select 1 from jsonb_array_elements(s->'log') l where l->>'e' = 'shot' and l->>'u' = b::text and l->>'t' = a::text), 'bs: timeout fires at a rival');
    perform pg_temp.check(pg_temp.turn(room) = a, 'bs: and the game goes on');
    -- reconnecting: the state is simply read again; the player's private view is intact
    perform pg_temp.check(pg_temp.view(room, a)->'me' ? 'inv' and pg_temp.view(room, a)->'mode' = '"buckshot"', 'bs: a returning client gets its own view back');
    -- finish round 2 for A; round 3 starts at 5 HP
    perform pg_temp.pin(room, array[true, true, true, true, true, true, true, true], a, jsonb_build_object(b::text, '{"hp": 1}'));
    perform pg_temp.fire(room, a, b);
    perform public._mode_advance(room);
    perform pg_temp.check((pg_temp.st(room)->>'round')::int = 3 and pg_temp.hp(room, a) = 5 and pg_temp.hp(room, b) = 5, 'bs: round 3 starts at 5 HP');
    perform pg_temp.pin(room, array[true, true, true, true, true, true, true, true], b, jsonb_build_object(a::text, '{"hp": 1}'));
    perform pg_temp.fire(room, b, a);
    perform public._mode_advance(room);
    -- (4)(17) three rounds, champion = most rounds (A: 2, B: 1)
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select status from public.matches where id = m) = 'finished', 'bs: the match ends after round 3');
    perform pg_temp.check((select total_points from public.match_players where match_id = m and user_id = a) = 20
                      and (select total_points from public.match_players where match_id = m and user_id = b) = 10, 'bs: A 2 rounds, B 1');
    perform pg_temp.check((select (stats->>'rounds_won')::int from public.player_game_stats where user_id = a and game_mode = 'buckshot') = 2, 'bs: stats');
    perform pg_temp.check((select count(*) from public.coin_transactions where source_key like 'buckshot_bag:' || m || ':%') = 3, 'bs: one bag per round');

    -- ============================================================ (2)(15)(13)(5) three players: elimination, play on, remote, cuffs
    room := pg_temp.start(us[5:7]);
    s := pg_temp.st(room);
    a := (s->'order'->>0)::uuid; b := (s->'order'->>1)::uuid; c := (s->'order'->>2)::uuid;
    perform pg_temp.check(jsonb_array_length(s->'order') = 3 and pg_temp.hp(room, c) = 3, 'bs3: three players at 3 HP');
    -- C has 1 HP; A kills C with a live shell; the round goes on between A and B with the remaining shells
    perform pg_temp.pin(room, array[true, false, true, false, false, true, false, false], a,
                        jsonb_build_object(c::text, '{"hp": 1, "inv": []}', a::text, '{"inv": ["handcuffs","remote"]}', b::text, '{"inv": []}'));
    perform pg_temp.fire(room, a, c);
    s := pg_temp.st(room);
    perform pg_temp.check(not (s->'p'->c::text->>'alive')::boolean and not coalesce((s->>'over')::boolean, false), 'bs3: C is out, the round continues');
    perform pg_temp.check(pg_temp.turn(room) = b, 'bs3: turn to the next living player');
    perform pg_temp.act(b);
    perform pg_temp.expect_error(format($q$ select public.mode_act('%s', 'fire', '{"target":"%s","nonce":"dead-target-1"}') $q$, (pg_temp.rd(room)).id, c), 'INVALID_TARGET');
    perform pg_temp.fire(room, b, a);           -- shell 2 blank -> A
    perform pg_temp.check(pg_temp.turn(room) = a, 'bs3: the eliminated player is skipped');
    -- remote: direction reversed (A,B,C with C out: still B next, but the direction flag flips)
    perform pg_temp.item(room, a, 'remote');
    perform pg_temp.check((pg_temp.st(room)->>'dir')::int = -1, 'bs3: the remote reverses the order');
    -- magazine ends with A and B alive (C out): new magazine, +2 items for A and B only, same round
    s := pg_temp.st(room);
    s := s || jsonb_build_object('mag', (s->'mag') || '{"pos": 7}'::jsonb);
    s := jsonb_set(s, array['mag','shells','7'], 'false');
    perform pg_temp.setst(room, s);
    perform pg_temp.fire(room, a, b);
    s := pg_temp.st(room);
    perform pg_temp.check((s->>'round')::int = 1 and (s->'mag'->>'pos')::int = 0 and jsonb_array_length(pg_temp.inv(room, c)) = 0
                      and jsonb_array_length(pg_temp.inv(room, b)) = 2, 'bs3: reload after an elimination, items only for the living');
    -- direction -1 in a 3-seat ring with C out: from B we go back to A
    perform pg_temp.check(pg_temp.turn(room) in (a, b), 'bs3: turn among the living');

    -- ============================================================ (3)(13) four players: cuffs skip, 1-1-1 tie-break needs 3 winners
    room := pg_temp.start(array[us[1], us[2], us[3], us[4]]);
    s := pg_temp.st(room);
    a := (s->'order'->>0)::uuid; b := (s->'order'->>1)::uuid; c := (s->'order'->>2)::uuid; d := (s->'order'->>3)::uuid;
    perform pg_temp.check(jsonb_array_length(s->'order') = 4 and pg_temp.hp(room, d) = 3, 'bs4: four players');
    perform pg_temp.pin(room, array[true, false, true, false, true, false, true, false], a, jsonb_build_object(a::text, '{"inv": ["handcuffs"]}'));
    perform pg_temp.item(room, a, 'handcuffs', b);
    perform pg_temp.fire(room, a, d);
    perform pg_temp.check(pg_temp.turn(room) = c, 'bs4: cuffed B is skipped, C plays');
    -- round winners A, B, C (1-1-1 among three, D 0): the round-3 winner takes the tie-break
    perform pg_temp.pin(room, array[true, true, true, true, true, true, true, true], a,
                        jsonb_build_object(b::text, '{"hp": 1}', c::text, '{"hp": 1}', d::text, '{"hp": 1}'));
    perform pg_temp.fire(room, a, b); perform pg_temp.fire(room, c, d);
    s := pg_temp.st(room);
    -- order A B C D, B out: after A -> C (skip B), C kills D; A and C left
    perform pg_temp.fire(room, (s->>'turn')::uuid, case when (s->>'turn')::uuid = a then c else a end);
    perform public._mode_advance(room);
    for i in 2..3 loop
        s := pg_temp.st(room);
        t := case i when 2 then b else c end;
        perform pg_temp.pin(room, array[true, true, true, true, true, true, true, true], t,
            (select jsonb_object_agg(k, '{"hp": 1}'::jsonb) from jsonb_array_elements_text(s->'order') k where k <> t::text));
        while not coalesce((pg_temp.st(room)->>'over')::boolean, false) loop
            s := pg_temp.st(room);
            perform pg_temp.fire(room, (s->>'turn')::uuid, (select k::uuid from unnest(public._bs_alive(s)) k where k <> s->>'turn' limit 1));
        end loop;
        perform public._mode_advance(room);
    end loop;
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select status from public.matches where id = m) = 'finished', 'bs4: finished');
    perform pg_temp.check((select user_id from public.match_players where match_id = m order by total_points desc limit 1)
                          = (select (r.public->>'winner')::uuid from public.mode_rounds r where r.match_id = m and r.round_number = 3),
                          'bs4: a 1-1-1 tie goes to the round-3 winner, no 4th round');
    perform pg_temp.check((select count(*) from public.mode_rounds where match_id = m) = 3, 'bs4: exactly three rounds');
end $$;

select 'BUCKSHOT TESTS PASSED' as result;
rollback;
