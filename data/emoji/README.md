# Emoji index

`emoji-index.tsv` lists 1,914 emoji in Unicode's order, one per line: the emoji, its group,
whether it takes skin tones, and its English and Vietnamese names and keywords. Skin-tone
variants are folded into their base. The core reads it (`xk_emoji_*`), for the emoji panel,
emoji search, and the emoji panel's suggestions for the words just typed.

## The suggestion strip's keywords

`keywords.tsv` is the much smaller table the suggestion strip offers emoji from, as Laban Key
does: an emoji shows only when the word as it stands is one of these keywords, or ends one of
several words. One line per keyword: the keyword (lowercase), `append` (the emoji goes after the
word: "ok 👌") or `replace` (it takes the word's place: "cam" → 🍊, ":)" → 🙂), its emoji
(at most five, best first: with no word to offer the strip shows four or five of them across
it), and where the line comes from (`cldr` or `hand`).

- A keyword of one word matches only as it is written, marks and all (any case, wherever the
  tone sits): "khóc" gives 😢, the bare "khoc" nothing. A keyword of several words (up to four)
  also matches typed without marks: "chuc mung sinh nhat" gives 🎂.
- `keywords.py` builds it from `emoji-index.tsv` and `keywords-hand.tsv`:

  ```bash
  python3 -I data/emoji/keywords.py
  ```

- From CLDR, only the names of things: animals, plants, food and drink, places, vehicles,
  sports and objects, in Vietnamese (up to three syllables) and English (up to two words), and
  the countries of the flags (two syllables or more in Vietnamese: "Đức", "Anh" and "Ý" are
  everyday words too). CLDR's other keywords are associations ("cái" for a ewe, "chơi" for a
  slide), and its faces, people and symbols are descriptions ("mặt cười toe toét"), so they are
  left out. So are CLDR's words typed without marks that are also a Vietnamese syllable ("cua"
  is "của" too, "voi" is "với", "cat" is "cát"). A lone name of a food, drink, animal or plant
  takes the word's place; every other name goes after it.
- `keywords-hand.tsv` is written for this project, from general knowledge of how Vietnamese is
  typed in chat: laughs and cries ("haha", "hihi", "huhu", "hic"), emoticons (":)", "=))",
  ":3", "<3"), abbreviations ("hpbd", "cmsn", "snvv", "iu", "ny", "thx", "uk"), feelings,
  greetings, thanks and wishes ("bó tay", "tức quá", "buồn ngủ", "cảm ơn", "chúc mừng năm
  mới"), the few words typed without marks that are worth an emoji ("cam", "hoa", "tim", "vui",
  "ngon", "ngu"), and the CLDR names left out (`skip`) because they are everyday words too
  ("tất" of "tất cả", "kiến" of "ý kiến", the hours: "ba giờ"). It is part of this project,
  under its MIT licence.

`generate.py` builds it from the files pinned in `sources.json`:

```bash
python3 -I data/emoji/generate.py fetch ~/.cache/xrnkey/downloads
python3 -I data/emoji/generate.py ~/.cache/xrnkey/downloads
```

## Sources and licence

- Unicode Emoji 17.0, `emoji-test.txt`: the emoji, their order and groups.
- Unicode CLDR 48, `annotations` and `annotationsDerived` for `en` and `vi`, from the
  `unicode-org/cldr-json` repository: names (`tts`) and keywords. `keywords.tsv` takes some of
  these names, through `emoji-index.tsv`.
- `keywords-hand.tsv`: this project's own, MIT.

The first two are Unicode data, under the Unicode License v3:

> UNICODE LICENSE V3 — COPYRIGHT AND PERMISSION NOTICE
>
> Copyright © 1991-2025 Unicode, Inc.
>
> NOTICE TO USER: Carefully read the following legal agreement. BY DOWNLOADING, INSTALLING,
> COPYING OR OTHERWISE USING DATA FILES, AND/OR SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE
> TO BE BOUND BY, ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO
> NOT DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of data files
> and any associated documentation (the "Data Files") or software and any associated
> documentation (the "Software") to deal in the Data Files or Software without restriction,
> including without limitation the rights to use, copy, modify, merge, publish, distribute,
> and/or sell copies of the Data Files or Software, and to permit persons to whom the Data Files
> or Software are furnished to do so, provided that either (a) this copyright and permission
> notice appear with all copies of the Data Files or Software, or (b) this copyright and
> permission notice appear in associated Documentation.
>
> THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
> PARTICULAR PURPOSE AND NONINFRINGEMENT OF THIRD PARTY RIGHTS.
>
> IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE BE LIABLE FOR ANY
> CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES, OR ANY DAMAGES WHATSOEVER RESULTING
> FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
> TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
> FILES OR SOFTWARE.
>
> Except as contained in this notice, the name of a copyright holder shall not be used in
> advertising or otherwise to promote the sale, use or other dealings in these Data Files or
> Software without prior written authorization of the copyright holder.
