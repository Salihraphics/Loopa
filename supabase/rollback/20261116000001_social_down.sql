-- =====================================================================
-- ROLLBACK of 20261116000001_social.sql — NOT a migration (never applied automatically).
--
-- Removes exactly what the social migration created: 33 functions, the realtime publication entry
-- of social_signals and the 8 social tables. Touches no existing table, function or row.
-- DATA LOSS: all friendships, requests, blocks, invites, messages and notifications are deleted.
-- Take a dump of these 8 tables first if any of that data must be kept.
-- Run inside one transaction:  begin; \i this file; commit;
-- =====================================================================
do $$
begin
    if exists (select 1 from pg_publication_tables
                where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'social_signals') then
        alter publication supabase_realtime drop table public.social_signals;
    end if;
end $$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and p.proname in (
                  '_social_bump','_pair_lock','_are_friends','_is_blocked','_require_user','_notify','_user_card',
                  '_presence','_relation','_expire_invites','_accept_request','_message_json',
                  'search_users','get_user_profile','send_friend_request','respond_friend_request','cancel_friend_request',
                  'remove_friend','block_user','unblock_user','touch_presence','set_presence_sharing','get_social_settings',
                  'get_social_overview','get_social_badges','send_room_invite','respond_room_invite',
                  'send_message','get_messages','mark_conversation_read','list_conversations',
                  'list_notifications','mark_notifications_read') loop
        execute format('drop function %s', f.sig);
    end loop;
end $$;

-- No existing table references these tables, so no CASCADE is needed (and none is used).
drop table if exists public.notifications;
drop table if exists public.direct_messages;
drop table if exists public.room_invites;
drop table if exists public.social_signals;
drop table if exists public.user_presence;
drop table if exists public.user_blocks;
drop table if exists public.friendships;
drop table if exists public.friend_requests;
