-- Rollback of 20261122000002_kralliklar_terrain_seeds.sql: the 0.8.0 definition (20261120000008_kralliklar.sql).
begin;
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
revoke all on function public._kr_gen_map(int, jsonb) from public, anon, authenticated;
delete from supabase_migrations.schema_migrations where version = '20261122000002';
commit;
