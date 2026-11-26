#!/usr/bin/env python3
"""xk-sync: the laptop's end of the personal words' sync.

The phone keyboard learns the owner's words. Its hub app carries them to the owner's hub server (the
keyboard itself has no network), and this brings them into the laptop's user dictionary,
user-dict.txt, which the Fcitx5 addon reads. Words added or removed here (xk-dict add, xk-dict remove,
or by hand) go back the same way, so a word removed on one device goes from every device.

    xk-sync                    sync once (a systemd user timer runs it every hour: xk-sync.timer)
    xk-sync --dry-run          say what would change, and change nothing
    xk-sync --allow-deletions  let this sync delete more words than the guard below lets through
    xk-sync --clear-all        delete every word that syncs, here and on every device

Only single words in lower case sync, the shape the phone keeps them in. A phrase ("từ mới") or a
capitalised entry stays on the laptop and is left alone, and the phone's word pairs (which the laptop
doesn't use) stay off it. The phone counts how often a word was typed; user-dict.txt holds a
frequency on the dictionary's scale, FREQ_PER_COUNT times that count, so "xk-dict add từ 9000" reaches
the phone as typed 9 times.

What it remembers between runs is in $XDG_STATE_HOME/xrnkey/words-sync.json: the last number it read
from the server and the words as they were after the last sync, which is how it tells a word removed
here from one never seen. The first sync only adds, both ways.

A word removed here is removed everywhere, so a file that lost its words must not look like one whose
words were all removed. A user-dict.txt that is missing, or holds none of the words that sync (what a
power cut during a rewrite leaves), is taken as lost: its words come back, and nothing is deleted. One
that doesn't read (not UTF-8) stops the sync, and nothing changes. And a sync that would delete more
than MAX_DELETIONS words and more than a quarter of them stops before sending anything, unless it is
run with --allow-deletions; --clear-all is the way to delete every word everywhere.

Reads ~/.config/xrnkey/sync.json, which never enters the repo:

    { "server": "https://<host>/<hub path>", "user": "<account>", "password_file": "<file with the password>" }
"""
import argparse
import base64
import fcntl
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.request
from urllib.parse import urlsplit

FREQ_PER_COUNT = 1000
MAX_FREQ = 2**31 - 1
MAX_TOKEN = 64  # bytes, as the keyboard learns them
MAX_CHANGES = 5000  # the server's limit for one push
MAX_PUSH_BYTES = 200 * 1024  # and well inside its body limit
WORDS_PATH = "/api/keyboard/words"
TIMEOUT_S = 30
LOOPBACK = ("127.0.0.1", "localhost", "::1")
MAX_DELETIONS = 20  # a sync may always delete this many; more only while within a quarter of the words


class Problem(Exception):
    """Why this sync stopped."""


class Purged(Problem):
    """The hub dropped deletions older than the last sync (410): sync as if for the first time."""

    def __init__(self):
        super().__init__("the hub purged old deletions since the last sync, and syncing afresh didn't help")


def xdg(name, fallback):
    base = os.environ.get(name, "")
    return base if base.startswith("/") else os.path.expanduser(fallback)


def data_dir():
    """The addon's folder of the user's files (user_paths.cpp; xk-dict's data_dir)."""
    return os.path.join(xdg("XDG_DATA_HOME", "~/.local/share"), "xrnkey")


def state_file():
    return os.path.join(xdg("XDG_STATE_HOME", "~/.local/state"), "xrnkey", "words-sync.json")


def config_file():
    return os.path.join(xdg("XDG_CONFIG_HOME", "~/.config"), "xrnkey", "sync.json")


# ---- the words -----------------------------------------------------------------------------------


def syncs(word):
    """Whether a user-dict entry is one the phone would keep as a word: one token, lower case, NFC,
    not a <mark>, at most MAX_TOKEN bytes."""
    return (bool(word) and " " not in word and not word.startswith(("<", "#"))
            and not re.search(r"[\x00-\x1f\x7f]", word) and word == word.lower()
            and word == unicodedata.normalize("NFC", word) and len(word.encode("utf-8")) <= MAX_TOKEN)


def read_dict(path):
    """(lines, {word: freq}) of user-dict.txt; the lines as they are, for writing back around the
    words that sync. A word on two lines counts at its higher frequency, as the addon reads it.
    (None, {}) when there is no file. Raises Problem when it doesn't read."""
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except FileNotFoundError:
        return None, {}
    except (OSError, UnicodeDecodeError) as e:
        raise Problem(f"{path} doesn't read ({e.__class__.__name__}): nothing was synced or changed") from None
    words = {}
    for line in lines:
        word, freq = entry(line)
        if word is not None and syncs(word):
            words[word] = max(freq, words.get(word, 0))
    return lines, words


def entry(line):
    """(word, freq) from a user-dict line as the addon reads it (core/dict.cpp), or (None, None)."""
    line = line.rstrip("\r")
    if not line or line.startswith("#"):
        return None, None
    word, tab, freq = line.partition("\t")
    if not tab or not word or not re.fullmatch(r"[0-9]+", freq.strip() or "x"):
        return None, None
    value = int(freq.strip())
    return (word, value) if value <= MAX_FREQ else (None, None)


def write_dict(path, lines, words):
    """user-dict.txt with each syncing word at its frequency: changed in place, gone where it went,
    new ones at the end. Every other line stays as it was. Written beside it and moved into place."""
    out, seen = [], set()
    for line in lines or []:
        word, _ = entry(line)
        if word is None or not syncs(word):
            out.append(line)
        elif word in words and word not in seen:
            out.append(f"{word}\t{words[word]}")
            seen.add(word)
    out += [f"{w}\t{words[w]}" for w in sorted(words) if w not in seen]
    write_whole(path, "".join(line + "\n" for line in out), ".user-dict.")


def write_whole(path, text, prefix):
    """`text` as the file at `path`, whole or not at all: written beside it, flushed to the disk,
    moved into place, and the folder flushed, so a power cut leaves the old file or the new one,
    never an empty one (which would read as every word deleted)."""
    folder = os.path.dirname(path)
    os.makedirs(folder, mode=0o700, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=prefix, dir=folder)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise
    dfd = os.open(folder, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(dfd)
    finally:
        os.close(dfd)


def count_of(freq):
    return max(freq, 1) / FREQ_PER_COUNT


def freq_of(count):
    return min(MAX_FREQ, max(1, round(count * FREQ_PER_COUNT)))


def local_changes(base, words, first, lost, now_ms):
    """[[word, count or None, at]]: what changed here since the last sync. A first sync sends at 0,
    which only adds; a lost file deletes nothing."""
    at = 0 if first else now_ms
    out = [[w, count_of(f), at] for w, f in sorted(words.items()) if w not in base or base[w][1] != f]
    if not first and not lost:
        out += [[w, None, now_ms] for w in sorted(base) if w not in words]
    return out


def too_many(deletions, base):
    """Whether a sync deleting `deletions` of the `base` words it last synced is more than a slip of
    the hand: more than MAX_DELETIONS, and more than a quarter of them."""
    return deletions > max(MAX_DELETIONS, base / 4)


def chunks(changes):
    """The changes in pushes the server takes: at most MAX_CHANGES, and MAX_PUSH_BYTES of JSON."""
    part, size = [], 0
    for change in changes:
        n = len(json.dumps(change, ensure_ascii=False).encode("utf-8")) + 1
        if part and (len(part) >= MAX_CHANGES or size + n > MAX_PUSH_BYTES):
            yield part
            part, size = [], 0
        part.append(change)
        size += n
    if part:
        yield part


def sync(server, dict_path, state_path, now_ms=None, dry_run=False, allow_deletions=False, clear_all=False,
         say=print):
    """One sync. `server` has push(base, changes, allow_deletions) and pull(since), which raise
    Purged when the hub answers 410. Returns whether user-dict.txt changed. Raises Problem, having
    sent and written nothing, when it would delete too many words (`allow_deletions` lets it).

    A cursor from before the hub purged its old deletions may have missed one, so the sync runs
    again once as a first sync, which only adds; a second 410 stops it."""
    now_ms = int(time.time() * 1000) if now_ms is None else now_ms
    try:
        return sync_once(server, dict_path, state_path, now_ms, dry_run, allow_deletions, clear_all, say, False)
    except Purged:
        say("the hub purged old deletions since the last sync: syncing as if for the first time")
        return sync_once(server, dict_path, state_path, now_ms, dry_run, allow_deletions, clear_all, say, True)


def sync_once(server, dict_path, state_path, now_ms, dry_run, allow_deletions, clear_all, say, afresh):
    state = None if afresh else load_state(state_path)
    first = state is None
    cursor = 0 if first else state["cursor"]
    base = {} if first else state["words"]
    lines, words = read_dict(dict_path)
    held = words  # what the file has, to tell what changed in it
    if clear_all:
        changes = [[w, None, now_ms] for w in sorted(set(base) | set(words))]
    else:
        lost = lines is None or (not words and bool(base))
        if lost:
            # Lost, not emptied: what it held at the last sync comes back, and nothing is deleted.
            words = {w: known[1] for w, known in base.items()}
            if base:
                say(f"{dict_path} has none of the {len(base)} words synced: taken as lost, they come back "
                    "(xk-sync --clear-all deletes them everywhere)")
        changes = local_changes(base, words, first, lost, now_ms)
        deletions = sum(1 for _, count, _ in changes if count is None)
        if too_many(deletions, len(base)) and not allow_deletions:
            raise Problem(f"this sync would delete {deletions} of the {len(base)} words synced, everywhere, "
                          "so it stopped and changed nothing. If they were removed on purpose: "
                          "xk-sync --allow-deletions")
    allow = clear_all or allow_deletions

    clean, reached = True, cursor
    if not dry_run:
        for part in chunks(changes):
            answer = server.push(cursor, part, allow)
            clean = clean and answer["from"] == reached  # nobody else wrote in between
            reached = answer["seq"]
    # Pushed with nobody writing in between: what the server has since is only this.
    since = reached if changes and clean and not dry_run else cursor
    remote = {}
    while True:
        page = server.pull(since)
        for key, count, _ in page["words"]:
            if " " not in key:  # the phone's word pairs stay off the laptop
                remote[key] = count
        since = page["next"]
        if not page["more"]:
            break

    after = {} if clear_all else dict(words)
    for word, count in remote.items():
        if not syncs(word):
            continue
        if count is None:
            after.pop(word, None)
        else:
            after[word] = freq_of(count)
    pushed = {w: c for w, c, _ in changes}
    known = {}
    for word, freq in after.items():
        if word in remote:
            count = remote[word]
        elif word in pushed:
            count = pushed[word]
        else:
            count = base.get(word, [count_of(freq)])[0]
        known[word] = [count, freq]

    added = sorted(set(after) - set(held))
    gone = sorted(set(held) - set(after))
    moved = sorted(w for w in after if w in held and after[w] != held[w])
    say(f"sent {len(changes)}, got {len(remote)}: {len(added)} new here, {len(gone)} gone, {len(moved)} changed"
        + (" (dry run: nothing sent or written)" if dry_run else ""))
    if dry_run:
        return False
    changed = bool(added or gone or moved)
    if changed:
        write_dict(dict_path, lines, after)
    save_state(state_path, {"cursor": since, "words": known})
    return bool(changed)


def load_state(path):
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
    except FileNotFoundError:
        return None
    except ValueError:
        raise Problem(f"{path} doesn't read: move it away to start over (the next sync only adds)") from None
    if not isinstance(doc, dict) or not isinstance(doc.get("cursor"), int) or not isinstance(doc.get("words"), dict):
        raise Problem(f"{path} isn't a sync state: move it away to start over (the next sync only adds)")
    return doc


def save_state(path, doc):
    write_whole(path, json.dumps(doc, ensure_ascii=False, separators=(",", ":")), ".words-sync.")


# ---- the server ----------------------------------------------------------------------------------


class Hub:
    """The hub server's keyboard words, with the sync login."""

    def __init__(self, server, user, password):
        self.server = server
        self.auth = "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()

    def ask(self, method, path, body=None):
        data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        req = urllib.request.Request(self.server + path, data=data, method=method, headers={
            "Authorization": self.auth, "Content-Type": "application/json", "User-Agent": "xk-sync",
        })
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT_S) as res:
                return json.loads(res.read())
        except urllib.error.HTTPError as e:
            if e.code == 410:
                raise Purged() from None
            said = {401: "it refused the login", 429: "too many wrong passwords; try again later",
                    404: "it has no keyboard words: is the hub server with them deployed?",
                    409: "it refused to delete that many words. If they were removed on purpose: "
                         "xk-sync --allow-deletions"}
            raise Problem(f"{self.server} answered {e.code}: {said.get(e.code, e.read()[:200].decode(errors='replace'))}") from None
        except (urllib.error.URLError, OSError) as e:
            raise Problem(f"couldn't reach {self.server}: {getattr(e, 'reason', e)}") from None

    def push(self, base, changes, allow_deletions=False):
        body = {"base": base, "changes": changes}
        if allow_deletions:
            body["clearAll"] = True  # the owner's say-so, past the hub's own guard against mass deletions
        return self.ask("POST", WORDS_PATH, body)

    def pull(self, since):
        return self.ask("GET", f"{WORDS_PATH}?since={since}")


def read_config(path):
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
    except FileNotFoundError:
        raise Problem(f"""no {path}. Create it with the hub's address, the account and where its password is:
  {{ "server": "https://<host>/<hub path>", "user": "<account>", "password_file": "<file>" }}""") from None
    except ValueError:
        raise Problem(f"{path} isn't JSON") from None
    if not isinstance(doc, dict):
        raise Problem(f"{path} must hold a JSON object")
    server = str(doc.get("server") or "").rstrip("/")
    parts = urlsplit(server)
    if not (parts.scheme == "https" or (parts.scheme == "http" and parts.hostname in LOOPBACK)) or not parts.hostname:
        raise Problem(f'{path} needs "server": the hub\'s https address')
    user = doc.get("user")
    if not isinstance(user, str) or not user or ":" in user:
        raise Problem(f'{path} needs "user": the account to sign in as')
    secret = os.path.expanduser(str(doc.get("password_file") or ""))
    try:
        with open(secret, encoding="utf-8") as f:
            password = f.read().strip()
    except OSError:
        raise Problem(f'{path} needs "password_file": a file holding the password') from None
    if not password:
        raise Problem(f"{secret} is empty")
    return server, user, password


def reload_fcitx():
    """The addon reads user-dict.txt again on Fcitx5's reload."""
    try:
        subprocess.run(["fcitx5-remote", "-r"], capture_output=True, timeout=10)
    except (OSError, subprocess.TimeoutExpired):
        pass


def main(argv=None):
    p = argparse.ArgumentParser(description="Sync the laptop's personal words with the phone keyboard's, through the hub.")
    p.add_argument("--dry-run", action="store_true", help="say what would change, and change nothing")
    p.add_argument("--allow-deletions", action="store_true",
                   help=f"let this sync delete more than {MAX_DELETIONS} words and more than a quarter of them")
    p.add_argument("--clear-all", action="store_true",
                   help="delete every word that syncs, from user-dict.txt and from every device")
    args = p.parse_args(argv)
    server, user, password = read_config(config_file())
    state = state_file()
    os.makedirs(os.path.dirname(state), mode=0o700, exist_ok=True)
    with open(state + ".lock", "w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Problem("another xk-sync is running") from None
        changed = sync(Hub(server, user, password), os.path.join(data_dir(), "user-dict.txt"), state,
                       dry_run=args.dry_run, allow_deletions=args.allow_deletions, clear_all=args.clear_all)
    if changed:
        reload_fcitx()


if __name__ == "__main__":
    try:
        main()
    except Problem as e:
        sys.exit(f"xk-sync: {e}")
