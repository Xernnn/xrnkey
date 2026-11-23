# The Japanese dictionary

The Android keyboard's Japanese input converts kana to kanji with `ja.xkdic`, built from
[Mozc](https://github.com/google/mozc)'s open-source dictionary (`src/data/dictionary_oss`):

- the words: each reading (hiragana), how it is written, its part of speech and its cost;
- the connection costs: what each part of speech costs after another (Mozc's
  `connection_single_column.txt`).

The keyboard finds the cheapest way through a reading, word costs plus connection costs
(`android/app/src/main/java/com/xern/xrnkey/ja/Converter.kt`). `build_dict.py` describes the
binary format, and its `convert()` does what the keyboard does, for checking.

## What is in git, and what is not

- **In git**: `build_dict.py` (Python standard library only), `sources.json` (the Mozc commit
  and every file's SHA-256), and the made-up test dictionary `test/dict.txt`.
- **Not in git**: the downloaded Mozc files and the built `ja.xkdic`.

The tests and CI use the made-up dictionary, built at build time by `android/host` (it is
written for this project, with made-up costs, and says nothing about quality). A debug build
without the real dictionary bundles it too; a release build needs the real one.

## Building it

```bash
python3 -I data/ja/build_dict.py fetch ~/.cache/xrnkey-lm/downloads/mozc       # about 100 MB
python3 -I data/ja/build_dict.py mozc ~/.cache/xrnkey-lm/downloads/mozc ~/.cache/xrnkey-lm/models/ja.xkdic
python3 -I data/ja/build_dict.py check ~/.cache/xrnkey-lm/models/ja.xkdic わたしはがくせいです かみ
python3 -I data/ja/build_dict.py eval ~/.cache/xrnkey-lm/models/ja.xkdic \
    ~/.cache/xrnkey-lm/downloads/mozc/evaluation.tsv
```

- `fetch` downloads the files pinned in `sources.json` and refuses any whose SHA-256 differs.
- `mozc` keeps the readings the keyboard can type (hiragana and ー), each word's cheapest cost
  per part of speech, and only the likelier words: a cost of at most 7,500 (`--max-cost`), and
  at most 6,500 for proper nouns (`--max-proper`). Costs are kept in steps of 64, which changes
  no conversion of Mozc's evaluation set. It takes about ten seconds.
- `eval` says how often the best conversion is the one Mozc gave, for the sentences of Mozc's
  own evaluation set (`evaluation.tsv`).

The Android tests run the same set through the keyboard's own converter, which converts as
`convert()` does (505 of 683), and say how often Mozc's conversion is among the strip's three
(547) or in the strip or its panel (553):

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests '*ConverterTest*' \
    -Pxrnkey.jaEval=$HOME/.cache/xrnkey-lm/downloads/mozc/evaluation.tsv
```

The Android build bundles `ja.xkdic` from the models folder (`~/.cache/xrnkey-lm/models`, or
`-Pxrnkey.models=<dir>`), uncompressed, next to `vi.xklm` and `en.xklm`, and maps it in place.

| Build | Size | Mozc's evaluation set |
|---|---|---|
| every word, no pruning (not built) | about 14 MB | 545 of 683 (79.8 %) |
| `--max-cost 8000 --max-proper 6500` | 8.2 MB | 517 of 683 (75.7 %) |
| `--max-cost 7500 --max-proper 6500` (shipped) | 6.8 MB | 505 of 683 (73.9 %) |
| `--max-cost 7500 --max-proper 6000` | 6.3 MB | 496 of 683 (72.6 %) |
| word costs alone, no connection costs | | 33 of 683 (4.8 %) |

The connection costs take 2.0 MB of it: each part of speech's commonest cost after another,
and the 861,000 that differ.

## Licences

The dictionary is derived from Mozc's, at commit `ffa0d785342651fe649d13dcb6fcb2046020b10d`
(`sources.json`):

- **Mozc**, Copyright 2010-2018 Google Inc.: the BSD 3-Clause licence;
- **IPAdic** (mecab-ipadic-2.7.0-20070801), on which Mozc's open-source dictionary is based,
  Copyright 2000-2003 Nara Institute of Science and Technology, with the conditions of the ICOT
  Free Software its entries come from, whose "NO WARRANTY" section must go with it;
- the **Okinawa dictionary** (<http://sourceforge.jp/projects/o-dic/>), for named entities: in
  the public domain.

Their notices, word for word, are in [`NOTICE`](NOTICE). The APK carries that file
(`assets/ja-notice.txt`), and Settings → About → Licences shows it under "Mozc". The code that
builds and reads the dictionary is MIT, like the rest of the repo.
