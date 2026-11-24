"""Tests for xk-sync; run with python3 cli-tools/test_xk_sync.py.

The hub server is a stand-in here, with the hub's merge rule (a row the device saw takes the device's
value; one written meanwhile keeps the larger count, or the later of a count and a deletion), and one
test runs the real command against it over HTTP. Every word is made up.
"""
import base64
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "xk-sync.py")
spec = importlib.util.spec_from_file_location("xk_sync", SCRIPT)
xk_sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(xk_sync)

NOW = 1_800_000_000_000


class FakeHub:
    """The hub's keyboard words, in memory."""

    def __init__(self):
        self.rows = {}  # key -> [count, at, seq]
        self.seq = 0
        self.pushes = []

    def push(self, base, changes):
        self.pushes.append(changes)
        before = self.seq
        for key, count, at in changes:
            old = self.rows.get(key)
            if old is None or old[2] <= base:
                new = (count, at)
            elif count is not None and old[0] is not None:
                new = (max(count, old[0]), max(at, old[1]))
            elif count is None and old[0] is None:
                new = (None, max(at, old[1]))
            elif at > old[1] or (at == old[1] and count is not None):
                new = (count, at)
            else:
                new = (old[0], old[1])
            if old is not None and new == (old[0], old[1]):
                continue
            self.seq += 1
            self.rows[key] = [new[0], new[1], self.seq]
        return {"from": before, "seq": self.seq}

    def pull(self, since, limit=2):
        rows = sorted((r[2], k, r[0], r[1]) for k, r in self.rows.items() if r[2] > since)
        page = rows[:limit]
        return {"seq": self.seq, "next": page[-1][0] if page else since, "more": len(rows) > limit,
                "words": [[k, c, at] for _, k, c, at in page]}

    def phone(self, *changes, base=None):
        """The phone's hub app pushing, having seen everything so far unless told otherwise."""
        return self.push(self.seq if base is None else base, [list(c) for c in changes])

    def live(self):
        return {k: r[0] for k, r in self.rows.items() if r[0] is not None}


class Sync(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dict = os.path.join(self.tmp.name, "xrnkey", "user-dict.txt")
        self.state = os.path.join(self.tmp.name, "state", "words-sync.json")
        self.hub = FakeHub()
        self.said = []

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, text):
        os.makedirs(os.path.dirname(self.dict), exist_ok=True)
        with open(self.dict, "w", encoding="utf-8") as f:
            f.write(text)

    def read(self):
        with open(self.dict, encoding="utf-8") as f:
            return f.read()

    def sync(self, now=NOW, **kw):
        return xk_sync.sync(self.hub, self.dict, self.state, now_ms=now, say=self.said.append, **kw)

    def test_a_first_sync_adds_both_ways_and_leaves_the_phones_pairs_off(self):
        self.hub.phone(("phở", 3, 0), ("ăn phở", 2, 0), ("<s> phở", 2, 0))
        self.write("bún\t9000\n")
        self.assertTrue(self.sync())
        self.assertEqual(self.read(), "bún\t9000\nphở\t3000\n")
        self.assertEqual(self.hub.pushes[-1], [["bún", 9.0, 0]])  # at 0: a first sync only adds
        self.assertEqual(self.hub.live(), {"phở": 3, "ăn phở": 2, "<s> phở": 2, "bún": 9.0})

    def test_a_word_added_or_removed_here_goes_to_the_phone(self):
        self.write("bún\t2000\n")
        self.sync()
        self.write("bún\t2000\nchè\t4000\n")
        self.sync(NOW + 10)
        self.assertEqual(self.hub.live()["chè"], 4.0)
        self.write("chè\t4000\n")  # xk-dict remove bún
        self.sync(NOW + 20)
        self.assertNotIn("bún", self.hub.live())
        self.assertEqual(self.hub.rows["bún"][:2], [None, NOW + 20])

    def test_a_word_removed_or_changed_on_the_phone_goes_here(self):
        self.write("bún\t2000\nphở\t3000\n")
        self.sync()
        self.hub.phone(("phở", None, NOW + 5), ("bún", 2.7, NOW + 5))  # deleted, and the month's fade
        self.assertTrue(self.sync(NOW + 10))
        self.assertEqual(self.read(), "bún\t2700\n")

    def test_nothing_changed_sends_nothing_and_writes_nothing(self):
        self.hub.phone(("phở", 2.71828, 0))
        self.write("bún\t2000\n")
        self.sync()
        self.hub.pushes.clear()
        self.assertFalse(self.sync(NOW + 10))  # 2718 on the laptop is still the phone's 2.71828
        self.assertEqual(self.hub.pushes, [])

    def test_both_sides_changing_one_word_keep_the_larger(self):
        self.write("phở\t3000\n")
        self.sync()
        self.hub.phone(("phở", 7, NOW + 5))
        self.write("phở\t5000\n")
        self.sync(NOW + 10)
        self.assertEqual(self.read(), "phở\t7000\n")
        self.assertEqual(self.hub.live()["phở"], 7)

    def test_a_deletion_beats_an_older_count_and_loses_to_a_newer_one(self):
        self.write("phở\t3000\nbún\t2000\n")
        self.sync()
        self.hub.phone(("phở", None, NOW + 5), ("bún", None, NOW + 50))
        self.write("phở\t4000\nbún\t6000\n")  # changed here before hearing of either deletion
        self.sync(NOW + 10)
        self.assertEqual(self.read(), "phở\t4000\n")

    def test_a_missing_file_is_lost_not_emptied(self):
        self.write("bún\t2000\n")
        self.sync()
        os.remove(self.dict)
        self.sync(NOW + 10)
        self.assertEqual(self.hub.live(), {"bún": 2.0})
        self.assertEqual(self.read(), "bún\t2000\n")
        self.write("")  # emptied by hand: that is a deletion
        self.sync(NOW + 20)
        self.assertEqual(self.hub.live(), {})

    def test_phrases_capitals_and_other_lines_stay_as_they_are(self):
        self.write("# my words\ntừ mới\t9000\nHà\t5000\n\nbún\t2000\nnot a line\n")
        self.sync()
        self.assertEqual(self.hub.live(), {"bún": 2.0})
        self.hub.phone(("bún", None, NOW + 5), ("phở", 3, NOW + 5), ("Hà", 4, NOW + 5))
        self.sync(NOW + 10)
        self.assertEqual(self.read(), "# my words\ntừ mới\t9000\nHà\t5000\n\nnot a line\nphở\t3000\n")

    def test_a_dry_run_sends_and_writes_nothing(self):
        self.hub.phone(("phở", 3, 0))
        self.write("bún\t2000\n")
        self.assertFalse(self.sync(dry_run=True))
        self.assertEqual((self.read(), self.hub.pushes[1:]), ("bún\t2000\n", []))
        self.assertFalse(os.path.exists(self.state))
        self.assertIn("dry run", self.said[-1])

    def test_a_big_push_goes_in_parts_the_server_takes(self):
        changes = [[f"từ{i}", 1.0, NOW] for i in range(xk_sync.MAX_CHANGES + 3)]
        parts = list(xk_sync.chunks(changes))
        self.assertEqual([len(p) for p in parts], [xk_sync.MAX_CHANGES, 3])
        long = [["x" * 60, 1.0, NOW]] * 4000
        for part in xk_sync.chunks(long):
            self.assertLessEqual(len(json.dumps(part).encode()), xk_sync.MAX_PUSH_BYTES + len(part))

    def test_frequencies_and_counts(self):
        self.assertEqual((xk_sync.freq_of(9), xk_sync.freq_of(2.7), xk_sync.freq_of(0.0001)), (9000, 2700, 1))
        self.assertEqual((xk_sync.count_of(9000), xk_sync.count_of(0)), (9.0, 0.001))
        self.assertEqual(xk_sync.freq_of(1e9), 2**31 - 1)


class Config(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.secret = os.path.join(self.tmp.name, "password")
        with open(self.secret, "w") as f:
            f.write("made up\n")

    def tearDown(self):
        self.tmp.cleanup()

    def config(self, **doc):
        path = os.path.join(self.tmp.name, "sync.json")
        with open(path, "w") as f:
            json.dump({"server": "https://hub.example/hub/", "user": "alex", "password_file": self.secret, **doc}, f)
        return xk_sync.read_config(path)

    def test_a_whole_config_reads(self):
        self.assertEqual(self.config(), ("https://hub.example/hub", "alex", "made up"))
        self.assertEqual(self.config(server="http://127.0.0.1:9/hub")[0], "http://127.0.0.1:9/hub")

    def test_plain_http_elsewhere_and_missing_parts_are_refused(self):
        for over in ({"server": "http://hub.example"}, {"server": ""}, {"user": ""}, {"user": "a:b"},
                     {"password_file": os.path.join(self.tmp.name, "none")}):
            with self.assertRaises(xk_sync.Problem, msg=over):
                self.config(**over)
        with self.assertRaises(xk_sync.Problem):
            xk_sync.read_config(os.path.join(self.tmp.name, "missing.json"))


class OverHttp(unittest.TestCase):
    """The command itself, against a stand-in hub on this machine that wants the login."""

    def test_it_signs_in_syncs_and_keeps_its_state(self):
        hub = FakeHub()
        hub.phone(("phở", 3, 0))
        login = "Basic " + base64.b64encode(b"alex:made up").decode()

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def answer(self, doc):
                if self.headers.get("Authorization") != login:
                    self.send_response(401)
                    self.end_headers()
                    return
                body = json.dumps(doc()).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                since = int(self.path.partition("since=")[2] or 0)
                self.answer(lambda: hub.pull(since))

            def do_POST(self):
                doc = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                self.answer(lambda: hub.push(doc["base"], doc["changes"]))

        srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        with tempfile.TemporaryDirectory() as tmp:
            env = dict(os.environ, XDG_CONFIG_HOME=os.path.join(tmp, "config"), XDG_DATA_HOME=os.path.join(tmp, "data"),
                       XDG_STATE_HOME=os.path.join(tmp, "state"), PATH="/nonexistent")  # no fcitx5-remote to call
            os.makedirs(os.path.join(tmp, "config", "xrnkey"))
            with open(os.path.join(tmp, "secret"), "w") as f:
                f.write("made up")
            config = {"server": f"http://127.0.0.1:{srv.server_address[1]}/hub", "user": "alex",
                      "password_file": os.path.join(tmp, "secret")}
            with open(os.path.join(tmp, "config", "xrnkey", "sync.json"), "w") as f:
                json.dump(config, f)
            run = lambda: subprocess.run([sys.executable, SCRIPT], env=env, capture_output=True, text=True)
            first = run()
            self.assertEqual(first.returncode, 0, first.stderr)
            with open(os.path.join(tmp, "data", "xrnkey", "user-dict.txt"), encoding="utf-8") as f:
                self.assertEqual(f.read(), "phở\t3000\n")
            with open(os.path.join(tmp, "state", "xrnkey", "words-sync.json"), encoding="utf-8") as f:
                self.assertEqual(json.load(f), {"cursor": 1, "words": {"phở": [3, 3000]}})
            config["password_file"] = os.path.join(tmp, "config", "xrnkey", "sync.json")  # the wrong password
            with open(os.path.join(tmp, "config", "xrnkey", "sync.json"), "w") as f:
                json.dump(config, f)
            refused = run()
            self.assertEqual(refused.returncode, 1)
            self.assertIn("refused the login", refused.stderr)


if __name__ == "__main__":
    unittest.main()
