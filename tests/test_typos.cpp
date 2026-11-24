// Typo correction in accent restoration, against the small test models
// (data/lm/test): words a key or two away on the QWERTY layout. And Space's
// typo fixes that know where each finger landed (core/typos.cpp).
#include <catch2/catch_test_macros.hpp>
#include <catch2/matchers/catch_matchers_floating_point.hpp>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "keys.h"
#include "xrnkey.h"

namespace {

struct Models {
    XKLm* vi = xk_lm_open((std::string(XRNKEY_TEST_LM_DIR) + "/vi.xklm").c_str());
    XKLm* en = xk_lm_open((std::string(XRNKEY_TEST_LM_DIR) + "/en.xklm").c_str());
    XKModels* m = xk_models_new(vi, en);
    ~Models() {
        xk_models_free(m);
        xk_lm_free(vi);
        xk_lm_free(en);
    }
};

std::vector<std::string> run(XKModels* m, std::vector<const char*> ctx, std::vector<const char*> typed) {
    std::vector<char*> raw(typed.size(), nullptr);
    int n = xk_restore_run(m, ctx.data(), static_cast<int>(ctx.size()), typed.data(),
                           static_cast<int>(typed.size()), nullptr, raw.data());
    std::vector<std::string> out;
    for (int i = 0; i < n; ++i) {
        out.emplace_back(raw[static_cast<size_t>(i)]);
        std::free(raw[static_cast<size_t>(i)]);
    }
    return out;
}

using V = std::vector<std::string>;

/* The candidates offered for `typed`, best first: text and slips. */
std::vector<std::pair<std::string, int>> offers(XKModels* m, std::vector<const char*> ctx, const char* typed) {
    std::vector<XKCandidate> c(64);
    int n = xk_restore_word(m, ctx.data(), static_cast<int>(ctx.size()), typed, c.data(), 64);
    std::vector<std::pair<std::string, int>> out;
    for (int i = 0; i < n; ++i) out.emplace_back(c[static_cast<size_t>(i)].text, c[static_cast<size_t>(i)].edit_distance);
    return out;
}

bool offered(const std::vector<std::pair<std::string, int>>& o, const std::string& text, int slips) {
    for (const auto& [t, k] : o)
        if (t == text && k == slips) return true;
    return false;
}

int most_slips(const std::vector<std::pair<std::string, int>>& o) {
    int m = 0;
    for (const auto& x : o) m = std::max(m, x.second);
    return m;
}

}  // namespace

TEST_CASE("a slip to a neighbouring key is put right in context", "[typos]") {
    Models t;
    REQUIRE(t.vi != nullptr);
    xk_models_set_correction(t.m, XK_CORRECT_FLEXIBLE);
    CHECK(run(t.m, {"<s>", "tôi"}, {"khonh"}) == V{"không"});            // g -> h
    CHECK(run(t.m, {"<s>", "chào"}, {"bab"}) == V{"bạn"});              // n -> b
    CHECK(offered(offers(t.m, {"<s>", "tôi", "không"}, "bieg"), "biết", 1));  // t -> g
    // The candidate says what it is.
    std::vector<const char*> ctx = {"<s>", "tôi"};
    XKCandidate c[4];
    REQUIRE(xk_restore_word(t.m, ctx.data(), 2, "khonh", c, 4) > 0);
    CHECK(std::string(c[0].text) == "không");
    CHECK(c[0].type == XK_CAND_CORRECTED);
    CHECK(c[0].edit_distance == 1);
}

TEST_CASE("correction is off unless asked, and leaves right words alone", "[typos]") {
    Models t;
    REQUIRE(t.vi != nullptr);
    CHECK(run(t.m, {"<s>", "tôi"}, {"khonh"}) == V{"khonh"});
    xk_models_set_correction(t.m, XK_CORRECT_FLEXIBLE);
    CHECK(run(t.m, {"<s>"}, {"Toi", "khong", "biet"}) == V{"Tôi", "không", "biết"});
    CHECK(run(t.m, {"<s>"}, {"we", "can", "chat"}) == V{"we", "can", "chat"});
    // Chat abbreviations and one-letter words are never corrected.
    CHECK(run(t.m, {"<s>", "tôi"}, {"ko"}) == V{"ko"});
    CHECK(run(t.m, {"<s>", "tôi"}, {"dc"}) == V{"dc"});
    CHECK(run(t.m, {"<s>", "tôi"}, {"oke"}) == V{"oke"});
    CHECK(run(t.m, {"<s>", "tôi"}, {"r"}) == V{"r"});
}

TEST_CASE("two slips: from 5 letters, or from 4 when strong", "[typos]") {
    Models t;
    REQUIRE(t.vi != nullptr);
    xk_models_set_correction(t.m, XK_CORRECT_FLEXIBLE);
    // Whether a correction two slips away wins depends on the model (this
    // test one is too small to pay for two); what is offered does not.
    CHECK(offered(offers(t.m, {"<s>", "tôi"}, "khlbg"), "không", 2));  // o -> l, n -> b
    CHECK(most_slips(offers(t.m, {"<s>", "tôi", "không"}, "bjey")) <= 1);  // 4 letters: one slip
    xk_models_set_correction(t.m, XK_CORRECT_STRONG);
    CHECK(offered(offers(t.m, {"<s>", "tôi", "không"}, "bjey"), "biết", 2));  // i -> j, t -> y
    xk_models_set_correction(t.m, XK_CORRECT_OFF);
    CHECK(most_slips(offers(t.m, {"<s>", "tôi"}, "khlbg")) == 0);
}

namespace {

/* A state with the test models and the phone's keys (1.4 key widths tall). */
struct Typing {
    Models t;
    XKState* s = xk_state_new(XK_MODE_TELEX);
    Typing() {
        xk_models_set_keyboard(t.m, 1.4f, nullptr, nullptr);
        xk_state_set_models(s, t.m);
    }
    ~Typing() { xk_state_free(s); }

    /* What Space's fix makes of `keys` typed after `ctx`; touches by key index (the rest
     * unknown). "" when the word stays. */
    std::string fix(std::vector<const char*> ctx, const char* keys, std::map<int, std::pair<float, float>> at = {},
                    int level = XK_CORRECT_FLEXIBLE, int* known = nullptr) {
        type(keys);
        std::vector<float> x, y;
        touches(keys, at, x, y);
        char* f = xk_state_fix_typo(s, ctx.data(), static_cast<int>(ctx.size()), x.data(), y.data(),
                                    static_cast<int>(x.size()), level, known);
        std::string out = f ? f : "";
        std::free(f);
        return out;
    }

    /* The best fix whatever the margins. */
    std::string best(std::vector<const char*> ctx, const char* keys, std::map<int, std::pair<float, float>> at = {}) {
        type(keys);
        std::vector<float> x, y;
        touches(keys, at, x, y);
        float lead, among;
        char* f = xk_state_typo_best(s, ctx.data(), static_cast<int>(ctx.size()), x.data(), y.data(),
                                     static_cast<int>(x.size()), XK_CORRECT_FLEXIBLE, &lead, &among);
        std::string out = f ? f : "";
        std::free(f);
        return out;
    }

    void type(const char* keys) {
        xk_state_reset(s);
        for (const char* p = keys; *p; ++p) xk_process_key(s, static_cast<unsigned char>(*p), 0);
    }
   private:
    static void touches(const char* keys, const std::map<int, std::pair<float, float>>& at, std::vector<float>& x,
                        std::vector<float>& y) {
        for (int i = 0; keys[i]; ++i) {
            auto it = at.find(i);
            x.push_back(it == at.end() ? NAN : it->second.first);
            y.push_back(it == at.end() ? NAN : it->second.second);
        }
    }
};

}  // namespace

TEST_CASE("a tap counts for the keys around where it landed", "[typos][touch]") {
    xrnkey::Keyboard kb;
    kb.key_height = 1.4;
    double w[26];
    // In the middle of j: h and k are possible, far less likely than j; q is out of reach.
    xrnkey::tap_weights(kb, 'j', 0.0, 0.0, true, w);
    CHECK(w['j' - 'a'] == 0.0);
    CHECK(w['h' - 'a'] < -1.4);
    CHECK(w['h' - 'a'] > -2.2);
    CHECK_THAT(w['h' - 'a'], Catch::Matchers::WithinAbs(w['k' - 'a'], 1e-9));
    CHECK(w['q' - 'a'] == xrnkey::kFar);
    // On j's left edge, h is about as likely as j; k much less.
    xrnkey::tap_weights(kb, 'j', -0.48, 0.0, true, w);
    CHECK(w['h' - 'a'] > -0.3);
    CHECK(w['k' - 'a'] < -1.8);
    // Not known where it landed: the key's centre.
    double centre[26];
    xrnkey::tap_weights(kb, 'j', 0.3, 0.3, false, centre);
    xrnkey::tap_weights(kb, 'j', 0.0, 0.0, true, w);
    for (int i = 0; i < 26; ++i) CHECK(centre[i] == w[i]);
    // The rows are as far apart as the keys are tall: u, above j, is further off than h.
    xrnkey::tap_weights(kb, 'j', 0.0, 0.0, true, w);
    CHECK(w['u' - 'a'] < w['h' - 'a']);
    // Where the owner's taps on h land on average moves h: a tap there counts for it.
    kb.sweet['h' - 'a'][0] = 0.4;
    double moved[26];
    xrnkey::tap_weights(kb, 'j', -0.3, 0.0, true, moved);
    xrnkey::Keyboard plain;
    plain.key_height = 1.4;
    xrnkey::tap_weights(plain, 'j', -0.3, 0.0, true, w);
    CHECK(moved['h' - 'a'] > w['h' - 'a'] + 0.5);
}

TEST_CASE("Space fixes a word that is no word toward Vietnamese", "[typos][touch]") {
    Typing t;
    REQUIRE(t.t.vi != nullptr);
    CHECK(t.fix({"<s>", "tôi"}, "khpng") == "không");      // o -> p: four letters, no vowel
    CHECK(t.fix({"<s>", "tôi"}, "Khpng") == "Không");      // the case typed
    CHECK(t.fix({"<s>", "chào"}, "bsnj") == "bạn");        // a -> s, the tone key kept
    CHECK(t.fix({"<s>", "chào"}, "bwnj") == "bạn");
    // Marks typed mean what they would have: the keys a slip away go through Telex again.
    CHECK(t.fix({"<s>", "tôi"}, "kjoong", {{1, {-0.45f, 0.0f}}}) == "không");
    CHECK(t.best({"<s>"}, "chapf") == "chào");             // chaof: chào, the f a tone
}

TEST_CASE("where the tap landed decides the fix", "[typos][touch]") {
    Typing t;
    REQUIRE(t.t.vi != nullptr);
    // j for h: on j's left edge it is h; on its right edge, toward k, not.
    CHECK(t.fix({"<s>", "tôi"}, "kjong", {{1, {-0.45f, 0.0f}}}) == "không");
    CHECK(t.fix({"<s>", "tôi"}, "kjong", {{1, {0.45f, 0.0f}}}).empty());
    // Letters with no vowel are an abbreviation, unless a tap landed by a vowel's key.
    CHECK(t.fix({"<s>", "cảm"}, "pwn").empty());
    CHECK(t.fix({"<s>", "cảm"}, "pwn", {{0, {-0.48f, 0.0f}}}) == "ơn");
    CHECK(t.fix({"<s>", "we"}, "cqn").empty());
    CHECK(t.fix({"<s>", "we"}, "cqn", {{1, {0.45f, 0.45f}}}) == "can");
    // Touches that don't match the keys count as unknown.
    std::vector<const char*> ctx = {"<s>", "tôi"};
    t.type("kjong");
    const float x[2] = {0.45f, 0.45f}, y[2] = {0.0f, 0.0f};
    char* f = xk_state_fix_typo(t.s, ctx.data(), 2, x, y, 2, XK_CORRECT_FLEXIBLE, nullptr);
    REQUIRE(f != nullptr);
    CHECK(std::string(f) == "không");
    std::free(f);
}

TEST_CASE("Space's fix leaves words, chat and codes alone", "[typos][touch]") {
    Typing t;
    REQUIRE(t.t.vi != nullptr);
    int known = 0;
    CHECK(t.fix({"<s>", "tôi"}, "khoong", {}, XK_CORRECT_FLEXIBLE, &known).empty());
    CHECK(known == 1);                                   // a word: its taps may teach
    CHECK(t.fix({"<s>", "tôi"}, "khong", {}, XK_CORRECT_FLEXIBLE, &known).empty());  // không without marks
    CHECK(known == 1);
    CHECK(t.fix({"<s>", "tôi"}, "khpng", {}, XK_CORRECT_FLEXIBLE, &known) == "không");
    CHECK(known == 0);
    CHECK(t.fix({"<s>"}, "oke").empty());                // chat meant as typed
    CHECK(t.fix({"<s>"}, "thx").empty());                // no vowel: an abbreviation
    CHECK(t.fix({"<s>"}, "KHPNG").empty());              // capitals throughout: a code
    CHECK(t.fix({"<s>", "tôi"}, "khpng", {}, XK_CORRECT_OFF).empty());
    // The English typos models have from web text are known.
    CHECK(t.fix({"<s>"}, "teh") == "the");
}

TEST_CASE("Space's fix puts the tone where the owner's style does", "[typos][touch]") {
    Typing t;
    REQUIRE(t.t.vi != nullptr);
    // o -> p in "khoer", the tap by o's key: khỏe, or khoẻ in the modern style.
    CHECK(t.best({"<s>", "bạn"}, "khpre", {{2, {-0.48f, 0.0f}}}) == "khỏe");
    xk_state_set_option(t.s, XK_OPT_MODERN_STYLE, 1);
    CHECK(t.best({"<s>", "bạn"}, "khpre", {{2, {-0.48f, 0.0f}}}) == "khoẻ");
}
