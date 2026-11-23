"""Tests for the Japanese dictionary's builder and its reference reader, on the made-up test
dictionary (test/dict.txt).

Run: python3 -I data/ja/test_build_dict.py
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_dict  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))


class Built(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.path = os.path.join(cls.tmp.name, "ja.xkdic")
        build_dict.build(build_dict.read_text(os.path.join(HERE, "test", "dict.txt")), cls.path)
        cls.d = build_dict.Dictionary(cls.path)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def convert(self, reading):
        return "".join(s for s, _ in build_dict.convert(self.d, reading))

    def test_sentences_convert_whole(self):
        self.assertEqual(self.convert("わたしはがくせいです"), "私は学生です")
        self.assertEqual(self.convert("がっこうにいきます"), "学校に行きます")
        self.assertEqual(self.convert("かみ、"), "紙、")

    def test_words_come_cheapest_alone_first(self):
        self.assertEqual([w[0] for w in self.d.lookup("かみ")], ["紙", "神", "髪", "上"])
        self.assertEqual(self.d.lookup("かみさま"), [])
        self.assertEqual(self.d.lookup("kami"), [])

    def test_stored_and_looked_through_predictions_agree(self):
        # "に" starts more readings than the test dictionary's @scan: its words are stored.
        stored = [w[0] for _, w in self.d.predict("に", 5)]
        looked = [w[0] for _, w in self.d.predict("にほ", 5)]
        self.assertEqual(stored, ["日本", "日本語", "日本人"])
        self.assertEqual(looked, stored)

    def test_the_build_is_the_same_every_time(self):
        again = os.path.join(self.tmp.name, "again.xkdic")
        build_dict.build(build_dict.read_text(os.path.join(HERE, "test", "dict.txt")), again)
        with open(self.path, "rb") as a, open(again, "rb") as b:
            self.assertEqual(a.read(), b.read())

    def test_surfaces_keep_the_readings_ending(self):
        self.assertEqual(build_dict.surface_form("たべる", "食べる"), (2, 2, ["食"]))
        self.assertEqual(build_dict.surface_form("かみ", "かみ"), (0, 0, ""))
        self.assertEqual(build_dict.surface_form("かみ", "カミ"), (1, 0, ""))
        # A character past U+FFFF is two UTF-16 units, and comes back whole.
        kind, keep, head = build_dict.surface_form("よしのや", "𠮷野家")
        self.assertEqual(len(head), 4)
        self.assertEqual(build_dict.from_utf16(head), "𠮷野家")


if __name__ == "__main__":
    unittest.main()
