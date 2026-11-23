// What Space may change (core/plain.cpp), against the small test models.
#include <catch2/catch_test_macros.hpp>
#include <cstdlib>
#include <string>

#include "xrnkey.h"

namespace {
struct Setup {
    XKLm* vi = xk_lm_open((std::string(XRNKEY_TEST_LM_DIR) + "/vi-general.xklm").c_str());
    XKLm* en = xk_lm_open((std::string(XRNKEY_TEST_LM_DIR) + "/en.xklm").c_str());
    XKModels* models = xk_models_new(vi, en);
    ~Setup() {
        xk_models_free(models);
        xk_lm_free(vi);
        xk_lm_free(en);
    }
    std::string fix(const char* w) const {
        char* f = xk_fix_wrong_word(models, w, 1.0f);
        std::string s = f ? f : "";
        std::free(f);
        return s;
    }
};
}  // namespace

TEST_CASE("a word typed without marks is kept when it is a word as it stands", "[plain]") {
    Setup m;
    REQUIRE(m.vi);
    // English words are words.
    CHECK(xk_plain_word(m.models, "meeting", -1.6f));
    CHECK(xk_plain_word(m.models, "file", -1.6f));
    // Not Vietnamese syllables without their marks: "duoc" is only ever "được".
    CHECK_FALSE(xk_plain_word(m.models, "duoc", -1.6f));
    CHECK_FALSE(xk_plain_word(m.models, "nguoi", -1.6f));
    // "khong" is spelled right, but the text only ever has "không".
    CHECK_FALSE(xk_plain_word(m.models, "khong", -1.6f));
    // Not letters: nothing to say.
    CHECK_FALSE(xk_plain_word(m.models, "a1", -1.6f));
    CHECK_FALSE(xk_plain_word(nullptr, "ong", -1.6f));
}

TEST_CASE("a word wrong in both languages gets a confident one-edit fix", "[plain]") {
    Setup m;
    CHECK(m.fix("teh") == "the");
    CHECK(m.fix("TEH") == "the");
    // Typos the web has often enough for a model to know them: fixed from a list.
    CHECK(m.fix("adn") == "and");
    CHECK(m.fix("recieve") == "receive");
    // Right words, and Vietnamese typed without marks, are left alone.
    CHECK(m.fix("the") == "");
    CHECK(m.fix("khong") == "");
    CHECK(m.fix("duoc") == "");
    CHECK(m.fix("ab") == "");
    CHECK(m.fix("zzqx") == "");
}

TEST_CASE("letters with no vowel are left as typed", "[plain]") {
    Setup m;
    // Chat abbreviations and letters typed for their own sake, not a word with a slip in it:
    // Laban Key keeps them, and "thx" used to become "the".
    CHECK(m.fix("thx") == "");
    CHECK(m.fix("THX") == "");
    CHECK(m.fix("fff") == "");
    CHECK(m.fix("strs") == "");
    CHECK(m.fix("teh") == "the");  // a vowel: a slip, fixed as before
}

TEST_CASE("chat words and laughs are left as typed", "[plain]") {
    Setup m;
    // The real models made "hehe" "here" and "oke" "one"; these never change.
    for (const char* w : {"hehe", "hehehe", "hahaha", "hihi", "hjhj", "kaka", "oke", "oki", "uk", "haizz"})
        CHECK(m.fix(w) == "");
    CHECK(m.fix("teh") == "the");  // a slip is still fixed
}

TEST_CASE("chat English is a word as it stands", "[plain]") {
    Setup m;
    // Contractions typed without the apostrophe: "youre" used to become "your", "weve" "were".
    CHECK(m.fix("youre") == "");
    CHECK(m.fix("weve") == "");
    CHECK(m.fix("theyre") == "");
    CHECK(xk_plain_word(m.models, "youre", -1.6f));
    CHECK(xk_plain_word(m.models, "isnt", -1.6f));
    // Abbreviations with a vowel in them.
    CHECK(xk_plain_word(m.models, "rofl", -1.6f));
    CHECK(xk_plain_word(m.models, "abt", -1.6f));
}

TEST_CASE("predictions can carry their scores", "[plain]") {
    Setup m;
    const char* ctx[] = {"<s>", "tôi", "không"};
    char* out[3];
    float lp[3];
    const int n = xk_predict_scored(m.models, ctx, 3, out, lp, 3);
    REQUIRE(n == 3);
    CHECK(lp[0] >= lp[1]);
    CHECK(lp[1] >= lp[2]);
    CHECK(lp[0] < 0.0f);
    for (int i = 0; i < n; ++i) std::free(out[i]);
}
