-- =====================================================================
-- LOOPA 0.8.0 — Renk Kartları, 101 Okey, Milyoner through the real RPCs
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
select ('60000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid, true from generate_series(1, 6) i;

do $$
declare
    A uuid := '60000000-0000-0000-0000-000000000001'; B uuid := '60000000-0000-0000-0000-000000000002';
    C uuid := '60000000-0000-0000-0000-000000000003'; D uuid := '60000000-0000-0000-0000-000000000004';
    room uuid; s jsonb; v jsonb; m uuid;
    function_card jsonb;
begin
    perform pg_temp.act(A); perform public.ensure_profile('Kartçı A'); perform public._grant_plus(A, 30, 'admin', 'test', A);
    perform pg_temp.act(B); perform public.ensure_profile('Kartçı B');
    perform pg_temp.act(C); perform public.ensure_profile('Kartçı C');
    perform pg_temp.act(D); perform public.ensure_profile('Kartçı D');
    update public.economy_config set value = '{"daily_rewarded_matches": 99, "same_opponents_daily": 99}' where key = 'anti_farm';

    -- =========================================================== Renk Kartları
    room := pg_temp.start('renk_kartlari', array[A, B, C, D], '{"rounds": 1}', 8);
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'hands'->A::text) = 7 and (s->'discard'->-1->>'v') ~ '^[0-9]$', 'rk: 7 cards each, number on top');
    perform pg_temp.act(B);
    v := public.get_mode_state(room);
    perform pg_temp.check(jsonb_array_length(v->'me'->'hand') = 7 and (v->'round'->'public'->'counts'->>A::text)::int = 7
                          and v->'round'->'public'->'hands' = 'null'::jsonb, 'rk: only my hand, counts of the others');
    -- pinned situation: A to play, red 5 on top
    s := s || jsonb_build_object('turn', A, 'dir', 1, 'color', 'r', 'pending', 0, 'stack_by', null, 'drawn', null,
        'discard', '[{"id":"r5x","c":"r","v":"5"}]'::jsonb,
        'hands', jsonb_build_object(
            A::text, '[{"id":"rd21","c":"r","v":"d2"},{"id":"g31","c":"g","v":"3"},{"id":"wild1","c":"k","v":"wild"},{"id":"r71","c":"r","v":"7"}]'::jsonb,
            B::text, '[{"id":"d41","c":"k","v":"d4"},{"id":"b11","c":"b","v":"1"},{"id":"b21","c":"b","v":"2"}]'::jsonb,
            C::text, '[{"id":"ayna1","c":"k","v":"ayna"},{"id":"y11","c":"y","v":"1"},{"id":"y21","c":"y","v":"2"}]'::jsonb,
            D::text, '[{"id":"g11","c":"g","v":"1"},{"id":"g21","c":"g","v":"2"}]'::jsonb),
        'deck', (select jsonb_agg(jsonb_build_object('id', 'x' || i, 'c', 'y', 'v', '9')) from generate_series(1, 40) i));
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''play'', ''{"card":"b11"}'')', room, B), 'NOT_YOUR_TURN');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''play'', ''{"card":"g31"}'')', room, A), 'INVALID_MOVE');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''play'', ''{"card":"wild1"}'')', room, A), 'CHOOSE_COLOR');
    -- +2, stacked +4, then AYNA: the stack of 6 goes back to A (who started it)
    perform pg_temp.move(room, A, 'play', '{"card":"rd21"}');
    perform pg_temp.check((pg_temp.st(room)->>'pending')::int = 2 and pg_temp.st(room)->>'turn' = B::text, 'rk: +2 pending');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''play'', ''{"card":"b11"}'')', room, B), 'INVALID_MOVE');
    perform pg_temp.move(room, B, 'play', '{"card":"d41","color":"y"}');
    perform pg_temp.check((pg_temp.st(room)->>'pending')::int = 6 and pg_temp.st(room)->>'stack_by' = A::text, 'rk: stacked +4 = 6');
    perform pg_temp.move(room, C, 'play', '{"card":"ayna1"}');
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'hands'->A::text) = 3 + 6 and (s->>'pending')::int = 0 and s->>'turn' = D::text and s->>'color' = 'y',
                          'rk: AYNA sends the whole stack to the first + player, play goes on, colour kept');
    -- TEMİZLE: every card of the chosen colour leaves the hand at once
    s := s || jsonb_build_object('turn', D, 'hands', jsonb_set(s->'hands', array[D::text],
        '[{"id":"temizle1","c":"k","v":"temizle"},{"id":"g11","c":"g","v":"1"},{"id":"g21","c":"g","v":"2"},{"id":"b51","c":"b","v":"5"}]'::jsonb));
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, D, 'play', '{"card":"temizle1","color":"g"}');
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'hands'->D::text) = 1 and s->'discard'->-1->>'v' = 'temizle' and s->>'color' = 'g', 'rk: TEMİZLE clears the colour');
    -- GAMBLE: next player holds the colour -> the gambler draws 4
    s := s || jsonb_build_object('turn', A, 'color', 'g', 'discard', '[{"id":"g9x","c":"g","v":"9"}]'::jsonb,
        'hands', jsonb_set(jsonb_set(s->'hands', array[A::text], '[{"id":"gamble1","c":"k","v":"gamble"},{"id":"r72","c":"r","v":"7"}]'::jsonb),
                           array[B::text], '[{"id":"b31","c":"b","v":"3"},{"id":"b41","c":"b","v":"4"}]'::jsonb));
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'play', '{"card":"gamble1","color":"b","uno":true}');
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'hands'->A::text) = 1 + 4 and jsonb_array_length(s->'hands'->B::text) = 2, 'rk: GAMBLE lost -> gambler draws 4');
    -- GAMBLE won: next player lacks the colour -> they draw 4
    s := s || jsonb_build_object('turn', A, 'hands', jsonb_set(s->'hands', array[A::text], '[{"id":"gamble2","c":"k","v":"gamble"},{"id":"r73","c":"r","v":"7"}]'::jsonb));
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'play', '{"card":"gamble2","color":"y","uno":true}');
    perform pg_temp.check(jsonb_array_length(pg_temp.st(room)->'hands'->B::text) = 6, 'rk: GAMBLE won -> next draws 4');
    -- ELİNİ GÖRELİM: only the player of the card sees the next hand
    s := pg_temp.st(room);
    s := s || jsonb_build_object('turn', A, 'hands', jsonb_set(s->'hands', array[A::text], '[{"id":"goster1","c":"k","v":"goster"},{"id":"r74","c":"r","v":"7"},{"id":"r75","c":"r","v":"8"}]'::jsonb));
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'play', '{"card":"goster1","color":"r"}');
    perform pg_temp.act(A);
    v := public.get_mode_state(room);
    perform pg_temp.check(jsonb_array_length(v->'me'->'seen'->'hand') = 6 and v->'me'->'seen'->>'of' = B::text, 'rk: hand shown to the player');
    perform pg_temp.act(C);
    v := public.get_mode_state(room);
    perform pg_temp.check(v->'me'->'seen' = 'null'::jsonb and not (v::text like '%"b31"%'), 'rk: nobody else sees it');
    perform pg_temp.check(jsonb_array_length(pg_temp.st(room)->'hands'->B::text) = 6, 'rk: no card is taken');
    -- cannot finish with WILD
    s := pg_temp.st(room) || jsonb_build_object('turn', C, 'hands', jsonb_set(pg_temp.st(room)->'hands', array[C::text], '[{"id":"wild9","c":"k","v":"wild"}]'::jsonb));
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''play'', ''{"card":"wild9","color":"r"}'')', room, C), 'CANNOT_FINISH_WITH_WILD');
    -- missed UNO can be caught: +2
    s := pg_temp.st(room) || jsonb_build_object('turn', C, 'color', 'r', 'discard', '[{"id":"r1x","c":"r","v":"1"}]'::jsonb,
        'hands', jsonb_set(pg_temp.st(room)->'hands', array[C::text], '[{"id":"r11","c":"r","v":"1"},{"id":"r12","c":"r","v":"2"}]'::jsonb));
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, C, 'play', '{"card":"r11"}');
    perform pg_temp.check(pg_temp.st(room)->>'uno_open' = C::text, 'rk: UNO not said');
    perform pg_temp.move(room, B, 'catch', jsonb_build_object('target', C));
    perform pg_temp.check(jsonb_array_length(pg_temp.st(room)->'hands'->C::text) = 3, 'rk: caught, +2');
    -- finishing the round: C plays the last card (a number) -> scores the other hands
    s := pg_temp.st(room) || jsonb_build_object('turn', C, 'color', 'r', 'uno_open', null,
        'hands', jsonb_set(pg_temp.st(room)->'hands', array[C::text], '[{"id":"r13","c":"r","v":"3"}]'::jsonb));
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, C, 'play', '{"card":"r13"}');
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select status from public.matches where id = m) = 'finished'
                          and (select final_rank from public.match_players where match_id = m and user_id = C) = 1, 'rk: round winner wins the 1-round match');
    perform pg_temp.check((select total_points from public.match_players where match_id = m and user_id = C) > 0, 'rk: scored the other hands');

    -- =========================================================== 101 Okey
    perform pg_temp.expect_error(format('select pg_temp.start(''okey'', array[%L,%L,%L]::uuid[], ''{}'')', A, B, C), 'NOT_ENOUGH_PLAYERS');
    room := pg_temp.start('okey', array[A, B, C, D], '{"rounds": 1}');
    s := pg_temp.st(room);
    perform pg_temp.check(jsonb_array_length(s->'hands'->A::text) = 22 and jsonb_array_length(s->'hands'->B::text) = 21
                          and jsonb_array_length(s->'deck') = 20, 'okey: 22/21/21/21 dealt, 20 left');
    perform pg_temp.check((select count(*) from (select jsonb_array_elements_text(hv) from jsonb_each(s->'hands') x(hk, hv)) y) + 20 + 1 = 106, 'okey: 106 tiles');
    perform pg_temp.act(B);
    v := public.get_mode_state(room);
    perform pg_temp.check(jsonb_array_length(v->'me'->'hand') = 21 and v->'round'->'public'->'hands' = 'null'::jsonb, 'okey: hands are private');
    -- pinned: okey is blue 5 (indicator b4); A on turn (discard stage, 22 tiles)
    s := s || jsonb_build_object('okey', '{"c":"b","n":5}'::jsonb, 'indicator', 'b4a', 'turn', A, 'stage', 'discard', 'took', null,
        'hands', jsonb_set(s->'hands', array[A::text],
            '["r10a","r11a","r12a","r13a", "k9a","y9a","b9a", "y10a","y11b","y12a", "r1a","r2a","r3a", "b5a", "k13a","k13b", "y2a","y3b", "k6a","r6b","r8a","k1a"]'::jsonb),
        'deck', (select jsonb_agg('y' || (1 + i % 13) || 'b') from generate_series(1, 20) i));
    perform pg_temp.setst(room, s);
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''lay'', ''{"melds":[["r1a","r2a","r3a"]]}'')', room, A), 'NOT_OPENED');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''open'', ''{"melds":[["r1a","r2a","r3a"],["k9a","y9a","b9a"]]}'')', room, A), 'OPENING_TOO_LOW');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''open'', ''{"melds":[["r1a","r2a","k6a"]]}'')', room, A), 'INVALID_MELD');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''open'', ''{"melds":[["r1a","r2a","r9z"]]}'')', room, A), 'INVALID_MELD');
    -- 10+11+12+13 (46) + 9*3 (27) + 10+11+12 (33) = 106 >= 101
    perform pg_temp.move(room, A, 'open', '{"melds":[["r10a","r11a","r12a","r13a"],["k9a","y9a","b9a"],["y10a","y11b","y12a"]]}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->'opened'->>A::text = 'melds' and jsonb_array_length(s->'table') = 3 and jsonb_array_length(s->'hands'->A::text) = 12, 'okey: opened with 106');
    perform pg_temp.move(room, A, 'lay', '{"melds":[["r1a","r2a","r3a"]]}');
    -- adding the okey (b5) to the red run 1-2-3 makes it 1-2-3-4
    perform pg_temp.move(room, A, 'add', '{"meld":3,"tile":"b5a"}');
    perform pg_temp.check((pg_temp.st(room)->'table'->3->>'hi')::int = 4, 'okey: tile added to a meld (okey as 4)');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''add'', ''{"meld":0,"tile":"k1a"}'')', room, A), 'INVALID_MELD');
    -- discarding a tile that fits the table (y9b? no: k13 does not fit; r6b fits nothing; y13b fits the yellow run 10-12)
    s := pg_temp.st(room);
    s := jsonb_set(s, array['hands', A::text], (s->'hands'->A::text) || '["y13b"]'::jsonb);
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'discard', '{"tile":"y13b"}');
    s := pg_temp.st(room);
    perform pg_temp.check((s->'penalty'->>A::text)::int = 101 and s->>'turn' = B::text and s->>'stage' = 'draw', 'okey: işler taş atma +101');
    -- B takes A's discard and must use it (cannot discard while holding it)
    perform pg_temp.move(room, B, 'draw', '{"from":"discard"}');
    perform pg_temp.check(pg_temp.st(room)->>'took' = 'y13b', 'okey: took the discard');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''discard'', ''{"tile":"y13b"}'')', room, B), 'MUST_USE_TAKEN_TILE');
    perform pg_temp.move(room, B, 'return_taken', '{}');
    s := pg_temp.st(room);
    perform pg_temp.check((s->'penalty'->>B::text)::int = 101 and s->>'took' is null and s->'discards'->A::text->>-1 = 'y13b', 'okey: returned, +101');
    perform pg_temp.move(room, B, 'discard', jsonb_build_object('tile', s->'hands'->B::text->>0));
    -- A finishes: last tiles laid, final discard
    s := pg_temp.st(room) || jsonb_build_object('turn', A, 'stage', 'discard', 'took', null,
        'hands', jsonb_set(pg_temp.st(room)->'hands', array[A::text], '["k13a","k13b","r13b","k6a"]'::jsonb));
    perform pg_temp.setst(room, s);
    s := s || jsonb_build_object('hands', jsonb_set(s->'hands', array[A::text], '["k6a"]'::jsonb));
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'discard', '{"tile":"k6a"}');
    s := (select payload from public.mode_secrets where round_id = (select id from public.mode_rounds where room_id = room order by round_number limit 1) and kind = 'g');
    perform pg_temp.check(s->>'winner' = A::text, 'okey: finished');
    perform pg_temp.check((s->'round_scores'->>A::text)::int = 0, 'okey: finisher -101 plus own +101 penalty = 0');
    perform pg_temp.check((s->'round_scores'->>C::text)::int = -202, 'okey: not opened +202');
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select final_rank from public.match_players where match_id = m and user_id = A) = 1, 'okey: lowest total wins');

    -- =========================================================== Milyoner
    room := pg_temp.start('milyoner', array[A, B, C], '{"max_rounds": 20}', 6);
    s := pg_temp.st(room);
    perform pg_temp.check((s->'players'->A::text->>'cash')::int = 1500 and s->>'stage' = 'roll', 'my: start money, roll first');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''end_turn'', ''{}'')', room, A), 'INVALID_ACTION');
    -- land A on Kasaba Yolu (1) and buy it
    s := public._my_land(s, A::text, 1, 3, false);
    perform pg_temp.check(s->>'stage' = 'buy' and (s->>'offer')::int = 1, 'my: free property offered');
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'buy', '{}');
    s := pg_temp.st(room);
    perform pg_temp.check(s->'owner'->>'1' = A::text and (s->'players'->A::text->>'cash')::int = 1440, 'my: bought for 60');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''build'', ''{"sq":1}'')', room, A), 'NEED_FULL_SET');
    -- the full set doubles the base rent; B lands on it
    s := jsonb_set(s, '{owner,3}', to_jsonb(A::text));
    s := public._my_land(s, B::text, 1, 4, false);
    perform pg_temp.check((s->'players'->B::text->>'cash')::int = 1500 - 12 and (s->'players'->A::text->>'cash')::int = 1440 + 12, 'my: rent x2 with the full set');
    -- even building
    s := s || jsonb_build_object('turn', A, 'stage', 'act');
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'build', '{"sq":1}');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''build'', ''{"sq":1}'')', room, A), 'BUILD_EVENLY');
    perform pg_temp.move(room, A, 'build', '{"sq":3}');
    s := pg_temp.st(room);
    perform pg_temp.check((s->'houses'->>'1')::int = 1 and (s->'houses'->>'3')::int = 1 and (s->'players'->A::text->>'cash')::int = 1452 - 100, 'my: houses');
    perform pg_temp.check(public._my_rent_at(s, 1, 7) = public._my_rent(60, 1, true), 'my: rent with a house');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''mortgage'', ''{"sq":1}'')', room, A), 'SELL_BUILDINGS_FIRST');
    perform pg_temp.expect_error(format('select pg_temp.move(%L, %L, ''build'', ''{"sq":6}'')', room, A), 'NOT_OWNER');
    -- mortgaged square: no rent
    s := jsonb_set(s, '{owner,5}', to_jsonb(C::text)) || jsonb_build_object('mortgaged', '["5"]'::jsonb);
    perform pg_temp.check(public._my_rent_at(s, 5, 7) = 0, 'my: no rent on mortgaged squares');
    -- go to jail and pay out
    s := public._my_land(s, B::text, 30, 6, false);
    perform pg_temp.check((s->'players'->B::text->>'pos')::int = 10 and (s->'players'->B::text->>'jail')::int = 1, 'my: Nezarete Git');
    -- bankruptcy: C owes more than everything -> assets to the creditor, C is out
    s := public._my_set_cash(s, C::text, 10);
    s := public._my_pay(s, C::text, 5000, A::text);
    perform pg_temp.check((s->'players'->C::text->>'out')::boolean and s->'owner'->>'5' = A::text, 'my: bankrupt, assets to the creditor');
    -- B goes bankrupt to the bank: last one standing (A) wins
    s := s || jsonb_build_object('turn', B, 'stage', 'act');
    s := public._my_set_cash(s, B::text, 0);
    s := public._my_pay(s, B::text, 99999, null);
    s := s || jsonb_build_object('turn', A, 'stage', 'act');
    perform pg_temp.setst(room, s);
    perform pg_temp.move(room, A, 'end_turn', '{}');
    m := (select id from public.matches where room_id = room order by started_at desc limit 1);
    perform pg_temp.check((select final_rank from public.match_players where match_id = m and user_id = A) = 1
                          and (select final_rank from public.match_players where match_id = m and user_id = C) = 3, 'my: last one standing wins');
    -- timeouts play passively and never buy
    room := pg_temp.start('milyoner', array[A, B], '{}', 6);
    update public.mode_rounds set deadline_at = now() - interval '1 second' where id = (pg_temp.rd(room)).id;
    perform public._mode_advance(room);
    s := pg_temp.st(room);
    perform pg_temp.check(s->>'turn' = B::text or (s->>'doubles')::int > 0, 'my: timeout ends the turn');
    perform pg_temp.check(not (s->'owner' ? ((s->'players'->A::text->>'pos'))), 'my: nothing bought automatically');
end $$;

select 'CARD GAMES TESTS PASSED' as result;
rollback;
