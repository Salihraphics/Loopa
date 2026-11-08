-- =====================================================================
-- LOOPA — plans, entitlements and admin roles (stage 2a)
--
-- Backward compatible: new tables and new functions only. Nothing that
-- exists today is changed or removed.
--
-- * A user's plan is never stored as a flag that clients can set. It is
--   computed by _user_plan() from:
--     - an active admin role (admin_allowlist entry bound to the user id), or
--     - an active entitlement row (source promo / admin / store).
-- * entitlements.source = 'store' is reserved for the future Google Play
--   Billing verification (an Edge Function with the service role that
--   verifies the purchase token and writes the row). No payment code exists.
-- * The admin e-mail allowlist lives only in the database. It is managed
--   from the Supabase dashboard SQL editor (postgres role) with
--   _admin_allowlist_add / _admin_allowlist_revoke. No e-mail address is in
--   the source code, the migrations or the APK.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Plans (catalogue)
-- ---------------------------------------------------------------------
create table if not exists public.plans (
    id         text primary key check (id in ('free','plus','admin')),
    name_tr    text not null,
    rank       int  not null,                   -- free < plus < admin
    store_product_id text                        -- future Google Play product id
);
insert into public.plans(id, name_tr, rank) values
    ('free', 'Ücretsiz', 0), ('plus', 'LOOPA Plus', 1), ('admin', 'Admin', 2)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------
-- Entitlements: time windows of Plus access
-- ---------------------------------------------------------------------
create table if not exists public.entitlements (
    id          uuid primary key default gen_random_uuid(),
    user_id     uuid not null references public.profiles(id) on delete cascade,
    plan        text not null default 'plus' references public.plans(id) check (plan = 'plus'),
    source      text not null check (source in ('promo','admin','store')),
    source_ref  text,                            -- promo code / admin note key / store order id
    starts_at   timestamptz not null,
    ends_at     timestamptz,                     -- null = no end (admin grants only)
    revoked_at  timestamptz,
    created_by  uuid references public.profiles(id) on delete set null,
    metadata    jsonb not null default '{}'::jsonb,
    created_at  timestamptz not null default now(),
    check (ends_at is null or ends_at > starts_at),
    check (ends_at is not null or source = 'admin')
);
create index if not exists entitlements_user_idx on public.entitlements(user_id, ends_at);
-- A store order can only ever create one entitlement (idempotent purchase sync).
create unique index if not exists entitlements_store_ref_uidx
    on public.entitlements(source_ref) where source = 'store';

-- ---------------------------------------------------------------------
-- Admin: allowlist (server only), roles bound to user ids, audit log
-- ---------------------------------------------------------------------
create table if not exists public.admin_allowlist (
    email          text primary key check (email = lower(btrim(email)) and email like '%_@_%'),
    note           text,
    added_at       timestamptz not null default now(),
    bound_user_id  uuid unique references auth.users(id) on delete set null,
    bound_at       timestamptz,
    revoked_at     timestamptz
);

create table if not exists public.user_roles (
    user_id     uuid primary key references public.profiles(id) on delete cascade,
    role        text not null check (role in ('admin')),
    source      text not null default 'allowlist' check (source in ('allowlist')),
    granted_at  timestamptz not null default now(),
    revoked_at  timestamptz
);

create table if not exists public.admin_audit_log (
    id          bigint generated always as identity primary key,
    actor_id    uuid references auth.users(id) on delete set null,
    actor_kind  text not null check (actor_kind in ('admin','console','system')),
    action      text not null,
    target_user uuid,
    details     jsonb not null default '{}'::jsonb,
    created_at  timestamptz not null default now()
);
create index if not exists admin_audit_log_created_idx on public.admin_audit_log(created_at desc);

alter table public.plans            enable row level security;
alter table public.entitlements     enable row level security;
alter table public.admin_allowlist  enable row level security;
alter table public.user_roles       enable row level security;
alter table public.admin_audit_log  enable row level security;

-- Clients may read the plan catalogue and their own entitlement windows. Nothing else.
drop policy if exists plans_read on public.plans;
create policy plans_read on public.plans for select to authenticated using (true);
drop policy if exists entitlements_own on public.entitlements;
create policy entitlements_own on public.entitlements for select to authenticated using (user_id = auth.uid());
-- admin_allowlist, user_roles, admin_audit_log: no policy => invisible to clients.

revoke all on public.plans, public.entitlements, public.admin_allowlist,
              public.user_roles, public.admin_audit_log from anon, authenticated;
grant select on public.plans, public.entitlements to authenticated;

-- ---------------------------------------------------------------------
-- Helpers
-- ---------------------------------------------------------------------
-- Serialises every entitlement change of one user (stacking must not race).
create or replace function public._lock_user_entitlements(p_user uuid)
returns void language sql security definer set search_path = public, pg_temp as $$
    select pg_advisory_xact_lock(hashtextextended('loopa:entitlements:' || p_user::text, 0));
$$;

-- End of the user's continuous Plus access that covers p_at (null if none at p_at).
-- 'infinity' when an open-ended admin grant is active.
create or replace function public._plus_until(p_user uuid, p_at timestamptz default now())
returns timestamptz language plpgsql stable security definer set search_path = public, pg_temp as $$
declare t timestamptz := p_at; nxt timestamptz; guard int := 0;
begin
    loop
        guard := guard + 1;
        exit when guard > 1000;
        select max(coalesce(e.ends_at, 'infinity'::timestamptz)) into nxt
          from public.entitlements e
         where e.user_id = p_user and e.revoked_at is null
           and e.starts_at <= t and coalesce(e.ends_at, 'infinity'::timestamptz) > t;
        exit when nxt is null;
        t := nxt;
        exit when t = 'infinity'::timestamptz;
    end loop;
    if t = p_at then return null; end if;
    return t;
end $$;

-- Verified Google e-mail of the user, or null. Only a linked Google identity counts.
create or replace function public._google_email(p_user uuid)
returns text language sql stable security definer set search_path = public, auth, pg_temp as $$
    select lower(btrim(i.identity_data->>'email'))
      from auth.identities i
     where i.user_id = p_user and i.provider = 'google'
       and coalesce(i.identity_data->>'email', '') <> ''
       and coalesce((i.identity_data->>'email_verified')::boolean, false)
     order by i.created_at
     limit 1;
$$;

create or replace function public._is_google_linked(p_user uuid)
returns boolean language sql stable security definer set search_path = public, auth, pg_temp as $$
    select exists (select 1 from auth.users u where u.id = p_user and not coalesce(u.is_anonymous, false))
       and exists (select 1 from auth.identities i where i.user_id = p_user and i.provider = 'google');
$$;

create or replace function public._is_admin(p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
    select exists (
        select 1 from public.user_roles r
          join public.admin_allowlist a on a.bound_user_id = r.user_id
         where r.user_id = p_user and r.role = 'admin' and r.revoked_at is null and a.revoked_at is null);
$$;

-- The one place that decides a user's plan.
create or replace function public._user_plan(p_user uuid, p_at timestamptz default now())
returns text language sql stable security definer set search_path = public, pg_temp as $$
    select case
        when p_user is null then 'free'
        when public._is_admin(p_user) then 'admin'
        when public._plus_until(p_user, p_at) is not null then 'plus'
        else 'free' end;
$$;

create or replace function public._audit(p_actor uuid, p_kind text, p_action text, p_target uuid, p_details jsonb)
returns void language sql security definer set search_path = public, pg_temp as $$
    insert into public.admin_audit_log(actor_id, actor_kind, action, target_user, details)
    values (p_actor, p_kind, p_action, p_target, coalesce(p_details, '{}'::jsonb));
$$;

-- Binds an allowlisted, verified Google e-mail to the calling user id (first claim wins).
create or replace function public._try_claim_admin(p_user uuid)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare v_email text; a public.admin_allowlist;
begin
    if public._is_admin(p_user) then return true; end if;
    if not public._is_google_linked(p_user) then return false; end if;
    v_email := public._google_email(p_user);
    if v_email is null then return false; end if;
    select * into a from public.admin_allowlist where email = v_email for update;
    if not found or a.revoked_at is not null then return false; end if;
    if a.bound_user_id is not null and a.bound_user_id <> p_user then return false; end if;
    if not exists (select 1 from public.profiles where id = p_user) then return false; end if;
    update public.admin_allowlist set bound_user_id = p_user, bound_at = coalesce(bound_at, now())
     where email = v_email;
    insert into public.user_roles(user_id, role) values (p_user, 'admin')
    on conflict (user_id) do update set revoked_at = null, granted_at = now();
    perform public._audit(p_user, 'system', 'admin_claimed', p_user, '{}'::jsonb);
    return true;
end $$;

-- ---------------------------------------------------------------------
-- Client RPC
-- ---------------------------------------------------------------------
-- Current plan of the caller. Also binds an allowlisted admin on first call.
create or replace function public.get_my_plan()
returns jsonb language plpgsql volatile security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); v_until timestamptz; v_plan text;
begin
    perform public._try_claim_admin(u);
    v_plan := public._user_plan(u);
    v_until := public._plus_until(u);
    return jsonb_build_object(
        'plan', v_plan,
        'is_admin', v_plan = 'admin',
        'plus_until', case when v_until = 'infinity'::timestamptz then null else v_until end,
        'plus_unlimited', v_until = 'infinity'::timestamptz,
        'google_linked', public._is_google_linked(u),
        'server_time', now(),
        'entitlements', coalesce((select jsonb_agg(jsonb_build_object(
                'id', e.id, 'source', e.source, 'starts_at', e.starts_at, 'ends_at', e.ends_at,
                'revoked', e.revoked_at is not null) order by e.starts_at)
            from public.entitlements e where e.user_id = u), '[]'::jsonb)
    );
end $$;

-- ---------------------------------------------------------------------
-- Admin RPCs (every call is checked server-side and logged)
-- ---------------------------------------------------------------------
create or replace function public._require_admin()
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid();
begin
    if not public._is_admin(u) then raise exception 'LOOPA:NOT_ADMIN'; end if;
    return u;
end $$;

-- Grants p_days of Plus (stacked after the user's current Plus), or open-ended when p_days is null.
create or replace function public._grant_plus(p_user uuid, p_days int, p_source text, p_ref text,
                                             p_by uuid, p_metadata jsonb default '{}'::jsonb)
returns public.entitlements language plpgsql security definer set search_path = public, pg_temp as $$
declare v_start timestamptz; v_until timestamptz; e public.entitlements;
begin
    perform public._lock_user_entitlements(p_user);
    v_until := public._plus_until(p_user);
    if p_days is not null and v_until = 'infinity'::timestamptz then
        raise exception 'LOOPA:PLUS_ALREADY_UNLIMITED';
    end if;
    v_start := greatest(now(), coalesce(v_until, now()));
    insert into public.entitlements(user_id, source, source_ref, starts_at, ends_at, created_by, metadata)
    values (p_user, p_source, p_ref, v_start,
            case when p_days is null then null else v_start + make_interval(days => p_days) end,
            p_by, coalesce(p_metadata, '{}'::jsonb))
    returning * into e;
    return e;
end $$;

create or replace function public.admin_grant_plus(p_user uuid, p_days int, p_note text default null)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare a uuid := public._require_admin(); e public.entitlements;
begin
    if p_days is not null and (p_days < 1 or p_days > 3660) then raise exception 'LOOPA:INVALID_INPUT'; end if;
    if not exists (select 1 from public.profiles where id = p_user) then raise exception 'LOOPA:PLAYER_NOT_FOUND'; end if;
    e := public._grant_plus(p_user, p_days, 'admin', left(p_note, 200), a);
    perform public._audit(a, 'admin', 'grant_plus', p_user,
        jsonb_build_object('entitlement_id', e.id, 'days', p_days, 'note', left(p_note, 200)));
    return jsonb_build_object('entitlement_id', e.id, 'starts_at', e.starts_at, 'ends_at', e.ends_at);
end $$;

create or replace function public.admin_revoke_entitlement(p_entitlement_id uuid, p_note text default null)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare a uuid := public._require_admin(); v_user uuid;
begin
    update public.entitlements set revoked_at = now()
     where id = p_entitlement_id and revoked_at is null
    returning user_id into v_user;
    if v_user is null then raise exception 'LOOPA:NOT_FOUND'; end if;
    perform public._audit(a, 'admin', 'revoke_entitlement', v_user,
        jsonb_build_object('entitlement_id', p_entitlement_id, 'note', left(p_note, 200)));
end $$;

create or replace function public.admin_user_overview(p_user uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare a uuid := public._require_admin(); v_until timestamptz;
begin
    if not exists (select 1 from public.profiles where id = p_user) then raise exception 'LOOPA:PLAYER_NOT_FOUND'; end if;
    v_until := public._plus_until(p_user);
    perform public._audit(a, 'admin', 'view_user', p_user, '{}'::jsonb);
    return jsonb_build_object(
        'user_id', p_user,
        'username', (select username from public.profiles where id = p_user),
        'plan', public._user_plan(p_user),
        'plus_until', case when v_until = 'infinity'::timestamptz then null else v_until end,
        'plus_unlimited', v_until = 'infinity'::timestamptz,
        'google_linked', public._is_google_linked(p_user),
        'entitlements', coalesce((select jsonb_agg(to_jsonb(e) order by e.starts_at)
                                    from public.entitlements e where e.user_id = p_user), '[]'::jsonb));
end $$;

-- ---------------------------------------------------------------------
-- Console-only helpers for the dashboard SQL editor (postgres role).
-- Not executable by anon / authenticated.
--   select public._admin_allowlist_add('someone@example.com', 'why');
--   select public._admin_allowlist_revoke('someone@example.com');
-- ---------------------------------------------------------------------
create or replace function public._admin_allowlist_add(p_email text, p_note text default null)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare v text := lower(btrim(p_email));
begin
    insert into public.admin_allowlist(email, note) values (v, p_note)
    on conflict (email) do update set revoked_at = null, note = coalesce(excluded.note, admin_allowlist.note);
    update public.user_roles r set revoked_at = null
      from public.admin_allowlist a where a.email = v and a.bound_user_id = r.user_id;
    perform public._audit(null, 'console', 'allowlist_add', null, jsonb_build_object('email_hash', md5(v)));
end $$;

create or replace function public._admin_allowlist_revoke(p_email text)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare v text := lower(btrim(p_email)); v_user uuid;
begin
    update public.admin_allowlist set revoked_at = now() where email = v returning bound_user_id into v_user;
    if v_user is not null then
        update public.user_roles set revoked_at = now() where user_id = v_user and revoked_at is null;
    end if;
    perform public._audit(null, 'console', 'allowlist_revoke', v_user, jsonb_build_object('email_hash', md5(v)));
end $$;

-- ---------------------------------------------------------------------
-- Grants
-- ---------------------------------------------------------------------
do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and p.proname in (
                  '_lock_user_entitlements','_plus_until','_google_email','_is_google_linked','_is_admin',
                  '_user_plan','_audit','_try_claim_admin','get_my_plan','_require_admin','_grant_plus',
                  'admin_grant_plus','admin_revoke_entitlement','admin_user_overview',
                  '_admin_allowlist_add','_admin_allowlist_revoke') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
        if left(f.proname, 1) <> '_' then
            execute format('grant execute on function %s to authenticated', f.sig);
        end if;
    end loop;
end $$;
