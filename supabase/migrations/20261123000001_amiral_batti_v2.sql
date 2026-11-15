-- Amiral Battı v2 (0.9.5): shoot again on a hit, and five one-use jokers. Approved by the owner.
--
-- Turn rule (changed, with approval):
--   * A hit (or sink) lets the same player fire again; a miss passes the turn to the other side.
--   * 1v1: the player keeps shooting while hitting.
--   * 2v2: one member of the team shoots per team turn and keeps shooting while hitting; the
--     team's members take turns starting the team's turns (state "starter").
--   * A timeout fires one random shot for the player whose turn it is (as before); a hit keeps
--     the turn, so the next deadline fires again.
-- Unchanged: board sizes, fleets, placement and its validation, hidden fleets, hit / miss / sunk,
-- defeat = whole fleet sunk, points and stats, placement timeout (random fleet).
--
-- Jokers (same rules as the main game's jokers):
--   * inventory of at most 3 per player; each joker is used once;
--   * every player gets 1 random joker when the battle starts;
--   * the player who sinks a ship gets 1 random joker if the inventory has room (none if full);
--   * everybody sees "X won a joker" (public log, no type); only the owner sees the type until it
--     is used (the use is public, its intel result is private to the user's team);
--   * at most one joker per player per turn (per run of consecutive shots).
--   Types:
--     sonar    {"x","y"}: is there an unhit ship part in the 3x3 area centred on x,y? (no shot used)
--     recon    {"axis":"row"|"col","i"}: is there an unhit ship part on that row / column? (no shot used)
--     double   {"cells":[[x,y],[x,y]]}: two different unshot cells fired at once (replaces the shot)
--     torpedo  {"axis":"row"|"col","i","rev"}: runs along the row / column from its start (or its end
--              when rev) and hits the first unhit ship part; open water it passes is not marked; a
--              miss if the line has none (replaces the shot)
--     salvo    {"x","y","dir":"h"|"v"}: three cells in a straight line, each judged on its own;
--              cells already shot are skipped, at least one must be new (replaces the shot)
--   After double / torpedo / salvo: any hit keeps the turn, otherwise the turn passes.
-- Every result comes from the server; the client only draws the confirmed events.

create or replace function public._ab_jk_types()
returns text[] language sql immutable as $$ select array['sonar','recon','double','torpedo','salvo']; $$;

-- Gives u one random joker if there is room; logs the award publicly without the type.
create or replace function public._ab_grant(s jsonb, u text, src text)
returns jsonb language plpgsql volatile as $$
declare inv jsonb := coalesce(s->'jokers'->u, '[]'::jsonb); nid int := coalesce((s->>'jk_next')::int, 1);
        types text[] := public._ab_jk_types();
begin
    if jsonb_array_length(inv) >= 3 then
        return public._tg_log(s, jsonb_build_object('e', 'joker_full', 'u', u, 'src', src));
    end if;
    inv := inv || jsonb_build_array(jsonb_build_object('id', nid, 't', types[1 + floor(random() * array_length(types, 1))::int]));
    s := jsonb_set(s, '{jokers}', coalesce(s->'jokers', '{}'::jsonb) || jsonb_build_object(u, inv));
    s := s || jsonb_build_object('jk_next', nid + 1);
    return public._tg_log(s, jsonb_build_object('e', 'joker', 'u', u, 'src', src));
end $$;

-- Battle start: first side at random, its first member starts; every player gets one joker.
create or replace function public._ab_begin_battle(s jsonb)
returns jsonb language plpgsql volatile as $$
declare first int := floor(random() * 2)::int; u text; starter jsonb;
begin
    starter := case when first = 0 then '[1,0]'::jsonb else '[0,1]'::jsonb end;
    s := s || jsonb_build_object('phase', 'battle', 'shooting', first, 'turn', s->'teams'->first->>0,
        'shots_left', 1, 'fired', '[]'::jsonb, 'starter', starter, 'jk_turn', null,
        'jokers', coalesce(s->'jokers', '{}'::jsonb), 'intel', coalesce(s->'intel', '[[],[]]'::jsonb),
        'deadline', public._tg_deadline((s->>'secs')::int));
    for u in select jsonb_object_keys(s->'side_of') order by 1 loop
        s := public._ab_grant(s, u, 'start');
    end loop;
    return s;
end $$;

-- The turn passes to side t: its next starting member, a fresh deadline, the joker limit resets.
create or replace function public._ab_hand(s jsonb, t int)
returns jsonb language plpgsql volatile as $$
declare st jsonb := coalesce(s->'starter', '[0,0]'::jsonb); k int; members int;
begin
    members := jsonb_array_length(s->'teams'->t);
    k := least(coalesce((st->>t)::int, 0), members - 1);
    st := jsonb_set(st, array[t::text], to_jsonb((k + 1) % members));
    return s || jsonb_build_object('shooting', t, 'turn', s->'teams'->t->>k, 'starter', st, 'jk_turn', null,
        'shots_left', 1, 'fired', '[]'::jsonb, 'cover', false, 'deadline', public._tg_deadline((s->>'secs')::int));
end $$;

-- One shot by me (side) at cell c of the other side. Result in s->>'_r' (hit / miss / sunk); the
-- game is over when s->>'over'. [via] marks shots fired through a joker in the public log.
create or replace function public._ab_shot(s jsonb, me text, side int, c text, via text)
returns jsonb language plpgsql volatile as $$
declare target int := 1 - side; res text; ship int; sh jsonb; all_hit boolean; i int; pts jsonb; stats jsonb; mem text;
        cx int := split_part(c, ',', 1)::int; cy int := split_part(c, ',', 2)::int;
begin
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
    s := public._tg_log(s, jsonb_build_object('u', me, 'x', cx, 'y', cy, 'r', res) || case when via is null then '{}'::jsonb else jsonb_build_object('via', via) end);
    if jsonb_array_length(s->'sunk'->target) = jsonb_array_length(s->'ships') then
        pts := '{}'::jsonb; stats := '{}'::jsonb;
        for mem in select jsonb_array_elements_text(s->'teams'->side) loop pts := pts || jsonb_build_object(mem, 1000); end loop;
        for mem in select jsonb_object_keys(s->'side_of') loop
            stats := stats || jsonb_build_object(mem, jsonb_build_object('games', 1, 'wins', case when (s->'side_of'->>mem)::int = side then 1 else 0 end,
                'shots', coalesce((s->'stat_shots'->>mem)::int, 0), 'hits', coalesce((s->'stat_hits'->>mem)::int, 0)));
        end loop;
        return s || jsonb_build_object('_r', res, 'over', true, 'phase', 'done', 'turn', null, 'winner_side', side, 'points', pts, 'stats', stats);
    end if;
    if res = 'sunk' then s := public._ab_grant(s, me, 'sunk'); end if;
    return s || jsonb_build_object('_r', res);
end $$;

-- After a shot (or a shooting joker): keep the turn on a hit, otherwise hand it over.
create or replace function public._ab_after(s jsonb, side int, any_hit boolean)
returns jsonb language plpgsql volatile as $$
begin
    s := s - '_r';
    if coalesce((s->>'over')::boolean, false) then return s; end if;
    if any_hit then return s || jsonb_build_object('deadline', public._tg_deadline((s->>'secs')::int)); end if;
    return public._ab_hand(s, 1 - side);
end $$;

create or replace function public._ab_cell(p jsonb, size int)
returns text language plpgsql immutable as $$
declare x int; y int;
begin
    begin
        x := (p->>0)::int; y := (p->>1)::int;
    exception when others then raise exception 'LOOPA:INVALID_MOVE';
    end;
    if x is null or y is null or x < 0 or y < 0 or x >= size or y >= size then raise exception 'LOOPA:INVALID_MOVE'; end if;
    return x || ',' || y;
end $$;

-- Is there a ship part of [target] in [area] that has not been hit yet?
create or replace function public._ab_unhit_in(s jsonb, target int, area text[])
returns boolean language sql immutable as $$
    select exists (select 1 from unnest(area) c where s->'cells'->target ? c and not (s->'shots'->target ? c));
$$;

create or replace function public._g_amiral_batti_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; side int := (s->'side_of'->>me)::int; target int; c text; c2 text; size int := (s->>'size')::int; fleet jsonb;
        inv jsonb; jk jsonb; jt text; area text[]; i int; j int; ax text; li int; hit boolean := false; found boolean; cx int; cy int; d text;
        cnt int := 0;
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
    if p_kind not in ('fire', 'joker') then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if s->>'phase' <> 'battle' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if (s->>'shooting')::int <> side or s->>'turn' is distinct from me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    target := 1 - side;

    if p_kind = 'fire' then
        c := public._ab_cell(jsonb_build_array(p->'x', p->'y'), size);
        if s->'shots'->target ? c then raise exception 'LOOPA:ALREADY_SHOT'; end if;
        s := public._ab_shot(s, me, side, c, null);
        return public._ab_after(s, side, s->>'_r' <> 'miss');
    end if;

    -- joker
    if s->>'jk_turn' = me then raise exception 'LOOPA:JOKER_LIMIT'; end if;
    inv := coalesce(s->'jokers'->me, '[]'::jsonb);
    select value into jk from jsonb_array_elements(inv) where value->>'id' = p->>'id' limit 1;
    if jk is null then raise exception 'LOOPA:JOKER_NOT_AVAILABLE'; end if;
    jt := jk->>'t';
    -- validate the target first, then spend the joker (an invalid target keeps it)
    if jt = 'sonar' then
        c := public._ab_cell(jsonb_build_array(p->'x', p->'y'), size);
        cx := split_part(c, ',', 1)::int; cy := split_part(c, ',', 2)::int;
        area := array(select (cx + dx) || ',' || (cy + dy) from generate_series(-1, 1) dx, generate_series(-1, 1) dy
                       where cx + dx between 0 and size - 1 and cy + dy between 0 and size - 1);
    elsif jt in ('recon', 'torpedo') then
        ax := p->>'axis'; li := case when (p->>'i') ~ '^\d+$' then (p->>'i')::int end;
        if ax not in ('row', 'col') or li is null or li >= size then raise exception 'LOOPA:INVALID_MOVE'; end if;
        area := array(select case when ax = 'row' then g || ',' || li else li || ',' || g end
                        from generate_series(0, size - 1) g
                       order by case when coalesce((p->>'rev')::boolean, false) then -g else g end);
    elsif jt = 'double' then
        if jsonb_typeof(p->'cells') <> 'array' or jsonb_array_length(p->'cells') <> 2 then raise exception 'LOOPA:INVALID_MOVE'; end if;
        c := public._ab_cell(p->'cells'->0, size); c2 := public._ab_cell(p->'cells'->1, size);
        if c = c2 then raise exception 'LOOPA:INVALID_MOVE'; end if;
        if s->'shots'->target ? c or s->'shots'->target ? c2 then raise exception 'LOOPA:ALREADY_SHOT'; end if;
    elsif jt = 'salvo' then
        c := public._ab_cell(jsonb_build_array(p->'x', p->'y'), size);
        d := p->>'dir'; cx := split_part(c, ',', 1)::int; cy := split_part(c, ',', 2)::int;
        if d not in ('h', 'v') then raise exception 'LOOPA:INVALID_MOVE'; end if;
        area := array(select (cx + case when d = 'h' then g else 0 end) || ',' || (cy + case when d = 'v' then g else 0 end) from generate_series(0, 2) g);
        if (d = 'h' and cx + 2 >= size) or (d = 'v' and cy + 2 >= size) then raise exception 'LOOPA:INVALID_MOVE'; end if;
        if not exists (select 1 from unnest(area) a where not (s->'shots'->target ? a)) then raise exception 'LOOPA:ALREADY_SHOT'; end if;
    else
        raise exception 'LOOPA:INVALID_ACTION';
    end if;
    s := jsonb_set(s, array['jokers', me], coalesce((select jsonb_agg(v) from jsonb_array_elements(inv) v where v->>'id' <> jk->>'id'), '[]'::jsonb));
    s := s || jsonb_build_object('jk_turn', me);

    if jt in ('sonar', 'recon') then
        found := public._ab_unhit_in(s, target, area);
        s := jsonb_set(s, array['intel', side::text], coalesce(s->'intel'->side, '[]'::jsonb) || jsonb_build_array(
            jsonb_build_object('t', jt, 'by', me, 'found', found, 'turn_shots', jsonb_array_length(jsonb_path_query_array(s->'shots'->target, '$.*')))
            || case when jt = 'sonar' then jsonb_build_object('x', cx, 'y', cy) else jsonb_build_object('axis', ax, 'i', li) end));
        s := public._tg_log(s, jsonb_build_object('e', 'jk', 'u', me, 't', jt)
            || case when jt = 'sonar' then jsonb_build_object('x', cx, 'y', cy) else jsonb_build_object('axis', ax, 'i', li) end);
        -- an information joker does not use the shot
        return s || jsonb_build_object('deadline', public._tg_deadline((s->>'secs')::int));
    end if;

    if jt = 'double' then
        s := public._tg_log(s, jsonb_build_object('e', 'jk', 'u', me, 't', jt));
        foreach c in array array[c, c2] loop
            s := public._ab_shot(s, me, side, c, jt);
            hit := hit or s->>'_r' <> 'miss';
            if coalesce((s->>'over')::boolean, false) then return public._ab_after(s, side, true); end if;
        end loop;
        return public._ab_after(s, side, hit);
    end if;

    if jt = 'torpedo' then
        c := (select a from unnest(area) with ordinality z(a, o) where s->'cells'->target ? a and not (s->'shots'->target ? a) order by o limit 1);
        s := public._tg_log(s, jsonb_build_object('e', 'jk', 'u', me, 't', jt, 'axis', ax, 'i', li, 'rev', coalesce((p->>'rev')::boolean, false),
                 'stop', case when c is null then null else to_jsonb(c) end));
        if c is null then
            -- the torpedo runs off the map: a miss, no cell marked
            return public._ab_after(s, side, false);
        end if;
        s := public._ab_shot(s, me, side, c, jt);
        return public._ab_after(s, side, true);
    end if;

    -- salvo
    s := public._tg_log(s, jsonb_build_object('e', 'jk', 'u', me, 't', jt, 'x', cx, 'y', cy, 'dir', d));
    foreach c in array area loop
        continue when s->'shots'->target ? c;
        s := public._ab_shot(s, me, side, c, jt);
        hit := hit or s->>'_r' <> 'miss';
        if coalesce((s->>'over')::boolean, false) then return public._ab_after(s, side, true); end if;
    end loop;
    return public._ab_after(s, side, hit);
end $$;

-- Timeouts: placement as before; in battle one random shot for the player whose turn it is.
create or replace function public._g_amiral_batti_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare side int; me text; target int; x int; y int; size int := (s->>'size')::int;
begin
    if s->>'phase' = 'placement' then
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
    target := 1 - (s->>'shooting')::int;
    loop
        x := floor(random() * size)::int; y := floor(random() * size)::int;
        exit when not (s->'shots'->target ? (x || ',' || y));
    end loop;
    return public._g_amiral_batti_act(s, me::uuid, 'fire', jsonb_build_object('x', x, 'y', y));
end $$;

-- Everybody: as before plus how many jokers each player holds (never which ones).
create or replace function public._g_amiral_batti_public(s jsonb)
returns jsonb language sql immutable as $$
    select jsonb_build_object('phase', s->'phase', 'format', s->'format', 'size', s->'size', 'ships', s->'ships', 'teams', s->'teams',
        'placed', jsonb_build_array(s->'fleets'->0 <> 'null'::jsonb, s->'fleets'->1 <> 'null'::jsonb),
        'shots', s->'shots', 'sunk', s->'sunk', 'shooting', s->'shooting', 'shots_left', s->'shots_left', 'turn', s->'turn',
        'winner_side', s->'winner_side', 'log', coalesce(s->'log', '[]'::jsonb),
        'jk_count', coalesce((select jsonb_object_agg(key, jsonb_array_length(value)) from jsonb_each(coalesce(s->'jokers', '{}'::jsonb))), '{}'::jsonb),
        'jk_turn', s->'jk_turn',
        'fleets', case when coalesce((s->>'over')::boolean, false) then s->'fleets' end);
$$;

-- A player: own side, the team's fleet, own jokers (with types) and the team's sonar / recon results.
create or replace function public._g_amiral_batti_view(s jsonb, u uuid)
returns jsonb language sql immutable as $$
    select case when s->'side_of' ? u::text then
        jsonb_build_object('side', s->'side_of'->u::text, 'fleet', s->'fleets'->((s->'side_of'->>u::text)::int),
            'jokers', coalesce(s->'jokers'->u::text, '[]'::jsonb),
            'intel', coalesce(s->'intel'->((s->'side_of'->>u::text)::int), '[]'::jsonb)) else '{}'::jsonb end;
$$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_ab\_%' or p.proname like '\_g\_amiral\_batti\_%') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;
