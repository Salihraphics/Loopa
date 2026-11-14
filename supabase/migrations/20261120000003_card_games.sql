-- =====================================================================
-- LOOPA 0.8.0 — Renk Kartları (UNO-style colour card game with LOOPA cards)
-- Classic rules + stacking of +2 / +4, and four LOOPA cards:
--   TEMİZLE  choose a colour: every card of that colour in your hand is discarded at once
--   AYNA     only against a pending +2/+4 stack: the whole stack goes to the player who
--            played the first + card of that stack; play continues after the AYNA player
--   GAMBLE   choose a colour: the next player draws 4 - unless they hold that colour,
--            then the 4 cards go to the GAMBLE player (even with fewer than 4 cards in hand)
--   ELİNİ GÖRELİM  the next player's hand is shown to you (only to you); nothing is taken
-- A hand may not be finished with WILD or WILD+4. Saying "UNO": play your second-to-last card
-- with {"uno":true}; otherwise any other player may "catch" you before the next move (+2 cards).
-- Round winner scores the cards left in the other hands (numbers face value, action 20, black 50).
-- Match: the number of rounds chosen by the host; most points wins.
-- =====================================================================

insert into public.game_modes(id, name_tr, name_en, description_tr, status, always_free, min_players, max_players, sort_order, tags) values
 ('renk_kartlari', 'Renk Kartları', 'Colour Cards', 'Rengi ya da sayıyı eşle, elini ilk sen bitir. TEMİZLE, AYNA, GAMBLE ve ELİNİ GÖRELİM kartlarıyla.',
  'available', false, 2, 8, 150, '{card,ffa}')
on conflict (id) do nothing;

create or replace function public._opts_renk_kartlari(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
begin
    return jsonb_build_object(
        'rounds', case when (o->>'rounds')::int in (1, 3, 5) then (o->>'rounds')::int else 3 end,
        'stacking', coalesce((o->>'stacking')::boolean, true),
        'loopa_cards', coalesce((o->>'loopa_cards')::boolean, true),
        'turn_seconds', case when (o->>'turn_seconds')::int in (15, 20, 30) then (o->>'turn_seconds')::int else 20 end);
end $$;

-- Card: {"id":"r7a","c":"r|y|g|b|k","v":"0..9|skip|rev|d2|wild|d4|temizle|ayna|gamble|goster"}
create or replace function public._rk_deck(p_loopa boolean)
returns jsonb language plpgsql volatile as $$
declare d jsonb := '[]'::jsonb; c text; v text; k int;
begin
    foreach c in array array['r','y','g','b'] loop
        d := d || jsonb_build_array(jsonb_build_object('id', c || '0', 'c', c, 'v', '0'));
        foreach v in array array['1','2','3','4','5','6','7','8','9','skip','rev','d2'] loop
            for k in 1..2 loop d := d || jsonb_build_array(jsonb_build_object('id', c || v || k, 'c', c, 'v', v)); end loop;
        end loop;
    end loop;
    for k in 1..4 loop
        d := d || jsonb_build_array(jsonb_build_object('id', 'wild' || k, 'c', 'k', 'v', 'wild'), jsonb_build_object('id', 'd4' || k, 'c', 'k', 'v', 'd4'));
    end loop;
    if p_loopa then
        foreach v in array array['temizle','ayna','gamble','goster'] loop
            for k in 1..2 loop d := d || jsonb_build_array(jsonb_build_object('id', v || k, 'c', 'k', 'v', v)); end loop;
        end loop;
    end if;
    return public._tg_shuffle(d);
end $$;

create or replace function public._rk_points(p_hand jsonb)
returns int language sql immutable as $$
    select coalesce(sum(case when c->>'v' ~ '^[0-9]$' then (c->>'v')::int when c->>'c' = 'k' then 50 else 20 end), 0)::int
      from jsonb_array_elements(coalesce(p_hand, '[]'::jsonb)) c;
$$;

-- Draws n cards for u (reshuffles the discard pile when the deck runs out).
create or replace function public._rk_draw(s jsonb, u text, n int)
returns jsonb language plpgsql volatile as $$
declare i int; top jsonb;
begin
    for i in 1..n loop
        if jsonb_array_length(s->'deck') = 0 then
            top := s->'discard'->-1;
            exit when jsonb_array_length(s->'discard') <= 1;
            s := jsonb_set(s, '{deck}', public._tg_shuffle((s->'discard') - -1));
            s := jsonb_set(s, '{discard}', jsonb_build_array(top));
        end if;
        s := jsonb_set(s, array['hands', u], (s->'hands'->u) || jsonb_build_array(s->'deck'->0));
        s := jsonb_set(s, '{deck}', (s->'deck') - 0);
    end loop;
    return s;
end $$;

create or replace function public._rk_next(s jsonb, p_from text, p_steps int default 1)
returns text language plpgsql immutable as $$
declare ord jsonb := s->'order'; n int := jsonb_array_length(s->'order'); i int; idx int := 0; dir int := (s->>'dir')::int;
begin
    for i in 0..n - 1 loop if ord->>i = p_from then idx := i; end if; end loop;
    return ord->>(((idx + dir * p_steps) % n + n) % n);
end $$;

create or replace function public._rk_playable(s jsonb, card jsonb)
returns boolean language plpgsql immutable as $$
declare top jsonb := s->'discard'->-1; pend int := coalesce((s->>'pending')::int, 0);
begin
    if pend > 0 then
        -- facing a + stack: only another + card (if stacking) or AYNA
        return card->>'v' = 'ayna' or (coalesce((s->>'stacking')::boolean, true) and card->>'v' in ('d2','d4'));
    end if;
    if card->>'v' = 'ayna' then return false; end if;
    if card->>'c' = 'k' then return true; end if;
    return card->>'c' = s->>'color' or card->>'v' = top->>'v';
end $$;

create or replace function public._g_renk_kartlari_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare rounds int := coalesce((public._tg_opt(m.id, 'rounds'))::int, 3); deck jsonb; hands jsonb := '{}'::jsonb; i int; k int;
        top jsonb; s jsonb; first int; secs int := public._tg_secs(m.id, 20);
begin
    if n > rounds or array_length(players, 1) < 2 then return null; end if;
    deck := public._rk_deck(coalesce((public._tg_opt(m.id, 'loopa_cards'))::boolean, true));
    for i in 1..array_length(players, 1) loop
        hands := hands || jsonb_build_object(players[i]::text, (select jsonb_agg(c) from (select c from jsonb_array_elements(deck) with ordinality t(c, o) where o > (i - 1) * 7 and o <= i * 7) x));
    end loop;
    deck := (select coalesce(jsonb_agg(c order by o), '[]'::jsonb) from jsonb_array_elements(deck) with ordinality t(c, o) where o > array_length(players, 1) * 7);
    -- first discard: a number card
    k := 0;
    loop
        top := deck->k;
        exit when top->>'v' ~ '^[0-9]$';
        k := k + 1;
    end loop;
    deck := deck - k;
    first := ((n - 1) % array_length(players, 1)) + 1;          -- the starter rotates every round
    s := jsonb_build_object('phase', 'play', 'round', n, 'rounds', rounds, 'order', to_jsonb(players), 'dir', 1, 'hands', hands,
        'deck', deck, 'discard', jsonb_build_array(top), 'color', top->'c', 'pending', 0, 'stack_by', null,
        'stacking', coalesce((public._tg_opt(m.id, 'stacking'))::boolean, true),
        'turn', players[first], 'drawn', null, 'secs', secs, 'deadline', public._tg_deadline(secs), 'timeouts', '{}'::jsonb,
        'uno_open', null, 'seen', '{}'::jsonb);
    return s;
end $$;

create or replace function public._rk_end_turn(s jsonb, p_next text)
returns jsonb language plpgsql volatile as $$
begin
    return s || jsonb_build_object('turn', p_next, 'drawn', null, 'deadline', public._tg_deadline((s->>'secs')::int));
end $$;

create or replace function public._rk_round_over(s jsonb, w text)
returns jsonb language plpgsql immutable as $$
declare pts int := 0; k text; stats jsonb := '{}'::jsonb;
begin
    for k in select jsonb_object_keys(s->'hands') loop
        if k <> w then pts := pts + public._rk_points(s->'hands'->k); end if;
        stats := stats || jsonb_build_object(k, jsonb_build_object('rounds', 1, 'rounds_won', case when k = w then 1 else 0 end));
    end loop;
    return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'winner', w, 'round_points', pts,
                                   'points', jsonb_build_object(w, pts), 'stats', stats);
end $$;

-- kinds: play {"card":id, "color":"r|y|g|b", "uno":bool}, draw, pass (after drawing), catch {"target":uid}
create or replace function public._g_renk_kartlari_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; hand jsonb; card jsonb; idx int; i int; v text; col text := p->>'color'; nxt text; first_plus text;
        pend int; nhand int; tgt text; keep jsonb;
begin
    -- catching a missed "UNO" is possible for anybody until the next move
    if p_kind = 'catch' then
        tgt := p->>'target';
        if s->>'uno_open' is distinct from tgt or tgt = me then raise exception 'LOOPA:INVALID_ACTION'; end if;
        s := public._rk_draw(s, tgt, 2) || jsonb_build_object('uno_open', null);
        return public._tg_log(s, jsonb_build_object('u', me, 'caught', tgt));
    end if;
    if s->>'turn' is distinct from me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    s := s || jsonb_build_object('uno_open', null);
    hand := s->'hands'->me;
    pend := coalesce((s->>'pending')::int, 0);

    if p_kind = 'draw' then
        if s->>'drawn' is not null then raise exception 'LOOPA:INVALID_ACTION'; end if;
        if pend > 0 then
            -- take the whole stack, turn over
            s := public._rk_draw(s, me, pend) || jsonb_build_object('pending', 0, 'stack_by', null);
            s := public._tg_log(s, jsonb_build_object('u', me, 'drew', pend));
            return public._rk_end_turn(s, public._rk_next(s, me));
        end if;
        s := public._rk_draw(s, me, 1);
        s := public._tg_log(s, jsonb_build_object('u', me, 'drew', 1));
        card := s->'hands'->me->-1;
        if public._rk_playable(s, card) then
            return s || jsonb_build_object('drawn', card->>'id', 'deadline', public._tg_deadline((s->>'secs')::int));
        end if;
        return public._rk_end_turn(s, public._rk_next(s, me));
    end if;
    if p_kind = 'pass' then
        if s->>'drawn' is null then raise exception 'LOOPA:MUST_MOVE'; end if;
        return public._rk_end_turn(s, public._rk_next(s, me));
    end if;
    if p_kind <> 'play' then raise exception 'LOOPA:INVALID_ACTION'; end if;

    idx := null;
    for i in 0..jsonb_array_length(hand) - 1 loop if hand->i->>'id' = p->>'card' then idx := i; end if; end loop;
    if idx is null then raise exception 'LOOPA:INVALID_MOVE'; end if;
    card := hand->idx; v := card->>'v';
    if s->>'drawn' is not null and s->>'drawn' <> card->>'id' then raise exception 'LOOPA:INVALID_MOVE'; end if;   -- after drawing: only that card
    if not public._rk_playable(s, card) then raise exception 'LOOPA:INVALID_MOVE'; end if;
    if card->>'c' = 'k' and v <> 'ayna' and (col is null or col not in ('r','y','g','b')) then raise exception 'LOOPA:CHOOSE_COLOR'; end if;
    if jsonb_array_length(hand) = 1 and v in ('wild','d4') then raise exception 'LOOPA:CANNOT_FINISH_WITH_WILD'; end if;

    hand := hand - idx;
    s := jsonb_set(s, array['hands', me], hand);
    s := jsonb_set(s, '{discard}', (s->'discard') || jsonb_build_array(card));
    -- AYNA keeps the current colour; other black cards set the chosen one; coloured cards their own
    s := s || jsonb_build_object('drawn', null, 'color', case when v = 'ayna' then s->>'color' when card->>'c' = 'k' then col else card->>'c' end);
    s := public._tg_log(s, jsonb_build_object('u', me, 'card', card, 'color', s->>'color'));

    nxt := public._rk_next(s, me);
    if v = 'skip' then nxt := public._rk_next(s, me, 2);
    elsif v = 'rev' then
        s := s || jsonb_build_object('dir', -(s->>'dir')::int);
        nxt := case when jsonb_array_length(s->'order') = 2 then me else public._rk_next(s, me) end;
    elsif v in ('d2','d4') then
        s := s || jsonb_build_object('pending', pend + case when v = 'd2' then 2 else 4 end,
                                     'stack_by', coalesce(s->>'stack_by', me));
        if not coalesce((s->>'stacking')::boolean, true) then
            -- no stacking: the next player draws at once and is skipped
            s := public._rk_draw(s, nxt, (s->>'pending')::int) || jsonb_build_object('pending', 0, 'stack_by', null);
            nxt := public._rk_next(s, nxt);
        end if;
    elsif v = 'ayna' then
        first_plus := s->>'stack_by';
        s := public._rk_draw(s, first_plus, pend) || jsonb_build_object('pending', 0, 'stack_by', null);
        s := public._tg_log(s, jsonb_build_object('u', me, 'mirror', first_plus, 'cards', pend));
    elsif v = 'temizle' then
        keep := (select coalesce(jsonb_agg(c), '[]'::jsonb) from jsonb_array_elements(s->'hands'->me) c where c->>'c' <> col);
        -- the cleared cards go under the TEMİZLE card, which stays on top
        s := jsonb_set(s, '{discard}', ((s->'discard') - -1)
                                       || (select coalesce(jsonb_agg(c), '[]'::jsonb) from jsonb_array_elements(s->'hands'->me) c where c->>'c' = col)
                                       || jsonb_build_array(card));
        s := jsonb_set(s, array['hands', me], keep);
        s := public._tg_log(s, jsonb_build_object('u', me, 'cleared', col));
    elsif v = 'gamble' then
        if exists (select 1 from jsonb_array_elements(s->'hands'->nxt) c where c->>'c' = col) then
            s := public._rk_draw(s, me, 4);
            s := public._tg_log(s, jsonb_build_object('u', me, 'gamble', 'lost', 'target', nxt));
        else
            s := public._rk_draw(s, nxt, 4);
            s := public._tg_log(s, jsonb_build_object('u', me, 'gamble', 'won', 'target', nxt));
        end if;
    elsif v = 'goster' then
        -- only the player of the card may look; recorded for this turn
        s := jsonb_set(s, array['seen', me], jsonb_build_object('of', nxt, 'hand', s->'hands'->nxt));
    end if;

    nhand := jsonb_array_length(s->'hands'->me);
    if nhand = 0 then return public._rk_round_over(s, me); end if;
    if nhand = 1 and not coalesce((p->>'uno')::boolean, false) then s := s || jsonb_build_object('uno_open', me); end if;
    if nhand = 1 and coalesce((p->>'uno')::boolean, false) then s := public._tg_log(s, jsonb_build_object('u', me, 'uno', true)); end if;
    -- a shown hand is only valid for the turn it was played in
    if v <> 'goster' then s := jsonb_set(s, array['seen'], (s->'seen') - me); end if;
    return public._rk_end_turn(s, nxt);
end $$;

create or replace function public._g_renk_kartlari_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := s->>'turn';
begin
    if me is null then return s; end if;
    s := public._tg_timeout_count(s, me::uuid);
    if s->>'drawn' is not null then return public._g_renk_kartlari_act(s, me::uuid, 'pass', '{}'); end if;
    s := public._g_renk_kartlari_act(s, me::uuid, 'draw', '{}');
    if s->>'drawn' is not null and s->>'turn' = me then s := public._g_renk_kartlari_act(s, me::uuid, 'pass', '{}'); end if;
    return s;
end $$;

create or replace function public._g_renk_kartlari_public(s jsonb)
returns jsonb language sql immutable as $$
    select jsonb_build_object('round', s->'round', 'rounds', s->'rounds', 'order', s->'order', 'dir', s->'dir', 'turn', s->'turn',
        'top', s->'discard'->-1, 'color', s->'color', 'pending', s->'pending', 'deck_count', jsonb_array_length(s->'deck'),
        'counts', (select jsonb_object_agg(k, jsonb_array_length(v)) from jsonb_each(s->'hands') x(k, v)),
        'uno_open', s->'uno_open', 'winner', s->'winner', 'round_points', s->'round_points', 'log', coalesce(s->'log', '[]'::jsonb),
        'hands', case when coalesce((s->>'over')::boolean, false) then s->'hands' end);
$$;

create or replace function public._g_renk_kartlari_view(s jsonb, u uuid)
returns jsonb language plpgsql immutable as $$
begin
    if not (s->'hands' ? u::text) then return '{}'::jsonb; end if;
    return jsonb_build_object('hand', s->'hands'->u::text, 'drawn', case when s->>'turn' = u::text then s->'drawn' end,
        'playable', (select coalesce(jsonb_agg(c->'id'), '[]'::jsonb) from jsonb_array_elements(s->'hands'->u::text) c
                      where s->>'turn' = u::text and public._rk_playable(s, c) and (s->>'drawn' is null or s->>'drawn' = c->>'id')),
        'seen', s->'seen'->u::text);
end $$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_rk\_%' or p.proname like '\_g\_renk\_kartlari\_%' or p.proname = '_opts_renk_kartlari') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;
