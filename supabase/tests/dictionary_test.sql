-- =====================================================================
-- Dictionary v2 tests. Read-only (only pg_temp helpers are created), so
-- this file can also be run against the hosted project to verify it.
-- =====================================================================
\set ON_ERROR_STOP 1
set client_min_messages = notice;

create or replace function pg_temp.tr_upper(p text) returns text language sql immutable as $$
    select upper(translate(p, 'abcçdefgğhıijklmnoöpqrsştuüvwxyz', 'ABCÇDEFGĞHIİJKLMNOÖPQRSŞTUÜVWXYZ'));
$$;

create or replace function pg_temp.expect(p_cat text, p_letter text, p_text text, p_validation text, p_reason text default null)
returns void language plpgsql as $$
declare c record;
begin
    c := public._classify_answer(p_cat, p_letter, p_text);
    if c.validation is distinct from p_validation or (p_reason is not null and c.reason is distinct from p_reason) then
        raise exception 'classify(%, %, %) = %/% (expected %/%)', p_cat, p_letter, p_text, c.validation, c.reason, p_validation, coalesce(p_reason, '*');
    end if;
end $$;

create or replace function pg_temp.same_entry(p_cat text, p_letter text, a text, b text) returns void language plpgsql as $$
declare x record; y record;
begin
    x := public._classify_answer(p_cat, p_letter, a);
    y := public._classify_answer(p_cat, p_letter, b);
    if x.entry_id is null or x.entry_id is distinct from y.entry_id then
        raise exception '% and % should be the same % entry (got % / %)', a, b, p_cat, x.entry_id, y.entry_id;
    end if;
    if public._answer_group_key(x.entry_id, public.normalize_answer(a)) <> public._answer_group_key(y.entry_id, public.normalize_answer(b)) then
        raise exception '% and % should be duplicates', a, b;
    end if;
end $$;

do $$
declare
    r record; c record; n int := 0; v int := 0; letters text[]; other text; tested_alias int := 0; tested_wrongcat int := 0; tested_wrongletter int := 0;
begin
    perform setseed(0.4242);

    -- ---------------------------------------------------------------- 1) 600 random v2 entries × 3 spellings
    for r in select e.* from public.dictionary_entries e
              where e.version_added >= 2 and e.retired_in is null
              order by random() limit 600 loop
        -- canonical spelling
        c := public._classify_answer(r.category_key, pg_temp.tr_upper(r.first_letter), r.canonical);
        if c.validation <> 'valid' or (select match_key from public.dictionary_entries where id = c.entry_id) <> r.match_key then
            raise exception 'v2 entry not accepted: % / % -> %/%', r.category_key, r.canonical, c.validation, c.reason;
        end if;
        -- all caps with extra spaces
        c := public._classify_answer(r.category_key, pg_temp.tr_upper(r.first_letter), '  ' || pg_temp.tr_upper(r.canonical) || '  ');
        if c.validation <> 'valid' then raise exception 'upper-case spelling rejected: % (%)', r.canonical, c.reason; end if;
        -- typed without Turkish characters (ş->s, ı->i, ğ->g …)
        c := public._classify_answer(r.category_key, pg_temp.tr_upper(r.first_letter), public.dict_fold(r.canonical));
        if c.validation <> 'valid' then raise exception 'ASCII spelling rejected: % as % (%)', r.canonical, public.dict_fold(r.canonical), c.reason; end if;
        n := n + 1;
    end loop;
    assert n >= 500, 'at least 500 new entries tested, got ' || n;
    raise notice 'dictionary: % new entries accepted in 3 spellings each', n;

    -- ---------------------------------------------------------------- 2) alternative spellings / names
    for r in select a.*, e.category_key, e.match_key as entry_key
               from public.dictionary_aliases a join public.dictionary_entries e on e.id = a.entry_id
              where a.version_added >= 2 and e.retired_in is null
              order by random() limit 250 loop
        c := public._classify_answer(r.category_key, pg_temp.tr_upper(r.first_letter), r.alias);
        if c.validation <> 'valid' then
            raise exception 'alias rejected: % (% -> %/%)', r.alias, r.category_key, c.validation, c.reason;
        end if;
        tested_alias := tested_alias + 1;
    end loop;
    assert tested_alias >= 200, 'aliases tested: ' || tested_alias;

    -- ---------------------------------------------------------------- 3) wrong letter
    letters := array['A','B','C','D','E','F','G','H','K','L','M','N','O','P','R','S','T','U','V','Y','Z'];
    for r in select e.* from public.dictionary_entries e
              where e.version_added >= 2 and e.retired_in is null and e.first_letter ~ '^[a-zçğıöşü]$'
              order by random() limit 200 loop
        select l into other from unnest(letters) l
         where public.dict_fold(lower(l)) <> public.dict_fold(r.first_letter) order by random() limit 1;
        c := public._classify_answer(r.category_key, other, r.canonical);
        -- the word may legitimately also be an alias starting with `other`; that is rare and allowed
        if c.validation = 'valid' and not exists (
            select 1 from public.dictionary_aliases a join public.dictionary_entries e2 on e2.id = a.entry_id
             where e2.category_key = r.category_key and a.match_key = r.match_key) then
            raise exception 'wrong letter accepted: % with letter %', r.canonical, other;
        end if;
        if c.validation = 'invalid' then tested_wrongletter := tested_wrongletter + 1; end if;
    end loop;
    assert tested_wrongletter >= 190, 'wrong-letter rejections: ' || tested_wrongletter;

    -- ---------------------------------------------------------------- 4) wrong category
    for r in select e.* , cc.category_key as target
               from public.category_conflicts cc
               join lateral (select * from public.dictionary_entries e
                              where e.category_key = cc.conflicting_key and e.retired_in is null and e.version_added >= 2
                                and not exists (select 1 from public.dictionary_entries t where t.category_key = cc.category_key and t.match_key = e.match_key)
                                and not exists (select 1 from public.dictionary_aliases a join public.dictionary_entries t on t.id = a.entry_id
                                                 where t.category_key = cc.category_key and a.match_key = e.match_key)
                              order by random() limit 8) e on true loop
        c := public._classify_answer(r.target, pg_temp.tr_upper(r.first_letter), r.canonical);
        if c.validation <> 'invalid' or c.reason <> 'wrong_category' then
            raise exception 'wrong category not rejected: % (%) typed into % -> %/%', r.canonical, r.category_key, r.target, c.validation, c.reason;
        end if;
        tested_wrongcat := tested_wrongcat + 1;
    end loop;
    assert tested_wrongcat >= 200, 'wrong-category cases: ' || tested_wrongcat;

    -- proper-noun categories never auto-reject on another category's evidence (names reuse ordinary words)
    for r in select e.* from public.dictionary_entries e
              where e.category_key = 'hayvan' and e.version_added >= 2 and e.retired_in is null
                and not exists (select 1 from public.dictionary_entries t where t.category_key = 'unlu' and t.match_key = e.match_key)
              order by random() limit 50 loop
        c := public._classify_answer('unlu', pg_temp.tr_upper(r.first_letter), r.canonical);
        if c.validation <> 'uncertain' then raise exception 'unlu should vote on %, got %/%', r.canonical, c.validation, c.reason; end if;
    end loop;

    raise notice 'dictionary: % aliases, % wrong-letter and % wrong-category cases verified', tested_alias, tested_wrongletter, tested_wrongcat;
end $$;

-- ------------------------------------------------------------------- 5) explicit Turkish cases
select pg_temp.expect('sehir', 'İ', 'istanbul', 'valid', 'dictionary');
select pg_temp.expect('sehir', 'İ', 'ISTANBUL', 'valid', 'dictionary');       -- capital I without dot
select pg_temp.expect('sehir', 'İ', '  İstanbul  ', 'valid', 'dictionary');
select pg_temp.same_entry('sehir', 'İ', 'İstanbul', 'Istanbul');
select pg_temp.expect('ulke', 'I', 'Irak', 'valid', 'dictionary');
select pg_temp.expect('ulke', 'I', 'ırak', 'valid', 'dictionary');
select pg_temp.expect('ulke', 'I', 'IRAK', 'valid', 'dictionary');
select pg_temp.expect('sehir', 'Ş', 'sanliurfa', 'valid', 'dictionary');      -- no Turkish keyboard
select pg_temp.expect('sehir', 'Ş', 'ŞANLIURFA', 'valid', 'dictionary');
select pg_temp.expect('sehir', 'S', 'Şanlıurfa', 'invalid', 'wrong_letter');  -- Ş is not S
select pg_temp.expect('sehir', 'U', 'Urfa', 'valid', 'dictionary');           -- alias with its own letter
select pg_temp.same_entry('sehir', 'Ş', 'Şanlıurfa', 'sanlıurfa');
select pg_temp.expect('sehir', 'A', 'Antep', 'valid', 'dictionary');
select pg_temp.expect('sehir', 'G', 'Antep', 'invalid', 'wrong_letter');
select pg_temp.expect('sehir', 'Ç', 'Çanakkale', 'valid', 'dictionary');
select pg_temp.expect('sehir', 'Ç', 'canakkale', 'valid', 'dictionary');
select pg_temp.expect('sehir', 'C', 'Çanakkale', 'invalid', 'wrong_letter');
select pg_temp.expect('ulke', 'A', 'ABD', 'valid', 'dictionary');
select pg_temp.expect('ulke', 'A', 'Amerika', 'valid', 'dictionary');
select pg_temp.expect('ulke', 'A', 'amerika birleşik devletleri', 'valid', 'dictionary');
select pg_temp.expect('ulke', 'İ', 'İngiltere', 'valid', 'dictionary');
select pg_temp.expect('ulke', 'B', 'Birleşik Krallık', 'valid', 'dictionary');
select pg_temp.expect('ulke', 'Ü', 'Ürdün', 'valid', 'dictionary');
select pg_temp.expect('ulke', 'Ü', 'URDUN', 'valid', 'dictionary');
select pg_temp.expect('ulke', 'Ö', 'Özbekistan', 'valid', 'dictionary');
select pg_temp.expect('marka', 'C', 'Coca-Cola', 'valid', 'dictionary');
select pg_temp.expect('marka', 'C', 'coca cola', 'valid', 'dictionary');
select pg_temp.expect('marka', 'C', 'COCACOLA', 'valid', 'dictionary');
select pg_temp.same_entry('marka', 'C', 'Coca-Cola', 'cocacola');
select pg_temp.expect('marka', 'T', 'THY', 'valid', 'dictionary');
select pg_temp.expect('marka', 'T', 'Türk Hava Yolları', 'valid', 'dictionary');
select pg_temp.expect('yemek', 'Ç', 'çiğ köfte', 'valid', 'dictionary');
select pg_temp.expect('yemek', 'Ç', 'CIG KOFTE', 'valid', 'dictionary');
select pg_temp.expect('yemek', 'Ç', 'Çiğköfte', 'valid', 'dictionary');
select pg_temp.expect('yemek', 'S', 'Sushi', 'valid', 'dictionary');
select pg_temp.expect('isim', 'Ş', 'Şule', 'valid', 'dictionary');
select pg_temp.expect('isim', 'Ğ', 'Ğğğğ', 'invalid', 'malformed');
select pg_temp.expect('isim', 'Ö', 'Özge', 'valid', 'dictionary');
select pg_temp.expect('isim', 'O', 'Ozge', 'invalid', 'wrong_letter');         -- means Özge: Ö is not O
select pg_temp.expect('isim', 'Ö', 'Ozge', 'valid', 'dictionary');             -- typed without ö for letter Ö
select pg_temp.expect('hayvan', 'K', 'Kedi', 'valid', 'dictionary');
select pg_temp.expect('hayvan', 'K', 'KÖPEK', 'valid', 'dictionary');
select pg_temp.expect('hayvan', 'Z', 'Zürafa', 'valid', 'dictionary');
select pg_temp.expect('hayvan', 'Z', 'zurafa', 'valid', 'dictionary');
select pg_temp.expect('bitki', 'Ç', 'Çam', 'valid', 'dictionary');
select pg_temp.expect('esya', 'Ş', 'Şemsiye', 'valid', 'dictionary');
select pg_temp.expect('meslek', 'Ö', 'öğretmen', 'valid', 'dictionary');
select pg_temp.expect('film', 'E', 'Eşkıya', 'valid', 'dictionary');
select pg_temp.expect('oyun', 'S', 'Satranç', 'valid', 'dictionary');
select pg_temp.expect('unlu', 'T', 'Tarkan', 'valid', 'dictionary');
select pg_temp.expect('unlu', 'A', 'Atatürk', 'valid', 'dictionary');
-- ---- reported in real play (2026-11-08)
select pg_temp.expect('yemek', 'T', 'Tarhana', 'valid', 'dictionary');
select pg_temp.expect('yemek', 'T', 'tarhana çorbası', 'valid', 'dictionary');
select pg_temp.expect('bitki', 'T', 'Tarhana', 'invalid', 'wrong_category');   -- typed into Bitki in the real game
select pg_temp.expect('hayvan', 'T', 'Tarhana', 'invalid', 'wrong_category');
select pg_temp.expect('isim', 'T', 'Tarık', 'valid', 'dictionary');
select pg_temp.expect('isim', 'T', 'TARIK', 'valid', 'dictionary');
select pg_temp.expect('isim', 'T', 'tarik', 'valid', 'dictionary');
select pg_temp.expect('isim', 'İ', 'İrfan', 'valid', 'dictionary');           -- was rejected as wrong letter
select pg_temp.expect('isim', 'İ', 'İhsan', 'valid', 'dictionary');           -- was rejected as wrong letter
select pg_temp.expect('isim', 'İ', 'irfan', 'valid', 'dictionary');
select pg_temp.expect('isim', 'N', 'Nejdet', 'valid', 'dictionary');
select pg_temp.expect('isim', 'Ö', 'Özcan', 'valid', 'dictionary');           -- dictionary has the foreign spelling "Ozcan"
select pg_temp.expect('ulke', 'İ', 'Irak', 'invalid', 'wrong_letter');        -- Irak starts with I, not İ
select pg_temp.expect('yemek', 'K', 'Kıymalı pide', 'valid', 'compound');
select pg_temp.expect('yemek', 'E', 'Etli bamya', 'valid', 'compound');
select pg_temp.expect('yemek', 'F', 'Fıstıklı baklava', 'valid', 'compound');
select pg_temp.expect('yemek', 'K', 'Kıymalı masa', 'uncertain', 'not_found'); -- modifier + non-dish is not a dish
select pg_temp.expect('sehir', 'İ', 'İstanbul', 'valid', 'dictionary');
select pg_temp.expect('sehir', 'I', 'İstanbul', 'invalid', 'wrong_letter');   -- I (dotless) is a different letter
select pg_temp.expect('sehir', 'Ş', 'Şanlıurfa', 'valid', 'dictionary');
select pg_temp.expect('hayvan', 'İ', 'İnsan', 'uncertain', 'not_found');      -- no longer auto-accepted as an animal
select pg_temp.expect('bitki', 'L', 'Lahmacun', 'invalid', 'wrong_category');
select pg_temp.expect('esya', 'B', 'Baklava', 'invalid', 'wrong_category');
select pg_temp.expect('bitki', 'P', 'Patates', 'valid', 'dictionary');        -- shared ingredient stays valid
select pg_temp.expect('hayvan', 'H', 'Hamsi', 'valid', 'dictionary');
select pg_temp.expect('hayvan', 'T', 'Tavuk', 'valid', 'dictionary');
select pg_temp.expect('unlu', 'M', 'Mozart', 'valid', 'dictionary');           -- surname alias of a very famous person
select pg_temp.expect('unlu', 'L', 'Leonardo DiCaprio', 'valid', 'dictionary');
-- made-up words are never auto-accepted
select pg_temp.expect('hayvan', 'T', 'Tqzxwpl', 'uncertain', 'not_found');
select pg_temp.expect('yemek', 'K', 'Kalamzırt', 'uncertain', 'not_found');
select pg_temp.expect('isim', 'A', 'Asdasdasd', 'uncertain', 'not_found');
-- wrong categories
select pg_temp.expect('sehir', 'K', 'Kedi', 'invalid', 'wrong_category');
select pg_temp.expect('ulke', 'K', 'Kalem', 'invalid', 'wrong_category');
select pg_temp.expect('hayvan', 'A', 'Ankara', 'invalid', 'wrong_category');
select pg_temp.expect('bitki', 'K', 'Kartal', 'invalid', 'wrong_category');
select pg_temp.expect('esya', 'L', 'Lale', 'invalid', 'wrong_category');
select pg_temp.expect('meslek', 'M', 'Masa', 'invalid', 'wrong_category');
select pg_temp.expect('yemek', 'T', 'Telefon', 'invalid', 'wrong_category');
-- unknown but plausible -> vote, never auto-rejected
select pg_temp.expect('hayvan', 'K', 'Kzqwplm', 'uncertain', 'not_found');
select pg_temp.expect('esya', 'B', 'Bilgisayar çantası', 'uncertain', 'not_found');
select pg_temp.expect('custom_1', 'K', 'Kedi', 'uncertain', 'not_found');     -- custom categories always vote
select pg_temp.expect('sehir', 'S', 'Sile', 'invalid', 'wrong_letter');        -- means Şile: wrong letter
select pg_temp.expect('sehir', 'Ş', 'Sile', 'valid', 'dictionary');           -- Şile without ş for letter Ş
-- structure
select pg_temp.expect('hayvan', 'K', '', 'empty', 'empty');
select pg_temp.expect('hayvan', 'K', 'K', 'invalid', 'malformed');
select pg_temp.expect('hayvan', 'K', 'kkkkk', 'invalid', 'malformed');
select pg_temp.expect('hayvan', 'K', 'Kedi#$%', 'invalid', 'malformed');
select pg_temp.expect('hayvan', 'A', 'Kedi', 'invalid', 'wrong_letter');
-- normalisation functions
do $$ begin
    assert public.dict_key('Çiğ Köfte') = 'cigkofte';
    assert public.dict_key('  İSTANBUL ') = 'istanbul';
    assert public.dict_key('Istanbul') = 'istanbul';
    assert public.dict_key('Coca-Cola') = 'cocacola';
    assert public.dict_key('Côte d''Ivoire') = 'cotedivoire';
    assert public.dict_first_letter('İzmir') = 'i' and public.dict_first_letter('Irak') = 'ı' and public.dict_first_letter('ışık') = 'ı';
    assert public.normalize_answer('IŞIK') = 'ışık';
    assert (public.dictionary_info()->>'version')::int >= 2;
    assert (public.dictionary_info()->>'entries')::int >= 50000, 'dictionary size';
    raise notice 'dictionary tests OK: %', public.dictionary_info()->'per_category';
end $$;
