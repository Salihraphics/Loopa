# LOOPA Turkish dictionary

## Size (version 2)

| Category | Entries | | Category | Entries |
|---|---|---|---|---|
| İsim | 31,691 | | Meslek | 1,341 |
| Şehir | 4,849 | | Ünlü | 36,836 |
| Hayvan | 5,052 | | Marka | 2,919 |
| Bitki | 2,113 | | Yemek | 1,221 |
| Eşya | 2,112 | | Film | 10,916 |
| Ülke | 210 | | Oyun | 1,963 |

101,223 active entries (v1: 1,752) and 5,033 alternative spellings. Every entry records its
category, canonical spelling, source (`wikidata:Q…`, `curated`, `loopa_v1`) and the version that added it.

## Sources and licences
- **Wikidata** (CC0 1.0), queried through QLever (`tools/dictionary/queries/*.rq`). Labels and
  Turkish alternative labels (`skos:altLabel`). Settlements of Turkey are provinces, districts, towns
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
3. Found in the category → `valid` if **that spelling** starts with the round's letter
   (`Antep` is valid for A, `Gaziantep` for G; `sanliurfa` is valid for Ş because it means Şanlıurfa).
   Otherwise `invalid / wrong_letter` (`Şanlıurfa` is not an S word).
4. Not found: the first letter is checked tolerantly (ş/s etc. are treated alike) → `invalid / wrong_letter` if it differs.
5. Found **only** in a clearly incompatible category (`category_conflicts`, e.g. an animal typed into
   Şehir) → `invalid / wrong_category`. Names, celebrities, brands, films and games are never used as
   evidence, because they reuse ordinary words.
6. Everything else → `uncertain / not_found` → **player vote** (unchanged majority voting system).
   Custom categories always go to the vote.

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
