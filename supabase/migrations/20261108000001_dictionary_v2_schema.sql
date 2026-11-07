-- =====================================================================
-- LOOPA — Dictionary v2 (versioned entries + spelling alternatives)
--
-- Backward compatible:
--  * `word_dictionary` is kept (read by nothing new) and its rows are
--    migrated into `dictionary_entries` as version 1.
--  * New answer columns are nullable; old rows stay valid.
--  * `_validate_answer(category, letter, normalized)` keeps its signature.
--
-- Validation outcome per answer (see `_classify_answer`):
--   empty          -> 'empty'
--   malformed      -> 'invalid'   (too short, junk characters, "aaaa")
--   dictionary hit with the right first letter -> 'valid'
--   dictionary hit, wrong first letter         -> 'invalid' (wrong_letter)
--   first letter does not match (even after folding ç/ş/ğ/ı/ö/ü) -> 'invalid'
--   word known *only* in a clearly incompatible category (e.g. an animal
--     typed into Şehir, see category_conflicts)                  -> 'invalid'
--   anything else -> 'uncertain' -> player vote (never auto-rejected)
-- =====================================================================

-- Spelling-insensitive matching key: Turkish-aware lower case, Turkish and
-- common Latin diacritics folded to ASCII, everything except a-z/0-9 removed.
--   'İstanbul' = 'istanbul' = 'Istanbul' = 'ISTANBUL'   -> 'istanbul'
--   'Coca-Cola' = 'coca cola' = 'CocaCola'               -> 'cocacola'
--   'Şanlıurfa' = 'sanliurfa'                            -> 'sanliurfa'
create or replace function public.dict_fold(p text)
returns text language sql immutable parallel safe as $$
    select translate(lower(public.normalize_answer(p)),
        'çğıöşüâîûéèêëáàäãåíìïóòôõúùñýÿœæß',
        'cgiosuaiueeeeaaaaaiiioooouunyyoas');
$$;

create or replace function public.dict_key(p text)
returns text language sql immutable parallel safe as $$
    select regexp_replace(public.dict_fold(p), '[^a-z0-9]', '', 'g');
$$;

-- First letter of a word in Turkish terms (İ/i, I/ı distinguished).
create or replace function public.dict_first_letter(p text)
returns text language sql immutable parallel safe as $$
    select left(regexp_replace(lower(public.normalize_answer(p)), '^[^[:alnum:]çğıöşüâîû]+', ''), 1);
$$;

create table public.dictionary_versions (
    version     int primary key,
    released_at timestamptz not null default now(),
    source      text not null,
    notes       text
);

create table public.dictionary_entries (
    id            bigint generated always as identity primary key,
    category_key  text not null references public.categories(key) on delete cascade,
    canonical     text not null check (char_length(canonical) between 1 and 60),
    normalized    text not null,
    match_key     text not null check (match_key <> ''),
    first_letter  text not null,
    source        text not null,              -- 'loopa_v1' | 'wikidata:Q…' | 'curated'
    version_added int  not null references public.dictionary_versions(version),
    retired_in    int  references public.dictionary_versions(version),
    unique (category_key, normalized)
);
create index dictionary_entries_lookup_idx on public.dictionary_entries(category_key, match_key) where retired_in is null;
create index dictionary_entries_key_idx    on public.dictionary_entries(match_key) where retired_in is null;

create table public.dictionary_aliases (
    entry_id      bigint not null references public.dictionary_entries(id) on delete cascade,
    alias         text not null,
    match_key     text not null check (match_key <> ''),
    first_letter  text not null,
    version_added int  not null references public.dictionary_versions(version),
    primary key (entry_id, match_key)
);
create index dictionary_aliases_key_idx on public.dictionary_aliases(match_key);

-- Words known in `conflicting_key` but not in `category_key` are rejected
-- automatically. Only clearly disjoint pairs are listed: names, titles and
-- brands are never used as evidence because they reuse ordinary words.
create table public.category_conflicts (
    category_key    text not null references public.categories(key) on delete cascade,
    conflicting_key text not null references public.categories(key) on delete cascade,
    primary key (category_key, conflicting_key)
);

insert into public.category_conflicts(category_key, conflicting_key) values
    ('sehir','hayvan'), ('sehir','bitki'), ('sehir','esya'), ('sehir','meslek'), ('sehir','yemek'),
    ('ulke','hayvan'), ('ulke','bitki'), ('ulke','esya'), ('ulke','meslek'), ('ulke','yemek'),
    ('hayvan','sehir'), ('hayvan','ulke'), ('hayvan','bitki'), ('hayvan','esya'), ('hayvan','meslek'),
    ('bitki','sehir'), ('bitki','ulke'), ('bitki','hayvan'), ('bitki','esya'), ('bitki','meslek'),
    ('esya','sehir'), ('esya','ulke'), ('esya','hayvan'), ('esya','bitki'), ('esya','meslek'),
    ('meslek','sehir'), ('meslek','ulke'), ('meslek','hayvan'), ('meslek','bitki'), ('meslek','esya'), ('meslek','yemek'),
    ('yemek','sehir'), ('yemek','ulke'), ('yemek','esya'), ('yemek','meslek');

alter table public.dictionary_versions enable row level security;
alter table public.dictionary_entries  enable row level security;
alter table public.dictionary_aliases  enable row level security;
alter table public.category_conflicts  enable row level security;
-- No policies: the dictionary is server-side only (clients must not be able to look words up mid-round).
revoke all on public.dictionary_versions, public.dictionary_entries, public.dictionary_aliases, public.category_conflicts from anon, authenticated;

-- New, nullable answer metadata.
alter table public.answers
    add column if not exists validation_reason text,
    add column if not exists entry_id bigint references public.dictionary_entries(id) on delete set null;

-- ---------------------------------------------------------------------
-- Loading helpers (used by data migrations; not callable by clients)
-- ---------------------------------------------------------------------
-- Adds one entry (idempotent) and its aliases. Returns the entry id.
create or replace function public._dict_add(p_version int, p_category text, p_canonical text, p_aliases text[], p_source text)
returns bigint language plpgsql security definer set search_path = public, pg_temp as $$
declare v_id bigint; v_norm text := public.normalize_answer(p_canonical); a text;
begin
    if v_norm = '' or public.dict_key(p_canonical) = '' then return null; end if;
    -- Same matching key already known in this category (e.g. 'Istanbul' vs 'İstanbul'):
    -- reuse that entry so one word never splits into two duplicate groups.
    select id into v_id from public.dictionary_entries
     where category_key = p_category and match_key = public.dict_key(p_canonical) and retired_in is null
     order by id limit 1;
    if v_id is null then
    insert into public.dictionary_entries(category_key, canonical, normalized, match_key, first_letter, source, version_added)
    values (p_category, btrim(p_canonical), v_norm, public.dict_key(p_canonical), public.dict_first_letter(p_canonical), p_source, p_version)
    on conflict (category_key, normalized) do nothing
    returning id into v_id;
    if v_id is null then
        select id into v_id from public.dictionary_entries where category_key = p_category and normalized = v_norm;
    end if;
    end if;
    foreach a in array coalesce(p_aliases, '{}') loop
        continue when public.dict_key(a) = '' or public.dict_key(a) = public.dict_key(p_canonical);
        insert into public.dictionary_aliases(entry_id, alias, match_key, first_letter, version_added)
        values (v_id, btrim(a), public.dict_key(a), public.dict_first_letter(a), p_version)
        on conflict do nothing;
    end loop;
    return v_id;
end $$;

-- Bulk loader: one line per entry, "canonical<TAB>alias1|alias2<TAB>source".
create or replace function public._dict_load(p_version int, p_category text, p_tsv text)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
declare line text; f text[]; n int := 0;
begin
    for line in select * from regexp_split_to_table(p_tsv, E'\n') loop
        continue when btrim(line) = '' or left(line, 1) = '#';
        f := string_to_array(line, E'\t');
        perform public._dict_add(p_version, p_category, f[1],
            case when coalesce(f[2], '') = '' then '{}'::text[] else string_to_array(f[2], '|') end,
            coalesce(nullif(f[3], ''), 'curated'));
        n := n + 1;
    end loop;
    return n;
end $$;

-- A standalone entry whose key is also an alias of another entry in the same
-- category (e.g. v1 had 'ABD' and 'Amerika Birleşik Devletleri' as separate words)
-- is retired, so both spellings resolve to ONE entry and count as duplicates.
create or replace function public._dict_consolidate(p_version int)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
declare n int;
begin
    update public.dictionary_entries s set retired_in = p_version
      from public.dictionary_aliases a join public.dictionary_entries owner on owner.id = a.entry_id
     where s.retired_in is null and owner.retired_in is null
       and s.category_key = owner.category_key and s.id <> owner.id and s.match_key = a.match_key;
    get diagnostics n = row_count;
    return n;
end $$;

-- ---------------------------------------------------------------------
-- Version 1: everything that was in word_dictionary
-- ---------------------------------------------------------------------
insert into public.dictionary_versions(version, source, notes)
values (1, 'loopa_v1', 'Initial hand-curated list (migrated from word_dictionary)')
on conflict do nothing;

insert into public.dictionary_entries(category_key, canonical, normalized, match_key, first_letter, source, version_added)
select category_key, word, word, public.dict_key(word), public.dict_first_letter(word), 'loopa_v1', 1
from public.word_dictionary
where public.dict_key(word) <> ''
on conflict (category_key, normalized) do nothing;

-- ---------------------------------------------------------------------
-- Classification
-- ---------------------------------------------------------------------
create or replace function public._classify_answer(p_category text, p_letter text, p_text text,
    out validation text, out reason text, out entry_id bigint)
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
    v_norm   text := public.normalize_answer(p_text);
    v_key    text := public.dict_key(p_text);
    v_letter text := public.dict_first_letter(p_letter);
    hit record;
    any_hit boolean := false;
begin
    if v_norm = '' then validation := 'empty'; reason := 'empty'; return; end if;
    if char_length(v_key) < 2
       or v_norm !~ '^[[:alnum:]çğıöşüâîûéèêëáàäãåíìïóòôõúùñýÿ .''&:,!?-]+$'
       or v_key ~ '^(.)\1+$' then
        validation := 'invalid'; reason := 'malformed'; return;
    end if;

    -- 1) dictionary entries / aliases of this category
    for hit in
        select e.id, (e.first_letter = v_letter) as letter_ok, 0 as pref
          from public.dictionary_entries e
         where e.category_key = p_category and e.match_key = v_key and e.retired_in is null
        union all
        select e.id, (a.first_letter = v_letter), 1
          from public.dictionary_aliases a join public.dictionary_entries e on e.id = a.entry_id
         where e.category_key = p_category and a.match_key = v_key and e.retired_in is null
        order by 2 desc, 3, 1
    loop
        any_hit := true;
        if hit.letter_ok then
            validation := 'valid'; reason := 'dictionary'; entry_id := hit.id; return;
        end if;
    end loop;
    if any_hit then validation := 'invalid'; reason := 'wrong_letter'; return; end if;

    -- 2) letter check, tolerant to missing Turkish keyboard characters (ş->s, ı->i …)
    if public.dict_fold(left(v_norm, 1)) <> public.dict_fold(v_letter) then
        validation := 'invalid'; reason := 'wrong_letter'; return;
    end if;

    -- 3) known only in a clearly incompatible category
    if exists (
        select 1 from public.category_conflicts cc
          join public.dictionary_entries e on e.category_key = cc.conflicting_key and e.retired_in is null
         where cc.category_key = p_category and e.match_key = v_key
        union all
        select 1 from public.category_conflicts cc
          join public.dictionary_entries e on e.category_key = cc.conflicting_key and e.retired_in is null
          join public.dictionary_aliases a on a.entry_id = e.id
         where cc.category_key = p_category and a.match_key = v_key
    ) then
        validation := 'invalid'; reason := 'wrong_category'; return;
    end if;

    -- 4) genuinely unknown -> players decide
    validation := 'uncertain'; reason := 'not_found';
end $$;

-- Backward-compatible wrapper (old signature).
create or replace function public._validate_answer(p_category text, p_letter text, p_norm text)
returns text language sql stable security definer set search_path = public, pg_temp as $$
    select (public._classify_answer(p_category, p_letter, p_norm)).validation;
$$;

-- Public, harmless metadata (no words): current version and sizes.
create or replace function public.dictionary_info()
returns jsonb language sql stable security definer set search_path = public, pg_temp as $$
    select jsonb_build_object(
        'version', (select max(version) from public.dictionary_versions),
        'entries', (select count(*) from public.dictionary_entries where retired_in is null),
        'aliases', (select count(*) from public.dictionary_aliases),
        'per_category', (select jsonb_object_agg(category_key, n) from (
            select category_key, count(*) n from public.dictionary_entries where retired_in is null group by 1) s)
    );
$$;

-- ---------------------------------------------------------------------
-- Engine updates: store reason + entry, group duplicates by dictionary entry
-- ---------------------------------------------------------------------
create or replace function public._close_answering(p_round_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare rd public.rounds; c jsonb; rp record; n_uncertain int; vote_secs int;
begin
    select * into rd from public.rounds where id = p_round_id for update;
    if rd.phase <> 'answering' then return; end if;

    for rp in select user_id from public.round_players where round_id = p_round_id loop
        for c in select * from jsonb_array_elements(rd.categories) loop
            insert into public.answers(round_id, room_id, user_id, category_key)
            values (p_round_id, rd.room_id, rp.user_id, c->>'key')
            on conflict do nothing;
        end loop;
    end loop;

    update public.answers a set validation = r.validation, validation_reason = r.reason, entry_id = r.entry_id
      from (select a2.id, (public._classify_answer(a2.category_key, rd.letter, a2.raw_text)).*
              from public.answers a2 where a2.round_id = p_round_id) r
     where a.id = r.id;
    update public.answers set final_status = case validation when 'valid' then 'accepted'
                                                             when 'empty' then 'empty'
                                                             when 'invalid' then 'rejected' end
     where round_id = p_round_id and validation <> 'uncertain';
    update public.answers a set final_status = 'accepted', validation_reason = 'no_voters'
     where a.round_id = p_round_id and a.validation = 'uncertain'
       and public._eligible_voters(p_round_id, a.user_id) = 0;
    update public.round_players set submitted_at = coalesce(submitted_at, deadline_at) where round_id = p_round_id;

    select count(*) into n_uncertain from public.answers where round_id = p_round_id and final_status is null;
    select coalesce((m.settings->>'vote_seconds')::int, 20) into vote_secs from public.matches m where m.id = rd.match_id;

    if n_uncertain > 0 then
        update public.rounds set phase = 'voting', answering_closed_at = now(),
               voting_deadline = now() + make_interval(secs => vote_secs)
         where id = p_round_id;
    else
        update public.rounds set answering_closed_at = now() where id = p_round_id;
        perform public._score_round(p_round_id);
    end if;
end $$;

-- Two answers are "the same" when they hit the same dictionary entry
-- (e.g. 'ABD' and 'Amerika Birleşik Devletleri'); otherwise when their
-- normalized text is identical. Similar-but-different spellings are not merged.
create or replace function public._answer_group_key(p_entry_id bigint, p_normalized text)
returns text language sql immutable as $$
    select coalesce('e' || p_entry_id::text, 'n' || p_normalized);
$$;

create or replace function public._score_round(p_round_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare rd public.rounds; best int; w record; n_players int; n_cats int; reveal_secs numeric;
begin
    select * into rd from public.rounds where id = p_round_id for update;
    if rd.scored then return; end if;
    perform public._resolve_votes(p_round_id);

    update public.answers a set
        is_duplicate = d.cnt > 1,
        points = case when a.final_status = 'accepted' then (case when d.cnt > 1 then 10 else 20 end) else 0 end
      from (select category_key, public._answer_group_key(entry_id, normalized) as gk, count(*) as cnt
              from public.answers where round_id = p_round_id and final_status = 'accepted'
             group by 1, 2) d
     where a.round_id = p_round_id and a.final_status = 'accepted'
       and a.category_key = d.category_key and public._answer_group_key(a.entry_id, a.normalized) = d.gk;
    update public.answers set points = 0, is_duplicate = false
     where round_id = p_round_id and final_status <> 'accepted';

    update public.answers a set points = a.points * 2, doubled = true
      from public.round_players rpl
     where a.round_id = p_round_id and rpl.round_id = p_round_id and rpl.user_id = a.user_id
       and rpl.double_category = a.category_key and a.points > 0;

    update public.round_players rpl set points = coalesce(s.total, 0) + rpl.early_bonus
      from (select user_id, sum(points)::int as total from public.answers where round_id = p_round_id group by user_id) s
     where rpl.round_id = p_round_id and s.user_id = rpl.user_id;

    select max(points) into best from public.round_players where round_id = p_round_id;
    if best > 0 then
        update public.round_players set is_round_winner = true where round_id = p_round_id and points = best;
    end if;

    update public.match_players mp set total_points = mp.total_points + rpl.points,
           round_wins = mp.round_wins + (case when rpl.is_round_winner then 1 else 0 end)
      from public.round_players rpl
     where rpl.round_id = p_round_id and mp.match_id = rd.match_id and mp.user_id = rpl.user_id;

    update public.player_statistics ps set
           total_points = ps.total_points + rpl.points,
           round_wins = ps.round_wins + (case when rpl.is_round_winner then 1 else 0 end),
           early_bonuses = ps.early_bonuses + (case when rpl.early_bonus > 0 then 1 else 0 end),
           updated_at = now()
      from public.round_players rpl where rpl.round_id = p_round_id and ps.user_id = rpl.user_id;

    for w in select user_id from public.round_players where round_id = p_round_id and is_round_winner loop
        perform public._award_xp(w.user_id, 30, 'round_win', 'round_win:' || p_round_id, rd.match_id);
        perform public._grant_joker(rd.match_id, w.user_id, 'round_win', p_round_id);
    end loop;

    select count(*) into n_players from public.round_players where round_id = p_round_id;
    n_cats := jsonb_array_length(rd.categories);
    reveal_secs := least(n_cats * (2.5 + 0.7 * n_players), 150) + 1.5;
    update public.rounds set scored = true, phase = 'reveal', reveal_started_at = now(),
           reveal_deadline = now() + make_interval(secs => reveal_secs)
     where id = p_round_id;
end $$;

-- Snapshot now also carries the validation reason and duplicate group key.
create or replace function public.get_room_state(p_room_id uuid)
returns jsonb language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
    u uuid := public._uid(); r public.rooms; me public.room_players; m public.matches; rd public.rounds;
    res jsonb; round_json jsonb := null; match_json jsonb := null;
begin
    select * into me from public.room_players where room_id = p_room_id and user_id = u;
    if not found then raise exception 'LOOPA:NOT_IN_ROOM'; end if;
    select * into r from public.rooms where id = p_room_id;

    res := jsonb_build_object(
        'server_time', now(),
        'me', jsonb_build_object('user_id', u, 'role', me.role, 'status', me.status),
        'room', jsonb_build_object('id', r.id, 'code', r.code, 'host_id', r.host_id, 'status', r.status,
                                   'is_public', r.is_public, 'settings', r.settings,
                                   'current_match_id', r.current_match_id, 'last_match_id', r.last_match_id),
        'players', coalesce((select jsonb_agg(jsonb_build_object(
                'user_id', rp.user_id, 'username', p.username, 'avatar', p.avatar, 'level', p.level,
                'role', rp.role, 'is_ready', rp.is_ready, 'is_host', rp.user_id = r.host_id,
                'online', rp.last_seen_at > now() - interval '25 seconds', 'joined_at', rp.joined_at)
                order by rp.joined_at)
            from public.room_players rp join public.profiles p on p.id = rp.user_id
            where rp.room_id = p_room_id and rp.status = 'active'), '[]'::jsonb)
    );

    if me.status <> 'active' then return res; end if;

    if r.current_match_id is not null then
        select * into m from public.matches where id = r.current_match_id;
        match_json := jsonb_build_object(
            'id', m.id, 'status', m.status, 'total_rounds', m.total_rounds, 'current_round', m.current_round,
            'settings', m.settings,
            'scoreboard', coalesce((select jsonb_agg(jsonb_build_object(
                    'user_id', mp.user_id, 'username', p.username, 'avatar', p.avatar,
                    'total_points', mp.total_points, 'round_wins', mp.round_wins,
                    'jokers', public._active_jokers(m.id, mp.user_id))
                    order by mp.total_points desc, mp.round_wins desc)
                from public.match_players mp join public.profiles p on p.id = mp.user_id
                where mp.match_id = m.id), '[]'::jsonb),
            'my_jokers', coalesce((select jsonb_agg(jsonb_build_object('id', id, 'type', joker_type, 'source', source, 'source_round_id', source_round_id) order by acquired_at)
                from public.player_jokers where match_id = m.id and user_id = u and status = 'available'), '[]'::jsonb)
        );

        select * into rd from public.rounds where match_id = m.id order by round_number desc limit 1;
        if found then
            round_json := jsonb_build_object(
                'id', rd.id, 'number', rd.round_number, 'letter', rd.letter, 'categories', rd.categories,
                'phase', rd.phase, 'started_at', rd.started_at, 'base_deadline', rd.base_deadline,
                'voting_deadline', rd.voting_deadline, 'reveal_started_at', rd.reveal_started_at,
                'reveal_deadline', rd.reveal_deadline, 'results_deadline', rd.results_deadline,
                'first_submitter_id', rd.first_submitter_id,
                'players', coalesce((select jsonb_agg(jsonb_build_object(
                        'user_id', rpl.user_id, 'deadline_at', rpl.deadline_at,
                        'submitted', rpl.submitted_at is not null, 'submitted_at', rpl.submitted_at,
                        'points', case when rd.scored then rpl.points end,
                        'early_bonus', rpl.early_bonus,
                        'is_round_winner', rpl.is_round_winner,
                        'double_category', case when rpl.user_id = u or rd.phase <> 'answering' then rpl.double_category end,
                        'joker_used', exists (select 1 from public.joker_usage ju where ju.round_id = rd.id and ju.user_id = rpl.user_id)))
                    from public.round_players rpl where rpl.round_id = rd.id), '[]'::jsonb),
                'joker_events', coalesce((select jsonb_agg(jsonb_build_object(
                        'id', ju.id, 'user_id', ju.user_id, 'type', ju.joker_type, 'target_user_id', ju.target_user_id,
                        'seconds', ju.seconds_delta, 'created_at', ju.created_at,
                        'category_key', case when ju.user_id = u or rd.phase <> 'answering' then ju.category_key end)
                        order by ju.created_at)
                    from public.joker_usage ju where ju.round_id = rd.id), '[]'::jsonb),
                'answers', coalesce((select jsonb_agg(jsonb_build_object(
                        'id', a.id, 'user_id', a.user_id, 'category_key', a.category_key,
                        'text', a.raw_text, 'normalized', a.normalized,
                        'validation', a.validation, 'final_status', a.final_status,
                        'reason', case when rd.phase <> 'answering' then a.validation_reason end,
                        'group_key', case when rd.phase <> 'answering' then public._answer_group_key(a.entry_id, a.normalized) end,
                        'is_duplicate', a.is_duplicate, 'points', a.points, 'doubled', a.doubled,
                        'my_vote', (select av.accept from public.answer_votes av where av.answer_id = a.id and av.voter_id = u),
                        'votes_cast', (select count(*) from public.answer_votes av where av.answer_id = a.id),
                        'votes_needed', case when a.validation = 'uncertain' then public._eligible_voters(rd.id, a.user_id) end,
                        'votes_accept', case when rd.phase not in ('answering','voting')
                            then (select count(*) from public.answer_votes av where av.answer_id = a.id and av.accept) end,
                        'votes_reject', case when rd.phase not in ('answering','voting')
                            then (select count(*) from public.answer_votes av where av.answer_id = a.id and not av.accept) end))
                    from public.answers a
                    where a.round_id = rd.id and (a.user_id = u or rd.phase <> 'answering')), '[]'::jsonb)
            );
        end if;
    end if;

    return res || jsonb_build_object('match', match_json, 'round', round_json);
end $$;


-- Grants for new functions: internal helpers stay private.
do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig, p.proname
               from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and p.proname in
                ('dict_fold','dict_key','dict_first_letter','_dict_add','_dict_load','_dict_consolidate','_classify_answer',
                 '_validate_answer','dictionary_info','_close_answering','_score_round','_answer_group_key','get_room_state') loop
        execute format('revoke all on function %s from public, anon', f.sig);
        if left(f.proname, 1) = '_' or f.proname like 'dict\_%' then
            execute format('revoke all on function %s from authenticated', f.sig);
        else
            execute format('grant execute on function %s to authenticated', f.sig);
        end if;
    end loop;
end $$;
