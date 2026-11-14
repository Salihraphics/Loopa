-- =====================================================================
-- LOOPA 0.7.0 — social platform: friends, blocks, presence, invites,
-- messages, notifications and row level security.
-- Runs inside one transaction that is rolled back: safe on any database.
-- =====================================================================
\set ON_ERROR_STOP 1
set client_min_messages = warning;
begin;

create or replace function pg_temp.act(p uuid) returns void language sql as $$
    select set_config('request.jwt.claim.sub', coalesce(p::text, ''), true);
$$;
create or replace function pg_temp.expect_error(p_sql text, p_code text) returns void language plpgsql as $$
begin
    execute p_sql;
    raise exception 'expected error % but statement succeeded: %', p_code, p_sql;
exception when others then
    if sqlerrm not like '%' || p_code || '%' then
        raise exception 'expected error % but got: % (statement: %)', p_code, sqlerrm, p_sql;
    end if;
end $$;
create or replace function pg_temp.check(ok boolean, what text) returns void language plpgsql as $$
begin
    if not coalesce(ok, false) then raise exception 'CHECK FAILED: %', what; end if;
end $$;

insert into auth.users(id, is_anonymous) values
 ('20000000-0000-0000-0000-0000000000a1', true), ('20000000-0000-0000-0000-0000000000a2', true),
 ('20000000-0000-0000-0000-0000000000a3', true), ('20000000-0000-0000-0000-0000000000a4', true),
 ('20000000-0000-0000-0000-0000000000a5', true), ('20000000-0000-0000-0000-0000000000a6', true);

do $$
declare
    A uuid := '20000000-0000-0000-0000-0000000000a1';
    B uuid := '20000000-0000-0000-0000-0000000000a2';
    C uuid := '20000000-0000-0000-0000-0000000000a3';
    D uuid := '20000000-0000-0000-0000-0000000000a4';
    E uuid := '20000000-0000-0000-0000-0000000000a5';
    F uuid := '20000000-0000-0000-0000-0000000000a6';
    v jsonb; req uuid; req2 uuid; room jsonb; room2 jsonb; inv uuid; m1 jsonb; m2 jsonb; n int; cid uuid := gen_random_uuid();
begin
    perform pg_temp.act(A); perform public.ensure_profile('Sosyal Ayşe');
    perform pg_temp.act(B); perform public.ensure_profile('Sosyal Barış');
    perform pg_temp.act(C); perform public.ensure_profile('Sosyal Cem');
    perform pg_temp.act(D); perform public.ensure_profile('Sosyal Deniz');
    perform pg_temp.act(E); perform public.ensure_profile('Elif 100');
    perform pg_temp.act(F); perform public.ensure_profile('Sosyal Fırat');

    -- ---------------------------------------------------------------- search
    perform pg_temp.act(A);
    perform pg_temp.expect_error('select public.search_users(''a'')', 'SEARCH_TOO_SHORT');
    v := public.search_users('sosyal');
    perform pg_temp.check(jsonb_array_length(v) = 4, 'search finds the 4 other Sosyal users');
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(v) x where (x->>'user_id')::uuid = A), 'search hides self');
    perform pg_temp.check((v->0->>'relation') = 'none' and (v->0) ? 'level' and (v->0) ? 'avatar', 'search result card');
    -- wildcards are literal
    perform pg_temp.check(jsonb_array_length(public.search_users('100')) = 1, 'plain match');
    perform pg_temp.check(jsonb_array_length(public.search_users('0%')) = 0, '% is literal, not a wildcard');
    perform pg_temp.check(jsonb_array_length(public.search_users('_1')) = 0, '_ is literal, not a wildcard');
    perform pg_temp.check(jsonb_array_length(public.search_users('SOSYAL CEM')) = 1, 'case-insensitive');

    -- ------------------------------------------------------- friend requests
    perform pg_temp.expect_error(format('select public.send_friend_request(%L)', A), 'CANNOT_ADD_SELF');
    perform pg_temp.expect_error(format('select public.send_friend_request(%L)', gen_random_uuid()), 'USER_NOT_FOUND');
    v := public.send_friend_request(B);
    perform pg_temp.check(v->>'status' = 'sent', 'request sent');
    req := (v->>'request_id')::uuid;
    perform pg_temp.expect_error(format('select public.send_friend_request(%L)', B), 'REQUEST_ALREADY_SENT');
    perform pg_temp.check(public._relation(A, B) = 'outgoing', 'A sees outgoing');
    perform pg_temp.check((public.get_social_overview()->'outgoing'->0->'user'->>'user_id')::uuid = B, 'outgoing list');

    perform pg_temp.act(B);
    perform pg_temp.check((public.get_social_badges()->>'requests')::int = 1, 'B has 1 request badge');
    perform pg_temp.check((public.get_social_badges()->>'notifications')::int = 1, 'B has 1 notification');
    perform pg_temp.check(public.list_notifications()->0->>'type' = 'friend_request', 'notification type');
    perform pg_temp.check((public.get_social_overview()->'incoming'->0->>'request_id')::uuid = req, 'incoming list');
    -- C cannot answer or cancel someone else's request
    perform pg_temp.act(C);
    perform pg_temp.expect_error(format('select public.respond_friend_request(%L, true)', req), 'REQUEST_NOT_FOUND');
    perform pg_temp.expect_error(format('select public.cancel_friend_request(%L)', req), 'REQUEST_NOT_FOUND');
    -- the sender cannot accept their own request
    perform pg_temp.act(A);
    perform pg_temp.expect_error(format('select public.respond_friend_request(%L, true)', req), 'REQUEST_NOT_FOUND');

    perform pg_temp.act(B);
    v := public.respond_friend_request(req, true);
    perform pg_temp.check(v->>'status' = 'accepted', 'accepted');
    perform pg_temp.check(public._are_friends(A, B), 'A and B are friends');
    perform pg_temp.check((public.get_social_badges()->>'notifications')::int = 0, 'request notification answered');
    perform pg_temp.expect_error(format('select public.respond_friend_request(%L, true)', req), 'REQUEST_NOT_FOUND');
    perform pg_temp.expect_error(format('select public.send_friend_request(%L)', A), 'ALREADY_FRIENDS');
    perform pg_temp.act(A);
    perform pg_temp.check(public.list_notifications()->0->>'type' = 'friend_accepted', 'A told about acceptance');
    perform pg_temp.check(jsonb_array_length(public.get_social_overview()->'friends') = 1, 'A has 1 friend');

    -- mutual: C asks D, then D asks C => becomes a friendship, one row, no second pending request
    perform pg_temp.act(C); perform public.send_friend_request(D);
    perform pg_temp.act(D); v := public.send_friend_request(C);
    perform pg_temp.check(v->>'status' = 'accepted', 'mutual request accepts');
    perform pg_temp.check(public._are_friends(C, D), 'C and D are friends');
    perform pg_temp.check((select count(*) from public.friend_requests where status = 'pending'
                             and C in (from_user, to_user) and D in (from_user, to_user)) = 0, 'no pending left');

    -- decline and cooldown
    perform pg_temp.act(E); v := public.send_friend_request(A); req2 := (v->>'request_id')::uuid;
    perform pg_temp.act(A); perform pg_temp.check(public.respond_friend_request(req2, false)->>'status' = 'declined', 'declined');
    perform pg_temp.act(E);
    perform pg_temp.expect_error(format('select public.send_friend_request(%L)', A), 'REQUEST_COOLDOWN');
    -- cancel
    perform pg_temp.act(F); v := public.send_friend_request(A); req2 := (v->>'request_id')::uuid;
    perform public.cancel_friend_request(req2);
    perform pg_temp.act(A);
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(public.list_notifications()) x
                                        where x->>'ref_id' = req2::text), 'cancelled request leaves no notification');

    -- ---------------------------------------------------------------- presence
    perform pg_temp.act(B); perform public.touch_presence(true);
    perform pg_temp.act(A); perform public.touch_presence(true);
    v := public.get_social_overview()->'friends'->0->'presence';
    perform pg_temp.check((v->>'visible')::boolean and (v->>'online')::boolean, 'friend online');
    perform pg_temp.check((public.get_user_profile(C)->'presence'->>'visible')::boolean = false, 'non-friend presence hidden');
    perform pg_temp.act(B); perform public.set_presence_sharing(false);
    perform pg_temp.act(A);
    v := public.get_social_overview()->'friends'->0->'presence';
    perform pg_temp.check(not (v->>'visible')::boolean and v->>'last_seen_at' is null, 'hidden presence: no last seen');
    perform pg_temp.act(B); perform public.set_presence_sharing(true);
    perform public.touch_presence(false);
    perform pg_temp.act(A);
    perform pg_temp.check(not (public.get_social_overview()->'friends'->0->'presence'->>'online')::boolean, 'went offline');

    -- ---------------------------------------------------------------- messages
    perform pg_temp.expect_error(format('select public.send_message(%L, %L)', C, 'selam'), 'NOT_FRIENDS');
    perform pg_temp.expect_error(format('select public.send_message(%L, %L)', B, '   '), 'MESSAGE_EMPTY');
    perform pg_temp.expect_error(format('select public.send_message(%L, %L)', B, repeat('x', 1001)), 'MESSAGE_TOO_LONG');
    m1 := public.send_message(B, '  Selam Barış! ', cid);
    perform pg_temp.check(m1->>'body' = 'Selam Barış!', 'body trimmed');
    m2 := public.send_message(B, 'Selam Barış!', cid);
    perform pg_temp.check(m1->>'id' = m2->>'id', 'same client id => same message (idempotent retry)');
    perform public.send_message(B, 'Oyun?');
    perform pg_temp.act(B);
    perform pg_temp.check((public.get_social_badges()->>'messages')::int = 2, '2 unread messages');
    v := public.list_notifications();
    perform pg_temp.check((select count(*) from jsonb_array_elements(v) x where x->>'type' = 'message') = 1, 'messages collapse into one notification');
    perform pg_temp.check((select (x->'payload'->>'count')::int from jsonb_array_elements(v) x where x->>'type' = 'message') = 2, 'count 2');
    v := public.list_conversations();
    perform pg_temp.check(jsonb_array_length(v) = 1 and (v->0->>'unread')::int = 2 and v->0->'last_message'->>'body' = 'Oyun?', 'conversation list');
    perform pg_temp.check(jsonb_array_length(public.get_messages(A)) = 2, 'history');
    perform pg_temp.check(public.get_messages(A, (m1->>'id')::bigint + 1, 1)->0->>'id' = m1->>'id', 'paging before id');
    perform pg_temp.check(public.mark_conversation_read(A) = 2, 'marked read');
    perform pg_temp.check((public.get_social_badges()->>'messages')::int = 0
                          and (public.get_social_badges()->>'notifications')::int = 0, 'read clears badges');
    perform pg_temp.act(A);
    perform pg_temp.check((public.get_messages(B)->0->>'read_at') is not null, 'sender sees read receipt');
    -- outsiders see nothing
    perform pg_temp.act(C);
    perform pg_temp.check(jsonb_array_length(public.get_messages(A)) = 0, 'C cannot read A-B history');
    perform pg_temp.check(public.mark_conversation_read(A) = 0, 'C cannot mark A messages');

    -- ---------------------------------------------------------------- invites
    perform pg_temp.act(A);
    room := public.create_room(false);
    perform pg_temp.expect_error(format('select public.send_room_invite(%L, %L)', C, room->>'id'), 'NOT_FRIENDS');
    perform pg_temp.act(B);
    perform pg_temp.expect_error(format('select public.send_room_invite(%L, %L)', A, room->>'id'), 'NOT_IN_ROOM');
    perform pg_temp.act(A);
    v := public.send_room_invite(B, (room->>'id')::uuid); inv := (v->>'invite_id')::uuid;
    perform public.send_room_invite(B, (room->>'id')::uuid);
    perform pg_temp.check((select count(*) from public.room_invites where to_user = B and status = 'pending') = 1, 're-invite refreshes');
    perform pg_temp.act(B);
    perform pg_temp.check((public.get_social_badges()->>'invites')::int = 1, 'invite badge');
    perform pg_temp.check(public.get_social_overview()->'invites'->0->>'room_code' = room->>'code', 'invite shows room code');
    perform pg_temp.act(C);
    perform pg_temp.expect_error(format('select public.respond_room_invite(%L, true)', inv), 'INVITE_NOT_FOUND');
    perform pg_temp.act(B);
    v := public.respond_room_invite(inv, true);
    perform pg_temp.check(v->>'status' = 'joined' and v->>'room_id' = room->>'id', 'joined through invite');
    perform pg_temp.check(exists (select 1 from public.room_players where room_id = (room->>'id')::uuid and user_id = B and status = 'active'), 'B in room');
    perform pg_temp.check(public.respond_room_invite(inv, true)->>'status' = 'closed', 'second answer is closed');
    perform pg_temp.act(A);
    perform pg_temp.expect_error(format('select public.send_room_invite(%L, %L)', B, room->>'id'), 'ALREADY_IN_ROOM');

    -- expired invite
    perform pg_temp.act(C); room2 := public.create_room(false);
    v := public.send_room_invite(D, (room2->>'id')::uuid); inv := (v->>'invite_id')::uuid;
    update public.room_invites set expires_at = now() - interval '1 second' where id = inv;
    perform pg_temp.act(D);
    perform pg_temp.check((public.get_social_badges()->>'invites')::int = 0, 'expired invite not counted');
    perform pg_temp.check(public.respond_room_invite(inv, true)->>'status' = 'expired', 'expired invite');
    perform pg_temp.check(not exists (select 1 from public.room_players where room_id = (room2->>'id')::uuid and user_id = D), 'not joined');
    -- room closed after the invite
    perform pg_temp.act(C);
    v := public.send_room_invite(D, (room2->>'id')::uuid); inv := (v->>'invite_id')::uuid;
    update public.rooms set status = 'closed', closed_at = now() where id = (room2->>'id')::uuid;
    perform pg_temp.act(D);
    perform pg_temp.check(public.respond_room_invite(inv, true)->>'status' = 'room_gone', 'room gone');
    -- full room: invite stays pending
    perform pg_temp.act(C); room2 := public.create_room(false);
    update public.rooms set settings = jsonb_set(settings, '{max_players}', '1') where id = (room2->>'id')::uuid;
    v := public.send_room_invite(D, (room2->>'id')::uuid); inv := (v->>'invite_id')::uuid;
    perform pg_temp.act(D);
    perform pg_temp.expect_error(format('select public.respond_room_invite(%L, true)', inv), 'ROOM_FULL');
    perform pg_temp.check((select status from public.room_invites where id = inv) = 'pending', 'full room keeps invite');
    perform pg_temp.check(public.respond_room_invite(inv, false)->>'status' = 'declined', 'decline');

    -- ---------------------------------------------------------------- blocking
    perform pg_temp.act(A);
    perform public.send_message(B, 'son mesaj');
    perform public.block_user(B);
    perform pg_temp.check(not public._are_friends(A, B), 'block removes friendship');
    perform pg_temp.check(public._relation(A, B) = 'blocked', 'relation blocked');
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(public.search_users('sosyal')) x
                                        where (x->>'user_id')::uuid = B), 'blocked user hidden from my search');
    perform pg_temp.check(jsonb_array_length(public.list_conversations()) = 0, 'blocked conversation hidden');
    perform pg_temp.expect_error(format('select public.send_friend_request(%L)', B), 'USER_BLOCKED');
    perform pg_temp.act(B);
    perform pg_temp.check(not exists (select 1 from jsonb_array_elements(public.search_users('sosyal')) x
                                        where (x->>'user_id')::uuid = A), 'blocker hidden from blocked user search');
    perform pg_temp.expect_error(format('select public.send_friend_request(%L)', A), 'USER_NOT_FOUND');
    perform pg_temp.expect_error(format('select public.get_user_profile(%L)', A), 'USER_NOT_FOUND');
    perform pg_temp.expect_error(format('select public.send_message(%L, %L)', A, 'hey'), 'NOT_FRIENDS');
    perform pg_temp.check((public.get_social_badges()->>'notifications')::int = 0, 'notifications from A removed');
    perform pg_temp.act(A);
    perform public.unblock_user(B);
    perform pg_temp.check(public._relation(A, B) = 'none', 'unblocked');
    perform pg_temp.check(public.get_user_profile(B)->>'username' = 'Sosyal Barış', 'profile visible again');

    -- remove friend
    perform pg_temp.act(C); perform public.remove_friend(D);
    perform pg_temp.check(not public._are_friends(C, D), 'removed');
    perform pg_temp.expect_error(format('select public.remove_friend(%L)', D), 'NOT_FRIENDS');

    -- notifications: mark all read
    perform pg_temp.act(D); perform public.send_friend_request(E);
    perform pg_temp.act(F); perform public.send_friend_request(E);
    perform pg_temp.act(E);
    perform pg_temp.check((public.get_social_badges()->>'notifications')::int = 2, 'two unread');
    perform pg_temp.check(public.mark_notifications_read(null) = 2, 'mark all read');
    perform pg_temp.check((public.get_social_badges()->>'notifications')::int = 0, 'none unread');

    -- signals were bumped for the users that changed
    perform pg_temp.check((select version from public.social_signals where user_id = E) > 0, 'signal bumped');
end $$;

-- ---------------------------------------------------------------------- RLS
-- As the client role: own rows only, and no direct writes at all.
do $$
declare
    A uuid := '20000000-0000-0000-0000-0000000000a1';
    C uuid := '20000000-0000-0000-0000-0000000000a3';
    n int;
begin
    perform pg_temp.act(C);
    set local role authenticated;
    select count(*) into n from public.direct_messages;           -- C never messaged
    perform pg_temp.check(n = 0, 'RLS: C sees no A-B messages');
    select count(*) into n from public.notifications where user_id <> C;
    perform pg_temp.check(n = 0, 'RLS: only own notifications');
    select count(*) into n from public.friend_requests where C not in (from_user, to_user);
    perform pg_temp.check(n = 0, 'RLS: only own requests');
    select count(*) into n from public.user_presence where user_id <> C;
    perform pg_temp.check(n = 0, 'RLS: presence rows are private');
    select count(*) into n from public.social_signals where user_id <> C;
    perform pg_temp.check(n = 0, 'RLS: only own signal');
    select count(*) into n from public.user_blocks;
    perform pg_temp.check(n = 0, 'RLS: blocks of others invisible');
    begin
        insert into public.friendships(user_a, user_b) values (least(A, C), greatest(A, C));
        raise exception 'CHECK FAILED: direct insert into friendships allowed';
    exception when insufficient_privilege then null;
    end;
    begin
        insert into public.direct_messages(sender_id, recipient_id, body) values (A, C, 'spoof');
        raise exception 'CHECK FAILED: direct message insert allowed';
    exception when insufficient_privilege then null;
    end;
    begin
        update public.notifications set read_at = now();
        raise exception 'CHECK FAILED: direct update allowed';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public._notify(A, 'message', C, null);
        raise exception 'CHECK FAILED: helper callable by clients';
    exception when insufficient_privilege then null;
    end;
    reset role;
end $$;

select 'SOCIAL TESTS PASSED' as result;
rollback;
