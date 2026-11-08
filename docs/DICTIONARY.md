# LOOPA Turkish dictionary

## Size (version 3, 2026-11-09)

```
kategori     v1      v2  v3 yeni   toplam   alias
isim        292   31398    16027    47717      74
unlu         80   36720     9492    46292    9070
film         31   10852      453    11336    6048
sehir       193    4656      884     5733    1278
hayvan      197    4854       12     5063       2
marka        83    2833     1834     4750    1660
oyun         37    1925      976     2938     609
esya        174    1938       24     2136      12
bitki       179    1934        8     2121       5
meslek      157    1184       33     1374     794
yemek        96    1105       55     1256     238
ulke        178      32        3      213     127
TOPLAM     1697   99431    29801   130929   19917
```
130,929 active entries and 19,928 alternative spellings. Version 3 adds 29,801 entries.

### Measured quality (`tools/dictionary/evaluate.py`)
| Set | v2 | v3 |
|---|---|---|
| Held-out common answers (528, never used for tuning) | 87.9% auto-accepted, 1 wrongly rejected | **95.1%, 0 wrongly rejected** |
| Gold common answers (918; overlaps curated lists, so optimistic) | 95.6%, 3 wrongly rejected | 100%, 0 |
| Wrong-category / made-up words handled correctly (41) | 32 | **41** |

## Sources and licences
- **Wikidata** (CC0 1.0), queried through QLever (`tools/dictionary/queries/*.rq`). Turkish labels,
  then language-neutral `mul` labels, then English. Many famous people have no `tr` label any more,
  only `mul`. Other-language labels and Turkish alternative labels (`skos:altLabel`) become aliases.
  Version 3 also adds:
  - given names actually borne by citizens of Turkey;
  - public figures of Turkey (Turkish Wikipedia article, at least 4 sitelinks);
  - music groups;
  - films with at least 40 sitelinks, and film series;
  - well-known video games;
  - companies and brands of Turkey;
  - every settlement with at least 150,000 inhabitants;
  - the constituent countries of the UK;
  - dishes of Turkish cuisine.

  Very famous people (at least 120 sitelinks) also match by surname alone, e.g. "Mozart". Settlements of Turkey are provinces, districts, towns
  and cities only. Villages and neighbourhoods are left out on purpose, because their names are often
  ordinary words. Raw results: `supabase/dictionary/sources/wikidata/` (`FETCHED_AT` records the date).
- **Curated lists** written for LOOPA (CC0): `supabase/dictionary/curated/<category>.txt`, the
  everyday words players actually type, plus colloquial names (Antep, Urfa, ABD, Amerika, THY…).
- **Blocklist**: `supabase/dictionary/curated/blocklist.txt` lists words that must never be accepted automatically.

## Validation rules (`_classify_answer`)
1. Empty → `empty`. Junk (< 2 letters, symbols, "aaaa") → `invalid / malformed`.
2. **Spelling-insensitive match** (`dict_key`): Turkish case rules (İ/i, I/ı), Turkish and Latin
   diacritics folded (ş→s, ı→i, ğ→g …), spaces and punctuation ignored. So `İstanbul`, `ISTANBUL`,
   `istanbul` and `Istanbul` are the same; so are `Coca-Cola` and `coca cola`.
3. Found in the category → `valid` if the answer starts with the round's letter. The letter the player
   **typed** is checked against the letter of the dictionary spelling, but a plain-ASCII foreign
   spelling ("Irfan", "Ozcan") does not overrule a Turkish letter the player actually typed
   ("İrfan" for İ, "Özcan" for Ö). Found in the category
   (`Antep` is valid for A, `Gaziantep` for G; `sanliurfa` is valid for Ş because it means Şanlıurfa).
   Otherwise `invalid / wrong_letter` (`Şanlıurfa` is not an S word).
4. Not found: the first letter is checked tolerantly (ş/s etc. are treated alike) → `invalid / wrong_letter` if it differs.
5. Found **only** in a clearly incompatible category (`category_conflicts`, e.g. an animal typed into
   Şehir, or a food typed into Bitki/Hayvan/Eşya) → `invalid / wrong_category`. Names, celebrities, brands, films and games are never used as
   evidence, because they reuse ordinary words.
6. Yemek only: Turkish compound dishes. A food modifier followed by a known dish counts as that dish
   ("Kıymalı pide", "Etli bamya"). The modifiers are in table `dictionary_modifiers`.
7. Everything else → `uncertain / not_found` → **player vote** (unchanged majority voting system).
   Custom categories always go to the vote.

### Controlled fallback and suggestions
- Every resolved vote on an unknown word is recorded in `word_suggestions` / `word_suggestion_events`
  (accepted and rejected counts, once per answer).
- If nobody can vote (everyone else left), an unknown word is accepted **only** with prior community
  evidence: accepted at least twice and more often than rejected. Otherwise it is rejected
  (`no_voters`). Made-up words are never accepted automatically.
- Curators review `word_suggestion_queue` and run `_approve_word_suggestion(id, version)`, which adds
  the word to the dictionary, or `_reject_word_suggestion(id)`.

Duplicates: two answers that hit the same dictionary entry are the same answer (`ABD` =
`Amerika Birleşik Devletleri`), worth 10 points each. Unknown answers are compared by their normalised text.

## Adding or removing words
1. Edit `supabase/dictionary/curated/<category>.txt` (`Canonical|alias|alias`) or `blocklist.txt`.
   Optionally refresh Wikidata: `tools/dictionary/fetch.sh`.
2. Build a new version:
   `python3 tools/dictionary/build.py --version 3 --migration <yyyymmddhhmmss>`
   This writes `supabase/dictionary/v3/*.tsv` for review, a `MANIFEST.json` and an idempotent migration.
3. `supabase db push` (or run the migration). Existing entries are kept, new ones get `version_added = 3`,
   and blocklisted ones are retired (`retired_in = 3`, never deleted).
4. Run `supabase/tests/dictionary_test.sql`.

Tests: `python3 tools/dictionary/test_build.py` checks that the build script normalises words the same way
as the SQL. `supabase/tests/dictionary_test.sql` checks 600 random entries in 3 spellings, 250 aliases,
wrong letters, wrong categories and the explicit Turkish cases. `supabase/tests/dictionary_scoring_test.sql`
plays a real round.
