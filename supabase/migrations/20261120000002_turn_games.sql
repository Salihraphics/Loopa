-- =====================================================================
-- LOOPA 0.8.0 — turn-based game layer + Tavla, 21, Kızma Birader, Amiral Battı
--
-- Built on the 0.5 mode engine (rooms, matches, mode_rounds, mode_act, get_mode_state,
-- results, XP). One mode_round is one game / hand. Its complete state is kept in
-- mode_secrets(kind 'g') (never readable by clients). After every change the layer writes
--   mode_rounds.public  = _g_<mode>_public(state)   (what everybody may see)
--   get_mode_state.me   = _g_<mode>_view(state, u)  (what only u may see: hand, own fleet)
-- Dice, shuffles and every rule check run here; clients only send intentions.
--
-- A game implements:
--   _g_<m>_new(m matches, players uuid[], n int) -> state or null (match over)
--   _g_<m>_act(state, u, kind, payload)         -> new state (raises LOOPA:* on illegal moves)
--   _g_<m>_timeout(state)                       -> new state (auto move for the player on turn)
--   _g_<m>_public(state) / _g_<m>_view(state, u)
-- Common state keys: phase, turn (uuid), secs (turn length), deadline, over (bool),
--   points {uid: n} (added to the match when the game ends), stats {uid: {...}}, log [..].
-- =====================================================================

insert into public.game_modes(id, name_tr, name_en, description_tr, status, always_free, min_players, max_players, sort_order, tags) values
 ('tavla',          'Tavla',             'Backgammon',  'Klasik Türk tavlası: pulları topla, ilk sen çıkar.',           'available',   false, 2, 2, 110, '{board,duel}'),
 ('yirmi_bir',      '21',                'Twenty-One',  '21''e en yakın sen ol, geçme. Bahis yok, sadece kart.',        'available',   false, 2, 2, 120, '{card,duel}'),
 ('kizma_birader',  'Kızma Birader',     'Ludo',        'Zar at, dört piyonunu eve ilk sen ulaştır.',                   'available',   false, 2, 4, 130, '{board,ffa}'),
 ('amiral_batti',   'Amiral Battı',      'Battleship',  'Gemilerini gizle, rakibin filosunu batır. 1v1 veya 2v2.',     'available',   false, 2, 4, 140, '{board,duel,team}')
on conflict (id) do nothing;

-- ---------------------------------------------------------------------
-- Generic layer
-- ---------------------------------------------------------------------
create or replace function public._tg_is(p_mode text)
returns boolean language sql immutable as $$
    select p_mode = any(array['tavla','yirmi_bir','kizma_birader','amiral_batti','renk_kartlari','okey','milyoner','kurt_adam','kralliklar']);
$$;

create or replace function public._tg_state(p_round uuid)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select payload from public.mode_secrets where round_id = p_round and kind = 'g';
$$;

-- Persists a state: public part, actor, deadline; finishes the round when the game is over.
create or replace function public._tg_commit(rd public.mode_rounds, s jsonb)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare pub jsonb; k text; v jsonb;
begin
    if s is null then raise exception 'LOOPA:INVALID_ACTION'; end if;
    insert into public.mode_secrets(round_id, kind, payload) values (rd.id, 'g', s)
    on conflict (round_id, kind) do update set payload = excluded.payload;
    execute format('select public.%I($1)', '_g_' || rd.mode || '_public') into pub using s;
    if coalesce((s->>'over')::boolean, false) then
        update public.mode_rounds set phase = 'done', public = pub, results = s->'points', actor_id = null, deadline_at = now()
         where id = rd.id;
        for k, v in select * from jsonb_each(coalesce(s->'points', '{}'::jsonb)) loop
            perform public._mz_award(rd.match_id, k::uuid, (v#>>'{}')::int);
        end loop;
        for k, v in select * from jsonb_each(coalesce(s->'stats', '{}'::jsonb)) loop
            perform public._game_stat(k::uuid, rd.mode, v);
        end loop;
    else
        update public.mode_rounds set phase = coalesce(s->>'phase', 'play'), public = pub,
               actor_id = nullif(s->>'turn', '')::uuid,
               deadline_at = coalesce((s->>'deadline')::timestamptz, now() + interval '1 hour')
         where id = rd.id;
    end if;
end $$;

-- Turn length from the room's options (seconds), clamped.
create or replace function public._tg_secs(p_match uuid, p_default int)
returns int language sql stable security definer set search_path = public, pg_temp as $$
    select least(greatest(coalesce((settings->'mode_options'->>'turn_seconds')::int, p_default), 10), 300)
      from public.matches where id = p_match;
$$;

create or replace function public._tg_opt(p_match uuid, p_key text)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select settings->'mode_options'->p_key from public.matches where id = p_match;
$$;

create or replace function public._tg_deadline(p_secs int)
returns text language sql volatile as $$ select (now() + make_interval(secs => p_secs))::text; $$;

create or replace function public._tg_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare s jsonb; v uuid; rd public.mode_rounds; players uuid[] := public._mz_players(m.id);
begin
    execute format('select public.%I($1, $2, $3)', '_g_' || m.game_mode || '_new') into s using m, players, n;
    if s is null then return null; end if;
    s := s || jsonb_build_object('match', m.id);
    insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, actor_id, public, started_at, deadline_at)
    values (m.id, m.room_id, m.game_mode, n, coalesce(s->>'phase', 'play'), nullif(s->>'turn', '')::uuid, '{}'::jsonb, now(),
            coalesce((s->>'deadline')::timestamptz, now() + interval '1 hour'))
    returning * into rd;
    perform public._tg_commit(rd, s);
    return rd.id;
end $$;

-- Deadline passed: the game makes the move for the player on turn (and counts the timeout).
create or replace function public._tg_tick(rd public.mode_rounds)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare s jsonb := public._tg_state(rd.id); s2 jsonb; turn uuid;
begin
    if rd.phase = 'done' or s is null then return false; end if;
    turn := nullif(s->>'turn', '')::uuid;
    -- a player who left the room is played for at once
    if now() < rd.deadline_at and (turn is null or public._mz_active(rd.room_id, turn)) then return false; end if;
    execute format('select public.%I($1)', '_g_' || rd.mode || '_timeout') into s2 using s;
    if s2 is null or s2 = s then return false; end if;
    perform public._tg_commit(rd, s2);
    return true;
end $$;

create or replace function public._tg_act(rd public.mode_rounds, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare s jsonb := public._tg_state(rd.id); s2 jsonb;
begin
    if rd.phase = 'done' or s is null or coalesce((s->>'over')::boolean, false) then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    execute format('select public.%I($1, $2, $3, $4)', '_g_' || rd.mode || '_act') into s2 using s, u, p_kind, coalesce(p, '{}'::jsonb);
    -- a deliberate move resets the player's timeout counter
    s2 := jsonb_set(s2, array['timeouts', u::text], '0'::jsonb, true);
    perform public._tg_commit(rd, s2);
    execute format('select public.%I($1, $2)', '_g_' || rd.mode || '_view') into s2 using public._tg_state(rd.id), u;
    return coalesce(s2, '{}'::jsonb);
end $$;

create or replace function public._tg_view(rd public.mode_rounds, u uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare s jsonb := public._tg_state(rd.id); v jsonb;
begin
    if s is null then return '{}'::jsonb; end if;
    execute format('select public.%I($1, $2)', '_g_' || rd.mode || '_view') into v using s, u;
    return coalesce(v, '{}'::jsonb);
end $$;

-- Helpers shared by the games
create or replace function public._tg_next_player(p_order jsonb, p_cur uuid, p_skip jsonb default '[]'::jsonb)
returns uuid language plpgsql immutable as $$
declare n int := jsonb_array_length(p_order); i int; idx int := 0; c text;
begin
    for i in 0..n - 1 loop if p_order->>i = p_cur::text then idx := i; end if; end loop;
    for i in 1..n loop
        c := p_order->>((idx + i) % n);
        if not (p_skip ? c) then return c::uuid; end if;
    end loop;
    return null;
end $$;

create or replace function public._tg_timeout_count(s jsonb, u uuid)
returns jsonb language sql immutable as $$
    select jsonb_set(s, array['timeouts', u::text], to_jsonb(coalesce((s->'timeouts'->>u::text)::int, 0) + 1), true);
$$;

create or replace function public._tg_shuffle(p jsonb)
returns jsonb language sql volatile as $$
    select coalesce(jsonb_agg(v order by random()), '[]'::jsonb) from jsonb_array_elements(p) v;
$$;

create or replace function public._tg_die()
returns int language sql volatile as $$ select 1 + floor(random() * 6)::int; $$;

create or replace function public._tg_log(s jsonb, e jsonb)
returns jsonb language sql immutable as $$
    -- last 30 public events
    select jsonb_set(s, '{log}', (select coalesce(jsonb_agg(x), '[]'::jsonb) from (
        select x from jsonb_array_elements(coalesce(s->'log', '[]'::jsonb) || jsonb_build_array(e)) x
        offset greatest(jsonb_array_length(coalesce(s->'log', '[]'::jsonb)) + 1 - 30, 0)) y));
$$;

-- ---------------------------------------------------------------------
-- Dispatch: the original nine games are unchanged; new games go through the layer.
-- ---------------------------------------------------------------------
create or replace function public._mz_new_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if public._tg_is(m.game_mode) then return public._tg_round(m, n); end if;
    return case m.game_mode
        when 'gizli_kelime' then public._mz_dg_round(m, n)
        when 'sessiz_sinema' then public._mz_dg_round(m, n)
        when 'ciz_tahmin' then public._mz_dg_round(m, n)
        when 'anlat_bakalim' then public._mz_ab_round(m, n)
        when 'bilgi_arenasi' then public._mz_qa_round(m, n)
        when 'iki_dogru_bir_yalan' then public._mz_tl_round(m, n)
        when 'en_muhtemel_kim' then public._mz_ml_round(m, n)
        when 'kelime_zinciri' then public._mz_wc_round(m, n)
        when 'cizim_telefonu' then public._mz_tp_round(m, n)
    end;
end $$;

create or replace function public._mz_tick(rd public.mode_rounds)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if public._tg_is(rd.mode) then return public._tg_tick(rd); end if;
    return case rd.mode
        when 'gizli_kelime' then public._mz_dg_tick(rd)
        when 'sessiz_sinema' then public._mz_dg_tick(rd)
        when 'ciz_tahmin' then public._mz_dg_tick(rd)
        when 'anlat_bakalim' then public._mz_ab_tick(rd)
        when 'bilgi_arenasi' then public._mz_qa_tick(rd)
        when 'iki_dogru_bir_yalan' then public._mz_tl_tick(rd)
        when 'en_muhtemel_kim' then public._mz_ml_tick(rd)
        when 'kelime_zinciri' then public._mz_wc_tick(rd)
        when 'cizim_telefonu' then public._mz_tp_tick(rd)
        else false
    end;
end $$;

create or replace function public._mz_view(rd public.mode_rounds, u uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
begin
    if public._tg_is(rd.mode) then return public._tg_view(rd, u); end if;
    return case rd.mode
        when 'gizli_kelime' then public._mz_dg_view(rd, u)
        when 'sessiz_sinema' then public._mz_dg_view(rd, u)
        when 'ciz_tahmin' then public._mz_dg_view(rd, u)
        when 'anlat_bakalim' then public._mz_ab_view(rd, u)
        when 'bilgi_arenasi' then public._mz_qa_view(rd, u)
        when 'iki_dogru_bir_yalan' then public._mz_tl_view(rd, u)
        when 'en_muhtemel_kim' then public._mz_ml_view(rd, u)
        when 'kelime_zinciri' then jsonb_build_object('history', coalesce((select public->'chain' from public.mode_match_state where match_id = rd.match_id), '[]'::jsonb))
        when 'cizim_telefonu' then public._mz_tp_view(rd, u)
        else '{}'::jsonb
    end;
end $$;

create or replace function public.mode_act(p_round_id uuid, p_kind text, p_payload jsonb default '{}'::jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); rd public.mode_rounds; res jsonb; latest int;
begin
    select * into rd from public.mode_rounds where id = p_round_id;
    if not found then raise exception 'LOOPA:ROUND_NOT_FOUND'; end if;
    perform 1 from public.rooms where id = rd.room_id for update;          -- serialise with game_tick
    select * into rd from public.mode_rounds where id = p_round_id for update;
    select max(round_number) into latest from public.mode_rounds where match_id = rd.match_id;
    if rd.round_number <> latest or not exists (select 1 from public.matches where id = rd.match_id and status = 'in_progress') then
        raise exception 'LOOPA:PHASE_CLOSED';
    end if;
    if not public._mz_active(rd.room_id, u)
       or not exists (select 1 from public.match_players where match_id = rd.match_id and user_id = u) then
        raise exception 'LOOPA:NOT_A_PARTICIPANT';
    end if;
    if public._tg_is(rd.mode) then
        res := public._tg_act(rd, u, p_kind, coalesce(p_payload, '{}'));
    else
        res := case rd.mode
            when 'gizli_kelime' then public._mz_dg_act(rd, u, p_kind, coalesce(p_payload, '{}'))
            when 'sessiz_sinema' then public._mz_dg_act(rd, u, p_kind, coalesce(p_payload, '{}'))
            when 'ciz_tahmin' then public._mz_dg_act(rd, u, p_kind, coalesce(p_payload, '{}'))
            when 'anlat_bakalim' then public._mz_ab_act(rd, u, p_kind, coalesce(p_payload, '{}'))
            when 'bilgi_arenasi' then public._mz_qa_act(rd, u, p_kind, coalesce(p_payload, '{}'))
            when 'iki_dogru_bir_yalan' then public._mz_tl_act(rd, u, p_kind, coalesce(p_payload, '{}'))
            when 'en_muhtemel_kim' then public._mz_ml_act(rd, u, p_kind, coalesce(p_payload, '{}'))
            when 'kelime_zinciri' then public._mz_wc_act(rd, u, p_kind, coalesce(p_payload, '{}'))
            when 'cizim_telefonu' then public._mz_tp_act(rd, u, p_kind, coalesce(p_payload, '{}'))
        end;
    end if;
    perform public._mode_advance(rd.room_id);       -- e.g. everybody answered: go on at once
    perform public._bump(rd.room_id);
    return res;
end $$;

-- Somebody left a duel / small game: the players still present get the win.
create or replace function public._mode_advance(p_room uuid)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms; m public.matches; rd public.mode_rounds; changed boolean := false; guard int := 0; x uuid;
begin
    select * into r from public.rooms where id = p_room;
    if r.status <> 'in_game' or r.current_match_id is null then return false; end if;
    select * into m from public.matches where id = r.current_match_id;
    if m.status <> 'in_progress' then return false; end if;
    loop
        guard := guard + 1;
        exit when guard > 8;
        -- Everybody else left: end the match properly (XP for those still here).
        if coalesce(array_length(public._mz_players(m.id), 1), 0) < 2 then
            if public._tg_is(m.game_mode) then
                foreach x in array public._mz_players(m.id) loop perform public._mz_award(m.id, x, 100000); end loop;
            end if;
            perform public._finish_match(m.id);
            changed := true;
            exit;
        end if;
        select * into rd from public.mode_rounds where match_id = m.id order by round_number desc limit 1 for update;
        exit when not found;
        if rd.phase = 'done' then
            changed := true;
            exit when not public._mode_next(m);
            continue;
        end if;
        exit when not public._mz_tick(rd);
        changed := true;
    end loop;
    if changed then perform public._bump(p_room); end if;
    return changed;
end $$;

-- =====================================================================
-- 21 (1v1, no betting of any kind)
-- Each hand: one shuffled deck, both get two cards; the opponent sees only your first card.
-- Players act in turn (hit / stand); the starter alternates. Over 21 loses the hand.
-- Higher valid total wins the hand; equal totals (or both over) are a push.
-- Match: first to ceil(hands/2) hand wins; at most 2 * hands hands, then more hand wins.
-- =====================================================================
create or replace function public._opts_yirmi_bir(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
begin
    return jsonb_build_object('hands', case when (o->>'hands')::int in (3, 5, 7) then (o->>'hands')::int else 5 end,
                              'turn_seconds', case when (o->>'turn_seconds')::int in (15, 20, 30) then (o->>'turn_seconds')::int else 20 end);
end $$;

create or replace function public._yb_value(p_cards jsonb)
returns int language plpgsql immutable as $$
declare total int := 0; aces int := 0; c jsonb; r int;
begin
    for c in select value from jsonb_array_elements(coalesce(p_cards, '[]'::jsonb)) loop
        r := (c->>'r')::int;
        if r = 1 then aces := aces + 1; total := total + 11;
        elsif r >= 10 then total := total + 10;
        else total := total + r; end if;
    end loop;
    while total > 21 and aces > 0 loop total := total - 10; aces := aces - 1; end loop;
    return total;
end $$;

create or replace function public._yb_deck()
returns jsonb language sql volatile as $$
    select public._tg_shuffle(coalesce(jsonb_agg(jsonb_build_object('r', r, 's', s)), '[]'::jsonb))
      from generate_series(1, 13) r, unnest(array['S','H','D','C']) s;
$$;

create or replace function public._g_yirmi_bir_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare hands int := coalesce((public._tg_opt(m.id, 'hands'))::int, 5); need int; ms jsonb := public._mz_state(m.id);
        wins jsonb := coalesce(ms->'yb_wins', '{}'::jsonb); a uuid; b uuid; deck jsonb; first uuid; secs int := public._tg_secs(m.id, 20);
begin
    need := (hands + 1) / 2;
    if array_length(players, 1) < 2 then return null; end if;
    a := players[1]; b := players[2];
    if coalesce((wins->>a::text)::int, 0) >= need or coalesce((wins->>b::text)::int, 0) >= need or n > hands * 2 then return null; end if;
    deck := public._yb_deck();
    first := case when n % 2 = 1 then a else b end;
    return jsonb_build_object('phase', 'play', 'hand', n, 'need', need, 'players', to_jsonb(players[1:2]),
        'hands', jsonb_build_object(a::text, jsonb_build_array(deck->0, deck->2), b::text, jsonb_build_array(deck->1, deck->3)),
        'deck', (select coalesce(jsonb_agg(v), '[]'::jsonb) from jsonb_array_elements(deck) with ordinality t(v, i) where i > 4),
        'stood', '[]'::jsonb, 'turn', first, 'first', first, 'secs', secs, 'deadline', public._tg_deadline(secs),
        'wins', wins, 'timeouts', '{}'::jsonb);
end $$;

create or replace function public._yb_settle(s jsonb, p_match uuid)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare a text := s->'players'->>0; b text := s->'players'->>1; va int := public._yb_value(s->'hands'->a); vb int := public._yb_value(s->'hands'->b);
        w text; wins jsonb := coalesce(s->'wins', '{}'::jsonb);
begin
    if va > 21 and vb > 21 then w := null;
    elsif va > 21 then w := b;
    elsif vb > 21 then w := a;
    elsif va > vb then w := a;
    elsif vb > va then w := b;
    else w := null; end if;
    if w is not null then wins := jsonb_set(wins, array[w], to_jsonb(coalesce((wins->>w)::int, 0) + 1)); end if;
    perform public._mz_set(p_match, 'yb_wins', wins);
    return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'winner', w, 'wins', wins,
        'totals', jsonb_build_object(a, va, b, vb),
        'points', case when w is null then '{}'::jsonb else jsonb_build_object(w, 1) end,
        'stats', jsonb_build_object(a, jsonb_build_object('hands', 1, 'hands_won', case when w = a then 1 else 0 end, 'busts', case when va > 21 then 1 else 0 end, 'twenty_ones', case when va = 21 then 1 else 0 end),
                                    b, jsonb_build_object('hands', 1, 'hands_won', case when w = b then 1 else 0 end, 'busts', case when vb > 21 then 1 else 0 end, 'twenty_ones', case when vb = 21 then 1 else 0 end)));
end $$;

create or replace function public._yb_after(s jsonb)
returns jsonb language plpgsql volatile as $$
declare a text := s->'players'->>0; b text := s->'players'->>1; cur text := s->>'turn'; other text;
        cur_done boolean; other_done boolean;
begin
    other := case when cur = a then b else a end;
    cur_done := (s->'stood' ? cur) or public._yb_value(s->'hands'->cur) > 21;
    other_done := (s->'stood' ? other) or public._yb_value(s->'hands'->other) > 21;
    if cur_done and other_done then return s || jsonb_build_object('settle', true); end if;
    if cur_done then cur := other; end if;
    return s || jsonb_build_object('turn', cur, 'deadline', public._tg_deadline((s->>'secs')::int));
end $$;

create or replace function public._g_yirmi_bir_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; m uuid;
begin
    if s->>'turn' is distinct from me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    if p_kind = 'hit' then
        s := jsonb_set(s, array['hands', me], (s->'hands'->me) || jsonb_build_array(s->'deck'->0));
        s := jsonb_set(s, '{deck}', (s->'deck') - 0);
        s := public._tg_log(s, jsonb_build_object('u', me, 'a', 'hit'));
    elsif p_kind = 'stand' then
        s := jsonb_set(s, '{stood}', (s->'stood') || to_jsonb(me));
        s := public._tg_log(s, jsonb_build_object('u', me, 'a', 'stand'));
    else
        raise exception 'LOOPA:INVALID_ACTION';
    end if;
    s := public._yb_after(s);
    if coalesce((s->>'settle')::boolean, false) then
        s := public._yb_settle(s - 'settle', (s->>'match')::uuid);
    end if;
    return s;
end $$;

create or replace function public._g_yirmi_bir_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := s->>'turn';
begin
    if me is null then return s; end if;
    s := public._tg_timeout_count(s, me::uuid);
    return public._g_yirmi_bir_act(s, me::uuid, 'stand', '{}'::jsonb);      -- time is up: stand
end $$;

create or replace function public._g_yirmi_bir_public(s jsonb)
returns jsonb language plpgsql immutable as $$
declare done boolean := coalesce((s->>'over')::boolean, false);
begin
    return jsonb_build_object('hand', s->'hand', 'need', s->'need', 'players', s->'players', 'turn', s->'turn', 'wins', s->'wins',
        'stood', s->'stood', 'log', coalesce(s->'log', '[]'::jsonb), 'winner', s->'winner', 'totals', case when done then s->'totals' end,
        -- open cards: everybody sees each player's first card; all cards after the hand
        'shown', (select jsonb_object_agg(k, case when done then v else jsonb_build_array(v->0) end) from jsonb_each(s->'hands') x(k, v)),
        'counts', (select jsonb_object_agg(k, jsonb_array_length(v)) from jsonb_each(s->'hands') x(k, v)));
end $$;

create or replace function public._g_yirmi_bir_view(s jsonb, u uuid)
returns jsonb language plpgsql immutable as $$
begin
    if not (s->'hands' ? u::text) then return '{}'::jsonb; end if;
    return jsonb_build_object('hand', s->'hands'->u::text, 'total', public._yb_value(s->'hands'->u::text));
end $$;

-- =====================================================================
-- Tavla (Turkish backgammon): 15 checkers each, two dice, doubles = 4 moves, hits, bar entry,
-- bearing off, "mars" (opponent has borne off nothing) counts 2. No doubling cube.
-- Board: 24 ints from player 1's view (+ = player 1, - = player 2). Player 1 moves 24 -> 1;
-- player 2 is handled by mirroring, so the move logic always moves high -> low.
-- Every turn must use as many dice as possible; if only one die can be used, the larger one.
-- =====================================================================
create or replace function public._opts_tavla(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
begin
    return jsonb_build_object('points', case when (o->>'points')::int in (1, 3, 5) then (o->>'points')::int else 3 end,
                              'turn_seconds', case when (o->>'turn_seconds')::int in (30, 45, 60, 90) then (o->>'turn_seconds')::int else 60 end);
end $$;

-- mover-relative board: mirror for player 2
create or replace function public._tv_rel(b int[], side int)
returns int[] language plpgsql immutable as $$
declare r int[] := array_fill(0, array[24]); i int;
begin
    if side = 1 then return b; end if;
    for i in 1..24 loop r[i] := -b[25 - i]; end loop;
    return r;
end $$;

-- One checker move in mover space. Returns {b, bar, obar, off, hit} or null if illegal. from = 25: from the bar.
create or replace function public._tv_step(b int[], bar int, obar int, off int, p_from int, d int)
returns jsonb language plpgsql immutable as $$
declare t int; i int; hit boolean := false;
begin
    if bar > 0 and p_from <> 25 then return null; end if;
    if p_from = 25 then
        if bar <= 0 then return null; end if;
        t := 25 - d;
    else
        if p_from < 1 or p_from > 24 or b[p_from] <= 0 then return null; end if;
        t := p_from - d;
    end if;
    if t <= 0 then
        -- bearing off: everything home (1..6), exact, or the highest checker with a larger die
        if bar > 0 then return null; end if;
        for i in 7..24 loop if b[i] > 0 then return null; end if; end loop;
        if t < 0 then
            for i in p_from + 1..6 loop if b[i] > 0 then return null; end if; end loop;
        end if;
        b[p_from] := b[p_from] - 1;
        return jsonb_build_object('b', b, 'bar', bar, 'obar', obar, 'off', off + 1, 'hit', false);
    end if;
    if b[t] < -1 then return null; end if;
    if b[t] = -1 then b[t] := 0; obar := obar + 1; hit := true; end if;
    if p_from = 25 then bar := bar - 1; else b[p_from] := b[p_from] - 1; end if;
    b[t] := b[t] + 1;
    return jsonb_build_object('b', b, 'bar', bar, 'obar', obar, 'off', off, 'hit', hit);
end $$;

-- Longest playable sequence (number of dice) and one example path. dice: remaining dice values.
create or replace function public._tv_best(b int[], bar int, obar int, off int, dice int[])
returns jsonb language plpgsql immutable as $$
declare best jsonb := jsonb_build_object('use', 0, 'path', '[]'::jsonb); i int; f int; st jsonb; sub jsonb; rest int[]; tried int[] := '{}';
begin
    if coalesce(array_length(dice, 1), 0) = 0 then return best; end if;
    for i in 1..array_length(dice, 1) loop
        if dice[i] = any(tried) then continue; end if;          -- same value: same subtree
        tried := tried || dice[i];
        rest := dice[1:i - 1] || dice[i + 1:array_length(dice, 1)];
        for f in select unnest(array[25] || array(select generate_series(24, 1, -1))) loop
            st := public._tv_step(b, bar, obar, off, f, dice[i]);
            continue when st is null;
            sub := public._tv_best(array(select jsonb_array_elements_text(st->'b')::int), (st->>'bar')::int, (st->>'obar')::int, (st->>'off')::int, rest);
            if (sub->>'use')::int + 1 > (best->>'use')::int then
                best := jsonb_build_object('use', (sub->>'use')::int + 1,
                                           'path', jsonb_build_array(jsonb_build_object('from', f, 'die', dice[i])) || (sub->'path'));
                if (best->>'use')::int = array_length(dice, 1) then return best; end if;
            end if;
        end loop;
    end loop;
    return best;
end $$;

create or replace function public._g_tavla_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare target int := coalesce((public._tg_opt(m.id, 'points'))::int, 3); ms jsonb := public._mz_state(m.id);
        score jsonb := coalesce(ms->'tv_score', '{}'::jsonb); a uuid; b uuid; d1 int; d2 int; first uuid; s jsonb; secs int := public._tg_secs(m.id, 60);
begin
    if array_length(players, 1) < 2 then return null; end if;
    a := players[1]; b := players[2];
    if coalesce((score->>a::text)::int, 0) >= target or coalesce((score->>b::text)::int, 0) >= target then return null; end if;
    -- opening: one die each, higher starts (re-roll ties); the starter then rolls both dice
    loop d1 := public._tg_die(); d2 := public._tg_die(); exit when d1 <> d2; end loop;
    first := case when d1 > d2 then a else b end;
    s := jsonb_build_object('phase', 'play', 'game', n, 'target', target, 'players', jsonb_build_array(a, b),
        'b', '[-2,0,0,0,0,5, 0,3,0,0,0,-5, 5,0,0,0,-3,0, -5,0,0,0,0,2]'::jsonb,   -- index 1..24 (p1 view)
        'bar', jsonb_build_array(0, 0), 'off', jsonb_build_array(0, 0),
        'opening', jsonb_build_object(a::text, d1, b::text, d2),
        'score', score, 'secs', secs, 'timeouts', '{}'::jsonb);
    return public._tv_start_turn(s, first);
end $$;

create or replace function public._tv_side(s jsonb, u text)
returns int language sql immutable as $$ select case when s->'players'->>0 = u then 1 else 2 end; $$;

create or replace function public._tv_start_turn(s jsonb, u uuid)
returns jsonb language plpgsql volatile as $$
declare d1 int := public._tg_die(); d2 int := public._tg_die(); dice int[]; side int := public._tv_side(s, u::text);
        b int[] := array(select jsonb_array_elements_text(s->'b')::int); best jsonb;
begin
    dice := case when d1 = d2 then array[d1, d1, d1, d1] else array[d1, d2] end;
    best := public._tv_best(public._tv_rel(b, side), (s->'bar'->>(side - 1))::int, (s->'bar'->>(2 - side))::int, (s->'off'->>(side - 1))::int, dice);
    s := s || jsonb_build_object('turn', u, 'dice', to_jsonb(dice), 'max_use', (best->>'use')::int,
                                 'deadline', public._tg_deadline((s->>'secs')::int));
    s := public._tg_log(s, jsonb_build_object('u', u, 'roll', jsonb_build_array(d1, d2)));
    if (best->>'use')::int = 0 then
        -- no legal move at all: the turn passes
        s := public._tg_log(s, jsonb_build_object('u', u, 'pass', true));
        return s || jsonb_build_object('blocked', true);
    end if;
    return s - 'blocked';
end $$;

-- payload: {"moves":[{"from":13,"die":5}, ...]} (from 25 = bar; points in the mover's own numbering 1..24)
create or replace function public._g_tavla_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; side int; b int[]; bar int; obar int; off int; dice int[]; mv jsonb; st jsonb; used int := 0; i int; k int;
        hits int := 0; other uuid; lose_off int; pts int; mars boolean; win_score jsonb; single jsonb;
begin
    if p_kind = 'pass' and coalesce((s->>'blocked')::boolean, false) and s->>'turn' = me then
        other := (case when s->'players'->>0 = me then s->'players'->>1 else s->'players'->>0 end)::uuid;
        return public._tv_start_turn(s, other);
    end if;
    if p_kind <> 'move' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if s->>'turn' is distinct from me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    side := public._tv_side(s, me);
    b := public._tv_rel(array(select jsonb_array_elements_text(s->'b')::int), side);
    bar := (s->'bar'->>(side - 1))::int; obar := (s->'bar'->>(2 - side))::int; off := (s->'off'->>(side - 1))::int;
    dice := array(select jsonb_array_elements_text(s->'dice')::int);
    if jsonb_typeof(p->'moves') <> 'array' or jsonb_array_length(p->'moves') <> (s->>'max_use')::int then
        raise exception 'LOOPA:MUST_USE_DICE';
    end if;
    for mv in select value from jsonb_array_elements(p->'moves') loop
        k := array_position(dice, (mv->>'die')::int);
        if k is null then raise exception 'LOOPA:INVALID_MOVE'; end if;
        st := public._tv_step(b, bar, obar, off, (mv->>'from')::int, (mv->>'die')::int);
        if st is null then raise exception 'LOOPA:INVALID_MOVE'; end if;
        b := array(select jsonb_array_elements_text(st->'b')::int); bar := (st->>'bar')::int; obar := (st->>'obar')::int; off := (st->>'off')::int;
        if (st->>'hit')::boolean then hits := hits + 1; end if;
        dice := dice[1:k - 1] || dice[k + 1:array_length(dice, 1)];
        used := used + 1;
    end loop;
    -- only one die playable out of two different dice: it must be the larger one when that is possible
    if (s->>'max_use')::int = 1 and jsonb_array_length(s->'dice') = 2 and (s->'dice'->>0) <> (s->'dice'->>1) then
        single := public._tv_best(public._tv_rel(array(select jsonb_array_elements_text(s->'b')::int), side),
                                  (s->'bar'->>(side - 1))::int, (s->'bar'->>(2 - side))::int, (s->'off'->>(side - 1))::int,
                                  array[greatest((s->'dice'->>0)::int, (s->'dice'->>1)::int)]);
        if (single->>'use')::int = 1 and (p->'moves'->0->>'die')::int <> greatest((s->'dice'->>0)::int, (s->'dice'->>1)::int) then
            raise exception 'LOOPA:MUST_USE_LARGER_DIE';
        end if;
    end if;
    -- write back in player 1 space
    s := s || jsonb_build_object('b', to_jsonb(public._tv_rel(b, side)),
        'bar', case when side = 1 then jsonb_build_array(bar, obar) else jsonb_build_array(obar, bar) end,
        'off', case when side = 1 then jsonb_build_array(off, (s->'off'->>1)::int) else jsonb_build_array((s->'off'->>0)::int, off) end);
    s := public._tg_log(s, jsonb_build_object('u', me, 'moves', p->'moves', 'hits', hits));
    if hits > 0 then s := jsonb_set(s, array['hits', me], to_jsonb(coalesce((s->'hits'->>me)::int, 0) + hits), true); end if;
    other := (case when s->'players'->>0 = me then s->'players'->>1 else s->'players'->>0 end)::uuid;
    if off = 15 then
        lose_off := (s->'off'->>(2 - side))::int;
        mars := lose_off = 0;
        pts := case when mars then 2 else 1 end;
        win_score := jsonb_set(coalesce(s->'score', '{}'::jsonb), array[me], to_jsonb(coalesce((s->'score'->>me)::int, 0) + pts));
        perform public._mz_set((s->>'match')::uuid, 'tv_score', win_score);
        return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'winner', me, 'mars', mars, 'score', win_score,
            'points', jsonb_build_object(me, pts),
            'stats', jsonb_build_object(me, jsonb_build_object('games', 1, 'games_won', 1, 'mars_won', case when mars then 1 else 0 end, 'hits', coalesce((s->'hits'->>me)::int, 0)),
                                        other::text, jsonb_build_object('games', 1, 'mars_lost', case when mars then 1 else 0 end, 'hits', coalesce((s->'hits'->>other::text)::int, 0))));
    end if;
    return public._tv_start_turn(s, other);
end $$;

create or replace function public._g_tavla_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := s->>'turn'; side int; best jsonb; u uuid;
begin
    if me is null then return s; end if;
    u := me::uuid;
    s := public._tg_timeout_count(s, u);
    if coalesce((s->>'blocked')::boolean, false) then return public._g_tavla_act(s, u, 'pass', '{}'); end if;
    side := public._tv_side(s, me);
    -- three timeouts in a row: the game is conceded
    if (s->'timeouts'->>me)::int >= 3 then
        return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'forfeit', me,
            'winner', case when s->'players'->>0 = me then s->'players'->>1 else s->'players'->>0 end,
            'points', jsonb_build_object(case when s->'players'->>0 = me then s->'players'->>1 else s->'players'->>0 end, 2));
    end if;
    best := public._tv_best(public._tv_rel(array(select jsonb_array_elements_text(s->'b')::int), side),
                            (s->'bar'->>(side - 1))::int, (s->'bar'->>(2 - side))::int, (s->'off'->>(side - 1))::int,
                            array(select jsonb_array_elements_text(s->'dice')::int));
    return public._g_tavla_act(s, u, 'move', jsonb_build_object('moves', best->'path'));
end $$;

create or replace function public._g_tavla_public(s jsonb)
returns jsonb language sql immutable as $$
    select jsonb_build_object('players', s->'players', 'b', s->'b', 'bar', s->'bar', 'off', s->'off', 'turn', s->'turn',
        'dice', s->'dice', 'max_use', s->'max_use', 'blocked', coalesce(s->'blocked', 'false'::jsonb), 'score', s->'score',
        'target', s->'target', 'game', s->'game', 'opening', s->'opening', 'winner', s->'winner', 'mars', s->'mars', 'log', coalesce(s->'log', '[]'::jsonb));
$$;

create or replace function public._g_tavla_view(s jsonb, u uuid)
returns jsonb language sql immutable as $$
    -- nothing is hidden in tavla; the side tells the client how to orient the board
    select case when s->'players' ? u::text then jsonb_build_object('side', public._tv_side(s, u::text)) else '{}'::jsonb end;
$$;

-- =====================================================================
-- Kızma Birader (2-4 players, 4 pieces each)
-- Track of 40 squares; each player starts at 10 * seat. Pieces: -1 = yard, 0..39 steps on the
-- track from the own start, 40..43 own home column (exact roll). A 6 brings a piece out and gives
-- another roll; with nothing on the track a player gets three tries for a 6. Landing on an
-- opponent sends it back to its yard, except on a start square (safe). No two own pieces on
-- one square. First to bring all four home wins; the others are ranked by progress.
-- =====================================================================
create or replace function public._opts_kizma_birader(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
begin
    return jsonb_build_object('turn_seconds', case when (o->>'turn_seconds')::int in (15, 20, 30) then (o->>'turn_seconds')::int else 20 end);
end $$;

create or replace function public._kb_abs(seat int, prog int)
returns int language sql immutable as $$ select case when prog between 0 and 39 then (seat * 10 + prog) % 40 end; $$;

-- Legal target for a piece, or null.
create or replace function public._kb_target(s jsonb, me text, piece int, d int)
returns int language plpgsql immutable as $$
declare cur int := (s->'pieces'->me->>piece)::int; t int; i int;
begin
    if cur = -1 then
        if d <> 6 then return null; end if;
        t := 0;
    else
        if cur >= 40 and cur + d > 43 then return null; end if;
        t := cur + d;
        if t > 43 then return null; end if;
    end if;
    for i in 0..3 loop
        if i <> piece and (s->'pieces'->me->>i)::int = t then return null; end if;     -- own piece there
    end loop;
    return t;
end $$;

create or replace function public._kb_movable(s jsonb, me text, d int)
returns jsonb language plpgsql immutable as $$
declare r jsonb := '[]'::jsonb; i int;
begin
    for i in 0..3 loop
        if (s->'pieces'->me->>i)::int < 44 and public._kb_target(s, me, i, d) is not null then r := r || to_jsonb(i); end if;
    end loop;
    return r;
end $$;

create or replace function public._kb_roll(s jsonb, u text)
returns jsonb language plpgsql volatile as $$
declare d int := public._tg_die(); mov jsonb;
begin
    s := s || jsonb_build_object('turn', u, 'die', d, 'deadline', public._tg_deadline((s->>'secs')::int));
    s := public._tg_log(s, jsonb_build_object('u', u, 'roll', d));
    mov := public._kb_movable(s, u, d);
    return s || jsonb_build_object('movable', mov);
end $$;

create or replace function public._g_kizma_birader_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare s jsonb; pieces jsonb := '{}'::jsonb; seats jsonb := '{}'::jsonb; i int; secs int := public._tg_secs(m.id, 20);
begin
    if n > 1 or array_length(players, 1) < 2 then return null; end if;
    for i in 1..array_length(players, 1) loop
        pieces := pieces || jsonb_build_object(players[i]::text, '[-1,-1,-1,-1]'::jsonb);
        seats := seats || jsonb_build_object(players[i]::text, i - 1);
    end loop;
    s := jsonb_build_object('phase', 'play', 'order', to_jsonb(players), 'pieces', pieces, 'seats', seats, 'finished', '[]'::jsonb,
                            'tries', 0, 'secs', secs, 'timeouts', '{}'::jsonb, 'captures', '{}'::jsonb);
    return public._kb_roll(s, players[1]::text);
end $$;

create or replace function public._kb_all_out(s jsonb, me text)
returns boolean language sql immutable as $$
    select not exists (select 1 from jsonb_array_elements_text(s->'pieces'->me) p where p::int between 0 and 39);
$$;

create or replace function public._kb_next(s jsonb, extra boolean)
returns jsonb language plpgsql volatile as $$
declare me text := s->>'turn'; nxt uuid;
begin
    if extra then return public._kb_roll(s || jsonb_build_object('tries', 0), me); end if;
    nxt := public._tg_next_player(s->'order', me::uuid, s->'finished' || coalesce(s->'gone', '[]'::jsonb));
    return public._kb_roll(s || jsonb_build_object('tries', 0), nxt::text);
end $$;

create or replace function public._kb_finish(s jsonb)
returns jsonb language plpgsql immutable as $$
declare pts jsonb := '{}'::jsonb; stats jsonb := '{}'::jsonb; u text; prog int; rank int := 0; x record;
begin
    for x in select k, (select coalesce(sum(greatest(v2::int, 0)), 0) + 1000 * case when s->'finished' ? k then 1 else 0 end
                         - 100 * coalesce((select ord from jsonb_array_elements_text(s->'finished') with ordinality f(v, ord) where v = k), 0)
                        from jsonb_array_elements_text(s->'pieces'->k) v2) as score
               from jsonb_object_keys(s->'pieces') k order by 2 desc loop
        rank := rank + 1;
        pts := pts || jsonb_build_object(x.k, greatest(0, 400 - rank * 100));
        stats := stats || jsonb_build_object(x.k, jsonb_build_object('games', 1, 'wins', case when rank = 1 then 1 else 0 end,
                                                                      'captures', coalesce((s->'captures'->>x.k)::int, 0)));
    end loop;
    return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'points', pts, 'stats', stats, 'winner', s->'finished'->0);
end $$;

-- payload: {"piece": 0..3}
create or replace function public._g_kizma_birader_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; d int := (s->>'die')::int; piece int := (p->>'piece')::int; t int; k text; seat int; tabs int; i int; captured boolean := false;
begin
    if s->>'turn' is distinct from me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    if p_kind = 'pass' then
        if jsonb_array_length(s->'movable') > 0 then raise exception 'LOOPA:MUST_MOVE'; end if;
        -- nothing on the track: up to three rolls for a 6
        if public._kb_all_out(s, me) and coalesce((s->>'tries')::int, 0) < 2 and d <> 6 then
            return public._kb_roll(s || jsonb_build_object('tries', coalesce((s->>'tries')::int, 0) + 1), me);
        end if;
        return public._kb_next(s, false);
    end if;
    if p_kind <> 'move' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if piece is null or not (s->'movable' @> to_jsonb(piece)) then raise exception 'LOOPA:INVALID_MOVE'; end if;
    t := public._kb_target(s, me, piece, d);
    seat := (s->'seats'->>me)::int;
    s := jsonb_set(s, array['pieces', me, piece::text], to_jsonb(t));
    -- capture on the shared track (not on any start square)
    if t between 0 and 39 then
        tabs := public._kb_abs(seat, t);
        if tabs % 10 <> 0 then
            for k in select jsonb_object_keys(s->'pieces') loop
                continue when k = me;
                for i in 0..3 loop
                    if public._kb_abs((s->'seats'->>k)::int, (s->'pieces'->k->>i)::int) = tabs then
                        s := jsonb_set(s, array['pieces', k, i::text], '-1'::jsonb);
                        captured := true;
                    end if;
                end loop;
            end loop;
        end if;
    end if;
    if captured then s := jsonb_set(s, array['captures', me], to_jsonb(coalesce((s->'captures'->>me)::int, 0) + 1)); end if;
    s := public._tg_log(s, jsonb_build_object('u', me, 'piece', piece, 'to', t, 'captured', captured));
    if not exists (select 1 from jsonb_array_elements_text(s->'pieces'->me) v where v::int < 40) then
        s := jsonb_set(s, '{finished}', (s->'finished') || to_jsonb(me));
        return public._kb_finish(s);                         -- first one home wins; the rest are ranked
    end if;
    return public._kb_next(s, d = 6);
end $$;

create or replace function public._g_kizma_birader_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := s->>'turn'; u uuid;
begin
    if me is null then return s; end if;
    u := me::uuid;
    s := public._tg_timeout_count(s, u);
    if jsonb_array_length(s->'movable') > 0 then
        return public._g_kizma_birader_act(s, u, 'move', jsonb_build_object('piece', s->'movable'->0));
    end if;
    return public._g_kizma_birader_act(s, u, 'pass', '{}');
end $$;

create or replace function public._g_kizma_birader_public(s jsonb)
returns jsonb language sql immutable as $$
    select jsonb_build_object('order', s->'order', 'pieces', s->'pieces', 'seats', s->'seats', 'turn', s->'turn', 'die', s->'die',
        'movable', s->'movable', 'finished', s->'finished', 'winner', s->'winner', 'log', coalesce(s->'log', '[]'::jsonb));
$$;

create or replace function public._g_kizma_birader_view(s jsonb, u uuid)
returns jsonb language sql immutable as $$
    select case when s->'seats' ? u::text then jsonb_build_object('seat', s->'seats'->u::text) else '{}'::jsonb end;
$$;

-- =====================================================================
-- Amiral Battı (1v1 or 2v2)
-- 1v1: 10x10, ships 5,4,3,3,2, one shot per turn.
-- 2v2: 14x14, ships 5,4,4,3,3,3,2,2 per team, the team fires two shots per turn (one per member;
--      a member who is gone or out of time is covered by the teammate).
-- Placement is hidden; the server answers hit / miss / sunk. Whole fleet sunk = defeat.
-- =====================================================================
create or replace function public._opts_amiral_batti(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
begin
    return jsonb_build_object('format', case when o->>'format' = '2v2' then '2v2' else '1v1' end,
                              'turn_seconds', case when (o->>'turn_seconds')::int in (20, 30, 45) then (o->>'turn_seconds')::int else 30 end);
end $$;

create or replace function public._startcheck_amiral_batti(p_settings jsonb, n int)
returns void language plpgsql immutable as $$
begin
    if coalesce(p_settings->'mode_options'->>'format', '1v1') = '2v2' and n <> 4 then raise exception 'LOOPA:WRONG_PLAYER_COUNT'; end if;
    if coalesce(p_settings->'mode_options'->>'format', '1v1') = '1v1' and n <> 2 then raise exception 'LOOPA:WRONG_PLAYER_COUNT'; end if;
end $$;

create or replace function public._ab_ships(fmt text)
returns int[] language sql immutable as $$
    select case when fmt = '2v2' then array[5,4,4,3,3,3,2,2] else array[5,4,3,3,2] end;
$$;

-- Validates a fleet [{"x":0,"y":0,"len":5,"dir":"h"}, ...] and returns its cells or raises.
create or replace function public._ab_cells(p_fleet jsonb, p_size int, p_ships int[])
returns jsonb language plpgsql immutable as $$
declare cells jsonb := '[]'::jsonb; sh jsonb; i int; x int; y int; c text; lens int[] := '{}'; ship_cells jsonb; idx int := 0;
begin
    if jsonb_typeof(p_fleet) <> 'array' or jsonb_array_length(p_fleet) <> array_length(p_ships, 1) then raise exception 'LOOPA:INVALID_FLEET'; end if;
    for sh in select value from jsonb_array_elements(p_fleet) loop
        lens := lens || (sh->>'len')::int;
        ship_cells := '[]'::jsonb;
        for i in 0..(sh->>'len')::int - 1 loop
            x := (sh->>'x')::int + case when sh->>'dir' = 'h' then i else 0 end;
            y := (sh->>'y')::int + case when sh->>'dir' = 'v' then i else 0 end;
            if sh->>'dir' not in ('h','v') or x < 0 or y < 0 or x >= p_size or y >= p_size then raise exception 'LOOPA:INVALID_FLEET'; end if;
            c := x || ',' || y;
            if cells ? c then raise exception 'LOOPA:INVALID_FLEET'; end if;
            cells := cells || to_jsonb(c); ship_cells := ship_cells || to_jsonb(c);
        end loop;
        idx := idx + 1;
    end loop;
    if (select array_agg(v order by v) from unnest(lens) v) <> (select array_agg(v order by v) from unnest(p_ships) v) then
        raise exception 'LOOPA:INVALID_FLEET';
    end if;
    return cells;
end $$;

create or replace function public._ab_random_fleet(p_size int, p_ships int[])
returns jsonb language plpgsql volatile as $$
declare fleet jsonb; occ jsonb; len int; tries int; x int; y int; dir text; ok boolean; i int; c text;
begin
    loop
        fleet := '[]'::jsonb; occ := '[]'::jsonb; ok := true;
        foreach len in array p_ships loop
            tries := 0;
            loop
                tries := tries + 1;
                if tries > 200 then ok := false; exit; end if;
                dir := case when random() < 0.5 then 'h' else 'v' end;
                x := floor(random() * (p_size - case when dir = 'h' then len - 1 else 0 end))::int;
                y := floor(random() * (p_size - case when dir = 'v' then len - 1 else 0 end))::int;
                if exists (select 1 from generate_series(0, len - 1) g
                            where occ ? ((x + case when dir = 'h' then g else 0 end) || ',' || (y + case when dir = 'v' then g else 0 end))) then
                    continue;
                end if;
                for i in 0..len - 1 loop
                    c := (x + case when dir = 'h' then i else 0 end) || ',' || (y + case when dir = 'v' then i else 0 end);
                    occ := occ || to_jsonb(c);
                end loop;
                fleet := fleet || jsonb_build_array(jsonb_build_object('x', x, 'y', y, 'len', len, 'dir', dir));
                exit;
            end loop;
            exit when not ok;
        end loop;
        exit when ok;
    end loop;
    return fleet;
end $$;

create or replace function public._g_amiral_batti_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare fmt text := coalesce(public._tg_opt(m.id, 'format')#>>'{}', '1v1'); teams jsonb; size int; secs int := public._tg_secs(m.id, 30);
        side_of jsonb := '{}'::jsonb; i int; t1 jsonb := '[]'::jsonb; t2 jsonb := '[]'::jsonb; x record;
begin
    if n > 1 or array_length(players, 1) < 2 then return null; end if;
    size := case when fmt = '2v2' then 14 else 10 end;
    if fmt = '2v2' then
        -- teams from match_players.team_side (balanced in the lobby) or seats 1+3 vs 2+4
        for x in select mp.user_id, mp.team_side from public.match_players mp where mp.match_id = m.id loop
            if x.team_side = 1 then t1 := t1 || to_jsonb(x.user_id); elsif x.team_side = 2 then t2 := t2 || to_jsonb(x.user_id); end if;
        end loop;
        if jsonb_array_length(t1) <> 2 or jsonb_array_length(t2) <> 2 then
            t1 := jsonb_build_array(players[1], players[3]); t2 := jsonb_build_array(players[2], players[4]);
            update public.match_players set team_side = case when user_id in (players[1], players[3]) then 1 else 2 end where match_id = m.id;
        end if;
        teams := jsonb_build_array(t1, t2);
    else
        teams := jsonb_build_array(jsonb_build_array(players[1]), jsonb_build_array(players[2]));
        update public.match_players set team_side = case when user_id = players[1] then 1 else 2 end where match_id = m.id;
    end if;
    for i in 0..1 loop
        for x in select value #>> '{}' as u from jsonb_array_elements(teams->i) loop side_of := side_of || jsonb_build_object(x.u, i); end loop;
    end loop;
    return jsonb_build_object('phase', 'placement', 'format', fmt, 'size', size, 'ships', to_jsonb(public._ab_ships(fmt)),
        'teams', teams, 'side_of', side_of, 'fleets', jsonb_build_array(null, null), 'cells', jsonb_build_array(null, null),
        'shots', jsonb_build_array('{}'::jsonb, '{}'::jsonb),          -- shots[i]: shots fired AT team i: {"x,y": "hit"/"miss"}
        'sunk', jsonb_build_array('[]'::jsonb, '[]'::jsonb), 'secs', secs, 'turn', null,
        'deadline', public._tg_deadline(90), 'timeouts', '{}'::jsonb);
end $$;

create or replace function public._ab_begin_battle(s jsonb)
returns jsonb language plpgsql volatile as $$
declare first int := floor(random() * 2)::int;
begin
    return s || jsonb_build_object('phase', 'battle', 'shooting', first, 'turn', s->'teams'->first->>0,
        'shots_left', case when s->>'format' = '2v2' then 2 else 1 end, 'fired', '[]'::jsonb,
        'deadline', public._tg_deadline((s->>'secs')::int));
end $$;

-- Which ship (index) covers a cell, from a fleet
create or replace function public._ab_ship_at(p_fleet jsonb, c text)
returns int language plpgsql immutable as $$
declare sh jsonb; i int; idx int := 0; x int; y int;
begin
    for sh in select value from jsonb_array_elements(p_fleet) loop
        for i in 0..(sh->>'len')::int - 1 loop
            x := (sh->>'x')::int + case when sh->>'dir' = 'h' then i else 0 end;
            y := (sh->>'y')::int + case when sh->>'dir' = 'v' then i else 0 end;
            if x || ',' || y = c then return idx; end if;
        end loop;
        idx := idx + 1;
    end loop;
    return null;
end $$;

create or replace function public._g_amiral_batti_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; side int := (s->'side_of'->>me)::int; target int; x int; y int; c text; res text; ship int; sh jsonb;
        all_hit boolean; i int; size int := (s->>'size')::int; fleet jsonb; won boolean; other_member text; pts jsonb; stats jsonb; mem text;
begin
    if side is null then raise exception 'LOOPA:NOT_A_PARTICIPANT'; end if;
    if p_kind = 'place' then
        if s->>'phase' <> 'placement' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
        if s->'fleets'->side <> 'null'::jsonb then raise exception 'LOOPA:ALREADY_SUBMITTED'; end if;
        fleet := case when coalesce((p->>'random')::boolean, false) then public._ab_random_fleet(size, array(select jsonb_array_elements_text(s->'ships')::int))
                      else p->'fleet' end;
        s := jsonb_set(s, array['cells', side::text], public._ab_cells(fleet, size, array(select jsonb_array_elements_text(s->'ships')::int)));
        s := jsonb_set(s, array['fleets', side::text], fleet);
        s := public._tg_log(s, jsonb_build_object('side', side, 'placed', true));
        if s->'fleets'->0 <> 'null'::jsonb and s->'fleets'->1 <> 'null'::jsonb then return public._ab_begin_battle(s); end if;
        return s;
    end if;
    if p_kind <> 'fire' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if s->>'phase' <> 'battle' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if (s->>'shooting')::int <> side then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    -- 2v2: each member fires one of the two shots, unless the teammate cannot
    if s->>'format' = '2v2' and (s->'fired' ? me) then
        other_member := (select v from jsonb_array_elements_text(s->'teams'->side) v where v <> me limit 1);
        if not coalesce((s->>'cover')::boolean, false) then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    end if;
    if s->>'format' = '1v1' and s->>'turn' <> me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    x := (p->>'x')::int; y := (p->>'y')::int;
    if x is null or y is null or x < 0 or y < 0 or x >= size or y >= size then raise exception 'LOOPA:INVALID_MOVE'; end if;
    target := 1 - side; c := x || ',' || y;
    if s->'shots'->target ? c then raise exception 'LOOPA:ALREADY_SHOT'; end if;
    if s->'cells'->target ? c then
        res := 'hit';
        ship := public._ab_ship_at(s->'fleets'->target, c);
        sh := s->'fleets'->target->ship;
        s := jsonb_set(s, array['shots', target::text, c], '"hit"'::jsonb);
        all_hit := true;
        for i in 0..(sh->>'len')::int - 1 loop
            if not (s->'shots'->target ? (((sh->>'x')::int + case when sh->>'dir' = 'h' then i else 0 end) || ',' || ((sh->>'y')::int + case when sh->>'dir' = 'v' then i else 0 end))) then
                all_hit := false;
            end if;
        end loop;
        if all_hit then
            res := 'sunk';
            s := jsonb_set(s, array['sunk', target::text], (s->'sunk'->target) || jsonb_build_array(sh));
        end if;
    else
        res := 'miss';
        s := jsonb_set(s, array['shots', target::text, c], '"miss"'::jsonb);
    end if;
    s := jsonb_set(s, array['stat_shots', me], to_jsonb(coalesce((s->'stat_shots'->>me)::int, 0) + 1), true);
    if res <> 'miss' then s := jsonb_set(s, array['stat_hits', me], to_jsonb(coalesce((s->'stat_hits'->>me)::int, 0) + 1), true); end if;
    s := public._tg_log(s, jsonb_build_object('u', me, 'x', x, 'y', y, 'r', res));
    won := jsonb_array_length(s->'sunk'->target) = jsonb_array_length(s->'ships');
    if won then
        pts := '{}'::jsonb; stats := '{}'::jsonb;
        for mem in select jsonb_array_elements_text(s->'teams'->side) loop
            pts := pts || jsonb_build_object(mem, 1000);
        end loop;
        for mem in select jsonb_object_keys(s->'side_of') loop
            stats := stats || jsonb_build_object(mem, jsonb_build_object('games', 1, 'wins', case when (s->'side_of'->>mem)::int = side then 1 else 0 end,
                'shots', coalesce((s->'stat_shots'->>mem)::int, 0), 'hits', coalesce((s->'stat_hits'->>mem)::int, 0)));
        end loop;
        return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'winner_side', side, 'points', pts, 'stats', stats);
    end if;
    s := jsonb_set(s, '{fired}', (s->'fired') || to_jsonb(me));
    s := s || jsonb_build_object('shots_left', (s->>'shots_left')::int - 1, 'cover', false);
    if (s->>'shots_left')::int > 0 then
        other_member := (select v from jsonb_array_elements_text(s->'teams'->side) v where v <> me limit 1);
        return s || jsonb_build_object('turn', coalesce(other_member, me), 'deadline', public._tg_deadline((s->>'secs')::int));
    end if;
    return s || jsonb_build_object('shooting', target, 'turn', s->'teams'->target->>0, 'fired', '[]'::jsonb,
        'shots_left', case when s->>'format' = '2v2' then 2 else 1 end, 'deadline', public._tg_deadline((s->>'secs')::int));
end $$;

create or replace function public._g_amiral_batti_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare side int; me text; c text; target int; x int; y int; size int := (s->>'size')::int;
begin
    if s->>'phase' = 'placement' then
        -- placement time is over: random fleets for whoever has not placed
        for side in 0..1 loop
            if s->'fleets'->side = 'null'::jsonb then
                s := public._g_amiral_batti_act(s, (s->'teams'->side->>0)::uuid, 'place', '{"random":true}');
            end if;
            exit when s->>'phase' <> 'placement';
        end loop;
        return s;
    end if;
    me := s->>'turn';
    s := public._tg_timeout_count(s, me::uuid);
    s := s || jsonb_build_object('cover', true);
    target := 1 - (s->>'shooting')::int;
    loop
        x := floor(random() * size)::int; y := floor(random() * size)::int;
        exit when not (s->'shots'->target ? (x || ',' || y));
    end loop;
    return public._g_amiral_batti_act(s, me::uuid, 'fire', jsonb_build_object('x', x, 'y', y));
end $$;

create or replace function public._g_amiral_batti_public(s jsonb)
returns jsonb language sql immutable as $$
    select jsonb_build_object('phase', s->'phase', 'format', s->'format', 'size', s->'size', 'ships', s->'ships', 'teams', s->'teams',
        'placed', jsonb_build_array(s->'fleets'->0 <> 'null'::jsonb, s->'fleets'->1 <> 'null'::jsonb),
        'shots', s->'shots', 'sunk', s->'sunk', 'shooting', s->'shooting', 'shots_left', s->'shots_left', 'turn', s->'turn',
        'winner_side', s->'winner_side', 'log', coalesce(s->'log', '[]'::jsonb),
        -- after the game everybody may see both fleets
        'fleets', case when coalesce((s->>'over')::boolean, false) then s->'fleets' end);
$$;

create or replace function public._g_amiral_batti_view(s jsonb, u uuid)
returns jsonb language sql immutable as $$
    select case when s->'side_of' ? u::text then
        jsonb_build_object('side', s->'side_of'->u::text, 'fleet', s->'fleets'->((s->'side_of'->>u::text)::int)) else '{}'::jsonb end;
$$;

-- ---------------------------------------------------------------------
-- Grants: everything here is internal; the client entry points stay mode_act / get_mode_state.
-- ---------------------------------------------------------------------
do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and (p.proname like '\_tg\_%' or p.proname like '\_g\_%' or p.proname like '\_yb\_%'
                    or p.proname like '\_tv\_%' or p.proname like '\_kb\_%' or p.proname like '\_ab\_%' or p.proname like '\_opts\_%'
                    or p.proname like '\_startcheck\_%' or p.proname in ('_mz_new_round','_mz_tick','_mz_view','_mode_advance','mode_act')) loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
        if left(f.proname, 1) <> '_' then
            execute format('grant execute on function %s to authenticated', f.sig);
        end if;
    end loop;
end $$;
