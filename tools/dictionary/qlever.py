#!/usr/bin/env python3
"""Query the Wikidata dump through QLever (qlever.dev, CC0 data). Usage: qlever.py -f file.rq > out.tsv"""
import json, sys, time, urllib.parse, urllib.request
UA = "LoopaDictionaryBuilder/1.0 (open-source Turkish word game)"
PREFIXES = """PREFIX wd: <http://www.wikidata.org/entity/>
PREFIX wdt: <http://www.wikidata.org/prop/direct/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX schema: <http://schema.org/>
PREFIX wikibase: <http://wikiba.se/ontology#>
"""
def query(sparql, retries=6):
    url = "https://qlever.dev/api/wikidata?" + urllib.parse.urlencode({"query": PREFIXES + sparql})
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
            with urllib.request.urlopen(req, timeout=300) as r:
                return [{k: v["value"] for k, v in b.items()} for b in json.load(r)["results"]["bindings"]]
        except Exception as e:
            print(f"  retry {i+1}: {e}", file=sys.stderr); time.sleep(15 * (i + 1))
    raise SystemExit("query failed")
if __name__ == "__main__":
    q = open(sys.argv[2]).read() if sys.argv[1] == "-f" else sys.argv[1]
    rows = query(q)
    cols = list(rows[0].keys()) if rows else []
    for row in rows:
        print("\t".join(row.get(c, "").replace("\t", " ").replace("\n", " ") for c in ["item", "label", "alt"] if c in row or c == "alt"))
