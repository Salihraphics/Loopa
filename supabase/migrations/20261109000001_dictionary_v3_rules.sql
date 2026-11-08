-- =====================================================================
-- LOOPA — Dictionary v3 rules (backward compatible: new table, replaced functions)
--
-- 1. Letter check on the TYPED answer. Dictionary spellings starting with an
--    undotted capital "I" are ambiguous (Wikidata "Irfan" vs Turkish "İrfan"),
--    so they are accepted for both İ and I. Real Turkish letters still matter:
--    "Şanlıurfa" is not an S word, "sanliurfa" typed for Ş is fine.
-- 2. word_suggestions: every vote on an unknown word becomes evidence that a
--    curator can turn into a dictionary entry (_approve_word_suggestion).
-- 3. Controlled fallback: when nobody can vote, an unknown word is accepted only
--    with prior community evidence (>= 2 accepted votes, more accepts than
--    rejects); otherwise it is rejected. Made-up words are never auto-accepted.
-- =====================================================================

-- t = first letter typed, l = round letter, e = first letter of the dictionary spelling, src = that spelling
-- The answer must start with the round letter (ş/s, ı/i … may be typed without the Turkish
-- keyboard). If the dictionary spelling is genuinely Turkish ("Şanlıurfa", "Özcan"), its letter
-- decides. If it is a plain ASCII spelling from a foreign source ("Irfan", "Ozcan"), the
-- Turkish letter the player actually typed decides.
create or replace function public._letter_ok(t text, l text, e text, src text)
returns boolean language sql immutable parallel safe as $$
    select public.dict_fold(t) = public.dict_fold(l)
       and (e = l or (t = l and left(btrim(src), 1) ~ '^[A-Za-z]'));
$$;

-- Turkish compound dishes: "<modifier> <dish>" (Etli bamya, Kıymalı pide, Fıstıklı baklava).
create table if not exists public.dictionary_modifiers (
    category_key text not null references public.categories(key) on delete cascade,
    word_key     text not null,
    primary key (category_key, word_key)
);
alter table public.dictionary_modifiers enable row level security;
revoke all on public.dictionary_modifiers from anon, authenticated;
insert into public.dictionary_modifiers(category_key, word_key)
select 'yemek', public.dict_key(w) from unnest(array[
    'etli','kıymalı','kuşbaşılı','tavuklu','balıklı','peynirli','kaşarlı','beyaz peynirli','lorlu','sucuklu','pastırmalı',
    'patatesli','ıspanaklı','mantarlı','sebzeli','domatesli','biberli','patlıcanlı','kabaklı','lahanalı','pırasalı','soğanlı',
    'nohutlu','bulgurlu','pirinçli','mercimekli','fasulyeli','yoğurtlu','sütlü','kremalı','kaymaklı','tereyağlı','zeytinyağlı',
    'fıstıklı','cevizli','fındıklı','bademli','çikolatalı','kakaolu','vişneli','çilekli','muzlu','elmalı','üzümlü','haşhaşlı',
    'susamlı','tahinli','pekmezli','ballı','şekerli','acılı','acısız','soslu','salçalı','baharatlı','ekşili','sarımsaklı',
    'fırın','fırında','ızgara','közlenmiş','köz','kızarmış','haşlanmış','kavrulmuş','buğulama','tava','sote',
    'kuzu','dana','tavuk','hindi','balık','et','sebze','ev','köy','yayla','sade','karışık','çıtır','sıcak','soğuk',
    'adana','urfa','antep','bursa','kayseri','tokat','trabzon','karadeniz','ege'
]) w
on conflict do nothing;

create table if not exists public.word_suggestions (
    id              bigint generated always as identity primary key,
    category_key    text not null,
    match_key       text not null,
    sample_text     text not null,
    times_seen      int  not null default 0,
    times_accepted  int  not null default 0,
    times_rejected  int  not null default 0,
    status          text not null default 'pending' check (status in ('pending','approved','rejected','ignored')),
    entry_id        bigint references public.dictionary_entries(id) on delete set null,
    review_note     text,
    first_seen_at   timestamptz not null default now(),
    last_seen_at    timestamptz not null default now(),
    reviewed_at     timestamptz,
    unique (category_key, match_key)
);
create table if not exists public.word_suggestion_events (
    answer_id     uuid primary key references public.answers(id) on delete cascade,  -- idempotent per answer
    suggestion_id bigint not null references public.word_suggestions(id) on delete cascade,
    accepted      boolean not null,
    votes_accept  int not null,
    votes_reject  int not null,
    created_at    timestamptz not null default now()
);
alter table public.word_suggestions enable row level security;
alter table public.word_suggestion_events enable row level security;
revoke all on public.word_suggestions, public.word_suggestion_events from anon, authenticated;

-- Review queue for curators (server-side only).
create or replace view public.word_suggestion_queue with (security_invoker = true) as
select s.*, round(100.0 * s.times_accepted / nullif(s.times_accepted + s.times_rejected, 0)) as accept_pct
  from public.word_suggestions s
 where s.status = 'pending'
 order by s.times_accepted desc, s.last_seen_at desc;
revoke all on public.word_suggestion_queue from anon, authenticated;

create or replace function public._community_accepts(p_category text, p_key text)
returns boolean language sql stable security definer set search_path = public, pg_temp as $$
    select exists (select 1 from public.word_suggestions
                    where category_key = p_category and match_key = p_key and status <> 'rejected'
                      and times_accepted >= 2 and times_accepted > times_rejected);
$$;

-- Records the outcome of resolved votes as suggestion evidence (custom categories excluded).
create or replace function public._record_vote_outcomes(p_round_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
declare a record; sid bigint;
begin
    for a in select x.id, x.category_key, x.raw_text, x.final_status,
                    (select count(*) from public.answer_votes v where v.answer_id = x.id and v.accept) yes,
                    (select count(*) from public.answer_votes v where v.answer_id = x.id and not v.accept) no
               from public.answers x
              where x.round_id = p_round_id and x.validation = 'uncertain'
                and x.final_status in ('accepted','rejected') and x.category_key not like 'custom\_%'
                and public.dict_key(x.raw_text) <> '' loop
        insert into public.word_suggestions(category_key, match_key, sample_text)
        values (a.category_key, public.dict_key(a.raw_text), a.raw_text)
        on conflict (category_key, match_key) do update set sample_text = excluded.sample_text, last_seen_at = now()
        returning id into sid;
        insert into public.word_suggestion_events(answer_id, suggestion_id, accepted, votes_accept, votes_reject)
        values (a.id, sid, a.final_status = 'accepted', a.yes, a.no)
        on conflict (answer_id) do nothing;
        if found then
            update public.word_suggestions set times_seen = times_seen + 1,
                   times_accepted = times_accepted + (case when a.final_status = 'accepted' then 1 else 0 end),
                   times_rejected = times_rejected + (case when a.final_status = 'rejected' then 1 else 0 end)
             where id = sid;
        end if;
    end loop;
end $$;

-- Majority of cast votes (ties, including 0-0, reject) — unchanged — then record evidence.
create or replace function public._resolve_votes(p_round_id uuid)
returns void language plpgsql security definer set search_path = public, pg_temp as $$
begin
    update public.answers a set final_status = case
            when coalesce(v.yes, 0) > coalesce(v.no, 0) then 'accepted' else 'rejected' end
      from (select a2.id,
                   (select count(*) from public.answer_votes av where av.answer_id = a2.id and av.accept) as yes,
                   (select count(*) from public.answer_votes av where av.answer_id = a2.id and not av.accept) as no
              from public.answers a2 where a2.round_id = p_round_id and a2.final_status is null) v
     where a.id = v.id;
    perform public._record_vote_outcomes(p_round_id);
end $$;

-- Curator action: turn a suggestion into a dictionary entry (new version) or reject it.
create or replace function public._approve_word_suggestion(p_id bigint, p_version int, p_canonical text default null, p_note text default null)
returns bigint language plpgsql security definer set search_path = public, pg_temp as $$
declare s public.word_suggestions; v_entry bigint;
begin
    select * into s from public.word_suggestions where id = p_id for update;
    if not found then raise exception 'suggestion % not found', p_id; end if;
    insert into public.dictionary_versions(version, source, notes) values (p_version, 'suggestions', 'approved player suggestions')
    on conflict do nothing;
    v_entry := public._dict_add(p_version, s.category_key, coalesce(p_canonical, s.sample_text), '{}', 'suggestion:' || p_id);
    update public.word_suggestions set status = 'approved', entry_id = v_entry, reviewed_at = now(), review_note = p_note where id = p_id;
    return v_entry;
end $$;

create or replace function public._reject_word_suggestion(p_id bigint, p_note text default null)
returns void language sql security definer set search_path = public, pg_temp as $$
    update public.word_suggestions set status = 'rejected', reviewed_at = now(), review_note = p_note where id = p_id;
$$;

create or replace function public._classify_answer(p_category text, p_letter text, p_text text,
    out validation text, out reason text, out entry_id bigint)
language plpgsql stable security definer set search_path = public, pg_temp as $$
declare
    v_norm   text := public.normalize_answer(p_text);
    v_key    text := public.dict_key(p_text);
    v_letter text := public.dict_first_letter(p_letter);
    v_typed  text := public.dict_first_letter(p_text);
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
        select e.id, public._letter_ok(v_typed, v_letter, e.first_letter, e.canonical) as letter_ok, 0 as pref
          from public.dictionary_entries e
         where e.category_key = p_category and e.match_key = v_key and e.retired_in is null
        union all
        select e.id, public._letter_ok(v_typed, v_letter, a.first_letter, a.alias), 1
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
    if public.dict_fold(v_typed) <> public.dict_fold(v_letter) then
        validation := 'invalid'; reason := 'wrong_letter'; return;
    end if;

    -- 2b) Turkish compound dish: modifiers + a known dish ("Kıymalı pide", "Etli bamya")
    if p_category = 'yemek' and v_norm ~ ' ' then
        declare words text[] := string_to_array(v_norm, ' '); k int; base text; ok boolean;
        begin
            for k in 2..array_length(words, 1) loop
                base := public.dict_key(array_to_string(words[k:], ' '));
                ok := (select bool_and(exists (select 1 from public.dictionary_modifiers m
                                                where m.category_key = 'yemek' and m.word_key = public.dict_key(w)))
                         from unnest(words[1:k-1]) w);
                if ok and exists (select 1 from public.dictionary_entries e
                                   where e.category_key = 'yemek' and e.retired_in is null and e.match_key = base
                                  union all
                                  select 1 from public.dictionary_aliases a join public.dictionary_entries e on e.id = a.entry_id
                                   where e.category_key = 'yemek' and e.retired_in is null and a.match_key = base) then
                    validation := 'valid'; reason := 'compound'; return;
                end if;
            end loop;
        end;
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
    -- Nobody can vote (everyone else left): accept only with prior community evidence,
    -- otherwise reject. An invented word is never accepted automatically.
    update public.answers a set
        final_status = case when public._community_accepts(a.category_key, public.dict_key(a.raw_text)) then 'accepted' else 'rejected' end,
        validation_reason = case when public._community_accepts(a.category_key, public.dict_key(a.raw_text)) then 'community' else 'no_voters' end
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

-- Grants: everything here is internal.
do $$
declare f record;
begin
    for f in select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid = p.pronamespace
              where n.nspname = 'public' and p.proname in ('_letter_ok','_community_accepts','_record_vote_outcomes','_resolve_votes',
                    '_approve_word_suggestion','_reject_word_suggestion','_classify_answer','_close_answering') loop
        execute format('revoke all on function %s from public, anon, authenticated', f.sig);
    end loop;
end $$;

-- 4. Foods typed into Bitki / Hayvan / Eşya are wrong-category answers ("Tarhana" is not a plant).
--    Shared ingredients (Patates, Hamsi, Tavuk …) are in Bitki/Hayvan too, so they stay valid there.
insert into public.category_conflicts(category_key, conflicting_key) values
    ('bitki','yemek'), ('hayvan','yemek'), ('esya','yemek')
on conflict do nothing;


-- 5. Consolidation fix. v2 could retire BOTH entries of a pair that list each other as
--    alias (e.g. "masa tenisi" <-> "Pinpon"), losing the word. Now: the older entry wins,
--    the newer one is retired and its spellings are moved onto the survivor as aliases.
create or replace function public._dict_consolidate(p_version int)
returns int language plpgsql security definer set search_path = public, pg_temp as $$
declare r record; n int := 0;
begin
    for r in
        select distinct s.id as sid, owner.id as oid
          from public.dictionary_entries s
          join public.dictionary_aliases a on a.match_key = s.match_key
          join public.dictionary_entries owner on owner.id = a.entry_id
         where s.retired_in is null and owner.retired_in is null
           and s.category_key = owner.category_key and s.id <> owner.id
         order by s.id desc
    loop
        -- both may have changed in this loop
        continue when exists (select 1 from public.dictionary_entries where id in (r.sid, r.oid) and retired_in is not null);
        -- mutual aliases: keep the older (lower id) entry
        continue when r.sid < r.oid and exists (
            select 1 from public.dictionary_aliases a2 join public.dictionary_entries o2 on o2.id = r.sid
             where a2.entry_id = r.sid and a2.match_key = (select match_key from public.dictionary_entries where id = r.oid));
        insert into public.dictionary_aliases(entry_id, alias, match_key, first_letter, version_added)
        select r.oid, x.alias, x.match_key, x.first_letter, p_version
          from (select e.canonical as alias, e.match_key, e.first_letter from public.dictionary_entries e where e.id = r.sid
                union all select a.alias, a.match_key, a.first_letter from public.dictionary_aliases a where a.entry_id = r.sid) x
         where x.match_key <> (select match_key from public.dictionary_entries where id = r.oid)
        on conflict do nothing;
        update public.dictionary_entries set retired_in = p_version where id = r.sid;
        n := n + 1;
    end loop;
    return n;
end $$;
revoke all on function public._dict_consolidate(int) from public, anon, authenticated;

-- Repair: undo v2's consolidation/blocklist retirements; the v3 data migration re-applies the
-- blocklist and consolidates again with the fixed rule.
update public.dictionary_entries set retired_in = null where retired_in = 2;
