-- =====================================================================
-- LOOPA 0.8.0 — tournaments
--
-- Formats
--   duel: single-elimination bracket of players (byes when not a power of two)
--   team: single-elimination bracket of persistent teams (teams table; every member plays)
--   ffa : everybody in one room; after each match the last place is eliminated
-- Lifecycle: registration -> running (rounds) -> finished (champion) | cancelled.
-- Every tournament match is a normal LOOPA room created by the server, locked to the
-- tournament's game and options. Results are read from the server's own match records when the
-- match's rewards are processed; nobody submits a result. A match that was not played by exactly
-- the expected line-up does not count and has to be replayed. A drawn knockout match is replayed.
-- A no-show can be eliminated by the organiser after the waiting time (forfeit).
-- Rewards (champion / finalist) are modest, configured in economy_config.tournament_rewards and
-- granted once, only when: enough distinct participants, the final was actually played (not a
-- forfeit), the player is within the daily cap of rewarded tournaments, and the same set of
-- participants has not already produced a rewarded tournament in the last 24 hours.
-- =====================================================================

create table if not exists public.tournaments (
    id               uuid primary key default gen_random_uuid(),
    name             text not null check (char_length(name) between 3 and 40),
    game_mode        text not null references public.game_modes(id),
    format           text not null check (format in ('duel','team','ffa')),
    team_size        int  not null default 1 check (team_size between 1 and 5),
    max_entries      int  not null check (max_entries between 2 and 32),
    mode_options     jsonb not null default '{}'::jsonb,
    host_id          uuid not null references public.profiles(id) on delete cascade,
    status           text not null default 'registration' check (status in ('registration','running','finished','cancelled')),
    current_round    int  not null default 0,
    champion_entry   uuid,
    rewards_granted  boolean not null default false,   -- reward step done (once)
    rewarded         boolean not null default false,   -- rewards were actually paid out
    participants_key text,                             -- md5 of the sorted participant ids (anti-farming)
    created_at       timestamptz not null default now(),
    started_at       timestamptz,
    finished_at      timestamptz
);
alter table public.tournaments add column if not exists rewarded boolean not null default false;
alter table public.tournaments add column if not exists participants_key text;

create table if not exists public.tournament_entries (
    id            uuid primary key default gen_random_uuid(),
    tournament_id uuid not null references public.tournaments(id) on delete cascade,
    user_ids      uuid[] not null check (cardinality(user_ids) between 1 and 5),
    team_id       uuid references public.teams(id) on delete set null,
    name          text not null,
    seed          int,
    status        text not null default 'active' check (status in ('active','eliminated','champion','withdrawn')),
    eliminated_round int,
    created_at    timestamptz not null default now()
);
create index if not exists tournament_entries_t_idx on public.tournament_entries(tournament_id);
create index if not exists tournament_entries_users_idx on public.tournament_entries using gin(user_ids);
create table if not exists public.tournament_matches (
    id            uuid primary key default gen_random_uuid(),
    tournament_id uuid not null references public.tournaments(id) on delete cascade,
    round         int  not null,
    slot          int  not null,
    entries       uuid[] not null,                -- 2 for knockouts, all active for ffa
    room_id       uuid references public.rooms(id) on delete set null,
    match_id      uuid references public.matches(id) on delete set null,
    status        text not null default 'waiting' check (status in ('waiting','playing','done','bye','forfeit')),
    winner_entry  uuid,
    eliminated_entry uuid,
    played        boolean not null default false,   -- decided by a real match (not a bye / forfeit)
    created_at    timestamptz not null default now(),
    finished_at   timestamptz,
    unique (tournament_id, round, slot)
);

alter table public.tournaments        enable row level security;
alter table public.tournament_entries enable row level security;
alter table public.tournament_matches enable row level security;
drop policy if exists tournaments_read on public.tournaments;
create policy tournaments_read on public.tournaments for select to authenticated using (true);
drop policy if exists tournament_entries_read on public.tournament_entries;
create policy tournament_entries_read on public.tournament_entries for select to authenticated using (true);
drop policy if exists tournament_matches_read on public.tournament_matches;
create policy tournament_matches_read on public.tournament_matches for select to authenticated using (true);
revoke all on public.tournaments, public.tournament_entries, public.tournament_matches from anon, authenticated;
grant select on public.tournaments, public.tournament_entries, public.tournament_matches to authenticated;

-- ---------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------
create or replace function public._tm_entry_users(p_entry uuid)
returns uuid[] language sql stable security definer set search_path = public, pg_temp as $$
    select user_ids from public.tournament_entries where id = p_entry;
$$;

create or replace function public._tm_expected_users(p_tm uuid)
returns uuid[] language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce(array_agg(u order by u), '{}') from public.tournament_matches tm, unnest(tm.entries) e,
           unnest(public._tm_entry_users(e)) u where tm.id = p_tm;
$$;

-- A server-made room for one tournament match (the first player of the first entry is host).
create or replace function public._tm_make_room(p_tm uuid)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare tm public.tournament_matches; t public.tournaments; code text; rid uuid; st jsonb; users uuid[]; teams jsonb := '[]'::jsonb; e uuid; i int := 0;
begin
    select * into tm from public.tournament_matches where id = p_tm;
    select * into t from public.tournaments where id = tm.tournament_id;
    users := public._tm_expected_users(p_tm);
    if t.format = 'team' then
        foreach e in array tm.entries loop
            teams := teams || jsonb_build_array(jsonb_build_object('members', to_jsonb(public._tm_entry_users(e)), 'strength', 0));
        end loop;
    end if;
    st := public._default_settings() || jsonb_build_object('game_mode', t.game_mode, 'mode_options', t.mode_options, 'configured', true,
          'max_players', greatest(cardinality(users), 2), 'allow_spectators', true, 'tournament_id', t.id, 'tournament_match_id', p_tm)
          || case when t.format = 'team' then jsonb_build_object('teams', teams) else '{}'::jsonb end;
    loop
        i := i + 1;
        code := public._gen_room_code();
        begin
            insert into public.rooms(code, host_id, is_public, settings) values (code, (public._tm_entry_users(tm.entries[1]))[1], false, st)
            returning id into rid;
            exit;
        exception when unique_violation then
            if i > 10 then raise exception 'LOOPA:CODE_GENERATION_FAILED'; end if;
        end;
    end loop;
    insert into public.room_signals(room_id) values (rid) on conflict do nothing;
    update public.tournament_matches set room_id = rid, status = 'playing' where id = p_tm;
    return rid;
end $$;

-- start_match hook: the room must hold exactly the expected players.
create or replace function public._tm_check_start(p_room uuid)
returns void language plpgsql stable security definer set search_path = public, pg_temp as $$
declare r public.rooms; present uuid[];
begin
    select * into r from public.rooms where id = p_room;
    select coalesce(array_agg(user_id order by user_id), '{}') into present
      from public.room_players where room_id = p_room and status = 'active' and role = 'player';
    if present <> public._tm_expected_users((r.settings->>'tournament_match_id')::uuid) then
        raise exception 'LOOPA:TOURNAMENT_LINEUP';
    end if;
end $$;

create or replace function public._tm_pair_round(p_t uuid, p_round int, p_entries uuid[])
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare n int := cardinality(p_entries); size int := 1; i int; slot int := 0; a uuid; b uuid; tm uuid;
begin
    while size < n loop size := size * 2; end loop;
    -- seeds 1..n in order; the top seeds get the byes
    for i in 1..size / 2 loop
        a := p_entries[i]; b := case when size + 1 - i <= n then p_entries[size + 1 - i] end;
        slot := slot + 1;
        if b is null then
            insert into public.tournament_matches(tournament_id, round, slot, entries, status, winner_entry, finished_at)
            values (p_t, p_round, slot, array[a], 'bye', a, now());
        else
            insert into public.tournament_matches(tournament_id, round, slot, entries) values (p_t, p_round, slot, array[a, b]) returning id into tm;
            perform public._tm_make_room(tm);
        end if;
    end loop;
end $$;

create or replace function public._tm_grant(p_t uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare t public.tournaments; cfg jsonb := public._cfg('tournament_rewards'); fin public.tournament_matches; u uuid; ent public.tournament_entries;
        n_users int; today int; role text; r jsonb; paid boolean := false;
begin
    select * into t from public.tournaments where id = p_t for update;
    if t.rewards_granted or t.status <> 'finished' then return; end if;
    update public.tournaments set rewards_granted = true where id = p_t;
    select count(distinct u2) into n_users from public.tournament_entries e, unnest(e.user_ids) u2 where e.tournament_id = p_t;
    if n_users < coalesce((cfg->>'min_participants')::int, 4) then return; end if;
    select * into fin from public.tournament_matches where tournament_id = p_t and status in ('done','forfeit') order by round desc, slot limit 1;
    if not found or not fin.played then return; end if;                       -- no rewards for a forfeit / bye final
    -- the same group already produced a paid tournament in the last 24 hours: nothing again
    if exists (select 1 from public.tournaments t2 where t2.id <> p_t and t2.rewarded and t2.participants_key = t.participants_key
                and t2.finished_at > now() - interval '24 hours') then
        return;
    end if;
    for ent in select * from public.tournament_entries where tournament_id = p_t
                and (status = 'champion' or id = any(fin.entries)) loop
        role := case when ent.status = 'champion' then 'champion' else 'finalist' end;
        r := cfg->role;
        foreach u in array ent.user_ids loop
            select count(*) into today from public.reputation_events
             where user_id = u and reason in ('tournament_champion','tournament_finalist') and created_at >= public._day_start();
            continue when today >= coalesce((cfg->>'daily_rewarded_tournaments')::int, 2);
            perform public._coins_grant(u, coalesce((r->>'coins')::int, 0), 'tournament', 'tournament:' || p_t);
            perform public._award_xp(u, coalesce((r->>'xp')::int, 0), 'tournament', 'tournament:' || p_t, null);
            perform public._reputation_add(u, coalesce((r->>'reputation')::int, 1), 'tournament_' || role, 'tournament:' || p_t);
            perform public._check_badges(u);
            paid := true;
        end loop;
    end loop;
    if paid then update public.tournaments set rewarded = true where id = p_t; end if;
end $$;

create or replace function public._tm_finish(p_t uuid, p_champion uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
begin
    update public.tournament_entries set status = 'champion' where id = p_champion;
    update public.tournaments set status = 'finished', champion_entry = p_champion, finished_at = now() where id = p_t;
    perform public._tm_grant(p_t);
end $$;

-- After a decided match: next round when the current one is complete.
create or replace function public._tm_progress(p_t uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare t public.tournaments; alive uuid[]; open_n int; winners uuid[]; nm uuid;
begin
    select * into t from public.tournaments where id = p_t for update;
    if t.status <> 'running' then return; end if;
    select count(*) into open_n from public.tournament_matches where tournament_id = p_t and round = t.current_round and status in ('waiting','playing');
    if open_n > 0 then return; end if;
    select array_agg(id order by seed) into alive from public.tournament_entries where tournament_id = p_t and status = 'active';
    if cardinality(alive) <= 1 then
        perform public._tm_finish(p_t, alive[1]);
        return;
    end if;
    update public.tournaments set current_round = current_round + 1 where id = p_t;
    if t.format = 'ffa' then
        insert into public.tournament_matches(tournament_id, round, slot, entries) values (p_t, t.current_round + 1, 1, alive) returning id into nm;
        perform public._tm_make_room(nm);
    else
        -- winners in bracket order
        select array_agg(winner_entry order by slot) into winners from public.tournament_matches
         where tournament_id = p_t and round = t.current_round;
        perform public._tm_pair_round_ordered(p_t, t.current_round + 1, winners);
    end if;
end $$;

-- Later rounds: neighbours in the bracket meet (1v2, 3v4, ...)
create or replace function public._tm_pair_round_ordered(p_t uuid, p_round int, p_entries uuid[])
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare i int := 1; slot int := 0; tm uuid;
begin
    while i <= cardinality(p_entries) loop
        slot := slot + 1;
        if i + 1 > cardinality(p_entries) then
            insert into public.tournament_matches(tournament_id, round, slot, entries, status, winner_entry, finished_at)
            values (p_t, p_round, slot, array[p_entries[i]], 'bye', p_entries[i], now());
        else
            insert into public.tournament_matches(tournament_id, round, slot, entries) values (p_t, p_round, slot, array[p_entries[i], p_entries[i + 1]])
            returning id into tm;
            perform public._tm_make_room(tm);
        end if;
        i := i + 2;
    end loop;
end $$;

-- Reads the result of a finished match in a tournament room.
create or replace function public._tm_on_match(p_match uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare m public.matches; r public.rooms; tm public.tournament_matches; t public.tournaments; players uuid[]; e uuid; win uuid; lose uuid;
        best_rank int; n_first int; worst uuid;
begin
    select * into m from public.matches where id = p_match;
    select * into r from public.rooms where id = m.room_id;
    if not (r.settings ? 'tournament_match_id') then return; end if;
    select * into tm from public.tournament_matches where id = (r.settings->>'tournament_match_id')::uuid for update;
    if not found or tm.status <> 'playing' then return; end if;
    select * into t from public.tournaments where id = tm.tournament_id;
    select array_agg(user_id order by user_id) into players from public.match_players where match_id = p_match;
    -- wrong line-up or somebody left before the end: not a result (replay)
    if players <> public._tm_expected_users(tm.id)
       or exists (select 1 from public.match_players mp join public.room_players rp on rp.room_id = mp.room_id and rp.user_id = mp.user_id
                   where mp.match_id = p_match and rp.status <> 'active') then
        return;
    end if;
    if t.format = 'ffa' then
        -- last place: worst rank, then fewest points
        select e2 into worst from unnest(tm.entries) e2
          join public.match_players mp on mp.match_id = p_match and mp.user_id = (public._tm_entry_users(e2))[1]
         order by mp.final_rank desc nulls first, mp.total_points asc limit 1;
        update public.tournament_entries set status = 'eliminated', eliminated_round = tm.round where id = worst;
        update public.tournament_matches set status = 'done', match_id = p_match, eliminated_entry = worst, played = true, finished_at = now() where id = tm.id;
    else
        -- the entry whose players all hold first place, alone
        win := null;
        foreach e in array tm.entries loop
            if not exists (select 1 from public.match_players mp where mp.match_id = p_match and mp.user_id = any(public._tm_entry_users(e)) and mp.final_rank <> 1)
               and not exists (select 1 from public.match_players mp where mp.match_id = p_match and not (mp.user_id = any(public._tm_entry_users(e))) and mp.final_rank = 1) then
                win := e;
            end if;
        end loop;
        if win is null then return; end if;                        -- draw: play again in the same room
        lose := (select x from unnest(tm.entries) x where x <> win limit 1);
        update public.tournament_entries set status = 'eliminated', eliminated_round = tm.round where id = lose;
        update public.tournament_matches set status = 'done', match_id = p_match, winner_entry = win, eliminated_entry = lose, played = true, finished_at = now()
         where id = tm.id;
    end if;
    update public.rooms set status = 'closed', closed_at = now() where id = r.id;
    perform public._tm_progress(tm.tournament_id);
end $$;

create or replace function public._tm_trigger()
returns trigger language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if new.rewards_processed and not old.rewards_processed then perform public._tm_on_match(new.id); end if;
    return new;
end $$;
drop trigger if exists matches_tournament on public.matches;
create trigger matches_tournament after update of rewards_processed on public.matches
    for each row execute function public._tm_trigger();

-- _ensure_host, extended (body otherwise unchanged): in tournament rooms only the expected
-- line-up can become host, and the room is never closed automatically (the tournament owns it).
create or replace function public._ensure_host(p_room_id uuid)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms; host_ok boolean; candidate uuid; expected uuid[];
begin
    select * into r from public.rooms where id = p_room_id for update;
    if not found or r.status = 'closed' then return false; end if;
    select exists (select 1 from public.room_players
                    where room_id = p_room_id and user_id = r.host_id and status = 'active'
                      and last_seen_at > now() - interval '30 seconds') into host_ok;
    if host_ok then return false; end if;
    if r.settings ? 'tournament_match_id' then
        expected := public._tm_expected_users((r.settings->>'tournament_match_id')::uuid);
    end if;
    select user_id into candidate from public.room_players
     where room_id = p_room_id and status = 'active' and user_id <> r.host_id
       and last_seen_at > now() - interval '30 seconds'
       and (expected is null or user_id = any(expected))
     order by (role = 'player') desc, joined_at asc limit 1;
    if candidate is null then
        if expected is not null then return false; end if;
        -- nobody connected; close the room if nobody is active at all
        if not exists (select 1 from public.room_players where room_id = p_room_id and status = 'active') then
            update public.rooms set status = 'closed', closed_at = now(), updated_at = now() where id = p_room_id;
            update public.matches set status = 'abandoned', finished_at = now()
             where room_id = p_room_id and status = 'in_progress';
            perform public._bump(p_room_id);
            return true;
        end if;
        return false;
    end if;
    update public.rooms set host_id = candidate, updated_at = now() where id = p_room_id;
    update public.room_players set role = 'player'
     where room_id = p_room_id and user_id = candidate and r.status = 'lobby';
    perform public._bump(p_room_id);
    return true;
end $$;

-- join_room, extended (body otherwise unchanged): outsiders in tournament rooms are spectators.
create or replace function public.join_room(p_code text, p_as_spectator boolean default false)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare
    u uuid := public._uid();
    r public.rooms;
    existing public.room_players;
    v_role text;
    n_players int; n_specs int;
    v_max int;
    was_participant boolean := false;
begin
    perform public._require_profile(u);
    select * into r from public.rooms where code = upper(btrim(p_code)) and status <> 'closed' for update;
    if not found then raise exception 'LOOPA:ROOM_NOT_FOUND'; end if;
    -- tournament rooms: only the expected line-up plays; anybody else may only watch
    if r.settings ? 'tournament_match_id' and not (u = any(public._tm_expected_users((r.settings->>'tournament_match_id')::uuid))) then
        p_as_spectator := true;
    end if;

    select * into existing from public.room_players where room_id = r.id and user_id = u;
    if found and existing.status = 'kicked' then raise exception 'LOOPA:KICKED'; end if;
    if found and existing.status = 'active' then
        update public.room_players set last_seen_at = now() where room_id = r.id and user_id = u;
        perform public._bump(r.id);
        return jsonb_build_object('id', r.id, 'code', r.code);
    end if;

    -- leave other rooms
    for existing in select * from public.room_players where user_id = u and status = 'active' and room_id <> r.id loop
        update public.room_players set status = 'left', is_ready = false where room_id = existing.room_id and user_id = u;
        perform public._bump(existing.room_id);
    end loop;

    v_max := coalesce((r.settings->>'max_players')::int, 10);
    select count(*) filter (where role = 'player'), count(*) filter (where role = 'spectator')
      into n_players, n_specs
      from public.room_players where room_id = r.id and status = 'active';

    if r.status = 'in_game' then
        select exists (select 1 from public.match_players where match_id = r.current_match_id and user_id = u)
          into was_participant;
    end if;

    if was_participant then
        v_role := 'player';                         -- reconnecting participant
    elsif coalesce(p_as_spectator, false) or r.status = 'in_game' then
        if not coalesce((r.settings->>'allow_spectators')::boolean, true) then
            raise exception 'LOOPA:GAME_IN_PROGRESS';
        end if;
        if n_specs >= 10 then raise exception 'LOOPA:ROOM_FULL'; end if;
        v_role := 'spectator';
    else
        if n_players >= v_max then raise exception 'LOOPA:ROOM_FULL'; end if;
        v_role := 'player';
    end if;

    insert into public.room_players(room_id, user_id, role, status, is_ready, joined_at, last_seen_at)
    values (r.id, u, v_role, 'active', false, now(), now())
    on conflict (room_id, user_id) do update
        set role = excluded.role, status = 'active', is_ready = false,
            joined_at = case when room_players.status = 'active' then room_players.joined_at else now() end,
            last_seen_at = now();
    perform public._ensure_host(r.id);
    perform public._bump(r.id);
    return jsonb_build_object('id', r.id, 'code', r.code);
end $$;


-- ---------------------------------------------------------------------
-- Client RPCs
-- ---------------------------------------------------------------------
create or replace function public.create_tournament(p_name text, p_mode text, p_format text, p_max_entries int, p_mode_options jsonb default '{}'::jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); gm public.game_modes; acc jsonb; t public.tournaments; nm text := regexp_replace(btrim(coalesce(p_name, '')), '\s+', ' ', 'g');
        tsize int := 1; opts jsonb;
begin
    perform public._require_profile(u);
    if char_length(nm) not between 3 and 40 then raise exception 'LOOPA:INVALID_INPUT'; end if;
    select * into gm from public.game_modes where id = p_mode and status in ('available','early_access');
    if not found then raise exception 'LOOPA:MODE_NOT_AVAILABLE'; end if;
    acc := public._mode_access(u, p_mode);
    if not (acc->>'allowed')::boolean then raise exception 'LOOPA:%', acc->>'reason'; end if;
    if p_format not in ('duel','team','ffa') or p_max_entries not between 2 and 32 then raise exception 'LOOPA:INVALID_INPUT'; end if;
    opts := public._mode_options(p_mode, p_mode_options, gm.max_players);
    if p_format = 'duel' and gm.min_players > 2 then raise exception 'LOOPA:FORMAT_NOT_SUPPORTED'; end if;
    if p_format = 'team' then
        if p_mode = 'amiral_batti' then opts := opts || '{"format":"2v2"}'; tsize := 2;
        elsif 'team' = any(gm.tags) then tsize := 2;
        else raise exception 'LOOPA:FORMAT_NOT_SUPPORTED'; end if;
        if tsize * 2 < gm.min_players or tsize * 2 > gm.max_players then raise exception 'LOOPA:FORMAT_NOT_SUPPORTED'; end if;
    end if;
    if p_format = 'ffa' and (p_max_entries < 3 or p_max_entries > gm.max_players) then raise exception 'LOOPA:FORMAT_NOT_SUPPORTED'; end if;
    if (select count(*) from public.tournaments where host_id = u and status in ('registration','running')) >= 3 then raise exception 'LOOPA:RATE_LIMITED'; end if;
    insert into public.tournaments(name, game_mode, format, team_size, max_entries, mode_options, host_id)
    values (nm, p_mode, p_format, tsize, p_max_entries, opts, u) returning * into t;
    return to_jsonb(t);
end $$;

create or replace function public.join_tournament(p_t uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); t public.tournaments; team uuid; members uuid[]; nm text; e public.tournament_entries;
begin
    perform public._require_profile(u);
    select * into t from public.tournaments where id = p_t for update;
    if not found then raise exception 'LOOPA:TOURNAMENT_NOT_FOUND'; end if;
    if t.status <> 'registration' then raise exception 'LOOPA:REGISTRATION_CLOSED'; end if;
    if (select count(*) from public.tournament_entries where tournament_id = p_t and status <> 'withdrawn') >= t.max_entries then raise exception 'LOOPA:TOURNAMENT_FULL'; end if;
    if t.format = 'team' then
        team := public._active_team(u);
        if team is null then raise exception 'LOOPA:TEAM_REQUIRED'; end if;
        select array_agg(user_id order by joined_at) into members from public.team_members where team_id = team;
        if cardinality(members) <> t.team_size then raise exception 'LOOPA:TEAM_SIZE'; end if;
        select name into nm from public.teams where id = team;
    else
        members := array[u];
        select username into nm from public.profiles where id = u;
    end if;
    -- nobody twice (also not via two teams)
    if exists (select 1 from public.tournament_entries where tournament_id = p_t and status <> 'withdrawn' and user_ids && members) then
        raise exception 'LOOPA:ALREADY_REGISTERED';
    end if;
    insert into public.tournament_entries(tournament_id, user_ids, team_id, name) values (p_t, members, team, nm) returning * into e;
    return to_jsonb(e);
end $$;

create or replace function public.leave_tournament(p_t uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    update public.tournament_entries set status = 'withdrawn'
     where tournament_id = p_t and u = any(user_ids) and status = 'active'
       and exists (select 1 from public.tournaments where id = p_t and status = 'registration');
    if not found then raise exception 'LOOPA:REGISTRATION_CLOSED'; end if;
end $$;

create or replace function public.start_tournament(p_t uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); t public.tournaments; ids uuid[]; n int; nm uuid; i int;
begin
    select * into t from public.tournaments where id = p_t for update;
    if not found then raise exception 'LOOPA:TOURNAMENT_NOT_FOUND'; end if;
    if t.host_id <> u then raise exception 'LOOPA:NOT_HOST'; end if;
    if t.status <> 'registration' then raise exception 'LOOPA:REGISTRATION_CLOSED'; end if;
    select array_agg(id order by random()) into ids from public.tournament_entries where tournament_id = p_t and status = 'active';
    n := coalesce(cardinality(ids), 0);
    if n < (case when t.format = 'ffa' then 3 else 2 end) then raise exception 'LOOPA:NOT_ENOUGH_PLAYERS'; end if;
    for i in 1..n loop update public.tournament_entries set seed = i where id = ids[i]; end loop;
    update public.tournaments set status = 'running', current_round = 1, started_at = now(),
           participants_key = (select md5(string_agg(u2::text, ',' order by u2)) from public.tournament_entries e, unnest(e.user_ids) u2
                                where e.tournament_id = p_t and e.status = 'active')
     where id = p_t;
    if t.format = 'ffa' then
        insert into public.tournament_matches(tournament_id, round, slot, entries) values (p_t, 1, 1, ids) returning id into nm;
        perform public._tm_make_room(nm);
    else
        perform public._tm_pair_round(p_t, 1, ids);
        perform public._tm_progress(p_t);           -- a round of byes only
    end if;
    return public.get_tournament(p_t);
end $$;

create or replace function public.cancel_tournament(p_t uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    update public.tournaments set status = 'cancelled', finished_at = now()
     where id = p_t and host_id = u and status in ('registration','running');
    if not found then raise exception 'LOOPA:NOT_HOST'; end if;
    update public.rooms set status = 'closed', closed_at = now()
     where id in (select room_id from public.tournament_matches where tournament_id = p_t and status = 'playing');
end $$;

-- Organiser: an entry did not show up within 10 minutes -> it is eliminated (no rewards come from such a final).
create or replace function public.tournament_forfeit(p_tm uuid, p_absent_entry uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); tm public.tournament_matches; t public.tournaments; win uuid;
begin
    select * into tm from public.tournament_matches where id = p_tm for update;
    if not found then raise exception 'LOOPA:TOURNAMENT_NOT_FOUND'; end if;
    select * into t from public.tournaments where id = tm.tournament_id;
    if t.host_id <> u then raise exception 'LOOPA:NOT_HOST'; end if;
    if tm.status <> 'playing' or not (p_absent_entry = any(tm.entries)) then raise exception 'LOOPA:INVALID_INPUT'; end if;
    if tm.created_at > now() - interval '10 minutes' then raise exception 'LOOPA:TOO_EARLY'; end if;
    if exists (select 1 from public.rooms where id = tm.room_id and status = 'in_game') then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    update public.tournament_entries set status = 'eliminated', eliminated_round = tm.round where id = p_absent_entry;
    if t.format = 'ffa' then
        update public.tournament_matches set status = 'forfeit', eliminated_entry = p_absent_entry, finished_at = now() where id = p_tm;
    else
        win := (select x from unnest(tm.entries) x where x <> p_absent_entry limit 1);
        update public.tournament_matches set status = 'forfeit', winner_entry = win, eliminated_entry = p_absent_entry, finished_at = now() where id = p_tm;
    end if;
    update public.rooms set status = 'closed', closed_at = now() where id = tm.room_id;
    perform public._tm_progress(t.id);
end $$;

-- Joining the room of my current tournament match.
create or replace function public.join_tournament_match(p_tm uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); tm public.tournament_matches; code text;
begin
    select * into tm from public.tournament_matches where id = p_tm;
    if not found or tm.status <> 'playing' then raise exception 'LOOPA:TOURNAMENT_NOT_FOUND'; end if;
    if not (u = any(public._tm_expected_users(p_tm))) then raise exception 'LOOPA:NOT_A_PARTICIPANT'; end if;
    select r.code into code from public.rooms r where r.id = tm.room_id and r.status <> 'closed';
    if code is null then raise exception 'LOOPA:ROOM_NOT_FOUND'; end if;
    return public.join_room(code, false);
end $$;

create or replace function public.get_tournament(p_t uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); t public.tournaments;
begin
    select * into t from public.tournaments where id = p_t;
    if not found then raise exception 'LOOPA:TOURNAMENT_NOT_FOUND'; end if;
    return to_jsonb(t) || jsonb_build_object(
        'host', public._user_card(t.host_id),
        'entries', coalesce((select jsonb_agg(jsonb_build_object('id', e.id, 'name', e.name, 'seed', e.seed, 'status', e.status,
                        'eliminated_round', e.eliminated_round, 'players', (select jsonb_agg(public._user_card(x)) from unnest(e.user_ids) x),
                        'mine', u = any(e.user_ids)) order by e.seed nulls last, e.created_at)
                    from public.tournament_entries e where e.tournament_id = p_t and e.status <> 'withdrawn'), '[]'::jsonb),
        'matches', coalesce((select jsonb_agg(jsonb_build_object('id', m.id, 'round', m.round, 'slot', m.slot, 'entries', to_jsonb(m.entries),
                        'status', m.status, 'winner_entry', m.winner_entry, 'eliminated_entry', m.eliminated_entry,
                        'room_code', case when u = any(public._tm_expected_users(m.id)) or t.host_id = u then (select code from public.rooms where id = m.room_id) end,
                        'mine', u = any(public._tm_expected_users(m.id))) order by m.round, m.slot)
                    from public.tournament_matches m where m.tournament_id = p_t), '[]'::jsonb));
end $$;

create or replace function public.list_tournaments(p_status text default null)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce(jsonb_agg(to_jsonb(t) || jsonb_build_object('entries_count',
               (select count(*) from public.tournament_entries e where e.tournament_id = t.id and e.status <> 'withdrawn'),
               'joined', exists (select 1 from public.tournament_entries e where e.tournament_id = t.id and public._uid() = any(e.user_ids) and e.status <> 'withdrawn'))
           order by t.created_at desc), '[]'::jsonb)
      from (select * from public.tournaments
             where (p_status is null and status in ('registration','running')) or status = p_status
             order by created_at desc limit 50) t;
$$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_tm\_%' or p.proname in ('create_tournament','join_tournament','leave_tournament','start_tournament',
                    'cancel_tournament','tournament_forfeit','join_tournament_match','get_tournament','list_tournaments','join_room','_ensure_host')) loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
        if left(f.proname, 1) <> '_' then execute format('grant execute on function %s to authenticated', f.sig); end if;
    end loop;
end $$;
