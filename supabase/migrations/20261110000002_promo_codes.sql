-- =====================================================================
-- LOOPA — promo codes (stage 2b)
--
-- LOPPAWEEK: 7 days of LOOPA Plus, once per account, only for accounts
-- that have a Google identity linked (no anonymous guests: a new guest
-- account is free to create, so "once per account" would be meaningless).
--
-- * Codes are never readable by clients (no RLS policy, no grant).
-- * redeem_promo_code() is atomic: the code row is locked, the
--   (code, user) pair is unique, and the entitlement is created in the
--   same transaction. A retry after success returns the same result
--   ('already_redeemed') instead of granting twice.
-- * The input is trimmed and compared case-insensitively. The spelling
--   itself must be exact: "LOPAWEEK" or "LOPPA WEEK" are rejected.
-- * Validity window, activation and the redemption cap are managed
--   server-side (admin_set_promo_code). LOPPAWEEK is created INACTIVE so it
--   cannot be used before the release that shows Plus features.
-- =====================================================================

create table if not exists public.promo_codes (
    code            text primary key check (code ~ '^[A-Z0-9]{4,24}$'),
    plus_days       int  not null check (plus_days between 1 and 90),
    active          boolean not null default false,
    valid_from      timestamptz,
    valid_until     timestamptz,
    max_redemptions int check (max_redemptions is null or max_redemptions > 0),
    redemptions     int not null default 0,
    requires_google boolean not null default true,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    check (valid_until is null or valid_from is null or valid_until > valid_from)
);

create table if not exists public.promo_redemptions (
    id             uuid primary key default gen_random_uuid(),
    code           text not null references public.promo_codes(code),
    user_id        uuid not null references public.profiles(id) on delete cascade,
    entitlement_id uuid references public.entitlements(id) on delete set null,
    redeemed_at    timestamptz not null default now(),
    unique (code, user_id)                         -- once per account
);
create index if not exists promo_redemptions_user_idx on public.promo_redemptions(user_id);

alter table public.promo_codes       enable row level security;
alter table public.promo_redemptions enable row level security;
drop policy if exists promo_redemptions_own on public.promo_redemptions;
create policy promo_redemptions_own on public.promo_redemptions for select to authenticated using (user_id = auth.uid());
revoke all on public.promo_codes, public.promo_redemptions from anon, authenticated;
grant select on public.promo_redemptions to authenticated;

insert into public.promo_codes(code, plus_days, active, requires_google)
values ('LOPPAWEEK', 7, false, true)
on conflict (code) do nothing;

-- ---------------------------------------------------------------------
-- Client RPC
-- ---------------------------------------------------------------------
-- Errors: PROMO_INVALID (unknown, inactive or outside its window — deliberately one code so
-- codes cannot be probed), PROMO_EXHAUSTED, GOOGLE_LINK_REQUIRED, PLUS_ALREADY_UNLIMITED, NO_PROFILE.
create or replace function public.redeem_promo_code(p_code text)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare
    u uuid := public._uid();
    v_code text := upper(btrim(coalesce(p_code, '')));
    pc public.promo_codes;
    prev public.promo_redemptions;
    e public.entitlements;
    v_until timestamptz;
begin
    perform public._require_profile(u);
    if v_code !~ '^[A-Z0-9]{4,24}$' then raise exception 'LOOPA:PROMO_INVALID'; end if;

    select * into pc from public.promo_codes where code = v_code for update;
    if not found then raise exception 'LOOPA:PROMO_INVALID'; end if;

    -- Idempotent: a second call by the same account returns the first result
    -- (also after the code has expired), never a second grant.
    select * into prev from public.promo_redemptions where code = v_code and user_id = u;
    if found then
        v_until := public._plus_until(u);
        return jsonb_build_object('status', 'already_redeemed', 'code', v_code,
                                  'redeemed_at', prev.redeemed_at,
                                  'plus_until', case when v_until = 'infinity'::timestamptz then null else v_until end);
    end if;

    if not pc.active
       or (pc.valid_from is not null and now() < pc.valid_from)
       or (pc.valid_until is not null and now() >= pc.valid_until) then
        raise exception 'LOOPA:PROMO_INVALID';
    end if;

    if pc.requires_google and not public._is_google_linked(u) then
        raise exception 'LOOPA:GOOGLE_LINK_REQUIRED';
    end if;
    if pc.max_redemptions is not null and pc.redemptions >= pc.max_redemptions then
        raise exception 'LOOPA:PROMO_EXHAUSTED';
    end if;

    e := public._grant_plus(u, pc.plus_days, 'promo', v_code, u, jsonb_build_object('code', v_code));
    insert into public.promo_redemptions(code, user_id, entitlement_id) values (v_code, u, e.id);
    update public.promo_codes set redemptions = redemptions + 1, updated_at = now() where code = v_code;

    v_until := public._plus_until(u);
    return jsonb_build_object('status', 'redeemed', 'code', v_code, 'days', pc.plus_days,
                              'starts_at', e.starts_at, 'ends_at', e.ends_at,
                              'plus_until', case when v_until = 'infinity'::timestamptz then null else v_until end);
end $$;

-- ---------------------------------------------------------------------
-- Admin RPC: create or change a code (never a code that grants admin).
-- ---------------------------------------------------------------------
create or replace function public.admin_set_promo_code(
    p_code text, p_plus_days int, p_active boolean,
    p_valid_from timestamptz default null, p_valid_until timestamptz default null,
    p_max_redemptions int default null)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare a uuid := public._require_admin(); v_code text := upper(btrim(coalesce(p_code, ''))); pc public.promo_codes;
begin
    if v_code !~ '^[A-Z0-9]{4,24}$' or p_plus_days is null or p_plus_days not between 1 and 90
       or (p_valid_from is not null and p_valid_until is not null and p_valid_until <= p_valid_from)
       or (p_max_redemptions is not null and p_max_redemptions < 1) then
        raise exception 'LOOPA:INVALID_INPUT';
    end if;
    insert into public.promo_codes(code, plus_days, active, valid_from, valid_until, max_redemptions)
    values (v_code, p_plus_days, coalesce(p_active, false), p_valid_from, p_valid_until, p_max_redemptions)
    on conflict (code) do update set
        plus_days = excluded.plus_days, active = excluded.active, valid_from = excluded.valid_from,
        valid_until = excluded.valid_until, max_redemptions = excluded.max_redemptions, updated_at = now()
    returning * into pc;
    perform public._audit(a, 'admin', 'set_promo_code', null, to_jsonb(pc));
    return to_jsonb(pc);
end $$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and p.proname in ('redeem_promo_code','admin_set_promo_code') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
        execute format('grant execute on function %s to authenticated', f.sig);
    end loop;
end $$;
