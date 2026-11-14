-- Rollback of 20261121000002_wheel_room_limits.sql: the 0.8.0 definition (20261120000001_platform.sql).
begin;
create or replace function public.spin_game_wheel(p_room_id uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); cands jsonb; total numeric; pick numeric; acc numeric := 0; c jsonb; res text; spin uuid;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    if (select count(*) from public.room_wheel_spins where room_id = p_room_id and created_at > now() - interval '5 seconds') > 0 then
        raise exception 'LOOPA:RATE_LIMITED';
    end if;
    cands := public._wheel_candidates(p_room_id);
    select coalesce(sum((x->>'weight')::numeric), 0) into total from jsonb_array_elements(cands) x;
    if total <= 0 then raise exception 'LOOPA:NO_SUITABLE_GAME'; end if;
    pick := random() * total;
    for c in select value from jsonb_array_elements(cands) loop
        acc := acc + (c->>'weight')::numeric;
        res := c->>'mode';
        exit when acc >= pick;
    end loop;
    insert into public.room_wheel_spins(room_id, result, candidates) values (p_room_id, res, cands) returning id into spin;
    update public.rooms set settings = settings || jsonb_build_object('game_mode', res, 'configured', false, 'mode_options', '{}'::jsonb),
           updated_at = now() where id = p_room_id;
    update public.room_players set is_ready = false where room_id = p_room_id and user_id <> r.host_id and status = 'active';
    perform public._bump(p_room_id);
    return jsonb_build_object('spin_id', spin, 'result', res, 'candidates', cands);
end $$;
revoke all on function public.spin_game_wheel(uuid) from public, anon;
grant execute on function public.spin_game_wheel(uuid) to authenticated;
delete from supabase_migrations.schema_migrations where version = '20261121000002';
commit;
