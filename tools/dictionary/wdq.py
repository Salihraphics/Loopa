#!/usr/bin/env python3
"""Tiny Wikidata SPARQL client (CC0 data). Usage: wdq.py 'SPARQL' | wdq.py -f file.rq"""
import json, sys, time, urllib.parse, urllib.request

UA = "LoopaDictionaryBuilder/1.0 (open-source Turkish word game; contact via repository)"

def query(sparql, retries=2):
    data = urllib.parse.urlencode({"query": sparql}).encode()
    for i in range(retries):
        try:
            req = urllib.request.Request("https://query.wikidata.org/sparql", data=data,
                headers={"User-Agent": UA, "Accept": "application/sparql-results+json",
                         "Content-Type": "application/x-www-form-urlencoded"})
            with urllib.request.urlopen(req, timeout=70) as r:
                return [{k: v["value"] for k, v in b.items()} for b in json.load(r)["results"]["bindings"]]
        except Exception as e:
            print(f"  retry {i+1}: {e}", file=sys.stderr)
            time.sleep(5 * (i + 1))
    raise SystemExit("query failed")

if __name__ == "__main__":
    q = open(sys.argv[2]).read() if sys.argv[1] == "-f" else sys.argv[1]
    for row in query(q):
        print("\t".join(row.values()))
