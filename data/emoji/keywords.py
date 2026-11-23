#!/usr/bin/env python3
"""Build data/emoji/keywords.tsv: the words the suggestion strip offers an emoji for.

usage: keywords.py [OUT]    write the table (default: keywords.tsv here)

One line per keyword, lowercase, NFC:

  keyword <TAB> append|replace <TAB> emoji emoji ... <TAB> cldr|hand

`append` puts the emoji after the word ("ok 👌"), `replace` puts it in the
word's place ("cam" -> "🍊"). At most five emoji, best first. The last column
says where the line comes from.

Two sources, both in this folder:

- emoji-index.tsv, CLDR's names (Unicode License v3, README.md): the names of
  things (animals, plants, food, drink, places, vehicles, sports, objects) in
  Vietnamese and English, and the countries of the flags. CLDR's other
  keywords are associations ("cái" for a ewe, "chơi" for a slide, "class" for
  a bicycle), and its faces, people and symbols are described ("mặt cười toe
  toét") rather than named as a typist writes them, so they are left to the
  hand list.
- keywords-hand.tsv, written for this project: chat words, abbreviations and
  emoticons, feelings, greetings and wishes, the few words typed without marks
  that are worth an emoji, and the CLDR names to leave out (`skip`). A hand
  line takes the place of CLDR's for the same keyword.

A keyword of one word typed without marks fires for every word typed the same
way, so CLDR's are left out when their letters are a Vietnamese syllable
("cua" is "của" too, "voi" is "với", "cat" is "cát" and "cắt"): of those, only
the hand list's are kept. A lone name of a food, drink, animal or plant takes
the word's place (the typist means the picture); every other name goes after
it. Run it with python3 -I.
"""

import collections
import os
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))

# CLDR's groups whose names are things a typist names.
THINGS = {"Animals & Nature", "Food & Drink", "Travel & Places", "Activities", "Objects"}
# Of these, the lone names that take the word's place ("cơm" -> "🍚").
PICTURES = {"Animals & Nature", "Food & Drink"}
MAX_EMOJI = 5


def lower(s):
    return unicodedata.normalize("NFC", s).lower()


def bare(s):
    """Letters without marks: "của" -> "cua"."""
    s = unicodedata.normalize("NFD", s.replace("đ", "d").replace("Đ", "D"))
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


def words(s, most):
    """`s` when it is one to `most` words of letters, single-spaced, else None."""
    if not s or s.strip() != s or "  " in s or not all(c.isalpha() or c == " " for c in s):
        return None
    return s if len(s.split()) <= most else None


def syllable_shapes():
    """Every attested Vietnamese syllable, without its marks."""
    shapes = set()
    with open(os.path.join(REPO, "core", "data", "vi-syllables.txt"), encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                shapes.add(bare(line).lower())
    return shapes


def read_index():
    rows = []
    with open(os.path.join(HERE, "emoji-index.tsv"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("# ") or not line.strip():
                continue
            col = line.rstrip("\n").split("\t")
            rows.append(col + [""] * (7 - len(col)))
    return rows


def read_hand():
    """keyword -> (action, [emoji]) in file order; `skip` lines have no emoji."""
    out = {}
    with open(os.path.join(HERE, "keywords-hand.tsv"), encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line or line.startswith("# "):
                continue
            col = line.split("\t")
            if len(col) < 2 or col[1] not in ("append", "replace", "skip"):
                sys.exit(f"keywords-hand.tsv:{n}: keyword <TAB> append|replace|skip <TAB> emoji ...")
            key = lower(col[0])
            emoji = col[2].split() if len(col) > 2 else []
            if (col[1] == "skip") != (not emoji):
                sys.exit(f"keywords-hand.tsv:{n}: emoji are for append and replace lines only")
            if key in out:
                sys.exit(f"keywords-hand.tsv:{n}: {key!r} twice")
            out[key] = (col[1], emoji)
    return out


def cldr(rows, shapes):
    """keyword -> (action, [emoji]) from CLDR's names, by the rules above."""
    named = collections.defaultdict(list)
    picture = set()
    for e, group, _skin, en, _en_kw, vi, _vi_kw in rows:
        if group == "Flags":
            # "cờ: Việt Nam", "flag: Vietnam": the country, when it has two syllables or
            # more in Vietnamese ("Đức", "Anh", "Ý" are everyday words too).
            country = words(vi[4:], 6) if vi.startswith("cờ: ") else None
            if country and " " in country:
                named[lower(country)].append(e)
            country = words(en[6:], 3) if en.startswith("flag: ") else None
            if country:
                named[lower(country)].append(e)
            continue
        if group not in THINGS:
            continue
        for name, most, alone in ((lower(vi), 3, 2), (lower(en), 2, 1)):
            if words(name, most):
                named[name].append(e)
                if group in PICTURES and len(name.split()) <= alone:
                    picture.add(name)
    out = {}
    for k, emoji in named.items():
        # Typed without marks, one word that is also a Vietnamese syllable: left out.
        if " " not in k and k.isascii() and bare(k) in shapes:
            continue
        out[k] = ("replace" if k in picture else "append", list(dict.fromkeys(emoji))[:MAX_EMOJI])
    return out


def build(out_path):
    rows = read_index()
    known = {r[0] for r in rows}
    table = cldr(rows, syllable_shapes())
    source = {k: "cldr" for k in table}
    for k, (action, emoji) in read_hand().items():
        if action == "skip":
            if source.get(k) != "cldr":
                sys.exit(f"keywords-hand.tsv: {k!r} is skipped but CLDR has no such name")
            del table[k], source[k]
            continue
        for e in emoji:
            if e not in known:
                sys.exit(f"keywords-hand.tsv: {k!r}: {e} is not in emoji-index.tsv")
        table[k] = (action, emoji[:MAX_EMOJI])
        source[k] = "hand"
    with open(out_path + ".part", "w", encoding="utf-8") as f:
        f.write("# xrnkey's emoji keywords for the suggestion strip: CLDR 48 names (Unicode License v3) "
                "and this project's hand list (data/emoji/README.md). Built by keywords.py.\n")
        for k in sorted(table):
            action, emoji = table[k]
            f.write(f"{k}\t{action}\t{' '.join(emoji)}\t{source[k]}\n")
    os.replace(out_path + ".part", out_path)
    counts = collections.Counter(source.values())
    actions = collections.Counter(a for a, _ in table.values())
    print(f"{len(table)} keywords ({counts['cldr']} CLDR, {counts['hand']} hand; "
          f"{actions['append']} append, {actions['replace']} replace) -> {out_path}")


def main():
    if len(sys.argv) > 2 or (len(sys.argv) == 2 and sys.argv[1].startswith("-")):
        sys.exit(__doc__)
    build(sys.argv[1] if len(sys.argv) == 2 else os.path.join(HERE, "keywords.tsv"))


if __name__ == "__main__":
    main()
