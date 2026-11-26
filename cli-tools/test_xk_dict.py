"""Tests for xk-dict; run with python3 cli-tools/test_xk_dict.py.

Only the commands that need no installed libxrnkey (add, remove), with
XDG_DATA_HOME in a temporary directory.
"""
import fcntl
import os
import subprocess
import sys
import tempfile
import time
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "xk-dict.py")


class XkDictTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = dict(os.environ, XDG_DATA_HOME=self.tmp.name, XDG_STATE_HOME=os.path.join(self.tmp.name, "state"))

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *args):
        r = subprocess.run([sys.executable, SCRIPT, *args], env=self.env,
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        return r.stdout

    def test_add_writes_the_file_the_addon_reads(self):
        self.run_cli("add", "từ mới", "9000")
        with open(os.path.join(self.tmp.name, "xrnkey", "user-dict.txt"), encoding="utf-8") as f:
            self.assertEqual(f.read(), "từ mới\t9000\n")

    def test_moves_the_folder_from_before_the_rename(self):
        old = os.path.join(self.tmp.name, "xrnikey")
        os.makedirs(old)
        with open(os.path.join(old, "user-dict.txt"), "w", encoding="utf-8") as f:
            f.write("hạnh phúc\t500\n")
        self.run_cli("add", "từ mới", "9000")
        self.assertFalse(os.path.exists(old))
        with open(os.path.join(self.tmp.name, "xrnkey", "user-dict.txt"), encoding="utf-8") as f:
            self.assertEqual(f.read(), "hạnh phúc\t500\ntừ mới\t9000\n")

    def test_remove_replaces_the_file_whole_and_waits_for_a_sync(self):
        # Rewritten in place, a crash or power cut mid-write left an empty or cut file, which the
        # next xk-sync read as words deleted; and a sync running meanwhile could write over it.
        path = os.path.join(self.tmp.name, "xrnkey", "user-dict.txt")
        self.run_cli("add", "bún", "2000")
        self.run_cli("add", "phở", "3000")
        inode = os.stat(path).st_ino
        lock_path = os.path.join(self.tmp.name, "state", "xrnkey", "words-sync.json.lock")
        os.makedirs(os.path.dirname(lock_path), exist_ok=True)
        with open(lock_path, "w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)  # an xk-sync running
            remove = subprocess.Popen([sys.executable, SCRIPT, "remove", "bún"], env=self.env,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            time.sleep(0.5)
            self.assertIsNone(remove.poll())
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), "bún\t2000\nphở\t3000\n")
        out, err = remove.communicate(timeout=10)
        self.assertEqual(remove.returncode, 0, err)
        with open(path, encoding="utf-8") as f:
            self.assertEqual(f.read(), "phở\t3000\n")
        self.assertNotEqual(os.stat(path).st_ino, inode)  # a new file moved in, not the old one cut
        self.assertEqual([n for n in os.listdir(os.path.dirname(path)) if n != "user-dict.txt"], [])


if __name__ == "__main__":
    unittest.main()
