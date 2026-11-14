-- =====================================================================
-- LOOPA 0.8.0 — Milyoner (property trading board game, 2-6 players)
-- Original LOOPA board (Turkish places), prices, rents and cards. No real money anywhere;
-- the game money ("₺" on the board) exists only inside one match.
--
-- Turn: roll two dice -> move (+200 when passing Başlangıç) -> resolve the square:
--   free property: buy or decline | owned by someone else: rent | tax | Şans / Kader card |
--   "Nezarete git" -> jail. Doubles roll again; third double in a row -> jail.
-- Jail: pay 50 or try for doubles (third failed try: pay 50 and move).
-- Own turn: build houses / hotel on complete colour sets (evenly), sell buildings (half),
-- mortgage / redeem (half price, +10% to redeem). No rent on mortgaged squares.
-- Can't pay: buildings are sold and properties mortgaged automatically; if still short the
-- player is bankrupt (assets to the creditor, or back to the bank).
-- End: last player standing, or the round limit -> highest net worth.
-- =====================================================================

insert into public.game_modes(id, name_tr, name_en, description_tr, status, always_free, min_players, max_players, sort_order, tags) values
 ('milyoner', 'Milyoner', 'Millionaire', 'Zar at, mülk al, kira topla, ev ve otel dik. Son ayakta kalan kazanır.', 'available', false, 2, 6, 170, '{board,strategy,ffa}')
on conflict (id) do nothing;

create or replace function public._opts_milyoner(o jsonb, n int)
returns jsonb language plpgsql immutable as $$
begin
    return jsonb_build_object('max_rounds', case when (o->>'max_rounds')::int in (20, 30, 50) then (o->>'max_rounds')::int else 30 end,
                              'turn_seconds', case when (o->>'turn_seconds')::int in (20, 30, 45) then (o->>'turn_seconds')::int else 30 end);
end $$;

-- Board: [{"t":type,"n":name,"g":group,"p":price,"h":house cost}]
create or replace function public._my_board()
returns jsonb language sql immutable as $$
select '[
 {"t":"start","n":"Başlangıç"},
 {"t":"prop","n":"Kasaba Yolu","g":1,"p":60,"h":50},
 {"t":"kader","n":"Kader"},
 {"t":"prop","n":"Köy Meydanı","g":1,"p":60,"h":50},
 {"t":"tax","n":"Gelir Vergisi","a":200},
 {"t":"station","n":"Haydarpaşa Garı","p":200},
 {"t":"prop","n":"Eskişehir Caddesi","g":2,"p":100,"h":50},
 {"t":"sans","n":"Şans"},
 {"t":"prop","n":"Bursa Caddesi","g":2,"p":100,"h":50},
 {"t":"prop","n":"Konya Caddesi","g":2,"p":120,"h":50},
 {"t":"jail","n":"Nezarethane"},
 {"t":"prop","n":"Ege Sokağı","g":3,"p":140,"h":100},
 {"t":"utility","n":"Elektrik İdaresi","p":150},
 {"t":"prop","n":"İzmir Kordon","g":3,"p":140,"h":100},
 {"t":"prop","n":"Bodrum Limanı","g":3,"p":160,"h":100},
 {"t":"station","n":"Kadıköy İskelesi","p":200},
 {"t":"prop","n":"Kapadokya","g":4,"p":180,"h":100},
 {"t":"kader","n":"Kader"},
 {"t":"prop","n":"Trabzon Sahili","g":4,"p":180,"h":100},
 {"t":"prop","n":"Rize Yaylası","g":4,"p":200,"h":100},
 {"t":"free","n":"Mola"},
 {"t":"prop","n":"Antalya Bulvarı","g":5,"p":220,"h":150},
 {"t":"sans","n":"Şans"},
 {"t":"prop","n":"Alanya Kalesi","g":5,"p":220,"h":150},
 {"t":"prop","n":"Fethiye Koyu","g":5,"p":240,"h":150},
 {"t":"station","n":"Sirkeci Garı","p":200},
 {"t":"prop","n":"Kızılay","g":6,"p":260,"h":150},
 {"t":"prop","n":"Çankaya","g":6,"p":260,"h":150},
 {"t":"utility","n":"Su İdaresi","p":150},
 {"t":"prop","n":"Ulus Meydanı","g":6,"p":280,"h":150},
 {"t":"gojail","n":"Nezarete Git"},
 {"t":"prop","n":"Beşiktaş","g":7,"p":300,"h":200},
 {"t":"prop","n":"Kadıköy","g":7,"p":300,"h":200},
 {"t":"kader","n":"Kader"},
 {"t":"prop","n":"Üsküdar","g":7,"p":320,"h":200},
 {"t":"station","n":"Karaköy İskelesi","p":200},
 {"t":"sans","n":"Şans"},
 {"t":"prop","n":"Boğaz Tepesi","g":8,"p":350,"h":200},
 {"t":"tax","n":"Lüks Vergisi","a":100},
 {"t":"prop","n":"Sarayburnu","g":8,"p":400,"h":200}
]'::jsonb;
$$;

-- LOOPA's own rent curve: base 1/10 of the price; buildings x5, x14, x36, x50, hotel x62 (rounded to 5)
create or replace function public._my_rent(p_price int, p_houses int, p_full_set boolean)
returns int language sql immutable as $$
    select case when p_houses = 0 then (p_price / 10) * case when p_full_set then 2 else 1 end
                else (round((p_price / 10.0) * (array[5, 14, 36, 50, 62])[p_houses] / 5) * 5)::int end;
$$;

create or replace function public._my_cards(p_kind text)
returns jsonb language sql immutable as $$
    select case when p_kind = 'sans' then '[
        {"t":"Başlangıça ilerle, 200 al.","a":"goto","to":0},
        {"t":"Kızılay''a git. Başlangıçtan geçersen 200 al.","a":"goto","to":26},
        {"t":"Üç kare geri git.","a":"back","n":3},
        {"t":"Nezarete git. Başlangıçtan geçme.","a":"jail"},
        {"t":"Hız cezası: 15 öde.","a":"pay","n":15},
        {"t":"Kitap fuarında ödül: 150 al.","a":"get","n":150},
        {"t":"Nezaretten çıkış kartı.","a":"free"},
        {"t":"Her oyuncuya 50 öde, sen kafayı ısmarla.","a":"pay_each","n":50},
        {"t":"Bina bakımı: ev başına 25, otel başına 100 öde.","a":"repairs","h":25,"o":100},
        {"t":"En yakın gara ilerle.","a":"station"}
      ]'::jsonb else '[
        {"t":"Banka hatası lehine: 200 al.","a":"get","n":200},
        {"t":"Doktor faturası: 50 öde.","a":"pay","n":50},
        {"t":"Doğum günün: her oyuncudan 10 al.","a":"get_each","n":10},
        {"t":"Vergi iadesi: 20 al.","a":"get","n":20},
        {"t":"Nezarete git. Başlangıçtan geçme.","a":"jail"},
        {"t":"Nezaretten çıkış kartı.","a":"free"},
        {"t":"Sigorta primi: 100 öde.","a":"pay","n":100},
        {"t":"Miras: 100 al.","a":"get","n":100},
        {"t":"Okul taksidi: 50 öde.","a":"pay","n":50},
        {"t":"Başlangıça ilerle, 200 al.","a":"goto","to":0}
      ]'::jsonb end;
$$;

create or replace function public._g_milyoner_new(m public.matches, players uuid[], n int)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare st jsonb := '{}'::jsonb; i int; secs int := public._tg_secs(m.id, 30);
begin
    if n > 1 or array_length(players, 1) < 2 then return null; end if;
    for i in 1..array_length(players, 1) loop
        st := st || jsonb_build_object(players[i]::text, jsonb_build_object('cash', 1500, 'pos', 0, 'jail', 0, 'free', 0, 'out', false));
    end loop;
    return jsonb_build_object('phase', 'play', 'order', to_jsonb(players), 'players', st, 'owner', '{}'::jsonb, 'houses', '{}'::jsonb,
        'mortgaged', '[]'::jsonb, 'turn', players[1], 'stage', 'roll', 'doubles', 0, 'round', 1,
        'max_rounds', coalesce((public._tg_opt(m.id, 'max_rounds'))::int, 30), 'out_order', '[]'::jsonb,
        'sans', public._tg_shuffle(public._my_cards('sans')), 'kader', public._tg_shuffle(public._my_cards('kader')),
        'secs', secs, 'deadline', public._tg_deadline(secs), 'timeouts', '{}'::jsonb);
end $$;

create or replace function public._my_cash(s jsonb, u text) returns int language sql immutable as $$ select (s->'players'->u->>'cash')::int; $$;
create or replace function public._my_set_cash(s jsonb, u text, v int) returns jsonb language sql immutable as $$
    select jsonb_set(s, array['players', u, 'cash'], to_jsonb(v));
$$;

create or replace function public._my_group_full(s jsonb, g int, u text)
returns boolean language plpgsql immutable as $$
declare b jsonb := public._my_board(); i int;
begin
    for i in 0..39 loop
        if (b->i->>'g')::int = g and s->'owner'->>i::text is distinct from u then return false; end if;
    end loop;
    return true;
end $$;

create or replace function public._my_rent_at(s jsonb, sq int, dice int)
returns int language plpgsql immutable as $$
declare b jsonb := public._my_board()->sq; own text := s->'owner'->>sq::text; n int; i int;
begin
    if own is null or s->'mortgaged' ? sq::text then return 0; end if;
    if b->>'t' = 'prop' then
        return public._my_rent((b->>'p')::int, coalesce((s->'houses'->>sq::text)::int, 0), public._my_group_full(s, (b->>'g')::int, own));
    elsif b->>'t' = 'station' then
        n := 0;
        for i in 0..39 loop if public._my_board()->i->>'t' = 'station' and s->'owner'->>i::text = own then n := n + 1; end if; end loop;
        return 25 * (1 << (n - 1));
    elsif b->>'t' = 'utility' then
        n := 0;
        for i in 0..39 loop if public._my_board()->i->>'t' = 'utility' and s->'owner'->>i::text = own then n := n + 1; end if; end loop;
        return dice * case when n = 2 then 10 else 4 end;
    end if;
    return 0;
end $$;

create or replace function public._my_net_worth(s jsonb, u text)
returns int language plpgsql immutable as $$
declare w int := public._my_cash(s, u); k text; b jsonb := public._my_board();
begin
    for k in select key from jsonb_each_text(s->'owner') where value = u loop
        w := w + case when s->'mortgaged' ? k then (b->k::int->>'p')::int / 2 else (b->k::int->>'p')::int end
               + coalesce((s->'houses'->>k)::int, 0) * coalesce((b->k::int->>'h')::int, 0) / 2;
    end loop;
    return w;
end $$;

-- Raises cash by selling buildings, then mortgaging (cheapest first). Returns the new state.
create or replace function public._my_liquidate(s jsonb, u text, need int)
returns jsonb language plpgsql immutable as $$
declare k text; b jsonb := public._my_board();
begin
    while public._my_cash(s, u) < need and exists (select 1 from jsonb_each_text(s->'houses') h where (h.value)::int > 0 and s->'owner'->>h.key = u) loop
        select h.key into k from jsonb_each_text(s->'houses') h where (h.value)::int > 0 and s->'owner'->>h.key = u
         order by (h.value)::int desc, h.key limit 1;
        s := jsonb_set(s, array['houses', k], to_jsonb((s->'houses'->>k)::int - 1));
        s := public._my_set_cash(s, u, public._my_cash(s, u) + (b->k::int->>'h')::int / 2);
    end loop;
    while public._my_cash(s, u) < need and exists (select 1 from jsonb_each_text(s->'owner') o where o.value = u and not (s->'mortgaged' ? o.key)) loop
        select o.key into k from jsonb_each_text(s->'owner') o where o.value = u and not (s->'mortgaged' ? o.key)
         order by (b->o.key::int->>'p')::int, o.key limit 1;
        s := jsonb_set(s, '{mortgaged}', (s->'mortgaged') || to_jsonb(k));
        s := public._my_set_cash(s, u, public._my_cash(s, u) + (b->k::int->>'p')::int / 2);
    end loop;
    return s;
end $$;

-- u pays amount to creditor (null = bank); bankrupt when it cannot be raised.
create or replace function public._my_pay(s jsonb, u text, amount int, creditor text)
returns jsonb language plpgsql immutable as $$
declare k text; paid int;
begin
    if amount <= 0 then return s; end if;
    if public._my_cash(s, u) < amount then s := public._my_liquidate(s, u, amount); end if;
    if public._my_cash(s, u) >= amount then
        s := public._my_set_cash(s, u, public._my_cash(s, u) - amount);
        if creditor is not null then s := public._my_set_cash(s, creditor, public._my_cash(s, creditor) + amount); end if;
        return s;
    end if;
    -- bankrupt: everything left goes to the creditor (or back to the bank)
    paid := public._my_cash(s, u);
    if creditor is not null then s := public._my_set_cash(s, creditor, public._my_cash(s, creditor) + paid); end if;
    s := public._my_set_cash(s, u, 0);
    for k in select key from jsonb_each_text(s->'owner') where value = u loop
        s := jsonb_set(s, array['houses', k], '0');
        if creditor is not null then
            s := jsonb_set(s, array['owner', k], to_jsonb(creditor));
        else
            s := jsonb_set(s, '{owner}', (s->'owner') - k);
            s := jsonb_set(s, '{mortgaged}', (select coalesce(jsonb_agg(x), '[]'::jsonb) from jsonb_array_elements_text(s->'mortgaged') x where x <> k));
        end if;
    end loop;
    s := jsonb_set(s, array['players', u, 'out'], 'true');
    s := jsonb_set(s, '{out_order}', (s->'out_order') || to_jsonb(u));
    return public._tg_log(s, jsonb_build_object('u', u, 'bankrupt', true, 'to', creditor));
end $$;

create or replace function public._my_alive(s jsonb)
returns text[] language sql immutable as $$
    select array(select v from jsonb_array_elements_text(s->'order') v where not (s->'players'->v->>'out')::boolean);
$$;

create or replace function public._my_finish(s jsonb)
returns jsonb language plpgsql immutable as $$
declare pts jsonb := '{}'::jsonb; stats jsonb := '{}'::jsonb; u text; rank int := 0; x record; n int := jsonb_array_length(s->'order');
begin
    -- survivors by net worth, then the bankrupt in reverse order of going out
    for x in select v as u, public._my_net_worth(s, v) as w, 0 as o from jsonb_array_elements_text(s->'order') v where not (s->'players'->v->>'out')::boolean
             union all
             select v, -1, ord from jsonb_array_elements_text(s->'out_order') with ordinality t(v, ord)
             order by 2 desc, 3 desc loop
        rank := rank + 1;
        pts := pts || jsonb_build_object(x.u, (n - rank + 1) * 100);
        stats := stats || jsonb_build_object(x.u, jsonb_build_object('games', 1, 'wins', case when rank = 1 then 1 else 0 end,
            'max_net_worth', greatest(x.w, 0), 'bankruptcies', case when s->'out_order' ? x.u then 1 else 0 end,
            'properties', (select count(*) from jsonb_each_text(s->'owner') o where o.value = x.u)));
        if rank = 1 then s := s || jsonb_build_object('winner', x.u); end if;
    end loop;
    return s || jsonb_build_object('over', true, 'phase', 'done', 'turn', null, 'points', pts, 'stats', stats,
        'worth', (select jsonb_object_agg(v, public._my_net_worth(s, v)) from jsonb_array_elements_text(s->'order') v));
end $$;

create or replace function public._my_next_turn(s jsonb)
returns jsonb language plpgsql volatile as $$
declare me text := s->>'turn'; nxt text; alive text[] := public._my_alive(s);
begin
    if array_length(alive, 1) <= 1 then return public._my_finish(s); end if;
    nxt := public._tg_next_player(s->'order', me::uuid, (select coalesce(jsonb_agg(v), '[]'::jsonb) from jsonb_array_elements_text(s->'order') v where (s->'players'->v->>'out')::boolean))::text;
    -- a new round starts when play passes the first living player again
    if (select array_position(alive, nxt)) <= (select array_position(alive, me)) or array_position(alive, me) is null then
        s := s || jsonb_build_object('round', (s->>'round')::int + 1);
        if (s->>'round')::int > (s->>'max_rounds')::int then return public._my_finish(s); end if;
    end if;
    return s || jsonb_build_object('turn', nxt, 'stage', 'roll', 'doubles', 0, 'offer', null, 'deadline', public._tg_deadline((s->>'secs')::int));
end $$;

-- Moves to square `sq` (passing Başlangıç pays 200 unless `no_go`) and resolves it.
create or replace function public._my_land(s jsonb, me text, sq int, dice int, passed_go boolean)
returns jsonb language plpgsql volatile as $$
declare b jsonb := public._my_board()->sq; own text; rent int; card jsonb; deck text; n int; k text; i int;
begin
    if passed_go then s := public._my_set_cash(s, me, public._my_cash(s, me) + 200); end if;
    s := jsonb_set(s, array['players', me, 'pos'], to_jsonb(sq));
    s := public._tg_log(s, jsonb_build_object('u', me, 'to', sq));
    if b->>'t' in ('prop','station','utility') then
        own := s->'owner'->>sq::text;
        if own is null then return s || jsonb_build_object('stage', 'buy', 'offer', sq); end if;
        if own <> me then
            rent := public._my_rent_at(s, sq, dice);
            s := public._my_pay(s, me, rent, own);
            s := public._tg_log(s, jsonb_build_object('u', me, 'rent', rent, 'to', own));
        end if;
    elsif b->>'t' = 'tax' then
        s := public._my_pay(s, me, (b->>'a')::int, null);
    elsif b->>'t' = 'gojail' then
        s := jsonb_set(jsonb_set(s, array['players', me, 'pos'], '10'), array['players', me, 'jail'], '1') || jsonb_build_object('doubles', 0, 'jailed_now', true);
    elsif b->>'t' in ('sans','kader') then
        deck := b->>'t';
        card := s->deck->0;
        s := jsonb_set(s, array[deck], ((s->deck) - 0) || case when card->>'a' = 'free' then '[]'::jsonb else jsonb_build_array(card) end);
        s := public._tg_log(s, jsonb_build_object('u', me, 'card', card->>'t'));
        case card->>'a'
        when 'goto' then return public._my_land(s, me, (card->>'to')::int, dice, (card->>'to')::int <= sq);
        when 'back' then return public._my_land(s, me, (sq - (card->>'n')::int + 40) % 40, dice, false);
        when 'jail' then
            s := jsonb_set(jsonb_set(s, array['players', me, 'pos'], '10'), array['players', me, 'jail'], '1') || jsonb_build_object('doubles', 0, 'jailed_now', true);
        when 'pay' then s := public._my_pay(s, me, (card->>'n')::int, null);
        when 'get' then s := public._my_set_cash(s, me, public._my_cash(s, me) + (card->>'n')::int);
        when 'free' then s := jsonb_set(s, array['players', me, 'free'], to_jsonb((s->'players'->me->>'free')::int + 1));
        when 'pay_each' then
            for k in select unnest(public._my_alive(s)) loop
                if k <> me then s := public._my_pay(s, me, (card->>'n')::int, k); end if;
            end loop;
        when 'get_each' then
            for k in select unnest(public._my_alive(s)) loop
                if k <> me then s := public._my_pay(s, k, (card->>'n')::int, me); end if;
            end loop;
        when 'repairs' then
            n := 0;
            for k in select key from jsonb_each_text(s->'owner') where value = me loop
                n := n + case when coalesce((s->'houses'->>k)::int, 0) = 5 then (card->>'o')::int else coalesce((s->'houses'->>k)::int, 0) * (card->>'h')::int end;
            end loop;
            s := public._my_pay(s, me, n, null);
        when 'station' then
            i := sq;
            loop i := (i + 1) % 40; exit when public._my_board()->i->>'t' = 'station'; end loop;
            return public._my_land(s, me, i, dice, i < sq);
        else null;
        end case;
    end if;
    return s;
end $$;

-- kinds: roll, buy, decline, build {sq}, sell {sq}, mortgage {sq}, redeem {sq}, pay_jail, use_free, end_turn
create or replace function public._g_milyoner_act(s jsonb, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := u::text; d1 int; d2 int; pos int; to_sq int; sq int := (p->>'sq')::int; b jsonb; g int; h int; i int; j int; cost int;
begin
    if s->>'turn' is distinct from me then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    if (s->'players'->me->>'out')::boolean then raise exception 'LOOPA:NOT_A_PARTICIPANT'; end if;
    s := s - 'jailed_now';
    if p_kind = 'roll' then
        if s->>'stage' <> 'roll' then raise exception 'LOOPA:INVALID_ACTION'; end if;
        d1 := public._tg_die(); d2 := public._tg_die();
        s := s || jsonb_build_object('dice', jsonb_build_array(d1, d2));
        s := public._tg_log(s, jsonb_build_object('u', me, 'roll', jsonb_build_array(d1, d2)));
        pos := (s->'players'->me->>'pos')::int;
        if (s->'players'->me->>'jail')::int > 0 then
            if d1 = d2 then
                s := jsonb_set(s, array['players', me, 'jail'], '0');
            elsif (s->'players'->me->>'jail')::int >= 3 then
                s := jsonb_set(s, array['players', me, 'jail'], '0');
                s := public._my_pay(s, me, 50, null);
                if (s->'players'->me->>'out')::boolean then return public._my_next_turn(s); end if;
            else
                s := jsonb_set(s, array['players', me, 'jail'], to_jsonb((s->'players'->me->>'jail')::int + 1));
                return s || jsonb_build_object('stage', 'act', 'deadline', public._tg_deadline((s->>'secs')::int));
            end if;
            s := s || jsonb_build_object('doubles', 0);
        elsif d1 = d2 then
            s := s || jsonb_build_object('doubles', (s->>'doubles')::int + 1);
            if (s->>'doubles')::int >= 3 then
                s := jsonb_set(jsonb_set(s, array['players', me, 'pos'], '10'), array['players', me, 'jail'], '1');
                s := public._tg_log(s, jsonb_build_object('u', me, 'jail', 'three_doubles'));
                return s || jsonb_build_object('stage', 'act', 'doubles', 0, 'deadline', public._tg_deadline((s->>'secs')::int));
            end if;
        else
            s := s || jsonb_build_object('doubles', 0);
        end if;
        to_sq := (pos + d1 + d2) % 40;
        s := public._my_land(s, me, to_sq, d1 + d2, pos + d1 + d2 >= 40);
        if (s->'players'->me->>'out')::boolean then return public._my_next_turn(s); end if;
        if s->>'stage' <> 'buy' then
            s := s || jsonb_build_object('stage', case when (s->>'doubles')::int > 0 and not coalesce((s->>'jailed_now')::boolean, false) then 'roll' else 'act' end);
        end if;
        return s || jsonb_build_object('deadline', public._tg_deadline((s->>'secs')::int));
    end if;
    if p_kind in ('buy', 'decline') then
        if s->>'stage' <> 'buy' then raise exception 'LOOPA:INVALID_ACTION'; end if;
        sq := (s->>'offer')::int; b := public._my_board()->sq;
        if p_kind = 'buy' then
            if public._my_cash(s, me) < (b->>'p')::int then raise exception 'LOOPA:NOT_ENOUGH_MONEY'; end if;
            s := public._my_set_cash(s, me, public._my_cash(s, me) - (b->>'p')::int);
            s := jsonb_set(s, array['owner', sq::text], to_jsonb(me));
            s := public._tg_log(s, jsonb_build_object('u', me, 'bought', sq));
        end if;
        return s || jsonb_build_object('stage', case when (s->>'doubles')::int > 0 then 'roll' else 'act' end, 'offer', null,
                                       'deadline', public._tg_deadline((s->>'secs')::int));
    end if;
    if p_kind in ('build', 'sell') then
        if s->'owner'->>sq::text is distinct from me then raise exception 'LOOPA:NOT_OWNER'; end if;
        b := public._my_board()->sq; g := (b->>'g')::int; h := coalesce((s->'houses'->>sq::text)::int, 0);
        if b->>'t' <> 'prop' then raise exception 'LOOPA:INVALID_ACTION'; end if;
        if p_kind = 'build' then
            if not public._my_group_full(s, g, me) then raise exception 'LOOPA:NEED_FULL_SET'; end if;
            if h >= 5 then raise exception 'LOOPA:INVALID_ACTION'; end if;
            for i in 0..39 loop
                if (public._my_board()->i->>'g')::int = g then
                    if s->'mortgaged' ? i::text then raise exception 'LOOPA:SET_MORTGAGED'; end if;
                    if coalesce((s->'houses'->>i::text)::int, 0) < h then raise exception 'LOOPA:BUILD_EVENLY'; end if;
                end if;
            end loop;
            cost := (b->>'h')::int;
            if public._my_cash(s, me) < cost then raise exception 'LOOPA:NOT_ENOUGH_MONEY'; end if;
            s := public._my_set_cash(s, me, public._my_cash(s, me) - cost);
            s := jsonb_set(s, array['houses', sq::text], to_jsonb(h + 1));
        else
            if h = 0 then raise exception 'LOOPA:INVALID_ACTION'; end if;
            for i in 0..39 loop
                if (public._my_board()->i->>'g')::int = g and coalesce((s->'houses'->>i::text)::int, 0) > h then raise exception 'LOOPA:BUILD_EVENLY'; end if;
            end loop;
            s := public._my_set_cash(s, me, public._my_cash(s, me) + (b->>'h')::int / 2);
            s := jsonb_set(s, array['houses', sq::text], to_jsonb(h - 1));
        end if;
        return public._tg_log(s, jsonb_build_object('u', me, p_kind, sq));
    end if;
    if p_kind in ('mortgage', 'redeem') then
        if s->'owner'->>sq::text is distinct from me then raise exception 'LOOPA:NOT_OWNER'; end if;
        b := public._my_board()->sq;
        if p_kind = 'mortgage' then
            if s->'mortgaged' ? sq::text then raise exception 'LOOPA:INVALID_ACTION'; end if;
            for i in 0..39 loop
                if b ? 'g' and (public._my_board()->i->>'g')::int = (b->>'g')::int and coalesce((s->'houses'->>i::text)::int, 0) > 0 then
                    raise exception 'LOOPA:SELL_BUILDINGS_FIRST';
                end if;
            end loop;
            s := jsonb_set(s, '{mortgaged}', (s->'mortgaged') || to_jsonb(sq::text));
            s := public._my_set_cash(s, me, public._my_cash(s, me) + (b->>'p')::int / 2);
        else
            if not (s->'mortgaged' ? sq::text) then raise exception 'LOOPA:INVALID_ACTION'; end if;
            cost := ((b->>'p')::int / 2) * 11 / 10;
            if public._my_cash(s, me) < cost then raise exception 'LOOPA:NOT_ENOUGH_MONEY'; end if;
            s := public._my_set_cash(s, me, public._my_cash(s, me) - cost);
            s := jsonb_set(s, '{mortgaged}', (select coalesce(jsonb_agg(x), '[]'::jsonb) from jsonb_array_elements_text(s->'mortgaged') x where x <> sq::text));
        end if;
        return public._tg_log(s, jsonb_build_object('u', me, p_kind, sq));
    end if;
    if p_kind in ('pay_jail', 'use_free') then
        if (s->'players'->me->>'jail')::int = 0 or s->>'stage' <> 'roll' then raise exception 'LOOPA:INVALID_ACTION'; end if;
        if p_kind = 'use_free' then
            if (s->'players'->me->>'free')::int < 1 then raise exception 'LOOPA:INVALID_ACTION'; end if;
            s := jsonb_set(s, array['players', me, 'free'], to_jsonb((s->'players'->me->>'free')::int - 1));
        else
            if public._my_cash(s, me) < 50 then raise exception 'LOOPA:NOT_ENOUGH_MONEY'; end if;
            s := public._my_set_cash(s, me, public._my_cash(s, me) - 50);
        end if;
        return jsonb_set(s, array['players', me, 'jail'], '0');
    end if;
    if p_kind = 'end_turn' then
        if s->>'stage' <> 'act' then raise exception 'LOOPA:INVALID_ACTION'; end if;
        return public._my_next_turn(s);
    end if;
    raise exception 'LOOPA:INVALID_ACTION';
end $$;

create or replace function public._g_milyoner_timeout(s jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare me text := s->>'turn'; u uuid; guard int := 0;
begin
    if me is null then return s; end if;
    u := me::uuid;
    s := public._tg_timeout_count(s, u);
    -- play the turn to its end with the most passive choices
    while s->>'turn' = me and guard < 6 and not coalesce((s->>'over')::boolean, false) loop
        guard := guard + 1;
        s := case s->>'stage' when 'roll' then public._g_milyoner_act(s, u, 'roll', '{}')
                              when 'buy' then public._g_milyoner_act(s, u, 'decline', '{}')
                              else public._g_milyoner_act(s, u, 'end_turn', '{}') end;
    end loop;
    return s;
end $$;

create or replace function public._g_milyoner_public(s jsonb)
returns jsonb language sql immutable as $$
    -- nothing is secret in Milyoner except the order of the card decks
    select jsonb_build_object('order', s->'order', 'players', s->'players', 'owner', s->'owner', 'houses', s->'houses',
        'mortgaged', s->'mortgaged', 'turn', s->'turn', 'stage', s->'stage', 'offer', s->'offer', 'dice', s->'dice',
        'round', s->'round', 'max_rounds', s->'max_rounds', 'out_order', s->'out_order', 'winner', s->'winner', 'worth', s->'worth',
        'log', coalesce(s->'log', '[]'::jsonb));
$$;

create or replace function public._g_milyoner_view(s jsonb, u uuid)
returns jsonb language sql immutable as $$
    select case when s->'players' ? u::text then jsonb_build_object('net_worth', public._my_net_worth(s, u::text)) else '{}'::jsonb end;
$$;

create or replace function public.get_milyoner_board()
returns jsonb language sql immutable as $$
    select (select jsonb_agg(sq || jsonb_build_object('i', i - 1,
               'rents', case when sq->>'t' = 'prop' then jsonb_build_array(public._my_rent((sq->>'p')::int, 0, false), public._my_rent((sq->>'p')::int, 1, true),
                   public._my_rent((sq->>'p')::int, 2, true), public._my_rent((sq->>'p')::int, 3, true), public._my_rent((sq->>'p')::int, 4, true),
                   public._my_rent((sq->>'p')::int, 5, true)) end) order by i)
              from jsonb_array_elements(public._my_board()) with ordinality t(sq, i));
$$;

do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname from pg_proc p where p.pronamespace = 'public'::regnamespace
               and (p.proname like '\_my\_%' or p.proname like '\_g\_milyoner\_%' or p.proname in ('_opts_milyoner','get_milyoner_board')) loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
        if f.proname = 'get_milyoner_board' then execute format('grant execute on function %s to authenticated', f.sig); end if;
    end loop;
end $$;
