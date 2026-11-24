#!/usr/bin/env python3
"""Build the Japanese keyboard's dictionary, ja.xkdic, from Mozc's open-source dictionary.

  build_dict.py fetch DOWNLOADS
      Download the files pinned in sources.json into DOWNLOADS (a new folder) and check
      each one's SHA-256.
  build_dict.py mozc DOWNLOADS OUT [--max-cost N] [--max-proper N]
      Build OUT from Mozc's dictionary00.txt ... dictionary09.txt, id.def and
      connection_single_column.txt in DOWNLOADS.
  build_dict.py text IN OUT
      Build OUT from a small dictionary written as text (the made-up one the tests use,
      test/dict.txt; its format is described there).
  build_dict.py check DICT READING...
      Convert each reading with DICT and print the best conversion, the words for the
      reading and the predictions, as the keyboard makes them.
  build_dict.py eval DICT EVALUATION_TSV
      How often the best conversion is the one Mozc gave (src/data/dictionary_oss/evaluation.tsv).

The dictionary: each reading (hiragana and ー), the words written so, each with its part of
speech (Mozc's left and right ids) and cost, and the cost of every part of speech followed by
another (Mozc's connection matrix). The keyboard finds the cheapest way through a reading
(android/.../ja/Converter.kt; convert() below does the same). The format, all little-endian:

  header      64 bytes: "XKJA", version, then the offsets and counts read by Header below
  blocks      the readings, sorted by their kana codes, 16 to a block. A block's first reading
              is whole; each after it says how many kana it shares with the one before. Each
              reading: a byte (shared << 4 | rest) or 0xFF, shared, rest when either is 15 or
              more; the rest's kana codes; the byte length of its words (varint); its words,
              cheapest alone first. A word: its part of speech (varint into the part-of-speech
              table), its cost / 64 (a byte), its surface: 0 the reading itself, 1 the reading
              in katakana, else 2 + keep * 30 + (n - 1) (or 255, keep, n as varints) followed
              by n characters (a byte for the 128 commonest, else two: 0x80 | hi, lo, indexes
              into the character table), then the reading's last `keep` kana.
  block index u32 per block, and one past the end: where each block starts in the blocks
  characters  u16 per entry, commonest first (UTF-16 units)
  parts of    u16 left id, u16 right id per entry, commonest first
  speech
  connection  for K ids (0 is the sentence's start and end): u8 default cost / 64 per row; u32
              where each row's exceptions start; per row and 64 columns, a u16 count of the
              row's exceptions before them and a u64 mask of the exceptions among them; then
              each exception's cost / 64 (u8)
  predictions for each prefix that starts more than PREDICT_SCAN readings (@scan in a text
              dictionary): its length and kana codes, a count, and its PREDICTIONS cheapest
              longer words (u32 reading index, u8 word index); sorted by prefix, after a u32
              offset to each. A prefix that starts fewer readings is looked through instead.
  literals    u16 ids for text that has no reading: a kana no word has, a number, Latin
              letters, a comma, a full stop, another symbol; then a byte per part of speech
              (K of them), how a word of it joins a phrase (a bunsetsu, as a conversion is
              split for changing it piece by piece): KINDS below. A reader that knows only
              the six ids reads on as before: the size in the header covers them.

Kana codes: ぁ (U+3041) ... ゖ (U+3096) are 1 ... 86, ー is 87, so their order is the
characters'. A reading with anything else is left out: the keyboard only types those.

Run it with python3 -I: it reads downloaded files.
"""

import argparse
import array
import collections
import glob
import hashlib
import json
import os
import struct
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
MAGIC = b"XKJA"
VERSION = 1
BLOCK = 16
COST_STEP = 64
PREDICT_SCAN = 1500
PREDICTIONS = 16
UNKNOWN_KANA = 12000
LITERALS = ("noun", "number", "alphabet", "comma", "period", "symbol")

# How a word of each part of speech joins a phrase (bunsetsu): a word starts one; a particle,
# an auxiliary, a suffix, a dependent word or punctuation goes with the word before it; a
# prefix takes the word after it; a form of する goes with a noun that takes する before it.
KINDS = ("word", "attach", "prefix", "suru", "sahen")


def kind_of(name):
    """How a word of Mozc's part of speech `name` (id.def) joins a phrase: an index of KINDS."""
    f = name.split(",")
    if f[0] in ("助詞", "助動詞"):
        return KINDS.index("attach")
    if f[0] in ("動詞", "形容詞") and f[1] in ("非自立", "接尾"):
        return KINDS.index("attach")
    if f[0] == "名詞" and f[1] in ("非自立", "接尾", "特殊", "動詞非自立的"):
        return KINDS.index("attach")
    if f[0] == "記号" and f[1] in ("句点", "読点", "括弧閉"):
        return KINDS.index("attach")
    if f[0] == "接頭詞" or (f[0] == "記号" and f[1] == "括弧開"):
        return KINDS.index("prefix")
    if f[0] == "動詞" and len(f) > 4 and f[4] == "サ変・スル":
        return KINDS.index("suru")
    if f[0] == "名詞" and f[1] == "サ変接続":
        return KINDS.index("sahen")
    return KINDS.index("word")
HEADER = struct.Struct("<4sIIIIIIIIIIIIIII")  # 64 bytes


def kana_code(ch):
    o = ord(ch)
    if 0x3041 <= o <= 0x3096:
        return o - 0x3040
    if ch == "ー":
        return 87
    return 0


def code_kana(c):
    return "ー" if c == 87 else chr(c + 0x3040)


def katakana(s):
    return "".join(chr(ord(c) + 0x60) if 0x3041 <= ord(c) <= 0x3096 else c for c in s)


def encode_reading(s):
    codes = [kana_code(c) for c in s]
    return bytes(codes) if all(codes) else None


def varint(n):
    out = bytearray()
    while n >= 0x80:
        out.append(0x80 | (n & 0x7F))
        n >>= 7
    out.append(n)
    return bytes(out)


def read_varint(b, at):
    n = shift = 0
    while True:
        x = b[at]
        at += 1
        n |= (x & 0x7F) << shift
        if x < 0x80:
            return n, at
        shift += 7


def quantize(cost):
    return max(0, min(255, (cost + COST_STEP // 2) // COST_STEP))


# ---- the sources -----------------------------------------------------------------------------

def load_sources():
    with open(os.path.join(HERE, "sources.json"), encoding="utf-8") as f:
        return json.load(f)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cmd_fetch(args):
    src = load_sources()
    os.makedirs(args.downloads, exist_ok=True)
    for name, digest in src["files"].items():
        path = os.path.join(args.downloads, name)
        if not os.path.exists(path):
            url = src["url"].format(commit=src["commit"], name=name)
            print("fetch", url, flush=True)
            part = path + ".part"
            with urllib.request.urlopen(url, timeout=60) as r, open(part, "wb") as out:
                while chunk := r.read(1 << 20):
                    out.write(chunk)
            os.replace(part, path)
        got = sha256(path)
        if got != digest:
            sys.exit(f"{path}: sha256 {got}, sources.json says {digest}")
        print("ok", name)


class Source:
    """What a dictionary is built from: words, the ids' names, the connection matrix."""

    def __init__(self, ids, conn, words, literals, scan=PREDICT_SCAN, kinds=None):
        self.ids = ids          # name of each id; 0 is the sentence's start and end
        self.conn = conn        # array: conn[right_id_of_left_word * n + left_id_of_right_word]
        self.words = words      # {(reading, surface, lid, rid): cost}
        self.literals = literals  # id for each of LITERALS
        self.scan = scan        # prefixes with more readings than this get stored predictions
        self.kinds = kinds or [0] * len(ids)  # each id's index of KINDS


def read_mozc(downloads, max_cost, max_proper):
    src = load_sources()
    for name, digest in src["files"].items():
        if name.endswith(".txt") or name.endswith(".def"):
            if sha256(os.path.join(downloads, name)) != digest:
                sys.exit(f"{name}: not the file sources.json pins; run fetch")
    ids = {}
    with open(os.path.join(downloads, "id.def"), encoding="utf-8") as f:
        for line in f:
            i, name = line.rstrip("\n").split(" ", 1)
            ids[int(i)] = name
    with open(os.path.join(downloads, "connection_single_column.txt"), encoding="utf-8") as f:
        n = int(f.readline())
        conn = array.array("i", (int(x) for x in f))
    if n != len(ids) or len(conn) != n * n:
        sys.exit("connection_single_column.txt does not match id.def")
    proper = {i for i, name in ids.items() if name.startswith("名詞,固有名詞,")}
    words = {}
    for path in sorted(glob.glob(os.path.join(downloads, "dictionary0[0-9].txt"))):
        with open(path, encoding="utf-8") as f:
            for line in f:
                reading, lid, rid, cost, surface = line.rstrip("\n").split("\t")[:5]
                lid, rid, cost = int(lid), int(rid), int(cost)
                if cost > (max_proper if lid in proper else max_cost):
                    continue
                if not encode_reading(reading) or not surface or "\t" in surface:
                    continue
                key = (reading, surface, lid, rid)
                if key not in words or words[key] > cost:
                    words[key] = cost
    by_name = {name: i for i, name in ids.items()}
    literals = [by_name[x] for x in (
        "名詞,一般,*,*,*,*,*", "名詞,数,アラビア数字,*,*,*,*", "記号,アルファベット,*,*,*,*,*",
        "記号,読点,*,*,*,*,、", "記号,句点,*,*,*,*,。", "記号,一般,*,*,*,*,*")]
    names = [ids[i] for i in range(n)]
    return Source(names, conn, words, literals, kinds=[kind_of(x) for x in names])


def read_text(path):
    """The made-up test dictionary: see test/dict.txt for its format."""
    ids, rules, words, literals, scan, kinds = ["BOS/EOS"], [], {}, {}, PREDICT_SCAN, [0]
    with open(path, encoding="utf-8") as f:
        for no, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            if line.startswith("@id "):
                name, *kind = line[4:].split()
                ids.append(name)
                kinds.append(KINDS.index(kind[0]) if kind else 0)
            elif line.startswith("@conn "):
                left, right, cost = line[6:].split()
                rules.append((left, right, int(cost)))
            elif line.startswith("@literal "):
                kind, name = line[9:].split()
                literals[kind] = name
            elif line.startswith("@scan "):
                scan = int(line[6:])
            else:
                reading, pos, cost, surface = line.split("\t")
                if not encode_reading(reading):
                    sys.exit(f"{path}:{no}: {reading!r} is not kana")
                i = ids.index(pos)
                key = (reading, surface, i, i)
                words[key] = min(words.get(key, int(cost)), int(cost))
    n = len(ids)
    conn = array.array("i", [0]) * (n * n)
    for left, right, cost in rules:
        for r in range(n):
            if left not in ("*", ids[r]) and not (left == "BOS" and r == 0):
                continue
            for c in range(n):
                if right in ("*", ids[c]) or (right == "EOS" and c == 0):
                    conn[r * n + c] = cost
    return Source(ids, conn, words, [ids.index(literals[k]) for k in LITERALS], scan, kinds)


# ---- writing ---------------------------------------------------------------------------------

def build(source, out):
    n_all = len(source.ids)
    used = {0, *source.literals}
    for (_, _, lid, rid) in source.words:
        used.add(lid)
        used.add(rid)
    dense = {old: new for new, old in enumerate(sorted(used))}
    k = len(dense)
    olds = sorted(used)

    def cost_of(r, c):
        return quantize(source.conn[olds[r] * n_all + olds[c]]) * COST_STEP

    # The parts of speech and characters, commonest first.
    pos_count, char_count = collections.Counter(), collections.Counter()
    by_reading = collections.defaultdict(list)
    for (reading, surface, lid, rid), cost in source.words.items():
        p = (dense[lid], dense[rid])
        pos_count[p] += 1
        by_reading[reading].append((surface, p, quantize(cost)))
    for reading, ws in by_reading.items():
        for surface, _, _ in ws:
            kind, keep, head = surface_form(reading, surface)
            if kind == 2:
                char_count.update(head)
    pos_list = [p for p, _ in pos_count.most_common()]
    pos_index = {p: i for i, p in enumerate(pos_list)}
    chars = [c for c, _ in char_count.most_common()]
    if len(chars) >= 0x8000:
        sys.exit("too many characters")
    char_index = {c: i for i, c in enumerate(chars)}

    def alone(p, q):
        return cost_of(0, p[0]) + q * COST_STEP + cost_of(p[1], 0)

    readings = sorted(by_reading, key=encode_reading)
    blocks = bytearray()
    offsets = []
    words_of = []  # per reading: its words in stored order (surface, alone)
    prev = b""
    for i, reading in enumerate(readings):
        code = encode_reading(reading)
        if i % BLOCK == 0:
            offsets.append(len(blocks))
            prev = b""
        shared = 0
        while shared < min(len(prev), len(code)) and prev[shared] == code[shared]:
            shared += 1
        rest = code[shared:]
        if shared < 15 and len(rest) < 15:
            blocks.append(shared << 4 | len(rest))
        else:
            blocks += bytes([0xFF]) + varint(shared) + varint(len(rest))
        blocks += rest
        ws = sorted(by_reading[reading], key=lambda w: (alone(w[1], w[2]), w[0]))
        body = bytearray()
        for surface, p, q in ws:
            body += varint(pos_index[p])
            body.append(q)
            kind, keep, head = surface_form(reading, surface)
            if kind < 2:
                body.append(kind)
            else:
                if keep < 8 and 1 <= len(head) <= 30:
                    body.append(2 + keep * 30 + len(head) - 1)
                else:
                    body += bytes([255]) + varint(keep) + varint(len(head))
                for ch in head:
                    c = char_index[ch]
                    body += bytes([c]) if c < 0x80 else bytes([0x80 | c >> 8, c & 0xFF])
        blocks += varint(len(body)) + body
        words_of.append([(s, alone(p, q)) for s, p, q in ws])
        prev = code
    offsets.append(len(blocks))

    # The predictions of each prefix too common to look through.
    codes = [encode_reading(r) for r in readings]
    prefix_ranges = collections.Counter()
    for c in codes:
        for n in range(1, len(c) + 1):
            prefix_ranges[c[:n]] += 1
    heavy = sorted(p for p, n in prefix_ranges.items() if n > source.scan)
    import bisect
    pred = bytearray()
    pred_offsets = []
    for p in heavy:
        lo = bisect.bisect_left(codes, p)
        best = []
        i = lo
        while i < len(codes) and codes[i].startswith(p):
            if len(codes[i]) > len(p):
                for j, (_, a) in enumerate(words_of[i][:4]):
                    best.append((a, len(codes[i]), i, j))
            i += 1
        best.sort()
        best = best[:PREDICTIONS]
        pred_offsets.append(len(pred))
        pred += bytes([len(p)]) + p + bytes([len(best)])
        for _, _, i, j in best:
            pred += struct.pack("<IB", i, j)

    # The connection matrix: each row's commonest cost, and the others where they are.
    words_per_row = (k + 63) // 64
    defaults = bytearray()
    row_start = array.array("I")
    ranks = array.array("H")
    masks = array.array("Q")
    values = bytearray()
    for r in range(k):
        row = [quantize(source.conn[olds[r] * n_all + olds[c]]) for c in range(k)]
        d = collections.Counter(row).most_common(1)[0][0]
        defaults.append(d)
        row_start.append(len(values))
        before = 0
        for w in range(words_per_row):
            ranks.append(before)
            m = 0
            for b in range(64):
                c = w * 64 + b
                if c < k and row[c] != d:
                    m |= 1 << b
                    values.append(row[c])
                    before += 1
            masks.append(m)

    if sys.byteorder != "little":
        for a in (row_start, ranks, masks):
            a.byteswap()
    out_bytes = bytearray(HEADER.size)
    sections = {}

    def put(name, data, align=4):
        while len(out_bytes) % align:
            out_bytes.append(0)
        sections[name] = len(out_bytes)
        out_bytes.extend(data)

    put("blocks", blocks)
    put("index", struct.pack(f"<{len(offsets)}I", *offsets))
    put("chars", struct.pack(f"<{len(chars)}H", *(ord(c) for c in chars)))
    put("pos", b"".join(struct.pack("<HH", a, b) for a, b in pos_list))
    put("conn", bytes(defaults))
    put("rows", row_start.tobytes())
    put("ranks", ranks.tobytes())
    put("masks", masks.tobytes(), align=8)
    put("values", bytes(values))
    put("pred", struct.pack(f"<{len(pred_offsets)}I", *pred_offsets) + bytes(pred))
    put("literals", struct.pack(f"<{len(LITERALS)}H", *(dense[x] for x in source.literals))
        + bytes(source.kinds[olds[i]] for i in range(k)))
    HEADER.pack_into(
        out_bytes, 0, MAGIC, VERSION, len(out_bytes), len(readings), len(offsets) - 1,
        sections["blocks"], sections["index"], len(chars), sections["chars"], len(pos_list),
        sections["pos"], k, sections["conn"], len(heavy), sections["pred"], sections["literals"],
    )
    with open(out + ".part", "wb") as f:
        f.write(out_bytes)
    os.replace(out + ".part", out)
    print(f"{out}: {len(out_bytes)} bytes, {len(readings)} readings, {len(source.words)} words, "
          f"{k} parts of speech, {len(pos_list)} pairs, {len(chars)} characters, "
          f"{len(values)} connection exceptions, {len(heavy)} predicted prefixes; "
          f"blocks {len(blocks)}, connection {sections['pred'] - sections['conn']}")


def surface_form(reading, surface):
    """How a surface is stored: (0 the reading, 1 its katakana, 2 written out), the reading's
    last kana it ends with (kept, not stored), and what is written out."""
    if surface == reading:
        return 0, 0, ""
    if surface == katakana(reading):
        return 1, 0, ""
    keep = 0
    while keep < min(len(reading), len(surface) - 1) and reading[-1 - keep] == surface[-1 - keep]:
        keep += 1
    return 2, keep, utf16(surface[:len(surface) - keep])


def utf16(s):
    """`s` as UTF-16 units, each a one-character string (a pair for a character past U+FFFF)."""
    b = s.encode("utf-16-le", "surrogatepass")
    return [chr(u) for u in struct.unpack(f"<{len(b) // 2}H", b)]


def from_utf16(units):
    return "".join(units).encode("utf-16-le", "surrogatepass").decode("utf-16-le")


# ---- reading, as the keyboard does -----------------------------------------------------------

class Dictionary:
    def __init__(self, path):
        with open(path, "rb") as f:
            self.b = f.read()
        (magic, version, size, self.n, self.nblocks, self.blocks, self.index, nchars, chars,
         npos, pos, self.k, conn, npred, pred, literals) = HEADER.unpack_from(self.b, 0)
        if magic != MAGIC or version != VERSION or size != len(self.b):
            raise ValueError(f"{path}: not a version {VERSION} dictionary")
        self.offsets = struct.unpack_from(f"<{self.nblocks + 1}I", self.b, self.index)
        self.chars = [chr(c) for c in struct.unpack_from(f"<{nchars}H", self.b, chars)]
        p = struct.unpack_from(f"<{2 * npos}H", self.b, pos)
        self.pos = list(zip(p[0::2], p[1::2]))
        w = (self.k + 63) // 64
        self.w = w
        self.defaults = self.b[conn:conn + self.k]
        at = conn + self.k
        at += -at % 4
        self.row_start = struct.unpack_from(f"<{self.k}I", self.b, at)
        at += 4 * self.k
        self.ranks = struct.unpack_from(f"<{self.k * w}H", self.b, at)
        at += 2 * self.k * w
        at += -at % 8
        self.masks = struct.unpack_from(f"<{self.k * w}Q", self.b, at)
        self.values = at + 8 * self.k * w
        self.pred_offsets = struct.unpack_from(f"<{npred}I", self.b, pred)
        self.pred = pred + 4 * npred
        self.literals = dict(zip(LITERALS, struct.unpack_from(f"<{len(LITERALS)}H", self.b, literals)))
        at = literals + 2 * len(LITERALS)
        # Each part of speech's kind (KINDS), where the build wrote them; else none.
        self.kinds = self.b[at:at + self.k] if at + self.k <= len(self.b) else None

    def conn(self, r, c):
        i = r * self.w + (c >> 6)
        m = self.masks[i]
        bit = c & 63
        if not (m >> bit) & 1:
            return self.defaults[r] * COST_STEP
        j = self.row_start[r] + self.ranks[i] + bin(m & ((1 << bit) - 1)).count("1")
        return self.b[self.values + j] * COST_STEP

    def _block(self, bi):
        """Each reading of block bi: (index, code, words' offset, words' end)."""
        at = self.offsets[bi]
        end = self.blocks + self.offsets[bi + 1]
        at += self.blocks
        prev = b""
        i = bi * BLOCK
        while at < end:
            h = self.b[at]
            at += 1
            if h == 0xFF:
                shared, at = read_varint(self.b, at)
                rest, at = read_varint(self.b, at)
            else:
                shared, rest = h >> 4, h & 15
            code = prev[:shared] + self.b[at:at + rest]
            at += rest
            size, at = read_varint(self.b, at)
            yield i, code, at, at + size
            at += size
            prev = code
            i += 1

    def _first(self, bi):
        return next(self._block(bi))[1]

    def lower_bound(self, code):
        """The first reading (index, code, at, end) not before `code`, as a block and position."""
        lo, hi = 0, self.nblocks
        while lo < hi:
            mid = (lo + hi) // 2
            if self._first(mid) <= code:
                lo = mid + 1
            else:
                hi = mid
        bi = max(0, lo - 1)
        while bi < self.nblocks:
            for r in self._block(bi):
                if r[1] >= code:
                    return bi, r
            bi += 1
        return self.nblocks, None

    def words(self, reading_code, at, end):
        reading = "".join(code_kana(c) for c in reading_code)
        out = []
        while at < end:
            p, at = read_varint(self.b, at)
            cost = self.b[at] * COST_STEP
            at += 1
            h = self.b[at]
            at += 1
            if h == 0:
                surface = reading
            elif h == 1:
                surface = katakana(reading)
            else:
                if h == 255:
                    keep, at = read_varint(self.b, at)
                    n, at = read_varint(self.b, at)
                else:
                    keep, n = (h - 2) // 30, (h - 2) % 30 + 1
                head = []
                for _ in range(n):
                    c = self.b[at]
                    at += 1
                    if c >= 0x80:
                        c = (c & 0x7F) << 8 | self.b[at]
                        at += 1
                    head.append(self.chars[c])
                surface = from_utf16(head) + (reading[len(reading) - keep:] if keep else "")
            lid, rid = self.pos[p]
            out.append((surface, lid, rid, cost))
        return out

    def lookup(self, reading):
        code = encode_reading(reading)
        if code is None:
            return []
        _, r = self.lower_bound(code)
        if r is None or r[1] != code:
            return []
        return self.words(r[1], r[2], r[3])

    def alone(self, w):
        return self.conn(0, w[1]) + w[3] + self.conn(w[2], 0)

    def predict(self, prefix, n=8):
        code = encode_reading(prefix)
        if code is None:
            return []
        lo, hi = 0, len(self.pred_offsets)
        while lo < hi:
            mid = (lo + hi) // 2
            at = self.pred + self.pred_offsets[mid]
            key = self.b[at + 1:at + 1 + self.b[at]]
            if key < code:
                lo = mid + 1
            else:
                hi = mid
        if lo < len(self.pred_offsets):
            at = self.pred + self.pred_offsets[lo]
            if self.b[at + 1:at + 1 + self.b[at]] == code:
                at += 1 + self.b[at]
                count = self.b[at]
                at += 1
                out = []
                for _ in range(count):
                    i, j = struct.unpack_from("<IB", self.b, at)
                    at += 5
                    bi = i // BLOCK
                    for r in self._block(bi):
                        if r[0] == i:
                            w = self.words(r[1], r[2], r[3])[j]
                            out.append(("".join(code_kana(c) for c in r[1]), w))
                return out[:n]
        bi, r = self.lower_bound(code)
        found = []
        while r is not None and r[1].startswith(code):
            if len(r[1]) > len(code):
                for w in self.words(r[1], r[2], r[3])[:4]:
                    found.append((self.alone(w), len(r[1]), "".join(code_kana(c) for c in r[1]), w))
            r = self._next(bi, r)
            if r is not None:
                bi = r[0] // BLOCK
        found.sort()
        return [(f[2], f[3]) for f in found[:n]]

    def _next(self, bi, r):
        i = r[0] + 1
        if i >= self.n:
            return None
        for x in self._block(i // BLOCK):
            if x[0] == i:
                return x
        return None


def literal_kind(ch):
    if ch.isdigit():
        return "number"
    if "a" <= ch.lower() <= "z":
        return "alphabet"
    if ch in "、,，":
        return "comma"
    if ch in "。.．！？!?":
        return "period"
    if ch.isalpha():
        return "noun"
    return "symbol"


def convert(d, reading, max_len=24):
    """The cheapest way through `reading`: a list of (surface, reading) segments."""
    n = len(reading)
    ends = [[] for _ in range(n + 1)]
    ends[0] = [(0, 0, None, "", "")]
    for i in range(n):
        if not ends[i]:
            continue
        nodes = []
        if kana_code(reading[i]):
            for length in range(1, min(max_len, n - i) + 1):
                part = reading[i:i + length]
                if not all(kana_code(c) for c in part):
                    break
                for w in d.lookup(part):
                    nodes.append((length, w[0], w[1], w[2], w[3]))
            noun = d.literals["noun"]
            nodes.append((1, reading[i], noun, noun, UNKNOWN_KANA))
        else:
            kind = literal_kind(reading[i])
            length = 1
            if kind in ("number", "alphabet"):
                while i + length < n and literal_kind(reading[i + length]) == kind:
                    length += 1
            lit = d.literals[kind]
            nodes.append((length, reading[i:i + length], lit, lit, 0))
        for length, surface, lid, rid, cost in nodes:
            best = min(ends[i], key=lambda e: e[0] + d.conn(e[1], lid))
            ends[i + length].append((best[0] + d.conn(best[1], lid) + cost, rid, best, surface, reading[i:i + length]))
    last = min(ends[n], key=lambda e: e[0] + d.conn(e[1], 0))
    out = []
    while last[2] is not None:
        out.append((last[3], last[4]))
        last = last[2]
    return out[::-1]


def cmd_check(args):
    d = Dictionary(args.dict)
    for reading in args.readings:
        print(reading, "→", " | ".join(s for s, _ in convert(d, reading)))
        print("  words:", " ".join(w[0] for w in d.lookup(reading)[:12]))
        print("  predictions:", " ".join(w[0] for _, w in d.predict(reading, 12)))


def cmd_eval(args):
    d = Dictionary(args.dict)
    same = total = 0
    with open(args.evaluation, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            reading, mozc = parts[1], parts[2]
            got = "".join(s for s, _ in convert(d, reading))
            same += got == mozc
            total += 1
    print(f"{same} of {total} as Mozc converts them ({100 * same / total:.1f} %)")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("fetch")
    p.add_argument("downloads")
    p.set_defaults(fn=cmd_fetch)
    p = sub.add_parser("mozc")
    p.add_argument("downloads")
    p.add_argument("out")
    p.add_argument("--max-cost", type=int, default=7500)
    p.add_argument("--max-proper", type=int, default=6500)
    p.set_defaults(fn=lambda a: build(read_mozc(a.downloads, a.max_cost, a.max_proper), a.out))
    p = sub.add_parser("text")
    p.add_argument("input")
    p.add_argument("out")
    p.set_defaults(fn=lambda a: build(read_text(a.input), a.out))
    p = sub.add_parser("check")
    p.add_argument("dict")
    p.add_argument("readings", nargs="+")
    p.set_defaults(fn=cmd_check)
    p = sub.add_parser("eval")
    p.add_argument("dict")
    p.add_argument("evaluation")
    p.set_defaults(fn=cmd_eval)
    args = ap.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
