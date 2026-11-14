-- =====================================================================
-- LOOPA 0.8.0 — Kurt Adam (6-20 players, no human moderator)
-- Source of truth: the Kurt Adam master specification + docs/design/KURT_ADAM_DECISIONS.md.
--
-- Seats: players appear as seats (in-game name + avatar). A Shapeshifter who takes over a
-- target continues in the target's seat; its own seat shows as dead. Real accounts are untouched.
-- Phases: night -> morning (results) -> discussion (chat) -> vote -> dusk (result) -> night ...
-- Night order (decided): 1 silence, 2 protections (Doktor, Cadı heal, Guardian),
--   3 attacks (wolves / Brute special, Suikastçı, Cadı poison, Avcı arrow, Shapeshifter shift),
--   then Medyum revive, 4 Kurt Yavrusu last move (protections do not apply),
--   5 investigations (Kâhin, Dedektif; the Illusionist shows that night's false village role).
-- Roles are not revealed on death; all roles are shown when the game ends.
-- =====================================================================

insert into public.game_modes(id, name_tr, name_en, description_tr, status, always_free, min_players, max_players, sort_order, tags) values
 ('kurt_adam', 'Kurt Adam', 'Werewolf', 'Gizli roller, gece yetenekleri, gündüz oylaması. Moderatörü LOOPA.', 'available', false, 6, 20, 180, '{social,team}')
on conflict (id) do nothing;

create or replace function public._ka_team(p_role text)
returns text language sql immutable as $$
    select case when p_role in ('kurt','shapeshifter','alpha','silencer','brute','illusionist','wolf_cub') then 'wolf'
                when p_role in ('jester','assassin') then p_role
                else 'village' end;
$$;

create or replace function public._ka_default_rarity()
returns jsonb language sql immutable as $$
    select '{"doctor":"common","guardian":"common","seer":"uncommon","mayor":"uncommon","witch":"uncommon",
             "hunter":"rare","detective":"rare","medium":"very_rare"}'::jsonb;
$$;

create or replace function public._opts_kurt_adam(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
declare roles text[] := array['shapeshifter','alpha','silencer','brute','illusionist','wolf_cub','seer','doctor','hunter','witch','detective',
                               'guardian','medium','mayor','jester','assassin'];
        dis jsonb := '[]'::jsonb; rar jsonb := public._ka_default_rarity(); w jsonb; x text;
begin
    for x in select jsonb_array_elements_text(coalesce(o->'disabled', '[]'::jsonb)) loop
        if x = any(roles) then dis := dis || to_jsonb(x); end if;
    end loop;
    for x in select key from jsonb_each_text(coalesce(o->'rarity', '{}'::jsonb)) loop
        if rar ? x and o->'rarity'->>x in ('common','uncommon','rare','very_rare') then rar := jsonb_set(rar, array[x], o->'rarity'->x); end if;
    end loop;
    w := jsonb_build_object('common', 50, 'uncommon', 30, 'rare', 15, 'very_rare', 5);
    for x in select key from jsonb_each_text(coalesce(o->'weights', '{}'::jsonb)) loop
        if w ? x and (o->'weights'->>x)::int between 0 and 100 then w := jsonb_set(w, array[x], o->'weights'->x); end if;
    end loop;
    return jsonb_build_object('disabled', dis, 'rarity', rar, 'weights', w,
        'night_seconds', case when (o->>'night_seconds')::int in (30, 45, 60) then (o->>'night_seconds')::int else 45 end,
        'day_seconds', case when (o->>'day_seconds')::int in (60, 90, 120, 180) then (o->>'day_seconds')::int else 90 end,
        'vote_seconds', case when (o->>'vote_seconds')::int in (20, 30, 45) then (o->>'vote_seconds')::int else 30 end);
end $$;

-- Authoritative distribution (decision 1): wolves / independents by player count.
create or replace function public._ka_counts(n int)
returns int[] language sql immutable as $$
    select case when n between 6 and 8 then array[1, 0] when n between 9 and 11 then array[2, 1]
                when n between 12 and 15 then array[3, 1] when n between 16 and 20 then array[4, 2] end;
$$;

create or replace function public._startcheck_kurt_adam(p_settings jsonb, n int)
returns void language plpgsql immutable as $$
declare c int[] := public._ka_counts(n);
begin
    if c is null then raise exception 'LOOPA:WRONG_PLAYER_COUNT'; end if;
    -- never more roles than players; at least one plain villager remains
    if n - c[1] - c[2] < 1 then raise exception 'LOOPA:WRONG_PLAYER_COUNT'; end if;
end $$;

-- Role deal: wolves from the enabled wolf roles, independents (Suikastçı only from 10 players),
-- then civilians: at least one plain Köylü, ~60% special roles drawn by rarity weight.
create or replace function public._ka_deal(p_players uuid[], o jsonb)
returns jsonb language plpgsql volatile as $$
declare n int := array_length(p_players, 1); c int[] := public._ka_counts(n); roles text[] := '{}'; pool text[]; dis text[];
        civ int; special int; i int; tier text; w jsonb := o->'weights'; total numeric; pick numeric; acc numeric; avail text[]; r text;
        shuffled uuid[]; out jsonb := '{}'::jsonb; tiers text[] := array['common','uncommon','rare','very_rare'];
begin
    dis := array(select jsonb_array_elements_text(coalesce(o->'disabled', '[]'::jsonb)));
    pool := array(select x from unnest(array['shapeshifter','alpha','silencer','brute','illusionist','wolf_cub']) x where not x = any(dis) order by random());
    for i in 1..c[1] loop roles := roles || coalesce(pool[i], 'kurt'); end loop;
    pool := array(select x from unnest(array['jester','assassin']) x
                   where not x = any(dis) and (x <> 'assassin' or n >= 10) order by random());
    for i in 1..c[2] loop
        if pool[i] is null then raise exception 'LOOPA:ROLE_POOL_TOO_SMALL'; end if;
        roles := roles || pool[i];
    end loop;
    civ := n - c[1] - c[2];
    special := least(greatest(civ - 1, 0), ceil(civ * 0.6)::int);
    pool := array(select key from jsonb_each_text(o->'rarity') where not key = any(dis));
    for i in 1..special loop
        exit when coalesce(array_length(pool, 1), 0) = 0;
        -- rarity tier by weight, among tiers that still have a role
        select coalesce(sum((w->>t)::numeric), 0) into total from unnest(tiers) t where exists (select 1 from unnest(pool) p where o->'rarity'->>p = t);
        exit when total <= 0;
        pick := random() * total; acc := 0; tier := null;
        foreach r in array tiers loop
            continue when not exists (select 1 from unnest(pool) p where o->'rarity'->>p = r);
            acc := acc + (w->>r)::numeric;
            if tier is null and acc >= pick then tier := r; end if;
        end loop;
        select p into r from unnest(pool) p where o->'rarity'->>p = tier order by random() limit 1;
        roles := roles || r;
        pool := array_remove(pool, r);                               -- each special role at most once
    end loop;
    while array_length(roles, 1) < n loop roles := roles || 'villager'::text; end loop;
    shuffled := array(select x from unnest(p_players) x order by random());
    for i in 1..n loop out := out || jsonb_build_object(shuffled[i]::text, roles[i]); end loop;
    return out;
end $$;

create or replace function public._ka_alive_users(s jsonb)
returns text[] language sql immutable as $$
    select array(select key from jsonb_each(s->'p') where (value->>'alive')::boolean order by key);
$$;

create or replace function public._ka_seat_user(s jsonb, seat int)
returns text language sql immutable as $$ select s->'seat_owner'->>seat::text; $$;

create or replace function public._ka_user_seat(s jsonb, u text)
returns int language sql immutable as $$
    select (key)::int from jsonb_each_text(s->'seat_owner') where value = u limit 1;
$$;

create or replace function public._g_kurt_adam_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare o jsonb := public._opts_kurt_adam(coalesce(m.settings->'mode_options', '{}'), array_length(players, 1)); roles jsonb; p jsonb := '{}'::jsonb;
        seats jsonb := '[]'::jsonb; owner jsonb := '{}'::jsonb; i int; pr record;
begin
    if n > 1 then return null; end if;
    roles := public._ka_deal(players, o);
    i := 0;
    for pr in select x as u, row_number() over () as k from unnest(players) x loop
        i := i + 1;
        p := p || jsonb_build_object(pr.u::text, jsonb_build_object('role', roles->>pr.u::text, 'alive', true, 'used', '[]'::jsonb, 'died_night', null));
        seats := seats || jsonb_build_array((select jsonb_build_object('seat', i, 'name', pf.username, 'avatar', pf.avatar, 'alive', true)
                                               from public.profiles pf where pf.id = pr.u));
        owner := owner || jsonb_build_object(i::text, pr.u::text);
    end loop;
    return jsonb_build_object('phase', 'night', 'night', 1, 'day', 0, 'p', p, 'seats', seats, 'seat_owner', owner, 'seat_origin', owner, 'opts', o,
        'actions', '{}'::jsonb, 'votes', '{}'::jsonb, 'chat', '[]'::jsonb, 'events', '{}'::jsonb, 'silenced', null, 'prev_silenced', null,
        'fake_role', null, 'turn', null, 'secs', (o->>'night_seconds')::int,
        'deadline', public._tg_deadline((o->>'night_seconds')::int), 'timeouts', '{}'::jsonb);
end $$;

create or replace function public._ka_event(s jsonb, u text, e jsonb)
returns jsonb language sql immutable as $$
    select jsonb_set(s, array['events', u], coalesce(s->'events'->u, '[]'::jsonb) || jsonb_build_array(e), true);
$$;

-- Kills user u now (seat shows dead, unless a Shapeshifter is wearing that seat).
create or replace function public._ka_kill(s jsonb, u text, cause text)
returns jsonb language plpgsql immutable as $$
declare seat int := public._ka_user_seat(s, u);
begin
    if not (s->'p'->u->>'alive')::boolean then return s; end if;
    s := jsonb_set(s, array['p', u, 'alive'], 'false');
    s := jsonb_set(s, array['p', u, 'died_night'], coalesce(s->'night', '0'::jsonb));
    s := jsonb_set(s, array['p', u, 'died_phase'], to_jsonb(s->>'phase'));
    if seat is not null then s := jsonb_set(s, array['seats', (seat - 1)::text, 'alive'], 'false'); end if;
    s := jsonb_set(s, '{deaths}', coalesce(s->'deaths', '[]'::jsonb) || jsonb_build_array(jsonb_build_object('seat', seat, 'cause', cause)));
    s := public._ka_event(s, u, jsonb_build_object('t', 'eliminated', 'cause', cause));
    if s->'p'->u->>'role' = 'wolf_cub' then
        s := jsonb_set(s, '{pending_cub}', coalesce(s->'pending_cub', '[]'::jsonb) || to_jsonb(u));
    end if;
    return s;
end $$;

-- Winner: 'jester' handled at the vote; assassin alone; village; wolves at parity; nobody.
create or replace function public._ka_winner(s jsonb)
returns text language plpgsql immutable as $$
declare alive text[] := public._ka_alive_users(s); wolves int; others int; assassin boolean;
begin
    if coalesce(array_length(alive, 1), 0) = 0 then return 'nobody'; end if;
    assassin := exists (select 1 from unnest(alive) a where s->'p'->a->>'role' = 'assassin');
    if assassin and array_length(alive, 1) = 1 then return 'assassin'; end if;
    if assassin then return null; end if;                      -- decision 10
    select count(*) filter (where public._ka_team(s->'p'->a->>'role') = 'wolf'), count(*) filter (where public._ka_team(s->'p'->a->>'role') <> 'wolf')
      into wolves, others from unnest(alive) a;
    if wolves = 0 then return 'village'; end if;
    if wolves >= others then return 'wolf'; end if;
    return null;
end $$;

create or replace function public._ka_finish(s jsonb, w text)
returns jsonb language plpgsql immutable as $$
declare u text; pts jsonb := '{}'::jsonb; stats jsonb := '{}'::jsonb; won boolean; role text;
begin
    for u in select jsonb_object_keys(s->'p') loop
        role := s->'p'->u->>'role';
        won := case w when 'village' then public._ka_team(role) = 'village'
                      when 'wolf' then public._ka_team(role) = 'wolf'
                      when 'jester' then role = 'jester' and u = s->>'jester_winner'
                      when 'assassin' then role = 'assassin'
                      else false end;
        pts := pts || jsonb_build_object(u, case when won then 100 else 0 end + case when (s->'p'->u->>'alive')::boolean then 10 else 0 end);
        stats := stats || jsonb_build_object(u, jsonb_build_object('games', 1, 'wins', case when won then 1 else 0 end,
            'as_wolf', case when public._ka_team(role) = 'wolf' then 1 else 0 end, 'survived', case when (s->'p'->u->>'alive')::boolean then 1 else 0 end));
    end loop;
    return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'winner', w, 'points', pts, 'stats', stats);
end $$;

-- ---------------------------------------------------------------------
-- Night resolution
-- ---------------------------------------------------------------------
create or replace function public._ka_resolve_night(s jsonb)
returns jsonb language plpgsql volatile as $$
declare a jsonb := s->'actions'; u text; role text; silenced text; prot_doc text; heal text; guard_by text; guard_target text;
        wolf_target text; brute_special boolean := false; attacks jsonb := '[]'::jsonb; at jsonb; t text; blocked boolean; x record;
        fake text; village_roles text[] := array['villager','seer','doctor','hunter','witch','detective','guardian','medium','mayor'];
        alive text[]; tie_n int; r text; who text; seen_role text; d1 text; d2 text; team1 text; team2 text;
begin
    s := s || jsonb_build_object('deaths', '[]'::jsonb);
    alive := public._ka_alive_users(s);
    -- Illusionist: one false village role for the whole night, the same for every investigator
    fake := village_roles[1 + floor(random() * array_length(village_roles, 1))::int];
    s := s || jsonb_build_object('fake_role', fake);

    -- 1) silence (not the same player two nights in a row); a silenced player's own night action is void
    for u in select unnest(alive) loop
        if s->'p'->u->>'role' = 'silencer' and a->u->>'target' is not null then
            silenced := public._ka_seat_user(s, (a->u->>'target')::int);
        end if;
    end loop;
    if silenced is not null and silenced = s->>'silenced_last' then silenced := null; end if;
    s := s || jsonb_build_object('silenced', silenced, 'silenced_last', silenced);
    if silenced is not null then
        a := a - silenced;
        s := public._ka_event(s, silenced, jsonb_build_object('t', 'silenced'));
    end if;

    -- 2) protections
    for u in select unnest(alive) loop
        role := s->'p'->u->>'role';
        continue when not (a ? u);
        if role = 'doctor' and a->u->>'target' is not null then prot_doc := public._ka_seat_user(s, (a->u->>'target')::int); end if;
        if role = 'guardian' and a->u->>'target' is not null then guard_by := u; guard_target := public._ka_seat_user(s, (a->u->>'target')::int); end if;
        if role = 'witch' and a->u->>'heal' is not null and not (s->'p'->u->'used' ? 'heal') then
            heal := public._ka_seat_user(s, (a->u->>'heal')::int);
            s := jsonb_set(s, array['p', u, 'used'], (s->'p'->u->'used') || '"heal"');
        end if;
    end loop;

    -- 3) attacks
    -- wolves: plurality of wolf votes; a tie between targets means no wolf attack this night
    select key, count(*) over () into wolf_target, tie_n from (
        select v.key, count(*) c, rank() over (order by count(*) desc) rk
          from (select a->w->>'attack' as key from unnest(alive) w where public._ka_team(s->'p'->w->>'role') = 'wolf' and a->w->>'attack' is not null) v
         group by v.key) z where rk = 1;
    if tie_n > 1 then wolf_target := null; end if;
    if wolf_target is not null then
        wolf_target := public._ka_seat_user(s, wolf_target::int);
        -- Brute: once per match, unstoppable
        for u in select unnest(alive) loop
            if s->'p'->u->>'role' = 'brute' and coalesce((a->u->>'special')::boolean, false) and not (s->'p'->u->'used' ? 'special')
               and a->u->>'attack' = (select seat_k from (select key as seat_k from jsonb_each_text(s->'seat_owner') where value = wolf_target) q) then
                brute_special := true;
                s := jsonb_set(s, array['p', u, 'used'], (s->'p'->u->'used') || '"special"');
                s := jsonb_set(s, array['p', u, 'role'], '"kurt"');            -- becomes a normal wolf
            end if;
        end loop;
        attacks := attacks || jsonb_build_array(jsonb_build_object('t', wolf_target, 'kind', case when brute_special then 'brute' else 'wolf' end));
    end if;
    for u in select unnest(alive) loop
        role := s->'p'->u->>'role';
        continue when not (a ? u);
        if role = 'assassin' and a->u->>'target' is not null then
            attacks := attacks || jsonb_build_array(jsonb_build_object('t', public._ka_seat_user(s, (a->u->>'target')::int), 'kind', 'assassin'));
        elsif role = 'witch' and a->u->>'poison' is not null and not (s->'p'->u->'used' ? 'poison') then
            if a->u->>'poison' = a->u->>'heal' then continue; end if;          -- decision 4
            attacks := attacks || jsonb_build_array(jsonb_build_object('t', public._ka_seat_user(s, (a->u->>'poison')::int), 'kind', 'poison'));
            s := jsonb_set(s, array['p', u, 'used'], (s->'p'->u->'used') || '"poison"');
        elsif role = 'hunter' and a->u->>'target' is not null and not (s->'p'->u->'used' ? 'arrow') then
            attacks := attacks || jsonb_build_array(jsonb_build_object('t', public._ka_seat_user(s, (a->u->>'target')::int), 'kind', 'arrow'));
            s := jsonb_set(s, array['p', u, 'used'], (s->'p'->u->'used') || '"arrow"');
        elsif role = 'shapeshifter' and a->u->>'target' is not null then
            attacks := attacks || jsonb_build_array(jsonb_build_object('t', public._ka_seat_user(s, (a->u->>'target')::int), 'kind', 'shift', 'by', u));
        end if;
    end loop;
    for at in select value from jsonb_array_elements(attacks) loop
        t := at->>'t';
        continue when t is null or not (s->'p'->t->>'alive')::boolean;
        blocked := false;
        if at->>'kind' = 'brute' or at->>'kind' = 'arrow' and guard_target is distinct from t then
            blocked := false;
        elsif at->>'kind' = 'wolf' and (prot_doc = t or heal = t) then
            blocked := true;
            s := public._ka_event(s, t, jsonb_build_object('t', 'protected', 'by', case when prot_doc = t then 'doctor' else 'witch' end));
        elsif at->>'kind' in ('assassin','poison') and heal = t then
            blocked := true;
            s := public._ka_event(s, t, jsonb_build_object('t', 'protected', 'by', 'witch'));
        elsif at->>'kind' = 'shift' and (prot_doc = t or heal = t) then
            blocked := true;                                                      -- no transformation, no fake death
        end if;
        continue when blocked;
        -- Guardian takes any attack on the guarded player except the Brute special and the shift
        if guard_target = t and guard_by is not null and (s->'p'->guard_by->>'alive')::boolean and at->>'kind' not in ('brute','shift') then
            s := public._ka_event(s, t, jsonb_build_object('t', 'guarded'));
            s := public._ka_kill(s, guard_by, 'guardian');
            continue;
        end if;
        if at->>'kind' = 'shift' and not (s->'p'->(at->>'by')->>'alive')::boolean then continue; end if;
        if at->>'kind' = 'shift' then
            -- the target is eliminated; the Shapeshifter continues in the target's seat, its own seat "dies"
            who := at->>'by';
            d1 := public._ka_user_seat(s, who)::text; d2 := public._ka_user_seat(s, t)::text;
            s := public._ka_kill(s, t, 'shift');
            s := jsonb_set(s, array['seats', (d2::int - 1)::text, 'alive'], 'true');
            s := jsonb_set(s, array['seats', (d1::int - 1)::text, 'alive'], 'false');
            s := jsonb_set(s, '{seat_owner}', (s->'seat_owner') - d1 || jsonb_build_object(d2, who));
            s := jsonb_set(s, '{deaths}', (select coalesce(jsonb_agg(x2), '[]'::jsonb) from jsonb_array_elements(s->'deaths') x2 where (x2->>'seat')::int <> d2::int)
                                           || jsonb_build_array(jsonb_build_object('seat', d1::int, 'cause', 'night')));
            s := public._ka_event(s, who, jsonb_build_object('t', 'transformed', 'seat', d2::int));
        else
            s := public._ka_kill(s, t, at->>'kind');
        end if;
    end loop;

    -- Medyum: once, a player eliminated before this night (decision 6)
    for u in select unnest(alive) loop
        if s->'p'->u->>'role' = 'medium' and (s->'p'->u->>'alive')::boolean and a ? u and a->u->>'revive' is not null and not (s->'p'->u->'used' ? 'revive') then
            who := a->u->>'revive';
            if s->'p' ? who and not (s->'p'->who->>'alive')::boolean and (s->'p'->who->>'died_night')::int < (s->>'night')::int
               and public._ka_user_seat(s, who) is not null then
                s := jsonb_set(s, array['p', who, 'alive'], 'true');
                if public._ka_user_seat(s, who) is not null then
                    s := jsonb_set(s, array['seats', (public._ka_user_seat(s, who) - 1)::text, 'alive'], 'true');
                end if;
                s := jsonb_set(s, array['p', u, 'used'], (s->'p'->u->'used') || '"revive"');
                s := public._ka_event(s, who, jsonb_build_object('t', 'revived'));
                s := jsonb_set(s, '{revived}', coalesce(s->'revived', '[]'::jsonb) || to_jsonb(public._ka_user_seat(s, who)));
            end if;
        end if;
    end loop;

    -- 5) investigations (Alpha Wolf reads as village; the Illusionist as tonight's false role)
    for u in select unnest(alive) loop
        role := s->'p'->u->>'role';
        continue when not (a ? u);
        if role = 'seer' and a->u->>'target' is not null then
            who := public._ka_seat_user(s, (a->u->>'target')::int);
            seen_role := case s->'p'->who->>'role' when 'alpha' then 'villager' when 'illusionist' then fake else s->'p'->who->>'role' end;
            s := public._ka_event(s, u, jsonb_build_object('t', 'seer', 'seat', (a->u->>'target')::int, 'wolf', public._ka_team(seen_role) = 'wolf'));
        elsif role = 'detective' and a->u->>'a' is not null and a->u->>'b' is not null then
            d1 := public._ka_seat_user(s, (a->u->>'a')::int); d2 := public._ka_seat_user(s, (a->u->>'b')::int);
            team1 := public._ka_team(case s->'p'->d1->>'role' when 'illusionist' then fake else s->'p'->d1->>'role' end);
            team2 := public._ka_team(case s->'p'->d2->>'role' when 'illusionist' then fake else s->'p'->d2->>'role' end);
            s := public._ka_event(s, u, jsonb_build_object('t', 'detective', 'a', (a->u->>'a')::int, 'b', (a->u->>'b')::int, 'same', team1 = team2));
        end if;
    end loop;
    return s;
end $$;

-- ---------------------------------------------------------------------
-- Phase machine
-- ---------------------------------------------------------------------
create or replace function public._ka_goto(s jsonb, p_phase text, p_secs int)
returns jsonb language plpgsql volatile as $$
begin
    return s || jsonb_build_object('phase', p_phase, 'deadline', public._tg_deadline(p_secs), 'secs', p_secs);
end $$;

create or replace function public._ka_after_deaths(s jsonb, p_next text)
returns jsonb language plpgsql volatile as $$
declare w text;
begin
    -- 4) a dead Wolf Cub takes one last player with it first
    if jsonb_array_length(coalesce(s->'pending_cub', '[]'::jsonb)) > 0 then
        return public._ka_goto(s || jsonb_build_object('cub_next', p_next), 'cub', 20);
    end if;
    w := public._ka_winner(s);
    if w is not null then return public._ka_finish(s, w); end if;
    if p_next = 'morning' then return public._ka_goto(s, 'morning', 8); end if;
    if p_next = 'night' then
        return public._ka_goto(s || jsonb_build_object('night', (s->>'night')::int + 1, 'actions', '{}'::jsonb), 'night', (s->'opts'->>'night_seconds')::int);
    end if;
    return public._ka_goto(s, p_next, 10);
end $$;

create or replace function public._ka_advance(s jsonb)
returns jsonb language plpgsql volatile as $$
declare ph text := s->>'phase'; tally record; top_n int; lynched text; w text; cub text; i int;
begin
    if ph = 'night' then
        s := public._ka_resolve_night(s);
        return public._ka_after_deaths(s, 'morning');
    elsif ph = 'morning' then
        return public._ka_goto(s || jsonb_build_object('day', (s->>'day')::int + 1, 'votes', '{}'::jsonb), 'day', (s->'opts'->>'day_seconds')::int);
    elsif ph = 'day' then
        return public._ka_goto(s, 'vote', (s->'opts'->>'vote_seconds')::int);
    elsif ph = 'vote' then
        -- plurality, Mayor counts twice; a tie or no votes: nobody leaves
        select seat, cnt, count(*) over () as n_top into tally from (
            select (v.value)::int as seat, sum(case when s->'p'->v.key->>'role' = 'mayor' then 2 else 1 end) as cnt,
                   rank() over (order by sum(case when s->'p'->v.key->>'role' = 'mayor' then 2 else 1 end) desc) as rk
              from jsonb_each_text(s->'votes') v
             where (s->'p'->v.key->>'alive')::boolean and v.value <> 'skip'
             group by (v.value)::int) z where rk = 1 limit 1;
        s := s || jsonb_build_object('deaths', '[]'::jsonb, 'lynch', null);
        if tally.seat is not null and tally.n_top = 1 then
            lynched := public._ka_seat_user(s, tally.seat);
            s := s || jsonb_build_object('lynch', tally.seat);
            if s->'p'->lynched->>'role' = 'jester' then
                s := public._ka_kill(s, lynched, 'vote');
                return public._ka_finish(s || jsonb_build_object('jester_winner', lynched), 'jester');
            end if;
            s := public._ka_kill(s, lynched, 'vote');
        end if;
        return public._ka_after_deaths(s, 'dusk');
    elsif ph = 'dusk' then
        return public._ka_after_deaths(s - 'pending_cub', 'night');
    elsif ph = 'cub' then
        -- timed out: the last move is not used
        return public._ka_after_deaths(s || jsonb_build_object('pending_cub', '[]'::jsonb), coalesce(s->>'cub_next', 'morning'));
    end if;
    return s;
end $$;

-- kinds:
--  night: act {"target":seat} (role ability) | wolves also {"attack":seat} (+ Brute {"special":true})
--         witch {"heal":seat,"poison":seat} | detective {"a":seat,"b":seat} | medium {"revive":user}
--  day:   chat {"text":".."}     vote: vote {"seat": n | "skip"}     cub: last {"seat": n}
create or replace function public._g_kurt_adam_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; role text := s->'p'->me->>'role'; alive boolean := coalesce((s->'p'->me->>'alive')::boolean, false);
        my_seat int := public._ka_user_seat(s, me); t int; txt text; n_alive int; done int; tgt text;
begin
    if not (s->'p' ? me) then raise exception 'LOOPA:NOT_A_PARTICIPANT'; end if;
    if p_kind = 'last' then
        if s->>'phase' <> 'cub' or not (s->'pending_cub' ? me) then raise exception 'LOOPA:PHASE_CLOSED'; end if;
        tgt := public._ka_seat_user(s, (p->>'seat')::int);
        if tgt is null or not (s->'p'->tgt->>'alive')::boolean then raise exception 'LOOPA:INVALID_TARGET'; end if;
        s := s || jsonb_build_object('pending_cub', (s->'pending_cub') - me);
        s := public._ka_kill(s, tgt, 'cub');                                  -- certain: no protection applies
        if jsonb_array_length(s->'pending_cub') = 0 then
            return public._ka_after_deaths(s, coalesce(s->>'cub_next', 'morning'));
        end if;
        return s;
    end if;
    if not alive then raise exception 'LOOPA:ELIMINATED'; end if;

    if p_kind = 'act' then
        if s->>'phase' <> 'night' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
        t := (p->>'target')::int;
        -- the pack's attack vote (any wolf-team role): {"attack": seat}
        if p ? 'attack' then
            if public._ka_team(role) <> 'wolf' then raise exception 'LOOPA:NO_NIGHT_ACTION'; end if;
            tgt := public._ka_seat_user(s, (p->>'attack')::int);
            if tgt is null or not (s->'p'->tgt->>'alive')::boolean or tgt = me then raise exception 'LOOPA:INVALID_TARGET'; end if;
            if coalesce((p->>'special')::boolean, false) and (role <> 'brute' or s->'p'->me->'used' ? 'special') then raise exception 'LOOPA:ABILITY_USED'; end if;
        end if;
        if role in ('villager','mayor','jester') then raise exception 'LOOPA:NO_NIGHT_ACTION'; end if;
        if p ? 'attack' and not (p ?| array['target','heal','poison','a','b','revive']) then
            null;                                                            -- attack vote only; the ability may be sent separately
        elsif role = 'medium' then
            if s->'p'->me->'used' ? 'revive' then raise exception 'LOOPA:ABILITY_USED'; end if;
            tgt := p->>'revive';
            if tgt is null or not (s->'p' ? tgt) or (s->'p'->tgt->>'alive')::boolean then raise exception 'LOOPA:INVALID_TARGET'; end if;
        elsif role = 'witch' then
            if (p->>'heal') is not null and s->'p'->me->'used' ? 'heal' then raise exception 'LOOPA:ABILITY_USED'; end if;
            if (p->>'poison') is not null and s->'p'->me->'used' ? 'poison' then raise exception 'LOOPA:ABILITY_USED'; end if;
            if p->>'heal' = p->>'poison' then raise exception 'LOOPA:INVALID_TARGET'; end if;
        elsif role = 'detective' then
            if p->>'a' is null or p->>'b' is null or p->>'a' = p->>'b' then raise exception 'LOOPA:INVALID_TARGET'; end if;
        elsif role in ('doctor','guardian','shapeshifter','silencer','seer','assassin','hunter') then
            if t is null or public._ka_seat_user(s, t) is null or not (s->'p'->public._ka_seat_user(s, t)->>'alive')::boolean then raise exception 'LOOPA:INVALID_TARGET'; end if;
            if t = my_seat then raise exception 'LOOPA:INVALID_TARGET'; end if;
            if role = 'hunter' and s->'p'->me->'used' ? 'arrow' then raise exception 'LOOPA:ABILITY_USED'; end if;
            if role = 'silencer' and public._ka_seat_user(s, t) = s->>'silenced_last' then raise exception 'LOOPA:SILENCED_LAST_NIGHT'; end if;
        elsif not (p ? 'attack') then
            raise exception 'LOOPA:NO_NIGHT_ACTION';                         -- plain wolves only vote the attack
        end if;
        -- ability and attack vote may come in separate calls: merge them
        s := jsonb_set(s, array['actions', me], coalesce(s->'actions'->me, '{}'::jsonb) || p);
        -- everybody with a night action has acted: morning comes early
        select count(*) into n_alive from unnest(public._ka_alive_users(s)) x
         where public._ka_team(s->'p'->x->>'role') = 'wolf' or s->'p'->x->>'role' not in ('villager','mayor','jester');
        select count(*) into done from jsonb_object_keys(s->'actions') k where (s->'p'->k->>'alive')::boolean;
        if done >= n_alive then s := s || jsonb_build_object('deadline', public._tg_deadline(1)); end if;
        return s;
    end if;
    if p_kind = 'chat' then
        if s->>'phase' not in ('day','vote','morning','dusk') then raise exception 'LOOPA:PHASE_CLOSED'; end if;
        if s->>'silenced' = me then raise exception 'LOOPA:SILENCED'; end if;
        txt := public._mz_clean(p->>'text', 200);
        if char_length(txt) = 0 then raise exception 'LOOPA:MESSAGE_EMPTY'; end if;
        if (select count(*) from jsonb_array_elements(s->'chat') c where c->>'seat' = my_seat::text and (c->>'at')::timestamptz > now() - interval '10 seconds') >= 5 then
            raise exception 'LOOPA:RATE_LIMITED';
        end if;
        return jsonb_set(s, '{chat}', (select coalesce(jsonb_agg(x), '[]'::jsonb) from (
            select x from jsonb_array_elements(s->'chat' || jsonb_build_array(jsonb_build_object('seat', my_seat, 'text', txt, 'at', now()))) x
            offset greatest(jsonb_array_length(s->'chat') + 1 - 80, 0)) y));
    end if;
    if p_kind = 'vote' then
        if s->>'phase' not in ('day','vote') then raise exception 'LOOPA:PHASE_CLOSED'; end if;
        if p->>'seat' <> 'skip' and (public._ka_seat_user(s, (p->>'seat')::int) is null
             or not (s->'p'->public._ka_seat_user(s, (p->>'seat')::int)->>'alive')::boolean) then
            raise exception 'LOOPA:INVALID_TARGET';
        end if;
        s := jsonb_set(s, array['votes', me], to_jsonb(p->>'seat'));
        -- everybody alive voted: close the vote early
        if (select count(*) from jsonb_object_keys(s->'votes') k where (s->'p'->k->>'alive')::boolean) >= array_length(public._ka_alive_users(s), 1)
           and s->>'phase' = 'vote' then
            s := s || jsonb_build_object('deadline', public._tg_deadline(1));
        end if;
        return s;
    end if;
    raise exception 'LOOPA:INVALID_ACTION';
end $$;

-- The clock drives the phases; nothing is done on behalf of a player (no actions on timeout).
create or replace function public._g_kurt_adam_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
begin
    return public._ka_advance(s);
end $$;

create or replace function public._g_kurt_adam_public(s jsonb)
returns jsonb language plpgsql immutable as $$
declare over boolean := coalesce((s->>'over')::boolean, false);
begin
    return jsonb_build_object('phase', s->'phase', 'night', s->'night', 'day', s->'day', 'seats', s->'seats',
        'deaths', s->'deaths', 'revived', s->'revived', 'lynch', s->'lynch', 'chat', s->'chat',
        'silenced_seat', case when s->>'silenced' is not null then public._ka_user_seat(s, s->>'silenced') end,
        'votes_count', case when s->>'phase' in ('vote','dusk') then (select jsonb_object_agg(v.value, cnt) from (
               select v2.value, sum(case when s->'p'->v2.key->>'role' = 'mayor' then 2 else 1 end) as cnt
                 from jsonb_each_text(s->'votes') v2 where (s->'p'->v2.key->>'alive')::boolean group by v2.value) v) end,
        'voted', (select coalesce(jsonb_agg(public._ka_user_seat(s, k)), '[]'::jsonb) from jsonb_object_keys(s->'votes') k),
        'cub_pending', jsonb_array_length(coalesce(s->'pending_cub', '[]'::jsonb)) > 0,
        'counts', public._ka_counts(jsonb_array_length(s->'seats')),
        'winner', s->'winner',
        -- the whole cast after the game
        'roles', case when over then (select jsonb_object_agg(public._ka_user_seat(s, k)::text, s->'p'->k->>'role') from jsonb_object_keys(s->'p') k
                                       where public._ka_user_seat(s, k) is not null) end,
        'cast', case when over then (select jsonb_agg(jsonb_build_object('user', k, 'role', s->'p'->k->>'role', 'alive', s->'p'->k->'alive'))
                                       from jsonb_object_keys(s->'p') k) end);
end $$;

-- What only u may know: own role / seat, wolf team-mates, own night results and events.
create or replace function public._g_kurt_adam_view(s jsonb, u uuid)
returns jsonb language plpgsql immutable as $$
declare me text := u::text; role text := s->'p'->me->>'role';
begin
    if not (s->'p' ? me) then return '{}'::jsonb; end if;
    return jsonb_build_object('role', role, 'team', public._ka_team(role), 'alive', s->'p'->me->'alive', 'seat', public._ka_user_seat(s, me),
        'used', s->'p'->me->'used', 'my_action', s->'actions'->me, 'my_vote', s->'votes'->me,
        'silenced', s->>'silenced' = me, 'cub_last', s->'pending_cub' ? me,
        'wolf_mates', case when public._ka_team(role) = 'wolf' then (select jsonb_agg(public._ka_user_seat(s, k)) from jsonb_object_keys(s->'p') k
                              where public._ka_team(s->'p'->k->>'role') = 'wolf' and k <> me and public._ka_user_seat(s, k) is not null) end,
        'wolf_votes', case when public._ka_team(role) = 'wolf' and s->>'phase' = 'night' then
                           (select jsonb_object_agg(public._ka_user_seat(s, k)::text, s->'actions'->k->'attack') from jsonb_object_keys(s->'actions') k
                             where public._ka_team(s->'p'->k->>'role') = 'wolf') end,
        'dead', case when role = 'medium' then (select coalesce(jsonb_agg(jsonb_build_object('user', k, 'seat', public._ka_user_seat(s, k),
                         'name', (select username from public.profiles where id = k::uuid))), '[]'::jsonb)
                       from jsonb_object_keys(s->'p') k where not (s->'p'->k->>'alive')::boolean and (s->'p'->k->>'died_night')::int < (s->>'night')::int) end,
        'events', coalesce(s->'events'->me, '[]'::jsonb));
end $$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_ka\_%' or p.proname like '\_g\_kurt\_adam\_%' or p.proname in ('_opts_kurt_adam','_startcheck_kurt_adam')) loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;
