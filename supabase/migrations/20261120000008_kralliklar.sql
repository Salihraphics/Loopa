-- =====================================================================
-- LOOPA 0.8.0 — Krallıklar (4-20 players, simultaneous 60-second planning turns)
-- Source of truth: the Krallıklar specification sent by the user.
--
-- Everything is decided here: map generation, fog of war, production, movement, battles,
-- diplomacy and eliminations. Clients send intentions only (orders, letters, offers).
--
-- BALANCE: the spec leaves many numbers open (section 16). Every number below lives in
-- economy_config['kralliklar_balance'] (admin-editable, merged over these defaults when a
-- game starts) and is PROVISIONAL until play-tested. See docs/design/KRALLIKLAR_NOTES.md.
--
-- Turn-end processing order:
--   1 buildings (paid in the order they were given; unaffordable ones fail)
--   2 departures (troops leave their tiles)
--   3 arrivals and battles (reinforcements first, then attackers, largest first)
--      a captured capital eliminates its kingdom; its territory goes to the conqueror
--   4 production (capital, resource tiles with lifetimes, farms, markets, barracks)
--   5 expiry of pacts and offers
--   6 victory check (last kingdom standing; optional safety turn limit)
-- =====================================================================

insert into public.game_modes(id, name_tr, name_en, description_tr, status, always_free, min_players, max_players, sort_order, tags) values
 ('kralliklar', 'Krallıklar', 'Kingdoms', 'Krallığını kur, kaynak topla, diplomasi yap; başkentini koru. Son krallık kazanır.',
  'available', false, 4, 20, 190, '{strategy,ffa}')
on conflict (id) do nothing;

insert into public.economy_config(key, value) values ('kralliklar_balance', '{}'::jsonb)
on conflict (key) do nothing;

-- Provisional defaults (spec section 16: to be tuned after testing).
create or replace function public._kr_default_balance()
returns jsonb language sql immutable as $$
    select '{
      "turn_seconds": 60,
      "speed": 2,
      "start_troops": 15,
      "start_res": {"wood": 40, "iron": 20, "gold": 5, "food": 40},
      "capital_income": {"wood": 3, "iron": 0, "gold": 1, "food": 3, "troops": 2},
      "resource": {
        "forest": {"res": "wood", "amount": 8, "life": 5},
        "mine":   {"res": "iron", "amount": 5, "life": 10},
        "gold":   {"res": "gold", "amount": 2, "life": 20},
        "farm":   {"res": "food", "amount": 6, "life": 15}
      },
      "terrain_weights": {"plain": 40, "forest": 22, "farm": 16, "mine": 14, "gold": 8},
      "defense_bonus": {"plain": 0, "forest": 0.2, "farm": 0, "mine": 0.3, "gold": 0.1},
      "capital_bonus": 0.5,
      "luck": 0.1,
      "neutral_garrison": 2,
      "buildings": {
        "barracks": {"cost": {"wood": 30, "iron": 15}, "troops": 5, "upkeep_food": 3},
        "market":   {"cost": {"wood": 25, "iron": 5}, "gold": 2},
        "farm":     {"cost": {"wood": 15}, "food": 4}
      },
      "alliance_max": 3,
      "offer_turns": 2,
      "letters_per_turn": 10
    }'::jsonb;
$$;

create or replace function public._kr_balance()
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare d jsonb := public._kr_default_balance(); c jsonb := public._cfg('kralliklar_balance'); k text;
begin
    -- shallow merge per top-level key, deep for the nested objects
    for k in select key from jsonb_each(c) loop
        if d ? k then
            if jsonb_typeof(d->k) = 'object' and jsonb_typeof(c->k) = 'object' then
                d := jsonb_set(d, array[k], (d->k) || (c->k));
            elsif jsonb_typeof(d->k) = jsonb_typeof(c->k) then
                d := jsonb_set(d, array[k], c->k);
            end if;
        end if;
    end loop;
    return d;
end $$;

-- Map size by player count (spec).
create or replace function public._kr_map_size(n int)
returns int language sql immutable as $$
    select case when n = 4 then 200 when n between 5 and 7 then 250 when n = 8 then 300 when n between 9 and 11 then 350
                when n = 12 then 400 when n between 13 and 15 then 450 when n = 16 then 500 when n between 17 and 19 then 550
                when n = 20 then 600 end;
$$;

create or replace function public._kr_palette()
returns text[] language sql immutable as $$
    select array['#E53935','#1E88E5','#43A047','#FDD835','#8E24AA','#FB8C00','#00ACC1','#D81B60','#6D4C41','#3949AB',
                 '#7CB342','#F4511E','#5E35B1','#00897B','#C0CA33','#546E7A','#FFB300','#26A69A','#EC407A','#8D6E63'];
$$;

create or replace function public._opts_kralliklar(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
begin
    -- optional safety limit (not in the spec): 0 = off; otherwise the strongest kingdom wins at the limit
    return jsonb_build_object('max_turns', case when (o->>'max_turns')::int in (0, 60, 100, 150) then (o->>'max_turns')::int else 100 end);
end $$;

create or replace function public._startcheck_kralliklar(p_settings jsonb, n int)
returns void language plpgsql immutable as $$
begin
    if n < 4 or n > 20 then raise exception 'LOOPA:WRONG_PLAYER_COUNT'; end if;
end $$;

create or replace function public._kr_dist(q1 int, r1 int, q2 int, r2 int)
returns int language sql immutable as $$
    select (abs(q1 - q2) + abs(r1 - r2) + abs(q1 + r1 - q2 - r2)) / 2;
$$;

create or replace function public._kr_tdist(s jsonb, a int, b int)
returns int language sql immutable as $$
    select public._kr_dist((s->'tiles'->a->>'q')::int, (s->'tiles'->a->>'r')::int, (s->'tiles'->b->>'q')::int, (s->'tiles'->b->>'r')::int);
$$;

-- Indexes of the existing neighbours of tile i.
create or replace function public._kr_nbrs(s jsonb, i int)
returns int[] language sql immutable as $$
    select array(select (s->'idx'->>(((s->'tiles'->i->>'q')::int + d.dq)::text || ',' || ((s->'tiles'->i->>'r')::int + d.dr)::text))::int
                   from (values (1,0),(1,-1),(0,-1),(-1,0),(-1,1),(0,1)) d(dq, dr)
                  where s->'idx' ? (((s->'tiles'->i->>'q')::int + d.dq)::text || ',' || ((s->'tiles'->i->>'r')::int + d.dr)::text));
$$;

-- Random compact hex blob of exactly p_n tiles with clustered terrain.
create or replace function public._kr_gen_map(p_n int, bal jsonb)
returns jsonb language plpgsql volatile as $$
declare idx jsonb := '{}'::jsonb; front jsonb := '{"0,0":true}'::jsonb; qs int[] := '{}'; rs int[] := '{}';
        pick text; q int; r int; i int; d record; tiles jsonb; seeds jsonb; w jsonb := bal->'terrain_weights'; total numeric;
begin
    for i in 1..p_n loop
        select key into pick from jsonb_object_keys(front) key
         order by public._kr_dist(split_part(key, ',', 1)::int, split_part(key, ',', 2)::int, 0, 0) + random() * 2.5 limit 1;
        q := split_part(pick, ',', 1)::int; r := split_part(pick, ',', 2)::int;
        front := front - pick;
        idx := idx || jsonb_build_object(pick, i - 1);
        qs := qs || q; rs := rs || r;
        for d in select * from (values (1,0),(1,-1),(0,-1),(-1,0),(-1,1),(0,1)) v(dq, dr) loop
            if not (idx ? ((q + d.dq)::text || ',' || (r + d.dr)::text)) then
                front := front || jsonb_build_object((q + d.dq)::text || ',' || (r + d.dr)::text, true);
            end if;
        end loop;
    end loop;
    select sum((value#>>'{}')::numeric) into total from jsonb_each(w);
    -- terrain seeds; every tile takes the terrain of its nearest seed (clusters)
    select jsonb_agg(jsonb_build_object('q', qs[k], 'r', rs[k], 't', (
               select t from (select key as t, sum((value#>>'{}')::numeric) over (order by key) as acc from jsonb_each(w)) z
                where acc >= x.pick order by acc limit 1)))
      into seeds
      from (select k, random() * total as pick from generate_series(1, p_n) k order by random() limit greatest(p_n / 10, 8)) x;
    select jsonb_agg(jsonb_build_object('q', qs[k], 'r', rs[k], 't',
              (select sd->>'t' from jsonb_array_elements(seeds) sd
                order by public._kr_dist(qs[k], rs[k], (sd->>'q')::int, (sd->>'r')::int), random() limit 1),
              'o', 0, 'x', 0, 'b', null, 'l', 0) order by k)
      into tiles from generate_series(1, p_n) k;
    return jsonb_build_object('tiles', tiles, 'idx', idx);
end $$;

create or replace function public._g_kralliklar_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare bal jsonb := public._kr_balance(); o jsonb := public._opts_kralliklar(coalesce(m.settings->'mode_options', '{}'), array_length(players, 1));
        np int := array_length(players, 1); mp jsonb; s jsonb; caps int[] := '{}'; c int; i int; k int; tj jsonb; nb int[]; interior int[];
        ring text[] := array['forest','farm','mine','plain','forest','farm']; ks jsonb := '{}'::jsonb; shuffled uuid[]; pal text[] := public._kr_palette();
        terr text; j int;
begin
    if n > 1 then return null; end if;
    mp := public._kr_gen_map(public._kr_map_size(np), bal);
    s := mp;
    -- capitals: interior tiles spread by farthest-point sampling
    interior := array(select g from generate_series(0, jsonb_array_length(s->'tiles') - 1) g where cardinality(public._kr_nbrs(s, g)) = 6);
    for k in 1..np loop
        select x into c from unnest(interior) x where not x = any(caps)
         order by coalesce((select min(public._kr_tdist(s, x, cp)) from unnest(caps) cp), 0) desc, random() limit 1;
        caps := caps || c;
    end loop;
    -- resource tiles: lifetimes and neutral garrisons
    select jsonb_agg(case when bal->'resource' ? (e.t->>'t')
                          then e.t || jsonb_build_object('l', (bal->'resource'->(e.t->>'t')->>'life')::int, 'x', (bal->>'neutral_garrison')::int)
                          else e.t end order by e.ord)
      into tj from jsonb_array_elements(s->'tiles') with ordinality e(t, ord);
    s := jsonb_set(s, '{tiles}', tj);
    shuffled := array(select x from unnest(players) x order by random());
    for k in 1..np loop
        c := caps[k];
        s := jsonb_set(s, array['tiles', c::text], (s->'tiles'->c) || jsonb_build_object('t', 'plain', 'o', k, 'x', (bal->>'start_troops')::int, 'l', 0));
        nb := public._kr_nbrs(s, c);
        -- fair start: the same ring of terrain for every kingdom
        for j in 1..6 loop
            terr := ring[j];
            s := jsonb_set(s, array['tiles', nb[j]::text], (s->'tiles'->nb[j]) || jsonb_build_object('t', terr, 'o', k, 'x', 0,
                    'l', coalesce((bal->'resource'->terr->>'life')::int, 0)));
        end loop;
        ks := ks || jsonb_build_object(k::text, jsonb_build_object('u', shuffled[k], 'cap', c, 'alive', true, 'color', pal[k], 'ready', false,
                    'name', left(coalesce((select username from public.profiles where id = shuffled[k]), 'Krallık') || ' Krallığı', 32),
                    'res', bal->'start_res', 'elim_turn', null, 'captured', 0, 'battles_won', 0, 'max_tiles', 7));
    end loop;
    return s || jsonb_build_object('phase', 'plan', 'turn', null, 'turn_no', 1, 'bal', bal, 'opts', o, 'k', ks,
        'secs', (bal->>'turn_seconds')::int, 'deadline', public._tg_deadline((bal->>'turn_seconds')::int),
        'armies', '[]'::jsonb, 'orders', '{}'::jsonb, 'alliances', '[]'::jsonb, 'pacts', '[]'::jsonb, 'offers', '[]'::jsonb,
        'inbox', '{}'::jsonb, 'sent', '{}'::jsonb, 'events', '{}'::jsonb, 'log', '[]'::jsonb, 'next_id', 1, 'timeouts', '{}'::jsonb);
end $$;

create or replace function public._kr_k(s jsonb, u text)
returns int language sql immutable as $$
    select key::int from jsonb_each(s->'k') where value->>'u' = u limit 1;
$$;

create or replace function public._kr_alliance_of(s jsonb, k int)
returns int[] language sql immutable as $$
    select coalesce((select array(select (m#>>'{}')::int from jsonb_array_elements(a->'m') m)
                       from jsonb_array_elements(s->'alliances') a where a->'m' @> to_jsonb(k) limit 1), '{}'::int[]);
$$;

create or replace function public._kr_allied(s jsonb, a int, b int)
returns boolean language sql immutable as $$ select a <> b and b = any(public._kr_alliance_of(s, a)); $$;

create or replace function public._kr_event(s jsonb, k int, e jsonb)
returns jsonb language sql immutable as $$
    select jsonb_set(s, array['events', k::text], (select coalesce(jsonb_agg(x), '[]'::jsonb) from (
        select x from jsonb_array_elements(coalesce(s->'events'->k::text, '[]'::jsonb) || jsonb_build_array(e || jsonb_build_object('turn', s->'turn_no'))) x
        offset greatest(jsonb_array_length(coalesce(s->'events'->k::text, '[]'::jsonb)) + 1 - 40, 0)) y), true);
$$;

create or replace function public._kr_res_ok(have jsonb, need jsonb)
returns boolean language sql immutable as $$
    select not exists (select 1 from jsonb_each_text(coalesce(need, '{}'::jsonb)) n where coalesce((have->>n.key)::int, 0) < n.value::int);
$$;

create or replace function public._kr_res_add(have jsonb, delta jsonb, sign int)
returns jsonb language sql immutable as $$
    select have || coalesce((select jsonb_object_agg(d.key, coalesce((have->>d.key)::int, 0) + sign * d.value::int)
                               from jsonb_each_text(coalesce(delta, '{}'::jsonb)) d), '{}'::jsonb);
$$;

-- Leaving / breaking an alliance (an attacker on an ally leaves the alliance).
create or replace function public._kr_leave_alliance(s jsonb, k int)
returns jsonb language sql immutable as $$
    select jsonb_set(s, '{alliances}', coalesce((select jsonb_agg(z) from (
        select case when a->'m' @> to_jsonb(k) then jsonb_set(a, '{m}', (a->'m') - (select (ord - 1)::int from jsonb_array_elements(a->'m') with ordinality e(v, ord) where (v#>>'{}')::int = k))
                    else a end as z
          from jsonb_array_elements(s->'alliances') a) w where jsonb_array_length(z->'m') >= 2), '[]'::jsonb));
$$;

-- Attacker a hits b: an alliance between them breaks, pacts between them break (announced).
create or replace function public._kr_on_attack(s jsonb, a int, b int)
returns jsonb language plpgsql immutable as $$
declare p jsonb; kept jsonb := '[]'::jsonb;
begin
    if a = b or b = 0 then return s; end if;
    if public._kr_allied(s, a, b) then
        s := public._kr_leave_alliance(s, a);
        s := public._tg_log(s, jsonb_build_object('turn', s->'turn_no', 'e', 'alliance_broken', 'k', a, 'victim', b));
    end if;
    for p in select * from jsonb_array_elements(s->'pacts') loop
        if ((p->>'a')::int = a and (p->>'b')::int = b) or ((p->>'a')::int = b and (p->>'b')::int = a) then
            s := public._tg_log(s, jsonb_build_object('turn', s->'turn_no', 'e', 'pact_broken', 'type', p->'type', 'k', a, 'victim', b));
        else
            kept := kept || jsonb_build_array(p);
        end if;
    end loop;
    return s || jsonb_build_object('pacts', kept);
end $$;

-- Kingdom d loses its capital to w: eliminated, its territory and stock go to w.
create or replace function public._kr_eliminate(s jsonb, d int, w int)
returns jsonb language plpgsql immutable as $$
declare tj jsonb;
begin
    select jsonb_agg(case when (e.t->>'o')::int = d then e.t || jsonb_build_object('o', w, 'x', case when (e.ord - 1) = (s->'k'->d::text->>'cap')::int then (e.t->>'x')::int else 0 end)
                          else e.t end order by e.ord)
      into tj from jsonb_array_elements(s->'tiles') with ordinality e(t, ord);
    s := jsonb_set(s, '{tiles}', tj);
    if w > 0 then
        s := jsonb_set(s, array['k', w::text, 'res'], public._kr_res_add(s->'k'->w::text->'res', s->'k'->d::text->'res', 1));
        s := jsonb_set(s, array['k', w::text, 'captured'], to_jsonb((s->'k'->w::text->>'captured')::int + 1));
    end if;
    s := jsonb_set(s, array['k', d::text], (s->'k'->d::text) || jsonb_build_object('alive', false, 'elim_turn', s->'turn_no', 'by', w,
              'res', '{"wood":0,"iron":0,"gold":0,"food":0}'::jsonb, 'ready', false));
    s := public._kr_leave_alliance(s, d);
    s := s || jsonb_build_object(
        'armies', coalesce((select jsonb_agg(a) from jsonb_array_elements(s->'armies') a where (a->>'k')::int <> d), '[]'::jsonb),
        'pacts', coalesce((select jsonb_agg(p) from jsonb_array_elements(s->'pacts') p where (p->>'a')::int <> d and (p->>'b')::int <> d), '[]'::jsonb),
        'offers', coalesce((select jsonb_agg(o) from jsonb_array_elements(s->'offers') o where (o->>'from')::int <> d and (o->>'to')::int <> d), '[]'::jsonb));
    s := s #- array['orders', d::text];
    s := public._kr_event(s, d, jsonb_build_object('e', 'eliminated', 'by', w));
    return public._tg_log(s, jsonb_build_object('turn', s->'turn_no', 'e', 'eliminated', 'k', d, 'by', w));
end $$;

-- One battle on tile i: attacker kingdom a with n troops against the holder.
create or replace function public._kr_battle(s jsonb, i int, a int, n int)
returns jsonb language plpgsql volatile as $$
declare t jsonb := s->'tiles'->i; d int := (t->>'o')::int; def int := (t->>'x')::int; bal jsonb := s->'bal';
        is_cap boolean := false; mult numeric; luck numeric := (bal->>'luck')::numeric; ap numeric; dp numeric; surv int; won boolean;
        rep jsonb;
begin
    is_cap := d > 0 and (s->'k'->d::text->>'cap')::int = i;
    s := public._kr_on_attack(s, a, d);
    if def <= 0 then
        won := true; surv := n; ap := n; dp := 0;
    else
        mult := 1 + coalesce((bal->'defense_bonus'->>(t->>'t'))::numeric, 0) + case when is_cap then (bal->>'capital_bonus')::numeric else 0 end;
        ap := n * (1 + (random() * 2 - 1) * luck);
        dp := def * mult * (1 + (random() * 2 - 1) * luck);
        won := ap > dp;
        if won then surv := greatest(1, round(n * (1 - dp / ap))::int);
        else surv := greatest(0, round(def * (1 - ap / dp))::int); end if;
    end if;
    rep := jsonb_build_object('e', 'battle', 'tile', i, 'att', a, 'def', d, 'att_n', n, 'def_n', def, 'won', won, 'left', surv);
    s := public._kr_event(s, a, rep);
    if d > 0 then s := public._kr_event(s, d, rep); end if;
    if won then
        s := jsonb_set(s, array['tiles', i::text], t || jsonb_build_object('o', a, 'x', surv, 'b', case when is_cap then null else t->'b' end));
        s := jsonb_set(s, array['k', a::text, 'battles_won'], to_jsonb((s->'k'->a::text->>'battles_won')::int + case when def > 0 then 1 else 0 end));
        if is_cap then s := public._kr_eliminate(s, d, a); end if;
    else
        s := jsonb_set(s, array['tiles', i::text, 'x'], to_jsonb(surv));
        if d > 0 then s := jsonb_set(s, array['k', d::text, 'battles_won'], to_jsonb((s->'k'->d::text->>'battles_won')::int + 1)); end if;
    end if;
    return s;
end $$;

create or replace function public._kr_finish(s jsonb)
returns jsonb language plpgsql immutable as $$
declare pts jsonb := '{}'::jsonb; stats jsonb := '{}'::jsonb; r record; winner int;
begin
    -- rank: alive kingdoms by strength (only one at a normal end), then the later eliminated
    for r in
        with sc as (
            select key::int as k, value as kv, (value->>'alive')::boolean as alive, coalesce((value->>'elim_turn')::int, 0) as et,
                   (select count(*) from jsonb_array_elements(s->'tiles') t where (t->>'o')::int = key::int) as tiles,
                   (select coalesce(sum((t->>'x')::int), 0) from jsonb_array_elements(s->'tiles') t where (t->>'o')::int = key::int) as troops
              from jsonb_each(s->'k'))
        select sc.*, (select count(*) from sc o where (not o.alive and sc.alive) or (o.alive = sc.alive and not o.alive and o.et < sc.et)
                          or (o.alive and sc.alive and (o.tiles * 10 + o.troops) < (sc.tiles * 10 + sc.troops))) as below
          from sc
    loop
        pts := pts || jsonb_build_object(r.kv->>'u', r.below);
        stats := stats || jsonb_build_object(r.kv->>'u', jsonb_build_object('games', 1,
                    'wins', case when r.below = jsonb_array_length(jsonb_path_query_array(s->'k', '$.*')) - 1 then 1 else 0 end,
                    'capitals_taken', (r.kv->>'captured')::int, 'battles_won', (r.kv->>'battles_won')::int,
                    'max_tiles', greatest((r.kv->>'max_tiles')::int, r.tiles)));
    end loop;
    select key::int into winner from jsonb_each(s->'k') where (value->>'alive')::boolean
     order by (select count(*) from jsonb_array_elements(s->'tiles') t where (t->>'o')::int = key::int) desc limit 1;
    return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'points', pts, 'stats', stats, 'winner', winner);
end $$;

-- Turn end (see the processing order in the header).
create or replace function public._kr_process(s jsonb)
returns jsonb language plpgsql volatile as $$
declare bal jsonb := s->'bal'; tn int := (s->>'turn_no')::int; k text; mv jsonb; b jsonb; i int; frm int; dst int; n int;
        cost jsonb; arrive int; grp record; att record; tile jsonb; tj jsonb; kk record; inc jsonb; alive_n int; max_t int;
        nid int := (s->>'next_id')::int; arrived jsonb;
begin
    -- 1) buildings
    for k in select key from jsonb_each(s->'orders') order by random() loop
        continue when not coalesce((s->'k'->k->>'alive')::boolean, false);
        for b in select * from jsonb_array_elements(coalesce(s->'orders'->k->'builds', '[]'::jsonb)) loop
            i := (b->>'tile')::int;
            tile := s->'tiles'->i;
            cost := bal->'buildings'->(b->>'type')->'cost';
            if tile is null or (tile->>'o') <> k or (tile->>'b') is not null or (s->'k'->k->>'cap')::int = i then
                s := public._kr_event(s, k::int, jsonb_build_object('e', 'build_failed', 'tile', i, 'type', b->'type', 'why', 'TILE'));
            elsif not public._kr_res_ok(s->'k'->k->'res', cost) then
                s := public._kr_event(s, k::int, jsonb_build_object('e', 'build_failed', 'tile', i, 'type', b->'type', 'why', 'RESOURCES'));
            else
                s := jsonb_set(s, array['k', k, 'res'], public._kr_res_add(s->'k'->k->'res', cost, -1));
                s := jsonb_set(s, array['tiles', i::text, 'b'], b->'type');
                s := public._kr_event(s, k::int, jsonb_build_object('e', 'built', 'tile', i, 'type', b->'type'));
            end if;
        end loop;
    end loop;
    -- 2) departures
    for k in select key from jsonb_each(s->'orders') order by random() loop
        continue when not coalesce((s->'k'->k->>'alive')::boolean, false);
        for mv in select * from jsonb_array_elements(coalesce(s->'orders'->k->'moves', '[]'::jsonb)) loop
            frm := (mv->>'from')::int; dst := (mv->>'to')::int;
            tile := s->'tiles'->frm;
            continue when tile is null or (tile->>'o') <> k;
            n := least((mv->>'n')::int, (tile->>'x')::int);
            continue when n < 1;
            s := jsonb_set(s, array['tiles', frm::text, 'x'], to_jsonb((tile->>'x')::int - n));
            arrive := tn + greatest(ceil(public._kr_tdist(s, frm, dst)::numeric / (bal->>'speed')::numeric)::int, 1) - 1;
            s := jsonb_set(s, '{armies}', (s->'armies') || jsonb_build_array(jsonb_build_object('id', nid, 'k', k::int, 'from', frm, 'to', dst, 'n', n, 'arrive', arrive)));
            nid := nid + 1;
        end loop;
    end loop;
    s := s || jsonb_build_object('next_id', nid);
    -- 3) arrivals and battles
    arrived := coalesce((select jsonb_agg(a) from jsonb_array_elements(s->'armies') a where (a->>'arrive')::int <= tn), '[]'::jsonb);
    s := jsonb_set(s, '{armies}', coalesce((select jsonb_agg(a) from jsonb_array_elements(s->'armies') a where (a->>'arrive')::int > tn), '[]'::jsonb));
    for grp in select z.dst from (select distinct (a->>'to')::int as dst from jsonb_array_elements(arrived) a) z order by random() loop
        -- own reinforcements first (an army whose kingdom fell in this turn disbands)
        for att in select (a->>'k')::int as k, sum((a->>'n')::int)::int as n from jsonb_array_elements(arrived) a
                    where (a->>'to')::int = grp.dst group by 1
                    order by ((a->>'k')::int = (s->'tiles'->grp.dst->>'o')::int) desc, 2 desc, random() loop
            continue when not coalesce((s->'k'->att.k::text->>'alive')::boolean, false);
            if (s->'tiles'->grp.dst->>'o')::int = att.k then
                s := jsonb_set(s, array['tiles', grp.dst::text, 'x'], to_jsonb((s->'tiles'->grp.dst->>'x')::int + att.n));
                s := public._kr_event(s, att.k, jsonb_build_object('e', 'arrived', 'tile', grp.dst, 'n', att.n));
            else
                s := public._kr_battle(s, grp.dst, att.k, att.n);
            end if;
        end loop;
    end loop;
    -- 4) production
    for kk in select key, value from jsonb_each(s->'k') where (value->>'alive')::boolean loop
        inc := jsonb_build_object('wood', (bal->'capital_income'->>'wood')::int, 'iron', (bal->'capital_income'->>'iron')::int,
                                  'gold', (bal->'capital_income'->>'gold')::int, 'food', (bal->'capital_income'->>'food')::int);
        select public._kr_res_add(inc, coalesce(jsonb_object_agg(z.res, z.amt), '{}'::jsonb), 1) into inc from (
            select bal->'resource'->(t->>'t')->>'res' as res, sum((bal->'resource'->(t->>'t')->>'amount')::int) as amt
              from jsonb_array_elements(s->'tiles') t
             where t->>'o' = kk.key and (t->>'l')::int > 0 and bal->'resource' ? (t->>'t') group by 1) z;
        select public._kr_res_add(inc, jsonb_build_object(
                   'food', coalesce(sum(case when t->>'b' = 'farm' then (bal->'buildings'->'farm'->>'food')::int else 0 end), 0),
                   'gold', coalesce(sum(case when t->>'b' = 'market' then (bal->'buildings'->'market'->>'gold')::int else 0 end), 0)), 1)
          into inc from jsonb_array_elements(s->'tiles') t where t->>'o' = kk.key;
        s := jsonb_set(s, array['k', kk.key, 'res'], public._kr_res_add(s->'k'->kk.key->'res', inc, 1));
        i := (kk.value->>'cap')::int;
        s := jsonb_set(s, array['tiles', i::text, 'x'], to_jsonb((s->'tiles'->i->>'x')::int + (bal->'capital_income'->>'troops')::int));
        -- barracks: soldiers if the food upkeep can be paid
        for i in select (e.ord - 1)::int from jsonb_array_elements(s->'tiles') with ordinality e(t, ord)
                  where e.t->>'o' = kk.key and e.t->>'b' = 'barracks' order by e.ord loop
            if (s->'k'->kk.key->'res'->>'food')::int >= (bal->'buildings'->'barracks'->>'upkeep_food')::int then
                s := jsonb_set(s, array['k', kk.key, 'res', 'food'], to_jsonb((s->'k'->kk.key->'res'->>'food')::int - (bal->'buildings'->'barracks'->>'upkeep_food')::int));
                s := jsonb_set(s, array['tiles', i::text, 'x'], to_jsonb((s->'tiles'->i->>'x')::int + (bal->'buildings'->'barracks'->>'troops')::int));
            else
                s := public._kr_event(s, kk.key::int, jsonb_build_object('e', 'no_food', 'tile', i));
            end if;
        end loop;
    end loop;
    -- resource lifetimes: an owned resource tile spends one turn of its life (depleted tiles stay owned, no regrowth)
    select jsonb_agg(case when (t->>'o')::int > 0 and (t->>'l')::int > 0 then t || jsonb_build_object('l', (t->>'l')::int - 1) else t end order by ord)
      into tj from jsonb_array_elements(s->'tiles') with ordinality e(t, ord);
    s := jsonb_set(s, '{tiles}', tj);
    for kk in select key from jsonb_each(s->'k') where (value->>'alive')::boolean loop
        s := jsonb_set(s, array['k', kk.key, 'max_tiles'], to_jsonb(greatest((s->'k'->kk.key->>'max_tiles')::int,
                 (select count(*)::int from jsonb_array_elements(s->'tiles') t where t->>'o' = kk.key))));
    end loop;
    -- 5) expiry
    s := s || jsonb_build_object(
        'pacts', coalesce((select jsonb_agg(p) from jsonb_array_elements(s->'pacts') p where (p->>'until')::int > tn), '[]'::jsonb),
        'offers', coalesce((select jsonb_agg(o) from jsonb_array_elements(s->'offers') o where o->>'status' = 'pending' and (o->>'expires')::int > tn), '[]'::jsonb));
    -- 6) victory
    select count(*) into alive_n from jsonb_each(s->'k') where (value->>'alive')::boolean;
    max_t := coalesce((s->'opts'->>'max_turns')::int, 0);
    if alive_n <= 1 or (max_t > 0 and tn >= max_t) then return public._kr_finish(s); end if;
    select jsonb_object_agg(key, value || '{"ready": false}'::jsonb) into tj from jsonb_each(s->'k');
    return s || jsonb_build_object('k', tj, 'turn_no', tn + 1, 'orders', '{}'::jsonb,
        'deadline', public._tg_deadline((bal->>'turn_seconds')::int));
end $$;

create or replace function public._kr_int(v jsonb)
returns int language plpgsql immutable as $$
begin
    if v is null or jsonb_typeof(v) <> 'number' then return null; end if;
    return (v#>>'{}')::numeric::int;
exception when others then return null;
end $$;

-- Clean resource bundle {wood,iron,gold,food} with non-negative integers.
create or replace function public._kr_bundle(v jsonb)
returns jsonb language plpgsql immutable as $$
declare out jsonb := '{}'::jsonb; k text; x int;
begin
    if v is null then return '{}'::jsonb; end if;
    if jsonb_typeof(v) <> 'object' then raise exception 'LOOPA:INVALID_INPUT'; end if;
    for k in select key from jsonb_each(v) loop
        x := public._kr_int(v->k);
        if k not in ('wood','iron','gold','food') or x is null or x < 0 or x > 1000000 then raise exception 'LOOPA:INVALID_INPUT'; end if;
        if x > 0 then out := out || jsonb_build_object(k, x); end if;
    end loop;
    return out;
end $$;

-- kinds:
--  orders {"moves":[{"from":i,"to":j,"n":c}],"builds":[{"tile":i,"type":"barracks|market|farm"}]}  (replaces this turn's orders)
--  ready {"ready":bool}       rename {"name":"..","color":"#RRGGBB"} (first turn only)
--  letter {"to":[k..],"subject":"..","body":".."}
--  offer {"to":k,"type":"nap|alliance|trade|joint_enemy|peace","turns":n,"give":{..},"get":{..},"target":k}
--  respond {"id":n,"accept":bool}   cancel_offer {"id":n}   leave_alliance {}
create or replace function public._g_kralliklar_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me int := public._kr_k(s, u::text); tn int := (s->>'turn_no')::int; bal jsonb := s->'bal'; ntiles int := jsonb_array_length(s->'tiles');
        mv jsonb; b jsonb; moves jsonb := '[]'::jsonb; builds jsonb := '[]'::jsonb; f int; t int; c int; x text; ofr jsonb; idx int;
        to_list jsonb := '[]'::jsonb; subj text; body text; typ text; turns int; give jsonb; get jsonb; tgt int; ga int[]; gb int[];
        room uuid; all_ready boolean; nid int := (s->>'next_id')::int; acc boolean;
begin
    if me is null then raise exception 'LOOPA:NOT_A_PARTICIPANT'; end if;
    if s->>'phase' <> 'plan' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if not (s->'k'->me::text->>'alive')::boolean then raise exception 'LOOPA:ELIMINATED'; end if;

    if p_kind = 'orders' then
        if jsonb_typeof(coalesce(p->'moves', '[]')) <> 'array' or jsonb_typeof(coalesce(p->'builds', '[]')) <> 'array'
           or jsonb_array_length(coalesce(p->'moves', '[]')) > 60 or jsonb_array_length(coalesce(p->'builds', '[]')) > 10 then
            raise exception 'LOOPA:INVALID_INPUT';
        end if;
        for mv in select * from jsonb_array_elements(coalesce(p->'moves', '[]')) loop
            f := public._kr_int(mv->'from'); t := public._kr_int(mv->'to'); c := public._kr_int(mv->'n');
            if f is null or t is null or c is null or f < 0 or t < 0 or f >= ntiles or t >= ntiles or f = t or c < 1 then raise exception 'LOOPA:INVALID_INPUT'; end if;
            if (s->'tiles'->f->>'o')::int <> me then raise exception 'LOOPA:NOT_YOUR_TILE'; end if;
            moves := moves || jsonb_build_array(jsonb_build_object('from', f, 'to', t, 'n', c));
        end loop;
        for b in select * from jsonb_array_elements(coalesce(p->'builds', '[]')) loop
            t := public._kr_int(b->'tile');
            if t is null or t < 0 or t >= ntiles or not (bal->'buildings' ? (b->>'type')) then raise exception 'LOOPA:INVALID_INPUT'; end if;
            if (s->'tiles'->t->>'o')::int <> me then raise exception 'LOOPA:NOT_YOUR_TILE'; end if;
            if (s->'tiles'->t->>'b') is not null or (s->'k'->me::text->>'cap')::int = t then raise exception 'LOOPA:TILE_TAKEN'; end if;
            builds := builds || jsonb_build_array(jsonb_build_object('tile', t, 'type', b->>'type'));
        end loop;
        return jsonb_set(s, array['orders', me::text], jsonb_build_object('moves', moves, 'builds', builds), true);
    end if;

    if p_kind = 'ready' then
        s := jsonb_set(s, array['k', me::text, 'ready'], to_jsonb(coalesce((p->>'ready')::boolean, true)));
        -- every kingdom whose player is still here is ready: end the planning early
        select room_id into room from public.matches where id = (s->>'match')::uuid;
        select bool_and((value->>'ready')::boolean) into all_ready from jsonb_each(s->'k')
         where (value->>'alive')::boolean and (room is null or public._mz_active(room, (value->>'u')::uuid));
        if coalesce(all_ready, false) then s := s || jsonb_build_object('deadline', public._tg_deadline(1)); end if;
        return s;
    end if;

    if p_kind = 'rename' then
        if tn <> 1 then raise exception 'LOOPA:PHASE_CLOSED'; end if;
        x := public._mz_clean(p->>'name', 24);
        if char_length(coalesce(x, '')) < 2 then raise exception 'LOOPA:INVALID_INPUT'; end if;
        if exists (select 1 from jsonb_each(s->'k') where key <> me::text and lower(value->>'name') = lower(x)) then raise exception 'LOOPA:NAME_TAKEN'; end if;
        s := jsonb_set(s, array['k', me::text, 'name'], to_jsonb(x));
        if p ? 'color' then
            if not (p->>'color' = any(public._kr_palette())) then raise exception 'LOOPA:INVALID_INPUT'; end if;
            if exists (select 1 from jsonb_each(s->'k') where key <> me::text and value->>'color' = p->>'color') then raise exception 'LOOPA:COLOR_TAKEN'; end if;
            s := jsonb_set(s, array['k', me::text, 'color'], to_jsonb(p->>'color'));
        end if;
        return s;
    end if;

    if p_kind = 'letter' then
        to_list := case when jsonb_typeof(p->'to') = 'array' then p->'to' else jsonb_build_array(p->'to') end;
        if jsonb_array_length(to_list) < 1 or jsonb_array_length(to_list) > 5 then raise exception 'LOOPA:INVALID_INPUT'; end if;
        subj := public._mz_clean(p->>'subject', 60); body := public._mz_clean(p->>'body', 600);
        if char_length(coalesce(body, '')) = 0 then raise exception 'LOOPA:MESSAGE_EMPTY'; end if;
        if (select count(*) from jsonb_each(s->'inbox') ib, jsonb_array_elements(ib.value) l
             where (l->>'from')::int = me and (l->>'turn')::int = tn) >= (bal->>'letters_per_turn')::int * 5 then
            raise exception 'LOOPA:RATE_LIMITED';
        end if;
        for idx in select distinct public._kr_int(v) from jsonb_array_elements(to_list) v loop
            if idx is null or idx = me or not coalesce((s->'k'->idx::text->>'alive')::boolean, false) then raise exception 'LOOPA:INVALID_TARGET'; end if;
            s := jsonb_set(s, array['inbox', idx::text], (select coalesce(jsonb_agg(z), '[]'::jsonb) from (
                     select z from jsonb_array_elements(coalesce(s->'inbox'->idx::text, '[]'::jsonb) || jsonb_build_array(jsonb_build_object(
                         'id', nid, 'from', me, 'to', to_list, 'subject', coalesce(subj, ''), 'body', body, 'turn', tn, 'at', now()))) z
                     offset greatest(jsonb_array_length(coalesce(s->'inbox'->idx::text, '[]'::jsonb)) + 1 - 50, 0)) w), true);
        end loop;
        -- the sender keeps a copy
        s := jsonb_set(s, array['sent', me::text], (select coalesce(jsonb_agg(z), '[]'::jsonb) from (
                 select z from jsonb_array_elements(coalesce(s->'sent'->me::text, '[]'::jsonb) || jsonb_build_array(jsonb_build_object(
                     'id', nid, 'from', me, 'to', to_list, 'subject', coalesce(subj, ''), 'body', body, 'turn', tn, 'at', now()))) z
                 offset greatest(jsonb_array_length(coalesce(s->'sent'->me::text, '[]'::jsonb)) + 1 - 30, 0)) w), true);
        return s || jsonb_build_object('next_id', nid + 1);
    end if;

    if p_kind = 'offer' then
        t := public._kr_int(p->'to'); typ := p->>'type';
        if t is null or t = me or not coalesce((s->'k'->t::text->>'alive')::boolean, false) then raise exception 'LOOPA:INVALID_TARGET'; end if;
        if typ not in ('nap','alliance','trade','joint_enemy','peace') then raise exception 'LOOPA:INVALID_INPUT'; end if;
        if (select count(*) from jsonb_array_elements(s->'offers') o where (o->>'from')::int = me and o->>'status' = 'pending') >= 10 then
            raise exception 'LOOPA:RATE_LIMITED';
        end if;
        ofr := jsonb_build_object('id', nid, 'from', me, 'to', t, 'type', typ, 'status', 'pending', 'turn', tn,
                                'expires', tn + (bal->>'offer_turns')::int);
        if typ in ('nap','peace','joint_enemy') then
            turns := coalesce(public._kr_int(p->'turns'), case when typ = 'peace' then 10 else 5 end);
            if turns < 2 or turns > 30 then raise exception 'LOOPA:INVALID_INPUT'; end if;
            ofr := ofr || jsonb_build_object('turns', turns);
        end if;
        if typ = 'joint_enemy' then
            tgt := public._kr_int(p->'target');
            if tgt is null or tgt in (me, t) or not coalesce((s->'k'->tgt::text->>'alive')::boolean, false) then raise exception 'LOOPA:INVALID_TARGET'; end if;
            ofr := ofr || jsonb_build_object('target', tgt);
        elsif typ = 'trade' then
            give := public._kr_bundle(p->'give'); get := public._kr_bundle(p->'get');
            if give = '{}'::jsonb and get = '{}'::jsonb then raise exception 'LOOPA:INVALID_INPUT'; end if;
            ofr := ofr || jsonb_build_object('give', give, 'get', get);
        elsif typ = 'alliance' and public._kr_allied(s, me, t) then
            raise exception 'LOOPA:ALREADY_ALLIED';
        end if;
        s := jsonb_set(s, '{offers}', (s->'offers') || jsonb_build_array(ofr));
        s := public._kr_event(s, t, jsonb_build_object('e', 'offer', 'id', nid, 'from', me, 'type', typ));
        return s || jsonb_build_object('next_id', nid + 1);
    end if;

    if p_kind in ('respond', 'cancel_offer') then
        idx := (select (ord - 1)::int from jsonb_array_elements(s->'offers') with ordinality e(o, ord)
                 where (o->>'id')::int = public._kr_int(p->'id') and o->>'status' = 'pending');
        if idx is null then raise exception 'LOOPA:OFFER_NOT_FOUND'; end if;
        ofr := s->'offers'->idx;
        if p_kind = 'cancel_offer' then
            if (ofr->>'from')::int <> me then raise exception 'LOOPA:OFFER_NOT_FOUND'; end if;
            return jsonb_set(s, array['offers', idx::text, 'status'], '"cancelled"');
        end if;
        if (ofr->>'to')::int <> me then raise exception 'LOOPA:OFFER_NOT_FOUND'; end if;
        f := (ofr->>'from')::int;
        acc := coalesce((p->>'accept')::boolean, false);
        if not acc then
            s := jsonb_set(s, array['offers', idx::text, 'status'], '"rejected"');
            return public._kr_event(s, f, jsonb_build_object('e', 'offer_rejected', 'id', ofr->'id', 'by', me, 'type', ofr->'type'));
        end if;
        typ := ofr->>'type';
        if typ = 'trade' then
            -- atomic swap; fails (without raising) when either side is short
            if not public._kr_res_ok(s->'k'->f::text->'res', ofr->'give') or not public._kr_res_ok(s->'k'->me::text->'res', ofr->'get') then
                s := jsonb_set(s, array['offers', idx::text, 'status'], '"failed"');
                s := public._kr_event(s, f, jsonb_build_object('e', 'trade_failed', 'id', ofr->'id', 'with', me));
                return public._kr_event(s, me, jsonb_build_object('e', 'trade_failed', 'id', ofr->'id', 'with', f));
            end if;
            s := jsonb_set(s, array['k', f::text, 'res'], public._kr_res_add(public._kr_res_add(s->'k'->f::text->'res', ofr->'give', -1), ofr->'get', 1));
            s := jsonb_set(s, array['k', me::text, 'res'], public._kr_res_add(public._kr_res_add(s->'k'->me::text->'res', ofr->'get', -1), ofr->'give', 1));
        elsif typ = 'alliance' then
            ga := public._kr_alliance_of(s, f); gb := public._kr_alliance_of(s, me);
            if (cardinality(ga) > 0 and cardinality(gb) > 0)
               or greatest(cardinality(ga), cardinality(gb)) + 1 > (bal->>'alliance_max')::int then
                s := jsonb_set(s, array['offers', idx::text, 'status'], '"failed"');
                s := public._kr_event(s, f, jsonb_build_object('e', 'alliance_failed', 'id', ofr->'id', 'with', me));
                return public._kr_event(s, me, jsonb_build_object('e', 'alliance_failed', 'id', ofr->'id', 'with', f));
            end if;
            if cardinality(ga) = 0 and cardinality(gb) = 0 then
                s := jsonb_set(s, '{alliances}', (s->'alliances') || jsonb_build_array(jsonb_build_object('id', nid, 'm', jsonb_build_array(f, me))));
                s := s || jsonb_build_object('next_id', nid + 1);
            else
                s := jsonb_set(s, '{alliances}', (select jsonb_agg(case when a->'m' @> to_jsonb(case when cardinality(ga) > 0 then f else me end)
                         then jsonb_set(a, '{m}', (a->'m') || to_jsonb(case when cardinality(ga) > 0 then me else f end)) else a end)
                       from jsonb_array_elements(s->'alliances') a));
            end if;
            s := public._tg_log(s, jsonb_build_object('turn', tn, 'e', 'alliance', 'a', f, 'b', me));
        else
            s := jsonb_set(s, '{pacts}', (s->'pacts') || jsonb_build_array(jsonb_build_object('type', typ, 'a', f, 'b', me,
                     'until', tn + (ofr->>'turns')::int, 'target', ofr->'target')));
        end if;
        s := jsonb_set(s, array['offers', idx::text, 'status'], '"accepted"');
        return public._kr_event(s, f, jsonb_build_object('e', 'offer_accepted', 'id', ofr->'id', 'by', me, 'type', typ));
    end if;

    if p_kind = 'leave_alliance' then
        if cardinality(public._kr_alliance_of(s, me)) = 0 then raise exception 'LOOPA:NOT_ALLIED'; end if;
        s := public._kr_leave_alliance(s, me);
        return public._tg_log(s, jsonb_build_object('turn', tn, 'e', 'alliance_left', 'k', me));
    end if;
    raise exception 'LOOPA:INVALID_ACTION';
end $$;

create or replace function public._g_kralliklar_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if s->>'phase' <> 'plan' then return s; end if;
    return public._kr_process(s);
end $$;

-- Everybody: map shape (coordinates only), kingdoms, alliances (public), turn, log. No tile contents.
create or replace function public._g_kralliklar_public(s jsonb)
returns jsonb language plpgsql immutable as $$
declare over boolean := coalesce((s->>'over')::boolean, false);
begin
    return jsonb_build_object('phase', s->'phase', 'turn_no', s->'turn_no', 'deadline', s->'deadline', 'opts', s->'opts',
        'map', (select jsonb_agg(jsonb_build_array((t->>'q')::int, (t->>'r')::int) order by ord) from jsonb_array_elements(s->'tiles') with ordinality e(t, ord)),
        'kingdoms', (select jsonb_agg(jsonb_build_object('k', key::int, 'name', value->'name', 'color', value->'color', 'alive', value->'alive',
                         'ready', value->'ready', 'user', value->'u', 'elim_turn', value->'elim_turn') order by key::int) from jsonb_each(s->'k')),
        'alliances', s->'alliances', 'log', s->'log', 'winner', s->'winner',
        -- after the game the whole map is revealed
        'tiles', case when over then s->'tiles' end);
end $$;

create or replace function public._kr_visible(s jsonb, ks int[])
returns int[] language sql immutable as $$
    with own as (select (e.ord - 1)::int as i, e.t from jsonb_array_elements(s->'tiles') with ordinality e(t, ord) where (e.t->>'o')::int = any(ks))
    select array(select distinct v from (
        select i as v from own
        union
        select (s->'idx'->>(((o.t->>'q')::int + d.dq)::text || ',' || ((o.t->>'r')::int + d.dr)::text))::int
          from own o, (values (1,0),(1,-1),(0,-1),(-1,0),(-1,1),(0,1)) d(dq, dr)) z where v is not null order by v);
$$;

-- What only this kingdom sees: own (and allies') tiles plus one ring, own stock, orders, armies, mail, offers, pacts, reports.
create or replace function public._g_kralliklar_view(s jsonb, u uuid)
returns jsonb language plpgsql immutable as $$
declare me int := public._kr_k(s, u::text); ks int[]; vis int[]; bal jsonb := s->'bal';
begin
    if me is null then return '{}'::jsonb; end if;
    ks := array[me] || public._kr_alliance_of(s, me);
    vis := case when (s->'k'->me::text->>'alive')::boolean then public._kr_visible(s, ks) else '{}'::int[] end;
    return jsonb_build_object('k', me, 'alive', s->'k'->me::text->'alive', 'capital', s->'k'->me::text->'cap', 'res', s->'k'->me::text->'res',
        'ready', s->'k'->me::text->'ready', 'allies', to_jsonb(public._kr_alliance_of(s, me)),
        'tiles', (select coalesce(jsonb_agg(jsonb_build_object('i', v, 't', s->'tiles'->v->'t', 'o', s->'tiles'->v->'o', 'x', s->'tiles'->v->'x',
                       'b', s->'tiles'->v->'b', 'l', s->'tiles'->v->'l',
                       'cap', exists (select 1 from jsonb_each(s->'k') kk where (kk.value->>'alive')::boolean and (kk.value->>'cap')::int = v))
                       order by v), '[]'::jsonb) from unnest(vis) v),
        'orders', coalesce(s->'orders'->me::text, '{"moves":[],"builds":[]}'::jsonb),
        'armies', coalesce((select jsonb_agg(a) from jsonb_array_elements(s->'armies') a where (a->>'k')::int = me), '[]'::jsonb),
        'inbox', coalesce(s->'inbox'->me::text, '[]'::jsonb), 'sent', coalesce(s->'sent'->me::text, '[]'::jsonb),
        'offers', coalesce((select jsonb_agg(o) from jsonb_array_elements(s->'offers') o where (o->>'from')::int = me or (o->>'to')::int = me), '[]'::jsonb),
        'pacts', coalesce((select jsonb_agg(p) from jsonb_array_elements(s->'pacts') p where (p->>'a')::int = me or (p->>'b')::int = me), '[]'::jsonb),
        'events', coalesce(s->'events'->me::text, '[]'::jsonb),
        'balance', jsonb_build_object('buildings', bal->'buildings', 'resource', bal->'resource', 'speed', bal->'speed',
                       'defense_bonus', bal->'defense_bonus', 'capital_bonus', bal->'capital_bonus', 'capital_income', bal->'capital_income'));
end $$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_kr\_%' or p.proname like '\_g\_kralliklar\_%' or p.proname in ('_opts_kralliklar','_startcheck_kralliklar')) loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;
