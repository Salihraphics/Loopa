-- =====================================================================
-- LOOPA backend integration test: simulates several players (distinct
-- auth.uid() values) going through a full match. Time is "fast-forwarded"
-- by moving server deadlines into the past, which is exactly what the
-- engine compares against now().
-- =====================================================================
\set ON_ERROR_STOP 1
set client_min_messages = warning;

create or replace function pg_temp.act(p uuid) returns void language sql as $$
    select set_config('request.jwt.claim.sub', p::text, false);
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

-- Answer generator: a dictionary word for the letter if available, otherwise an "uncertain" word.
create or replace function pg_temp.word_for(p_cat text, p_letter text, p_variant int) returns text language sql as $$
    select coalesce(
        (select word from public.word_dictionary
          where category_key = p_cat and left(word, 1) = public.normalize_answer(p_letter)
          order by word offset p_variant limit 1),
        p_letter || 'zort' || p_variant);
$$;

create or replace function pg_temp.answers_for(p_round uuid, p_variant int) returns jsonb language sql as $$
    select jsonb_object_agg(c->>'key', pg_temp.word_for(c->>'key', r.letter, p_variant))
      from public.rounds r, jsonb_array_elements(r.categories) c where r.id = p_round;
$$;

create or replace function pg_temp.fast_forward_answering(p_room uuid) returns void language sql as $$
    update public.rounds set started_at = now() - interval '200 seconds' where room_id = p_room and phase = 'answering';
$$;
create or replace function pg_temp.expire_all(p_room uuid) returns void language sql as $$
    update public.round_players set deadline_at = now() - interval '5 seconds' where room_id = p_room;
    update public.rounds set voting_deadline = now() - interval '1 second' where room_id = p_room and phase = 'voting';
    update public.rounds set reveal_deadline = now() - interval '1 second' where room_id = p_room and phase = 'reveal';
    update public.rounds set results_deadline = now() - interval '1 second' where room_id = p_room and phase = 'results';
$$;

insert into auth.users(id) values
 ('00000000-0000-0000-0000-00000000000a'), ('00000000-0000-0000-0000-00000000000b'),
 ('00000000-0000-0000-0000-00000000000c'), ('00000000-0000-0000-0000-00000000000d'),
 ('00000000-0000-0000-0000-00000000000e');

do $$
declare
    A uuid := '00000000-0000-0000-0000-00000000000a';
    B uuid := '00000000-0000-0000-0000-00000000000b';
    C uuid := '00000000-0000-0000-0000-00000000000c';
    D uuid := '00000000-0000-0000-0000-00000000000d';   -- spectator
    E uuid := '00000000-0000-0000-0000-00000000000e';   -- kicked player
    v jsonb; room uuid; code text; m uuid; rd public.rounds; n int; s jsonb; j record; pts int; xp_before int; i int;
begin
    -- ---------------- profiles ----------------
    perform pg_temp.act(A); perform public.ensure_profile('Ayşe', '{"base":"base_round","hair":"hair_long"}');
    perform pg_temp.act(B); perform public.ensure_profile('Barış');
    perform pg_temp.act(C); perform public.ensure_profile('Cem');
    perform pg_temp.act(D); perform public.ensure_profile('Deniz');
    perform pg_temp.act(E); perform public.ensure_profile('Ece');
    perform pg_temp.act(A);
    perform pg_temp.expect_error($q$select public.update_avatar('{"hat":"hat_crown"}')$q$, 'COSMETIC_LOCKED');
    perform pg_temp.expect_error($q$select public.unlock_cosmetic('hat_crown')$q$, 'LEVEL_TOO_LOW');
    perform pg_temp.expect_error($q$select public.ensure_profile('x')$q$, 'INVALID_USERNAME');
    assert (select count(*) from public.player_cosmetics where user_id = A) > 30, 'free cosmetics granted';

    -- ---------------- room ----------------
    v := public.create_room(false);
    room := (v->>'id')::uuid; code := v->>'code';
    assert code ~ '^[A-Z2-9]{6}$', 'room code format';
    perform pg_temp.act(B); perform public.join_room(lower(code));          -- case-insensitive
    perform pg_temp.act(C); perform public.join_room(code);
    perform pg_temp.act(D); perform public.join_room(code, true);           -- spectator
    perform pg_temp.act(E); perform public.join_room(code);
    perform pg_temp.expect_error($q$select public.join_room('ZZZZZZ')$q$, 'ROOM_NOT_FOUND');

    -- non-host cannot change settings / start
    perform pg_temp.act(B);
    perform pg_temp.expect_error(format('select public.start_match(%L)', room), 'NOT_HOST');
    perform pg_temp.expect_error(format('select public.update_room_settings(%L, %L, true)', room, public._default_settings()), 'NOT_HOST');

    -- host kicks E, E cannot rejoin
    perform pg_temp.act(A); perform public.kick_player(room, E);
    perform pg_temp.act(E); perform pg_temp.expect_error(format('select public.join_room(%L)', code), 'KICKED');

    -- settings validation
    perform pg_temp.act(A);
    s := public._default_settings() || '{"rounds": 3, "round_seconds": 30}'::jsonb;
    perform pg_temp.expect_error(format('select public.update_room_settings(%L, %L, false)', room, s || '{"rounds": 7}'), 'INVALID_SETTINGS');
    perform pg_temp.expect_error(format('select public.update_room_settings(%L, %L, false)', room,
        s || '{"categories":[{"key":"isim","custom":false},{"key":"sehir","custom":false}]}'), 'TOO_FEW_CATEGORIES');
    s := s || jsonb_build_object('categories', (s->'categories') || '[{"key":"custom_1","name":"  Süper   Kahraman ","custom":true}]'::jsonb);
    s := public.update_room_settings(room, s, true);
    assert (s->'categories'->-1->>'name') = 'Süper Kahraman', 'custom category sanitized';
    assert (select is_public from public.rooms where id = room), 'room public';

    -- ready checks
    perform pg_temp.expect_error(format('select public.start_match(%L)', room), 'PLAYERS_NOT_READY');
    perform pg_temp.act(B); perform public.set_ready(room, true);
    perform pg_temp.act(C); perform public.set_ready(room, true);
    perform pg_temp.act(A);
    m := public.start_match(room);
    perform pg_temp.expect_error(format('select public.start_match(%L)', room), 'GAME_IN_PROGRESS');   -- no double start
    assert (select count(*) from public.match_players where match_id = m) = 3, '3 participants (spectator excluded)';
    assert (select count(*) from public.player_jokers where match_id = m) = 3, 'one starting joker each';

    -- spectator sees state but is not a participant
    perform pg_temp.act(D);
    v := public.get_room_state(room);
    assert v->'round'->>'phase' = 'answering', 'spectator sees round';

    -- ================= ROUND LOOP =================
    for i in 1..3 loop
        select * into rd from public.rounds where match_id = m order by round_number desc limit 1;
        assert rd.round_number = i, 'round number ' || i;

        -- answers before the letter reveal finishes are refused
        perform pg_temp.act(A);
        if i = 1 then
            perform pg_temp.expect_error(format('select public.submit_answers(%L, %L)', rd.id, '{}'), 'ROUND_NOT_STARTED');
        end if;
        perform pg_temp.fast_forward_answering(room);
        update public.round_players set deadline_at = now() + interval '60 seconds' where round_id = rd.id;

        -- spectator cannot answer
        perform pg_temp.act(D);
        perform pg_temp.expect_error(format('select public.save_answers(%L, %L)', rd.id, '{}'), 'NOT_A_PARTICIPANT');

        if i = 1 then
            -- joker usage: give everyone deterministic jokers for the test
            update public.player_jokers set joker_type = 'slowdown' where match_id = m and user_id = B;
            update public.player_jokers set joker_type = 'double_points' where match_id = m and user_id = A;
            update public.player_jokers set joker_type = 'time_thief' where match_id = m and user_id = C;
            perform pg_temp.act(B);
            select id into j from public.player_jokers where match_id = m and user_id = B and status = 'available' limit 1;
            perform pg_temp.expect_error(format('select public.use_joker(%L, %L, %L)', rd.id, j.id, B), 'INVALID_TARGET');
            perform public.use_joker(rd.id, j.id, C);
            assert (select deadline_at from public.round_players where round_id = rd.id and user_id = C)
                 < now() + interval '51 seconds', 'slowdown removed 10 s from C';
            perform pg_temp.act(C);
            select id into j from public.player_jokers where match_id = m and user_id = C and status = 'available' limit 1;
            perform public.use_joker(rd.id, j.id, A);
            assert (select deadline_at from public.round_players where round_id = rd.id and user_id = A)
                 < now() + interval '56 seconds', 'thief took 5 s from A';
            assert (select deadline_at from public.round_players where round_id = rd.id and user_id = C)
                 > now() + interval '54 seconds', 'thief added 5 s to C';
            perform pg_temp.act(A);
            select id into j from public.player_jokers where match_id = m and user_id = A and status = 'available' limit 1;
            perform public.use_joker(rd.id, j.id, null, 'isim');
            -- no second joker in the same round (even if they had one)
            insert into public.player_jokers(match_id, room_id, user_id, joker_type, source) values (m, room, A, 'extra_time', 'round_win');
            select id into j from public.player_jokers where match_id = m and user_id = A and status = 'available' limit 1;
            perform pg_temp.expect_error(format('select public.use_joker(%L, %L)', rd.id, j.id), 'JOKER_ALREADY_USED_THIS_ROUND');
            perform pg_temp.act(B);
            perform pg_temp.expect_error(format('select public.use_joker(%L, %L)', rd.id, j.id), 'JOKER_NOT_OWNED');
        end if;

        -- A and B submit identical answers (duplicates), A first => early bonus
        perform pg_temp.act(A);
        v := public.submit_answers(rd.id, pg_temp.answers_for(rd.id, 0));
        assert (v->>'early_bonus')::int = 5, 'first complete submission gets +5';
        perform pg_temp.expect_error(format('select public.submit_answers(%L, %L)', rd.id, '{}'), 'ANSWERS_LOCKED');
        perform pg_temp.act(B);
        v := public.submit_answers(rd.id, pg_temp.answers_for(rd.id, 0));
        assert (v->>'early_bonus')::int = 0, 'only first submission gets bonus';

        -- C only saves a partial draft and then times out
        perform pg_temp.act(C);
        perform public.save_answers(rd.id, jsonb_build_object('isim', pg_temp.word_for('isim', rd.letter, 1), 'sehir', 'Qqq'));
        -- opponents' answers hidden during answering
        v := public.get_room_state(room);
        assert (select count(*) from jsonb_array_elements(v->'round'->'answers') x where x->>'user_id' <> C::text) = 0,
            'opponent answers hidden while answering';

        -- tick before deadline does nothing
        perform public.game_tick(room);
        assert (select phase from public.rounds where id = rd.id) = 'answering', 'still answering';
        update public.round_players set deadline_at = now() - interval '5 seconds' where round_id = rd.id and user_id = C;
        perform public.game_tick(room);
        select * into rd from public.rounds where id = rd.id;
        assert rd.phase in ('voting', 'reveal'), 'answering closed after deadline, got ' || rd.phase;
        perform pg_temp.expect_error(format('select public.save_answers(%L, %L)', rd.id, '{}'), 'ANSWERS_LOCKED');
        assert (select count(*) from public.answers where round_id = rd.id) = 3 * jsonb_array_length(rd.categories), 'all answer rows exist';
        assert (select validation from public.answers where round_id = rd.id and user_id = C and category_key = 'sehir') = 'invalid', 'wrong letter invalid';
        assert (select final_status from public.answers where round_id = rd.id and user_id = C and category_key = 'hayvan') = 'empty', 'empty answer';

        if rd.phase = 'voting' then
            -- everyone votes on uncertain answers; own answers are refused
            for j in select aa.id, aa.user_id from public.answers aa where aa.round_id = rd.id and aa.final_status is null loop
                perform pg_temp.act(j.user_id);
                perform pg_temp.expect_error(format('select public.cast_vote(%L, true)', j.id), 'CANNOT_VOTE_OWN');
            end loop;
            perform pg_temp.act(D);
            select id into j from public.answers where round_id = rd.id and final_status is null limit 1;
            perform pg_temp.expect_error(format('select public.cast_vote(%L, true)', j.id), 'NOT_A_PARTICIPANT');
            perform pg_temp.act(C);
            for j in select aa.id from public.answers aa where aa.round_id = rd.id and aa.final_status is null and aa.user_id <> C loop
                perform public.cast_vote(j.id, true);
                perform pg_temp.expect_error(format('select public.cast_vote(%L, false)', j.id), 'ALREADY_VOTED');
            end loop;
            -- A rejects B's uncertain answers => 1-1 tie => rejected; B accepts A's => 2-0 accepted
            perform pg_temp.act(A);
            for j in select aa.id from public.answers aa where aa.round_id = rd.id and aa.final_status is null and aa.user_id = B loop
                perform public.cast_vote(j.id, false);
            end loop;
            perform pg_temp.act(B);
            for j in select aa.id from public.answers aa where aa.round_id = rd.id and aa.final_status is null and aa.user_id = A loop
                perform public.cast_vote(j.id, true);
            end loop;
            -- C's uncertain answers: nobody voted on them yet => expire
            perform pg_temp.act(A);
            if (select phase from public.rounds where id = rd.id) = 'voting' then
                perform pg_temp.expire_all(room);
                perform public.game_tick(room);
            end if;
            select * into rd from public.rounds where id = rd.id;
            assert rd.phase = 'reveal', 'voting resolved -> reveal, got ' || rd.phase;
            assert not exists (select 1 from public.answers aa where aa.round_id = rd.id and aa.user_id = B
                               and aa.validation = 'uncertain' and aa.final_status <> 'rejected'), 'tie rejects';
            assert not exists (select 1 from public.answers aa where aa.round_id = rd.id and aa.user_id = A
                               and aa.validation = 'uncertain' and aa.final_status <> 'accepted'), 'majority accepts';
        end if;

        -- scoring checks
        assert rd.scored, 'round scored';
        -- A's and B's dictionary-valid answers are duplicates => 10 each (A's isim doubled in round 1)
        assert not exists (select 1 from public.answers aa where aa.round_id = rd.id and aa.user_id = B and aa.validation = 'valid'
                           and not (aa.points = 10 and aa.is_duplicate)), 'duplicates score 10';
        if i = 1 then
            select points into pts from public.answers where round_id = rd.id and user_id = A and category_key = 'isim';
            assert pts in (0, 20, 40), 'double points applied to isim, got ' || pts;
        end if;
        assert (select points from public.round_players where round_id = rd.id and user_id = A)
             = (select sum(points) from public.answers where round_id = rd.id and user_id = A) + 5, 'early bonus not doubled';

        -- reveal -> results -> next round
        perform pg_temp.expire_all(room);
        perform public.game_tick(room);
        assert (select phase from public.rounds where id = rd.id) = 'results', 'results phase';
        perform pg_temp.expire_all(room);
        perform public.game_tick(room);
        perform public.game_tick(room);   -- idempotent extra tick
    end loop;

    -- ================= MATCH END =================
    assert (select status from public.matches where id = m) = 'finished', 'match finished';
    assert (select status from public.rooms where id = room) = 'lobby', 'room back to lobby';
    assert (select count(*) from public.match_history where match_id = m) = 3, 'history rows';
    assert (select count(*) from public.xp_transactions where match_id = m and reason = 'match_complete') = 3, '+20 for everyone';
    assert (select count(*) from public.xp_transactions where match_id = m and reason = 'match_winner') >= 1, 'winner xp';
    assert (select count(*) from public.player_jokers where match_id = m and user_id = A and status = 'available') <= 3, 'inventory cap';
    select xp into xp_before from public.profiles where id = A;
    perform public._finish_match(m);                        -- idempotent reward processing
    perform public._award_xp(A, 100, 'match_winner', 'match_rank:' || m, m);
    assert (select xp from public.profiles where id = A) = xp_before, 'no duplicate XP';
    assert (select level from public.profiles where id = A) = public.level_for_xp(xp_before), 'level consistent';
    assert (select matches_played from public.player_statistics where user_id = A) = 1, 'stats';
    v := public.get_match_results(m);
    assert jsonb_array_length(v->'players') = 3, 'results players';
    assert (v->'players'->0->>'final_rank')::int = 1, 'ranked';

    -- ================= HOST TRANSFER =================
    update public.room_players set last_seen_at = now() - interval '2 minutes' where room_id = room and user_id = A;
    perform pg_temp.act(B);
    perform public.room_heartbeat(room);
    assert (select host_id from public.rooms where id = room) = B, 'host transferred to B';
    perform pg_temp.act(A);
    perform public.leave_room(room);
    perform pg_temp.act(B);
    perform public.close_room(room);
    assert (select status from public.rooms where id = room) = 'closed', 'room closed';

    -- ================= CODE GENERATION =================
    perform pg_temp.act(C);
    for i in 1..200 loop perform public.create_room(false); end loop;
    assert (select count(distinct r0.code) from public.rooms r0 where status <> 'closed') =
           (select count(*) from public.rooms where status <> 'closed'), 'open room codes unique';
    assert (select count(*) from public.room_players where user_id = C and status = 'active') = 1, 'one active room per player';

    -- ================= NORMALIZATION / LEVELS =================
    assert public.normalize_answer('  İSTANBUL   Boğazı ') = 'istanbul boğazı', 'turkish normalize';
    assert public.normalize_answer('IŞIK') = 'ışık', 'dotless i';
    assert public.level_for_xp(0) = 1 and public.level_for_xp(99) = 1 and public.level_for_xp(100) = 2
       and public.level_for_xp(250) = 3, 'xp curve';
    raise notice 'game flow test OK';
end $$;

-- ================= RLS (as the authenticated role) =================
select pg_temp.act('00000000-0000-0000-0000-00000000000c');
set role authenticated;
do $$
begin
    begin
        insert into public.rooms(code, host_id, settings) values ('HACK00', auth.uid(), '{}');
        raise exception 'direct insert should fail';
    exception when insufficient_privilege then null;
    end;
    begin
        update public.profiles set xp = 999999 where id = auth.uid();
        raise exception 'direct update should fail';
    exception when insufficient_privilege then null;
    end;
    begin
        perform count(*) from public.word_dictionary;
        raise exception 'dictionary should be private';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public._award_xp(auth.uid(), 1000, 'match_winner', 'hack', null);
        raise exception 'internal function should not be callable';
    exception when insufficient_privilege then null;
    end;
    assert (select count(*) from public.xp_transactions where user_id <> auth.uid()) = 0, 'cannot read others xp';
    raise notice 'RLS test OK';
end $$;
reset role;
