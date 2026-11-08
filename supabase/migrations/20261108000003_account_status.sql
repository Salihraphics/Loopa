-- =====================================================================
-- LOOPA — account status for guest (anonymous) and linked accounts.
-- Identity linking itself is done by Supabase Auth (manual linking):
-- the user id never changes, so every player row (profile, XP, cosmetics,
-- statistics, history) stays attached automatically. Nothing is copied.
-- =====================================================================
create or replace function public.get_account_status()
returns jsonb language sql stable security definer set search_path = public, auth, pg_temp as $$
    select jsonb_build_object(
        'user_id', u.id,
        'is_anonymous', coalesce(u.is_anonymous, false),
        'email', u.email,
        'providers', coalesce((select jsonb_agg(distinct i.provider order by i.provider)
                                 from auth.identities i where i.user_id = u.id), '[]'::jsonb),
        'created_at', u.created_at,
        'has_profile', exists (select 1 from public.profiles p where p.id = u.id)
    )
    from auth.users u where u.id = auth.uid();
$$;

revoke all on function public.get_account_status() from public, anon;
grant execute on function public.get_account_status() to authenticated;
