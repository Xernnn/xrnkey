#!/usr/bin/env python3
"""Open every model in a built APK where it lies, as the keyboard does on the phone.

usage: check_apk_models.py APK LIBXRNKEY BUILD_DICT [REPORT]

The keyboard maps each assets/*.xklm in place (AssetManager.openFd: the APK's descriptor, an
offset and a length) through xk_lm_open_fd. This does the same with the engine built for this
machine (android/host), at the offsets the APK really has: each model must be stored, not
compressed, and must open; with both, the English model must fix "teh". AGP aligns stored
assets to 4 bytes only, and a reader that wanted 8 once refused the English model on the phone
while every test, which opened the models by path, passed. The Japanese dictionary
(assets/ja.xkdic) must be stored too, and convert a sentence with the reader of BUILD_DICT
(data/ja/build_dict.py), real or test dictionary alike. Exits 1 on any failure.
"""

import ctypes
import importlib.util
import os
import struct
import sys
import tempfile
import zipfile

# Both the real dictionary and the made-up test one convert this so.
SENTENCE = ("わたしはがくせいです", "私は学生です")


def check_dictionary(build_dict, data):
    """The bundled dictionary, opened by the reference reader: what it converts SENTENCE to."""
    spec = importlib.util.spec_from_file_location("build_dict", build_dict)
    bd = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bd)
    with tempfile.TemporaryDirectory() as tmp:
        path = os.path.join(tmp, "ja.xkdic")
        with open(path, "wb") as f:
            f.write(data)
        d = bd.Dictionary(path)
        return "".join(s for s, _ in bd.convert(d, SENTENCE[0]))


def main():
    if len(sys.argv) not in (4, 5):
        sys.exit(__doc__.strip().splitlines()[2])
    apk, lib, build_dict = sys.argv[1], sys.argv[2], sys.argv[3]
    L = ctypes.CDLL(lib)
    vp = ctypes.c_void_p
    L.xk_lm_open_fd.restype = vp
    L.xk_lm_open_fd.argtypes = [ctypes.c_int, ctypes.c_int64, ctypes.c_int64]
    L.xk_lm_vocab_size.argtypes = [vp]
    L.xk_lm_free.argtypes = [vp]
    L.xk_models_new.restype = vp
    L.xk_models_new.argtypes = [vp, vp]
    L.xk_models_free.argtypes = [vp]
    L.xk_fix_wrong_word.restype = vp
    L.xk_fix_wrong_word.argtypes = [vp, ctypes.c_char_p, ctypes.c_float]
    libc = ctypes.CDLL(None)
    libc.free.argtypes = [vp]

    lines, failed, opened, japanese = [], False, {}, None
    with zipfile.ZipFile(apk) as z, open(apk, "rb") as f:
        for info in z.infolist():
            name = info.filename
            if name == "assets/ja.xkdic":
                if info.compress_type != zipfile.ZIP_STORED:
                    lines.append(f"{name}: compressed; the keyboard maps it in place, so it must be stored")
                    failed = True
                    continue
                f.seek(info.header_offset)
                local = f.read(30)
                name_len, extra_len = struct.unpack("<HH", local[26:30])
                f.seek(info.header_offset + 30 + name_len + extra_len)
                data = f.read(info.file_size)
                try:
                    japanese = check_dictionary(build_dict, data)
                except ValueError as e:
                    lines.append(f"{name}: REFUSED ({e})")
                    failed = True
                    continue
                lines.append(f"{name}: {info.file_size} bytes, {SENTENCE[0]!r} -> {japanese!r}")
                if japanese != SENTENCE[1]:
                    failed = True
                continue
            if not (name.startswith("assets/") and name.endswith(".xklm")):
                continue
            if info.compress_type != zipfile.ZIP_STORED:
                lines.append(f"{name}: compressed; the keyboard maps it in place, so it must be stored")
                failed = True
                continue
            f.seek(info.header_offset)
            local = f.read(30)
            if local[:4] != b"PK\x03\x04":
                lines.append(f"{name}: no local header at {info.header_offset}")
                failed = True
                continue
            name_len, extra_len = struct.unpack("<HH", local[26:30])
            at = info.header_offset + 30 + name_len + extra_len
            lm = L.xk_lm_open_fd(f.fileno(), at, info.file_size)
            if not lm:
                lines.append(f"{name}: REFUSED at {at} ({at % 8} mod 8), {info.file_size} bytes")
                failed = True
                continue
            lines.append(f"{name}: {L.xk_lm_vocab_size(lm)} words at {at} ({at % 8} mod 8), {info.file_size} bytes")
            opened[name] = lm
    vi, en = opened.get("assets/vi.xklm"), opened.get("assets/en.xklm")
    if not vi or not en:
        lines.append("vi.xklm and en.xklm must both be in the APK and open")
        failed = True
    else:
        models = L.xk_models_new(vi, en)
        fix = L.xk_fix_wrong_word(models, b"teh", 1.0)
        got = ctypes.string_at(fix).decode() if fix else None
        if fix:
            libc.free(fix)
        L.xk_models_free(models)
        lines.append(f'"teh" -> {got!r}')
        if got != "the":
            failed = True
    if japanese is None and not any("ja.xkdic" in line for line in lines):
        lines.append("assets/ja.xkdic must be in the APK")
        failed = True
    for lm in opened.values():
        L.xk_lm_free(lm)
    report = f"{apk}\n" + "\n".join("  " + line for line in lines) + "\n"
    if len(sys.argv) == 5:
        with open(sys.argv[4], "w", encoding="utf-8") as out:
            out.write(report)
    print(report, end="", file=sys.stderr if failed else sys.stdout)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
