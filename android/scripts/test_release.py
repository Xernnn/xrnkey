"""What stops release.py before anything reaches the server; run with python3 android/scripts/test_release.py."""
import hashlib
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import release  # noqa: E402

SIGNER = "ab" * 32


def git_answers(branch="main", status=""):
    answers = {("rev-parse", "--abbrev-ref", "HEAD"): branch + "\n", ("status", "--porcelain"): status}
    return lambda *args: answers[args]


class Tree(unittest.TestCase):
    def test_main_with_everything_committed_is_released(self):
        self.assertEqual(release.tree_refusals(git_answers()), [])

    def test_another_branch_or_uncommitted_work_is_not(self):
        self.assertEqual(release.tree_refusals(git_answers(branch="feat/x")), ["on feat/x, not main"])
        said = release.tree_refusals(git_answers(status=" M android/app/build.gradle.kts\n"))
        self.assertEqual(len(said), 1)
        self.assertIn("not committed", said[0])
        self.assertEqual(len(release.tree_refusals(git_answers(branch="feat/x", status="?? x\n"))), 2)


class Config(unittest.TestCase):
    def good(self, **over):
        doc = {"server": "https://hub.example/xrnhub/", "ssh": ["one", "two"], "signer": SIGNER}
        doc.update(over)
        return doc

    def test_a_whole_config_reads(self):
        self.assertEqual(release.read_config(self.good()),
                         ("https://hub.example/xrnhub", ["one", "two"], SIGNER, release.DEFAULT_DIR))
        self.assertEqual(release.read_config(self.good(ssh="one", dir="/hub/data/update/xrnkey/"))[1:],
                         (["one"], SIGNER, "hub/data/update/xrnkey"))

    def test_the_signer_is_taken_as_apksigner_or_keytool_prints_it(self):
        colons = ":".join(["AB"] * 32)
        self.assertEqual(release.read_config(self.good(signer=colons))[2], SIGNER)

    def test_anything_missing_or_odd_is_refused(self):
        for over in ({"server": "http://hub.example"}, {"server": ""}, {"ssh": []}, {"ssh": [""]}, {"ssh": None},
                     {"signer": ""}, {"signer": "ab" * 31}, {"signer": "zz" * 32}, {"dir": "../elsewhere"},
                     {"dir": "a;rm -rf ~"}, {"dir": "x/../../y"}):
            with self.assertRaises(release.Refused, msg=over):
                release.read_config(self.good(**over))
        with self.assertRaises(release.Refused):
            release.read_config([])


class Version(unittest.TestCase):
    def test_the_version_code_comes_from_the_build_file(self):
        self.assertEqual(release.version_code('        versionCode = 21\n        versionName = "0.10.0"\n'), 21)
        with self.assertRaises(release.Refused):
            release.version_code("versionName = 1")

    def test_only_a_newer_build_than_the_servers_goes_up(self):
        release.check_newer(21, None)
        release.check_newer(21, {"versionCode": 20, "versionName": "0.9.0"})
        for live in (21, 22):
            with self.assertRaises(release.Refused) as said:
                release.check_newer(21, {"versionCode": live, "versionName": "0.10.0"})
            self.assertIn("bump versionCode", str(said.exception))


class Checks(unittest.TestCase):
    APKSIGNER = f"Signer #1 certificate DN: CN=Someone\nSigner #1 certificate SHA-256 digest: {SIGNER}\n" \
                "Signer #1 certificate SHA-1 digest: 00\n"

    def test_the_signer_must_be_the_pinned_one(self):
        cert = release.signer_of(self.APKSIGNER)
        self.assertEqual(cert, SIGNER)
        release.check_signer(cert, SIGNER)
        with self.assertRaises(release.Refused):
            release.check_signer("cd" * 32, SIGNER)
        with self.assertRaises(release.Refused):  # unreadable counts as wrong
            release.check_signer(release.signer_of("WARNING: nothing here"), SIGNER)

    def test_an_apk_that_asks_for_any_permission_is_refused(self):
        own = "package: com.xern.xrnkey\npermission: com.xern.xrnkey.permission.SYNC_WORDS\n"
        self.assertEqual(release.asked_permissions(own), [])
        release.check_no_permissions([])
        net = own + "uses-permission: name='android.permission.INTERNET'\n"
        self.assertEqual(release.asked_permissions(net), ["android.permission.INTERNET"])
        with self.assertRaises(release.Refused):
            release.check_no_permissions(release.asked_permissions(net))

    def test_app_json_names_the_apk_with_its_size_and_hash(self):
        apk = b"PK\x03\x04 made up"
        self.assertEqual(release.release_doc(apk, 21, "0.10.0"), {
            "versionCode": 21, "versionName": "0.10.0", "apk": "xrnkey-21.apk", "size": len(apk),
            "sha256": hashlib.sha256(apk).hexdigest(),
        })


if __name__ == "__main__":
    unittest.main()
