-- One real round: different spellings of the same dictionary entry are duplicates,
-- wrong-category answers are rejected without a vote, reasons are exposed in the snapshot.
\set ON_ERROR_STOP 1
set client_min_messages = warning;
create or replace function pg_temp.act(p uuid) returns void language sql as $$ select set_config('request.jwt.claim.sub', p::text, false); $$;
insert into auth.users(id) values ('20000000-0000-0000-0000-00000000000a'), ('20000000-0000-0000-0000-00000000000b'), ('20000000-0000-0000-0000-00000000000c');
do $$
declare A uuid := '20000000-0000-0000-0000-00000000000a'; B uuid := '20000000-0000-0000-0000-00000000000b'; C uuid := '20000000-0000-0000-0000-00000000000c';
        v jsonb; room uuid; m uuid; rd public.rounds; s jsonb;
begin
    perform pg_temp.act(A); perform public.ensure_profile('Sözlük A');
    perform pg_temp.act(B); perform public.ensure_profile('Sözlük B');
    perform pg_temp.act(C); perform public.ensure_profile('Sözlük C');
    perform pg_temp.act(A);
    v := public.create_room(false); room := (v->>'id')::uuid;
    s := public._default_settings() || jsonb_build_object('rounds', 3, 'categories', jsonb_build_array(
        jsonb_build_object('key','ulke','custom',false), jsonb_build_object('key','sehir','custom',false),
        jsonb_build_object('key','hayvan','custom',false)));
    perform public.update_room_settings(room, s, false);
    perform pg_temp.act(B); perform public.join_room(v->>'code'); perform public.set_ready(room, true);
    perform pg_temp.act(C); perform public.join_room(v->>'code'); perform public.set_ready(room, true);
    perform pg_temp.act(A); m := public.start_match(room);
    -- force the letter A for a deterministic test
    update public.rounds set letter = 'A', started_at = now() - interval '1 second' where match_id = m;
    select * into rd from public.rounds where match_id = m;

    perform pg_temp.act(A); perform public.submit_answers(rd.id, '{"ulke":"ABD","sehir":"Antep","hayvan":"Aslan"}');
    perform pg_temp.act(B); perform public.submit_answers(rd.id, '{"ulke":"Amerika Birleşik Devletleri","sehir":"ANKARA","hayvan":"aslan"}');
    perform pg_temp.act(C); perform public.submit_answers(rd.id, '{"ulke":"amerika","sehir":"Arı","hayvan":"Ankara"}');

    select * into rd from public.rounds where id = rd.id;
    assert rd.phase = 'reveal', 'no vote needed, got ' || rd.phase;
    assert (select count(*) from public.answers where round_id = rd.id and category_key = 'ulke' and is_duplicate and points = 10) = 3,
        'ABD / Amerika Birleşik Devletleri / amerika are one answer (duplicates)';
    assert (select points from public.answers where round_id = rd.id and user_id = A and category_key = 'sehir') = 20, 'Antep unique';
    assert (select points from public.answers where round_id = rd.id and user_id = B and category_key = 'sehir') = 20, 'ANKARA unique';
    assert (select validation_reason from public.answers where round_id = rd.id and user_id = C and category_key = 'sehir') = 'wrong_category', 'Arı in Şehir';
    assert (select validation_reason from public.answers where round_id = rd.id and user_id = C and category_key = 'hayvan') = 'wrong_category', 'Ankara in Hayvan';
    assert (select count(*) from public.answers where round_id = rd.id and category_key = 'hayvan' and is_duplicate) = 2, 'Aslan/aslan duplicates';
    v := public.get_room_state(room);
    assert (select count(*) from jsonb_array_elements(v->'round'->'answers') x where x->>'reason' = 'wrong_category') = 2, 'reason in snapshot';
    assert (select count(distinct x->>'group_key') from jsonb_array_elements(v->'round'->'answers') x where x->>'category_key' = 'ulke') = 1, 'one group key';
    raise notice 'dictionary scoring test OK';
end $$;
