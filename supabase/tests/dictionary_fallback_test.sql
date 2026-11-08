-- Controlled fallback for unknown words + suggestion evidence.
\set ON_ERROR_STOP 1
set client_min_messages = warning;
create or replace function pg_temp.act(p uuid) returns void language sql as $$ select set_config('request.jwt.claim.sub', p::text, false); $$;
insert into auth.users(id) values ('30000000-0000-0000-0000-00000000000a'), ('30000000-0000-0000-0000-00000000000b'), ('30000000-0000-0000-0000-00000000000c');
do $$
declare A uuid := '30000000-0000-0000-0000-00000000000a'; B uuid := '30000000-0000-0000-0000-00000000000b'; C uuid := '30000000-0000-0000-0000-00000000000c';
        v jsonb; room uuid; m uuid; rd public.rounds; s jsonb; ans record;
begin
    perform pg_temp.act(A); perform public.ensure_profile('Yedek A');
    perform pg_temp.act(B); perform public.ensure_profile('Yedek B');
    perform pg_temp.act(C); perform public.ensure_profile('Yedek C');
    perform pg_temp.act(A);
    v := public.create_room(false); room := (v->>'id')::uuid;
    s := public._default_settings() || jsonb_build_object('rounds', 3, 'categories', jsonb_build_array(
        jsonb_build_object('key','hayvan','custom',false), jsonb_build_object('key','yemek','custom',false), jsonb_build_object('key','esya','custom',false)));
    perform public.update_room_settings(room, s, false);
    perform pg_temp.act(B); perform public.join_room(v->>'code'); perform public.set_ready(room, true);
    perform pg_temp.act(C); perform public.join_room(v->>'code'); perform public.set_ready(room, true);
    perform pg_temp.act(A); m := public.start_match(room);

    -- ROUND 1: three players vote on unknown words -> evidence is recorded once per answer
    update public.rounds set letter = 'K', started_at = now() - interval '1 second' where match_id = m;
    select * into rd from public.rounds where match_id = m and round_number = 1;
    perform pg_temp.act(A); perform public.submit_answers(rd.id, '{"hayvan":"Kedi","yemek":"Kurdumbuz","esya":"Kalem"}');
    perform pg_temp.act(B); perform public.submit_answers(rd.id, '{"hayvan":"Köpek","yemek":"Kurdumbuz","esya":"Kaşık"}');
    perform pg_temp.act(C); perform public.submit_answers(rd.id, '{"hayvan":"Koyun","yemek":"Köfte","esya":"Kova"}');
    select * into rd from public.rounds where id = rd.id;
    assert rd.phase = 'voting', 'Kurdumbuz is unknown -> vote, got ' || rd.phase;
    -- C accepts both Kurdumbuz answers, A and B accept each other's -> accepted 2:0
    for ans in select id, user_id from public.answers where round_id = rd.id and final_status is null loop
        perform pg_temp.act(C); perform public.cast_vote(ans.id, true);
        if ans.user_id = A then perform pg_temp.act(B); else perform pg_temp.act(A); end if;
        perform public.cast_vote(ans.id, true);
    end loop;
    assert (select times_seen from public.word_suggestions where category_key = 'yemek' and match_key = 'kurdumbuz') = 2, 'two votes recorded';
    assert (select times_accepted from public.word_suggestions where category_key = 'yemek' and match_key = 'kurdumbuz') = 2, 'two acceptances';
    perform public._record_vote_outcomes(rd.id);          -- idempotent
    assert (select times_seen from public.word_suggestions where category_key = 'yemek' and match_key = 'kurdumbuz') = 2, 'idempotent';
    assert (select count(*) from public.word_suggestion_queue where match_key = 'kurdumbuz') = 1, 'in review queue';

    -- finish round 1
    update public.rounds set reveal_deadline = now() - interval '1 second' where id = rd.id; perform public.game_tick(room);
    update public.rounds set results_deadline = now() - interval '1 second' where id = rd.id; perform public.game_tick(room);

    -- ROUND 2: B and C leave before answering closes -> nobody can vote
    select * into rd from public.rounds where match_id = m and round_number = 2;
    update public.rounds set letter = 'K', started_at = now() - interval '1 second' where id = rd.id;
    select * into rd from public.rounds where id = rd.id;
    perform pg_temp.act(A); perform public.submit_answers(rd.id, '{"hayvan":"Kzqwplm","yemek":"Kurdumbuz","esya":"Kalem"}');
    perform pg_temp.act(B); perform public.leave_room(room);
    perform pg_temp.act(C); perform public.leave_room(room);
    update public.round_players set deadline_at = now() - interval '5 seconds' where round_id = rd.id;
    perform pg_temp.act(A); perform public.game_tick(room);
    assert (select final_status || '/' || validation_reason from public.answers where round_id = rd.id and user_id = A and category_key = 'hayvan')
         = 'rejected/no_voters', 'made-up word with no voters is rejected';
    assert (select final_status || '/' || validation_reason from public.answers where round_id = rd.id and user_id = A and category_key = 'yemek')
         = 'accepted/community', 'word accepted by earlier votes is accepted via community evidence';
    assert (select final_status from public.answers where round_id = rd.id and user_id = A and category_key = 'esya') = 'accepted', 'dictionary words unaffected';

    -- curator turns the suggestion into a dictionary word (new version) -> now valid directly
    perform public._approve_word_suggestion((select id from public.word_suggestions where match_key = 'kurdumbuz'), 99, 'Kurdumbuz', 'test');
    assert (public._classify_answer('yemek', 'K', 'kurdumbuz')).validation = 'valid', 'approved suggestion is a dictionary word';
    -- leave the shared test database as we found it
    update public.dictionary_entries set retired_in = 99 where source like 'suggestion:%';
    raise notice 'dictionary fallback test OK';
end $$;
