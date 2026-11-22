/* Completions of a word typed in part (xk_complete): the strip's guesses at the rest of it, as
 * Laban Key offers them ("hel": help, hello, held; "xin": xinh).
 *
 * The English model's vocabulary is sorted by bytes, so the words that start with the typed
 * letters are one range of it ("hel" -> "hello"). The Vietnamese model's shape index is sorted
 * too, so the shapes that start with them are one range of it, and each shape lists its
 * accented syllables ("xin" -> "xinh" -> xinh, xính). The commonest of each range, by unigram, go on to be scored in context by the mixture
 * that ranks next words (score.cpp), so a completion agrees with what restoration and the next
 * words make of the same text. The owner's personal words join in too. */
#include "learn.h"
#include "score.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <string>
#include <string_view>
#include <unordered_set>
#include <utility>
#include <vector>

namespace xrnkey {
namespace {

/* How many of each range's commonest words are scored in context. A one-letter range holds
 * thousands; its unigram pass is a read of one float per word. */
constexpr size_t kPerSource = 24;

bool az(std::string_view w) {
    if (w.empty()) return false;
    for (char c : w)
        if (c < 'a' || c > 'z') return false;
    return true;
}

/* The `keep` best of `all` by prior (log10 unigram). */
void keep_best(std::vector<std::pair<float, uint32_t>>& all, size_t keep) {
    const auto by_prior = [](const auto& a, const auto& b) {
        return a.first != b.first ? a.first > b.first : a.second < b.second;
    };
    if (all.size() > keep) {
        std::nth_element(all.begin(), all.begin() + static_cast<std::ptrdiff_t>(keep), all.end(), by_prior);
        all.resize(keep);
    }
    std::sort(all.begin(), all.end(), by_prior);
}

/* The English model's words longer than `prefix` that start with it and are in the list of
 * common English words: the model's vocabulary, lowercase, is full of names from news text
 * ("helen" before "hello"), and a name is no completion. */
void english_words(const Lm& m, const std::string& prefix, std::vector<std::string>& out) {
    uint32_t lo, hi;
    lm_prefix_range(m, prefix, &lo, &hi);
    std::vector<std::pair<float, uint32_t>> all;
    all.reserve(hi - lo);
    for (uint32_t pos = lo; pos < hi; ++pos) {
        const uint32_t id = lm_sorted_id(m, pos);
        all.emplace_back(lm_prob_at(m, 1, id), id);
    }
    /* The commonest first, until enough are words: a heap, so a long range isn't sorted. */
    const auto worse = [](const auto& a, const auto& b) {
        return a.first != b.first ? a.first < b.first : a.second > b.second;
    };
    std::make_heap(all.begin(), all.end(), worse);
    size_t found = 0;
    for (auto end = all.end(); end != all.begin() && found < kPerSource; --end) {
        std::pop_heap(all.begin(), end, worse);
        const std::string w = lm_word(m, (end - 1)->second);
        if (w.size() > prefix.size() && az(w) && lexicon_is_english(w)) {
            out.push_back(w);
            ++found;
        }
    }
}

/* The syllables whose shape is longer than `prefix` and starts with it. */
void syllables(const Lm& m, const std::string& prefix, std::vector<std::string>& out) {
    uint64_t lo, hi;
    if (!lm_shape_prefix_range(m, prefix, &lo, &hi)) return;
    std::vector<std::pair<float, uint32_t>> all;
    const char* words[64];
    uint32_t ids[64];
    for (uint64_t pos = lo; pos < hi; ++pos) {
        if (std::strlen(lm_shape_at(m, pos)) <= prefix.size()) continue;
        const int n = lm_shape_candidates_at(m, pos, words, ids, 64);
        for (int i = 0; i < n; ++i)
            if (ids[i] != lmfmt::kUnk) all.emplace_back(lm_prob_at(m, 1, ids[i]), ids[i]);
    }
    keep_best(all, kPerSource);
    for (const auto& [prior, id] : all) out.emplace_back(lm_word(m, id));
}

/* The owner's own words (learned twice) that complete `prefix`, as typed or without marks. */
void personal(const User& u, const std::string& prefix, std::vector<std::string>& out) {
    for (const auto& [word, count] : u.words) {
        if (count < 2.0) continue;
        const std::string key = az(word) ? word : syllable_shape(word);
        if (key.size() > prefix.size() && key.compare(0, prefix.size(), prefix) == 0) out.push_back(word);
    }
}

}  // namespace
}  // namespace xrnkey

using xrnkey::Models;

extern "C" int xk_complete(const XKModels* models, const char* const* context, int n, const char* typed,
                           char** out, float* logprobs, int max) {
    if (!models || !out || max <= 0) return 0;
    const auto& m = *reinterpret_cast<const Models*>(models);
    std::string prefix;
    xrnkey::Case cs;
    if (!xrnkey::typed_word(typed, &prefix, &cs)) return 0;
    std::vector<std::string> cand;
    if (m.en) xrnkey::english_words(*m.en, prefix, cand);
    if (m.vi) xrnkey::syllables(*m.vi, prefix, cand);
    if (m.user) xrnkey::personal(*m.user, prefix, cand);
    const xrnkey::Context c = xrnkey::context_from(&m, context, n, n == 0);
    std::unordered_set<std::string> seen;
    std::vector<std::pair<double, std::string>> scored;
    for (const auto& w : cand)
        if (seen.insert(w).second) scored.emplace_back(xrnkey::mix_logprob(m, c, w), w);
    std::sort(scored.begin(), scored.end(), [](const auto& a, const auto& b) {
        return a.first != b.first ? a.first > b.first : a.second < b.second;
    });
    int k = 0;
    for (const auto& [score, w] : scored) {
        if (k >= max) break;
        const std::string t = xrnkey::with_case(w, cs);
        out[k] = static_cast<char*>(std::malloc(t.size() + 1));
        if (!out[k]) break;
        std::memcpy(out[k], t.c_str(), t.size() + 1);
        if (logprobs) logprobs[k] = static_cast<float>(score);
        ++k;
    }
    return k;
}
