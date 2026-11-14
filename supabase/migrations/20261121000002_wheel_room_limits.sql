-- =====================================================================
-- LOOPA — the game wheel keeps the room's player limit valid for the drawn game.
-- Before: the wheel switched e.g. a 10-player room to a 2-4 player game and the host's next
-- save failed with INVALID_SETTINGS. Now the limit is clamped to what the game accepts
-- (never below the players already in the room). Nothing else changes.
-- Rollback: supabase/rollback/20261121000002_wheel_room_limits_down.sql
-- =====================================================================
create or replace function public.spin_game_wheel(p_room_id uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); cands jsonb; total numeric; pick numeric; acc numeric := 0; c jsonb; res text; spin uuid;
        n int; gm public.game_modes; cur int; maxp int;
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
    -- the room's player limit must be valid for the drawn game, or the host could not save the setup
    select count(*) into n from public.room_players where room_id = p_room_id and status = 'active' and role = 'player';
    select * into gm from public.game_modes where id = res;
    cur := coalesce((r.settings->>'max_players')::int, 10);
    if res in ('isim_sehir','gizli_kelime','kelime_zinciri','bilgi_arenasi','iki_dogru_bir_yalan','ciz_tahmin',
               'en_muhtemel_kim','anlat_bakalim','cizim_telefonu','sessiz_sinema') then
        maxp := case when cur in (4, 6, 10) and cur >= n then cur else (select min(x) from unnest(array[4, 6, 10]) x where x >= n) end;
    else
        maxp := least(gm.max_players, greatest(cur, n));
    end if;
    update public.rooms set settings = settings || jsonb_build_object('game_mode', res, 'configured', false, 'mode_options', '{}'::jsonb,
                                                                      'max_players', coalesce(maxp, cur)),
           updated_at = now() where id = p_room_id;
    update public.room_players set is_ready = false where room_id = p_room_id and user_id <> r.host_id and status = 'active';
    perform public._bump(p_room_id);
    return jsonb_build_object('spin_id', spin, 'result', res, 'candidates', cands);
end $$;

revoke all on function public.spin_game_wheel(uuid) from public, anon;
grant execute on function public.spin_game_wheel(uuid) to authenticated;
