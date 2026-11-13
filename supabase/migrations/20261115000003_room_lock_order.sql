-- =====================================================================
-- LOOPA 0.5.0 — fix: lock order of room functions (deadlock)
--
-- set_ready, update_room_settings, start_match, kick_player … lock the room row
-- first and then player rows. room_heartbeat, leave_room and _leave_all_rooms
-- did the opposite (own player row first, then the room row via _ensure_host).
-- Two such calls at the same moment (e.g. a heartbeat and "Hazır" by the same
-- player) could deadlock; PostgreSQL then aborted one of them. Found by the
-- multi-client E2E test. All three now take the room lock first.
-- Bodies are otherwise unchanged.
-- =====================================================================

create or replace function public.room_heartbeat(p_room_id uuid)
returns timestamptz language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); was_offline boolean;
begin
    perform 1 from public.rooms where id = p_room_id for update;      -- new: room first
    select last_seen_at < now() - interval '25 seconds' into was_offline
      from public.room_players where room_id = p_room_id and user_id = u and status = 'active';
    if was_offline is null then raise exception 'LOOPA:NOT_IN_ROOM'; end if;
    update public.room_players set last_seen_at = now() where room_id = p_room_id and user_id = u;
    if public._ensure_host(p_room_id) or was_offline then
        perform public._bump(p_room_id);   -- connection status changed
    end if;
    return now();
end $$;

create or replace function public.leave_room(p_room_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    perform 1 from public.rooms where id = p_room_id for update;      -- new: room first
    update public.room_players set status = 'left', is_ready = false
     where room_id = p_room_id and user_id = u and status = 'active';
    if found then
        perform public._ensure_host(p_room_id);
        perform public._bump(p_room_id);
    end if;
end $$;

create or replace function public._leave_all_rooms(p_user uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare rid uuid;
begin
    for rid in select room_id from public.room_players where user_id = p_user and status = 'active' order by room_id loop
        perform 1 from public.rooms where id = rid for update;          -- new: room first
        update public.room_players set status = 'left', is_ready = false where room_id = rid and user_id = p_user;
        perform public._ensure_host(rid);
        perform public._bump(rid);
    end loop;
end $$;

revoke all on function public.room_heartbeat(uuid) from public, anon;
grant execute on function public.room_heartbeat(uuid) to authenticated;
revoke all on function public.leave_room(uuid) from public, anon;
grant execute on function public.leave_room(uuid) to authenticated;
revoke all on function public._leave_all_rooms(uuid) from public, anon, authenticated;
