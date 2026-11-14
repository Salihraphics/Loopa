-- =====================================================================
-- LOOPA 0.7.0 — social platform
--
-- Friends (requests, friendships, blocks), presence, room invites,
-- one-to-one messages between friends and in-app notifications.
--
-- Same model as the rest of the backend:
--  * Clients can only SELECT their own rows (RLS). There are no INSERT /
--    UPDATE / DELETE grants: every write is a SECURITY DEFINER RPC that
--    takes the caller from auth.uid() (never from a parameter) and
--    validates the relationship (friends, not blocked, own request ...).
--  * Realtime carries no data: `social_signals` has one row per user that
--    the RPCs bump; the client listens to its own row (RLS-filtered) and
--    re-fetches through the RPCs.
--  * Operations on a pair of users are serialised with a transaction
--    advisory lock on the pair, so simultaneous mutual requests, accept
--    vs. cancel, or block vs. accept cannot interleave.
--
-- Re-runnable: IF NOT EXISTS / CREATE OR REPLACE / DROP POLICY IF EXISTS.
-- Touches no existing table or function.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------
create table if not exists public.friend_requests (
    id           uuid primary key default gen_random_uuid(),
    from_user    uuid not null references public.profiles(id) on delete cascade,
    to_user      uuid not null references public.profiles(id) on delete cascade,
    status       text not null default 'pending' check (status in ('pending','accepted','declined','cancelled')),
    created_at   timestamptz not null default now(),
    responded_at timestamptz,
    check (from_user <> to_user)
);
-- At most one pending request per pair, whichever direction.
create unique index if not exists friend_requests_pending_pair_uidx
    on public.friend_requests (least(from_user, to_user), greatest(from_user, to_user)) where status = 'pending';
create index if not exists friend_requests_to_idx   on public.friend_requests (to_user, status, created_at desc);
create index if not exists friend_requests_from_idx on public.friend_requests (from_user, status, created_at desc);

create table if not exists public.friendships (
    user_a     uuid not null references public.profiles(id) on delete cascade,
    user_b     uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_a, user_b),
    check (user_a < user_b)                        -- one row per pair
);
create index if not exists friendships_b_idx on public.friendships (user_b);

create table if not exists public.user_blocks (
    blocker_id uuid not null references public.profiles(id) on delete cascade,
    blocked_id uuid not null references public.profiles(id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker_id, blocked_id),
    check (blocker_id <> blocked_id)
);
create index if not exists user_blocks_blocked_idx on public.user_blocks (blocked_id);

create table if not exists public.user_presence (
    user_id        uuid primary key references public.profiles(id) on delete cascade,
    last_seen_at   timestamptz not null default now(),
    is_active      boolean not null default false,  -- app in the foreground
    share_presence boolean not null default true,   -- privacy: friends may see online / last seen
    updated_at     timestamptz not null default now()
);

create table if not exists public.room_invites (
    id           uuid primary key default gen_random_uuid(),
    room_id      uuid not null references public.rooms(id) on delete cascade,
    from_user    uuid not null references public.profiles(id) on delete cascade,
    to_user      uuid not null references public.profiles(id) on delete cascade,
    status       text not null default 'pending' check (status in ('pending','accepted','declined','expired','cancelled')),
    created_at   timestamptz not null default now(),
    expires_at   timestamptz not null,
    responded_at timestamptz,
    check (from_user <> to_user)
);
create unique index if not exists room_invites_pending_uidx on public.room_invites (room_id, to_user) where status = 'pending';
create index if not exists room_invites_to_idx   on public.room_invites (to_user, status, created_at desc);
create index if not exists room_invites_from_idx on public.room_invites (from_user, created_at desc);

create table if not exists public.direct_messages (
    id           bigint generated always as identity primary key,
    sender_id    uuid not null references public.profiles(id) on delete cascade,
    recipient_id uuid not null references public.profiles(id) on delete cascade,
    body         text not null check (char_length(body) between 1 and 1000),
    client_id    uuid,                               -- idempotency key from the sending device
    created_at   timestamptz not null default now(),
    read_at      timestamptz,
    check (sender_id <> recipient_id),
    unique (sender_id, client_id)
);
create index if not exists direct_messages_pair_idx
    on public.direct_messages (least(sender_id, recipient_id), greatest(sender_id, recipient_id), id desc);
create index if not exists direct_messages_unread_idx
    on public.direct_messages (recipient_id, sender_id) where read_at is null;
create index if not exists direct_messages_sender_time_idx on public.direct_messages (sender_id, created_at desc);

create table if not exists public.notifications (
    id         bigint generated always as identity primary key,
    user_id    uuid not null references public.profiles(id) on delete cascade,
    type       text not null check (type in ('friend_request','friend_accepted','room_invite','message')),
    actor_id   uuid references public.profiles(id) on delete cascade,
    ref_id     text,                                 -- request / invite id, or the actor for messages
    payload    jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    read_at    timestamptz
);
create index if not exists notifications_user_idx on public.notifications (user_id, created_at desc);
create index if not exists notifications_unread_idx on public.notifications (user_id) where read_at is null;
-- New messages from one friend collapse into a single unread notification.
create unique index if not exists notifications_message_uidx
    on public.notifications (user_id, actor_id) where type = 'message' and read_at is null;

create table if not exists public.social_signals (
    user_id    uuid primary key references public.profiles(id) on delete cascade,
    version    bigint not null default 0,
    updated_at timestamptz not null default now()
);

-- ---------------------------------------------------------------------
-- Row level security: own rows only, read only
-- ---------------------------------------------------------------------
alter table public.friend_requests  enable row level security;
alter table public.friendships      enable row level security;
alter table public.user_blocks      enable row level security;
alter table public.user_presence    enable row level security;
alter table public.room_invites     enable row level security;
alter table public.direct_messages  enable row level security;
alter table public.notifications    enable row level security;
alter table public.social_signals   enable row level security;

drop policy if exists friend_requests_own on public.friend_requests;
create policy friend_requests_own on public.friend_requests for select to authenticated
    using (from_user = auth.uid() or to_user = auth.uid());
drop policy if exists friendships_own on public.friendships;
create policy friendships_own on public.friendships for select to authenticated
    using (user_a = auth.uid() or user_b = auth.uid());
drop policy if exists user_blocks_own on public.user_blocks;
create policy user_blocks_own on public.user_blocks for select to authenticated using (blocker_id = auth.uid());
drop policy if exists user_presence_own on public.user_presence;
create policy user_presence_own on public.user_presence for select to authenticated using (user_id = auth.uid());
drop policy if exists room_invites_own on public.room_invites;
create policy room_invites_own on public.room_invites for select to authenticated
    using (from_user = auth.uid() or to_user = auth.uid());
drop policy if exists direct_messages_own on public.direct_messages;
create policy direct_messages_own on public.direct_messages for select to authenticated
    using (sender_id = auth.uid() or recipient_id = auth.uid());
drop policy if exists notifications_own on public.notifications;
create policy notifications_own on public.notifications for select to authenticated using (user_id = auth.uid());
drop policy if exists social_signals_own on public.social_signals;
create policy social_signals_own on public.social_signals for select to authenticated using (user_id = auth.uid());

revoke all on public.friend_requests, public.friendships, public.user_blocks, public.user_presence,
              public.room_invites, public.direct_messages, public.notifications, public.social_signals
  from anon, authenticated;
grant select on public.friend_requests, public.friendships, public.user_blocks, public.user_presence,
                public.room_invites, public.direct_messages, public.notifications, public.social_signals
  to authenticated;

do $$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
       and not exists (select 1 from pg_publication_tables
                        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'social_signals') then
        alter publication supabase_realtime add table public.social_signals;
    end if;
end $$;

-- ---------------------------------------------------------------------
-- Helpers (not callable by clients)
-- ---------------------------------------------------------------------
create or replace function public._social_bump(p_user uuid)
returns void language sql security definer set search_path = public, pg_temp as $$
    insert into public.social_signals(user_id, version, updated_at) values (p_user, 1, now())
    on conflict (user_id) do update set version = social_signals.version + 1, updated_at = now();
$$;

create or replace function public._pair_lock(a uuid, b uuid)
returns void language sql security definer set search_path = public, pg_temp as $$
    select pg_advisory_xact_lock(hashtextextended('loopa:pair:' || least(a, b)::text || ':' || greatest(a, b)::text, 0));
$$;

create or replace function public._are_friends(a uuid, b uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
    select exists (select 1 from public.friendships where user_a = least(a, b) and user_b = greatest(a, b));
$$;

create or replace function public._is_blocked(a uuid, b uuid)   -- either direction
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
    select exists (select 1 from public.user_blocks
                    where (blocker_id = a and blocked_id = b) or (blocker_id = b and blocked_id = a));
$$;

create or replace function public._require_user(p_user uuid)
returns void language plpgsql stable security definer set search_path = public, pg_temp as $$
begin
    if p_user is null or not exists (select 1 from public.profiles where id = p_user) then
        raise exception 'LOOPA:USER_NOT_FOUND';
    end if;
end $$;

create or replace function public._notify(p_user uuid, p_type text, p_actor uuid, p_ref text, p_payload jsonb default '{}'::jsonb)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if p_type = 'message' then
        insert into public.notifications(user_id, type, actor_id, ref_id, payload)
        values (p_user, 'message', p_actor, p_actor::text, p_payload || jsonb_build_object('count', 1))
        on conflict (user_id, actor_id) where type = 'message' and read_at is null do update
            set payload = excluded.payload || jsonb_build_object('count', coalesce((notifications.payload->>'count')::int, 1) + 1),
                created_at = now();
    else
        insert into public.notifications(user_id, type, actor_id, ref_id, payload)
        values (p_user, p_type, p_actor, p_ref, coalesce(p_payload, '{}'::jsonb));
    end if;
    perform public._social_bump(p_user);
end $$;

create or replace function public._user_card(p_user uuid)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select jsonb_build_object('user_id', p.id, 'username', p.username, 'avatar', p.avatar, 'level', p.level)
      from public.profiles p where p.id = p_user;
$$;

-- Presence of [p_user] as seen by [p_viewer]: friends only, and only when both share it.
create or replace function public._presence(p_viewer uuid, p_user uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare me public.user_presence; them public.user_presence;
begin
    select * into me from public.user_presence where user_id = p_viewer;
    select * into them from public.user_presence where user_id = p_user;
    if not public._are_friends(p_viewer, p_user)
       or not coalesce(me.share_presence, true) or not coalesce(them.share_presence, true) then
        return jsonb_build_object('visible', false, 'online', false, 'last_seen_at', null);
    end if;
    return jsonb_build_object('visible', true,
        'online', coalesce(them.is_active and them.last_seen_at > now() - interval '90 seconds', false),
        'last_seen_at', them.last_seen_at);
end $$;

create or replace function public._relation(p_me uuid, p_other uuid)
returns text language sql stable security definer set search_path = public, pg_temp as $$
    select case
        when exists (select 1 from public.user_blocks where blocker_id = p_me and blocked_id = p_other) then 'blocked'
        when public._are_friends(p_me, p_other) then 'friend'
        when exists (select 1 from public.friend_requests where from_user = p_me and to_user = p_other and status = 'pending') then 'outgoing'
        when exists (select 1 from public.friend_requests where from_user = p_other and to_user = p_me and status = 'pending') then 'incoming'
        else 'none' end;
$$;

-- Pending invites past their time are marked expired (lazily, by the RPCs that read them).
create or replace function public._expire_invites(p_user uuid)
returns void language sql security definer set search_path = public, pg_temp as $$
    update public.room_invites i set status = 'expired', responded_at = now()
     where i.status = 'pending' and (i.to_user = p_user or i.from_user = p_user)
       and (i.expires_at <= now() or exists (select 1 from public.rooms r where r.id = i.room_id and r.status = 'closed'));
$$;

-- ---------------------------------------------------------------------
-- Search and profiles
-- ---------------------------------------------------------------------
create or replace function public.search_users(p_query text)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
    u uuid := public._uid();
    q text := btrim(coalesce(p_query, ''));
    pattern text;
begin
    perform public._require_profile(u);
    if char_length(q) < 2 then raise exception 'LOOPA:SEARCH_TOO_SHORT'; end if;
    q := left(q, 16);
    pattern := '%' || replace(replace(replace(q, '\', '\\'), '%', '\%'), '_', '\_') || '%';
    return coalesce((
        select jsonb_agg(public._user_card(x.id) || jsonb_build_object('relation', public._relation(u, x.id)) order by x.rank, x.username)
          from (select p.id, p.username,
                       case when lower(p.username) = lower(q) then 0 when lower(p.username) like lower(q) || '%' then 1 else 2 end as rank
                  from public.profiles p
                 where p.id <> u
                   and p.username ilike pattern
                   and not exists (select 1 from public.user_blocks b
                                    where (b.blocker_id = u and b.blocked_id = p.id) or (b.blocker_id = p.id and b.blocked_id = u))
                 order by 3, p.username
                 limit 20) x), '[]'::jsonb);
end $$;

create or replace function public.get_user_profile(p_user uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); s public.player_statistics;
begin
    perform public._require_profile(u);
    perform public._require_user(p_user);
    -- Someone who blocked you does not exist for you.
    if exists (select 1 from public.user_blocks where blocker_id = p_user and blocked_id = u) then
        raise exception 'LOOPA:USER_NOT_FOUND';
    end if;
    select * into s from public.player_statistics where user_id = p_user;
    return public._user_card(p_user) || jsonb_build_object(
        'relation', case when p_user = u then 'self' else public._relation(u, p_user) end,
        'presence', public._presence(u, p_user),
        'matches_played', coalesce(s.matches_played, 0),
        'match_wins', coalesce(s.match_wins, 0),
        'friend_count', (select count(*) from public.friendships where user_a = p_user or user_b = p_user));
end $$;

-- ---------------------------------------------------------------------
-- Friend requests
-- ---------------------------------------------------------------------
create or replace function public._accept_request(r public.friend_requests)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
begin
    update public.friend_requests set status = 'accepted', responded_at = now() where id = r.id;
    insert into public.friendships(user_a, user_b) values (least(r.from_user, r.to_user), greatest(r.from_user, r.to_user))
    on conflict do nothing;
    -- the request notification is answered
    update public.notifications set read_at = now()
     where user_id = r.to_user and type = 'friend_request' and ref_id = r.id::text and read_at is null;
    perform public._notify(r.from_user, 'friend_accepted', r.to_user, r.id::text);
    perform public._social_bump(r.to_user);
end $$;

create or replace function public.send_friend_request(p_user uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); r public.friend_requests; n int;
begin
    perform public._require_profile(u);
    perform public._require_user(p_user);
    if p_user = u then raise exception 'LOOPA:CANNOT_ADD_SELF'; end if;
    perform public._pair_lock(u, p_user);
    if exists (select 1 from public.user_blocks where blocker_id = u and blocked_id = p_user) then
        raise exception 'LOOPA:USER_BLOCKED';
    end if;
    if exists (select 1 from public.user_blocks where blocker_id = p_user and blocked_id = u) then
        raise exception 'LOOPA:USER_NOT_FOUND';      -- do not reveal the block
    end if;
    if public._are_friends(u, p_user) then raise exception 'LOOPA:ALREADY_FRIENDS'; end if;

    select * into r from public.friend_requests
     where status = 'pending' and least(from_user, to_user) = least(u, p_user) and greatest(from_user, to_user) = greatest(u, p_user)
     for update;
    if found then
        if r.from_user = u then raise exception 'LOOPA:REQUEST_ALREADY_SENT'; end if;
        -- They already asked us: sending back means yes.
        perform public._accept_request(r);
        return jsonb_build_object('status', 'accepted', 'request_id', r.id);
    end if;

    -- No re-asking right after a "no".
    if exists (select 1 from public.friend_requests where from_user = u and to_user = p_user
                  and status = 'declined' and responded_at > now() - interval '24 hours') then
        raise exception 'LOOPA:REQUEST_COOLDOWN';
    end if;
    select count(*) into n from public.friend_requests where from_user = u and status = 'pending';
    if n >= 50 then raise exception 'LOOPA:TOO_MANY_REQUESTS'; end if;

    insert into public.friend_requests(from_user, to_user) values (u, p_user) returning * into r;
    perform public._notify(p_user, 'friend_request', u, r.id::text);
    perform public._social_bump(u);
    return jsonb_build_object('status', 'sent', 'request_id', r.id);
end $$;

create or replace function public.respond_friend_request(p_request_id uuid, p_accept boolean)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); r public.friend_requests;
begin
    select * into r from public.friend_requests where id = p_request_id and to_user = u;
    if not found then raise exception 'LOOPA:REQUEST_NOT_FOUND'; end if;
    perform public._pair_lock(r.from_user, r.to_user);
    select * into r from public.friend_requests where id = p_request_id for update;
    if r.status <> 'pending' then raise exception 'LOOPA:REQUEST_NOT_FOUND'; end if;
    if coalesce(p_accept, false) then
        if public._is_blocked(r.from_user, r.to_user) then raise exception 'LOOPA:REQUEST_NOT_FOUND'; end if;
        perform public._accept_request(r);
        return jsonb_build_object('status', 'accepted');
    end if;
    update public.friend_requests set status = 'declined', responded_at = now() where id = r.id;
    update public.notifications set read_at = now()
     where user_id = u and type = 'friend_request' and ref_id = r.id::text and read_at is null;
    perform public._social_bump(u);
    perform public._social_bump(r.from_user);   -- their "sent" list changes; no notification for a no
    return jsonb_build_object('status', 'declined');
end $$;

create or replace function public.cancel_friend_request(p_request_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); r public.friend_requests;
begin
    select * into r from public.friend_requests where id = p_request_id and from_user = u;
    if not found then raise exception 'LOOPA:REQUEST_NOT_FOUND'; end if;
    perform public._pair_lock(r.from_user, r.to_user);
    update public.friend_requests set status = 'cancelled', responded_at = now()
     where id = r.id and status = 'pending';
    if not found then raise exception 'LOOPA:REQUEST_NOT_FOUND'; end if;
    delete from public.notifications where user_id = r.to_user and type = 'friend_request' and ref_id = r.id::text;
    perform public._social_bump(u);
    perform public._social_bump(r.to_user);
end $$;

create or replace function public.remove_friend(p_user uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    perform public._pair_lock(u, p_user);
    delete from public.friendships where user_a = least(u, p_user) and user_b = greatest(u, p_user);
    if not found then raise exception 'LOOPA:NOT_FRIENDS'; end if;
    update public.room_invites set status = 'cancelled', responded_at = now()
     where status = 'pending' and ((from_user = u and to_user = p_user) or (from_user = p_user and to_user = u));
    perform public._social_bump(u);
    perform public._social_bump(p_user);
end $$;

create or replace function public.block_user(p_user uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    perform public._require_profile(u);
    perform public._require_user(p_user);
    if p_user = u then raise exception 'LOOPA:CANNOT_ADD_SELF'; end if;
    perform public._pair_lock(u, p_user);
    insert into public.user_blocks(blocker_id, blocked_id) values (u, p_user) on conflict do nothing;
    delete from public.friendships where user_a = least(u, p_user) and user_b = greatest(u, p_user);
    update public.friend_requests set status = 'cancelled', responded_at = now()
     where status = 'pending' and ((from_user = u and to_user = p_user) or (from_user = p_user and to_user = u));
    update public.room_invites set status = 'cancelled', responded_at = now()
     where status = 'pending' and ((from_user = u and to_user = p_user) or (from_user = p_user and to_user = u));
    -- Their notifications disappear from my list; mine disappear from theirs.
    delete from public.notifications where (user_id = u and actor_id = p_user) or (user_id = p_user and actor_id = u);
    perform public._social_bump(u);
    perform public._social_bump(p_user);
end $$;

create or replace function public.unblock_user(p_user uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    delete from public.user_blocks where blocker_id = u and blocked_id = p_user;
    perform public._social_bump(u);
end $$;

-- ---------------------------------------------------------------------
-- Presence and privacy
-- ---------------------------------------------------------------------
create or replace function public.touch_presence(p_active boolean default true)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    perform public._require_profile(u);
    insert into public.user_presence(user_id, last_seen_at, is_active, updated_at)
    values (u, now(), coalesce(p_active, true), now())
    on conflict (user_id) do update set last_seen_at = now(), is_active = excluded.is_active, updated_at = now();
end $$;

create or replace function public.set_presence_sharing(p_share boolean)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    perform public._require_profile(u);
    insert into public.user_presence(user_id, share_presence) values (u, coalesce(p_share, true))
    on conflict (user_id) do update set share_presence = excluded.share_presence, updated_at = now();
    return jsonb_build_object('share_presence', coalesce(p_share, true));
end $$;

create or replace function public.get_social_settings()
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select jsonb_build_object('share_presence',
        coalesce((select share_presence from public.user_presence where user_id = public._uid()), true));
$$;

-- ---------------------------------------------------------------------
-- Overview (one call for the Social screen) and badges
-- ---------------------------------------------------------------------
create or replace function public.get_social_overview()
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    perform public._require_profile(u);
    perform public._expire_invites(u);
    return jsonb_build_object(
        'friends', coalesce((
            select jsonb_agg(public._user_card(f.other) || jsonb_build_object('presence', public._presence(u, f.other), 'since', f.created_at)
                             order by p.username)
              from (select case when user_a = u then user_b else user_a end as other, created_at
                      from public.friendships where user_a = u or user_b = u) f
              join public.profiles p on p.id = f.other), '[]'::jsonb),
        'incoming', coalesce((
            select jsonb_agg(jsonb_build_object('request_id', r.id, 'created_at', r.created_at, 'user', public._user_card(r.from_user))
                             order by r.created_at desc)
              from public.friend_requests r where r.to_user = u and r.status = 'pending'), '[]'::jsonb),
        'outgoing', coalesce((
            select jsonb_agg(jsonb_build_object('request_id', r.id, 'created_at', r.created_at, 'user', public._user_card(r.to_user))
                             order by r.created_at desc)
              from public.friend_requests r where r.from_user = u and r.status = 'pending'), '[]'::jsonb),
        'invites', coalesce((
            select jsonb_agg(jsonb_build_object('invite_id', i.id, 'room_id', i.room_id, 'room_code', ro.code,
                                               'game_mode', coalesce(ro.settings->>'game_mode', 'isim_sehir'),
                                               'room_status', ro.status,
                                               'created_at', i.created_at, 'expires_at', i.expires_at,
                                               'from', public._user_card(i.from_user))
                             order by i.created_at desc)
              from public.room_invites i join public.rooms ro on ro.id = i.room_id
             where i.to_user = u and i.status = 'pending'), '[]'::jsonb),
        'blocked', coalesce((
            select jsonb_agg(public._user_card(b.blocked_id) order by b.created_at desc)
              from public.user_blocks b where b.blocker_id = u), '[]'::jsonb),
        'share_presence', coalesce((select share_presence from public.user_presence where user_id = u), true)
    );
end $$;

create or replace function public.get_social_badges()
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    return jsonb_build_object(
        'notifications', (select count(*) from public.notifications where user_id = u and read_at is null),
        'messages', (select count(*) from public.direct_messages where recipient_id = u and read_at is null
                        and not exists (select 1 from public.user_blocks b where b.blocker_id = u and b.blocked_id = sender_id)),
        'requests', (select count(*) from public.friend_requests where to_user = u and status = 'pending'),
        'invites', (select count(*) from public.room_invites i join public.rooms r on r.id = i.room_id
                     where i.to_user = u and i.status = 'pending' and i.expires_at > now() and r.status <> 'closed'));
end $$;

-- ---------------------------------------------------------------------
-- Room invites (built on join_room: capacity, kicks and spectators keep their rules)
-- ---------------------------------------------------------------------
create or replace function public.send_room_invite(p_user uuid, p_room_id uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); r public.rooms; i public.room_invites; n int;
begin
    perform public._require_profile(u);
    perform public._require_user(p_user);
    select * into r from public.rooms where id = p_room_id;
    if not found or r.status = 'closed' then raise exception 'LOOPA:ROOM_NOT_FOUND'; end if;
    if not exists (select 1 from public.room_players where room_id = p_room_id and user_id = u and status = 'active') then
        raise exception 'LOOPA:NOT_IN_ROOM';
    end if;
    if not public._are_friends(u, p_user) or public._is_blocked(u, p_user) then raise exception 'LOOPA:NOT_FRIENDS'; end if;
    if exists (select 1 from public.room_players where room_id = p_room_id and user_id = p_user and status = 'active') then
        raise exception 'LOOPA:ALREADY_IN_ROOM';
    end if;
    select count(*) into n from public.room_invites where from_user = u and created_at > now() - interval '10 minutes';
    if n >= 30 then raise exception 'LOOPA:RATE_LIMITED'; end if;

    -- Inviting again refreshes the pending invite instead of stacking a second one.
    update public.room_invites set expires_at = now() + interval '15 minutes', from_user = u
     where room_id = p_room_id and to_user = p_user and status = 'pending'
    returning * into i;
    if not found then
        insert into public.room_invites(room_id, from_user, to_user, expires_at)
        values (p_room_id, u, p_user, now() + interval '15 minutes') returning * into i;
    end if;
    delete from public.notifications where user_id = p_user and type = 'room_invite' and ref_id = i.id::text;
    perform public._notify(p_user, 'room_invite', u, i.id::text,
        jsonb_build_object('room_code', r.code, 'game_mode', coalesce(r.settings->>'game_mode', 'isim_sehir')));
    perform public._social_bump(u);
    return jsonb_build_object('invite_id', i.id, 'expires_at', i.expires_at);
end $$;

-- Accepting joins the room through join_room. Expired / closed rooms are reported as a
-- status (and stored), not raised, so the invite is closed even though nothing was joined.
create or replace function public.respond_room_invite(p_invite_id uuid, p_accept boolean)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); i public.room_invites; r public.rooms; joined jsonb;
begin
    select * into i from public.room_invites where id = p_invite_id and to_user = u for update;
    if not found then raise exception 'LOOPA:INVITE_NOT_FOUND'; end if;
    update public.notifications set read_at = now()
     where user_id = u and type = 'room_invite' and ref_id = i.id::text and read_at is null;
    perform public._social_bump(u);
    if i.status <> 'pending' then
        return jsonb_build_object('status', case when i.status = 'expired' then 'expired' else 'closed' end);
    end if;
    if not coalesce(p_accept, false) then
        update public.room_invites set status = 'declined', responded_at = now() where id = i.id;
        perform public._social_bump(i.from_user);
        return jsonb_build_object('status', 'declined');
    end if;
    select * into r from public.rooms where id = i.room_id;
    if not found or r.status = 'closed' then
        update public.room_invites set status = 'expired', responded_at = now() where id = i.id;
        return jsonb_build_object('status', 'room_gone');
    end if;
    if i.expires_at <= now() then
        update public.room_invites set status = 'expired', responded_at = now() where id = i.id;
        return jsonb_build_object('status', 'expired');
    end if;
    -- Full room, kicked, spectators off ...: join_room raises and the invite stays pending.
    joined := public.join_room(r.code, false);
    update public.room_invites set status = 'accepted', responded_at = now() where id = i.id;
    perform public._social_bump(i.from_user);
    return jsonb_build_object('status', 'joined', 'room_id', joined->>'id', 'code', joined->>'code');
end $$;

-- ---------------------------------------------------------------------
-- Messages (friends only)
-- ---------------------------------------------------------------------
create or replace function public._message_json(m public.direct_messages)
returns jsonb language sql stable as $$
    select jsonb_build_object('id', m.id, 'sender_id', m.sender_id, 'recipient_id', m.recipient_id, 'body', m.body,
                              'client_id', m.client_id, 'created_at', m.created_at, 'read_at', m.read_at);
$$;

create or replace function public.send_message(p_user uuid, p_body text, p_client_id uuid default null)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); v_body text := btrim(coalesce(p_body, '')); m public.direct_messages; n int;
begin
    perform public._require_profile(u);
    if p_client_id is not null then
        select * into m from public.direct_messages where sender_id = u and client_id = p_client_id;
        if found then return public._message_json(m); end if;     -- retry of a message that already arrived
    end if;
    if char_length(v_body) = 0 then raise exception 'LOOPA:MESSAGE_EMPTY'; end if;
    if char_length(v_body) > 1000 then raise exception 'LOOPA:MESSAGE_TOO_LONG'; end if;
    if not public._are_friends(u, p_user) or public._is_blocked(u, p_user) then raise exception 'LOOPA:NOT_FRIENDS'; end if;
    select count(*) into n from public.direct_messages where sender_id = u and created_at > now() - interval '1 minute';
    if n >= 40 then raise exception 'LOOPA:RATE_LIMITED'; end if;
    insert into public.direct_messages(sender_id, recipient_id, body, client_id)
    values (u, p_user, v_body, p_client_id)
    on conflict (sender_id, client_id) do nothing
    returning * into m;
    if not found then   -- concurrent retry with the same client id
        select * into m from public.direct_messages where sender_id = u and client_id = p_client_id;
        return public._message_json(m);
    end if;
    perform public._notify(p_user, 'message', u, u::text, jsonb_build_object('preview', left(v_body, 80)));
    perform public._social_bump(u);
    return public._message_json(m);
end $$;

create or replace function public.get_messages(p_user uuid, p_before bigint default null, p_limit int default 50)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    return coalesce((
        select jsonb_agg(public._message_json(m) order by m.id desc)
          from (select * from public.direct_messages
                 where least(sender_id, recipient_id) = least(u, p_user) and greatest(sender_id, recipient_id) = greatest(u, p_user)
                   and (p_before is null or id < p_before)
                 order by id desc
                 limit greatest(1, least(coalesce(p_limit, 50), 100))) m), '[]'::jsonb);
end $$;

create or replace function public.mark_conversation_read(p_user uuid)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); n int;
begin
    update public.direct_messages set read_at = now()
     where recipient_id = u and sender_id = p_user and read_at is null;
    get diagnostics n = row_count;
    update public.notifications set read_at = now()
     where user_id = u and type = 'message' and actor_id = p_user and read_at is null;
    if n > 0 then
        perform public._social_bump(p_user);   -- their "read" ticks
    end if;
    perform public._social_bump(u);
    return n;
end $$;

create or replace function public.list_conversations()
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    return coalesce((
        select jsonb_agg(public._user_card(c.other) || jsonb_build_object(
                    'last_message', public._message_json(lm),
                    'unread', (select count(*) from public.direct_messages d
                                where d.recipient_id = u and d.sender_id = c.other and d.read_at is null),
                    'can_message', public._are_friends(u, c.other) and not public._is_blocked(u, c.other),
                    'presence', public._presence(u, c.other))
                 order by lm.id desc)
          from (select distinct case when sender_id = u then recipient_id else sender_id end as other
                  from public.direct_messages where sender_id = u or recipient_id = u) c
          cross join lateral (select * from public.direct_messages d
                               where least(d.sender_id, d.recipient_id) = least(u, c.other)
                                 and greatest(d.sender_id, d.recipient_id) = greatest(u, c.other)
                               order by d.id desc limit 1) lm
         where not exists (select 1 from public.user_blocks b where b.blocker_id = u and b.blocked_id = c.other)
    ), '[]'::jsonb);
end $$;

-- ---------------------------------------------------------------------
-- Notifications
-- ---------------------------------------------------------------------
create or replace function public.list_notifications(p_limit int default 50)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    perform public._expire_invites(u);
    return coalesce((
        select jsonb_agg(jsonb_build_object('id', n.id, 'type', n.type, 'ref_id', n.ref_id, 'payload', n.payload,
                                            'created_at', n.created_at, 'read_at', n.read_at,
                                            'actor', public._user_card(n.actor_id),
                                            'invite_status', case when n.type = 'room_invite'
                                                then (select i.status from public.room_invites i where i.id::text = n.ref_id) end)
                         order by n.created_at desc)
          from (select * from public.notifications where user_id = u
                 order by created_at desc limit greatest(1, least(coalesce(p_limit, 50), 100))) n), '[]'::jsonb);
end $$;

create or replace function public.mark_notifications_read(p_ids bigint[] default null)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); n int;
begin
    update public.notifications set read_at = now()
     where user_id = u and read_at is null and (p_ids is null or id = any(p_ids));
    get diagnostics n = row_count;
    if n > 0 then perform public._social_bump(u); end if;
    return n;
end $$;

-- ---------------------------------------------------------------------
-- Grants: client RPCs executable by signed-in users, helpers by nobody.
-- ---------------------------------------------------------------------
do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and p.proname in (
                  '_social_bump','_pair_lock','_are_friends','_is_blocked','_require_user','_notify','_user_card',
                  '_presence','_relation','_expire_invites','_accept_request','_message_json',
                  'search_users','get_user_profile','send_friend_request','respond_friend_request','cancel_friend_request',
                  'remove_friend','block_user','unblock_user','touch_presence','set_presence_sharing','get_social_settings',
                  'get_social_overview','get_social_badges','send_room_invite','respond_room_invite',
                  'send_message','get_messages','mark_conversation_read','list_conversations',
                  'list_notifications','mark_notifications_read') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
        if left(f.proname, 1) <> '_' then
            execute format('grant execute on function %s to authenticated', f.sig);
        end if;
    end loop;
end $$;
