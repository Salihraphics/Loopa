-- Rollback of 20261122000001_kralliklar_explored.sql: back to the 0.8.0 definitions (20261120000008_kralliklar.sql).
-- The "seen" key left in running games' state is ignored by the 0.8.0 code.
begin;
drop function public._kr_process(jsonb);
drop function public._g_kralliklar_new(public.matches, uuid[], int);
drop function public._kr_remember(jsonb);
drop function if exists public._kr_expand(jsonb, int[], int);
alter function public._kr_process_core(jsonb) rename to _kr_process;
alter function public._kr_new_core(public.matches, uuid[], int) rename to _g_kralliklar_new;
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
revoke all on function public._g_kralliklar_view(jsonb, uuid) from public, anon, authenticated;
delete from supabase_migrations.schema_migrations where version = '20261122000001';
commit;
