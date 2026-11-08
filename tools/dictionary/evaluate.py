#!/usr/bin/env python3
"""
Measures dictionary quality with the gold sets (supabase/dictionary/gold) by calling the
real `_classify_answer` in a database. Read-only.

  python3 tools/dictionary/evaluate.py --psql "host=... port=... user=postgres dbname=..."
  python3 tools/dictionary/evaluate.py --mgmt /var/tmp/loopa-remote/q.sh     # hosted, via Management API
"""
import argparse, json, os, subprocess, sys
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
GOLD = os.path.join(ROOT, "supabase", "dictionary", "gold")
UP = str.maketrans("abcçdefgğhıijklmnoöprsştuüvyzqwx", "ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZQWX")

def letter(word):
    c = word.strip()[0]
    return c.translate(UP) if c.islower() else c

def cases():
    pos, neg = [], []
    for f in sorted(os.listdir(GOLD)):
        if f.endswith(".txt"):
            cat = f[:-4]
            for line in open(os.path.join(GOLD, f), encoding="utf-8"):
                line = line.strip()
                if line and not line.startswith("#"):
                    for w in line.split("|"):
                        pos.append((cat, w.strip(), "valid"))
    neg_path = os.path.join(GOLD, "negatives.tsv")
    for line in (open(neg_path, encoding="utf-8") if os.path.exists(neg_path) else []):
        if line.strip() and not line.startswith("#"):
            cat, w, exp = line.rstrip("\n").split("\t")
            neg.append((cat, w, exp))
    return pos, neg

def sql_for(items):
    q = lambda s: "'" + s.replace("'", "''") + "'"
    vals = ",".join(f"({q(c)},{q(letter(w))},{q(w)},{q(e)})" for c, w, e in items)
    return ("select coalesce(json_agg(json_build_object('cat',cat,'word',word,'exp',exp,'v',(r).validation,'why',(r).reason)),'[]') "
            f"from (select cat, word, exp, public._classify_answer(cat, l, word) r from (values {vals}) t(cat,l,word,exp)) s")

def run(args, sql):
    if args.mgmt:
        out = subprocess.run([args.mgmt, sql], capture_output=True, text=True).stdout
        body = out.rsplit("\n", 1)[0]
        return json.loads(json.loads(body)[0]["coalesce"]) if isinstance(json.loads(body)[0]["coalesce"], str) else json.loads(body)[0]["coalesce"]
    out = subprocess.run(["psql", args.psql, "-tAc", sql], capture_output=True, text=True, check=True).stdout
    return json.loads(out)

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--psql"); ap.add_argument("--mgmt"); ap.add_argument("--json")
    ap.add_argument("--holdout", action="store_true", help="evaluate supabase/dictionary/gold/holdout instead")
    args = ap.parse_args()
    global GOLD
    if args.holdout:
        GOLD = os.path.join(GOLD, "holdout")
    pos, neg = cases()
    rows = run(args, sql_for(pos)) + (run(args, sql_for(neg)) if neg else [])
    report = {"categories": {}, "negatives": {"total": 0, "ok": 0}, "failures": []}
    for r in rows:
        if r["exp"] == "valid":
            c = report["categories"].setdefault(r["cat"], {"total": 0, "valid": 0, "uncertain": 0, "invalid": 0})
            c["total"] += 1
            c["valid" if r["v"] == "valid" else ("uncertain" if r["v"] == "uncertain" else "invalid")] += 1
            if r["v"] != "valid":
                report["failures"].append(f"MISS  {r['cat']:7} {r['word']:24} -> {r['v']}/{r['why']}")
        else:
            report["negatives"]["total"] += 1
            ok = (r["v"] == "invalid") if r["exp"] == "invalid" else (r["v"] != "valid")
            report["negatives"]["ok"] += ok
            if not ok:
                report["failures"].append(f"NEG   {r['cat']:7} {r['word']:24} -> {r['v']}/{r['why']} (expected {r['exp']})")
    if not report["negatives"]["total"]:
        report["negatives"]["total"] = 1; report["negatives"]["ok"] = 1
    tot = sum(c["total"] for c in report["categories"].values()); val = sum(c["valid"] for c in report["categories"].values())
    wrong_rejects = sum(c["invalid"] for c in report["categories"].values())
    print(f"recall (auto-accepted): {val}/{tot} = {100*val/tot:.1f}%   real words auto-REJECTED: {wrong_rejects}   negatives correct: {report['negatives']['ok']}/{report['negatives']['total']}")
    for cat, c in sorted(report["categories"].items()):
        print(f"  {cat:7} {c['valid']:3}/{c['total']:3} valid  {c['uncertain']:3} vote  {c['invalid']:3} rejected")
    for f in report["failures"]:
        print(" ", f)
    if args.json:
        json.dump(report, open(args.json, "w"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    main()
