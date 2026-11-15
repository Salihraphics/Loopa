-- Krallıklar: explored-tile memory for a three-level fog of war (0.9.0).
--
-- Before: a kingdom's view held only the tiles it sees right now (own and allied tiles plus one
-- ring). Everything else was the same blank fog.
-- Now there are three levels:
--   visible    own and allied tiles plus one ring, full detail (unchanged);
--   explored   terrain known, nothing else: every tile within "terrain_sight" steps (balance
--              key, default 3) of the kingdom's (and its allies') land, now or earlier;
--   unexplored never seen; not sent at all (unchanged).
-- The server remembers explored tiles per kingdom (state key "seen") and the view sends the ones
-- that are not visible right now with their TERRAIN ONLY:
--     {"i": <tile>, "t": <terrain>, "seen": true}
-- No owner, troops, building, resource life or capital flag is sent for them, so enemy positions,
-- counts and movements stay hidden. Terrain never changes during a game, so it is not stale.
--
-- Game rules, map generation, movement, battles and turn order are unchanged: the existing turn
-- processing and game creation run first, untouched (renamed to *_core), and only then is the
-- memory updated, at game start and at every turn end.

alter function public._kr_process(jsonb) rename to _kr_process_core;
alter function public._g_kralliklar_new(public.matches, uuid[], int) rename to _kr_new_core;

-- Tiles within n more steps of a set of tiles (ring by ring through the coordinate index).
create or replace function public._kr_expand(s jsonb, tiles int[], n int)
returns int[] language plpgsql immutable as $$
declare cur int[] := tiles; i int;
begin
    for i in 1..greatest(n, 0) loop
        cur := array(select distinct v from (
            select unnest(cur) as v
            union
            select (s->'idx'->>(((s->'tiles'->c->>'q')::int + d.dq)::text || ',' || ((s->'tiles'->c->>'r')::int + d.dr)::text))::int
              from unnest(cur) c, (values (1,0),(1,-1),(0,-1),(-1,0),(-1,1),(0,1)) d(dq, dr)) z where v is not null);
    end loop;
    return cur;
end $$;

-- Adds what every living kingdom (with its allies' land) knows the terrain of to its memory:
-- the visible ring plus "terrain_sight" - 1 more rings.
create or replace function public._kr_remember(s jsonb)
returns jsonb language plpgsql immutable as $$
declare kk record; vis int[]; seen jsonb := coalesce(s->'seen', '{}'::jsonb);
        sight int := greatest(coalesce((s->'bal'->>'terrain_sight')::int, 3), 1);
begin
    if s->'k' is null then return s; end if;
    for kk in select key from jsonb_each(s->'k') where (value->>'alive')::boolean loop
        vis := public._kr_expand(s, public._kr_visible(s, array[kk.key::int] || public._kr_alliance_of(s, kk.key::int)), sight - 1);
        seen := seen || jsonb_build_object(kk.key, (
            select coalesce(jsonb_agg(v order by v), '[]'::jsonb) from (
                select distinct v from (
                    select (x)::int as v from jsonb_array_elements_text(coalesce(seen->kk.key, '[]'::jsonb)) x
                    union select unnest(vis)) z) d));
    end loop;
    return jsonb_set(s, '{seen}', seen);
end $$;

create or replace function public._kr_process(s jsonb)
returns jsonb language plpgsql volatile as $$
begin
    return public._kr_remember(public._kr_process_core(s));
end $$;

create or replace function public._g_kralliklar_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare s jsonb := public._kr_new_core(m, players, n);
begin
    if s is null then return null; end if;
    return public._kr_remember(s);
end $$;

-- What only this kingdom sees: own (and allies') tiles plus one ring with full detail, own stock,
-- orders, armies, mail, offers, pacts, reports; plus remembered tiles with terrain only.
create or replace function public._g_kralliklar_view(s jsonb, u uuid)
returns jsonb language plpgsql immutable as $$
declare me int := public._kr_k(s, u::text); ks int[]; vis int[]; mem int[]; bal jsonb := s->'bal';
begin
    if me is null then return '{}'::jsonb; end if;
    ks := array[me] || public._kr_alliance_of(s, me);
    vis := case when (s->'k'->me::text->>'alive')::boolean then public._kr_visible(s, ks) else '{}'::int[] end;
    mem := array(select (x)::int from jsonb_array_elements_text(coalesce(s->'seen'->me::text, '[]'::jsonb)) x
                 where not (x)::int = any(vis) order by 1);
    return jsonb_build_object('k', me, 'alive', s->'k'->me::text->'alive', 'capital', s->'k'->me::text->'cap', 'res', s->'k'->me::text->'res',
        'ready', s->'k'->me::text->'ready', 'allies', to_jsonb(public._kr_alliance_of(s, me)),
        'tiles', (select coalesce(jsonb_agg(jsonb_build_object('i', v, 't', s->'tiles'->v->'t', 'o', s->'tiles'->v->'o', 'x', s->'tiles'->v->'x',
                       'b', s->'tiles'->v->'b', 'l', s->'tiles'->v->'l',
                       'cap', exists (select 1 from jsonb_each(s->'k') kk where (kk.value->>'alive')::boolean and (kk.value->>'cap')::int = v))
                       order by v), '[]'::jsonb) from unnest(vis) v),
        'explored', (select coalesce(jsonb_agg(jsonb_build_object('i', v, 't', s->'tiles'->v->'t', 'seen', true) order by v), '[]'::jsonb) from unnest(mem) v),
        'orders', coalesce(s->'orders'->me::text, '{"moves":[],"builds":[]}'::jsonb),
        'armies', coalesce((select jsonb_agg(a) from jsonb_array_elements(s->'armies') a where (a->>'k')::int = me), '[]'::jsonb),
        'inbox', coalesce(s->'inbox'->me::text, '[]'::jsonb), 'sent', coalesce(s->'sent'->me::text, '[]'::jsonb),
        'offers', coalesce((select jsonb_agg(o) from jsonb_array_elements(s->'offers') o where (o->>'from')::int = me or (o->>'to')::int = me), '[]'::jsonb),
        'pacts', coalesce((select jsonb_agg(p) from jsonb_array_elements(s->'pacts') p where (p->>'a')::int = me or (p->>'b')::int = me), '[]'::jsonb),
        'events', coalesce(s->'events'->me::text, '[]'::jsonb),
        'balance', jsonb_build_object('buildings', bal->'buildings', 'resource', bal->'resource', 'speed', bal->'speed',
                       'defense_bonus', bal->'defense_bonus', 'capital_bonus', bal->'capital_bonus', 'capital_income', bal->'capital_income'));
end $$;

-- The public part must never carry the memory (it is per kingdom): _g_kralliklar_public builds its
-- object field by field and does not include "seen"; nothing to change there.

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
               and p.proname in ('_kr_remember', '_kr_expand', '_kr_process', '_kr_process_core', '_g_kralliklar_new', '_kr_new_core', '_g_kralliklar_view') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;
