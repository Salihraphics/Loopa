-- =====================================================================
-- LOOPA — server-authoritative game engine
--
-- Every competitive action is an RPC executed as SECURITY DEFINER.
-- Errors are raised as 'LOOPA:<CODE>' so the client can map them to
-- localized messages.
--
-- Round state machine (rounds.phase):
--   answering -> (voting) -> reveal -> results -> next round | match end
-- Transitions happen inside game_tick() (callable by any room member,
-- idempotent, row-locked) or eagerly when the last submission / vote
-- arrives. Deadlines are always evaluated against the database clock.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Pure helpers
-- ---------------------------------------------------------------------
create or replace function public.normalize_answer(p text)
returns text language sql immutable parallel safe as $$
    -- Turkish-aware lower-casing that does not depend on DB locale,
    -- trimming and whitespace collapsing.
    select regexp_replace(
             btrim(translate(coalesce(p, ''),
                 'ABCÇDEFGĞHIİJKLMNOÖPQRSŞTUÜVWXYZÂÎÛâîû',
                 'abcçdefgğhıijklmnoöpqrsştuüvwxyzaiuaiu')),
             '\s+', ' ', 'g');
$$;

-- XP needed to go from level L to L+1 is 100 + 50*(L-1).
create or replace function public.xp_for_level(p_level int)
returns int language sql immutable parallel safe as $$
    select (100 * (greatest(p_level, 1) - 1) + 25 * (greatest(p_level, 1) - 1) * (greatest(p_level, 1) - 2))::int;
$$;

create or replace function public.level_for_xp(p_xp int)
returns int language plpgsql immutable parallel safe as $$
declare l int := 1;
begin
    while public.xp_for_level(l + 1) <= coalesce(p_xp, 0) and l < 999 loop
        l := l + 1;
    end loop;
    return l;
end $$;

create or replace function public._turkish_letters()
returns text[] language sql immutable as $$
    select array['A','B','C','Ç','D','E','F','G','H','I','İ','J','K','L','M',
                 'N','O','Ö','P','R','S','Ş','T','U','Ü','V','Y','Z'];  -- Ğ never starts a word
$$;

create or replace function public._default_settings()
returns jsonb language sql stable set search_path = public, pg_temp as $$
    select jsonb_build_object(
        'round_seconds', 60,
        'rounds', 5,
        'max_players', 10,
        'vote_seconds', 20,
        'allow_spectators', true,
        'excluded_letters', jsonb_build_array('J'),
        'categories', (select jsonb_agg(jsonb_build_object('key', key, 'name', name_tr, 'custom', false) order by sort_order)
                       from public.categories where is_default)
    );
$$;

create or replace function public._uid()
returns uuid language plpgsql stable as $$
declare u uuid := auth.uid();
begin
    if u is null then raise exception 'LOOPA:NOT_AUTHENTICATED'; end if;
    return u;
end $$;

create or replace function public._bump(p_room_id uuid)
returns void language sql security definer set search_path = public, pg_temp as $$
    insert into public.room_signals(room_id, version, updated_at) values (p_room_id, 1, now())
    on conflict (room_id) do update set version = room_signals.version + 1, updated_at = now();
$$;

-- Weighted joker roll: 40% Ek Süre, 30% Zaman Hırsızı, 20% Yavaşlatma, 10% 2X Puan
create or replace function public._roll_joker()
returns text language plpgsql volatile as $$
declare r int := (get_byte(extensions.gen_random_bytes(1), 0) * 256 + get_byte(extensions.gen_random_bytes(1), 0)) % 100;
begin
    if r < 40 then return 'extra_time';
    elsif r < 70 then return 'time_thief';
    elsif r < 90 then return 'slowdown';
    else return 'double_points';
    end if;
end $$;

create or replace function public._gen_room_code()
returns text language plpgsql volatile as $$
declare
    alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';  -- no 0/O/1/I
    b bytea := extensions.gen_random_bytes(6);
    s text := '';
begin
    for i in 0..5 loop
        s := s || substr(alphabet, (get_byte(b, i) % 32) + 1, 1);
    end loop;
    return s;
end $$;

create or replace function public._active_jokers(p_match_id uuid, p_user_id uuid)
returns int language sql stable security definer set search_path = public, pg_temp as $$
    select count(*)::int from public.player_jokers
    where match_id = p_match_id and user_id = p_user_id and status = 'available';
$$;

create or replace function public._grant_joker(p_match_id uuid, p_user_id uuid, p_source text, p_round_id uuid)
returns text language plpgsql security definer set search_path = public, pg_temp as $$
declare t text; v_room uuid;
begin
    if public._active_jokers(p_match_id, p_user_id) >= 3 then return null; end if;  -- inventory cap
    select room_id into v_room from public.matches where id = p_match_id;
    t := public._roll_joker();
    insert into public.player_jokers(match_id, room_id, user_id, joker_type, source, source_round_id)
    values (p_match_id, v_room, p_user_id, t, p_source, p_round_id)
    on conflict do nothing;
    if not found then return null; end if;
    return t;
end $$;

-- Idempotent XP grant. Returns the amount actually granted (0 if already granted).
create or replace function public._award_xp(p_user uuid, p_amount int, p_reason text, p_key text, p_match uuid)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
begin
    insert into public.xp_transactions(user_id, amount, reason, source_key, match_id)
    values (p_user, p_amount, p_reason, p_key, p_match)
    on conflict (user_id, source_key) do nothing;
    if not found then return 0; end if;
    update public.profiles
       set xp = xp + p_amount, level = public.level_for_xp(xp + p_amount), updated_at = now()
     where id = p_user;
    if p_match is not null then
        update public.match_players set xp_earned = xp_earned + p_amount
         where match_id = p_match and user_id = p_user;
    end if;
    return p_amount;
end $$;

-- ---------------------------------------------------------------------
-- Time
-- ---------------------------------------------------------------------
create or replace function public.server_time()
returns timestamptz language sql stable as $$ select now(); $$;

-- ---------------------------------------------------------------------
-- Profiles & cosmetics
-- ---------------------------------------------------------------------
create or replace function public._clean_username(p text)
returns text language plpgsql immutable as $$
declare s text := regexp_replace(btrim(coalesce(p, '')), '\s+', ' ', 'g');
begin
    if char_length(s) < 3 or char_length(s) > 16 then raise exception 'LOOPA:INVALID_USERNAME'; end if;
    if s !~ '^[A-Za-z0-9ÇĞİÖŞÜçğıöşü _.-]+$' then raise exception 'LOOPA:INVALID_USERNAME'; end if;
    return s;
end $$;

create or replace function public._grant_free_cosmetics(p_user uuid)
returns void language sql security definer set search_path = public, pg_temp as $$
    insert into public.player_cosmetics(user_id, item_id)
    select p_user, id from public.cosmetic_items where required_level = 1
    on conflict do nothing;
$$;

create or replace function public._validate_avatar(p_user uuid, p_avatar jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare
    k text; v text; clean jsonb := '{}'::jsonb; lvl int;
    slots constant text[] := array['base','skin','hair','hair_color','eyes','mouth','glasses',
                                   'hat','outfit','accessory','background','frame','decoration'];
begin
    if p_avatar is null or jsonb_typeof(p_avatar) <> 'object' then return clean; end if;
    select level into lvl from public.profiles where id = p_user;
    lvl := coalesce(lvl, 1);
    for k, v in select key, value #>> '{}' from jsonb_each(p_avatar) loop
        continue when not (k = any(slots));
        continue when v is null or v = '' or v = 'none';
        if not exists (select 1 from public.cosmetic_items ci where ci.id = v and ci.slot = k) then
            raise exception 'LOOPA:UNKNOWN_COSMETIC';
        end if;
        if not exists (select 1 from public.player_cosmetics pc where pc.user_id = p_user and pc.item_id = v) then
            raise exception 'LOOPA:COSMETIC_LOCKED';
        end if;
        clean := clean || jsonb_build_object(k, v);
    end loop;
    return clean;
end $$;

create or replace function public.ensure_profile(p_username text, p_avatar jsonb default null)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); name text := public._clean_username(p_username);
begin
    insert into public.profiles(id, username) values (u, name)
    on conflict (id) do update set username = excluded.username, updated_at = now();
    insert into public.player_statistics(user_id) values (u) on conflict do nothing;
    perform public._grant_free_cosmetics(u);
    if p_avatar is not null then
        update public.profiles set avatar = public._validate_avatar(u, p_avatar) where id = u;
    end if;
    return public.get_profile_overview();
end $$;

create or replace function public.update_username(p_username text)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
begin
    update public.profiles set username = public._clean_username(p_username), updated_at = now()
     where id = public._uid();
    if not found then raise exception 'LOOPA:NO_PROFILE'; end if;
end $$;

create or replace function public.update_avatar(p_avatar jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); clean jsonb;
begin
    perform public._grant_free_cosmetics(u);
    clean := public._validate_avatar(u, p_avatar);
    update public.profiles set avatar = clean, updated_at = now() where id = u;
    if not found then raise exception 'LOOPA:NO_PROFILE'; end if;
    return clean;
end $$;

create or replace function public.unlock_cosmetic(p_item_id text)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); need int; lvl int;
begin
    select required_level into need from public.cosmetic_items where id = p_item_id;
    if need is null then raise exception 'LOOPA:UNKNOWN_COSMETIC'; end if;
    select level into lvl from public.profiles where id = u;
    if lvl is null then raise exception 'LOOPA:NO_PROFILE'; end if;
    if lvl < need then raise exception 'LOOPA:LEVEL_TOO_LOW'; end if;
    insert into public.player_cosmetics(user_id, item_id) values (u, p_item_id) on conflict do nothing;
end $$;

create or replace function public.get_profile_overview()
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); p public.profiles;
begin
    select * into p from public.profiles where id = u;
    if not found then return null; end if;
    return jsonb_build_object(
        'id', p.id, 'username', p.username, 'avatar', p.avatar, 'xp', p.xp, 'level', p.level,
        'created_at', p.created_at,
        'stats', (select to_jsonb(s) - 'user_id' from public.player_statistics s where s.user_id = u),
        'owned_cosmetics', coalesce((select jsonb_agg(item_id) from public.player_cosmetics where user_id = u), '[]'::jsonb),
        'recent_xp', coalesce((select jsonb_agg(x) from (
                select amount, reason, created_at from public.xp_transactions
                where user_id = u order by created_at desc, id desc limit 10) x), '[]'::jsonb),
        'history', coalesce((select jsonb_agg(h) from (
                select match_id, room_code, final_rank, player_count, total_points, round_wins, xp_earned, finished_at
                from public.match_history where user_id = u order by finished_at desc limit 20) h), '[]'::jsonb)
    );
end $$;

-- ---------------------------------------------------------------------
-- Rooms
-- ---------------------------------------------------------------------
create or replace function public._require_profile(p_user uuid)
returns void language plpgsql stable security definer set search_path = public, pg_temp as $$
begin
    if not exists (select 1 from public.profiles where id = p_user) then raise exception 'LOOPA:NO_PROFILE'; end if;
end $$;

create or replace function public._lock_room(p_room_id uuid)
returns public.rooms language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms;
begin
    select * into r from public.rooms where id = p_room_id for update;
    if not found or r.status = 'closed' then raise exception 'LOOPA:ROOM_NOT_FOUND'; end if;
    return r;
end $$;

create or replace function public._require_host(r public.rooms)
returns void language plpgsql stable as $$
begin
    if r.host_id <> auth.uid() then raise exception 'LOOPA:NOT_HOST'; end if;
end $$;

-- Transfers host to the longest-present connected player when the host
-- left or has not sent a heartbeat for 30 s. Returns true if it changed.
create or replace function public._ensure_host(p_room_id uuid)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms; host_ok boolean; candidate uuid;
begin
    select * into r from public.rooms where id = p_room_id for update;
    if not found or r.status = 'closed' then return false; end if;
    select exists (select 1 from public.room_players
                    where room_id = p_room_id and user_id = r.host_id and status = 'active'
                      and last_seen_at > now() - interval '30 seconds') into host_ok;
    if host_ok then return false; end if;
    select user_id into candidate from public.room_players
     where room_id = p_room_id and status = 'active' and user_id <> r.host_id
       and last_seen_at > now() - interval '30 seconds'
     order by (role = 'player') desc, joined_at asc limit 1;
    if candidate is null then
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

create or replace function public.create_room(p_is_public boolean default false)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); v_code text; v_id uuid; attempts int := 0;
begin
    perform public._require_profile(u);
    -- leave any other room first: a player is in one room at a time
    perform public._leave_all_rooms(u);
    loop
        attempts := attempts + 1;
        v_code := public._gen_room_code();
        begin
            insert into public.rooms(code, host_id, is_public, settings)
            values (v_code, u, coalesce(p_is_public, false), public._default_settings())
            returning id into v_id;
            exit;
        exception when unique_violation then
            if attempts >= 20 then raise exception 'LOOPA:CODE_GENERATION_FAILED'; end if;
        end;
    end loop;
    insert into public.room_players(room_id, user_id, role, is_ready) values (v_id, u, 'player', true);
    perform public._bump(v_id);
    return jsonb_build_object('id', v_id, 'code', v_code);
end $$;

create or replace function public._leave_all_rooms(p_user uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare rid uuid;
begin
    for rid in select room_id from public.room_players where user_id = p_user and status = 'active' loop
        update public.room_players set status = 'left', is_ready = false where room_id = rid and user_id = p_user;
        perform public._ensure_host(rid);
        perform public._bump(rid);
    end loop;
end $$;

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

create or replace function public.leave_room(p_room_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    update public.room_players set status = 'left', is_ready = false
     where room_id = p_room_id and user_id = u and status = 'active';
    if found then
        perform public._ensure_host(p_room_id);
        perform public._bump(p_room_id);
    end if;
end $$;

create or replace function public.room_heartbeat(p_room_id uuid)
returns timestamptz language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); was_offline boolean;
begin
    select last_seen_at < now() - interval '25 seconds' into was_offline
      from public.room_players where room_id = p_room_id and user_id = u and status = 'active';
    if was_offline is null then raise exception 'LOOPA:NOT_IN_ROOM'; end if;
    update public.room_players set last_seen_at = now() where room_id = p_room_id and user_id = u;
    if public._ensure_host(p_room_id) or was_offline then
        perform public._bump(p_room_id);   -- connection status changed
    end if;
    return now();
end $$;

create or replace function public.set_ready(p_room_id uuid, p_ready boolean)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); r public.rooms := public._lock_room(p_room_id);
begin
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    update public.room_players set is_ready = coalesce(p_ready, false), last_seen_at = now()
     where room_id = p_room_id and user_id = u and status = 'active' and role = 'player';
    if not found then raise exception 'LOOPA:NOT_IN_ROOM'; end if;
    perform public._bump(p_room_id);
end $$;

create or replace function public.set_role(p_room_id uuid, p_spectator boolean)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); r public.rooms := public._lock_room(p_room_id); n int;
begin
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    if p_spectator then
        if r.host_id = u then raise exception 'LOOPA:HOST_MUST_PLAY'; end if;
        if not coalesce((r.settings->>'allow_spectators')::boolean, true) then raise exception 'LOOPA:SPECTATORS_DISABLED'; end if;
        update public.room_players set role = 'spectator', is_ready = false
         where room_id = p_room_id and user_id = u and status = 'active';
    else
        select count(*) into n from public.room_players where room_id = p_room_id and status = 'active' and role = 'player';
        if n >= coalesce((r.settings->>'max_players')::int, 10) then raise exception 'LOOPA:ROOM_FULL'; end if;
        update public.room_players set role = 'player', is_ready = false
         where room_id = p_room_id and user_id = u and status = 'active';
    end if;
    perform public._bump(p_room_id);
end $$;

create or replace function public._validate_settings(p jsonb, p_current_players int)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
    secs int := (p->>'round_seconds')::int;
    rnds int := (p->>'rounds')::int;
    maxp int := (p->>'max_players')::int;
    vote int := coalesce((p->>'vote_seconds')::int, 20);
    specs boolean := coalesce((p->>'allow_spectators')::boolean, true);
    cats jsonb := '[]'::jsonb;
    excl jsonb := '[]'::jsonb;
    c jsonb; k text; nm text; seen text[] := '{}'; n_custom int := 0; l text;
begin
    if secs is null or secs not in (30, 60, 90, 120) then raise exception 'LOOPA:INVALID_SETTINGS'; end if;
    if rnds is null or rnds not in (3, 5, 10, 15)    then raise exception 'LOOPA:INVALID_SETTINGS'; end if;
    if maxp is null or maxp not in (4, 6, 10)        then raise exception 'LOOPA:INVALID_SETTINGS'; end if;
    if maxp < p_current_players then raise exception 'LOOPA:MAX_BELOW_PLAYERS'; end if;
    if vote not between 10 and 60 then raise exception 'LOOPA:INVALID_SETTINGS'; end if;

    if jsonb_typeof(p->'categories') <> 'array' then raise exception 'LOOPA:INVALID_SETTINGS'; end if;
    for c in select * from jsonb_array_elements(p->'categories') loop
        k := c->>'key';
        if k is null or k = any(seen) then raise exception 'LOOPA:INVALID_CATEGORIES'; end if;
        seen := seen || k;
        if coalesce((c->>'custom')::boolean, false) then
            if k !~ '^custom_[0-9]{1,3}$' then raise exception 'LOOPA:INVALID_CATEGORIES'; end if;
            nm := regexp_replace(btrim(coalesce(c->>'name', '')), '\s+', ' ', 'g');
            if char_length(nm) < 2 or char_length(nm) > 20 then raise exception 'LOOPA:INVALID_CATEGORY_NAME'; end if;
            n_custom := n_custom + 1;
            cats := cats || jsonb_build_array(jsonb_build_object('key', k, 'name', nm, 'custom', true));
        else
            select name_tr into nm from public.categories where key = k;
            if nm is null then raise exception 'LOOPA:INVALID_CATEGORIES'; end if;
            cats := cats || jsonb_build_array(jsonb_build_object('key', k, 'name', nm, 'custom', false));
        end if;
    end loop;
    if jsonb_array_length(cats) < 3 then raise exception 'LOOPA:TOO_FEW_CATEGORIES'; end if;
    if jsonb_array_length(cats) > 12 or n_custom > 6 then raise exception 'LOOPA:TOO_MANY_CATEGORIES'; end if;

    if p ? 'excluded_letters' then
        if jsonb_typeof(p->'excluded_letters') <> 'array' then raise exception 'LOOPA:INVALID_SETTINGS'; end if;
        for l in select distinct value #>> '{}' from jsonb_array_elements(p->'excluded_letters') loop
            if not (l = any(public._turkish_letters())) then raise exception 'LOOPA:INVALID_SETTINGS'; end if;
            excl := excl || to_jsonb(l);
        end loop;
    end if;
    if jsonb_array_length(excl) > array_length(public._turkish_letters(), 1) - 5 then
        raise exception 'LOOPA:TOO_MANY_EXCLUDED_LETTERS';
    end if;

    return jsonb_build_object('round_seconds', secs, 'rounds', rnds, 'max_players', maxp,
                              'vote_seconds', vote, 'allow_spectators', specs,
                              'excluded_letters', excl, 'categories', cats);
end $$;

create or replace function public.update_room_settings(p_room_id uuid, p_settings jsonb, p_is_public boolean)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); n int; clean jsonb;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    select count(*) into n from public.room_players where room_id = p_room_id and status = 'active' and role = 'player';
    clean := public._validate_settings(p_settings, n);
    if not (clean->>'allow_spectators')::boolean then
        update public.room_players set role = 'player'
         where room_id = p_room_id and status = 'active' and role = 'spectator'
           and (select count(*) from public.room_players x where x.room_id = p_room_id and x.status = 'active' and x.role = 'player') < (clean->>'max_players')::int;
        update public.room_players set status = 'left'
         where room_id = p_room_id and status = 'active' and role = 'spectator';
    end if;
    update public.rooms set settings = clean, is_public = coalesce(p_is_public, is_public), updated_at = now()
     where id = p_room_id;
    -- settings changed => everybody must confirm ready again
    update public.room_players set is_ready = false
     where room_id = p_room_id and user_id <> r.host_id and status = 'active';
    perform public._bump(p_room_id);
    return clean;
end $$;

create or replace function public.kick_player(p_room_id uuid, p_user_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id);
begin
    perform public._require_host(r);
    if p_user_id = r.host_id then raise exception 'LOOPA:CANNOT_KICK_SELF'; end if;
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    update public.room_players set status = 'kicked', is_ready = false
     where room_id = p_room_id and user_id = p_user_id and status = 'active';
    if not found then raise exception 'LOOPA:PLAYER_NOT_FOUND'; end if;
    perform public._bump(p_room_id);
end $$;

create or replace function public.transfer_host(p_room_id uuid, p_user_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id);
begin
    perform public._require_host(r);
    if not exists (select 1 from public.room_players where room_id = p_room_id and user_id = p_user_id
                   and status = 'active' and role = 'player') then
        raise exception 'LOOPA:PLAYER_NOT_FOUND';
    end if;
    update public.rooms set host_id = p_user_id, updated_at = now() where id = p_room_id;
    update public.room_players set is_ready = true where room_id = p_room_id and user_id = p_user_id;
    perform public._bump(p_room_id);
end $$;

create or replace function public.close_room(p_room_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id);
begin
    perform public._require_host(r);
    update public.matches set status = 'abandoned', finished_at = now()
     where room_id = p_room_id and status = 'in_progress';
    update public.rooms set status = 'closed', closed_at = now(), updated_at = now(), current_match_id = null
     where id = p_room_id;
    perform public._bump(p_room_id);
end $$;

create or replace function public.list_public_rooms()
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce(jsonb_agg(x order by x->>'created_at' desc), '[]'::jsonb) from (
        select jsonb_build_object(
            'id', r.id, 'code', r.code, 'created_at', r.created_at,
            'host_name', p.username, 'host_avatar', p.avatar,
            'players', (select count(*) from public.room_players rp where rp.room_id = r.id and rp.status = 'active' and rp.role = 'player'),
            'max_players', (r.settings->>'max_players')::int,
            'round_seconds', (r.settings->>'round_seconds')::int,
            'rounds', (r.settings->>'rounds')::int,
            'categories', jsonb_array_length(r.settings->'categories')
        ) as x
        from public.rooms r join public.profiles p on p.id = r.host_id
        where r.is_public and r.status = 'lobby'
          and exists (select 1 from public.room_players rp where rp.room_id = r.id and rp.user_id = r.host_id
                      and rp.status = 'active' and rp.last_seen_at > now() - interval '60 seconds')
        limit 50
    ) s
    where (x->>'players')::int < (x->>'max_players')::int;
$$;

create or replace function public.get_my_active_room()
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select jsonb_build_object('id', r.id, 'code', r.code, 'status', r.status)
      from public.room_players rp join public.rooms r on r.id = rp.room_id
     where rp.user_id = auth.uid() and rp.status = 'active' and r.status <> 'closed'
     order by rp.joined_at desc limit 1;
$$;

-- ---------------------------------------------------------------------
-- Match lifecycle
-- ---------------------------------------------------------------------
create or replace function public._start_round(p_match_id uuid)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare
    m public.matches; s jsonb; pool text[]; v_letter text; v_round uuid; secs int; v_start timestamptz;
begin
    select * into m from public.matches where id = p_match_id for update;
    s := m.settings;
    secs := (s->>'round_seconds')::int;
    select coalesce(array_agg(l), '{}') into pool
      from unnest(public._turkish_letters()) l
     where not (l = any(array(select jsonb_array_elements_text(s->'excluded_letters'))))
       and not (l = any(m.used_letters));
    if coalesce(array_length(pool, 1), 0) = 0 then
        select array_agg(l) into pool from unnest(public._turkish_letters()) l
         where not (l = any(array(select jsonb_array_elements_text(s->'excluded_letters'))));
        m.used_letters := '{}';
    end if;
    v_letter := pool[1 + (get_byte(extensions.gen_random_bytes(1), 0) % array_length(pool, 1))];
    v_start := now() + interval '4 seconds';   -- letter reveal countdown

    update public.matches set current_round = current_round + 1, used_letters = m.used_letters || v_letter
     where id = p_match_id;

    insert into public.rounds(match_id, room_id, round_number, letter, categories, phase, started_at, base_deadline)
    values (p_match_id, m.room_id, m.current_round + 1, v_letter, s->'categories', 'answering',
            v_start, v_start + make_interval(secs => secs))
    returning id into v_round;

    insert into public.round_players(round_id, room_id, user_id, deadline_at)
    select v_round, m.room_id, mp.user_id, v_start + make_interval(secs => secs)
      from public.match_players mp
      join public.room_players rp on rp.room_id = m.room_id and rp.user_id = mp.user_id
     where mp.match_id = p_match_id and rp.status = 'active' and rp.role = 'player';

    update public.player_statistics ps set rounds_played = rounds_played + 1, updated_at = now()
      from public.round_players rpl where rpl.round_id = v_round and rpl.user_id = ps.user_id;
    return v_round;
end $$;

create or replace function public.start_match(p_room_id uuid)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); v_match uuid; n int; not_ready int; p record;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    select count(*), count(*) filter (where not is_ready and user_id <> r.host_id)
      into n, not_ready
      from public.room_players where room_id = p_room_id and status = 'active' and role = 'player';
    if n < 2 then raise exception 'LOOPA:NOT_ENOUGH_PLAYERS'; end if;
    if not_ready > 0 then raise exception 'LOOPA:PLAYERS_NOT_READY'; end if;

    begin
        insert into public.matches(room_id, settings, total_rounds)
        values (p_room_id, r.settings, (r.settings->>'rounds')::int)
        returning id into v_match;
    exception when unique_violation then
        raise exception 'LOOPA:GAME_IN_PROGRESS';
    end;

    insert into public.match_players(match_id, room_id, user_id)
    select v_match, p_room_id, user_id from public.room_players
     where room_id = p_room_id and status = 'active' and role = 'player';

    for p in select user_id from public.match_players where match_id = v_match loop
        perform public._grant_joker(v_match, p.user_id, 'match_start', null);
    end loop;

    update public.rooms set status = 'in_game', current_match_id = v_match, updated_at = now() where id = p_room_id;
    update public.room_players set is_ready = false where room_id = p_room_id and user_id <> r.host_id;
    perform public._start_round(v_match);
    perform public._bump(p_room_id);
    return v_match;
end $$;

-- Validation outcome for one answer.
create or replace function public._validate_answer(p_category text, p_letter text, p_norm text)
returns text language plpgsql stable security definer set search_path = public, pg_temp as $$
begin
    if p_norm is null or p_norm = '' then return 'empty'; end if;
    if left(p_norm, 1) <> public.normalize_answer(p_letter) then return 'invalid'; end if;
    if char_length(p_norm) < 2 then return 'invalid'; end if;
    if p_norm !~ '^[a-zçğıöşüéë0-9 .''&-]+$' then return 'invalid'; end if;
    if p_norm ~ '^(.)\1+$' then return 'invalid'; end if;                     -- "aaaa"
    if exists (select 1 from public.word_dictionary where category_key = p_category and word = p_norm) then
        return 'valid';
    end if;
    return 'uncertain';
end $$;

-- Players eligible to vote on an answer: active participants other than its author.
create or replace function public._eligible_voters(p_round_id uuid, p_author uuid)
returns int language sql stable security definer set search_path = public, pg_temp as $$
    select count(*)::int from public.round_players rpl
      join public.room_players rp on rp.room_id = rpl.room_id and rp.user_id = rpl.user_id
     where rpl.round_id = p_round_id and rpl.user_id <> p_author and rp.status = 'active' and rp.role = 'player';
$$;

create or replace function public._close_answering(p_round_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare rd public.rounds; c jsonb; rp record; n_uncertain int; vote_secs int;
begin
    select * into rd from public.rounds where id = p_round_id for update;
    if rd.phase <> 'answering' then return; end if;

    -- Make sure every participant has a row for every category (empty if missing).
    for rp in select user_id from public.round_players where round_id = p_round_id loop
        for c in select * from jsonb_array_elements(rd.categories) loop
            insert into public.answers(round_id, room_id, user_id, category_key)
            values (p_round_id, rd.room_id, rp.user_id, c->>'key')
            on conflict do nothing;
        end loop;
    end loop;

    update public.answers a set validation = public._validate_answer(a.category_key, rd.letter, a.normalized)
     where a.round_id = p_round_id;
    update public.answers set final_status = case validation when 'valid' then 'accepted'
                                                             when 'empty' then 'empty'
                                                             when 'invalid' then 'rejected' end
     where round_id = p_round_id and validation <> 'uncertain';
    -- Uncertain answers nobody can judge are accepted (documented fallback).
    update public.answers a set final_status = 'accepted'
     where a.round_id = p_round_id and a.validation = 'uncertain'
       and public._eligible_voters(p_round_id, a.user_id) = 0;
    update public.round_players set submitted_at = coalesce(submitted_at, deadline_at) where round_id = p_round_id;

    select count(*) into n_uncertain from public.answers where round_id = p_round_id and final_status is null;
    select coalesce((m.settings->>'vote_seconds')::int, 20) into vote_secs
      from public.matches m where m.id = rd.match_id;

    if n_uncertain > 0 then
        update public.rounds set phase = 'voting', answering_closed_at = now(),
               voting_deadline = now() + make_interval(secs => vote_secs)
         where id = p_round_id;
    else
        update public.rounds set answering_closed_at = now() where id = p_round_id;
        perform public._score_round(p_round_id);
    end if;
end $$;

create or replace function public._resolve_votes(p_round_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
begin
    -- Majority of cast votes; ties (including 0-0) are rejected.
    update public.answers a set final_status = case
            when coalesce(v.yes, 0) > coalesce(v.no, 0) then 'accepted' else 'rejected' end
      from (select a2.id,
                   (select count(*) from public.answer_votes av where av.answer_id = a2.id and av.accept) as yes,
                   (select count(*) from public.answer_votes av where av.answer_id = a2.id and not av.accept) as no
              from public.answers a2 where a2.round_id = p_round_id and a2.final_status is null) v
     where a.id = v.id;
end $$;

create or replace function public._score_round(p_round_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare rd public.rounds; best int; w record; n_players int; n_cats int; reveal_secs numeric;
begin
    select * into rd from public.rounds where id = p_round_id for update;
    if rd.scored then return; end if;
    perform public._resolve_votes(p_round_id);

    -- unique accepted answer: 20, duplicate accepted answer: 10
    update public.answers a set
        is_duplicate = d.cnt > 1,
        points = case when a.final_status = 'accepted' then (case when d.cnt > 1 then 10 else 20 end) else 0 end
      from (select category_key, normalized, count(*) as cnt
              from public.answers where round_id = p_round_id and final_status = 'accepted'
             group by category_key, normalized) d
     where a.round_id = p_round_id and a.category_key = d.category_key and a.normalized = d.normalized;
    update public.answers set points = 0, is_duplicate = false
     where round_id = p_round_id and final_status <> 'accepted';

    -- 2X Puan doubles one category (never the early bonus)
    update public.answers a set points = a.points * 2, doubled = true
      from public.round_players rpl
     where a.round_id = p_round_id and rpl.round_id = p_round_id and rpl.user_id = a.user_id
       and rpl.double_category = a.category_key and a.points > 0;

    update public.round_players rpl set points = coalesce(s.total, 0) + rpl.early_bonus
      from (select user_id, sum(points)::int as total from public.answers where round_id = p_round_id group by user_id) s
     where rpl.round_id = p_round_id and s.user_id = rpl.user_id;

    select max(points) into best from public.round_players where round_id = p_round_id;
    if best > 0 then
        update public.round_players set is_round_winner = true where round_id = p_round_id and points = best;
    end if;

    update public.match_players mp set total_points = mp.total_points + rpl.points,
           round_wins = mp.round_wins + (case when rpl.is_round_winner then 1 else 0 end)
      from public.round_players rpl
     where rpl.round_id = p_round_id and mp.match_id = rd.match_id and mp.user_id = rpl.user_id;

    update public.player_statistics ps set
           total_points = ps.total_points + rpl.points,
           round_wins = ps.round_wins + (case when rpl.is_round_winner then 1 else 0 end),
           early_bonuses = ps.early_bonuses + (case when rpl.early_bonus > 0 then 1 else 0 end),
           updated_at = now()
      from public.round_players rpl where rpl.round_id = p_round_id and ps.user_id = rpl.user_id;

    -- Round winners: +30 XP and one random joker if inventory permits.
    for w in select user_id from public.round_players where round_id = p_round_id and is_round_winner loop
        perform public._award_xp(w.user_id, 30, 'round_win', 'round_win:' || p_round_id, rd.match_id);
        perform public._grant_joker(rd.match_id, w.user_id, 'round_win', p_round_id);
    end loop;

    select count(*) into n_players from public.round_players where round_id = p_round_id;
    n_cats := jsonb_array_length(rd.categories);
    reveal_secs := least(n_cats * (2.5 + 0.7 * n_players), 150) + 1.5;
    update public.rounds set scored = true, phase = 'reveal', reveal_started_at = now(),
           reveal_deadline = now() + make_interval(secs => reveal_secs)
     where id = p_round_id;
end $$;

create or replace function public._finish_match(p_match_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare m public.matches; p record; n int; v_code text;
begin
    select * into m from public.matches where id = p_match_id for update;
    if m.rewards_processed then return; end if;

    update public.match_players mp set final_rank = r.rnk
      from (select user_id, rank() over (order by total_points desc, round_wins desc) as rnk
              from public.match_players where match_id = p_match_id) r
     where mp.match_id = p_match_id and mp.user_id = r.user_id;

    select count(*) into n from public.match_players where match_id = p_match_id;
    select code into v_code from public.rooms where id = m.room_id;

    for p in select mp.*, (rp.status = 'active') as present
               from public.match_players mp
               join public.room_players rp on rp.room_id = mp.room_id and rp.user_id = mp.user_id
              where mp.match_id = p_match_id loop
        if p.present then
            perform public._award_xp(p.user_id, 20, 'match_complete', 'match_complete:' || p_match_id, p_match_id);
            if p.final_rank = 1 then
                perform public._award_xp(p.user_id, 100, 'match_winner', 'match_rank:' || p_match_id, p_match_id);
            elsif p.final_rank = 2 then
                perform public._award_xp(p.user_id, 50, 'match_second', 'match_rank:' || p_match_id, p_match_id);
            elsif p.final_rank = 3 then
                perform public._award_xp(p.user_id, 30, 'match_third', 'match_rank:' || p_match_id, p_match_id);
            end if;
        end if;
        update public.player_statistics set
               matches_played = matches_played + 1,
               match_wins    = match_wins    + (case when p.final_rank = 1 then 1 else 0 end),
               second_places = second_places + (case when p.final_rank = 2 then 1 else 0 end),
               third_places  = third_places  + (case when p.final_rank = 3 then 1 else 0 end),
               updated_at = now()
         where user_id = p.user_id;
    end loop;

    insert into public.match_history(match_id, user_id, room_code, final_rank, player_count, total_points, round_wins, xp_earned)
    select mp.match_id, mp.user_id, v_code, mp.final_rank, n, mp.total_points, mp.round_wins, mp.xp_earned
      from public.match_players mp where mp.match_id = p_match_id
    on conflict do nothing;

    update public.matches set status = 'finished', finished_at = now(), rewards_processed = true where id = p_match_id;
    update public.rooms set status = 'lobby', current_match_id = null, last_match_id = p_match_id, updated_at = now()
     where id = m.room_id and current_match_id = p_match_id;
    update public.room_players rp set is_ready = (rp.user_id = r.host_id)
      from public.rooms r where r.id = m.room_id and rp.room_id = m.room_id;
end $$;

-- Advances the current round of a room as far as the clock allows.
create or replace function public._advance(p_room_id uuid)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms; m public.matches; rd public.rounds; changed boolean := false; guard int := 0; pending int; total_votes_needed int; votes_cast int; n_active int;
begin
    select * into r from public.rooms where id = p_room_id;
    if r.status <> 'in_game' or r.current_match_id is null then return false; end if;
    loop
        guard := guard + 1;
        exit when guard > 6;
        select * into rd from public.rounds where match_id = r.current_match_id order by round_number desc limit 1 for update;
        exit when not found;

        if rd.phase = 'answering' then
            exit when now() < rd.started_at;
            -- still waiting for a connected, unsubmitted player whose deadline (+1.5 s grace) has not passed?
            select count(*) into pending from public.round_players rpl
              join public.room_players rp on rp.room_id = rpl.room_id and rp.user_id = rpl.user_id
             where rpl.round_id = rd.id and rpl.submitted_at is null and rp.status = 'active'
               and rpl.deadline_at + interval '1.5 seconds' > now();
            exit when pending > 0;
            perform public._close_answering(rd.id);
            changed := true;
        elsif rd.phase = 'voting' then
            select coalesce(sum(public._eligible_voters(rd.id, a.user_id)), 0),
                   coalesce(sum((select count(*) from public.answer_votes av
                                  join public.room_players rp on rp.room_id = rd.room_id and rp.user_id = av.voter_id and rp.status = 'active'
                                 where av.answer_id = a.id)), 0)
              into total_votes_needed, votes_cast
              from public.answers a where a.round_id = rd.id and a.final_status is null;
            exit when now() < rd.voting_deadline and votes_cast < total_votes_needed;
            perform public._score_round(rd.id);
            changed := true;
        elsif rd.phase = 'reveal' then
            exit when now() < rd.reveal_deadline;
            update public.rounds set phase = 'results', results_deadline = now() + interval '8 seconds' where id = rd.id;
            changed := true;
        elsif rd.phase = 'results' then
            exit when now() < rd.results_deadline;
            update public.rounds set phase = 'finished' where id = rd.id;
            select * into m from public.matches where id = r.current_match_id;
            select count(*) into n_active from public.room_players rp
              join public.match_players mp on mp.user_id = rp.user_id and mp.match_id = m.id
             where rp.room_id = p_room_id and rp.status = 'active';
            if rd.round_number >= m.total_rounds or n_active < 2 then
                perform public._finish_match(m.id);
                changed := true;
                exit;
            else
                perform public._start_round(m.id);
            end if;
            changed := true;
        else
            exit;
        end if;
    end loop;
    if changed then perform public._bump(p_room_id); end if;
    return changed;
end $$;

create or replace function public.game_tick(p_room_id uuid)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); r public.rooms;
begin
    if not exists (select 1 from public.room_players where room_id = p_room_id and user_id = u and status = 'active') then
        raise exception 'LOOPA:NOT_IN_ROOM';
    end if;
    -- serialize ticks per room
    select * into r from public.rooms where id = p_room_id for update;
    perform public._ensure_host(p_room_id);
    return public._advance(p_room_id);
end $$;

create or replace function public.skip_reveal(p_room_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id);
begin
    perform public._require_host(r);
    update public.rounds set reveal_deadline = now()
     where match_id = r.current_match_id and phase = 'reveal';
    update public.rounds set results_deadline = now()
     where match_id = r.current_match_id and phase = 'results';
    perform public._advance(p_room_id);
    perform public._bump(p_room_id);
end $$;

-- ---------------------------------------------------------------------
-- Answers
-- ---------------------------------------------------------------------
create or replace function public._lock_my_round(p_round_id uuid, out rd public.rounds, out me public.round_players)
language plpgsql security definer set search_path = public, pg_temp as $$
begin
    select * into rd from public.rounds where id = p_round_id for update;
    if not found then raise exception 'LOOPA:ROUND_NOT_FOUND'; end if;
    select * into me from public.round_players where round_id = p_round_id and user_id = auth.uid() for update;
    if not found then raise exception 'LOOPA:NOT_A_PARTICIPANT'; end if;
    if not exists (select 1 from public.room_players where room_id = rd.room_id and user_id = auth.uid()
                   and status = 'active' and role = 'player') then
        raise exception 'LOOPA:NOT_A_PARTICIPANT';
    end if;
end $$;

create or replace function public._write_answers(rd public.rounds, p_user uuid, p_answers jsonb)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
declare c jsonb; txt text; filled int := 0;
begin
    if p_answers is null or jsonb_typeof(p_answers) <> 'object' then raise exception 'LOOPA:INVALID_ANSWERS'; end if;
    for c in select * from jsonb_array_elements(rd.categories) loop
        txt := left(regexp_replace(btrim(coalesce(p_answers->>(c->>'key'), '')), '\s+', ' ', 'g'), 40);
        insert into public.answers(round_id, room_id, user_id, category_key, raw_text, normalized, updated_at)
        values (rd.id, rd.room_id, p_user, c->>'key', txt, public.normalize_answer(txt), now())
        on conflict (round_id, user_id, category_key) do update
            set raw_text = excluded.raw_text, normalized = excluded.normalized, updated_at = now();
        if txt <> '' then filled := filled + 1; end if;
    end loop;
    return filled;
end $$;

-- Draft autosave: what you typed counts if time runs out.
create or replace function public.save_answers(p_round_id uuid, p_answers jsonb)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare x record;
begin
    perform public._uid();
    select * into x from public._lock_my_round(p_round_id);
    if (x.rd).phase <> 'answering' or (x.me).submitted_at is not null then raise exception 'LOOPA:ANSWERS_LOCKED'; end if;
    if now() < (x.rd).started_at then raise exception 'LOOPA:ROUND_NOT_STARTED'; end if;
    if now() > (x.me).deadline_at + interval '1.5 seconds' then raise exception 'LOOPA:TIME_UP'; end if;
    perform public._write_answers(x.rd, auth.uid(), p_answers);
end $$;

create or replace function public.submit_answers(p_round_id uuid, p_answers jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare x record; filled int; n_cats int; bonus int := 0;
begin
    perform public._uid();
    select * into x from public._lock_my_round(p_round_id);     -- row lock serializes simultaneous submits
    if (x.rd).phase <> 'answering' or (x.me).submitted_at is not null then raise exception 'LOOPA:ANSWERS_LOCKED'; end if;
    if now() < (x.rd).started_at then raise exception 'LOOPA:ROUND_NOT_STARTED'; end if;
    if now() > (x.me).deadline_at + interval '1.5 seconds' then raise exception 'LOOPA:TIME_UP'; end if;

    filled := public._write_answers(x.rd, auth.uid(), p_answers);
    n_cats := jsonb_array_length((x.rd).categories);
    if filled = n_cats and (x.rd).first_submitter_id is null and now() <= (x.me).deadline_at then
        update public.rounds set first_submitter_id = auth.uid() where id = p_round_id;
        bonus := 5;
    end if;
    update public.round_players set submitted_at = least(now(), deadline_at), early_bonus = bonus
     where round_id = p_round_id and user_id = auth.uid();
    perform public._advance((x.rd).room_id);
    perform public._bump((x.rd).room_id);
    return jsonb_build_object('early_bonus', bonus, 'filled', filled);
end $$;

-- ---------------------------------------------------------------------
-- Jokers
-- ---------------------------------------------------------------------
create or replace function public.use_joker(p_round_id uuid, p_joker_id uuid, p_target uuid default null, p_category text default null)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare
    x record; j public.player_jokers; tgt public.round_players; delta int := 0;
    remaining numeric; v_now timestamptz := now();
begin
    perform public._uid();
    select * into x from public._lock_my_round(p_round_id);
    if (x.rd).phase <> 'answering' or v_now < (x.rd).started_at then raise exception 'LOOPA:JOKER_NOT_NOW'; end if;
    if (x.me).submitted_at is not null or v_now >= (x.me).deadline_at then raise exception 'LOOPA:JOKER_NOT_NOW'; end if;

    select * into j from public.player_jokers
     where id = p_joker_id and user_id = auth.uid() and match_id = (x.rd).match_id and status = 'available' for update;
    if not found then raise exception 'LOOPA:JOKER_NOT_OWNED'; end if;
    if exists (select 1 from public.joker_usage where round_id = p_round_id and user_id = auth.uid()) then
        raise exception 'LOOPA:JOKER_ALREADY_USED_THIS_ROUND';
    end if;

    if j.joker_type in ('time_thief', 'slowdown') then
        if p_target is null or p_target = auth.uid() then raise exception 'LOOPA:INVALID_TARGET'; end if;
        select * into tgt from public.round_players where round_id = p_round_id and user_id = p_target for update;
        if not found or tgt.submitted_at is not null or tgt.deadline_at <= v_now then raise exception 'LOOPA:INVALID_TARGET'; end if;
        remaining := extract(epoch from (tgt.deadline_at - v_now));
    end if;

    if j.joker_type = 'extra_time' then
        delta := 10;
        update public.round_players set deadline_at = deadline_at + interval '10 seconds'
         where round_id = p_round_id and user_id = auth.uid();
    elsif j.joker_type = 'time_thief' then
        delta := least(5, floor(remaining))::int;
        if delta <= 0 then raise exception 'LOOPA:INVALID_TARGET'; end if;
        update public.round_players set deadline_at = deadline_at - make_interval(secs => delta)
         where round_id = p_round_id and user_id = p_target;
        update public.round_players set deadline_at = deadline_at + make_interval(secs => delta)
         where round_id = p_round_id and user_id = auth.uid();
    elsif j.joker_type = 'slowdown' then
        if remaining <= 5 then raise exception 'LOOPA:JOKER_FINAL_SECONDS'; end if;
        if exists (select 1 from public.joker_usage where round_id = p_round_id and target_user_id = p_target and joker_type = 'slowdown') then
            raise exception 'LOOPA:TARGET_ALREADY_SLOWED';
        end if;
        delta := least(10, floor(remaining))::int;
        update public.round_players set deadline_at = greatest(v_now, deadline_at - interval '10 seconds')
         where round_id = p_round_id and user_id = p_target;
    elsif j.joker_type = 'double_points' then
        if p_category is null or not exists (select 1 from jsonb_array_elements((x.rd).categories) c where c->>'key' = p_category) then
            raise exception 'LOOPA:INVALID_CATEGORY';
        end if;
        update public.round_players set double_category = p_category
         where round_id = p_round_id and user_id = auth.uid();
    end if;

    begin
        insert into public.joker_usage(round_id, room_id, user_id, joker_id, joker_type, target_user_id, category_key, seconds_delta)
        values (p_round_id, (x.rd).room_id, auth.uid(), j.id, j.joker_type,
                case when j.joker_type in ('time_thief','slowdown') then p_target end,
                case when j.joker_type = 'double_points' then p_category end, delta);
    exception when unique_violation then
        raise exception 'LOOPA:JOKER_ALREADY_USED_THIS_ROUND';
    end;
    update public.player_jokers set status = 'used', used_at = v_now where id = j.id;
    update public.player_statistics set jokers_used = jokers_used + 1 where user_id = auth.uid();
    perform public._bump((x.rd).room_id);
    return jsonb_build_object('type', j.joker_type, 'seconds', delta);
end $$;

-- ---------------------------------------------------------------------
-- Voting
-- ---------------------------------------------------------------------
create or replace function public.cast_vote(p_answer_id uuid, p_accept boolean)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare a public.answers; rd public.rounds;
begin
    perform public._uid();
    select * into a from public.answers where id = p_answer_id;
    if not found then raise exception 'LOOPA:ANSWER_NOT_FOUND'; end if;
    select * into rd from public.rounds where id = a.round_id for update;
    if rd.phase <> 'voting' or now() > rd.voting_deadline or a.final_status is not null then
        raise exception 'LOOPA:VOTING_CLOSED';
    end if;
    if a.user_id = auth.uid() then raise exception 'LOOPA:CANNOT_VOTE_OWN'; end if;
    if not exists (select 1 from public.round_players rpl
                     join public.room_players rp on rp.room_id = rpl.room_id and rp.user_id = rpl.user_id
                    where rpl.round_id = a.round_id and rpl.user_id = auth.uid()
                      and rp.status = 'active' and rp.role = 'player') then
        raise exception 'LOOPA:NOT_A_PARTICIPANT';
    end if;
    insert into public.answer_votes(answer_id, room_id, voter_id, accept) values (p_answer_id, a.room_id, auth.uid(), p_accept)
    on conflict (answer_id, voter_id) do nothing;
    if not found then raise exception 'LOOPA:ALREADY_VOTED'; end if;
    perform public._advance(a.room_id);
    perform public._bump(a.room_id);
end $$;

-- ---------------------------------------------------------------------
-- Snapshot reads
-- ---------------------------------------------------------------------
create or replace function public.get_room_state(p_room_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
    u uuid := public._uid(); r public.rooms; me public.room_players; m public.matches; rd public.rounds;
    res jsonb; round_json jsonb := null; match_json jsonb := null;
begin
    select * into me from public.room_players where room_id = p_room_id and user_id = u;
    if not found then raise exception 'LOOPA:NOT_IN_ROOM'; end if;
    select * into r from public.rooms where id = p_room_id;

    res := jsonb_build_object(
        'server_time', now(),
        'me', jsonb_build_object('user_id', u, 'role', me.role, 'status', me.status),
        'room', jsonb_build_object('id', r.id, 'code', r.code, 'host_id', r.host_id, 'status', r.status,
                                   'is_public', r.is_public, 'settings', r.settings,
                                   'current_match_id', r.current_match_id, 'last_match_id', r.last_match_id),
        'players', coalesce((select jsonb_agg(jsonb_build_object(
                'user_id', rp.user_id, 'username', p.username, 'avatar', p.avatar, 'level', p.level,
                'role', rp.role, 'is_ready', rp.is_ready, 'is_host', rp.user_id = r.host_id,
                'online', rp.last_seen_at > now() - interval '25 seconds', 'joined_at', rp.joined_at)
                order by rp.joined_at)
            from public.room_players rp join public.profiles p on p.id = rp.user_id
            where rp.room_id = p_room_id and rp.status = 'active'), '[]'::jsonb)
    );

    if me.status <> 'active' then return res; end if;

    if r.current_match_id is not null then
        select * into m from public.matches where id = r.current_match_id;
        match_json := jsonb_build_object(
            'id', m.id, 'status', m.status, 'total_rounds', m.total_rounds, 'current_round', m.current_round,
            'settings', m.settings,
            'scoreboard', coalesce((select jsonb_agg(jsonb_build_object(
                    'user_id', mp.user_id, 'username', p.username, 'avatar', p.avatar,
                    'total_points', mp.total_points, 'round_wins', mp.round_wins,
                    'jokers', public._active_jokers(m.id, mp.user_id))
                    order by mp.total_points desc, mp.round_wins desc)
                from public.match_players mp join public.profiles p on p.id = mp.user_id
                where mp.match_id = m.id), '[]'::jsonb),
            'my_jokers', coalesce((select jsonb_agg(jsonb_build_object('id', id, 'type', joker_type, 'source', source) order by acquired_at)
                from public.player_jokers where match_id = m.id and user_id = u and status = 'available'), '[]'::jsonb)
        );

        select * into rd from public.rounds where match_id = m.id order by round_number desc limit 1;
        if found then
            round_json := jsonb_build_object(
                'id', rd.id, 'number', rd.round_number, 'letter', rd.letter, 'categories', rd.categories,
                'phase', rd.phase, 'started_at', rd.started_at, 'base_deadline', rd.base_deadline,
                'voting_deadline', rd.voting_deadline, 'reveal_started_at', rd.reveal_started_at,
                'reveal_deadline', rd.reveal_deadline, 'results_deadline', rd.results_deadline,
                'first_submitter_id', rd.first_submitter_id,
                'players', coalesce((select jsonb_agg(jsonb_build_object(
                        'user_id', rpl.user_id, 'deadline_at', rpl.deadline_at,
                        'submitted', rpl.submitted_at is not null, 'submitted_at', rpl.submitted_at,
                        'points', case when rd.scored then rpl.points end,
                        'early_bonus', rpl.early_bonus,
                        'is_round_winner', rpl.is_round_winner,
                        'double_category', case when rpl.user_id = u or rd.phase <> 'answering' then rpl.double_category end,
                        'joker_used', exists (select 1 from public.joker_usage ju where ju.round_id = rd.id and ju.user_id = rpl.user_id)))
                    from public.round_players rpl where rpl.round_id = rd.id), '[]'::jsonb),
                'joker_events', coalesce((select jsonb_agg(jsonb_build_object(
                        'id', ju.id, 'user_id', ju.user_id, 'type', ju.joker_type, 'target_user_id', ju.target_user_id,
                        'seconds', ju.seconds_delta, 'created_at', ju.created_at,
                        'category_key', case when ju.user_id = u or rd.phase <> 'answering' then ju.category_key end)
                        order by ju.created_at)
                    from public.joker_usage ju where ju.round_id = rd.id), '[]'::jsonb),
                'answers', coalesce((select jsonb_agg(jsonb_build_object(
                        'id', a.id, 'user_id', a.user_id, 'category_key', a.category_key,
                        'text', a.raw_text, 'normalized', a.normalized,
                        'validation', a.validation, 'final_status', a.final_status,
                        'is_duplicate', a.is_duplicate, 'points', a.points, 'doubled', a.doubled,
                        'my_vote', (select av.accept from public.answer_votes av where av.answer_id = a.id and av.voter_id = u),
                        'votes_cast', (select count(*) from public.answer_votes av where av.answer_id = a.id),
                        'votes_needed', case when a.validation = 'uncertain' then public._eligible_voters(rd.id, a.user_id) end,
                        'votes_accept', case when rd.phase not in ('answering','voting')
                            then (select count(*) from public.answer_votes av where av.answer_id = a.id and av.accept) end,
                        'votes_reject', case when rd.phase not in ('answering','voting')
                            then (select count(*) from public.answer_votes av where av.answer_id = a.id and not av.accept) end))
                    from public.answers a
                    where a.round_id = rd.id and (a.user_id = u or rd.phase <> 'answering')), '[]'::jsonb)
            );
        end if;
    end if;

    return res || jsonb_build_object('match', match_json, 'round', round_json);
end $$;

create or replace function public.get_match_results(p_match_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare m public.matches;
begin
    perform public._uid();
    select * into m from public.matches where id = p_match_id;
    if not found or not public.is_room_member(m.room_id) then raise exception 'LOOPA:MATCH_NOT_FOUND'; end if;
    return jsonb_build_object(
        'id', m.id, 'status', m.status, 'room_id', m.room_id, 'total_rounds', m.total_rounds,
        'rounds_played', (select count(*) from public.rounds where match_id = m.id and scored),
        'started_at', m.started_at, 'finished_at', m.finished_at,
        'players', coalesce((select jsonb_agg(jsonb_build_object(
                'user_id', mp.user_id, 'username', p.username, 'avatar', p.avatar, 'level', p.level, 'xp', p.xp,
                'total_points', mp.total_points, 'round_wins', mp.round_wins, 'final_rank', mp.final_rank,
                'xp_earned', mp.xp_earned,
                'valid_answers', (select count(*) from public.answers a join public.rounds r2 on r2.id = a.round_id
                                   where r2.match_id = m.id and a.user_id = mp.user_id and a.final_status = 'accepted'),
                'unique_answers', (select count(*) from public.answers a join public.rounds r2 on r2.id = a.round_id
                                   where r2.match_id = m.id and a.user_id = mp.user_id and a.final_status = 'accepted' and not a.is_duplicate),
                'early_bonuses', (select count(*) from public.round_players rpl join public.rounds r2 on r2.id = rpl.round_id
                                   where r2.match_id = m.id and rpl.user_id = mp.user_id and rpl.early_bonus > 0))
                order by mp.final_rank nulls last, mp.total_points desc)
            from public.match_players mp join public.profiles p on p.id = mp.user_id
            where mp.match_id = m.id), '[]'::jsonb),
        'round_winners', coalesce((select jsonb_agg(jsonb_build_object('round', r2.round_number, 'letter', r2.letter,
                'winners', (select jsonb_agg(rpl.user_id) from public.round_players rpl where rpl.round_id = r2.id and rpl.is_round_winner))
                order by r2.round_number)
            from public.rounds r2 where r2.match_id = m.id and r2.scored), '[]'::jsonb)
    );
end $$;

-- ---------------------------------------------------------------------
-- Grants: internal helpers (prefixed "_") are not callable by clients.
-- ---------------------------------------------------------------------
do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' loop
        execute format('revoke all on function %s from public, anon', f.sig);
        if left(f.proname, 1) = '_' then
            execute format('revoke all on function %s from authenticated', f.sig);
        else
            execute format('grant execute on function %s to authenticated', f.sig);
        end if;
    end loop;
end $$;
