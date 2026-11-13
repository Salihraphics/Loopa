-- =====================================================================
-- LOOPA 0.5.0 — common game-mode engine and the 9 new games
--
-- İsim Şehir keeps its own engine (rounds / answers / answer_votes) and its
-- code path is unchanged: _advance, start_match and skip_reveal only branch on
-- matches.game_mode, with İsim Şehir's original bodies kept as they were.
--
-- New games use generic tables:
--   mode_match_state  server-only state per match (+ a public part)
--   mode_rounds       one row per round / turn / step, public state + deadline
--   mode_secrets      words, answers, cards: never readable by clients
--   mode_submissions  guesses, answers, votes, statements, chain words, drawings
--   mode_strokes      live strokes of Çiz ve Tahmin Et
--   mode_content      word / question / prompt pools (seeded separately)
--   player_mode_stats per-game profile statistics
-- None of these tables has a client policy or grant: everything is read through
-- get_mode_state / get_strokes (SECURITY DEFINER, filtered per player) and written
-- through mode_act / mode_draw, which validate room, player, phase and deadline.
-- Points are computed only here; the client never sends a score.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------
create table if not exists public.mode_match_state (
    match_id  uuid primary key references public.matches(id) on delete cascade,
    state     jsonb not null default '{}'::jsonb,     -- server only
    public    jsonb not null default '{}'::jsonb      -- shown to the room
);

create table if not exists public.mode_rounds (
    id           uuid primary key default gen_random_uuid(),
    match_id     uuid not null references public.matches(id) on delete cascade,
    room_id      uuid not null references public.rooms(id) on delete cascade,
    mode         text not null references public.game_modes(id),
    round_number int  not null check (round_number >= 1),
    phase        text not null,
    actor_id     uuid references public.profiles(id) on delete set null,
    public       jsonb not null default '{}'::jsonb,
    results      jsonb,
    started_at   timestamptz not null default now(),
    deadline_at  timestamptz,
    created_at   timestamptz not null default now(),
    unique (match_id, round_number)
);
create index if not exists mode_rounds_room_idx on public.mode_rounds(room_id, created_at desc);

create table if not exists public.mode_secrets (
    round_id uuid not null references public.mode_rounds(id) on delete cascade,
    kind     text not null,
    payload  jsonb not null,
    primary key (round_id, kind)
);

create table if not exists public.mode_submissions (
    id         uuid primary key default gen_random_uuid(),
    round_id   uuid not null references public.mode_rounds(id) on delete cascade,
    match_id   uuid not null references public.matches(id) on delete cascade,
    user_id    uuid not null references public.profiles(id) on delete cascade,
    kind       text not null,
    payload    jsonb not null default '{}'::jsonb,
    correct    boolean,
    points     int not null default 0,
    created_at timestamptz not null default clock_timestamp()
);
create index if not exists mode_submissions_round_idx on public.mode_submissions(round_id, kind, created_at);
-- one answer / vote / statement set / step per player and round
create unique index if not exists mode_submissions_once_uidx on public.mode_submissions(round_id, user_id, kind)
    where kind in ('answer','vote','statements','step');

create table if not exists public.mode_strokes (
    seq        bigint generated always as identity primary key,
    round_id   uuid not null references public.mode_rounds(id) on delete cascade,
    user_id    uuid not null references public.profiles(id) on delete cascade,
    payload    jsonb not null,
    created_at timestamptz not null default now()
);
create index if not exists mode_strokes_round_idx on public.mode_strokes(round_id, seq);

create table if not exists public.mode_content (
    id       bigint generated always as identity primary key,
    kind     text not null check (kind in ('word','draw_word','charade','taboo','question','prompt','telephone')),
    category text not null default '',
    payload  jsonb not null,
    active   boolean not null default true
);
create index if not exists mode_content_kind_idx on public.mode_content(kind) where active;
create unique index if not exists mode_content_uidx on public.mode_content(kind, md5(payload::text));

-- Extra words accepted by Kelime Zinciri (besides the category dictionary).
create table if not exists public.chain_lexicon (
    match_key text primary key,
    word      text not null
);

create table if not exists public.player_mode_stats (
    user_id        uuid not null references public.profiles(id) on delete cascade,
    game_mode      text not null references public.game_modes(id),
    matches_played int not null default 0,
    wins           int not null default 0,
    total_points   bigint not null default 0,
    best_points    int not null default 0,
    last_played_at timestamptz,
    primary key (user_id, game_mode)
);

alter table public.mode_match_state  enable row level security;
alter table public.mode_rounds       enable row level security;
alter table public.mode_secrets      enable row level security;
alter table public.mode_submissions  enable row level security;
alter table public.mode_strokes      enable row level security;
alter table public.mode_content      enable row level security;
alter table public.chain_lexicon     enable row level security;
alter table public.player_mode_stats enable row level security;
drop policy if exists player_mode_stats_read on public.player_mode_stats;
create policy player_mode_stats_read on public.player_mode_stats for select to authenticated using (true);
revoke all on public.mode_match_state, public.mode_rounds, public.mode_secrets, public.mode_submissions,
              public.mode_strokes, public.mode_content, public.chain_lexicon, public.player_mode_stats
  from anon, authenticated;
grant select on public.player_mode_stats to authenticated;

-- All games are playable now; minimum players per game.
update public.game_modes set status = 'available', updated_at = now()
 where id <> 'isim_sehir' and status = 'coming_soon';
update public.game_modes set min_players = v.n from (values
    ('gizli_kelime', 3), ('kelime_zinciri', 2), ('bilgi_arenasi', 2), ('iki_dogru_bir_yalan', 3),
    ('ciz_tahmin', 3), ('en_muhtemel_kim', 3), ('anlat_bakalim', 4), ('cizim_telefonu', 3), ('sessiz_sinema', 3)) v(id, n)
 where game_modes.id = v.id;

-- ---------------------------------------------------------------------
-- Small helpers
-- ---------------------------------------------------------------------
create or replace function public._mz_state(p_match uuid)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce((select state from public.mode_match_state where match_id = p_match), '{}'::jsonb);
$$;

create or replace function public._mz_set(p_match uuid, p_key text, p_val jsonb)
returns void language sql security definer set search_path = public, pg_temp as $$
    update public.mode_match_state set state = jsonb_set(state, array[p_key], coalesce(p_val, 'null'::jsonb), true)
     where match_id = p_match;
$$;

create or replace function public._mz_pub(p_match uuid, p_key text, p_val jsonb)
returns void language sql security definer set search_path = public, pg_temp as $$
    update public.mode_match_state set public = jsonb_set(public, array[p_key], coalesce(p_val, 'null'::jsonb), true)
     where match_id = p_match;
$$;

create or replace function public._mz_active(p_room uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
    select exists (select 1 from public.room_players
                    where room_id = p_room and user_id = p_user and status = 'active' and role = 'player');
$$;

-- Participants in turn order who are still in the room.
create or replace function public._mz_players(p_match uuid)
returns uuid[] language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce(array_agg(t.u::uuid order by t.ord), '{}')
      from public.matches m,
           jsonb_array_elements_text(public._mz_state(p_match)->'order') with ordinality t(u, ord)
     where m.id = p_match and public._mz_active(m.room_id, t.u::uuid);
$$;

create or replace function public._mz_award(p_match uuid, p_user uuid, p_points int)
returns void language sql security definer set search_path = public, pg_temp as $$
    update public.match_players set total_points = total_points + p_points
     where match_id = p_match and user_id = p_user;
$$;

-- Random unused item of a content pool (falls back to used ones when the pool is exhausted).
create or replace function public._mz_pick(p_match uuid, p_kind text)
returns public.mode_content language plpgsql security definer set search_path = public, pg_temp as $$
declare used jsonb := coalesce(public._mz_state(p_match)->'used', '[]'::jsonb); c public.mode_content;
begin
    select * into c from public.mode_content
     where kind = p_kind and active and not (used @> to_jsonb(id))
     order by random() limit 1;
    if not found then
        select * into c from public.mode_content where kind = p_kind and active order by random() limit 1;
    end if;
    if not found then raise exception 'LOOPA:MODE_CONTENT_MISSING'; end if;
    perform public._mz_set(p_match, 'used', used || to_jsonb(c.id));
    return c;
end $$;

-- Does a guess name the answer? Spelling-insensitive (same keys as the dictionary).
create or replace function public._mz_matches(p_guess text, p_answer jsonb)
returns boolean language sql immutable as $$
    select public.dict_key(p_guess) <> '' and (
        public.dict_key(p_guess) = public.dict_key(p_answer->>'word')
        or exists (select 1 from jsonb_array_elements_text(coalesce(p_answer->'aliases', '[]'::jsonb)) a
                    where public.dict_key(a) = public.dict_key(p_guess)));
$$;

create or replace function public._mz_secs(p_match uuid, p_min int, p_max int)
returns int language sql stable security definer set search_path = public, pg_temp as $$
    select least(greatest(coalesce((settings->>'round_seconds')::int, 60), p_min), p_max) from public.matches where id = p_match;
$$;

create or replace function public._mz_rounds(p_match uuid)
returns int language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce((settings->>'rounds')::int, 5) from public.matches where id = p_match;
$$;

create or replace function public._mz_clean(p text, p_max int)
returns text language sql immutable as $$
    select left(regexp_replace(btrim(coalesce(p, '')), '\s+', ' ', 'g'), p_max);
$$;

-- Turkish chain letter: last letter of the spelling-insensitive key (dağ -> g, ağaç -> c).
create or replace function public._mz_last_letter(p text)
returns text language sql immutable as $$
    select right(public.dict_key(p), 1);
$$;

create or replace function public._mz_chain_known(p text)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
    select exists (select 1 from public.chain_lexicon where match_key = public.dict_key(p))
        or exists (select 1 from public.dictionary_entries e
                    where e.match_key = public.dict_key(p) and e.retired_in is null
                      and e.category_key in ('hayvan','bitki','esya','yemek','meslek','sehir','ulke'))
        or exists (select 1 from public.dictionary_aliases a join public.dictionary_entries e on e.id = a.entry_id
                    where a.match_key = public.dict_key(p) and e.retired_in is null
                      and e.category_key in ('hayvan','bitki','esya','yemek','meslek','sehir','ulke'));
$$;

-- Strokes: [{"c":0..11,"w":1..40,"e":bool,"p":[x,y,...] (0..1000)}] or {"clear":true}
create or replace function public._mz_valid_strokes(p jsonb, p_max_strokes int)
returns boolean language sql immutable as $$
    select jsonb_typeof(p) = 'array' and jsonb_array_length(p) between 0 and p_max_strokes
       and octet_length(p::text) <= 400000
       and not exists (
            select 1 from jsonb_array_elements(p) s
             where jsonb_typeof(s) <> 'object'
                or (not coalesce((s->>'clear')::boolean, false) and (
                        jsonb_typeof(s->'p') is distinct from 'array'
                     or jsonb_array_length(s->'p') > 800
                     or coalesce((s->>'c')::int, -1) not between 0 and 11
                     or coalesce((s->>'w')::int, 0) not between 1 and 40)));
$$;

create or replace function public._mz_round_win(p_round uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare rd public.mode_rounds; best int;
begin
    select * into rd from public.mode_rounds where id = p_round;
    select max(s) into best from (select sum(points) s from public.mode_submissions where round_id = p_round group by user_id) x;
    if coalesce(best, 0) <= 0 then return; end if;
    update public.match_players mp set round_wins = round_wins + 1
     where mp.match_id = rd.match_id and mp.user_id in (
        select user_id from public.mode_submissions where round_id = p_round group by user_id having sum(points) = best);
end $$;

-- ---------------------------------------------------------------------
-- Describe & guess: Gizli Kelime, Sessiz Sinema, Çiz ve Tahmin Et
-- ---------------------------------------------------------------------
create or replace function public._mz_dg_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare players uuid[] := public._mz_players(m.id); actor uuid; c public.mode_content; v uuid; secs int; w text; pub jsonb;
begin
    if n > public._mz_rounds(m.id) or coalesce(array_length(players, 1), 0) < 2 then return null; end if;
    actor := players[((n - 1) % array_length(players, 1)) + 1];
    c := public._mz_pick(m.id, case m.game_mode when 'ciz_tahmin' then 'draw_word' when 'sessiz_sinema' then 'charade' else 'word' end);
    secs := public._mz_secs(m.id, 45, 120);
    w := c.payload->>'word';
    pub := jsonb_build_object('category', c.category, 'words', array_length(regexp_split_to_array(w, '\s+'), 1));
    if m.game_mode = 'ciz_tahmin' then pub := pub || jsonb_build_object('hint', regexp_replace(w, '[^ ]', '_', 'g')); end if;
    insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, actor_id, public, started_at, deadline_at)
    values (m.id, m.room_id, m.game_mode, n, 'play', actor, pub, now() + interval '3 seconds',
            now() + interval '3 seconds' + make_interval(secs => secs))
    returning id into v;
    insert into public.mode_secrets(round_id, kind, payload) values (v, 'answer', c.payload);
    return v;
end $$;

create or replace function public._mz_dg_tick(rd public.mode_rounds)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare guessers int; solved int; ans jsonb;
begin
    if rd.phase = 'play' then
        select count(*) into guessers from unnest(public._mz_players(rd.match_id)) u where u <> rd.actor_id;
        select count(distinct user_id) into solved from public.mode_submissions where round_id = rd.id and kind = 'guess' and correct;
        if now() < rd.deadline_at and public._mz_active(rd.room_id, rd.actor_id) and guessers > 0
           and (case when rd.mode = 'sessiz_sinema' then solved = 0 else solved < guessers end) then
            return false;
        end if;
        select payload into ans from public.mode_secrets where round_id = rd.id and kind = 'answer';
        update public.mode_rounds set phase = 'reveal', deadline_at = now() + interval '6 seconds',
               results = jsonb_build_object(
                   'answer', ans->>'word',
                   'actor_left', not public._mz_active(rd.room_id, rd.actor_id),
                   'solved', coalesce((select jsonb_agg(jsonb_build_object('user_id', s.user_id, 'points', s.points) order by s.created_at)
                                        from public.mode_submissions s where s.round_id = rd.id and s.kind = 'guess' and s.correct), '[]'::jsonb),
                   'actor_points', coalesce((select sum(points) from public.mode_submissions where round_id = rd.id and kind = 'actor_bonus'), 0))
         where id = rd.id;
        return true;
    elsif rd.phase = 'reveal' then
        if now() < rd.deadline_at then return false; end if;
        perform public._mz_round_win(rd.id);
        update public.mode_rounds set phase = 'done' where id = rd.id;
        return true;
    end if;
    return false;
end $$;

create or replace function public._mz_dg_act(rd public.mode_rounds, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare txt text := public._mz_clean(p->>'text', 40); ans jsonb; ok boolean; k int; pts int := 0; frac numeric; total numeric;
begin
    if p_kind <> 'guess' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if rd.phase <> 'play' or now() > rd.deadline_at + interval '1 second' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if now() < rd.started_at then raise exception 'LOOPA:ROUND_NOT_STARTED'; end if;
    if u = rd.actor_id then raise exception 'LOOPA:ACTOR_CANNOT_GUESS'; end if;
    if txt = '' then raise exception 'LOOPA:EMPTY_GUESS'; end if;
    if exists (select 1 from public.mode_submissions where round_id = rd.id and user_id = u and kind = 'guess' and correct) then
        raise exception 'LOOPA:ALREADY_GUESSED';
    end if;
    if (select count(*) from public.mode_submissions where round_id = rd.id and user_id = u and kind = 'guess') >= 40 then
        raise exception 'LOOPA:TOO_MANY_GUESSES';
    end if;
    if rd.mode = 'sessiz_sinema' and exists (select 1 from public.mode_submissions where round_id = rd.id and kind = 'guess' and correct) then
        raise exception 'LOOPA:PHASE_CLOSED';
    end if;
    select payload into ans from public.mode_secrets where round_id = rd.id and kind = 'answer';
    ok := public._mz_matches(txt, ans);
    if ok then
        total := greatest(extract(epoch from rd.deadline_at - rd.started_at), 1);
        frac := least(greatest(extract(epoch from rd.deadline_at - now()) / total, 0), 1);
        select count(distinct user_id) into k from public.mode_submissions where round_id = rd.id and kind = 'guess' and correct;
        pts := case rd.mode
                 when 'gizli_kelime' then greatest(10, 30 - 5 * k)          -- 30, 25, 20, 15, 10 …
                 when 'ciz_tahmin'   then 10 + round(40 * frac)::int         -- speed based: 10..50
                 else 20 + round(20 * frac)::int end;                        -- sessiz_sinema: 20..40, first only
        insert into public.mode_submissions(round_id, match_id, user_id, kind, payload, correct, points)
        values (rd.id, rd.match_id, rd.actor_id, 'actor_bonus', jsonb_build_object('for', u), null,
                case rd.mode when 'gizli_kelime' then 10 when 'ciz_tahmin' then 8 else 20 end);
        perform public._mz_award(rd.match_id, rd.actor_id, case rd.mode when 'gizli_kelime' then 10 when 'ciz_tahmin' then 8 else 20 end);
        perform public._mz_award(rd.match_id, u, pts);
    end if;
    insert into public.mode_submissions(round_id, match_id, user_id, kind, payload, correct, points)
    values (rd.id, rd.match_id, u, 'guess', jsonb_build_object('text', txt), ok, pts);
    return jsonb_build_object('correct', ok, 'points', pts);
end $$;

create or replace function public._mz_dg_view(rd public.mode_rounds, u uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare ans jsonb; is_actor boolean := u = rd.actor_id;
begin
    select payload into ans from public.mode_secrets where round_id = rd.id and kind = 'answer';
    return jsonb_build_object(
        'secret', case when is_actor or rd.phase <> 'play' then ans end,
        'solved', exists (select 1 from public.mode_submissions where round_id = rd.id and user_id = u and kind = 'guess' and correct),
        'feed', coalesce((select jsonb_agg(x order by x->>'at') from (
            select jsonb_build_object('user_id', s.user_id, 'correct', s.correct, 'at', s.created_at, 'points', s.points,
                       'text', case when not s.correct or s.user_id = u or is_actor or rd.phase <> 'play' then s.payload->>'text' end) x
              from public.mode_submissions s where s.round_id = rd.id and s.kind = 'guess'
             order by s.created_at desc limit 60) q), '[]'::jsonb),
        'stroke_seq', coalesce((select max(seq) from public.mode_strokes where round_id = rd.id), 0));
end $$;

-- ---------------------------------------------------------------------
-- Anlat Bakalım (teams, taboo words, pass)
-- ---------------------------------------------------------------------
create or replace function public._mz_ab_init(m public.matches)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare ord jsonb := public._mz_state(m.id)->'order'; a jsonb := '[]'; b jsonb := '[]'; i int := 0; x text;
begin
    for x in select value from jsonb_array_elements_text(ord) loop
        if i % 2 = 0 then a := a || to_jsonb(x); else b := b || to_jsonb(x); end if;
        i := i + 1;
    end loop;
    perform public._mz_pub(m.id, 'teams', jsonb_build_array(a, b));
    perform public._mz_pub(m.id, 'team_scores', '[0,0]'::jsonb);
    perform public._mz_set(m.id, 'team_turns', '[0,0]'::jsonb);
end $$;

create or replace function public._mz_ab_team(p_match uuid, p_team int)
returns uuid[] language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce(array_agg(t.u::uuid order by t.ord), '{}')
      from public.matches m,
           jsonb_array_elements_text((select public from public.mode_match_state where match_id = p_match)->'teams'->p_team) with ordinality t(u, ord)
     where m.id = p_match and public._mz_active(m.room_id, t.u::uuid);
$$;

create or replace function public._mz_ab_user_team(p_match uuid, p_user uuid)
returns int language sql stable security definer set search_path = public, pg_temp as $$
    select case when (public->'teams'->0) ? p_user::text then 0 when (public->'teams'->1) ? p_user::text then 1 end
      from public.mode_match_state where match_id = p_match;
$$;

create or replace function public._mz_ab_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare t int := (n - 1) % 2; members uuid[]; turns jsonb := public._mz_state(m.id)->'team_turns'; narrator uuid; c public.mode_content; v uuid; secs int;
begin
    if n > public._mz_rounds(m.id) then return null; end if;
    if coalesce(array_length(public._mz_ab_team(m.id, 0), 1), 0) < 2 or coalesce(array_length(public._mz_ab_team(m.id, 1), 1), 0) < 2 then
        return null;   -- a team cannot play with fewer than two people
    end if;
    members := public._mz_ab_team(m.id, t);
    narrator := members[((turns->>t)::int % array_length(members, 1)) + 1];
    perform public._mz_set(m.id, 'team_turns', jsonb_set(turns, array[t::text], to_jsonb((turns->>t)::int + 1)));
    c := public._mz_pick(m.id, 'taboo');
    secs := public._mz_secs(m.id, 45, 120);
    insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, actor_id, public, started_at, deadline_at)
    values (m.id, m.room_id, m.game_mode, n, 'play', narrator,
            jsonb_build_object('team', t, 'passes_left', 3, 'cards_done', 0, 'turn_score', 0),
            now() + interval '3 seconds', now() + interval '3 seconds' + make_interval(secs => secs))
    returning id into v;
    insert into public.mode_secrets(round_id, kind, payload) values (v, 'card', c.payload);
    return v;
end $$;

create or replace function public._mz_ab_next_card(rd public.mode_rounds, p_result text, p_by uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare card jsonb; c public.mode_content; t int := (rd.public->>'team')::int; delta int := 0; scores jsonb; mem uuid;
begin
    select payload into card from public.mode_secrets where round_id = rd.id and kind = 'card';
    delta := case p_result when 'correct' then 1 when 'taboo' then -1 else 0 end;
    insert into public.mode_submissions(round_id, match_id, user_id, kind, payload, correct, points)
    values (rd.id, rd.match_id, p_by, 'card', jsonb_build_object('word', card->>'word', 'result', p_result), p_result = 'correct', delta * 10);
    if delta <> 0 then
        foreach mem in array public._mz_ab_team(rd.match_id, t) loop perform public._mz_award(rd.match_id, mem, delta * 10); end loop;
        select public->'team_scores' into scores from public.mode_match_state where match_id = rd.match_id;
        perform public._mz_pub(rd.match_id, 'team_scores', jsonb_set(scores, array[t::text], to_jsonb((scores->>t)::int + delta)));
    end if;
    c := public._mz_pick(rd.match_id, 'taboo');
    update public.mode_secrets set payload = c.payload where round_id = rd.id and kind = 'card';
    update public.mode_rounds set public = public
        || jsonb_build_object('cards_done', (public->>'cards_done')::int + 1, 'turn_score', (public->>'turn_score')::int + delta)
        || case when p_result = 'pass' then jsonb_build_object('passes_left', (public->>'passes_left')::int - 1) else '{}'::jsonb end
     where id = rd.id;
end $$;

create or replace function public._mz_ab_act(rd public.mode_rounds, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare t int := (rd.public->>'team')::int; my_team int := public._mz_ab_user_team(rd.match_id, u); card jsonb; ok boolean;
begin
    if rd.phase <> 'play' or now() > rd.deadline_at + interval '1 second' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if now() < rd.started_at then raise exception 'LOOPA:ROUND_NOT_STARTED'; end if;
    if p_kind = 'correct' or p_kind = 'pass' then
        if u <> rd.actor_id then raise exception 'LOOPA:NOT_ACTOR'; end if;
        if p_kind = 'pass' and (rd.public->>'passes_left')::int <= 0 then raise exception 'LOOPA:NO_PASSES_LEFT'; end if;
        perform public._mz_ab_next_card(rd, p_kind, u);
        return jsonb_build_object('result', p_kind);
    elsif p_kind = 'taboo' then
        if my_team is null or my_team = t then raise exception 'LOOPA:NOT_OPPONENT'; end if;
        perform public._mz_ab_next_card(rd, 'taboo', u);
        return jsonb_build_object('result', 'taboo');
    elsif p_kind = 'guess' then
        if my_team is distinct from t or u = rd.actor_id then raise exception 'LOOPA:NOT_GUESSER'; end if;
        select payload into card from public.mode_secrets where round_id = rd.id and kind = 'card';
        ok := public._mz_matches(public._mz_clean(p->>'text', 40), card);
        insert into public.mode_submissions(round_id, match_id, user_id, kind, payload, correct)
        values (rd.id, rd.match_id, u, 'guess', jsonb_build_object('text', public._mz_clean(p->>'text', 40)), ok);
        if ok then perform public._mz_ab_next_card(rd, 'correct', u); end if;
        return jsonb_build_object('correct', ok);
    end if;
    raise exception 'LOOPA:INVALID_ACTION';
end $$;

create or replace function public._mz_ab_tick(rd public.mode_rounds)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if rd.phase = 'play' then
        if now() < rd.deadline_at and public._mz_active(rd.room_id, rd.actor_id) then return false; end if;
        update public.mode_rounds set phase = 'reveal', deadline_at = now() + interval '6 seconds',
               results = jsonb_build_object('team', rd.public->'team', 'turn_score', rd.public->'turn_score',
                   'cards', coalesce((select jsonb_agg(s.payload order by s.created_at) from public.mode_submissions s
                                       where s.round_id = rd.id and s.kind = 'card'), '[]'::jsonb))
         where id = rd.id;
        return true;
    elsif rd.phase = 'reveal' then
        if now() < rd.deadline_at then return false; end if;
        update public.mode_rounds set phase = 'done' where id = rd.id;
        return true;
    end if;
    return false;
end $$;

create or replace function public._mz_ab_view(rd public.mode_rounds, u uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare my_team int := public._mz_ab_user_team(rd.match_id, u); t int := (rd.public->>'team')::int; sees boolean;
begin
    -- The narrator and the opposing team (who watch for forbidden words) see the card; the guessing team does not.
    sees := rd.phase = 'play' and (u = rd.actor_id or (my_team is not null and my_team <> t));
    return jsonb_build_object(
        'my_team', my_team,
        'role', case when u = rd.actor_id then 'narrator' when my_team = t then 'guesser' when my_team is not null then 'watcher' else 'spectator' end,
        'secret', case when sees then (select payload from public.mode_secrets where round_id = rd.id and kind = 'card') end,
        'feed', coalesce((select jsonb_agg(jsonb_build_object('user_id', s.user_id, 'word', s.payload->>'word', 'result', s.payload->>'result', 'at', s.created_at)
                                            order by s.created_at)
                            from public.mode_submissions s where s.round_id = rd.id and s.kind = 'card'), '[]'::jsonb));
end $$;

-- ---------------------------------------------------------------------
-- Bilgi Arenası (multiple choice, speed bonus)
-- ---------------------------------------------------------------------
create or replace function public._mz_qa_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare c public.mode_content; v uuid;
begin
    if n > public._mz_rounds(m.id) or coalesce(array_length(public._mz_players(m.id), 1), 0) < 2 then return null; end if;
    c := public._mz_pick(m.id, 'question');
    insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, public, started_at, deadline_at)
    values (m.id, m.room_id, m.game_mode, n, 'answer',
            jsonb_build_object('question', c.payload->>'q', 'options', c.payload->'options', 'category', c.category, 'seconds', 20),
            now() + interval '2 seconds', now() + interval '22 seconds')
    returning id into v;
    insert into public.mode_secrets(round_id, kind, payload)
    values (v, 'answer', jsonb_build_object('answer', (c.payload->>'answer')::int, 'explanation', c.payload->>'explanation'));
    return v;
end $$;

create or replace function public._mz_qa_act(rd public.mode_rounds, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare ch int;
begin
    if p_kind <> 'answer' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if rd.phase <> 'answer' or now() > rd.deadline_at + interval '1 second' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if now() < rd.started_at then raise exception 'LOOPA:ROUND_NOT_STARTED'; end if;
    ch := case when jsonb_typeof(p->'choice') = 'number' then (p->>'choice')::int end;
    if ch is null or ch < 0 or ch >= jsonb_array_length(rd.public->'options') then raise exception 'LOOPA:INVALID_CHOICE'; end if;
    begin
        insert into public.mode_submissions(round_id, match_id, user_id, kind, payload)
        values (rd.id, rd.match_id, u, 'answer', jsonb_build_object('choice', ch));
    exception when unique_violation then raise exception 'LOOPA:ALREADY_ANSWERED';
    end;
    return jsonb_build_object('locked', true);
end $$;

create or replace function public._mz_qa_tick(rd public.mode_rounds)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare sec jsonb; s record; pts int; total numeric; left_s numeric;
begin
    if rd.phase = 'answer' then
        if now() < rd.deadline_at and exists (select 1 from unnest(public._mz_players(rd.match_id)) p
                    where not exists (select 1 from public.mode_submissions x where x.round_id = rd.id and x.user_id = p and x.kind = 'answer')) then
            return false;
        end if;
        select payload into sec from public.mode_secrets where round_id = rd.id and kind = 'answer';
        total := greatest(extract(epoch from rd.deadline_at - rd.started_at), 1);
        for s in select * from public.mode_submissions where round_id = rd.id and kind = 'answer' loop
            if (s.payload->>'choice')::int = (sec->>'answer')::int then
                left_s := least(greatest(extract(epoch from rd.deadline_at - s.created_at), 0), total);
                pts := 100 + floor(50 * left_s / total)::int;       -- correct: 100 + speed bonus up to 50
                update public.mode_submissions set correct = true, points = pts where id = s.id;
                perform public._mz_award(rd.match_id, s.user_id, pts);
            else
                update public.mode_submissions set correct = false where id = s.id;
            end if;
        end loop;
        update public.mode_rounds set phase = 'reveal', deadline_at = now() + interval '7 seconds',
               results = jsonb_build_object('answer', sec->'answer', 'explanation', sec->'explanation',
                   'answers', coalesce((select jsonb_agg(jsonb_build_object('user_id', x.user_id, 'choice', x.payload->'choice',
                                                         'correct', x.correct, 'points', x.points) order by x.created_at)
                                         from public.mode_submissions x where x.round_id = rd.id and x.kind = 'answer'), '[]'::jsonb))
         where id = rd.id;
        return true;
    elsif rd.phase = 'reveal' then
        if now() < rd.deadline_at then return false; end if;
        perform public._mz_round_win(rd.id);
        update public.mode_rounds set phase = 'done' where id = rd.id;
        return true;
    end if;
    return false;
end $$;

create or replace function public._mz_qa_view(rd public.mode_rounds, u uuid)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select jsonb_build_object(
        'my_choice', (select payload->'choice' from public.mode_submissions where round_id = rd.id and user_id = u and kind = 'answer'),
        'answered', coalesce((select jsonb_agg(user_id) from public.mode_submissions where round_id = rd.id and kind = 'answer'), '[]'::jsonb));
$$;

-- ---------------------------------------------------------------------
-- İki Doğru Bir Yalan
-- ---------------------------------------------------------------------
create or replace function public._mz_tl_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare v uuid; authors jsonb := public._mz_state(m.id)->'authors'; author uuid; st jsonb;
begin
    if n = 1 then
        insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, public, started_at, deadline_at)
        values (m.id, m.room_id, m.game_mode, 1, 'write', '{}'::jsonb, now(),
                now() + make_interval(secs => public._mz_secs(m.id, 60, 120)))
        returning id into v;
        return v;
    end if;
    if authors is null or n - 1 > jsonb_array_length(authors) then return null; end if;
    if coalesce(array_length(public._mz_players(m.id), 1), 0) < 2 then return null; end if;
    author := (authors->>(n - 2))::uuid;
    select payload into st from public.mode_submissions s join public.mode_rounds r on r.id = s.round_id
     where r.match_id = m.id and r.round_number = 1 and s.user_id = author and s.kind = 'statements';
    insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, actor_id, public, started_at, deadline_at)
    values (m.id, m.room_id, m.game_mode, n, 'vote', author, jsonb_build_object('statements', st->'items'),
            now() + interval '2 seconds', now() + interval '27 seconds')
    returning id into v;
    insert into public.mode_secrets(round_id, kind, payload) values (v, 'lie', jsonb_build_object('lie', st->'lie'));
    return v;
end $$;

create or replace function public._mz_tl_act(rd public.mode_rounds, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare items jsonb := '[]'; x text; lie int; ch int;
begin
    if p_kind = 'statements' then
        if rd.phase <> 'write' or now() > rd.deadline_at + interval '1 second' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
        if jsonb_typeof(p->'items') <> 'array' or jsonb_array_length(p->'items') <> 3 then raise exception 'LOOPA:INVALID_STATEMENTS'; end if;
        for x in select value from jsonb_array_elements_text(p->'items') loop
            x := public._mz_clean(x, 90);
            if char_length(x) < 3 then raise exception 'LOOPA:INVALID_STATEMENTS'; end if;
            items := items || to_jsonb(x);
        end loop;
        lie := case when jsonb_typeof(p->'lie') = 'number' then (p->>'lie')::int end;
        if lie is null or lie not between 0 and 2 then raise exception 'LOOPA:INVALID_STATEMENTS'; end if;
        insert into public.mode_submissions(round_id, match_id, user_id, kind, payload)
        values (rd.id, rd.match_id, u, 'statements', jsonb_build_object('items', items, 'lie', lie))
        on conflict (round_id, user_id, kind) where kind in ('answer','vote','statements','step')
        do update set payload = excluded.payload, created_at = clock_timestamp();
        return jsonb_build_object('saved', true);
    elsif p_kind = 'vote' then
        if rd.phase <> 'vote' or now() > rd.deadline_at + interval '1 second' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
        if now() < rd.started_at then raise exception 'LOOPA:ROUND_NOT_STARTED'; end if;
        if u = rd.actor_id then raise exception 'LOOPA:CANNOT_VOTE_OWN'; end if;
        ch := case when jsonb_typeof(p->'choice') = 'number' then (p->>'choice')::int end;
        if ch is null or ch not between 0 and 2 then raise exception 'LOOPA:INVALID_CHOICE'; end if;
        begin
            insert into public.mode_submissions(round_id, match_id, user_id, kind, payload)
            values (rd.id, rd.match_id, u, 'vote', jsonb_build_object('choice', ch));
        exception when unique_violation then raise exception 'LOOPA:ALREADY_VOTED';
        end;
        return jsonb_build_object('locked', true);
    end if;
    raise exception 'LOOPA:INVALID_ACTION';
end $$;

create or replace function public._mz_tl_tick(rd public.mode_rounds)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare lie int; s record; fooled int := 0;
begin
    if rd.phase = 'write' then
        if now() < rd.deadline_at and exists (select 1 from unnest(public._mz_players(rd.match_id)) p
                    where not exists (select 1 from public.mode_submissions x where x.round_id = rd.id and x.user_id = p and x.kind = 'statements')) then
            return false;
        end if;
        perform public._mz_set(rd.match_id, 'authors', coalesce((
            select jsonb_agg(sub.user_id order by t.ord)
              from public.mode_submissions sub
              join jsonb_array_elements_text(public._mz_state(rd.match_id)->'order') with ordinality t(u, ord) on t.u::uuid = sub.user_id
             where sub.round_id = rd.id and sub.kind = 'statements'), '[]'::jsonb));
        perform public._mz_pub(rd.match_id, 'total', to_jsonb(1 + jsonb_array_length(public._mz_state(rd.match_id)->'authors')));
        update public.mode_rounds set phase = 'done', results = jsonb_build_object('authors', public._mz_state(rd.match_id)->'authors') where id = rd.id;
        return true;
    elsif rd.phase = 'vote' then
        if now() < rd.deadline_at and exists (select 1 from unnest(public._mz_players(rd.match_id)) p
                    where p <> rd.actor_id and not exists (select 1 from public.mode_submissions x where x.round_id = rd.id and x.user_id = p and x.kind = 'vote')) then
            return false;
        end if;
        select (payload->>'lie')::int into lie from public.mode_secrets where round_id = rd.id and kind = 'lie';
        for s in select * from public.mode_submissions where round_id = rd.id and kind = 'vote' loop
            if (s.payload->>'choice')::int = lie then
                update public.mode_submissions set correct = true, points = 10 where id = s.id;
                perform public._mz_award(rd.match_id, s.user_id, 10);
            else
                update public.mode_submissions set correct = false where id = s.id;
                fooled := fooled + 1;
            end if;
        end loop;
        if fooled > 0 then
            insert into public.mode_submissions(round_id, match_id, user_id, kind, payload, points)
            values (rd.id, rd.match_id, rd.actor_id, 'fooled', jsonb_build_object('count', fooled), 5 * fooled);
            perform public._mz_award(rd.match_id, rd.actor_id, 5 * fooled);
        end if;
        update public.mode_rounds set phase = 'reveal', deadline_at = now() + interval '7 seconds',
               results = jsonb_build_object('lie', lie, 'fooled', fooled, 'author_points', 5 * fooled,
                   'votes', coalesce((select jsonb_agg(jsonb_build_object('user_id', x.user_id, 'choice', x.payload->'choice', 'correct', x.correct))
                                       from public.mode_submissions x where x.round_id = rd.id and x.kind = 'vote'), '[]'::jsonb))
         where id = rd.id;
        return true;
    elsif rd.phase = 'reveal' then
        if now() < rd.deadline_at then return false; end if;
        update public.mode_rounds set phase = 'done' where id = rd.id;
        return true;
    end if;
    return false;
end $$;

create or replace function public._mz_tl_view(rd public.mode_rounds, u uuid)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select jsonb_build_object(
        'mine', (select payload from public.mode_submissions where round_id = rd.id and user_id = u and kind = 'statements'),
        'my_vote', (select payload->'choice' from public.mode_submissions where round_id = rd.id and user_id = u and kind = 'vote'),
        'done', coalesce((select jsonb_agg(user_id) from public.mode_submissions where round_id = rd.id and kind in ('statements','vote')), '[]'::jsonb));
$$;

-- ---------------------------------------------------------------------
-- En Muhtemel Kim?
-- ---------------------------------------------------------------------
create or replace function public._mz_ml_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare c public.mode_content; v uuid;
begin
    if n > public._mz_rounds(m.id) or coalesce(array_length(public._mz_players(m.id), 1), 0) < 3 then return null; end if;
    c := public._mz_pick(m.id, 'prompt');
    insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, public, started_at, deadline_at)
    values (m.id, m.room_id, m.game_mode, n, 'vote', jsonb_build_object('prompt', c.payload->>'text'),
            now() + interval '2 seconds', now() + interval '22 seconds')
    returning id into v;
    return v;
end $$;

create or replace function public._mz_ml_act(rd public.mode_rounds, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare t uuid;
begin
    if p_kind <> 'vote' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if rd.phase <> 'vote' or now() > rd.deadline_at + interval '1 second' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if now() < rd.started_at then raise exception 'LOOPA:ROUND_NOT_STARTED'; end if;
    begin t := (p->>'target')::uuid; exception when others then raise exception 'LOOPA:INVALID_TARGET'; end;
    if t is null or not exists (select 1 from public.match_players where match_id = rd.match_id and user_id = t) then raise exception 'LOOPA:INVALID_TARGET'; end if;
    if t = u then raise exception 'LOOPA:CANNOT_VOTE_SELF'; end if;
    begin
        insert into public.mode_submissions(round_id, match_id, user_id, kind, payload)
        values (rd.id, rd.match_id, u, 'vote', jsonb_build_object('target', t));
    exception when unique_violation then raise exception 'LOOPA:ALREADY_VOTED';
    end;
    return jsonb_build_object('locked', true);
end $$;

create or replace function public._mz_ml_tick(rd public.mode_rounds)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare best int; tops jsonb;
begin
    if rd.phase = 'vote' then
        if now() < rd.deadline_at and exists (select 1 from unnest(public._mz_players(rd.match_id)) p
                    where not exists (select 1 from public.mode_submissions x where x.round_id = rd.id and x.user_id = p and x.kind = 'vote')) then
            return false;
        end if;
        select max(c) into best from (select count(*) c from public.mode_submissions where round_id = rd.id and kind = 'vote' group by payload->>'target') x;
        select coalesce(jsonb_agg(t), '[]'::jsonb) into tops from (
            select payload->>'target' t from public.mode_submissions where round_id = rd.id and kind = 'vote'
             group by payload->>'target' having count(*) = best) q;
        -- Voters who picked (one of) the room's choice get 10 points.
        update public.mode_submissions set correct = tops ? (payload->>'target'), points = case when tops ? (payload->>'target') then 10 else 0 end
         where round_id = rd.id and kind = 'vote';
        perform public._mz_award(rd.match_id, s.user_id, 10)
           from public.mode_submissions s where s.round_id = rd.id and s.kind = 'vote' and s.correct;
        update public.match_players set round_wins = round_wins + 1
         where match_id = rd.match_id and tops ? user_id::text;
        update public.mode_rounds set phase = 'reveal', deadline_at = now() + interval '7 seconds',
               results = jsonb_build_object('tops', tops,
                   'counts', coalesce((select jsonb_agg(jsonb_build_object('user_id', t, 'votes', c) order by c desc)
                                         from (select payload->>'target' t, count(*) c from public.mode_submissions
                                                where round_id = rd.id and kind = 'vote' group by 1) q), '[]'::jsonb),
                   'votes', coalesce((select jsonb_agg(jsonb_build_object('user_id', user_id, 'target', payload->>'target'))
                                        from public.mode_submissions where round_id = rd.id and kind = 'vote'), '[]'::jsonb))
         where id = rd.id;
        return true;
    elsif rd.phase = 'reveal' then
        if now() < rd.deadline_at then return false; end if;
        update public.mode_rounds set phase = 'done' where id = rd.id;
        return true;
    end if;
    return false;
end $$;

create or replace function public._mz_ml_view(rd public.mode_rounds, u uuid)
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select jsonb_build_object(
        'my_vote', (select payload->>'target' from public.mode_submissions where round_id = rd.id and user_id = u and kind = 'vote'),
        'voted', coalesce((select jsonb_agg(user_id) from public.mode_submissions where round_id = rd.id and kind = 'vote'), '[]'::jsonb));
$$;

-- ---------------------------------------------------------------------
-- Kelime Zinciri (turns, lives, no repeats)
-- ---------------------------------------------------------------------
create or replace function public._mz_wc_init(m public.matches)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare w text; lives jsonb := '{}'; x text;
begin
    select word into w from public.chain_lexicon where char_length(match_key) between 4 and 7 and right(match_key, 1) not in ('g','f','j','v','z','h')
     order by random() limit 1;
    w := coalesce(w, 'kalem');
    for x in select value from jsonb_array_elements_text(public._mz_state(m.id)->'order') loop
        lives := lives || jsonb_build_object(x, 3);
    end loop;
    perform public._mz_set(m.id, 'chain_used', jsonb_build_array(public.dict_key(w)));
    perform public._mz_pub(m.id, 'word', to_jsonb(w));
    perform public._mz_pub(m.id, 'letter', to_jsonb(public._mz_last_letter(w)));
    perform public._mz_pub(m.id, 'lives', lives);
    perform public._mz_pub(m.id, 'chain', jsonb_build_array(jsonb_build_object('word', w, 'user_id', null)));
    perform public._mz_pub(m.id, 'total', to_jsonb(public._mz_rounds(m.id) * jsonb_array_length(public._mz_state(m.id)->'order')));
end $$;

create or replace function public._mz_wc_alive(p_match uuid)
returns uuid[] language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce(array_agg(u order by i), '{}') from unnest(public._mz_players(p_match)) with ordinality t(u, i)
     where coalesce(((select public from public.mode_match_state where match_id = p_match)->'lives'->>u::text)::int, 0) > 0;
$$;

create or replace function public._mz_wc_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare alive uuid[] := public._mz_wc_alive(m.id); last uuid; idx int := 0; actor uuid; ord uuid[]; v uuid; secs int; i int; x uuid;
        pub jsonb := (select public from public.mode_match_state where match_id = m.id);
begin
    if n > (pub->>'total')::int or coalesce(array_length(alive, 1), 0) < 2 then
        -- survivors bonus
        foreach x in array alive loop perform public._mz_award(m.id, x, 15); end loop;
        return null;
    end if;
    select actor_id into last from public.mode_rounds where match_id = m.id order by round_number desc limit 1;
    select array_agg(t.u::uuid order by t.ord) into ord from jsonb_array_elements_text(public._mz_state(m.id)->'order') with ordinality t(u, ord);
    if last is not null then idx := array_position(ord, last); end if;
    for i in 1..array_length(ord, 1) loop
        x := ord[((coalesce(idx, 0) + i - 1) % array_length(ord, 1)) + 1];
        if x = any(alive) then actor := x; exit; end if;
    end loop;
    secs := case public._mz_secs(m.id, 30, 120) when 30 then 15 when 60 then 20 when 90 then 25 else 30 end;
    insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, actor_id, public, started_at, deadline_at)
    values (m.id, m.room_id, m.game_mode, n, 'turn', actor,
            jsonb_build_object('word', pub->'word', 'letter', pub->'letter', 'seconds', secs),
            now() + interval '1 second', now() + interval '1 second' + make_interval(secs => secs))
    returning id into v;
    return v;
end $$;

create or replace function public._mz_wc_act(rd public.mode_rounds, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare txt text := public._mz_clean(p->>'text', 30); k text := public.dict_key(public._mz_clean(p->>'text', 30)); pts int; letter text := rd.public->>'letter';
        used jsonb := public._mz_state(rd.match_id)->'chain_used'; pub jsonb;
begin
    if p_kind <> 'word' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if rd.phase <> 'turn' or now() > rd.deadline_at + interval '1 second' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if u <> rd.actor_id then raise exception 'LOOPA:NOT_YOUR_TURN'; end if;
    if txt ~ '\s' or txt !~ '^[[:alpha:]çğıöşüâîûÇĞİÖŞÜÂÎÛ]+$' then raise exception 'LOOPA:CHAIN_SINGLE_WORD'; end if;
    if char_length(k) < 2 then raise exception 'LOOPA:CHAIN_TOO_SHORT'; end if;
    if left(k, 1) <> letter then raise exception 'LOOPA:CHAIN_WRONG_LETTER'; end if;
    if used ? k then raise exception 'LOOPA:CHAIN_USED'; end if;
    if not public._mz_chain_known(txt) then raise exception 'LOOPA:CHAIN_UNKNOWN_WORD'; end if;
    pts := 10 + least(5, greatest(0, char_length(k) - 4));       -- 10, +1 per letter over 4, max 15
    insert into public.mode_submissions(round_id, match_id, user_id, kind, payload, correct, points)
    values (rd.id, rd.match_id, u, 'word', jsonb_build_object('text', txt), true, pts);
    perform public._mz_award(rd.match_id, u, pts);
    perform public._mz_set(rd.match_id, 'chain_used', used || to_jsonb(k));
    select public into pub from public.mode_match_state where match_id = rd.match_id;
    perform public._mz_pub(rd.match_id, 'word', to_jsonb(txt));
    perform public._mz_pub(rd.match_id, 'letter', to_jsonb(public._mz_last_letter(txt)));
    perform public._mz_pub(rd.match_id, 'chain', (pub->'chain') || jsonb_build_array(jsonb_build_object('word', txt, 'user_id', u, 'points', pts)));
    update public.mode_rounds set phase = 'done', results = jsonb_build_object('valid', true, 'word', txt, 'points', pts) where id = rd.id;
    return jsonb_build_object('valid', true, 'points', pts);
end $$;

create or replace function public._mz_wc_tick(rd public.mode_rounds)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare lives jsonb; left_now int; gone boolean;
begin
    if rd.phase <> 'turn' then return false; end if;
    gone := not public._mz_active(rd.room_id, rd.actor_id);
    if now() < rd.deadline_at + interval '1 second' and not gone then return false; end if;
    select public->'lives' into lives from public.mode_match_state where match_id = rd.match_id;
    left_now := case when gone then 0 else greatest(coalesce((lives->>rd.actor_id::text)::int, 1) - 1, 0) end;
    perform public._mz_pub(rd.match_id, 'lives', jsonb_set(lives, array[rd.actor_id::text], to_jsonb(left_now)));
    update public.mode_rounds set phase = 'done',
           results = jsonb_build_object('valid', false, 'timeout', true, 'left', gone, 'lives', left_now) where id = rd.id;
    return true;
end $$;

-- ---------------------------------------------------------------------
-- Çizim Telefonu (draw / guess chains)
-- ---------------------------------------------------------------------
create or replace function public._mz_tp_init(m public.matches)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare n int := jsonb_array_length(public._mz_state(m.id)->'order'); starts jsonb := '[]'; c public.mode_content; i int;
begin
    for i in 1..n loop
        c := public._mz_pick(m.id, 'telephone');
        starts := starts || to_jsonb(c.payload->>'text');
    end loop;
    perform public._mz_set(m.id, 'starts', starts);
    perform public._mz_pub(m.id, 'total', to_jsonb(n + 1));
end $$;

-- Index (0-based) in the turn order of the player who works on chain p_chain at step p_step.
create or replace function public._mz_tp_worker(p_match uuid, p_chain int, p_step int)
returns uuid language sql stable security definer set search_path = public, pg_temp as $$
    select (s->'order'->>(((p_chain + p_step - 1) % jsonb_array_length(s->'order'))))::uuid
      from (select public._mz_state(p_match) s) x;
$$;

create or replace function public._mz_tp_chain_of(p_match uuid, p_user uuid, p_step int)
returns int language sql stable security definer set search_path = public, pg_temp as $$
    select ((t.ord - 1 - (p_step - 1)) % n + n) % n
      from (select public._mz_state(p_match) s) x,
           lateral (select jsonb_array_length(x.s->'order') n) nn,
           jsonb_array_elements_text(x.s->'order') with ordinality t(u, ord)
     where t.u::uuid = p_user;
$$;

-- What the worker of chain p_chain sees at step p_step: the last non-empty item before it.
create or replace function public._mz_tp_prev(p_match uuid, p_chain int, p_step int)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare s int; item jsonb;
begin
    for s in reverse (p_step - 1)..1 loop
        select jsonb_build_object('kind', case when s % 2 = 1 then 'draw' else 'guess' end, 'step', s, 'payload', sub.payload)
          into item
          from public.mode_rounds r join public.mode_submissions sub on sub.round_id = r.id and sub.kind = 'step'
         where r.match_id = p_match and r.round_number = s and sub.user_id = public._mz_tp_worker(p_match, p_chain, s);
        if item is not null then return item; end if;
    end loop;
    return jsonb_build_object('kind', 'start', 'step', 0, 'payload', jsonb_build_object('text', public._mz_state(p_match)->'starts'->>p_chain));
end $$;

create or replace function public._mz_tp_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare steps int := jsonb_array_length(public._mz_state(m.id)->'order'); v uuid; kind text; secs int; chains jsonb := '[]'; c int; s int;
        item jsonb; items jsonb; final_guess text; x uuid;
begin
    if n <= steps then
        if coalesce(array_length(public._mz_players(m.id), 1), 0) < 2 then return null; end if;
        kind := case when n % 2 = 1 then 'draw' else 'guess' end;
        secs := case when kind = 'draw' then public._mz_secs(m.id, 45, 120) else 35 end;
        insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, public, started_at, deadline_at)
        values (m.id, m.room_id, m.game_mode, n, 'work', jsonb_build_object('step', n, 'steps', steps, 'kind', kind),
                now() + interval '2 seconds', now() + interval '2 seconds' + make_interval(secs => secs))
        returning id into v;
        return v;
    elsif n = steps + 1 then
        -- Showcase: every chain from start to end, with authors. Bonus when the last guess names the start.
        for c in 0..steps - 1 loop
            items := jsonb_build_array(jsonb_build_object('kind', 'start', 'payload', jsonb_build_object('text', public._mz_state(m.id)->'starts'->>c)));
            final_guess := null;
            for s in 1..steps loop
                select jsonb_build_object('kind', case when s % 2 = 1 then 'draw' else 'guess' end, 'user_id', sub.user_id, 'payload', sub.payload)
                  into item
                  from public.mode_rounds r join public.mode_submissions sub on sub.round_id = r.id and sub.kind = 'step'
                 where r.match_id = m.id and r.round_number = s and sub.user_id = public._mz_tp_worker(m.id, c, s);
                if item is null then
                    item := jsonb_build_object('kind', case when s % 2 = 1 then 'draw' else 'guess' end,
                                               'user_id', public._mz_tp_worker(m.id, c, s), 'missing', true);
                elsif s % 2 = 0 then final_guess := item->'payload'->>'text';
                end if;
                items := items || item;
            end loop;
            -- Only when the chain ends with a guess (even number of steps).
            if steps % 2 = 0 and final_guess is not null
               and public.dict_key(final_guess) = public.dict_key(public._mz_state(m.id)->'starts'->>c) then
                for s in 1..steps loop
                    x := public._mz_tp_worker(m.id, c, s);
                    perform public._mz_award(m.id, x, 20);
                end loop;
                chains := chains || jsonb_build_object('items', items, 'matched', true);
            else
                chains := chains || jsonb_build_object('items', items, 'matched', false);
            end if;
        end loop;
        insert into public.mode_rounds(match_id, room_id, mode, round_number, phase, public, results, started_at, deadline_at)
        values (m.id, m.room_id, m.game_mode, n, 'showcase', jsonb_build_object('steps', steps), jsonb_build_object('chains', chains),
                now(), now() + make_interval(secs => least(20 * steps, 180)))
        returning id into v;
        return v;
    end if;
    return null;
end $$;

create or replace function public._mz_tp_act(rd public.mode_rounds, u uuid, p_kind text, p jsonb)
returns jsonb language plpgsql security definer set search_path = public, pg_temp as $$
declare kind text := rd.public->>'kind'; payload jsonb;
begin
    if p_kind <> 'step' then raise exception 'LOOPA:INVALID_ACTION'; end if;
    if rd.phase <> 'work' or now() > rd.deadline_at + interval '2 seconds' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if now() < rd.started_at then raise exception 'LOOPA:ROUND_NOT_STARTED'; end if;
    if public._mz_tp_chain_of(rd.match_id, u, rd.round_number) is null then raise exception 'LOOPA:NOT_A_PARTICIPANT'; end if;
    if kind = 'draw' then
        if not public._mz_valid_strokes(p->'strokes', 600) then raise exception 'LOOPA:INVALID_DRAWING'; end if;
        payload := jsonb_build_object('strokes', p->'strokes');
    else
        if char_length(public._mz_clean(p->>'text', 60)) < 1 then raise exception 'LOOPA:EMPTY_GUESS'; end if;
        payload := jsonb_build_object('text', public._mz_clean(p->>'text', 60));
    end if;
    begin
        insert into public.mode_submissions(round_id, match_id, user_id, kind, payload, points)
        values (rd.id, rd.match_id, u, 'step', payload, 10);
    exception when unique_violation then raise exception 'LOOPA:ALREADY_SUBMITTED';
    end;
    perform public._mz_award(rd.match_id, u, 10);
    return jsonb_build_object('saved', true);
end $$;

create or replace function public._mz_tp_tick(rd public.mode_rounds)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if rd.phase = 'work' then
        if now() < rd.deadline_at + interval '1 second' and exists (select 1 from unnest(public._mz_players(rd.match_id)) p
                    where not exists (select 1 from public.mode_submissions x where x.round_id = rd.id and x.user_id = p and x.kind = 'step')) then
            return false;
        end if;
        update public.mode_rounds set phase = 'done' where id = rd.id;
        return true;
    elsif rd.phase = 'showcase' then
        if now() < rd.deadline_at then return false; end if;
        update public.mode_rounds set phase = 'done' where id = rd.id;
        return true;
    end if;
    return false;
end $$;

create or replace function public._mz_tp_view(rd public.mode_rounds, u uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare ch int;
begin
    if rd.phase <> 'work' then return '{}'::jsonb; end if;
    ch := public._mz_tp_chain_of(rd.match_id, u, rd.round_number);
    return jsonb_build_object(
        'chain', ch,
        'task', case when ch is not null then public._mz_tp_prev(rd.match_id, ch, rd.round_number) end,
        'submitted', exists (select 1 from public.mode_submissions where round_id = rd.id and user_id = u and kind = 'step'),
        'done', coalesce((select jsonb_agg(user_id) from public.mode_submissions where round_id = rd.id and kind = 'step'), '[]'::jsonb));
end $$;

-- ---------------------------------------------------------------------
-- Dispatch
-- ---------------------------------------------------------------------
create or replace function public._mz_new_round(m public.matches, n int)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
begin
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

-- Creates the next round; finishes the match (XP, ranks, history) when there is none.
create or replace function public._mode_next(m public.matches)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare n int; v uuid;
begin
    select coalesce(max(round_number), 0) + 1 into n from public.mode_rounds where match_id = m.id;
    v := public._mz_new_round(m, n);
    if v is null then
        perform public._finish_match(m.id);
        return false;
    end if;
    update public.matches set current_round = n where id = m.id;
    update public.player_statistics ps set rounds_played = rounds_played + 1, updated_at = now()
     where ps.user_id = any(public._mz_players(m.id));
    return true;
end $$;

create or replace function public._mode_start(p_match uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare m public.matches; ord jsonb;
begin
    select * into m from public.matches where id = p_match for update;
    select coalesce(jsonb_agg(mp.user_id order by rp.joined_at, mp.user_id), '[]'::jsonb) into ord
      from public.match_players mp join public.room_players rp on rp.room_id = mp.room_id and rp.user_id = mp.user_id
     where mp.match_id = p_match;
    insert into public.mode_match_state(match_id, state, public)
    values (p_match, jsonb_build_object('order', ord, 'used', '[]'::jsonb), jsonb_build_object('total', public._mz_rounds(p_match)))
    on conflict (match_id) do nothing;
    if m.game_mode = 'anlat_bakalim' then perform public._mz_ab_init(m);
    elsif m.game_mode = 'kelime_zinciri' then perform public._mz_wc_init(m);
    elsif m.game_mode = 'cizim_telefonu' then perform public._mz_tp_init(m);
    elsif m.game_mode = 'iki_dogru_bir_yalan' then perform public._mz_pub(p_match, 'total', to_jsonb(1 + jsonb_array_length(ord)));
    end if;
    perform public._mode_next(m);
end $$;

-- Advances the current mode round as far as the clock allows (called from _advance under the room lock).
create or replace function public._mode_advance(p_room uuid)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms; m public.matches; rd public.mode_rounds; changed boolean := false; guard int := 0;
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

-- ---------------------------------------------------------------------
-- Client RPCs
-- ---------------------------------------------------------------------
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
    perform public._mode_advance(rd.room_id);       -- e.g. everybody answered: go on at once
    perform public._bump(rd.room_id);
    return res;
end $$;

-- Live strokes of the drawer (Çiz ve Tahmin Et). Not bumped: clients fetch them with get_strokes.
create or replace function public.mode_draw(p_round_id uuid, p_strokes jsonb)
returns bigint language plpgsql security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); rd public.mode_rounds; s jsonb; last bigint;
begin
    select * into rd from public.mode_rounds where id = p_round_id;
    if not found then raise exception 'LOOPA:ROUND_NOT_FOUND'; end if;
    if rd.mode <> 'ciz_tahmin' or rd.phase <> 'play' or now() > rd.deadline_at + interval '1 second' then raise exception 'LOOPA:PHASE_CLOSED'; end if;
    if rd.actor_id <> u or not public._mz_active(rd.room_id, u) then raise exception 'LOOPA:NOT_ACTOR'; end if;
    if not public._mz_valid_strokes(p_strokes, 60) then raise exception 'LOOPA:INVALID_DRAWING'; end if;
    if (select count(*) from public.mode_strokes where round_id = rd.id) + jsonb_array_length(p_strokes) > 4000 then
        raise exception 'LOOPA:DRAWING_TOO_LARGE';
    end if;
    for s in select value from jsonb_array_elements(p_strokes) loop
        insert into public.mode_strokes(round_id, user_id, payload) values (rd.id, u, s) returning seq into last;
    end loop;
    return coalesce(last, (select max(seq) from public.mode_strokes where round_id = rd.id), 0);
end $$;

create or replace function public.get_strokes(p_round_id uuid, p_after bigint default 0)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); rd public.mode_rounds;
begin
    select * into rd from public.mode_rounds where id = p_round_id;
    if not found or not public.is_room_member(rd.room_id) then raise exception 'LOOPA:ROUND_NOT_FOUND'; end if;
    return coalesce((select jsonb_agg(jsonb_build_object('seq', seq, 's', payload) order by seq)
                       from (select * from public.mode_strokes where round_id = rd.id and seq > coalesce(p_after, 0)
                              order by seq limit 1500) x), '[]'::jsonb);
end $$;

-- Per-player snapshot of the running (or last) mode match.
create or replace function public.get_mode_state(p_room_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare u uuid := public._uid(); me public.room_players; r public.rooms; m public.matches; rd public.mode_rounds; mms public.mode_match_state;
begin
    select * into me from public.room_players where room_id = p_room_id and user_id = u;
    if not found or me.status <> 'active' then raise exception 'LOOPA:NOT_IN_ROOM'; end if;
    select * into r from public.rooms where id = p_room_id;
    select * into m from public.matches where id = coalesce(r.current_match_id, r.last_match_id);
    if not found or m.game_mode = 'isim_sehir' then return null; end if;
    select * into mms from public.mode_match_state where match_id = m.id;
    select * into rd from public.mode_rounds where match_id = m.id order by round_number desc limit 1;
    return jsonb_build_object(
        'server_time', now(),
        'mode', m.game_mode,
        'match_id', m.id,
        'match_status', m.status,
        'public', coalesce(mms.public, '{}'::jsonb),
        'round', case when rd.id is null then null else jsonb_build_object(
            'id', rd.id, 'number', rd.round_number, 'phase', rd.phase, 'actor_id', rd.actor_id, 'public', rd.public,
            'results', case when rd.phase in ('reveal','done','showcase') then rd.results end,
            'started_at', rd.started_at, 'deadline_at', rd.deadline_at) end,
        'me', case when rd.id is null then '{}'::jsonb else public._mz_view(rd, u) end
    );
end $$;

create or replace function public.mode_skip(p_room_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id);
begin
    perform public._require_host(r);
    update public.mode_rounds set deadline_at = now()
     where match_id = r.current_match_id and phase in ('reveal','showcase');
    perform public._mode_advance(p_room_id);
    perform public._bump(p_room_id);
end $$;

create or replace function public.get_my_mode_stats()
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select coalesce(jsonb_agg(jsonb_build_object('game_mode', game_mode, 'matches_played', matches_played, 'wins', wins,
                                                  'total_points', total_points, 'best_points', best_points, 'last_played_at', last_played_at)
                              order by matches_played desc), '[]'::jsonb)
      from public.player_mode_stats where user_id = auth.uid();
$$;

-- ---------------------------------------------------------------------
-- Per-game statistics: written once, when a match's rewards are processed.
-- ---------------------------------------------------------------------
create or replace function public._mz_stats_trigger()
returns trigger language plpgsql security definer set search_path = public, pg_temp as $$
begin
    if new.rewards_processed and not old.rewards_processed then
        insert into public.player_mode_stats(user_id, game_mode, matches_played, wins, total_points, best_points, last_played_at)
        select mp.user_id, new.game_mode, 1, case when mp.final_rank = 1 then 1 else 0 end, mp.total_points, mp.total_points, now()
          from public.match_players mp where mp.match_id = new.id
        on conflict (user_id, game_mode) do update set
            matches_played = player_mode_stats.matches_played + 1,
            wins = player_mode_stats.wins + excluded.wins,
            total_points = player_mode_stats.total_points + excluded.total_points,
            best_points = greatest(player_mode_stats.best_points, excluded.best_points),
            last_played_at = now();
    end if;
    return new;
end $$;
drop trigger if exists matches_mode_stats on public.matches;
create trigger matches_mode_stats after update of rewards_processed on public.matches
    for each row execute function public._mz_stats_trigger();

-- ---------------------------------------------------------------------
-- Existing functions, extended. İsim Şehir code below is unchanged.
-- ---------------------------------------------------------------------
create or replace function public._advance(p_room_id uuid)
returns boolean language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms; m public.matches; rd public.rounds; changed boolean := false; guard int := 0; pending int; total_votes_needed int; votes_cast int; n_active int;
begin
    select * into r from public.rooms where id = p_room_id;
    if r.status <> 'in_game' or r.current_match_id is null then return false; end if;
    -- New: every game except İsim Şehir runs on the mode engine.
    if exists (select 1 from public.matches mm where mm.id = r.current_match_id and mm.game_mode <> 'isim_sehir') then
        return public._mode_advance(p_room_id);
    end if;
    loop
        guard := guard + 1;
        exit when guard > 6;
        select * into rd from public.rounds where match_id = r.current_match_id order by round_number desc limit 1 for update;
        exit when not found;

        if rd.phase = 'answering' then
            exit when now() < rd.started_at;
            -- still waiting for a connected, unsubmitted player whose deadline (+1.5 s grace) has not passed?
            select count(*) into pending from public.round_players rpl
              join public.room_players rp on rp.room_id = rpl.room_id and rp.user_id = rpl.user_id
             where rpl.round_id = rd.id and rpl.submitted_at is null and rp.status = 'active'
               and rpl.deadline_at + interval '1.5 seconds' > now();
            exit when pending > 0;
            perform public._close_answering(rd.id);
            changed := true;
        elsif rd.phase = 'voting' then
            select coalesce(sum(public._eligible_voters(rd.id, a.user_id)), 0),
                   coalesce(sum((select count(*) from public.answer_votes av
                                  join public.room_players rp on rp.room_id = rd.room_id and rp.user_id = av.voter_id and rp.status = 'active'
                                 where av.answer_id = a.id)), 0)
              into total_votes_needed, votes_cast
              from public.answers a where a.round_id = rd.id and a.final_status is null;
            exit when now() < rd.voting_deadline and votes_cast < total_votes_needed;
            perform public._score_round(rd.id);
            changed := true;
        elsif rd.phase = 'reveal' then
            exit when now() < rd.reveal_deadline;
            update public.rounds set phase = 'results', results_deadline = now() + interval '8 seconds' where id = rd.id;
            changed := true;
        elsif rd.phase = 'results' then
            exit when now() < rd.results_deadline;
            update public.rounds set phase = 'finished' where id = rd.id;
            select * into m from public.matches where id = r.current_match_id;
            select count(*) into n_active from public.room_players rp
              join public.match_players mp on mp.user_id = rp.user_id and mp.match_id = m.id
             where rp.room_id = p_room_id and rp.status = 'active';
            if rd.round_number >= m.total_rounds or n_active < 2 then
                perform public._finish_match(m.id);
                changed := true;
                exit;
            else
                perform public._start_round(m.id);
            end if;
            changed := true;
        else
            exit;
        end if;
    end loop;
    if changed then perform public._bump(p_room_id); end if;
    return changed;
end $$;

create or replace function public.skip_reveal(p_room_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id);
begin
    perform public._require_host(r);
    update public.rounds set reveal_deadline = now()
     where match_id = r.current_match_id and phase = 'reveal';
    update public.rounds set results_deadline = now()
     where match_id = r.current_match_id and phase = 'results';
    -- New: reveal / showcase of the other games.
    update public.mode_rounds set deadline_at = now()
     where match_id = r.current_match_id and phase in ('reveal','showcase');
    perform public._advance(p_room_id);
    perform public._bump(p_room_id);
end $$;

-- New: every implemented game can start; İsim Şehir path unchanged.
create or replace function public.start_match(p_room_id uuid)
returns uuid language plpgsql security definer set search_path = public, pg_temp as $$
declare r public.rooms := public._lock_room(p_room_id); v_match uuid; n int; not_ready int; p record;
        v_mode text; acc jsonb; v_min int; v_max int;
begin
    perform public._require_host(r);
    if r.status <> 'lobby' then raise exception 'LOOPA:GAME_IN_PROGRESS'; end if;

    v_mode := public._room_mode(r.settings);
    acc := public._mode_access(r.host_id, v_mode);
    if not (acc->>'allowed')::boolean then raise exception 'LOOPA:%', acc->>'reason'; end if;
    select min_players, max_players into v_min, v_max from public.game_modes where id = v_mode;

    select count(*), count(*) filter (where not is_ready and user_id <> r.host_id)
      into n, not_ready
      from public.room_players where room_id = p_room_id and status = 'active' and role = 'player';
    if n < greatest(2, coalesce(v_min, 2)) then raise exception 'LOOPA:NOT_ENOUGH_PLAYERS'; end if;
    if not_ready > 0 then raise exception 'LOOPA:PLAYERS_NOT_READY'; end if;

    begin
        insert into public.matches(room_id, settings, total_rounds, game_mode, entitlement_snapshot)
        values (p_room_id, r.settings, (r.settings->>'rounds')::int, v_mode,
                acc || jsonb_build_object('host_id', r.host_id, 'mode', v_mode, 'at', now()))
        returning id into v_match;
    exception when unique_violation then
        raise exception 'LOOPA:GAME_IN_PROGRESS';
    end;

    insert into public.match_players(match_id, room_id, user_id)
    select v_match, p_room_id, user_id from public.room_players
     where room_id = p_room_id and status = 'active' and role = 'player';

    update public.rooms set status = 'in_game', current_match_id = v_match, updated_at = now() where id = p_room_id;
    update public.room_players set is_ready = false where room_id = p_room_id and user_id <> r.host_id;

    if v_mode = 'isim_sehir' then
        for p in select user_id from public.match_players where match_id = v_match loop
            perform public._grant_joker(v_match, p.user_id, 'match_start', null);
        end loop;
        perform public._start_round(v_match);
    else
        perform public._mode_start(v_match);
    end if;
    perform public._bump(p_room_id);
    return v_match;
end $$;

-- New fields: game_mode, mode_public (teams, final chain …). İsim Şehir fields unchanged.
create or replace function public.get_match_results(p_match_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare m public.matches;
begin
    perform public._uid();
    select * into m from public.matches where id = p_match_id;
    if not found or not public.is_room_member(m.room_id) then raise exception 'LOOPA:MATCH_NOT_FOUND'; end if;
    return jsonb_build_object(
        'id', m.id, 'status', m.status, 'room_id', m.room_id, 'total_rounds', m.total_rounds,
        'game_mode', m.game_mode,
        'mode_public', (select public from public.mode_match_state where match_id = m.id),
        'rounds_played', case when m.game_mode = 'isim_sehir' then (select count(*) from public.rounds where match_id = m.id and scored)
                              else (select count(*) from public.mode_rounds where match_id = m.id and phase = 'done') end,
        'started_at', m.started_at, 'finished_at', m.finished_at,
        'players', coalesce((select jsonb_agg(jsonb_build_object(
                'user_id', mp.user_id, 'username', p.username, 'avatar', p.avatar, 'level', p.level, 'xp', p.xp,
                'total_points', mp.total_points, 'round_wins', mp.round_wins, 'final_rank', mp.final_rank,
                'xp_earned', mp.xp_earned,
                'valid_answers', (select count(*) from public.answers a join public.rounds r2 on r2.id = a.round_id
                                   where r2.match_id = m.id and a.user_id = mp.user_id and a.final_status = 'accepted'),
                'unique_answers', (select count(*) from public.answers a join public.rounds r2 on r2.id = a.round_id
                                   where r2.match_id = m.id and a.user_id = mp.user_id and a.final_status = 'accepted' and not a.is_duplicate),
                'early_bonuses', (select count(*) from public.round_players rpl join public.rounds r2 on r2.id = rpl.round_id
                                   where r2.match_id = m.id and rpl.user_id = mp.user_id and rpl.early_bonus > 0),
                'correct_actions', (select count(*) from public.mode_submissions s where s.match_id = m.id and s.user_id = mp.user_id and s.correct))
                order by mp.final_rank nulls last, mp.total_points desc)
            from public.match_players mp join public.profiles p on p.id = mp.user_id
            where mp.match_id = m.id), '[]'::jsonb),
        'round_winners', coalesce((select jsonb_agg(jsonb_build_object('round', r2.round_number, 'letter', r2.letter,
                'winners', (select jsonb_agg(rpl.user_id) from public.round_players rpl where rpl.round_id = r2.id and rpl.is_round_winner))
                order by r2.round_number)
            from public.rounds r2 where r2.match_id = m.id and r2.scored), '[]'::jsonb)
    );
end $$;

-- ---------------------------------------------------------------------
-- Grants: client RPCs executable, internal helpers not.
-- ---------------------------------------------------------------------
do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and (p.proname like '\_mz\_%' or p.proname in (
                  '_mode_next','_mode_start','_mode_advance','mode_act','mode_draw','get_strokes','get_mode_state','mode_skip',
                  'get_my_mode_stats','_advance','skip_reveal','start_match','get_match_results')) loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
        if left(f.proname, 1) <> '_' then
            execute format('grant execute on function %s to authenticated', f.sig);
        end if;
    end loop;
end $$;
