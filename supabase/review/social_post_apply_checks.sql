-- Read-only checks after applying 20261116000001_social.sql (one SELECT; safe on production).
-- Every "want" column must equal its "got" column.
with soc(t) as (values ('friend_requests'),('friendships'),('user_blocks'),('user_presence'),
                       ('room_invites'),('direct_messages'),('notifications'),('social_signals'))
select
  (select count(*) from pg_class c join soc on soc.t = c.relname where c.relnamespace = 'public'::regnamespace) as tables_got, 8 as tables_want,
  (select count(*) from pg_class c join soc on soc.t = c.relname where c.relnamespace = 'public'::regnamespace and c.relrowsecurity) as rls_got, 8 as rls_want,
  (select count(*) from pg_policies p join soc on soc.t = p.tablename where p.schemaname = 'public') as policies_got, 8 as policies_want,
  (select count(*) from pg_proc where pronamespace = 'public'::regnamespace and proname in (
      '_social_bump','_pair_lock','_are_friends','_is_blocked','_require_user','_notify','_user_card','_presence','_relation',
      '_expire_invites','_accept_request','_message_json','search_users','get_user_profile','send_friend_request',
      'respond_friend_request','cancel_friend_request','remove_friend','block_user','unblock_user','touch_presence',
      'set_presence_sharing','get_social_settings','get_social_overview','get_social_badges','send_room_invite',
      'respond_room_invite','send_message','get_messages','mark_conversation_read','list_conversations',
      'list_notifications','mark_notifications_read')) as functions_got, 33 as functions_want,
  (select count(*) from pg_publication_tables where pubname = 'supabase_realtime' and tablename in ('room_signals','social_signals')) as realtime_got, 2 as realtime_want,
  (select count(*) from pg_class c join soc on soc.t = c.relname, unnest(array['anon','authenticated']) r
    where c.relnamespace = 'public'::regnamespace and has_table_privilege(r, c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE')) as client_write_privs_got, 0 as client_write_privs_want,
  (select count(*) from pg_class c join soc on soc.t = c.relname where c.relnamespace = 'public'::regnamespace and has_table_privilege('anon', c.oid, 'SELECT')) as anon_read_got, 0 as anon_read_want,
  (select count(*) from unnest(array[pg_get_serial_sequence('public.direct_messages','id'), pg_get_serial_sequence('public.notifications','id')]) s,
          unnest(array['anon','authenticated']) r where has_sequence_privilege(r, s, 'USAGE') or has_sequence_privilege(r, s, 'UPDATE')) as sequence_privs_got, 0 as sequence_privs_want,
  (select count(*) from pg_proc where pronamespace = 'public'::regnamespace and proname like '\_%'
      and proname in ('_social_bump','_notify','_pair_lock','_accept_request','_expire_invites','_presence','_relation')
      and has_function_privilege('authenticated', oid, 'EXECUTE')) as helper_exec_got, 0 as helper_exec_want,
  (select count(*) from pg_proc where pronamespace = 'public'::regnamespace and proname in ('send_message','search_users','get_social_overview','send_friend_request')
      and has_function_privilege('anon', oid, 'EXECUTE')) as anon_exec_got, 0 as anon_exec_want,
  (select count(*) from public.friendships) + (select count(*) from public.direct_messages) as social_rows_right_after_apply
