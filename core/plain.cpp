/* What Space may change, as Laban Key does it: a word typed without marks is kept unless it
 * isn't a word on its own (xk_plain_word), and a word that is neither Vietnamese nor English
 * gets a confident one-edit fix (xk_fix_wrong_word: "teh" -> "the", but "helo" stays). */
#include "lm_internal.h"
#include "score.h"
#include "xrnkey.h"
#include "xrnkey_internal.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

namespace {

using xrnkey::Lm;
using xrnkey::Models;

std::string lower_ascii(const char* s) {
    std::string w;
    for (const char* p = s; *p; ++p) {
        const unsigned char c = static_cast<unsigned char>(*p);
        if (c >= 0x80) return {};
        w += static_cast<char>(c >= 'A' && c <= 'Z' ? c + 32 : c);
    }
    return w;
}

bool letters_only(const std::string& w) {
    if (w.empty()) return false;
    for (char c : w)
        if (c < 'a' || c > 'z') return false;
    return true;
}

/* The Vietnamese readings of an ASCII shape: unigram log10 of the plain form itself and of
 * its best other reading; false when the shape has no reading. */
bool readings(const Lm& m, const std::string& shape, float* plain, float* best) {
    const char* words[64];
    uint32_t ids[64];
    const int n = xrnkey::lm_shape_candidates(m, shape, words, ids, 64);
    if (n <= 0) return false;
    *plain = -99.0f;
    *best = -99.0f;
    for (int i = 0; i < n; ++i) {
        const float p = ids[i] == xrnkey::lmfmt::kUnk ? -99.0f : xrnkey::lm_logprob(m, nullptr, 0, ids[i]);
        if (shape == words[i]) *plain = std::max(*plain, p);
        else *best = std::max(*best, p);
    }
    return true;
}

}  // namespace

/* English typos common enough that web text (and so a model) has them: their fix is known,
 * whatever the model makes of them. */
const char* xrnkey::known_english_typo(const std::string& w) {
    static const std::pair<const char*, const char*> table[] = {
        {"teh", "the"},     {"hte", "the"},       {"adn", "and"},       {"nad", "and"},
        {"taht", "that"},   {"thta", "that"},     {"thier", "their"},   {"wiht", "with"},
        {"whit", "with"},   {"thsi", "this"},     {"tihs", "this"},     {"jsut", "just"},
        {"knwo", "know"},   {"waht", "what"},     {"wnat", "want"},     {"becuase", "because"},
        {"recieve", "receive"}, {"definately", "definitely"}, {"seperate", "separate"},
        {"untill", "until"}, {"wich", "which"},   {"freind", "friend"}, {"beleive", "believe"},
        {"tommorow", "tomorrow"}, {"tomorow", "tomorrow"}, {"occured", "occurred"},
        {"goign", "going"}, {"thnak", "thank"},   {"thnaks", "thanks"}, {"pleae", "please"},
    };
    for (const auto& [typo, fix] : table)
        if (w == typo) return fix;
    return nullptr;
}

namespace {

bool english(const Models& m, const std::string& w) {
    if (xrnkey::lexicon_is_english(w)) return true;
    return m.en && xrnkey::lm_id(*m.en, w) != xrnkey::lmfmt::kUnk &&
           xrnkey::lm_logprob(*m.en, nullptr, 0, xrnkey::lm_id(*m.en, w)) > -5.5f;
}

}  // namespace

extern "C" int xk_plain_word(const XKModels* models, const char* typed, float min_ratio) {
    if (!models || !typed) return 0;
    const auto& m = *reinterpret_cast<const Models*>(models);
    const std::string w = lower_ascii(typed);
    if (!letters_only(w)) return 0;
    if (english(m, w)) return 1;
    if (!xrnkey::is_valid_vn_syllable(w)) return 0;
    std::u32string letters;
    int tone = 0;
    if (!xrnkey::syllable_split(w, &letters, &tone) || !xrnkey::lexicon_is_attested(letters, tone)) return 0;
    if (!m.vi) return 1;
    float plain, best;
    if (!readings(*m.vi, w, &plain, &best)) return 1;
    /* A plain form far rarer than its best reading is that reading typed without marks
     * ("khong" for "không"); one about as common is a word of its own ("ong", "ban"). */
    return plain - best >= min_ratio ? 1 : 0;
}

extern "C" char* xk_fix_wrong_word(const XKModels* models, const char* typed, float min_margin) {
    if (!models || !typed) return nullptr;
    const auto& m = *reinterpret_cast<const Models*>(models);
    if (!m.en) return nullptr;
    const std::string w = lower_ascii(typed);
    if (!letters_only(w) || w.size() < 3) return nullptr;
    /* No vowel: an abbreviation ("thx", "pls") or letters typed for their own sake ("fff"),
     * not a word with a slip in it. Laban Key leaves them as typed; "thx" used to be "the". */
    if (w.find_first_of("aeiouy") == std::string::npos) return nullptr;
    /* Chat meant as typed: "hehe" used to become "here" and "oke" "one". */
    if (xrnkey::keep_as_typed(w)) return nullptr;
    /* A shape with Vietnamese readings is Vietnamese typed without marks, not a typo. */
    float plain, best;
    if (m.vi && readings(*m.vi, w, &plain, &best)) return nullptr;
    if (const char* fix = xrnkey::known_english_typo(w)) return strdup(fix);
    /* Right in English: nothing to fix. */
    if (english(m, w)) return nullptr;
    std::vector<std::string> edits;
    const std::string abc = "abcdefghijklmnopqrstuvwxyz";
    for (size_t i = 0; i < w.size(); ++i) edits.push_back(w.substr(0, i) + w.substr(i + 1));
    for (size_t i = 0; i + 1 < w.size(); ++i) {
        std::string t = w;
        std::swap(t[i], t[i + 1]);
        edits.push_back(t);
    }
    for (size_t i = 0; i < w.size(); ++i)
        for (char c : abc) {
            if (c == w[i]) continue;
            std::string t = w;
            t[i] = c;
            edits.push_back(t);
        }
    for (size_t i = 0; i <= w.size(); ++i)
        for (char c : abc) edits.push_back(w.substr(0, i) + c + w.substr(i));
    std::sort(edits.begin(), edits.end());
    edits.erase(std::unique(edits.begin(), edits.end()), edits.end());
    float first = -99.0f, second = -99.0f;
    std::string fix;
    for (const auto& e : edits) {
        const uint32_t id = xrnkey::lm_id(*m.en, e);
        if (id == xrnkey::lmfmt::kUnk) continue;
        const float p = xrnkey::lm_logprob(*m.en, nullptr, 0, id);
        if (p > first) {
            second = first;
            first = p;
            fix = e;
        } else if (p > second) {
            second = p;
        }
    }
    /* Confident: one candidate stands well above the rest ("the" for "teh"); several close
     * ones ("help", "held", "hello" for "helo") leave the word as it was typed. */
    if (fix.empty() || first < -4.5f || first - second < min_margin) return nullptr;
    return strdup(fix.c_str());
}
