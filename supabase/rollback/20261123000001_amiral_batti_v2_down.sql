-- Rollback of 20261123000001_amiral_batti_v2.sql: the 0.8.0 definitions (20261120000002_turn_games.sql).
-- Running games keep their extra keys (jokers, intel, starter), which the 0.8.0 code ignores.
begin;
create or replace function public._ab_begin_battle(s jsonb)
returns jsonb language plpgsql volatile as $$
declare first int := floor(random() * 2)::int;
begin
    return s || jsonb_build_object('phase', 'battle', 'shooting', first, 'turn', s->'teams'->first->>0,
        'shots_left', case when s->>'format' = '2v2' then 2 else 1 end, 'fired', '[]'::jsonb,
        'deadline', public._tg_deadline((s->>'secs')::int));
end $$;

create or replace function public._g_amiral_batti_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; side int := (s->'side_of'->>me)::int; target int; x int; y int; c text; res text; ship int; sh jsonb;
        all_hit boolean; i int; size int := (s->>'size')::int; fleet jsonb; won boolean; other_member text; pts jsonb; stats jsonb; mem text;
begin
    if side is null then raise exception 'LOOPA:NOT_A_PARTICIPANT'; end if;
    if p_kind = 'place' then
        if s->>'phase' <> 'placement' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
        if s->'fleets'->side <> 'null'::jsonb then raise exception 'LOOPA:ALREADY_SUBMITTED'; end if;
        fleet := case when coalesce((p->>'random')::boolean, false) then public._ab_random_fleet(size, array(select jsonb_array_elements_text(s->'ships')::int))
                      else p->'fleet' end;
        s := jsonb_set(s, array['cells', side::text], public._ab_cells(fleet, size, array(select jsonb_array_elements_text(s->'ships')::int)));
        s := jsonb_set(s, array['fleets', side::text], fleet);
        s := public._tg_log(s, jsonb_build_object('side', side, 'placed', true));
        if s->'fleets'->0 <> 'null'::jsonb and s->'fleets'->1 <> 'null'::jsonb then return public._ab_begin_battle(s); end if;
        return s;
    end if;
    if p_kind <> 'fire' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if s->>'phase' <> 'battle' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if (s->>'shooting')::int <> side then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    -- 2v2: each member fires one of the two shots, unless the teammate cannot
    if s->>'format' = '2v2' and (s->'fired' ? me) then
        other_member := (select v from jsonb_array_elements_text(s->'teams'->side) v where v <> me limit 1);
        if not coalesce((s->>'cover')::boolean, false) then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    end if;
    if s->>'format' = '1v1' and s->>'turn' <> me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    x := (p->>'x')::int; y := (p->>'y')::int;
    if x is null or y is null or x < 0 or y < 0 or x >= size or y >= size then raise exception 'LOOPA:INVALID_MOVE'; end if;
    target := 1 - side; c := x || ',' || y;
    if s->'shots'->target ? c then raise exception 'LOOPA:ALREADY_SHOT'; end if;
    if s->'cells'->target ? c then
        res := 'hit';
        ship := public._ab_ship_at(s->'fleets'->target, c);
        sh := s->'fleets'->target->ship;
        s := jsonb_set(s, array['shots', target::text, c], '"hit"'::jsonb);
        all_hit := true;
        for i in 0..(sh->>'len')::int - 1 loop
            if not (s->'shots'->target ? (((sh->>'x')::int + case when sh->>'dir' = 'h' then i else 0 end) || ',' || ((sh->>'y')::int + case when sh->>'dir' = 'v' then i else 0 end))) then
                all_hit := false;
            end if;
        end loop;
        if all_hit then
            res := 'sunk';
            s := jsonb_set(s, array['sunk', target::text], (s->'sunk'->target) || jsonb_build_array(sh));
        end if;
    else
        res := 'miss';
        s := jsonb_set(s, array['shots', target::text, c], '"miss"'::jsonb);
    end if;
    s := jsonb_set(s, array['stat_shots', me], to_jsonb(coalesce((s->'stat_shots'->>me)::int, 0) + 1), true);
    if res <> 'miss' then s := jsonb_set(s, array['stat_hits', me], to_jsonb(coalesce((s->'stat_hits'->>me)::int, 0) + 1), true); end if;
    s := public._tg_log(s, jsonb_build_object('u', me, 'x', x, 'y', y, 'r', res));
    won := jsonb_array_length(s->'sunk'->target) = jsonb_array_length(s->'ships');
    if won then
        pts := '{}'::jsonb; stats := '{}'::jsonb;
        for mem in select jsonb_array_elements_text(s->'teams'->side) loop
            pts := pts || jsonb_build_object(mem, 1000);
        end loop;
        for mem in select jsonb_object_keys(s->'side_of') loop
            stats := stats || jsonb_build_object(mem, jsonb_build_object('games', 1, 'wins', case when (s->'side_of'->>mem)::int = side then 1 else 0 end,
                'shots', coalesce((s->'stat_shots'->>mem)::int, 0), 'hits', coalesce((s->'stat_hits'->>mem)::int, 0)));
        end loop;
        return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'winner_side', side, 'points', pts, 'stats', stats);
    end if;
    s := jsonb_set(s, '{fired}', (s->'fired') || to_jsonb(me));
    s := s || jsonb_build_object('shots_left', (s->>'shots_left')::int - 1, 'cover', false);
    if (s->>'shots_left')::int > 0 then
        other_member := (select v from jsonb_array_elements_text(s->'teams'->side) v where v <> me limit 1);
        return s || jsonb_build_object('turn', coalesce(other_member, me), 'deadline', public._tg_deadline((s->>'secs')::int));
    end if;
    return s || jsonb_build_object('shooting', target, 'turn', s->'teams'->target->>0, 'fired', '[]'::jsonb,
        'shots_left', case when s->>'format' = '2v2' then 2 else 1 end, 'deadline', public._tg_deadline((s->>'secs')::int));
end $$;

create or replace function public._g_amiral_batti_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare side int; me text; c text; target int; x int; y int; size int := (s->>'size')::int;
begin
    if s->>'phase' = 'placement' then
        -- placement time is over: random fleets for whoever has not placed
        for side in 0..1 loop
            if s->'fleets'->side = 'null'::jsonb then
                s := public._g_amiral_batti_act(s, (s->'teams'->side->>0)::uuid, 'place', '{"random":true}');
            end if;
            exit when s->>'phase' <> 'placement';
        end loop;
        return s;
    end if;
    me := s->>'turn';
    s := public._tg_timeout_count(s, me::uuid);
    s := s || jsonb_build_object('cover', true);
    target := 1 - (s->>'shooting')::int;
    loop
        x := floor(random() * size)::int; y := floor(random() * size)::int;
        exit when not (s->'shots'->target ? (x || ',' || y));
    end loop;
    return public._g_amiral_batti_act(s, me::uuid, 'fire', jsonb_build_object('x', x, 'y', y));
end $$;

create or replace function public._g_amiral_batti_public(s jsonb)
returns jsonb language sql immutable as $$
    select jsonb_build_object('phase', s->'phase', 'format', s->'format', 'size', s->'size', 'ships', s->'ships', 'teams', s->'teams',
        'placed', jsonb_build_array(s->'fleets'->0 <> 'null'::jsonb, s->'fleets'->1 <> 'null'::jsonb),
        'shots', s->'shots', 'sunk', s->'sunk', 'shooting', s->'shooting', 'shots_left', s->'shots_left', 'turn', s->'turn',
        'winner_side', s->'winner_side', 'log', coalesce(s->'log', '[]'::jsonb),
        -- after the game everybody may see both fleets
        'fleets', case when coalesce((s->>'over')::boolean, false) then s->'fleets' end);
$$;

create or replace function public._g_amiral_batti_view(s jsonb, u uuid)
returns jsonb language sql immutable as $$
    select case when s->'side_of' ? u::text then
        jsonb_build_object('side', s->'side_of'->u::text, 'fleet', s->'fleets'->((s->'side_of'->>u::text)::int)) else '{}'::jsonb end;
$$;

drop function if exists public._ab_after(jsonb, int, boolean);
drop function if exists public._ab_shot(jsonb, text, int, text, text);
drop function if exists public._ab_hand(jsonb, int);
drop function if exists public._ab_grant(jsonb, text, text);
drop function if exists public._ab_jk_types();
drop function if exists public._ab_cell(jsonb, int);
drop function if exists public._ab_unhit_in(jsonb, int, text[]);
do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_ab\_%' or p.proname like '\_g\_amiral\_batti\_%') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;
delete from supabase_migrations.schema_migrations where version = '20261123000001';
commit;
