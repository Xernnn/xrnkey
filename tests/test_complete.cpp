// Completions of a word typed in part (xk_complete), against the small test models built from
// the made-up text in data/lm/test.
#include <catch2/catch_test_macros.hpp>
#include <algorithm>
#include <cstdlib>
#include <string>
#include <vector>

#include "xrnkey.h"
#include "xrnkey_internal.h"

namespace {

struct Setup {
    XKLm* vi;
    XKLm* en = xk_lm_open((std::string(XRNKEY_TEST_LM_DIR) + "/en.xklm").c_str());
    XKUser* user = xk_user_new();
    XKModels* models;
    explicit Setup(const char* vi_name = "vi.xklm")
        : vi(xk_lm_open((std::string(XRNKEY_TEST_LM_DIR) + "/" + vi_name).c_str())),
          models(xk_models_new(vi, en)) {
        xk_models_set_user(models, user);
    }
    ~Setup() {
        xk_models_free(models);
        xk_user_free(user);
        xk_lm_free(vi);
        xk_lm_free(en);
    }

    std::vector<std::string> complete(std::vector<const char*> ctx, const char* typed, int max = 6,
                                      std::vector<float>* scores = nullptr) const {
        std::vector<char*> out(static_cast<size_t>(max));
        std::vector<float> lp(static_cast<size_t>(max));
        const int n = xk_complete(models, ctx.data(), static_cast<int>(ctx.size()), typed, out.data(), lp.data(), max);
        std::vector<std::string> words;
        for (int i = 0; i < n; ++i) {
            words.emplace_back(out[static_cast<size_t>(i)]);
            std::free(out[static_cast<size_t>(i)]);
        }
        if (scores) scores->assign(lp.begin(), lp.begin() + n);
        return words;
    }
};

bool has(const std::vector<std::string>& v, const char* w) { return std::find(v.begin(), v.end(), w) != v.end(); }

// The letters a word is typed with, without marks: "được" -> "duoc"; an a-z word is itself.
std::string bare(const std::string& w) {
    const std::string s = xrnkey::syllable_shape(w);
    return s.empty() ? w : s;
}

}  // namespace

TEST_CASE("a bare prefix completes to the longer syllables that start with it, accented", "[complete]") {
    Setup m;
    REQUIRE(m.vi);
    REQUIRE(m.en);
    const auto got = m.complete({"<s>"}, "duo");
    CHECK(has(got, "được"));
    CHECK(has(got, "dược"));
    for (const auto& w : got) {
        INFO(w);
        CHECK(bare(w).rfind("duo", 0) == 0);
        CHECK(bare(w).size() > 3);
    }
    CHECK(has(m.complete({"<s>"}, "kh"), "không"));
}

TEST_CASE("the words before decide which completion comes first", "[complete]") {
    Setup m;
    // "công ty dược phẩm" and "không được" in the corpus.
    CHECK(m.complete({"<s>", "công", "ty"}, "duo").at(0) == "dược");
    CHECK(m.complete({"<s>", "tôi", "không"}, "duo").at(0) == "được");
    std::vector<float> scores;
    m.complete({"<s>"}, "duo", 6, &scores);
    REQUIRE(scores.size() >= 2);
    CHECK(std::is_sorted(scores.begin(), scores.end(), [](float a, float b) { return a > b; }));
}

TEST_CASE("English completions are common words, in the typed case", "[complete]") {
    Setup m;
    const auto got = m.complete({"<s>", "my"}, "boo");
    CHECK(has(got, "boost"));
    CHECK(has(got, "boots"));
    for (const auto& w : got) CHECK(w.rfind("boo", 0) == 0);
    const auto capital = m.complete({"<s>"}, "Boo");
    CHECK(has(capital, "Boost"));
    CHECK(has(m.complete({"<s>"}, "BOO"), "BOOST"));
}

TEST_CASE("what was typed is never its own completion", "[complete]") {
    Setup m;
    // "khong" is "không" typed bare: a reading of it, not a completion.
    for (const auto& w : m.complete({"<s>"}, "khong")) CHECK(bare(w).size() > 5);
    CHECK(!has(m.complete({"<s>"}, "boos"), "boos"));
    CHECK(has(m.complete({"<s>"}, "boos"), "boost"));
}

TEST_CASE("the owner's own words complete too, once typed twice", "[complete]") {
    Setup m("vi-general.xklm");  // without "tủn"
    REQUIRE(m.vi);
    CHECK(!has(m.complete({"<s>", "ngắn"}, "tu"), "tủn"));
    xk_user_learn(m.user, "ngắn", "tủn");
    CHECK(!has(m.complete({"<s>", "ngắn"}, "tu"), "tủn"));
    xk_user_learn(m.user, "ngắn", "tủn");
    CHECK(has(m.complete({"<s>", "ngắn"}, "tu"), "tủn"));
}

TEST_CASE("completing asks for letters typed without marks", "[complete]") {
    Setup m;
    CHECK(m.complete({"<s>"}, "đư").empty());
    CHECK(m.complete({"<s>"}, "a1").empty());
    CHECK(m.complete({"<s>"}, "").empty());
    char* out[2];
    CHECK(xk_complete(nullptr, nullptr, 0, "duo", out, nullptr, 2) == 0);
    CHECK(xk_complete(m.models, nullptr, 0, nullptr, out, nullptr, 2) == 0);
    CHECK(xk_complete(m.models, nullptr, 0, "duo", out, nullptr, 0) == 0);
    // No models with words: nothing, and no crash.
    XKModels* none = xk_models_new(nullptr, nullptr);
    CHECK(xk_complete(none, nullptr, 0, "duo", out, nullptr, 2) == 0);
    xk_models_free(none);
}
