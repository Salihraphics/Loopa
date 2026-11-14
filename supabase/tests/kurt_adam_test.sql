-- =====================================================================
-- LOOPA 0.8.0 — Kurt Adam: distribution, night order, roles, votes, win conditions
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

-- set the night deadline into the past and let the server advance
create or replace function pg_temp.tick(p_room uuid) returns void language plpgsql as $$
begin
    update public.mode_rounds set deadline_at = now() - interval '1 second' where id = (pg_temp.rd(p_room)).id;
    perform public._mode_advance(p_room);
end $$;
-- pin roles (users in seat order) and go to a fresh night
create or replace function pg_temp.pin(p_room uuid, p_roles text[]) returns jsonb language plpgsql as $$
declare s jsonb := pg_temp.st(p_room); i int; u text; seats jsonb := '[]'::jsonb;
begin
    s := s || jsonb_build_object('seat_owner', s->'seat_origin');
    for i in 1..array_length(p_roles, 1) loop
        u := s->'seat_owner'->>i::text;
        s := jsonb_set(s, array['p', u], jsonb_build_object('role', p_roles[i], 'alive', true, 'used', '[]'::jsonb, 'died_night', null));
        seats := seats || jsonb_build_array((s->'seats'->(i - 1)) || '{"alive": true}');
    end loop;
    s := s || jsonb_build_object('seats', seats, 'phase', 'night', 'actions', '{}'::jsonb, 'votes', '{}'::jsonb, 'events', '{}'::jsonb,
                                 'silenced', null, 'silenced_last', null, 'pending_cub', '[]'::jsonb, 'deaths', '[]'::jsonb);
    perform pg_temp.setst(p_room, s);
    return s;
end $$;
create or replace function pg_temp.u(p_room uuid, seat int) returns uuid language sql as $$ select (pg_temp.st(p_room)->'seat_owner'->>seat::text)::uuid; $$;
create or replace function pg_temp.night(p_room uuid, seat int, p jsonb) returns void language plpgsql as $$
begin perform pg_temp.move(p_room, pg_temp.u(p_room, seat), 'act', p); end $$;
create or replace function pg_temp.alive(p_room uuid, seat int) returns boolean language sql as $$
    select (pg_temp.st(p_room)->'p'->(pg_temp.st(p_room)->'seat_owner'->>seat::text)->>'alive')::boolean;
$$;

insert into auth.users(id, is_anonymous)
select ('80000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid, true from generate_series(1, 20) i;

do $$
declare
    us uuid[] := array(select ('80000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid from generate_series(1, 20) i);
    room uuid; s jsonb; v jsonb; n int; d jsonb; roles text[]; k int; m uuid; who uuid; i int; r text;
begin
    for i in 1..20 loop perform pg_temp.act(us[i]); perform public.ensure_profile('Köylü ' || i); end loop;
    perform public._grant_plus(us[1], 30, 'admin', 'test', us[1]);

    -- ============================================================ distribution (authoritative table)
    for n in 6..20 loop
        for k in 1..40 loop
            d := public._ka_deal(us[1:n], public._opts_kurt_adam('{}', n));
            roles := array(select value from jsonb_each_text(d));
            perform pg_temp.check(array_length(roles, 1) = n, 'deal: one role per player');
            perform pg_temp.check((select count(*) from unnest(roles) x where public._ka_team(x) = 'wolf') = (public._ka_counts(n))[1], format('deal %s: wolves', n));
            perform pg_temp.check((select count(*) from unnest(roles) x where x in ('jester','assassin')) = (public._ka_counts(n))[2], format('deal %s: independents', n));
            perform pg_temp.check('villager' = any(roles), format('deal %s: at least one plain villager', n));
            perform pg_temp.check(n >= 10 or not ('assassin' = any(roles)), format('deal %s: no assassin under 10', n));
            perform pg_temp.check((select count(*) from unnest(roles) x where x not in ('villager','kurt')) = (select count(distinct x) from unnest(roles) x where x not in ('villager','kurt')),
                                  format('deal %s: special roles at most once', n));
        end loop;
    end loop;
    perform pg_temp.check((public._ka_counts(6)) = array[1,0] and (public._ka_counts(9)) = array[2,1] and (public._ka_counts(12)) = array[3,1] and (public._ka_counts(20)) = array[4,2], 'table');
    -- disabled roles never appear; a disabled Soytarı at 9 players cannot fill the independent slot
    d := public._ka_deal(us[1:12], public._opts_kurt_adam('{"disabled":["doctor","seer","mayor","guardian","witch"]}', 12));
    perform pg_temp.check(not exists (select 1 from jsonb_each_text(d) where value in ('doctor','seer','mayor','guardian','witch')), 'disabled roles');
    perform pg_temp.expect_error(format('select public._ka_deal(%L::uuid[], public._opts_kurt_adam(''{"disabled":["jester"]}'', 9))', us[1:9]), 'ROLE_POOL_TOO_SMALL');

    -- ============================================================ start (8 players) and privacy
    perform pg_temp.expect_error(format('select pg_temp.start(''kurt_adam'', %L::uuid[], ''{}'')', us[1:5]), 'NOT_ENOUGH_PLAYERS');
    room := pg_temp.start('kurt_adam', us[1:8], '{}', 20);
    s := pg_temp.st(room);
    perform pg_temp.check(s->>'phase' = 'night' and jsonb_array_length(s->'seats') = 8, 'ka: night 1, 8 seats');
    perform pg_temp.act(us[2]);
    v := public.get_mode_state(room);
    perform pg_temp.check(v->'me'->>'role' is not null and v->'round'->'public'->'roles' = 'null'::jsonb
                          and not ((v->'round'->'public')::text like '%"role"%'), 'ka: only my own role');

    -- seats: 1 kurt, 2 doctor, 3 villager, 4 guardian, 5 seer, 6 villager, 7 mayor, 8 villager
    s := pg_temp.pin(room, array['kurt','doctor','villager','guardian','seer','villager','mayor','villager']);
    perform pg_temp.expect_error(format('select pg_temp.night(%L, 3, ''{"target":1}'')', room), 'NO_NIGHT_ACTION');
    perform pg_temp.expect_error(format('select pg_temp.night(%L, 2, ''{"target":2}'')', room), 'INVALID_TARGET');      -- doctor not self
    perform pg_temp.night(room, 1, '{"attack":3}');
    perform pg_temp.night(room, 2, '{"target":3}');
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check(pg_temp.alive(room, 3) and s->>'phase' = 'morning', 'ka: doctor blocks the wolf attack');
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(s->'events'->(s->'seat_owner'->>'3')) e where e->>'t' = 'protected'), 'ka: personal protected event');
    perform pg_temp.act(pg_temp.u(room, 6));
    perform pg_temp.check(not (public.get_mode_state(room)::text like '%"protected"%'), 'ka: events are personal');

    -- Guardian takes the attack
    s := pg_temp.pin(room, array['kurt','doctor','villager','guardian','seer','villager','mayor','villager']);
    perform pg_temp.night(room, 1, '{"attack":3}');
    perform pg_temp.night(room, 4, '{"target":3}');
    perform pg_temp.tick(room);
    perform pg_temp.check(pg_temp.alive(room, 3) and not pg_temp.alive(room, 4), 'ka: guardian dies instead');

    -- Brute special: through Doktor and Guardian, once; then a normal wolf
    s := pg_temp.pin(room, array['brute','doctor','villager','guardian','seer','villager','mayor','villager']);
    perform pg_temp.night(room, 1, '{"attack":3,"special":true}');
    perform pg_temp.night(room, 2, '{"target":3}');
    perform pg_temp.night(room, 4, '{"target":3}');
    perform pg_temp.tick(room);
    perform pg_temp.check(not pg_temp.alive(room, 3) and pg_temp.alive(room, 4), 'ka: brute special is unstoppable');
    perform pg_temp.check(pg_temp.st(room)->'p'->(pg_temp.u(room, 1)::text)->>'role' = 'kurt', 'ka: brute becomes a normal wolf');

    -- Silencer: the doctor's protection is void; silenced player cannot chat, can vote; not twice in a row
    s := pg_temp.pin(room, array['silencer','doctor','villager','guardian','seer','villager','mayor','villager']);
    perform pg_temp.night(room, 1, '{"target":2,"attack":3}');
    perform pg_temp.night(room, 2, '{"target":3}');
    perform pg_temp.tick(room);
    perform pg_temp.check(not pg_temp.alive(room, 3), 'ka: silenced doctor could not protect');
    perform pg_temp.check(pg_temp.st(room)->'p'->(pg_temp.u(room, 2)::text)->'used' = '[]'::jsonb, 'ka: nothing consumed by silence');
    perform pg_temp.tick(room);                                         -- morning -> day
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''chat'', ''{"text":"ben doktorum"}'')', room, pg_temp.u(room, 2)), 'SILENCED');
    perform pg_temp.move(room, pg_temp.u(room, 2), 'vote', '{"seat":"skip"}');
    perform pg_temp.move(room, pg_temp.u(room, 5), 'chat', '{"text":"merhaba"}');
    perform pg_temp.check(jsonb_array_length(pg_temp.st(room)->'chat') >= 1, 'ka: day chat');
    s := pg_temp.st(room) || jsonb_build_object('phase', 'night', 'actions', '{}'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.night(%L, 1, ''{"target":2}'')', room), 'SILENCED_LAST_NIGHT');

    -- Seer: Alpha reads as village, Illusionist as a village role, a plain wolf as wolf
    s := pg_temp.pin(room, array['alpha','illusionist','kurt','detective','seer','villager','mayor','villager']);
    perform pg_temp.night(room, 5, '{"target":1}');
    perform pg_temp.night(room, 4, '{"a":3,"b":6}');
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((select (e->>'wolf')::boolean from jsonb_array_elements(s->'events'->(pg_temp.u(room, 5)::text)) e where e->>'t' = 'seer') = false, 'ka: alpha looks village');
    perform pg_temp.check((select (e->>'same')::boolean from jsonb_array_elements(s->'events'->(pg_temp.u(room, 4)::text)) e where e->>'t' = 'detective') = false, 'ka: detective: wolf vs village');
    s := pg_temp.pin(room, array['alpha','illusionist','kurt','detective','seer','villager','mayor','villager']);
    perform pg_temp.night(room, 5, '{"target":2}');
    perform pg_temp.night(room, 4, '{"a":2,"b":6}');
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check((select (e->>'wolf')::boolean from jsonb_array_elements(s->'events'->(pg_temp.u(room, 5)::text)) e where e->>'t' = 'seer') = false
                          and (select (e->>'same')::boolean from jsonb_array_elements(s->'events'->(pg_temp.u(room, 4)::text)) e where e->>'t' = 'detective') = true,
                          'ka: illusionist fools both investigators the same way');
    s := pg_temp.pin(room, array['alpha','illusionist','kurt','detective','seer','villager','mayor','villager']);
    perform pg_temp.night(room, 5, '{"target":3}');
    perform pg_temp.tick(room);
    perform pg_temp.check((select (e->>'wolf')::boolean from jsonb_array_elements(pg_temp.st(room)->'events'->(pg_temp.u(room, 5)::text)) e where e->>'t' = 'seer'), 'ka: plain wolf seen');

    -- Witch: both potions in one night, not on the same player
    s := pg_temp.pin(room, array['kurt','witch','villager','villager','villager','villager','mayor','villager']);
    perform pg_temp.expect_error(format('select pg_temp.night(%L, 2, ''{"heal":3,"poison":3}'')', room), 'INVALID_TARGET');
    perform pg_temp.night(room, 1, '{"attack":3}');
    perform pg_temp.night(room, 2, '{"heal":3,"poison":4}');
    perform pg_temp.tick(room);
    perform pg_temp.check(pg_temp.alive(room, 3) and not pg_temp.alive(room, 4), 'ka: heal + poison the same night');
    s := pg_temp.st(room) || jsonb_build_object('phase', 'night', 'actions', '{}'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.night(%L, 2, ''{"heal":5}'')', room), 'ABILITY_USED');

    -- Hunter's arrow: Doktor does not stop it, Guardian takes it
    s := pg_temp.pin(room, array['kurt','doctor','villager','guardian','hunter','villager','mayor','villager']);
    perform pg_temp.night(room, 5, '{"target":3}');
    perform pg_temp.night(room, 2, '{"target":3}');
    perform pg_temp.tick(room);
    perform pg_temp.check(not pg_temp.alive(room, 3), 'ka: arrow through the doctor');
    s := pg_temp.pin(room, array['kurt','doctor','villager','guardian','hunter','villager','mayor','villager']);
    perform pg_temp.night(room, 5, '{"target":3}');
    perform pg_temp.night(room, 4, '{"target":3}');
    perform pg_temp.tick(room);
    perform pg_temp.check(pg_temp.alive(room, 3) and not pg_temp.alive(room, 4), 'ka: guardian takes the arrow');

    -- Shapeshifter: target eliminated, own seat "dies", continues in the target's seat; Doktor blocks it
    s := pg_temp.pin(room, array['shapeshifter','doctor','villager','villager','villager','villager','mayor','villager']);
    who := pg_temp.u(room, 1);
    perform pg_temp.night(room, 1, '{"target":6}');
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check(s->'seat_owner'->>'6' = who::text and not (s->'seats'->0->>'alive')::boolean and (s->'seats'->5->>'alive')::boolean,
                          'ka: shapeshifter wears seat 6, seat 1 dead');
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(s->'deaths') x where (x->>'seat')::int = 1) and
                          not exists (select 1 from jsonb_array_elements(s->'deaths') x where (x->>'seat')::int = 6), 'ka: morning shows seat 1 dead');
    s := pg_temp.pin(room, array['villager','doctor','villager','villager','villager','shapeshifter','mayor','villager']);
    perform pg_temp.night(room, 6, '{"target":3}');
    perform pg_temp.night(room, 2, '{"target":3}');
    perform pg_temp.tick(room);
    perform pg_temp.check(pg_temp.alive(room, 3) and pg_temp.st(room)->'seat_owner'->>'6' = pg_temp.u(room, 6)::text, 'ka: doctor stops the shift');

    -- Medyum: not a player who died tonight; an earlier one comes back with the role, used abilities stay used
    s := pg_temp.pin(room, array['kurt','medium','hunter','villager','villager','villager','mayor','villager']);
    s := jsonb_set(s, array['p', pg_temp.u(room, 3)::text], jsonb_build_object('role', 'hunter', 'alive', false, 'used', '["arrow"]'::jsonb, 'died_night', 0));
    s := jsonb_set(s, '{night}', '2');
    perform pg_temp.setst(room, s);
    perform pg_temp.night(room, 2, jsonb_build_object('revive', pg_temp.u(room, 3)));
    perform pg_temp.tick(room);
    s := pg_temp.st(room);
    perform pg_temp.check(pg_temp.alive(room, 3) and s->'p'->(pg_temp.u(room, 3)::text)->'used' ? 'arrow', 'ka: revived, arrow still used');

    -- Kurt Yavrusu: eliminated -> takes one more, protections do not apply
    s := pg_temp.pin(room, array['wolf_cub','doctor','villager','villager','hunter','villager','mayor','kurt']);
    perform pg_temp.night(room, 5, '{"target":1}');
    perform pg_temp.night(room, 2, '{"target":4}');
    perform pg_temp.tick(room);
    perform pg_temp.check(pg_temp.st(room)->>'phase' = 'cub', 'ka: cub last move phase');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''last'', ''{"seat":4}'')', room, pg_temp.u(room, 3)), 'PHASE_CLOSED');
    perform pg_temp.move(room, pg_temp.u(room, 1), 'last', '{"seat":4}');
    perform pg_temp.check(not pg_temp.alive(room, 4), 'ka: cub''s last move is certain');

    -- Day vote: Mayor counts twice; a tie sends nobody
    s := pg_temp.pin(room, array['kurt','kurt','villager','villager','villager','villager','mayor','villager']) || jsonb_build_object('phase', 'vote');
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, pg_temp.u(room, 7), 'vote', '{"seat":3}');          -- 2
    perform pg_temp.move(room, pg_temp.u(room, 4), 'vote', '{"seat":5}');
    perform pg_temp.move(room, pg_temp.u(room, 6), 'vote', '{"seat":5}');          -- 2 : tie
    perform pg_temp.tick(room);
    perform pg_temp.check(pg_temp.alive(room, 3) and pg_temp.alive(room, 5), 'ka: tie -> nobody');
    s := pg_temp.pin(room, array['kurt','kurt','villager','villager','villager','villager','mayor','villager']) || jsonb_build_object('phase', 'vote');
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, pg_temp.u(room, 7), 'vote', '{"seat":1}');
    perform pg_temp.move(room, pg_temp.u(room, 4), 'vote', '{"seat":5}');
    perform pg_temp.tick(room);
    perform pg_temp.check(not pg_temp.alive(room, 1), 'ka: mayor''s double vote decides');

    -- win conditions
    s := pg_temp.st(room);
    perform pg_temp.check(public._ka_winner(s) is null, 'ka: game goes on');
    s := jsonb_set(s, array['p', pg_temp.u(room, 2)::text, 'alive'], 'false');
    perform pg_temp.check(public._ka_winner(s) = 'village', 'ka: no wolves -> village');
    s := pg_temp.pin(room, array['kurt','kurt','villager','villager','villager','villager','assassin','villager']);
    for i in 3..6 loop s := jsonb_set(s, array['p', pg_temp.u(room, i)::text, 'alive'], 'false'); end loop;
    s := jsonb_set(s, array['p', pg_temp.u(room, 8)::text, 'alive'], 'false');
    perform pg_temp.check(public._ka_winner(s) is null, 'ka: wolves at parity but the assassin lives -> no winner yet');
    s := jsonb_set(jsonb_set(s, array['p', pg_temp.u(room, 1)::text, 'alive'], 'false'), array['p', pg_temp.u(room, 2)::text, 'alive'], 'false');
    perform pg_temp.check(public._ka_winner(s) = 'assassin', 'ka: assassin last alive wins');
    s := pg_temp.pin(room, array['kurt','kurt','villager','villager','villager','villager','villager','villager']);
    for i in 3..6 loop s := jsonb_set(s, array['p', pg_temp.u(room, i)::text, 'alive'], 'false'); end loop;
    perform pg_temp.check(public._ka_winner(s) = 'wolf', 'ka: wolves at parity win');
    -- Soytarı voted out: wins alone, game over
    s := pg_temp.pin(room, array['kurt','kurt','jester','villager','villager','villager','mayor','villager']) || jsonb_build_object('phase', 'vote');
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, pg_temp.u(room, 4), 'vote', '{"seat":3}');
    perform pg_temp.move(room, pg_temp.u(room, 5), 'vote', '{"seat":3}');
    who := pg_temp.u(room, 3);
    perform pg_temp.tick(room);
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select status from public.matches where id = m) = 'finished'
                          and (select final_rank from public.match_players where match_id = m and user_id = who) = 1, 'ka: jester wins alone');
    perform pg_temp.act(us[2]);
    v := public.get_mode_state(room);
    perform pg_temp.check(v->'round'->'public'->'roles' <> 'null'::jsonb, 'ka: roles revealed at the end');
end $$;

select 'KURT ADAM TESTS PASSED' as result;
rollback;
