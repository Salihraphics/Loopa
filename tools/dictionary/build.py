#!/usr/bin/env python3
"""
Builds LOOPA dictionary version N from
  - supabase/dictionary/curated/<category>.txt   (hand-written, CC0)
  - supabase/dictionary/sources/wikidata/*.tsv   (Wikidata via QLever, CC0)
into
  - supabase/dictionary/v<N>/<category>.tsv       (reviewable merged lists)
  - supabase/dictionary/v<N>/MANIFEST.json
  - supabase/migrations/<timestamp>_dictionary_v<N>_data.sql

Usage: python3 tools/dictionary/build.py --version 2 --migration 20261108000002

Normalisation mirrors the SQL functions normalize_answer / dict_fold / dict_key /
dict_first_letter (see 20261108000001_dictionary_v2_schema.sql); test_build.py
checks the mirror.
"""
import argparse, json, os, re, sys
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CURATED = os.path.join(ROOT, "supabase", "dictionary", "curated")
WIKIDATA = os.path.join(ROOT, "supabase", "dictionary", "sources", "wikidata")

UPPER = "ABCÇDEFGĞHIİJKLMNOÖPQRSŞTUÜVWXYZÂÎÛâîû"
LOWER = "abcçdefgğhıijklmnoöpqrsştuüvwxyzaiuaiu"
FOLD_FROM = "çğıöşüâîûéèêëáàäãåíìïóòôõúùñýÿœæß"
FOLD_TO = "cgiosuaiueeeeaaaaaiiioooouunyyoas"
_tr_lower = str.maketrans(UPPER, LOWER)
_fold = str.maketrans(FOLD_FROM, FOLD_TO)


def normalize(s: str) -> str:
    return re.sub(r"\s+", " ", s.translate(_tr_lower).strip())


def fold(s: str) -> str:
    return normalize(s).lower().translate(_fold)


def key(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", fold(s))


def first_letter(s: str) -> str:
    n = re.sub(r"^[^\w]+", "", normalize(s).lower())
    return n[:1]


# Characters an answer may contain (mirrors the SQL "malformed" check).
ALLOWED = re.compile(r"^[A-Za-z0-9ÇĞİÖŞÜçğıöşüÂÎÛâîûÉÈÊËÁÀÄÃÅÍÌÏÓÒÔÕÚÙÑÝŸéèêëáàäãåíìïóòôõúùñýÿ .'&:,!?\-]+$")
PAREN = re.compile(r"\s*\([^)]*\)")
LATIN_SUFFIX = re.compile(r"(us|um|is|ae|ii|ensis|oides|ata|ica|ina|ana|ifolia|iflora|ella|ium|ius|ix|ax|ex|ans|ens|oides|alis|ilis|aris|osa|ana|era)$")
TURKISH_CHARS = set("çğıöşüÇĞİÖŞÜ")

# Wikidata files feeding each category.
SOURCES = {
    "isim": ["isim"], "sehir": ["sehir_tr", "sehir_world"], "hayvan": ["hayvan"], "bitki": ["bitki"],
    "esya": ["esya"], "ulke": ["ulke"], "meslek": ["meslek"], "unlu": ["unlu"], "marka": ["marka"],
    "yemek": ["yemek"], "film": ["film"], "oyun": ["oyun"],
}
COMMON_NOUN = {"hayvan", "bitki", "esya", "meslek", "yemek"}


def looks_latin_binomial(label: str) -> bool:
    if any(c in TURKISH_CHARS for c in label) or " var. " in label or " subsp. " in label or " × " in label:
        return " var. " in label or " subsp. " in label or " × " in label
    parts = label.split(" ")
    return len(parts) >= 2 and parts[0][:1].isupper() and parts[1].islower() and bool(LATIN_SUFFIX.search(parts[1]))


def clean(label: str, category: str):
    """Returns a cleaned label or None when the label should not be used."""
    s = PAREN.sub("", label).strip()
    if category == "sehir" and "," in s:
        s = s.split(",")[0].strip()
    if not s or len(s) > 40:
        return None
    if ":" in s and category not in ("film", "oyun"):
        return None
    if not ALLOWED.match(s) or not s[0].isalpha():
        return None
    if len(key(s)) < 2 or re.match(r"^(.)\1+$", key(s)):
        return None
    if category == "isim":
        if " " in s or "-" in s or not s[0].isupper() or any(ch.isdigit() for ch in s) or len(s) > 20:
            return None
    if category in COMMON_NOUN:
        words = s.split(" ")
        if any(ch.isdigit() for ch in s) or len(words) > 4:
            return None
        if any(w[:1].isupper() for w in words[1:]):  # "Renault Latitude", "Gönüllü Editör"
            return None
    if category in ("hayvan", "bitki") and looks_latin_binomial(s):
        return None
    if category == "unlu" and len(s.split(" ")) > 5:
        return None
    return s


def read_curated(category):
    path = os.path.join(CURATED, f"{category}.txt")
    out = []
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = [p.strip() for p in line.split("|") if p.strip()]
            out.append((parts[0], parts[1:], "curated"))
    return out


def read_wikidata(category):
    groups = OrderedDict()
    for name in SOURCES[category]:
        path = os.path.join(WIKIDATA, f"{name}.tsv")
        if not os.path.exists(path):
            continue
        for line in open(path, encoding="utf-8"):
            cols = line.rstrip("\n").split("\t")
            if len(cols) < 2:
                continue
            qid = cols[0].rsplit("/", 1)[-1]
            g = groups.setdefault(qid, {"label": cols[1], "alts": []})
            if len(cols) > 2 and cols[2]:
                g["alts"].append(cols[2])
    out = []
    for qid, g in groups.items():
        canonical = clean(g["label"], category)
        if canonical is None:
            continue
        alts = [a for a in (clean(x, category) for x in g["alts"]) if a]
        out.append((canonical, alts, f"wikidata:{qid}"))
    return out


def read_blocklist():
    path = os.path.join(CURATED, "blocklist.txt")
    out = {}
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            line = line.strip()
            if line and not line.startswith("#") and "|" in line:
                cat, word = line.split("|", 1)
                out.setdefault(cat.strip(), set()).add(key(word))
    return out


BLOCK = read_blocklist()


def merge(category):
    """Curated first, then Wikidata. One entry per match key; other spellings become aliases."""
    blocked = BLOCK.get(category, set())
    entries = OrderedDict()   # key -> [canonical, OrderedDict(alias_key -> alias), source]
    alias_owner = {}
    stats = {"curated": 0, "wikidata": 0, "merged_as_alias": 0}
    for canonical, alts, source in read_curated(category) + read_wikidata(category):
        k = key(canonical)
        if not k or k in blocked:
            continue
        owner = entries.get(k) or (entries.get(alias_owner[k]) if k in alias_owner else None)
        if owner is None:
            owner = [canonical, OrderedDict(), source]
            entries[k] = owner
            stats["curated" if source == "curated" else "wikidata"] += 1
        elif normalize(canonical) != normalize(owner[0]):
            stats["merged_as_alias"] += 1
        for a in alts:
            ak = key(a)
            if ak and ak != key(owner[0]) and ak not in owner[1] and ak not in entries:
                owner[1][ak] = a
                alias_owner.setdefault(ak, key(owner[0]))
    return entries, stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", type=int, required=True)
    ap.add_argument("--migration", required=True, help="migration timestamp prefix, e.g. 20261108000002")
    ap.add_argument("--chunk", type=int, default=2500)
    args = ap.parse_args()

    outdir = os.path.join(ROOT, "supabase", "dictionary", f"v{args.version}")
    os.makedirs(outdir, exist_ok=True)
    fetched = open(os.path.join(WIKIDATA, "FETCHED_AT")).read().strip() if os.path.exists(os.path.join(WIKIDATA, "FETCHED_AT")) else "unknown"
    manifest = {"version": args.version, "wikidata_fetched_at": fetched, "licence": "CC0 1.0 (Wikidata + LOOPA curated)", "categories": {}}

    sql = [
        f"-- GENERATED by tools/dictionary/build.py — do not edit by hand.",
        f"-- LOOPA dictionary version {args.version}: curated lists (CC0) + Wikidata labels (CC0, fetched {fetched}).",
        f"-- Idempotent: re-running inserts nothing new.",
        f"insert into public.dictionary_versions(version, source, notes) values ({args.version}, 'wikidata+curated', "
        f"'Wikidata via QLever fetched {fetched}; curated lists in supabase/dictionary/curated') on conflict do nothing;",
    ]
    total = 0
    for category in SOURCES:
        entries, stats = merge(category)
        rows = []
        for k, (canonical, aliases, source) in entries.items():
            rows.append(f"{canonical}\t{'|'.join(aliases.values())}\t{source}")
        with open(os.path.join(outdir, f"{category}.tsv"), "w", encoding="utf-8") as f:
            f.write("# canonical\taliases (|)\tsource\n" + "\n".join(rows) + "\n")
        manifest["categories"][category] = {"entries": len(rows), "aliases": sum(len(a) for _, a, _ in entries.values()), **stats}
        total += len(rows)
        for i in range(0, len(rows), args.chunk):
            body = "\n".join(rows[i:i + args.chunk])
            assert "$loopa$" not in body
            sql.append(f"select public._dict_load({args.version}, '{category}', $loopa$\n{body}\n$loopa$);")
    # Retire blocklisted words that an earlier version may already have loaded.
    for cat, keys in sorted(BLOCK.items()):
        ks = ",".join("'" + k + "'" for k in sorted(keys))
        sql.append(f"update public.dictionary_entries set retired_in = {args.version} "
                   f"where category_key = '{cat}' and match_key in ({ks}) and retired_in is null;")
    sql.append(f"select public._dict_consolidate({args.version});")
    manifest["blocklisted"] = {c: len(k) for c, k in BLOCK.items()}
    manifest["total_entries"] = total
    with open(os.path.join(outdir, "MANIFEST.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    mig = os.path.join(ROOT, "supabase", "migrations", f"{args.migration}_dictionary_v{args.version}_data.sql")
    with open(mig, "w", encoding="utf-8") as f:
        f.write("\n".join(sql) + "\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=1))
    print("wrote", os.path.relpath(mig, ROOT), f"({os.path.getsize(mig) // 1024} KiB)")


if __name__ == "__main__":
    sys.exit(main())
