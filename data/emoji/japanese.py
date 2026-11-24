#!/usr/bin/env python3
"""Build data/emoji/keywords-ja.tsv: what the Japanese keyboard's strip offers emoji for.

usage: japanese.py DOWNLOADS [OUT]    write the table (default: keywords-ja.tsv here)

DOWNLOADS holds the files in sources.json (generate.py fetch gets them). One
line per keyword:

  keyword <TAB> emoji <TAB> emoji ... <TAB> hand|name|cldr

At most five emoji (or kaomoji), best first; the last column says where the
line comes from: the hand list, CLDR naming the first emoji so (its name, or a
flag's country, is the keyword: すし 🍣), or CLDR's other keywords, which are
associations (本: 📕). The keyboard offers a keyword's emoji while a reading
is composing whose best conversion is the keyword (ねこ: 猫), or that is it,
in place of the reading: the first in the strip for a hand or name line, in
the panel for a cldr one.

Two sources:

- CLDR 48's Japanese annotations (Unicode License v3, README.md): each
  emoji's name and keywords, and the flags' countries from the derived ones
  ("旗: 日本" gives 日本). A keyword written in katakana or hiragana is also
  keyed in hiragana, as it is read (ネコ: ねこ). A keyword more than
  MOST_SHARED emoji share is a category (顔, 動物, 記号), and one kana long
  says too little: both are left out. A keyword's emoji come best first: the
  one it names (its name is the keyword), then those whose name has it, then
  the more particular (fewer keywords), then Unicode's order. Only emoji the
  index (emoji-index.tsv) has.
- keywords-ja-hand.tsv, written for this project: chat words, greetings,
  feelings, food and weather as typed, a few kaomoji, and CLDR keywords to
  leave out (`skip`). A hand line takes the place of CLDR's for its keyword.

Run it with python3 -I: it reads downloaded files.
"""

import collections
import hashlib
import json
import os
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
MAX_EMOJI = 5
MOST_SHARED = 15


def sources():
    with open(os.path.join(HERE, "sources.json"), encoding="utf-8") as f:
        return {s["name"]: s for s in json.load(f)["sources"]}


def path_of(downloads, src):
    return os.path.join(downloads, src["name"], os.path.basename(src["url"]))


def checked(downloads, name):
    src = sources()[name]
    path = path_of(downloads, src)
    with open(path, "rb") as f:
        if hashlib.sha256(f.read()).hexdigest() != src["sha256"]:
            sys.exit(f"{name}: not the file sources.json pins (run generate.py fetch)")
    return path


def hiragana(s):
    """Katakana as hiragana (ネコ: ねこ); None when `s` isn't all kana."""
    out = []
    for c in s:
        if "ァ" <= c <= "ヶ":
            out.append(chr(ord(c) - 0x60))
        elif "ぁ" <= c <= "ゖ" or c == "ー":
            out.append(c)
        else:
            return None
    return "".join(out)


def index():
    """The index's emoji in order, keyed by their form without variation selectors too."""
    order, by_bare = [], {}
    with open(os.path.join(HERE, "emoji-index.tsv"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("# ") or not line.strip():
                continue
            e = line.split("\t", 1)[0]
            by_bare[e.replace("️", "")] = e
            order.append(e)
    return order, by_bare


def annotations(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    table = data.get("annotations", data.get("annotationsDerived", {}))
    return table.get("annotations", {})


def read_hand():
    out = {}
    with open(os.path.join(HERE, "keywords-ja-hand.tsv"), encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line or line.startswith("# "):
                continue
            col = line.split("\t")
            if len(col) < 2 or any(not c or " " in c for c in col):
                sys.exit(f"keywords-ja-hand.tsv:{n}: keyword <TAB> emoji <TAB> ... (or skip)")
            key = unicodedata.normalize("NFC", col[0])
            if key in out:
                sys.exit(f"keywords-ja-hand.tsv:{n}: {key!r} twice")
            out[key] = [] if col[1:] == ["skip"] else col[1:]
    return out


def cldr(downloads, order, by_bare):
    """keyword -> [emoji], best first, from CLDR's Japanese names and keywords."""
    rank = {e: i for i, e in enumerate(order)}
    found = collections.defaultdict(list)  # keyword -> [(emoji, name, keyword count)]
    plain = annotations(checked(downloads, "cldr48-annotations-ja"))
    for key, a in plain.items():
        e = by_bare.get(key.replace("️", ""))
        if e is None:
            continue
        name = (a.get("tts") or [""])[0]
        words = set(a.get("default") or []) | ({name} if name else set())
        for w in words:
            found[w].append((e, name, len(words)))
    for key, a in annotations(checked(downloads, "cldr48-derived-ja")).items():
        name = (a.get("tts") or [""])[0]
        e = by_bare.get(key.replace("️", ""))
        if e is not None and name.startswith("旗: "):
            found[name[3:]].append((e, name[3:], 1))
    out = {}
    for w, entries in found.items():
        w = unicodedata.normalize("NFC", w.strip())
        if not w or " " in w or len({e for e, _, _ in entries}) > MOST_SHARED:
            continue
        kana = hiragana(w)
        keys = [w] + ([kana] if kana and kana != w else [])
        for k in keys:
            if len(k) < 2 and hiragana(k) is not None:
                continue
            for e, name, n in entries:
                named = 0 if name in (w, k) or hiragana(name) == k else 1 if w in name else 2
                out.setdefault(k, {})
                best = out[k].get(e)
                if best is None or (named, n) < best:
                    out[k][e] = (named, n)
    table = {}
    for k, v in out.items():
        ranked = sorted(v.items(), key=lambda x: (x[1], rank[x[0]]))
        table[k] = ([e for e, _ in ranked][:MAX_EMOJI], "name" if ranked[0][1][0] == 0 else "cldr")
    return table


def build(downloads, out_path):
    order, by_bare = index()
    table = cldr(downloads, order, by_bare)
    for k, emoji in read_hand().items():
        if emoji:
            table[k] = (emoji[:MAX_EMOJI], "hand")
        else:
            table.pop(k, None)
            kana = hiragana(k)
            if kana:
                table.pop(kana, None)
    with open(out_path + ".part", "w", encoding="utf-8") as f:
        f.write("# xrnkey's Japanese emoji keywords: CLDR 48 Japanese annotations (Unicode License v3) "
                "and this project's hand list (data/emoji/README.md). Built by japanese.py.\n")
        for k in sorted(table):
            emoji, source = table[k]
            f.write("\t".join([k] + emoji + [source]) + "\n")
    os.replace(out_path + ".part", out_path)
    print(f"{len(table)} keywords -> {out_path}")


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    build(sys.argv[1], sys.argv[2] if len(sys.argv) == 3 else os.path.join(HERE, "keywords-ja.tsv"))


if __name__ == "__main__":
    main()
