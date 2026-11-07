-- Full 10-player room: capacity limit, 10 simultaneous participants, one round to scoring.
\set ON_ERROR_STOP 1
set client_min_messages = warning;
create or replace function pg_temp.act(p uuid) returns void language sql as $$
    select set_config('request.jwt.claim.sub', p::text, false);
$$;

insert into auth.users(id) select ('10000000-0000-0000-0000-0000000000' || lpad(i::text, 2, '0'))::uuid from generate_series(1, 11) i;

do $$
declare
    u uuid[]; v jsonb; room uuid; code text; m uuid; rd public.rounds; i int; c jsonb; ans jsonb; err text;
begin
    select array_agg(id order by id) into u from auth.users where id::text like '10000000-%';
    for i in 1..11 loop
        perform pg_temp.act(u[i]); perform public.ensure_profile('Oyuncu ' || i);
    end loop;
    perform pg_temp.act(u[1]);
    v := public.create_room(false); room := (v->>'id')::uuid; code := v->>'code';
    for i in 2..10 loop
        perform pg_temp.act(u[i]); perform public.join_room(code); perform public.set_ready(room, true);
    end loop;
    -- 11th player is refused as a player ...
    perform pg_temp.act(u[11]);
    begin
        perform public.join_room(code);
        raise exception 'room should be full';
    exception when others then
        if sqlerrm not like '%ROOM_FULL%' then raise; end if;
    end;
    -- ... but may watch
    perform public.join_room(code, true);
    assert (select count(*) from public.room_players where room_id = room and status = 'active' and role = 'player') = 10, '10 players';

    perform pg_temp.act(u[1]);
    m := public.start_match(room);
    select * into rd from public.rounds where match_id = m;
    assert (select count(*) from public.round_players where round_id = rd.id) = 10, '10 participants';
    update public.rounds set started_at = now() - interval '1 second' where id = rd.id;

    for i in 1..10 loop
        perform pg_temp.act(u[i]);
        ans := '{}';
        for c in select * from jsonb_array_elements(rd.categories) loop
            ans := ans || jsonb_build_object(c->>'key', rd.letter || 'kelime' || (i % 3));
        end loop;
        perform public.submit_answers(rd.id, ans);
    end loop;
    select * into rd from public.rounds where id = rd.id;
    assert rd.phase = 'voting', 'all submitted -> voting immediately, got ' || rd.phase;
    assert (select count(*) from public.round_players where round_id = rd.id and early_bonus = 5) = 1, 'single early bonus among 10';
    -- 10 voters each accept all others' pending answers
    for i in 1..10 loop
        perform pg_temp.act(u[i]);
        perform public.cast_vote(a.id, true) from public.answers a where a.round_id = rd.id and a.final_status is null and a.user_id <> u[i];
    end loop;
    select * into rd from public.rounds where id = rd.id;
    assert rd.phase = 'reveal' and rd.scored, 'scored after all votes, got ' || rd.phase;
    -- groups of identical answers (i % 3) => everyone's answers are duplicates worth 10
    assert not exists (select 1 from public.answers where round_id = rd.id and points not in (10, 0)), 'duplicates score 10';
    assert (select count(*) from public.round_players where round_id = rd.id and is_round_winner) = 1, 'early bonus breaks the tie';
    raise notice 'ten players test OK';
end $$;
