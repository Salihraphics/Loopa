-- =====================================================================
-- LOOPA — Buckshot (2-4 players, free for all, 3 rounds)
-- Source: the Buckshot integration spec + the owner's decisions (docs/design/BUCKSHOT_NOTES.md).
--
-- One shared shotgun. A magazine holds 8 shells, live or blank (at least one of each; the
-- live/blank counts are announced, the order is secret). On your turn: use any number of
-- items, then fire at yourself or a living opponent. A live shell does 1 damage (2 with the
-- saw). A blank at yourself keeps your turn. Elimination only removes you from the round.
-- The last player alive wins the round (a "para çantası": coins, server-side, once).
-- Round HP 3 / 4 / 5. Items carry over between rounds (capacity 8): 4 new ones per round,
-- 2 more per player alive whenever a magazine runs out and the round goes on.
-- Most round wins after 3 rounds wins the match (tie-break: the tied player who won round 3).
--
-- The shell order never leaves the server: clients get counts, what they themselves learnt
-- (magnifier / phone) and what was fired. Every action carries a nonce: a replayed request
-- is refused, so it cannot fire, heal or consume twice. No betting of any kind.
-- =====================================================================

insert into public.game_modes(id, name_tr, name_en, description_tr, status, always_free, min_players, max_players, sort_order, tags) values
 ('buckshot', 'Buckshot', 'Buckshot', 'Tek tüfek, gizli mermiler. Eşyalarını kullan, olasılığı hesapla, ayakta kalan son kişi ol.',
  'available', false, 2, 4, 200, '{duel,ffa,strategy}')
on conflict (id) do nothing;

-- rewards: the class used by the normal match reward, plus the per-round money bag
update public.economy_config set value = value || '{"buckshot": "ffa"}'::jsonb
 where key = 'game_reward_class' and not (value ? 'buckshot');
insert into public.economy_config(key, value) values
 ('buckshot', '{"round_bag_coins": 100, "bag_daily_cap": 15, "item_capacity": 8, "round_items": 4, "reload_items": 2}')
on conflict (key) do nothing;

-- the turn-game layer learns the new game
create or replace function public._tg_is(p_mode text)
returns boolean language sql immutable as $$
    select p_mode = any(array['tavla','yirmi_bir','kizma_birader','amiral_batti','renk_kartlari','okey','milyoner','kurt_adam','kralliklar','buckshot']);
$$;

create or replace function public._opts_buckshot(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
begin
    return jsonb_build_object('turn_seconds', case when (o->>'turn_seconds')::int in (20, 30, 45) then (o->>'turn_seconds')::int else 30 end);
end $$;

create or replace function public._startcheck_buckshot(p_settings jsonb, n int)
returns void language plpgsql immutable as $$
begin
    if n < 2 or n > 4 then raise exception 'LOOPA:WRONG_PLAYER_COUNT'; end if;
end $$;

create or replace function public._bs_cfg()
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select '{"round_bag_coins": 100, "bag_daily_cap": 15, "item_capacity": 8, "round_items": 4, "reload_items": 2}'::jsonb || public._cfg('buckshot');
$$;

-- Item pool: the remote (turn order reversal) only matters with 3+ players.
create or replace function public._bs_pool(n int)
returns text[] language sql immutable as $$
    select array['beer','cigarettes','magnifier','handcuffs','saw','inverter','phone','medicine']
           || case when n >= 3 then array['remote'] else '{}'::text[] end;
$$;

-- k independent random items, never above the capacity (the rest is lost).
create or replace function public._bs_deal(inv jsonb, k int, pool text[], cap int)
returns jsonb language plpgsql volatile as $$
declare i int;
begin
    inv := coalesce(inv, '[]'::jsonb);
    for i in 1..k loop
        exit when jsonb_array_length(inv) >= cap;
        inv := inv || to_jsonb(pool[1 + floor(random() * array_length(pool, 1))::int]);
    end loop;
    return inv;
end $$;

-- A fresh magazine: 8 shells, 1-7 live, shuffled.
create or replace function public._bs_magazine(p_no int)
returns jsonb language plpgsql volatile as $$
declare live int := 1 + floor(random() * 7)::int;
begin
    return jsonb_build_object('no', p_no, 'pos', 0, 'loaded_live', live, 'loaded_blank', 8 - live, 'live_seen', 0, 'blank_seen', 0, 'inverted', 0,
        'shells', (select jsonb_agg(v order by random()) from (select (g <= live) as v from generate_series(1, 8) g) x));
end $$;

create or replace function public._bs_alive(s jsonb)
returns text[] language sql immutable as $$
    select array(select x from jsonb_array_elements_text(s->'order') x where (s->'p'->x->>'alive')::boolean);
$$;

create or replace function public._bs_new_stats()
returns jsonb language sql immutable as $$
    select '{"rounds": 0, "rounds_won": 0, "shots": 0, "live_hits": 0, "self_blanks": 0, "items_used": 0, "eliminations": 0}'::jsonb;
$$;

create or replace function public._bs_stat(s jsonb, u text, k text, d int default 1)
returns jsonb language sql immutable as $$
    select jsonb_set(s, array['stats', u, k], to_jsonb(coalesce((s->'stats'->u->>k)::int, 0) + d), true);
$$;

create or replace function public._g_buckshot_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare ms jsonb := public._mz_state(m.id); ord jsonb; inv jsonb; p jsonb := '{}'::jsonb; u text; cfg jsonb := public._bs_cfg();
        hp int; pool text[]; secs int := public._tg_secs(m.id, 30); first text; present text[]; mag jsonb := public._bs_magazine(1);
begin
    if n > 3 or coalesce(array_length(players, 1), 0) < 2 then return null; end if;
    present := array(select x::text from unnest(players) x);
    -- seats are fixed for the whole match; the starter is drawn each round
    ord := coalesce(ms->'bs_order', (select jsonb_agg(x order by random()) from unnest(present) x));
    if ms->'bs_order' is null then perform public._mz_set(m.id, 'bs_order', ord); end if;
    ord := (select coalesce(jsonb_agg(x), '[]'::jsonb) from jsonb_array_elements_text(ord) x where x = any(present));
    if jsonb_array_length(ord) < 2 then return null; end if;
    hp := case n when 1 then 3 when 2 then 4 else 5 end;
    pool := public._bs_pool(jsonb_array_length(ord));
    for u in select jsonb_array_elements_text(ord) loop
        inv := public._bs_deal(coalesce(ms->'bs_inv'->u, '[]'::jsonb), (cfg->>'round_items')::int, pool, (cfg->>'item_capacity')::int);
        p := p || jsonb_build_object(u, jsonb_build_object('hp', hp, 'alive', true, 'inv', inv, 'cuffed', false, 'saw', false, 'nonces', '[]'::jsonb));
    end loop;
    first := ord->>(floor(random() * jsonb_array_length(ord))::int);
    return jsonb_build_object('phase', 'play', 'round', n, 'max_hp', hp, 'order', ord, 'dir', 1, 'p', p,
        'mag', mag, 'known', '{}'::jsonb, 'wins', coalesce(ms->'bs_wins', '{}'::jsonb),
        'turn', first, 'secs', secs, 'deadline', public._tg_deadline(secs), 'timeouts', '{}'::jsonb,
        'stats', (select jsonb_object_agg(x, public._bs_new_stats() || '{"rounds": 1}') from jsonb_array_elements_text(ord) x),
        'log', jsonb_build_array(jsonb_build_object('e', 'reload', 'live', mag->'loaded_live', 'blank', mag->'loaded_blank')));
end $$;

-- Next living player in the current direction, skipping (and releasing) handcuffed ones.
create or replace function public._bs_next(s jsonb, p_from text)
returns jsonb language plpgsql immutable as $$
declare n int := jsonb_array_length(s->'order'); idx int := 0; i int; k int; c text; dir int := (s->>'dir')::int; guard int := 0;
begin
    for i in 0..n - 1 loop if s->'order'->>i = p_from then idx := i; end if; end loop;
    k := idx;
    loop
        guard := guard + 1;
        exit when guard > 3 * n;
        k := ((k + dir) % n + n) % n;
        c := s->'order'->>k;
        continue when not (s->'p'->c->>'alive')::boolean;
        if (s->'p'->c->>'cuffed')::boolean then
            s := jsonb_set(s, array['p', c, 'cuffed'], 'false');
            s := public._tg_log(s, jsonb_build_object('e', 'skipped', 'u', c));
            continue;
        end if;
        return s || jsonb_build_object('turn', c);
    end loop;
    return s || jsonb_build_object('turn', p_from);
end $$;

-- Round over: points (10 per round, +1 tie-break in round 3), money bag once, items and wins kept.
create or replace function public._bs_end_round(s jsonb, w text)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare m uuid := (s->>'match')::uuid; wins jsonb; pts int := 10; top int; tied int; cfg jsonb := public._bs_cfg(); bag int := 0;
        today int; u text; inv jsonb := '{}'::jsonb;
begin
    wins := jsonb_set(coalesce(s->'wins', '{}'::jsonb), array[w], to_jsonb(coalesce((s->'wins'->>w)::int, 0) + 1));
    if (s->>'round')::int = 3 then
        select max((value#>>'{}')::int) into top from jsonb_each(wins);
        select count(*) into tied from jsonb_each(wins) where (value#>>'{}')::int = top;
        if tied > 1 and (wins->>w)::int = top then pts := pts + 1; end if;
    end if;
    -- the money bag: coins through the LOOPA ledger, idempotent per round, capped per day
    select count(*) into today from public.coin_transactions
     where user_id = w::uuid and source_key like 'buckshot_bag:%' and created_at >= public._day_start();
    if today < (cfg->>'bag_daily_cap')::int then
        bag := public._coins_grant(w::uuid, (cfg->>'round_bag_coins')::int, 'match', 'buckshot_bag:' || m || ':' || (s->>'round'));
    end if;
    for u in select jsonb_array_elements_text(s->'order') loop inv := inv || jsonb_build_object(u, s->'p'->u->'inv'); end loop;
    perform public._mz_set(m, 'bs_wins', wins);
    perform public._mz_set(m, 'bs_inv', (public._mz_state(m)->'bs_inv') || inv);
    s := public._bs_stat(s, w, 'rounds_won');
    s := public._tg_log(s, jsonb_build_object('e', 'round_won', 'u', w, 'bag', bag));
    return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'winner', w, 'wins', wins, 'bag', bag,
        'points', jsonb_build_object(w, pts));
end $$;

-- After a shot / item: round winner, empty magazine (reload + 2 items), whose turn.
create or replace function public._bs_settle(s jsonb, actor text, keep_turn boolean)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare alive text[] := public._bs_alive(s); u text; cfg jsonb := public._bs_cfg(); pool text[];
begin
    if array_length(alive, 1) = 1 then return public._bs_end_round(s, alive[1]); end if;
    if (s->'mag'->>'pos')::int >= 8 then
        pool := public._bs_pool(jsonb_array_length(s->'order'));
        s := s || jsonb_build_object('mag', public._bs_magazine((s->'mag'->>'no')::int + 1));
        foreach u in array alive loop
            s := jsonb_set(s, array['p', u, 'inv'], public._bs_deal(s->'p'->u->'inv', (cfg->>'reload_items')::int, pool, (cfg->>'item_capacity')::int));
        end loop;
        s := public._tg_log(s, jsonb_build_object('e', 'reload', 'live', s->'mag'->'loaded_live', 'blank', s->'mag'->'loaded_blank'));
    end if;
    if keep_turn and (s->'p'->actor->>'alive')::boolean then
        return s || jsonb_build_object('turn', actor, 'deadline', public._tg_deadline((s->>'secs')::int));
    end if;
    s := public._bs_next(s, actor);
    return s || jsonb_build_object('deadline', public._tg_deadline((s->>'secs')::int));
end $$;

create or replace function public._bs_kill_check(s jsonb, u text, by_u text)
returns jsonb language plpgsql immutable as $$
begin
    if (s->'p'->u->>'hp')::int <= 0 and (s->'p'->u->>'alive')::boolean then
        s := jsonb_set(jsonb_set(s, array['p', u, 'alive'], 'false'), array['p', u, 'hp'], '0');
        s := jsonb_set(s, array['p', u, 'cuffed'], 'false');
        if by_u <> u then s := public._bs_stat(s, by_u, 'eliminations'); end if;
        s := public._tg_log(s, jsonb_build_object('e', 'out', 'u', u));
    end if;
    return s;
end $$;

-- kinds: fire {"target": uid, "nonce": "..."} | item {"item": name, "target": uid?, "nonce": "..."}
create or replace function public._g_buckshot_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; tgt text := p->>'target'; nonce text := p->>'nonce'; it text; idx int; pos int; live boolean; dmg int;
        mag jsonb; cand int[]; pick int; hp int; maxhp int := (s->>'max_hp')::int; keep boolean := false; k text;
begin
    if not (s->'p' ? me) then raise exception 'LOOPA:NOT_A_PARTICIPANT'; end if;
    if s->>'turn' is distinct from me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    if not (s->'p'->me->>'alive')::boolean then raise exception 'LOOPA:ELIMINATED'; end if;
    if nonce is null or char_length(nonce) not between 8 and 64 then raise exception 'LOOPA:INVALID_INPUT'; end if;
    if s->'p'->me->'nonces' ? nonce then raise exception 'LOOPA:DUPLICATE_ACTION'; end if;
    s := jsonb_set(s, array['p', me, 'nonces'], (select coalesce(jsonb_agg(x), '[]'::jsonb) from (
             select x from jsonb_array_elements(s->'p'->me->'nonces' || to_jsonb(nonce)) x
             offset greatest(jsonb_array_length(s->'p'->me->'nonces') + 1 - 40, 0)) y));
    mag := s->'mag'; pos := (mag->>'pos')::int;

    if p_kind = 'fire' then
        if tgt is null or not (s->'p' ? tgt) or not (s->'p'->tgt->>'alive')::boolean then raise exception 'LOOPA:INVALID_TARGET'; end if;
        live := (mag->'shells'->>pos)::boolean;
        s := jsonb_set(s, '{mag,pos}', to_jsonb(pos + 1));
        s := jsonb_set(s, array['mag', case when live then 'live_seen' else 'blank_seen' end], to_jsonb((mag->>case when live then 'live_seen' else 'blank_seen' end)::int + 1));
        s := public._bs_stat(s, me, 'shots');
        dmg := 0;
        if live then
            dmg := case when (s->'p'->me->>'saw')::boolean then 2 else 1 end;
            s := jsonb_set(s, array['p', me, 'saw'], 'false');              -- the saw ends with the first live shell
            s := jsonb_set(s, array['p', tgt, 'hp'], to_jsonb((s->'p'->tgt->>'hp')::int - dmg));
            if tgt <> me then s := public._bs_stat(s, me, 'live_hits'); end if;
        elsif tgt = me then
            keep := true;
            s := public._bs_stat(s, me, 'self_blanks');
        end if;
        s := public._tg_log(s, jsonb_build_object('e', 'shot', 'u', me, 't', tgt, 'live', live, 'dmg', dmg, 'shell', pos + 1));
        s := public._bs_kill_check(s, tgt, me);
        return public._bs_settle(s, me, keep);
    end if;

    if p_kind <> 'item' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    it := p->>'item';
    idx := (select (o - 1)::int from jsonb_array_elements_text(s->'p'->me->'inv') with ordinality t(v, o) where v = it limit 1);
    if idx is null then raise exception 'LOOPA:ITEM_NOT_OWNED'; end if;
    hp := (s->'p'->me->>'hp')::int;
    -- refusals first: an item that cannot work is not consumed
    if it = 'cigarettes' and hp >= maxhp then raise exception 'LOOPA:ITEM_NOT_USABLE'; end if;
    if it = 'saw' and (s->'p'->me->>'saw')::boolean then raise exception 'LOOPA:ITEM_NOT_USABLE'; end if;
    if it = 'handcuffs' then
        if tgt is null or tgt = me or not (s->'p' ? tgt) or not (s->'p'->tgt->>'alive')::boolean then raise exception 'LOOPA:INVALID_TARGET'; end if;
        if (s->'p'->tgt->>'cuffed')::boolean then raise exception 'LOOPA:ITEM_NOT_USABLE'; end if;
    end if;
    s := jsonb_set(s, array['p', me, 'inv'], (s->'p'->me->'inv') - idx);
    s := public._bs_stat(s, me, 'items_used');
    case it
    when 'beer' then
        live := (mag->'shells'->>pos)::boolean;
        s := jsonb_set(s, '{mag,pos}', to_jsonb(pos + 1));
        s := jsonb_set(s, array['mag', case when live then 'live_seen' else 'blank_seen' end], to_jsonb((mag->>case when live then 'live_seen' else 'blank_seen' end)::int + 1));
        s := public._tg_log(s, jsonb_build_object('e', 'item', 'u', me, 'item', it, 'live', live, 'shell', pos + 1));
        return public._bs_settle(s, me, true);                               -- an ejected shell is no shot: same player
    when 'cigarettes' then
        s := jsonb_set(s, array['p', me, 'hp'], to_jsonb(hp + 1));
    when 'magnifier' then
        s := jsonb_set(s, array['known', me], coalesce(s->'known'->me, '[]'::jsonb)
                 || jsonb_build_array(jsonb_build_object('mag', mag->'no', 'idx', pos, 'live', (mag->'shells'->>pos)::boolean)), true);
    when 'handcuffs' then
        s := jsonb_set(s, array['p', tgt, 'cuffed'], 'true');
    when 'saw' then
        s := jsonb_set(s, array['p', me, 'saw'], 'true');
    when 'inverter' then
        s := jsonb_set(s, array['mag', 'shells', pos::text], to_jsonb(not (mag->'shells'->>pos)::boolean));
        s := jsonb_set(s, '{mag,inverted}', to_jsonb((mag->>'inverted')::int + 1));
        -- whoever knew this shell sees it flipped (the use itself is public)
        for k in select jsonb_object_keys(s->'known') loop
            s := jsonb_set(s, array['known', k], (select coalesce(jsonb_agg(case when (e->>'mag')::int = (mag->>'no')::int and (e->>'idx')::int = pos
                     then e || jsonb_build_object('live', not (e->>'live')::boolean) else e end), '[]'::jsonb) from jsonb_array_elements(s->'known'->k) e));
        end loop;
    when 'phone' then
        cand := array(select g from generate_series(pos + 1, 7) g);
        if coalesce(array_length(cand, 1), 0) = 0 then cand := array[pos]; end if;
        pick := cand[1 + floor(random() * array_length(cand, 1))::int];
        s := jsonb_set(s, array['known', me], coalesce(s->'known'->me, '[]'::jsonb)
                 || jsonb_build_array(jsonb_build_object('mag', mag->'no', 'idx', pick, 'live', (s->'mag'->'shells'->>pick)::boolean)), true);
    when 'medicine' then
        if random() < 0.5 then
            s := jsonb_set(s, array['p', me, 'hp'], to_jsonb(least(maxhp, hp + 2)));
            s := public._tg_log(s, jsonb_build_object('e', 'item', 'u', me, 'item', it, 'heal', true));
        else
            s := jsonb_set(s, array['p', me, 'hp'], to_jsonb(hp - 1));
            s := public._tg_log(s, jsonb_build_object('e', 'item', 'u', me, 'item', it, 'heal', false));
            s := public._bs_kill_check(s, me, me);
        end if;
        return public._bs_settle(s, me, true);                               -- dead: _bs_settle passes the turn
    when 'remote' then
        s := s || jsonb_build_object('dir', -(s->>'dir')::int);
    else
        raise exception 'LOOPA:INVALID_ACTION';
    end case;
    -- public log never carries what the magnifier or the phone revealed
    s := public._tg_log(s, jsonb_build_object('e', 'item', 'u', me, 'item', it, 't', case when it = 'handcuffs' then tgt end));
    return s || jsonb_build_object('deadline', public._tg_deadline((s->>'secs')::int));
end $$;

-- Time is up (or the player left): the server fires at a random living opponent.
create or replace function public._g_buckshot_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := s->>'turn'; tgt text;
begin
    if me is null or coalesce((s->>'over')::boolean, false) then return s; end if;
    s := public._tg_timeout_count(s, me::uuid);
    select x into tgt from unnest(public._bs_alive(s)) x where x <> me order by random() limit 1;
    if tgt is null then return s; end if;
    s := public._tg_log(s, jsonb_build_object('e', 'timeout', 'u', me));
    return public._g_buckshot_act(s, me::uuid, 'fire', jsonb_build_object('target', tgt, 'nonce', 'timeout-' || gen_random_uuid()));
end $$;

create or replace function public._g_buckshot_public(s jsonb)
returns jsonb language sql immutable as $$
    select jsonb_build_object('round', s->'round', 'max_hp', s->'max_hp', 'order', s->'order', 'dir', s->'dir', 'turn', s->'turn',
        'wins', s->'wins', 'winner', s->'winner', 'bag', s->'bag', 'log', coalesce(s->'log', '[]'::jsonb),
        -- everybody: hp, alive, how MANY items (not which), handcuffs and an active saw
        'players', (select jsonb_object_agg(k, jsonb_build_object('hp', v->'hp', 'alive', v->'alive', 'items', jsonb_array_length(v->'inv'),
                        'cuffed', v->'cuffed', 'saw', v->'saw')) from jsonb_each(s->'p') x(k, v)),
        -- the magazine: counts only, never the order
        'mag', jsonb_build_object('no', s->'mag'->'no', 'remaining', 8 - (s->'mag'->>'pos')::int,
               'loaded_live', s->'mag'->'loaded_live', 'loaded_blank', s->'mag'->'loaded_blank',
               'live_seen', s->'mag'->'live_seen', 'blank_seen', s->'mag'->'blank_seen', 'inverted', s->'mag'->'inverted'));
$$;

-- Only u: own items, and what u learnt about shells still in the current magazine.
create or replace function public._g_buckshot_view(s jsonb, u uuid)
returns jsonb language sql immutable as $$
    select case when s->'p' ? u::text then jsonb_build_object(
        'inv', s->'p'->u::text->'inv',
        'known', (select coalesce(jsonb_agg(jsonb_build_object('ahead', (e->>'idx')::int - (s->'mag'->>'pos')::int, 'shell', (e->>'idx')::int + 1, 'live', e->'live')
                        order by (e->>'idx')::int), '[]'::jsonb)
                    from jsonb_array_elements(coalesce(s->'known'->u::text, '[]'::jsonb)) e
                   where (e->>'mag')::int = (s->'mag'->>'no')::int and (e->>'idx')::int >= (s->'mag'->>'pos')::int))
        else '{}'::jsonb end;
$$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_bs\_%' or p.proname like '\_g\_buckshot\_%' or p.proname in ('_opts_buckshot','_startcheck_buckshot','_tg_is')) loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;
