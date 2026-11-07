#!/usr/bin/env bash
# Re-downloads all Wikidata source sets through QLever. Output: supabase/dictionary/sources/wikidata/*.tsv
set -e
cd "$(dirname "$0")"
OUT=../../supabase/dictionary/sources/wikidata
for q in queries/*.rq; do
  n=$(basename "$q" .rq)
  python3 -c "import sys,runpy; sys.path.insert(0,'.'); sys.argv=['qlever.py','-f','$q']; runpy.run_path('qlever.py', run_name='__main__')" > "$OUT/$n.tsv.tmp"
  mv "$OUT/$n.tsv.tmp" "$OUT/$n.tsv"
  echo "$n: $(wc -l < "$OUT/$n.tsv") rows"
  sleep 3
done
date -u +%Y-%m-%dT%H:%MZ > "$OUT/FETCHED_AT"
