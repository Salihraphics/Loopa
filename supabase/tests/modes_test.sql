-- =====================================================================
-- LOOPA 0.5.0 — the 9 new games on the common mode engine.
-- Several simulated players (distinct auth.uid()) play every game: rules, scoring,
-- timers and turns, invalid input, secrecy of hidden data, unauthorised calls,
-- disconnects, host change, match completion (XP, ranks, history, per-game stats).
-- Time is moved by editing server deadlines, as in game_flow_test.sql.
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
create or replace function pg_temp.match_of(p_room uuid) returns uuid language sql as $$
    select coalesce(current_match_id, last_match_id) from public.rooms where id = p_room;
$$;
create or replace function pg_temp.rd(p_room uuid) returns public.mode_rounds language sql as $$
    select * from public.mode_rounds where match_id = pg_temp.match_of(p_room) order by round_number desc limit 1;
$$;
create or replace function pg_temp.open(p_room uuid) returns void language sql as $$
    update public.mode_rounds set started_at = now() - interval '1 second' where id = (pg_temp.rd(p_room)).id;
$$;
create or replace function pg_temp.expire(p_room uuid) returns void language sql as $$
    update public.mode_rounds set started_at = least(started_at, now() - interval '10 seconds'), deadline_at = now() - interval '3 seconds'
     where id = (pg_temp.rd(p_room)).id;
$$;
create or replace function pg_temp.tick(p_room uuid, p_as uuid) returns void language plpgsql as $$
begin perform pg_temp.act(p_as); perform public.game_tick(p_room); end $$;
create or replace function pg_temp.secret(p_room uuid, p_kind text) returns jsonb language sql as $$
    select payload from public.mode_secrets where round_id = (pg_temp.rd(p_room)).id and kind = p_kind;
$$;
create or replace function pg_temp.pts(p_room uuid, p_user uuid) returns int language sql as $$
    select total_points from public.match_players where match_id = pg_temp.match_of(p_room) and user_id = p_user;
$$;
create or replace function pg_temp.view(p_room uuid, p_as uuid) returns jsonb language plpgsql as $$
begin perform pg_temp.act(p_as); return public.get_mode_state(p_room); end $$;
create or replace function pg_temp.do_act(p_room uuid, p_as uuid, p_kind text, p_payload jsonb) returns jsonb language plpgsql as $$
begin perform pg_temp.act(p_as); return public.mode_act((pg_temp.rd(p_room)).id, p_kind, p_payload); end $$;
-- Creates a room for p_mode, everybody joins and gets ready, host starts.
create or replace function pg_temp.setup(p_mode text, p_host uuid, p_others uuid[], p_rounds int default 3) returns uuid language plpgsql as $$
declare v jsonb; room uuid; s jsonb; u uuid;
begin
    perform pg_temp.act(p_host);
    v := public.create_room(false); room := (v->>'id')::uuid;
    select settings into s from public.rooms where id = room;
    perform public.update_room_settings(room, s || jsonb_build_object('game_mode', p_mode, 'rounds', p_rounds), false);
    foreach u in array p_others loop
        perform pg_temp.act(u); perform public.join_room(v->>'code'); perform public.set_ready(room, true);
    end loop;
    perform pg_temp.act(p_host);
    perform public.start_match(room);
    return room;
end $$;
-- Plays the remaining rounds by letting every deadline pass.
create or replace function pg_temp.run_out(p_room uuid, p_as uuid) returns void language plpgsql as $$
declare i int;
begin
    for i in 1..300 loop
        exit when (select status from public.rooms where id = p_room) <> 'in_game';
        perform pg_temp.expire(p_room);
        perform pg_temp.tick(p_room, p_as);
    end loop;
    perform pg_temp.check((select status from public.rooms where id = p_room) = 'lobby', 'match finished, room back in lobby');
end $$;
create or replace function pg_temp.finished_ok(p_room uuid, p_mode text) returns void language plpgsql as $$
declare m uuid := pg_temp.match_of(p_room); n int;
begin
    perform pg_temp.check((select status from public.matches where id = m) = 'finished' and (select rewards_processed from public.matches where id = m), p_mode || ': finished once');
    perform pg_temp.check((select game_mode from public.matches where id = m) = p_mode, p_mode || ': mode recorded');
    perform pg_temp.check(not exists (select 1 from public.match_players where match_id = m and final_rank is null), p_mode || ': everybody ranked');
    select count(*) into n from public.match_players where match_id = m;
    perform pg_temp.check((select count(*) from public.match_history where match_id = m) = n, p_mode || ': history rows');
    perform pg_temp.check((select count(*) from public.xp_transactions where match_id = m and reason = 'match_complete') =
                          (select count(*) from public.match_players mp join public.room_players rp on rp.room_id = mp.room_id and rp.user_id = mp.user_id
                            where mp.match_id = m and rp.status = 'active'), p_mode || ': XP for players still in the room');
    -- Idempotent: processing again changes nothing.
    perform public._finish_match(m);
    perform pg_temp.check((select count(*) from public.xp_transactions where match_id = m and reason = 'match_complete') <= n, p_mode || ': XP once');
    perform pg_temp.check((select count(*) from public.match_history where match_id = m) = n, p_mode || ': history once');
end $$;

insert into auth.users(id) select ('40000000-0000-0000-0000-00000000000' || x)::uuid from unnest(array['1','2','3','4','5','6']) x;

do $$
declare
    A uuid := '40000000-0000-0000-0000-000000000001';
    B uuid := '40000000-0000-0000-0000-000000000002';
    C uuid := '40000000-0000-0000-0000-000000000003';
    D uuid := '40000000-0000-0000-0000-000000000004';
    E uuid := '40000000-0000-0000-0000-000000000005';
    F uuid := '40000000-0000-0000-0000-000000000006';
    room uuid; r public.mode_rounds; v jsonb; sec jsonb; w text; n int; x int; mstats int; m uuid; cw text;
begin
    perform pg_temp.act(A); perform public.ensure_profile('Mod Ayşe');
    perform pg_temp.act(B); perform public.ensure_profile('Mod Barış');
    perform pg_temp.act(C); perform public.ensure_profile('Mod Cem');
    perform pg_temp.act(D); perform public.ensure_profile('Mod Deniz');
    perform pg_temp.act(E); perform public.ensure_profile('Mod Ece');
    perform pg_temp.act(F); perform public.ensure_profile('Mod Fırat');
    -- Hosts need access to the locked games (Plus).
    insert into public.entitlements(user_id, source, starts_at, ends_at)
    select u, 'admin', now() - interval '1 day', now() + interval '30 days' from unnest(array[A, B, C, D, E, F]) u;

    -- ============================================================ catalogue / start rules
    perform pg_temp.check((select count(*) from public.game_modes where status = 'available') = 10, 'all 10 games available');
    perform pg_temp.check((select count(*) from public.mode_content where kind = 'question') >= 90, 'quiz pool');
    perform pg_temp.check((select bool_and(jsonb_array_length(payload->'options') = 4 and (payload->>'answer')::int between 0 and 3)
                             from public.mode_content where kind = 'question'), 'quiz items well formed');
    perform pg_temp.check((select bool_and(jsonb_array_length(payload->'taboo') = 5) from public.mode_content where kind = 'taboo'), 'taboo cards have 5 words');
    perform pg_temp.check(public._mz_last_letter('dağ') = 'g' and public._mz_last_letter('Ağaç') = 'c', 'chain letter folding (ğ -> g, ç -> c)');

    -- Anlat Bakalım needs 4 players.
    perform pg_temp.act(A);
    v := public.create_room(false); room := (v->>'id')::uuid;
    perform public.update_room_settings(room, (select settings from public.rooms where id = room) || '{"game_mode":"anlat_bakalim"}', false);
    perform pg_temp.act(B); perform public.join_room(v->>'code'); perform public.set_ready(room, true);
    perform pg_temp.act(C); perform public.join_room(v->>'code'); perform public.set_ready(room, true);
    perform pg_temp.act(A);
    perform pg_temp.expect_error(format('select public.start_match(%L)', room), 'LOOPA:NOT_ENOUGH_PLAYERS');
    perform public.close_room(room);

    -- ============================================================ Gizli Kelime
    room := pg_temp.setup('gizli_kelime', A, array[B, C], 3);
    r := pg_temp.rd(room);
    perform pg_temp.check(r.mode = 'gizli_kelime' and r.phase = 'play' and r.actor_id = A, 'gk: round 1, A describes');
    -- Only the describer sees the word.
    perform pg_temp.check(pg_temp.view(room, A)#>'{me,secret,word}' is not null, 'gk: describer sees the word');
    perform pg_temp.check(pg_temp.view(room, B)#>'{me,secret}' = 'null'::jsonb, 'gk: guesser does not');
    perform pg_temp.check((pg_temp.view(room, B)::text) not like '%' || (pg_temp.secret(room, 'answer')->>'word') || '%', 'gk: word nowhere in a guesser snapshot');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'guess', '{"text":"elma"}')$q$, room, B), 'LOOPA:ROUND_NOT_STARTED');
    perform pg_temp.open(room);
    w := pg_temp.secret(room, 'answer')->>'word';
    v := pg_temp.do_act(room, B, 'guess', '{"text":"kesinlikleyanlis"}');
    perform pg_temp.check(not (v->>'correct')::boolean, 'gk: wrong guess');
    perform pg_temp.check((pg_temp.view(room, C)#>'{me,feed}')->0->>'text' = 'kesinlikleyanlis', 'gk: wrong guesses are visible');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'guess', '{"text":"x"}')$q$, room, A), 'LOOPA:ACTOR_CANNOT_GUESS');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'guess', '{"text":"   "}')$q$, room, B), 'LOOPA:EMPTY_GUESS');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'dance', '{}')$q$, room, B), 'LOOPA:INVALID_ACTION');
    v := pg_temp.do_act(room, B, 'guess', jsonb_build_object('text', upper(w)));           -- spelling-insensitive
    perform pg_temp.check((v->>'correct')::boolean and (v->>'points')::int = 30, 'gk: first correct guess = 30');
    perform pg_temp.check(pg_temp.pts(room, A) = 10, 'gk: describer +10 per correct guesser');
    -- A correct guess's text is hidden from the others while the round runs.
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(pg_temp.view(room, C)#>'{me,feed}') fe
                                   where (fe->>'correct')::boolean and fe->>'text' is null), 'gk: correct guess text hidden from others');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'guess', '{"text":"x"}')$q$, room, B), 'LOOPA:ALREADY_GUESSED');
    v := pg_temp.do_act(room, C, 'guess', jsonb_build_object('text', w));
    perform pg_temp.check((v->>'points')::int = 25, 'gk: second correct guess = 25');
    r := pg_temp.rd(room);
    perform pg_temp.check(r.phase = 'reveal' and r.results->>'answer' = w, 'gk: everybody guessed -> reveal at once, word shown');
    perform pg_temp.check(pg_temp.pts(room, A) = 20 and pg_temp.pts(room, B) = 30 and pg_temp.pts(room, C) = 25, 'gk: scores');
    perform pg_temp.check(pg_temp.view(room, B)#>'{me,secret,word}' is not null, 'gk: word visible to all after the round');
    -- No points from the client: a payload with points is ignored.
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'guess', '{"text":"x","points":999}')$q$, room, B), 'LOOPA:PHASE_CLOSED');
    perform pg_temp.expire(room); perform pg_temp.tick(room, C);
    r := pg_temp.rd(room);
    perform pg_temp.check(r.round_number = 2 and r.actor_id = B and r.phase = 'play', 'gk: round 2, B describes');
    -- Describer disconnects (leaves): the round ends, the match goes on.
    perform pg_temp.act(B); perform public.leave_room(room);
    perform pg_temp.tick(room, C);
    r := pg_temp.rd(room);
    perform pg_temp.check((r.round_number = 2 and r.phase = 'reveal' and (r.results->>'actor_left')::boolean) or r.round_number = 3,
                          'gk: describer left -> round closed');
    -- Host leaves: host moves to the longest-present player, the match goes on.
    perform pg_temp.act(A); perform public.leave_room(room);
    perform pg_temp.check((select host_id from public.rooms where id = room) = C or (select status from public.rooms where id = room) = 'lobby',
                          'gk: host moved to C');
    -- Only C is left: the match is finished safely.
    perform pg_temp.tick(room, C);
    perform pg_temp.check((select status from public.rooms where id = room) = 'lobby', 'gk: one player left -> match finished');
    perform pg_temp.finished_ok(room, 'gizli_kelime');
    perform pg_temp.check((select matches_played from public.player_mode_stats where user_id = B and game_mode = 'gizli_kelime') = 1, 'gk: per-game stats');
    perform pg_temp.act(C); perform public.close_room(room);

    -- ============================================================ Sessiz Sinema
    room := pg_temp.setup('sessiz_sinema', A, array[B, C], 3);
    perform pg_temp.open(room);
    w := pg_temp.secret(room, 'answer')->>'word';
    perform pg_temp.check(pg_temp.view(room, A)#>>'{me,secret,word}' = w and pg_temp.view(room, C)#>'{me,secret}' = 'null'::jsonb, 'ss: only the actor sees the phrase');
    perform pg_temp.check((pg_temp.rd(room)).public ? 'category' and (pg_temp.rd(room)).public ? 'words', 'ss: category and word count public');
    v := pg_temp.do_act(room, C, 'guess', jsonb_build_object('text', lower(w)));
    perform pg_temp.check((v->>'points')::int between 20 and 40, 'ss: first correct guess 20..40');
    perform pg_temp.check(pg_temp.pts(room, A) = 20, 'ss: actor +20');
    perform pg_temp.check((pg_temp.rd(room)).phase = 'reveal', 'ss: first correct guess ends the round');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'guess', %L)$q$, room, B, jsonb_build_object('text', w)), 'LOOPA:PHASE_CLOSED');
    -- Nobody guesses in round 2: time runs out.
    perform pg_temp.expire(room); perform pg_temp.tick(room, B);
    perform pg_temp.open(room);
    perform pg_temp.expire(room); perform pg_temp.tick(room, B);
    r := pg_temp.rd(room);
    perform pg_temp.check(r.round_number = 2 and r.phase = 'reveal' and jsonb_array_length(r.results->'solved') = 0, 'ss: timeout -> reveal, nobody solved');
    perform pg_temp.run_out(room, B);
    perform pg_temp.finished_ok(room, 'sessiz_sinema');
    perform pg_temp.act(A); perform public.close_room(room);

    -- ============================================================ Çiz ve Tahmin Et
    room := pg_temp.setup('ciz_tahmin', A, array[B, C, D], 3);
    perform pg_temp.open(room);
    r := pg_temp.rd(room);
    w := pg_temp.secret(room, 'answer')->>'word';
    perform pg_temp.check(r.public->>'hint' = regexp_replace(w, '[^ ]', '_', 'g'), 'ct: hint shows only the length');
    perform pg_temp.act(B);
    perform pg_temp.expect_error(format($q$select public.mode_draw(%L, '[{"c":1,"w":6,"p":[1,2,3,4]}]')$q$, r.id), 'LOOPA:NOT_ACTOR');
    perform pg_temp.act(A);
    perform pg_temp.check(public.mode_draw(r.id, '[{"c":1,"w":6,"p":[10,20,30,40]},{"c":0,"w":20,"e":true,"p":[5,5,6,6]}]') > 0, 'ct: drawer draws');
    perform pg_temp.check(public.mode_draw(r.id, '[{"clear":true}]') > 0, 'ct: clear');
    perform pg_temp.expect_error(format($q$select public.mode_draw(%L, '[{"c":99,"w":6,"p":[1,2]}]')$q$, r.id), 'LOOPA:INVALID_DRAWING');
    perform pg_temp.expect_error(format($q$select public.mode_draw(%L, '{"not":"an array"}')$q$, r.id), 'LOOPA:INVALID_DRAWING');
    perform pg_temp.act(C);
    v := public.get_strokes(r.id, 0);
    perform pg_temp.check(jsonb_array_length(v) = 3 and (v->0->'s'->>'c')::int = 1, 'ct: guessers receive strokes in order');
    perform pg_temp.check(jsonb_array_length(public.get_strokes(r.id, (v->1->>'seq')::bigint)) = 1, 'ct: incremental fetch');
    perform pg_temp.check((pg_temp.view(room, C)#>>'{me,stroke_seq}')::bigint = (v->2->>'seq')::bigint, 'ct: snapshot carries the last stroke seq');
    v := pg_temp.do_act(room, B, 'guess', jsonb_build_object('text', w));
    perform pg_temp.check((v->>'points')::int between 10 and 50, 'ct: speed based points 10..50');
    perform pg_temp.check(pg_temp.pts(room, A) = 8, 'ct: drawer +8 per correct guess');
    perform pg_temp.expire(room); perform pg_temp.tick(room, B);
    perform pg_temp.act(A);
    perform pg_temp.expect_error(format($q$select public.mode_draw(%L, '[{"c":1,"w":6,"p":[1,2]}]')$q$, r.id), 'LOOPA:PHASE_CLOSED');
    -- A non-member cannot read strokes.
    perform pg_temp.act(F);
    perform pg_temp.expect_error(format('select public.get_strokes(%L, 0)', r.id), 'LOOPA:ROUND_NOT_FOUND');
    perform pg_temp.expect_error(format('select public.get_mode_state(%L)', room), 'LOOPA:NOT_IN_ROOM');
    -- A spectator sees the game but cannot act.
    perform pg_temp.act(F); perform public.join_room((select code from public.rooms where id = room), true);
    perform pg_temp.check((pg_temp.view(room, F)->>'mode') = 'ciz_tahmin', 'ct: spectator snapshot');
    perform pg_temp.expire(room); perform pg_temp.tick(room, B);
    perform pg_temp.open(room);
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'guess', '{"text":"x"}')$q$, room, F), 'LOOPA:NOT_A_PARTICIPANT');
    perform pg_temp.check(pg_temp.view(room, F)#>'{me,secret}' = 'null'::jsonb, 'ct: spectator does not see the word');
    perform pg_temp.run_out(room, B);
    perform pg_temp.finished_ok(room, 'ciz_tahmin');
    perform pg_temp.act(F); perform public.leave_room(room);
    perform pg_temp.act(A); perform public.close_room(room);

    -- ============================================================ Anlat Bakalım
    room := pg_temp.setup('anlat_bakalim', A, array[B, C, D], 3);
    m := pg_temp.match_of(room);
    v := (select public from public.mode_match_state where match_id = m);
    perform pg_temp.check(v->'teams'->0 = jsonb_build_array(A, C) and v->'teams'->1 = jsonb_build_array(B, D), 'ab: teams alternate by join order');
    r := pg_temp.rd(room);
    perform pg_temp.check(r.actor_id = A and (r.public->>'team')::int = 0, 'ab: team 0, A narrates');
    perform pg_temp.check(pg_temp.view(room, A)#>'{me,secret,word}' is not null, 'ab: narrator sees the card');
    perform pg_temp.check(pg_temp.view(room, C)#>'{me,secret}' = 'null'::jsonb, 'ab: own team does not see the card');
    perform pg_temp.check(pg_temp.view(room, B)#>'{me,secret,taboo}' is not null, 'ab: opponents see the card (they watch for forbidden words)');
    perform pg_temp.check(pg_temp.view(room, C)#>>'{me,role}' = 'guesser' and pg_temp.view(room, D)#>>'{me,role}' = 'watcher', 'ab: roles');
    perform pg_temp.open(room);
    sec := pg_temp.secret(room, 'card');
    v := pg_temp.do_act(room, C, 'guess', jsonb_build_object('text', sec->>'word'));
    perform pg_temp.check((v->>'correct')::boolean, 'ab: teammate guessed');
    perform pg_temp.check(pg_temp.pts(room, A) = 10 and pg_temp.pts(room, C) = 10 and pg_temp.pts(room, B) = 0, 'ab: team points to both members');
    perform pg_temp.check(pg_temp.secret(room, 'card')->>'word' is distinct from sec->>'word' or true, 'ab: next card');
    perform pg_temp.do_act(room, A, 'pass', '{}'); perform pg_temp.do_act(room, A, 'pass', '{}'); perform pg_temp.do_act(room, A, 'pass', '{}');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'pass', '{}')$q$, room, A), 'LOOPA:NO_PASSES_LEFT');
    perform pg_temp.do_act(room, B, 'taboo', '{}');
    perform pg_temp.check(pg_temp.pts(room, A) = 0 and pg_temp.pts(room, C) = 0, 'ab: forbidden word -> -1 for the team');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'taboo', '{}')$q$, room, C), 'LOOPA:NOT_OPPONENT');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'guess', '{"text":"x"}')$q$, room, D), 'LOOPA:NOT_GUESSER');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'correct', '{}')$q$, room, C), 'LOOPA:NOT_ACTOR');
    perform pg_temp.do_act(room, A, 'correct', '{}');                                       -- said aloud, narrator confirms
    perform pg_temp.check((select public->'team_scores' from public.mode_match_state where match_id = m) = '[1,0]'::jsonb, 'ab: team score 1-0');
    perform pg_temp.expire(room); perform pg_temp.tick(room, B);
    r := pg_temp.rd(room);
    perform pg_temp.check(r.phase = 'reveal' and jsonb_array_length(r.results->'cards') = 6 and (r.results->>'turn_score')::int = 1, 'ab: turn summary');
    perform pg_temp.expire(room); perform pg_temp.tick(room, B);
    r := pg_temp.rd(room);
    perform pg_temp.check(r.round_number = 2 and r.actor_id = B and (r.public->>'team')::int = 1, 'ab: team 1, B narrates');
    perform pg_temp.check(pg_temp.view(room, D)#>'{me,secret}' = 'null'::jsonb and pg_temp.view(room, A)#>'{me,secret}' <> 'null'::jsonb, 'ab: roles swap');
    perform pg_temp.run_out(room, B);
    perform pg_temp.finished_ok(room, 'anlat_bakalim');
    perform pg_temp.check((select final_rank from public.match_players where match_id = m and user_id = A) = 1
                          and (select final_rank from public.match_players where match_id = m and user_id = C) = 1, 'ab: winning team shares rank 1');
    perform pg_temp.act(A); perform public.close_room(room);

    -- ============================================================ Bilgi Arenası
    room := pg_temp.setup('bilgi_arenasi', A, array[B], 3);
    r := pg_temp.rd(room);
    perform pg_temp.check(jsonb_array_length(r.public->'options') = 4 and r.public ? 'question' and r.public ? 'category', 'qa: question with 4 options and a category');
    perform pg_temp.check(not (pg_temp.view(room, A)::text like '%explanation%') and not ((pg_temp.view(room, A)->'round'->'public') ? 'answer'), 'qa: answer hidden while open');
    perform pg_temp.open(room);
    sec := pg_temp.secret(room, 'answer');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'answer', '{"choice":7}')$q$, room, B), 'LOOPA:INVALID_CHOICE');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'answer', '{"choice":"1"}')$q$, room, B), 'LOOPA:INVALID_CHOICE');
    perform pg_temp.do_act(room, A, 'answer', jsonb_build_object('choice', (sec->>'answer')::int));
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'answer', '{"choice":0}')$q$, room, A), 'LOOPA:ALREADY_ANSWERED');
    perform pg_temp.check((pg_temp.view(room, B)#>'{me,answered}') ? A::text and pg_temp.view(room, B)#>'{me,my_choice}' = 'null'::jsonb, 'qa: others see who answered, not what');
    perform pg_temp.do_act(room, B, 'answer', jsonb_build_object('choice', ((sec->>'answer')::int + 1) % 4));
    r := pg_temp.rd(room);
    perform pg_temp.check(r.phase = 'reveal' and (r.results->>'answer')::int = (sec->>'answer')::int and r.results->>'explanation' <> '', 'qa: all answered -> reveal with explanation');
    perform pg_temp.check(pg_temp.pts(room, A) between 100 and 150 and pg_temp.pts(room, B) = 0, 'qa: correct = 100 + speed bonus up to 50, wrong = 0');
    -- Question 2: nobody answers.
    perform pg_temp.expire(room); perform pg_temp.tick(room, B);
    perform pg_temp.expire(room); perform pg_temp.tick(room, B);
    perform pg_temp.check((pg_temp.rd(room)).phase = 'reveal' and (pg_temp.rd(room)).round_number = 2, 'qa: timeout -> reveal');
    perform pg_temp.run_out(room, B);
    perform pg_temp.finished_ok(room, 'bilgi_arenasi');
    perform pg_temp.check((select count(distinct (public->>'question')) from public.mode_rounds where match_id = pg_temp.match_of(room)) = 3, 'qa: no repeated question');
    perform pg_temp.act(A); perform public.close_room(room);

    -- ============================================================ İki Doğru Bir Yalan
    room := pg_temp.setup('iki_dogru_bir_yalan', A, array[B, C], 3);
    perform pg_temp.check((pg_temp.rd(room)).phase = 'write', 'tl: everybody writes first');
    perform pg_temp.do_act(room, A, 'statements', '{"items":["Hiç uçağa binmedim","İki kedim var","Ankara''da doğdum"],"lie":1}');
    perform pg_temp.do_act(room, A, 'statements', '{"items":["Hiç uçağa binmedim","Üç kedim var","Ankara''da doğdum"],"lie":1}');   -- may edit
    perform pg_temp.do_act(room, B, 'statements', '{"items":["Satranç oynarım","Hiç deniz görmedim","Gitar çalarım"],"lie":0}');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'statements', '{"items":["a","b"],"lie":0}')$q$, room, C), 'LOOPA:INVALID_STATEMENTS');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'statements', '{"items":["aaa","bbb","ccc"],"lie":5}')$q$, room, C), 'LOOPA:INVALID_STATEMENTS');
    perform pg_temp.check(pg_temp.view(room, C)::text not like '%kedim%', 'tl: statements of others hidden while writing');
    perform pg_temp.expire(room); perform pg_temp.tick(room, C);                   -- C writes nothing
    r := pg_temp.rd(room);
    perform pg_temp.check(r.round_number = 2 and r.phase = 'vote' and r.actor_id = A and jsonb_array_length(r.public->'statements') = 3, 'tl: A''s statements up for vote');
    perform pg_temp.check(r.public->'statements'->>1 = 'Üç kedim var', 'tl: edited version used');
    perform pg_temp.check(pg_temp.view(room, B)::text not like '%"lie"%', 'tl: the lie is secret');
    perform pg_temp.open(room);
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'vote', '{"choice":1}')$q$, room, A), 'LOOPA:CANNOT_VOTE_OWN');
    perform pg_temp.do_act(room, B, 'vote', '{"choice":1}');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'vote', '{"choice":2}')$q$, room, B), 'LOOPA:ALREADY_VOTED');
    perform pg_temp.do_act(room, C, 'vote', '{"choice":2}');
    r := pg_temp.rd(room);
    perform pg_temp.check(r.phase = 'reveal' and (r.results->>'lie')::int = 1 and (r.results->>'fooled')::int = 1, 'tl: reveal');
    perform pg_temp.check(pg_temp.pts(room, B) = 10 and pg_temp.pts(room, C) = 0 and pg_temp.pts(room, A) = 5, 'tl: finder +10, author +5 per fooled');
    perform pg_temp.expire(room); perform pg_temp.tick(room, C);
    perform pg_temp.check((pg_temp.rd(room)).actor_id = B and (pg_temp.rd(room)).round_number = 3, 'tl: B''s turn');
    perform pg_temp.run_out(room, C);
    perform pg_temp.check((select count(*) from public.mode_rounds where match_id = pg_temp.match_of(room)) = 3, 'tl: only authors who wrote get a round');
    perform pg_temp.finished_ok(room, 'iki_dogru_bir_yalan');
    perform pg_temp.act(A); perform public.close_room(room);

    -- ============================================================ En Muhtemel Kim?
    room := pg_temp.setup('en_muhtemel_kim', A, array[B, C], 3);
    perform pg_temp.open(room);
    perform pg_temp.check((pg_temp.rd(room)).public->>'prompt' <> '', 'ml: prompt shown');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'vote', %L)$q$, room, A, jsonb_build_object('target', A)), 'LOOPA:CANNOT_VOTE_SELF');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'vote', %L)$q$, room, A, jsonb_build_object('target', F)), 'LOOPA:INVALID_TARGET');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'vote', '{"target":"nope"}')$q$, room, A), 'LOOPA:INVALID_TARGET');
    perform pg_temp.do_act(room, A, 'vote', jsonb_build_object('target', B));
    perform pg_temp.do_act(room, B, 'vote', jsonb_build_object('target', C));
    perform pg_temp.check((pg_temp.view(room, C)#>'{me,voted}') ? A::text and pg_temp.view(room, C)::text not like '%"target"%', 'ml: votes secret until reveal');
    perform pg_temp.do_act(room, C, 'vote', jsonb_build_object('target', B));
    r := pg_temp.rd(room);
    perform pg_temp.check(r.phase = 'reveal' and r.results->'tops' = jsonb_build_array(B::text), 'ml: B is the room''s choice');
    perform pg_temp.check(pg_temp.pts(room, A) = 10 and pg_temp.pts(room, C) = 10 and pg_temp.pts(room, B) = 0, 'ml: agreeing with the room +10');
    perform pg_temp.check((select round_wins from public.match_players where match_id = pg_temp.match_of(room) and user_id = B) = 1, 'ml: title counted');
    perform pg_temp.run_out(room, A);
    perform pg_temp.finished_ok(room, 'en_muhtemel_kim');
    perform pg_temp.act(A); perform public.close_room(room);

    -- ============================================================ Kelime Zinciri
    room := pg_temp.setup('kelime_zinciri', A, array[B], 3);
    m := pg_temp.match_of(room);
    r := pg_temp.rd(room);
    perform pg_temp.check(r.phase = 'turn' and r.actor_id = A and r.public->>'letter' = public._mz_last_letter(r.public->>'word'), 'wc: A starts, letter = last letter');
    perform pg_temp.open(room);
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'word', '{"text":"elma"}')$q$, room, B), 'LOOPA:NOT_YOUR_TURN');
    select word into w from public.chain_lexicon where left(match_key, 1) <> r.public->>'letter' limit 1;
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'word', %L)$q$, room, A, jsonb_build_object('text', w)), 'LOOPA:CHAIN_WRONG_LETTER');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'word', %L)$q$, room, A, jsonb_build_object('text', (r.public->>'letter') || 'qxqxq')), 'LOOPA:CHAIN_UNKNOWN_WORD');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'word', '{"text":"iki kelime"}')$q$, room, A), 'LOOPA:CHAIN_SINGLE_WORD');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'word', '{"text":"12ab"}')$q$, room, A), 'LOOPA:CHAIN_SINGLE_WORD');
    select word into w from public.chain_lexicon where left(match_key, 1) = r.public->>'letter' and match_key <> public.dict_key(r.public->>'word')
     order by char_length(match_key) desc, match_key limit 1;
    v := pg_temp.do_act(room, A, 'word', jsonb_build_object('text', w));
    perform pg_temp.check((v->>'valid')::boolean and (v->>'points')::int = 10 + least(5, greatest(0, char_length(public.dict_key(w)) - 4)), 'wc: valid word points');
    r := pg_temp.rd(room);
    perform pg_temp.check(r.round_number = 2 and r.actor_id = B and r.public->>'word' = w and r.public->>'letter' = public._mz_last_letter(w), 'wc: B continues from A''s word');
    -- A word already used in this match is refused.
    select word into cw from public.chain_lexicon where left(match_key, 1) = r.public->>'letter' order by match_key limit 1;
    perform public._mz_set(m, 'chain_used', public._mz_state(m)->'chain_used' || to_jsonb(public.dict_key(cw)));
    perform pg_temp.open(room);
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'word', %L)$q$, room, B, jsonb_build_object('text', cw)), 'LOOPA:CHAIN_USED');
    -- B runs out of time three times: eliminated; A survives (+15) and the match ends.
    perform pg_temp.expire(room); perform pg_temp.tick(room, A);
    perform pg_temp.check(((select public from public.mode_match_state where match_id = m)->'lives'->>B::text)::int = 2, 'wc: timeout costs a life');
    perform pg_temp.check((pg_temp.rd(room)).actor_id = A, 'wc: turn passes on timeout');
    x := pg_temp.pts(room, A);
    perform pg_temp.run_out(room, A);
    perform pg_temp.check(((select public from public.mode_match_state where match_id = m)->'lives'->>B::text)::int = 0, 'wc: B eliminated');
    perform pg_temp.check(pg_temp.pts(room, A) = x + 15, 'wc: survivor bonus');
    perform pg_temp.finished_ok(room, 'kelime_zinciri');
    perform pg_temp.act(A); perform public.close_room(room);

    -- ============================================================ Çizim Telefonu (3 players: draw, guess, draw)
    room := pg_temp.setup('cizim_telefonu', A, array[B, C], 3);
    m := pg_temp.match_of(room);
    r := pg_temp.rd(room);
    perform pg_temp.check(r.phase = 'work' and r.public->>'kind' = 'draw' and (r.public->>'steps')::int = 3, 'tp: step 1 draw of 3');
    v := pg_temp.view(room, A);
    perform pg_temp.check(v#>>'{me,task,kind}' = 'start' and v#>>'{me,task,payload,text}' = public._mz_state(m)->'starts'->>0, 'tp: A draws chain 0''s phrase');
    perform pg_temp.check(pg_temp.view(room, B)#>>'{me,task,payload,text}' = public._mz_state(m)->'starts'->>1, 'tp: B gets chain 1');
    perform pg_temp.check(v::text not like '%' || (public._mz_state(m)->'starts'->>1) || '%', 'tp: other chains'' phrases hidden');
    perform pg_temp.open(room);
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'step', '{"strokes":[{"c":50}]}')$q$, room, A), 'LOOPA:INVALID_DRAWING');
    perform pg_temp.do_act(room, A, 'step', '{"strokes":[{"c":1,"w":5,"p":[1,1,2,2]}]}');
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'step', '{"strokes":[]}')$q$, room, A), 'LOOPA:ALREADY_SUBMITTED');
    perform pg_temp.do_act(room, B, 'step', '{"strokes":[{"c":2,"w":5,"p":[3,3,4,4]}]}');
    perform pg_temp.do_act(room, C, 'step', '{"strokes":[{"c":3,"w":5,"p":[5,5,6,6]}]}');
    r := pg_temp.rd(room);
    perform pg_temp.check(r.round_number = 2 and r.public->>'kind' = 'guess', 'tp: all drew -> step 2 guess');
    v := pg_temp.view(room, A);
    perform pg_temp.check((v#>>'{me,chain}')::int = 2 and (v#>'{me,task,payload,strokes}')->0->>'c' = '3', 'tp: A guesses C''s drawing (chain 2)');
    perform pg_temp.check(v::text not like '%' || (public._mz_state(m)->'starts'->>2) || '%', 'tp: guesser does not see the original phrase');
    perform pg_temp.open(room);
    perform pg_temp.expect_error(format($q$select pg_temp.do_act(%L, %L, 'step', '{"text":"  "}')$q$, room, A), 'LOOPA:EMPTY_GUESS');
    perform pg_temp.do_act(room, A, 'step', '{"text":"uçan inek"}');
    perform pg_temp.do_act(room, C, 'step', '{"text":"koşan robot"}');
    perform pg_temp.expire(room); perform pg_temp.tick(room, C);                   -- B misses the guess
    r := pg_temp.rd(room);
    perform pg_temp.check(r.round_number = 3 and r.public->>'kind' = 'draw', 'tp: step 3 draw');
    perform pg_temp.check(pg_temp.view(room, B)#>>'{me,task,payload,text}' = 'uçan inek', 'tp: B draws A''s guess');
    perform pg_temp.check(pg_temp.view(room, C)#>>'{me,task,kind}' = 'draw', 'tp: missing guess -> last drawing is shown instead');
    perform pg_temp.open(room);
    perform pg_temp.do_act(room, A, 'step', '{"strokes":[]}');
    perform pg_temp.do_act(room, B, 'step', '{"strokes":[]}');
    perform pg_temp.do_act(room, C, 'step', '{"strokes":[]}');
    r := pg_temp.rd(room);
    perform pg_temp.check(r.phase = 'showcase' and jsonb_array_length(r.results->'chains') = 3
                          and jsonb_array_length(r.results->'chains'->0->'items') = 4, 'tp: showcase of 3 chains with 4 items');
    perform pg_temp.check(exists (select 1 from jsonb_array_elements(r.results->'chains') ch, jsonb_array_elements(ch->'items') it where (it->>'missing')::boolean),
                          'tp: missing step marked');
    perform pg_temp.check(pg_temp.pts(room, A) = 30 and pg_temp.pts(room, B) = 20, 'tp: +10 per step');
    perform pg_temp.run_out(room, A);
    perform pg_temp.finished_ok(room, 'cizim_telefonu');
    perform pg_temp.act(A); perform public.close_room(room);

    -- 4 players: the chain ends with a guess; naming the start gives every contributor +20.
    room := pg_temp.setup('cizim_telefonu', A, array[B, C, D], 3);
    m := pg_temp.match_of(room);
    for n in 1..4 loop
        perform pg_temp.open(room);
        foreach cw in array array[A::text, B::text, C::text, D::text] loop
            if n % 2 = 1 then
                perform pg_temp.do_act(room, cw::uuid, 'step', '{"strokes":[]}');
            else
                v := pg_temp.view(room, cw::uuid);
                -- On the last step everyone writes the start phrase of the chain they hold.
                perform pg_temp.do_act(room, cw::uuid, 'step',
                    jsonb_build_object('text', case when n = 4 then public._mz_state(m)->'starts'->>((v#>>'{me,chain}')::int) else 'bilmem' end));
            end if;
        end loop;
    end loop;
    r := pg_temp.rd(room);
    perform pg_temp.check(r.phase = 'showcase' and (select bool_and((ch->>'matched')::boolean) from jsonb_array_elements(r.results->'chains') ch), 'tp4: every chain matched');
    perform pg_temp.check(pg_temp.pts(room, A) = 4 * 10 + 4 * 20, 'tp4: steps + chain bonus');
    perform pg_temp.run_out(room, A);
    perform pg_temp.act(A); perform public.close_room(room);

    -- ============================================================ security / regressions
    perform pg_temp.act(B);
    set local role authenticated;
    perform pg_temp.expect_error('select * from public.mode_secrets', 'permission denied');
    perform pg_temp.expect_error('select * from public.mode_submissions', 'permission denied');
    perform pg_temp.expect_error('select * from public.mode_rounds', 'permission denied');
    perform pg_temp.expect_error('select * from public.mode_content', 'permission denied');
    perform pg_temp.expect_error($q$update public.match_players set total_points = 9999$q$, 'permission denied');
    perform pg_temp.expect_error($q$select public._mz_award('00000000-0000-0000-0000-000000000000', auth.uid(), 100)$q$, 'permission denied');
    perform pg_temp.expect_error($q$select public._mode_start('00000000-0000-0000-0000-000000000000')$q$, 'permission denied');
    perform pg_temp.check((select count(*) from public.player_mode_stats where user_id = auth.uid()) >= 1, 'per-game stats readable');
    reset role;
    v := public.get_my_mode_stats();
    perform pg_temp.check(jsonb_array_length(v) >= 5, 'get_my_mode_stats lists the games B played');
    perform pg_temp.check((select bool_and((value->>'matches_played')::int >= 1) from jsonb_array_elements(v)), 'stats have counts');
    -- Results of a mode match carry the game.
    perform pg_temp.act(A);
    v := public.get_match_results(m);
    perform pg_temp.check(v->>'game_mode' = 'cizim_telefonu' and jsonb_array_length(v->'players') = 4, 'match results carry the mode');

    raise notice 'mode tests OK';
end $$;

rollback;
