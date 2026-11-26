#!/usr/bin/env python3
"""xk-dict — interactive dictionary CLI for xrnkey."""

import argparse
import contextlib
import ctypes
import fcntl
import os
import sys
import tempfile

def data_dir():
    """The folder of the user's files, where the Fcitx5 addon reads them
    (user_paths.cpp, userDataDir). Until 1.1.0 it was called xrnikey; when
    only that one exists it is moved to the new name."""
    data_home = os.environ.get("XDG_DATA_HOME", "")
    if not data_home.startswith("/"):
        data_home = os.path.expanduser("~/.local/share")
    new, old = os.path.join(data_home, "xrnkey"), os.path.join(data_home, "xrnikey")
    if not os.path.lexists(new) and os.path.lexists(old):
        try:
            os.rename(old, new)
        except OSError:
            if not os.path.lexists(new):
                return old
    return new

@contextlib.contextmanager
def sync_lock():
    """Held while user-dict.txt changes: xk-sync takes the same lock for a sync, so neither writes
    over the other's change."""
    state = os.environ.get("XDG_STATE_HOME", "")
    if not state.startswith("/"):
        state = os.path.expanduser("~/.local/state")
    path = os.path.join(state, "xrnkey", "words-sync.json.lock")
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    with open(path, "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield

def write_whole(path, text):
    """`text` as the file at `path`, whole or not at all: written beside it, flushed to the disk and
    moved into place, so a crash never leaves it empty or cut (xk-sync would read the words that
    went as deleted)."""
    folder = os.path.dirname(path)
    fd, tmp = tempfile.mkstemp(prefix=".user-dict.", dir=folder)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        with contextlib.suppress(OSError):
            os.remove(tmp)
        raise
    dfd = os.open(folder, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)

def load_lib():
    for path in ["/usr/lib/libxrnkey.so", "/usr/local/lib/libxrnkey.so"]:
        if os.path.exists(path):
            return ctypes.CDLL(path)
    print("Error: libxrnkey.so not found", file=sys.stderr)
    sys.exit(1)

def cmd_lookup(lib, word, dict_path):
    lib.xk_dict_load.argtypes = [ctypes.c_char_p]
    lib.xk_dict_load.restype = ctypes.c_void_p
    lib.xk_dict_lookup.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    lib.xk_dict_lookup.restype = ctypes.c_int
    lib.xk_dict_free.argtypes = [ctypes.c_void_p]

    d = lib.xk_dict_load(dict_path.encode())
    if not d:
        print(f"Error: cannot load dictionary from {dict_path}", file=sys.stderr)
        sys.exit(1)

    freq = lib.xk_dict_lookup(d, word.encode("utf-8"))
    if freq:
        print(f"{word}\tfrequency={freq}")
    else:
        print(f"{word}\tnot found")
    lib.xk_dict_free(d)

def cmd_add(word, freq, user_dict):
    os.makedirs(os.path.dirname(user_dict), exist_ok=True)
    with sync_lock(), open(user_dict, "a", encoding="utf-8") as f:
        f.write(f"{word}\t{freq}\n")
    print(f"Added: {word} (freq={freq})")

def cmd_remove(word, user_dict):
    if not os.path.exists(user_dict):
        print("User dictionary is empty")
        return
    with sync_lock():
        with open(user_dict, encoding="utf-8") as f:
            lines = f.readlines()
        kept = [line for line in lines if not line.startswith(word + "\t")]
        removed = len(kept) < len(lines)
        if removed:
            write_whole(user_dict, "".join(kept))
    print(f"Removed: {word}" if removed else f"Not found: {word}")

def cmd_import(src, user_dict):
    os.makedirs(os.path.dirname(user_dict), exist_ok=True)
    count = 0
    with sync_lock(), open(src, encoding="utf-8") as inf, open(user_dict, "a", encoding="utf-8") as outf:
        for line in inf:
            line = line.strip()
            if line and not line.startswith("#"):
                outf.write(line + "\n")
                count += 1
    print(f"Imported {count} entries")

def cmd_export(dest, user_dict):
    if not os.path.exists(user_dict):
        print("User dictionary is empty")
        return
    import shutil
    shutil.copy(user_dict, dest)
    print(f"Exported to {dest}")

def cmd_stats(user_dict):
    if not os.path.exists(user_dict):
        print("User dictionary: 0 entries")
        return
    lines = [l for l in open(user_dict, encoding="utf-8") if l.strip() and not l.startswith("#")]
    print(f"User dictionary: {len(lines)} entries")

def main():
    parser = argparse.ArgumentParser(description="xrnkey dictionary CLI")
    sub = parser.add_subparsers(dest="command")

    p_lookup = sub.add_parser("lookup")
    p_lookup.add_argument("word")
    p_lookup.add_argument("--dict", default="/usr/share/xrnkey/dict/vi.dict")

    p_add = sub.add_parser("add")
    p_add.add_argument("word")
    p_add.add_argument("freq", type=int)

    p_remove = sub.add_parser("remove")
    p_remove.add_argument("word")

    p_import = sub.add_parser("import")
    p_import.add_argument("file")

    p_export = sub.add_parser("export")
    p_export.add_argument("file")

    sub.add_parser("stats")

    args = parser.parse_args()
    user_dict = os.path.join(data_dir(), "user-dict.txt")

    if args.command == "lookup":
        lib = load_lib()
        cmd_lookup(lib, args.word, args.dict)
    elif args.command == "add":
        cmd_add(args.word, args.freq, user_dict)
    elif args.command == "remove":
        cmd_remove(args.word, user_dict)
    elif args.command == "import":
        cmd_import(args.file, user_dict)
    elif args.command == "export":
        cmd_export(args.file, user_dict)
    elif args.command == "stats":
        cmd_stats(user_dict)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
