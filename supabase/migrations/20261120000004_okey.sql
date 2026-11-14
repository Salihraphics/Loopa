-- =====================================================================
-- LOOPA 0.8.0 — 101 Okey (4 players)
--
-- 106 tiles: 4 colours (r, y, b, k) x 1..13 x 2, plus 2 false jokers (sahte okey).
-- An indicator tile is turned up; the okey is the same colour, one higher (13 -> 1). The two
-- okey tiles are wild; a false joker stands for the okey tile itself.
-- Starter gets 22 tiles, the others 21. A turn: draw (pile, or the previous player's discard),
-- then optionally open / lay / add, then discard one tile.
-- Opening: melds worth at least 101 points (runs: same colour, consecutive, >= 3, 12-13-1
-- allowed; groups: same number, different colours, 3-4), or at least 5 pairs.
-- After opening: lay further melds and add tiles to any meld on the table.
-- Taking the previous discard obliges you to use it this turn (else it goes back, +101).
-- Discarding a tile that fits a meld on the table: +101.
-- Round end: finisher -101 (-202 when finishing with the okey or opening and finishing in one
-- turn); not opened +202; opened: the tiles left in the hand (okey left in hand: 101).
-- Pair openers' penalties count double. Lowest total after the chosen rounds wins.
-- =====================================================================

insert into public.game_modes(id, name_tr, name_en, description_tr, status, always_free, min_players, max_players, sort_order, tags) values
 ('okey', '101 Okey', '101 Okey', 'Klasik 101: 101 puanla aç, taşlarını işle, elini ilk sen bitir.', 'available', false, 4, 4, 160, '{board,ffa}')
on conflict (id) do nothing;

create or replace function public._opts_okey(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
begin
    return jsonb_build_object('rounds', case when (o->>'rounds')::int in (1, 3, 5, 7) then (o->>'rounds')::int else 3 end,
                              'turn_seconds', case when (o->>'turn_seconds')::int in (30, 45, 60) then (o->>'turn_seconds')::int else 45 end);
end $$;

-- identity of a tile id ("r5a", "fj1") under a given okey {"c":..,"n":..}
create or replace function public._ok_tile(p_id text, okey jsonb)
returns jsonb language plpgsql immutable as $$
declare c text; n int;
begin
    if p_id like 'fj%' then return jsonb_build_object('id', p_id, 'c', okey->>'c', 'n', (okey->>'n')::int, 'wild', false); end if;
    c := left(p_id, 1); n := substring(p_id from 2 for length(p_id) - 2)::int;
    return jsonb_build_object('id', p_id, 'c', c, 'n', n, 'wild', c = okey->>'c' and n = (okey->>'n')::int);
end $$;

-- Validates a meld; returns {"kind","value","c"/"n","lo","hi"} or null.
create or replace function public._ok_meld(p_ids jsonb, okey jsonb)
returns jsonb language plpgsql immutable as $$
declare t jsonb; reals jsonb := '[]'::jsonb; wilds int := 0; len int := jsonb_array_length(p_ids); n int; cols text[]; nums int[];
        lo int; hi int; gaps int; extra int; up int; down int; v int; hi_ace boolean; i int; c text;
begin
    if len < 3 or len > 13 then return null; end if;
    for t in select public._ok_tile(x #>> '{}', okey) from jsonb_array_elements(p_ids) x loop
        if (t->>'wild')::boolean then wilds := wilds + 1; else reals := reals || jsonb_build_array(t); end if;
    end loop;
    if jsonb_array_length(reals) = 0 then return null; end if;
    -- group: one number, different colours, 3..4 tiles
    if len <= 4 and (select count(distinct x->>'n') from jsonb_array_elements(reals) x) = 1
       and (select count(distinct x->>'c') from jsonb_array_elements(reals) x) = jsonb_array_length(reals) then
        n := (reals->0->>'n')::int;
        return jsonb_build_object('kind', 'group', 'n', n, 'value', n * len, 'colors', (select jsonb_agg(distinct x->>'c') from jsonb_array_elements(reals) x));
    end if;
    -- run: one colour, distinct numbers, wilds fill gaps and ends. Ace low (1-2-3) or high (12-13-1), never wrapping.
    if (select count(distinct x->>'c') from jsonb_array_elements(reals) x) <> 1 then return null; end if;
    c := reals->0->>'c';
    foreach hi_ace in array array[false, true] loop
        nums := array(select case when hi_ace and (x->>'n')::int = 1 then 14 else (x->>'n')::int end from jsonb_array_elements(reals) x order by 1);
        if hi_ace and not (14 = any(nums)) then continue; end if;
        if (select count(distinct v2) from unnest(nums) v2) <> array_length(nums, 1) then return null; end if;
        lo := nums[1]; hi := nums[array_length(nums, 1)];
        gaps := (hi - lo + 1) - array_length(nums, 1);
        continue when gaps > wilds;
        extra := wilds - gaps;
        up := least(extra, case when hi_ace then 14 else 13 end - hi);       -- extend upwards first
        down := extra - up;
        continue when lo - down < case when hi_ace then 2 else 1 end;
        hi := hi + up; lo := lo - down;
        v := 0;
        for i in lo..hi loop v := v + case when i = 14 then 1 else i end; end loop;      -- an ace counts 1
        return jsonb_build_object('kind', 'run', 'c', c, 'lo', lo, 'hi', hi, 'value', v);
    end loop;
    return null;
end $$;

create or replace function public._ok_is_pair(a text, b text, okey jsonb)
returns boolean language plpgsql immutable as $$
declare x jsonb := public._ok_tile(a, okey); y jsonb := public._ok_tile(b, okey);
begin
    if a = b then return false; end if;
    if (x->>'wild')::boolean or (y->>'wild')::boolean then return true; end if;      -- an okey pairs with anything
    return x->>'c' = y->>'c' and x->>'n' = y->>'n';
end $$;

create or replace function public._ok_value(p_id text, okey jsonb)
returns int language sql immutable as $$
    select case when (public._ok_tile(p_id, okey)->>'wild')::boolean then 101 else (public._ok_tile(p_id, okey)->>'n')::int end;
$$;

-- Does a single tile fit some meld on the table ("işler taş")?
create or replace function public._ok_fits_table(s jsonb, p_id text)
returns boolean language plpgsql immutable as $$
declare m jsonb;
begin
    if (public._ok_tile(p_id, s->'okey')->>'wild')::boolean then return false; end if;
    for m in select value from jsonb_array_elements(coalesce(s->'table', '[]'::jsonb)) loop
        if m->>'kind' <> 'pair' and public._ok_meld((m->'ids') || to_jsonb(p_id), s->'okey') is not null then return true; end if;
    end loop;
    return false;
end $$;

create or replace function public._g_okey_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare rounds int := coalesce((public._tg_opt(m.id, 'rounds'))::int, 3); tiles jsonb := '[]'::jsonb; c text; k int; cp text; ind jsonb; okey jsonb;
        hands jsonb := '{}'::jsonb; i int; pos int := 0; starter int; cnt int; secs int := public._tg_secs(m.id, 45); ind_id text;
begin
    if n > rounds or array_length(players, 1) < 4 then return null; end if;
    foreach c in array array['r','y','b','k'] loop
        for k in 1..13 loop foreach cp in array array['a','b'] loop tiles := tiles || to_jsonb(c || k || cp); end loop; end loop;
    end loop;
    tiles := public._tg_shuffle(tiles || '["fj1","fj2"]'::jsonb);
    -- indicator: first real tile
    k := 0;
    while (tiles->>k) like 'fj%' loop k := k + 1; end loop;
    ind_id := tiles->>k; tiles := tiles - k;
    ind := public._ok_tile(ind_id, '{"c":"-","n":0}');
    okey := jsonb_build_object('c', ind->>'c', 'n', case when (ind->>'n')::int = 13 then 1 else (ind->>'n')::int + 1 end);
    starter := ((n - 1) % 4) + 1;
    for i in 1..4 loop
        cnt := case when i = starter then 22 else 21 end;
        hands := hands || jsonb_build_object(players[i]::text, (select jsonb_agg(x order by o) from jsonb_array_elements(tiles) with ordinality t(x, o) where o > pos and o <= pos + cnt));
        pos := pos + cnt;
    end loop;
    tiles := (select coalesce(jsonb_agg(x order by o), '[]'::jsonb) from jsonb_array_elements(tiles) with ordinality t(x, o) where o > pos);
    return jsonb_build_object('phase', 'play', 'round', n, 'rounds', rounds, 'order', to_jsonb(players[1:4]),
        'indicator', ind_id, 'okey', okey, 'hands', hands, 'deck', tiles, 'discards', '{}'::jsonb, 'table', '[]'::jsonb,
        'opened', '{}'::jsonb, 'penalty', '{}'::jsonb, 'turn', players[starter], 'stage', 'discard', 'took', null,
        'opened_now', false, 'secs', secs, 'deadline', public._tg_deadline(secs), 'timeouts', '{}'::jsonb);
end $$;

create or replace function public._ok_take_from_hand(s jsonb, me text, ids jsonb)
returns jsonb language plpgsql immutable as $$
declare hand jsonb := s->'hands'->me; x text; idx int;
begin
    for x in select jsonb_array_elements_text(ids) loop
        idx := (select o - 1 from jsonb_array_elements_text(hand) with ordinality t(v, o) where v = x limit 1);
        if idx is null then raise exception 'LOOPA:TILE_NOT_IN_HAND'; end if;
        hand := hand - idx::int;
    end loop;
    return jsonb_set(s, array['hands', me], hand);
end $$;

create or replace function public._ok_round_end(s jsonb, p_finisher text, p_double boolean)
returns jsonb language plpgsql immutable as $$
declare k text; pen int; pts jsonb := '{}'::jsonb; stats jsonb := '{}'::jsonb; x text;
begin
    for k in select jsonb_array_elements_text(s->'order') loop
        if k = p_finisher then
            pen := case when p_double then -202 else -101 end;
        elsif not (s->'opened' ? k) then
            pen := 202;
        else
            pen := 0;
            for x in select jsonb_array_elements_text(s->'hands'->k) loop pen := pen + public._ok_value(x, s->'okey'); end loop;
        end if;
        if s->'opened'->>k = 'pairs' and pen > 0 then pen := pen * 2; end if;
        pen := pen + coalesce((s->'penalty'->>k)::int, 0);
        pts := pts || jsonb_build_object(k, -pen);
        stats := stats || jsonb_build_object(k, jsonb_build_object('rounds', 1, 'rounds_won', case when k = p_finisher then 1 else 0 end,
            'openings', case when s->'opened' ? k then 1 else 0 end, 'pair_openings', case when s->'opened'->>k = 'pairs' then 1 else 0 end));
    end loop;
    return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'winner', p_finisher, 'points', pts, 'round_scores', pts, 'stats', stats);
end $$;

create or replace function public._ok_next_turn(s jsonb)
returns jsonb language plpgsql volatile as $$
begin
    if jsonb_array_length(s->'deck') = 0 then return public._ok_round_end(s, null, false); end if;   -- pile exhausted
    return s || jsonb_build_object('turn', public._tg_next_player(s->'order', (s->>'turn')::uuid), 'stage', 'draw', 'took', null,
                                   'opened_now', false, 'deadline', public._tg_deadline((s->>'secs')::int));
end $$;

-- kinds: draw {"from":"deck"|"discard"}, open {"melds":[[ids]]} | {"pairs":[[a,b]]}, lay {"melds":[[ids]]} | {"pairs":[[a,b]]},
--        add {"meld": index, "tile": id}, return_taken, discard {"tile": id}
create or replace function public._g_okey_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; prev text; tile text; total int := 0; mm jsonb; meld jsonb; ids jsonb; pairs int := 0; i int; tbl jsonb; target jsonb;
begin
    if s->>'turn' is distinct from me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    if p_kind = 'draw' then
        if s->>'stage' <> 'draw' then raise exception 'LOOPA:INVALID_ACTION'; end if;
        if p->>'from' = 'discard' then
            prev := (select v from jsonb_array_elements_text(s->'order') with ordinality t(v, o)
                      where o = ((select o2 from jsonb_array_elements_text(s->'order') with ordinality t2(v2, o2) where v2 = me) + 2) % 4 + 1);
            if coalesce(jsonb_array_length(s->'discards'->prev), 0) = 0 then raise exception 'LOOPA:INVALID_ACTION'; end if;
            tile := s->'discards'->prev->>-1;
            s := jsonb_set(s, array['discards', prev], (s->'discards'->prev) - -1);
            s := jsonb_set(s, array['hands', me], (s->'hands'->me) || to_jsonb(tile));
            s := s || jsonb_build_object('stage', 'discard', 'took', tile);
        else
            tile := s->'deck'->>0;
            s := jsonb_set(s, '{deck}', (s->'deck') - 0);
            s := jsonb_set(s, array['hands', me], (s->'hands'->me) || to_jsonb(tile));
            s := s || jsonb_build_object('stage', 'discard', 'took', null, 'last_drawn', tile);
        end if;
        return public._tg_log(s, jsonb_build_object('u', me, 'drew', p->>'from'));
    end if;
    if s->>'stage' <> 'discard' then raise exception 'LOOPA:MUST_DRAW'; end if;

    if p_kind = 'return_taken' then
        if s->>'took' is null then raise exception 'LOOPA:INVALID_ACTION'; end if;
        prev := (select v from jsonb_array_elements_text(s->'order') with ordinality t(v, o)
                  where o = ((select o2 from jsonb_array_elements_text(s->'order') with ordinality t2(v2, o2) where v2 = me) + 2) % 4 + 1);
        s := public._ok_take_from_hand(s, me, jsonb_build_array(s->>'took'));
        s := jsonb_set(s, array['discards', prev], coalesce(s->'discards'->prev, '[]'::jsonb) || to_jsonb(s->>'took'));
        s := jsonb_set(s, array['penalty', me], to_jsonb(coalesce((s->'penalty'->>me)::int, 0) + 101));
        tile := s->'deck'->>0;
        s := jsonb_set(s, '{deck}', (s->'deck') - 0);
        s := jsonb_set(s, array['hands', me], (s->'hands'->me) || to_jsonb(tile));
        return public._tg_log(s || jsonb_build_object('took', null), jsonb_build_object('u', me, 'returned', true, 'penalty', 101));
    end if;

    if p_kind in ('open', 'lay') then
        if p_kind = 'open' and s->'opened' ? me then raise exception 'LOOPA:ALREADY_OPENED'; end if;
        if p_kind = 'lay' and not (s->'opened' ? me) then raise exception 'LOOPA:NOT_OPENED'; end if;
        tbl := s->'table';
        if p ? 'pairs' then
            if p_kind = 'lay' and s->'opened'->>me <> 'pairs' then raise exception 'LOOPA:INVALID_ACTION'; end if;
            for mm in select value from jsonb_array_elements(p->'pairs') loop
                if jsonb_array_length(mm) <> 2 or not public._ok_is_pair(mm->>0, mm->>1, s->'okey') then raise exception 'LOOPA:INVALID_MELD'; end if;
                s := public._ok_take_from_hand(s, me, mm);
                tbl := tbl || jsonb_build_array(jsonb_build_object('owner', me, 'kind', 'pair', 'ids', mm));
                pairs := pairs + 1;
            end loop;
            if p_kind = 'open' and pairs < 5 then raise exception 'LOOPA:OPENING_TOO_LOW'; end if;
            if p_kind = 'open' then s := jsonb_set(s, array['opened', me], '"pairs"'); end if;
        else
            if p_kind = 'lay' and s->'opened'->>me = 'pairs' then raise exception 'LOOPA:INVALID_ACTION'; end if;
            for mm in select value from jsonb_array_elements(coalesce(p->'melds', '[]'::jsonb)) loop
                meld := public._ok_meld(mm, s->'okey');
                if meld is null then raise exception 'LOOPA:INVALID_MELD'; end if;
                s := public._ok_take_from_hand(s, me, mm);
                total := total + (meld->>'value')::int;
                tbl := tbl || jsonb_build_array(meld || jsonb_build_object('owner', me, 'ids', mm));
            end loop;
            if jsonb_array_length(coalesce(p->'melds', '[]'::jsonb)) = 0 then raise exception 'LOOPA:INVALID_MELD'; end if;
            if p_kind = 'open' and total < 101 then raise exception 'LOOPA:OPENING_TOO_LOW'; end if;
            if p_kind = 'open' then s := jsonb_set(s, array['opened', me], '"melds"'); end if;
        end if;
        s := jsonb_set(s, '{table}', tbl);
        if p_kind = 'open' then s := s || jsonb_build_object('opened_now', true); end if;
        -- the taken discard must have been used
        if s->>'took' is not null and not exists (select 1 from jsonb_array_elements_text(s->'hands'->me) v where v = s->>'took') then
            s := s || jsonb_build_object('took', null);
        end if;
        s := public._tg_log(s, jsonb_build_object('u', me, 'a', p_kind, 'value', total, 'pairs', pairs));
        if jsonb_array_length(s->'hands'->me) = 0 then return public._ok_round_end(s, me, coalesce((s->>'opened_now')::boolean, false)); end if;
        return s;
    end if;

    if p_kind = 'add' then
        if not (s->'opened' ? me) then raise exception 'LOOPA:NOT_OPENED'; end if;
        i := (p->>'meld')::int; tile := p->>'tile';
        target := s->'table'->i;
        if target is null or target->>'kind' = 'pair' then raise exception 'LOOPA:INVALID_MELD'; end if;
        meld := public._ok_meld((target->'ids') || to_jsonb(tile), s->'okey');
        if meld is null then raise exception 'LOOPA:INVALID_MELD'; end if;
        s := public._ok_take_from_hand(s, me, jsonb_build_array(tile));
        s := jsonb_set(s, array['table', i::text], meld || jsonb_build_object('owner', target->>'owner', 'ids', (target->'ids') || to_jsonb(tile)));
        if s->>'took' = tile then s := s || jsonb_build_object('took', null); end if;
        s := public._tg_log(s, jsonb_build_object('u', me, 'added', tile, 'to', i));
        if jsonb_array_length(s->'hands'->me) = 0 then return public._ok_round_end(s, me, coalesce((s->>'opened_now')::boolean, false)); end if;
        return s;
    end if;

    if p_kind = 'discard' then
        tile := p->>'tile';
        if s->>'took' is not null then raise exception 'LOOPA:MUST_USE_TAKEN_TILE'; end if;
        s := public._ok_take_from_hand(s, me, jsonb_build_array(tile));
        if public._ok_fits_table(s, tile) then
            s := jsonb_set(s, array['penalty', me], to_jsonb(coalesce((s->'penalty'->>me)::int, 0) + 101));
            s := public._tg_log(s, jsonb_build_object('u', me, 'penalty', 101, 'reason', 'isler'));
        end if;
        s := jsonb_set(s, array['discards', me], coalesce(s->'discards'->me, '[]'::jsonb) || to_jsonb(tile));
        s := public._tg_log(s, jsonb_build_object('u', me, 'discard', tile));
        if jsonb_array_length(s->'hands'->me) = 0 then
            if not (s->'opened' ? me) then raise exception 'LOOPA:NOT_OPENED'; end if;
            return public._ok_round_end(s, me, (public._ok_tile(tile, s->'okey')->>'wild')::boolean or coalesce((s->>'opened_now')::boolean, false));
        end if;
        return public._ok_next_turn(s);
    end if;
    raise exception 'LOOPA:INVALID_ACTION';
end $$;

create or replace function public._g_okey_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := s->>'turn'; u uuid; t text;
begin
    if me is null then return s; end if;
    u := me::uuid;
    s := public._tg_timeout_count(s, u);
    if s->>'stage' = 'draw' then s := public._g_okey_act(s, u, 'draw', '{"from":"deck"}'); end if;
    if s->>'took' is not null then s := public._g_okey_act(s, u, 'return_taken', '{}'); end if;
    -- discard the tile just drawn when possible, else the highest value tile
    t := coalesce(s->>'last_drawn', (select v from jsonb_array_elements_text(s->'hands'->me) v order by public._ok_value(v, s->'okey') desc limit 1));
    if not exists (select 1 from jsonb_array_elements_text(s->'hands'->me) v where v = t) then
        t := (select v from jsonb_array_elements_text(s->'hands'->me) v limit 1);
    end if;
    return public._g_okey_act(s - 'last_drawn', u, 'discard', jsonb_build_object('tile', t));
end $$;

create or replace function public._g_okey_public(s jsonb)
returns jsonb language sql immutable as $$
    select jsonb_build_object('round', s->'round', 'rounds', s->'rounds', 'order', s->'order', 'turn', s->'turn', 'stage', s->'stage',
        'indicator', s->'indicator', 'okey', s->'okey', 'deck_count', jsonb_array_length(s->'deck'),
        'discard_tops', (select coalesce(jsonb_object_agg(k, v->-1), '{}'::jsonb) from jsonb_each(s->'discards') x(k, v)),
        'counts', (select jsonb_object_agg(k, jsonb_array_length(v)) from jsonb_each(s->'hands') x(k, v)),
        'table', s->'table', 'opened', s->'opened', 'penalty', s->'penalty', 'winner', s->'winner', 'round_scores', s->'round_scores',
        'log', coalesce(s->'log', '[]'::jsonb),
        'hands', case when coalesce((s->>'over')::boolean, false) then s->'hands' end);
$$;

create or replace function public._g_okey_view(s jsonb, u uuid)
returns jsonb language sql immutable as $$
    select case when s->'hands' ? u::text then jsonb_build_object('hand', s->'hands'->u::text, 'took', case when s->>'turn' = u::text then s->'took' end)
                else '{}'::jsonb end;
$$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_ok\_%' or p.proname like '\_g\_okey\_%' or p.proname = '_opts_okey') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;
