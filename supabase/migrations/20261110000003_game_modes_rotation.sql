-- =====================================================================
-- LOOPA — game mode catalogue, weekly rotation, host gating (stage 2c)
--
-- * İsim Şehir is always free (always_free = true). The weekly rotation
--   covers only the other 9 modes.
-- * The rotation start (week 1) is NOT fixed yet: rotation_settings.anchor_week
--   is null, so no rotating mode is free until an admin sets the Monday of the
--   launch week (admin_set_rotation_anchor). Weeks start on Monday 00:00
--   Europe/Istanbul and are always computed with the database clock.
-- * The HOST's plan decides what a room can play. Free players can join a
--   Plus host's room and play any mode there.
-- * start_match stores an entitlement snapshot on the match, so a running
--   match is never interrupted when the host leaves or their Plus ends.
--   A new Free host cannot START a locked mode (checked again at start).
-- * Only İsim Şehir has an engine today. Every other mode is 'coming_soon'
--   and start_match refuses it with MODE_NOT_IMPLEMENTED.
--
-- Backward compatible: rooms without settings.game_mode are İsim Şehir.
-- Clients that do not send game_mode keep the room's current mode.
-- =====================================================================

create table if not exists public.game_modes (
    id           text primary key check (id ~ '^[a-z_]{3,32}$'),
    name_tr      text not null,
    name_en      text not null,
    description_tr text not null default '',
    status       text not null default 'coming_soon'
                 check (status in ('available','early_access','coming_soon','disabled')),
    always_free  boolean not null default false,
    min_players  int not null default 2 check (min_players between 2 and 10),
    max_players  int not null default 10 check (max_players between 2 and 10),
    sort_order   int not null default 0,
    updated_at   timestamptz not null default now(),
    check (min_players <= max_players)
);

insert into public.game_modes(id, name_tr, name_en, description_tr, status, always_free, min_players, max_players, sort_order) values
 ('isim_sehir',          'İsim Şehir',          'Name City',            'Harfi gör, kategorileri doldur.',                       'available',   true,  2, 10, 10),
 ('gizli_kelime',        'Gizli Kelime',        'Secret Word',          'Kelimeyi sözlü anlat, takımın tahmin etsin.',           'coming_soon', false, 3, 10, 20),
 ('kelime_zinciri',      'Kelime Zinciri',      'Word Chain',           'Son harften yeni kelime türet.',                         'coming_soon', false, 2, 10, 30),
 ('bilgi_arenasi',       'Bilgi Arenası',       'Quiz Arena',           'Soruları en hızlı ve doğru cevapla.',                    'coming_soon', false, 2, 10, 40),
 ('iki_dogru_bir_yalan', 'İki Doğru Bir Yalan', 'Two Truths and a Lie', 'Yalanı bul.',                                            'coming_soon', false, 3, 10, 50),
 ('ciz_tahmin',          'Çiz ve Tahmin Et',    'Draw and Guess',       'Çiz, diğerleri tahmin etsin.',                           'coming_soon', false, 3, 10, 60),
 ('en_muhtemel_kim',     'En Muhtemel Kim?',    'Most Likely To',       'Kim en çok yapar? Oyla.',                                'coming_soon', false, 3, 10, 70),
 ('anlat_bakalim',       'Anlat Bakalım',       'Tell Me',              'Yasaklı kelimeleri kullanmadan sözlü anlat.',            'coming_soon', false, 4, 10, 80),
 ('cizim_telefonu',      'Çizim Telefonu',      'Drawing Telephone',    'Yaz, çiz, tahmin et; zincir nereye varacak?',            'coming_soon', false, 4, 10, 90),
 ('sessiz_sinema',       'Sessiz Sinema',       'Charades',             'Konuşmadan anlat (kamera yok, aynı ortamda oynanır).',   'coming_soon', false, 4, 10, 100)
on conflict (id) do nothing;

-- Singleton configuration of the rotation.
create table if not exists public.rotation_settings (
    id           boolean primary key default true check (id),
    anchor_week  date check (anchor_week is null or extract(isodow from anchor_week) = 1),  -- Monday of week 1
    cycle_weeks  int  not null default 5 check (cycle_weeks between 1 and 52),
    updated_at   timestamptz not null default now()
);
insert into public.rotation_settings(id, anchor_week, cycle_weeks) values (true, null, 5)
on conflict (id) do nothing;

-- Which modes are free to host in which week of the cycle.
create table if not exists public.mode_rotation (
    week_slot int  not null check (week_slot >= 1),
    mode_id   text not null references public.game_modes(id) on delete cascade,
    primary key (week_slot, mode_id)
);
-- The agreed 5-week table without İsim Şehir (always free).
insert into public.mode_rotation(week_slot, mode_id) values
 (1, 'ciz_tahmin'),
 (2, 'gizli_kelime'),        (2, 'en_muhtemel_kim'),
 (3, 'kelime_zinciri'),      (3, 'anlat_bakalim'),
 (4, 'bilgi_arenasi'),       (4, 'cizim_telefonu'),
 (5, 'iki_dogru_bir_yalan'), (5, 'sessiz_sinema')
on conflict do nothing;

alter table public.matches add column if not exists game_mode text not null default 'isim_sehir'
    references public.game_modes(id);
alter table public.matches add column if not exists entitlement_snapshot jsonb;

alter table public.game_modes        enable row level security;
alter table public.rotation_settings enable row level security;
alter table public.mode_rotation     enable row level security;
drop policy if exists game_modes_read on public.game_modes;
create policy game_modes_read on public.game_modes for select to authenticated using (true);
drop policy if exists mode_rotation_read on public.mode_rotation;
create policy mode_rotation_read on public.mode_rotation for select to authenticated using (true);
drop policy if exists rotation_settings_read on public.rotation_settings;
create policy rotation_settings_read on public.rotation_settings for select to authenticated using (true);
revoke all on public.game_modes, public.rotation_settings, public.mode_rotation from anon, authenticated;
grant select on public.game_modes, public.rotation_settings, public.mode_rotation to authenticated;

-- ---------------------------------------------------------------------
-- Rotation helpers
-- ---------------------------------------------------------------------
-- Monday 00:00 Europe/Istanbul of the week containing p_at.
create or replace function public._istanbul_week_start(p_at timestamptz)
returns timestamptz language sql stable set search_path = public, pg_temp as $$
    select date_trunc('week', p_at at time zone 'Europe/Istanbul') at time zone 'Europe/Istanbul';
$$;

-- 1-based week of the cycle, or null when the rotation has not started.
create or replace function public._rotation_slot(p_at timestamptz)
returns int language sql stable security definer set search_path = public, pg_temp as $$
    select case
        when s.anchor_week is null then null
        when (p_at at time zone 'Europe/Istanbul')::date < s.anchor_week then null
        else (((p_at at time zone 'Europe/Istanbul')::date - s.anchor_week) / 7) % s.cycle_weeks + 1 end
      from public.rotation_settings s where s.id;
$$;

create or replace function public._room_mode(p_settings jsonb)
returns text language sql immutable as $$
    select coalesce(nullif(p_settings->>'game_mode', ''), 'isim_sehir');
$$;

-- Can p_host start / select p_mode at p_at? {allowed, reason, unlocked_by, plan, week_slot}
create or replace function public._mode_access(p_host uuid, p_mode text, p_at timestamptz default now())
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare gm public.game_modes; v_plan text := public._user_plan(p_host, p_at); v_slot int := public._rotation_slot(p_at);
        in_rot boolean; ok boolean := false; why text := null; how text := null;
begin
    select * into gm from public.game_modes where id = p_mode;
    if not found then
        return jsonb_build_object('allowed', false, 'reason', 'MODE_NOT_FOUND', 'plan', v_plan, 'week_slot', v_slot);
    end if;
    in_rot := v_slot is not null and exists (select 1 from public.mode_rotation where week_slot = v_slot and mode_id = p_mode);
    if gm.status = 'disabled' then why := 'MODE_NOT_AVAILABLE';
    elsif gm.status = 'coming_soon' then
        if v_plan = 'admin' then ok := true; how := 'admin'; else why := 'MODE_NOT_AVAILABLE'; end if;
    elsif gm.status = 'early_access' then
        if v_plan in ('plus','admin') then ok := true; how := v_plan; else why := 'PLUS_REQUIRED'; end if;
    elsif gm.always_free then ok := true; how := 'always_free';
    elsif v_plan in ('plus','admin') then ok := true; how := v_plan;
    elsif in_rot then ok := true; how := 'rotation';
    else why := 'PLUS_REQUIRED';
    end if;
    return jsonb_build_object('allowed', ok, 'reason', why, 'unlocked_by', how, 'plan', v_plan,
                              'week_slot', v_slot, 'in_rotation', in_rot);
end $$;

-- ---------------------------------------------------------------------
-- Client RPC: catalogue + this week / next week + what the caller may host
-- ---------------------------------------------------------------------
create or replace function public.get_game_catalog()
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); t timestamptz := now();
        ws timestamptz := public._istanbul_week_start(t);
        nws timestamptz := public._istanbul_week_start(t) + interval '7 days';
        slot int := public._rotation_slot(t); nslot int;
begin
    nslot := public._rotation_slot(nws + interval '1 hour');
    return jsonb_build_object(
        'server_time', t,
        'plan', public._user_plan(u, t),
        'rotation', jsonb_build_object(
            'active', slot is not null,
            'starts_on', (select anchor_week from public.rotation_settings where id),
            'week_slot', slot,
            'week_starts_at', ws,
            'week_ends_at', nws,
            'free_modes', coalesce((select jsonb_agg(mode_id order by mode_id) from public.mode_rotation where week_slot = slot), '[]'::jsonb),
            'next_week_slot', nslot,
            'next_free_modes', coalesce((select jsonb_agg(mode_id order by mode_id) from public.mode_rotation where week_slot = nslot), '[]'::jsonb)),
        'modes', coalesce((select jsonb_agg(jsonb_build_object(
                'id', gm.id, 'name', gm.name_tr, 'description', gm.description_tr, 'status', gm.status,
                'always_free', gm.always_free, 'min_players', gm.min_players, 'max_players', gm.max_players,
                'access', public._mode_access(u, gm.id, t)) order by gm.sort_order)
            from public.game_modes gm where gm.status <> 'disabled'), '[]'::jsonb)
    );
end $$;

-- ---------------------------------------------------------------------
-- Admin RPCs
-- ---------------------------------------------------------------------
create or replace function public.admin_set_rotation_anchor(p_week date)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare a uuid := public._require_admin(); old date;
begin
    if p_week is not null and extract(isodow from p_week) <> 1 then raise exception 'LOOPA:ANCHOR_NOT_MONDAY'; end if;
    select anchor_week into old from public.rotation_settings where id for update;
    update public.rotation_settings set anchor_week = p_week, updated_at = now() where id;
    perform public._audit(a, 'admin', 'set_rotation_anchor', null, jsonb_build_object('old', old, 'new', p_week));
end $$;

create or replace function public.admin_set_mode_status(p_mode text, p_status text)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare a uuid := public._require_admin(); old text;
begin
    if p_status not in ('available','early_access','coming_soon','disabled') then raise exception 'LOOPA:INVALID_INPUT'; end if;
    if p_mode = 'isim_sehir' and p_status <> 'available' then raise exception 'LOOPA:INVALID_INPUT'; end if;
    select status into old from public.game_modes where id = p_mode for update;
    if not found then raise exception 'LOOPA:MODE_NOT_FOUND'; end if;
    update public.game_modes set status = p_status, updated_at = now() where id = p_mode;
    perform public._audit(a, 'admin', 'set_mode_status', null, jsonb_build_object('mode', p_mode, 'old', old, 'new', p_status));
end $$;

-- ---------------------------------------------------------------------
-- Existing functions, extended (bodies otherwise unchanged)
-- ---------------------------------------------------------------------
create or replace function public._default_settings()
returns jsonb language sql stable set search_path = public, pg_temp as $$
    select jsonb_build_object(
        'game_mode', 'isim_sehir',
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

-- New: optional settings.game_mode. Changing the mode requires that the host may play it.
create or replace function public.update_room_settings(p_room_id uuid, p_settings jsonb, p_is_public boolean)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); n int; clean jsonb; v_mode text; acc jsonb;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;
    v_mode := coalesce(nullif(p_settings->>'game_mode', ''), public._room_mode(r.settings));
    if v_mode <> public._room_mode(r.settings) then
        acc := public._mode_access(r.host_id, v_mode);
        if not (acc->>'allowed')::boolean then raise exception 'LOOPA:%', acc->>'reason'; end if;
    end if;
    select count(*) into n from public.room_players where room_id = p_room_id and status = 'active' and role = 'player';
    clean := public._validate_settings(p_settings, n) || jsonb_build_object('game_mode', v_mode);
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

-- New: mode access check at start, entitlement snapshot, game_mode on the match.
create or replace function public.start_match(p_room_id uuid)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); v_match uuid; n int; not_ready int; p record;
        v_mode text; acc jsonb; v_min int;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;

    v_mode := public._room_mode(r.settings);
    acc := public._mode_access(r.host_id, v_mode);
    if not (acc->>'allowed')::boolean then raise exception 'LOOPA:%', acc->>'reason'; end if;
    if v_mode <> 'isim_sehir' then raise exception 'LOOPA:MODE_NOT_IMPLEMENTED'; end if;
    select min_players into v_min from public.game_modes where id = v_mode;

    select count(*), count(*) filter (where not is_ready and user_id <> r.host_id)
      into n, not_ready
      from public.room_players where room_id = p_room_id and status = 'active' and role = 'player';
    if n < greatest(2, coalesce(v_min, 2)) then raise exception 'LOOPA:NOT_ENOUGH_PLAYERS'; end if;
    if not_ready > 0 then raise exception 'LOOPA:PLAYERS_NOT_READY'; end if;

    begin
        insert into public.matches(room_id, settings, total_rounds, game_mode, entitlement_snapshot)
        values (p_room_id, r.settings, (r.settings->>'rounds')::int, v_mode,
                acc || jsonb_build_object('host_id', r.host_id, 'mode', v_mode, 'at', now()))
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

-- New field: game_mode.
create or replace function public.list_public_rooms()
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce(jsonb_agg(x order by x->>'created_at' desc), '[]'::jsonb) from (
        select jsonb_build_object(
            'id', r.id, 'code', r.code, 'created_at', r.created_at,
            'host_name', p.username, 'host_avatar', p.avatar,
            'game_mode', public._room_mode(r.settings),
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

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and p.proname in (
                  '_istanbul_week_start','_rotation_slot','_room_mode','_mode_access','get_game_catalog',
                  'admin_set_rotation_anchor','admin_set_mode_status','_default_settings',
                  'update_room_settings','start_match','list_public_rooms') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
        if left(f.proname, 1) <> '_' then
            execute format('grant execute on function %s to authenticated', f.sig);
        end if;
    end loop;
end $$;
