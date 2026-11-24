/* Typo fixes on Space that know where each finger landed (xk_state_fix_typo), as LatinIME and
 * Laban Key do them.
 *
 * Only a word that isn't one is fixed: not a Vietnamese syllable (with marks, or typed without
 * them and so standing for some), not English, not one of the owner's words, not chat meant as
 * typed ("oke", "hihi"). A real word stays, whatever slip made it.
 *
 * The fixes tried are the key sequences one or two slips away from the keys typed: a tap meant
 * for a neighbouring key (weighed by where the tap landed, keys.cpp), a key left out, one too
 * many, two swapped. Each sequence goes through Telex again, so its marks mean what they would
 * have ("chapf" -> "chaof" -> "chào"), and stands for the words it spells: a syllable with its
 * marks, the accented readings of letters typed without any, an English word. Each word is
 * scored in context by the models' mixture (score.cpp) plus what its slips cost, LatinIME's
 * noisy channel, and so is leaving the word as typed: the models' share for a word they don't
 * know. The best fix replaces the word only when it leads leaving it by the level's margin and
 * is the likely one among the rest. */
#include "keys.h"
#include "learn.h"
#include "lm_internal.h"
#include "score.h"
#include "xrnkey_internal.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <string_view>
#include <unordered_map>
#include <utility>
#include <vector>

namespace xrnkey {
namespace {

using lmfmt::kUnk;

/* What the slips that aren't a neighbour's key cost, log10 against typing the word right. As in
 * LatinIME's typing weights, a doubled letter typed once, or a key typed twice, is the cheapest
 * of them. */
constexpr double kOmit = -2.6;        /* a key left out */
constexpr double kOmitDouble = -2.2;  /* a doubled letter typed once ("helo") */
constexpr double kExtra = -2.6;       /* a key too many */
constexpr double kExtraTwice = -2.0;  /* the same key twice ("chaoo") */
constexpr double kExtraNear = -2.3;   /* the next or last key's neighbour brushed too */
constexpr double kSwap = -2.4;        /* two keys swapped ("teh") */
/* A neighbour worth trying for one tap, and the least a fix's slips may cost together. */
constexpr double kSubFloor = -3.5;
constexpr double kChannelFloor = -6.0;
/* A vowel's key this likely makes letters with no vowel a slip, not an abbreviation. */
constexpr double kVowelEdge = -0.6;
/* A neighbour that likely (the tap near its edge) may come with a second slip of another kind. */
constexpr double kPairedSub = -1.2;
/* Leaving the word as typed scores the models' share for a word they don't know; a spelling
 * chat uses ("kà") is likelier typed so on purpose, by this much. */
constexpr double kChatSpelling = 1.5;
/* A syllable the Vietnamese model knows counts as a word from this unigram up: the model holds
 * the odd typo from web text as a token of its own. */
constexpr float kKnownSyllable = -6.5f;
/* What the best fix must lead by, at each level: over leaving the word (log10), and its share
 * of all the readings, leaving the word included. Tuned on the dev text with simulated fingers
 * (tools/xk-typos): Flexible gets about 2 % of mistyped words wrong, Strong about 3 %. */
constexpr double kMargin[3] = {99.0, 0.75, 0.5};
constexpr double kShare[3] = {1.1, 0.8, 0.6};

bool az(std::string_view w) {
    if (w.empty()) return false;
    for (char c : w)
        if (c < 'a' || c > 'z') return false;
    return true;
}

bool has_vowel(std::string_view w) { return w.find_first_of("aeiouy") != std::string_view::npos; }

/* An English word: the common list, or one the English model has often enough (it holds web
 * text's typos too, rarely). */
bool english(const Models& m, const std::string& w) {
    if (lexicon_is_english(w)) return true;
    if (!m.en) return false;
    const uint32_t id = lm_id(*m.en, w);
    return id != kUnk && lm_logprob(*m.en, nullptr, 0, id) > -5.5f;
}

bool personal(const Models& m, const std::string& token) {
    if (!m.user) return false;
    auto it = m.user->words.find(token);
    return it != m.user->words.end() && it->second >= 2.0;
}

/* A syllable (with its marks) that is a word: attested, or one the model knows well enough. */
bool known_syllable(const Models& m, const std::string& token) {
    std::u32string letters;
    int tone;
    if (!syllable_split(token, &letters, &tone) || !syllable_valid(letters, tone)) return false;
    if (lexicon_is_attested(letters, tone)) return true;
    if (!m.vi) return false;
    const uint32_t id = lm_id(*m.vi, token);
    return id != kUnk && lm_logprob(*m.vi, nullptr, 0, id) >= kKnownSyllable;
}

/* `token` (as context_token makes it) is a word as it stands. */
bool is_word(const Models& m, const std::string& token) {
    if (token.empty() || token[0] == '<') return false;
    if (personal(m, token) || keep_as_typed(token)) return true;
    if (az(token)) {
        if (english(m, token)) return true;
        if (m.vi) {
            std::vector<std::pair<std::string_view, uint32_t>> r;
            shape_readings(*m.vi, token, r);
            if (!r.empty()) return true;
            const uint32_t id = lm_id(*m.vi, token);
            if (id != kUnk && lm_logprob(*m.vi, nullptr, 0, id) >= kKnownSyllable) return true;
        }
        return false;
    }
    return known_syllable(m, token);
}

struct Cand {
    std::string text;  /* lowercase, as a model token */
    uint32_t vi, en;
    double channel;
    int slips;
};

/* Keys that can't take a Telex effect: no tone or mark key, no vowel or d typed twice. */
bool plain_keys(std::string_view keys) {
    bool seen[26] = {};
    for (char c : keys) {
        if (std::string_view("sfrxjwz").find(c) != std::string_view::npos) return false;
        if ((c == 'a' || c == 'e' || c == 'o' || c == 'd') && seen[c - 'a']) return false;
        seen[c - 'a'] = true;
    }
    return true;
}

/* The words a key sequence (lowercase) spells, with what its slips cost. */
class Resolver {
   public:
    Resolver(const XKStateImpl& s, const Models& m) : s_(s), m_(m) {}

    void words(const std::string& keys, double channel, int slips, std::vector<Cand>& out) {
        if (s_.mode == XK_MODE_PLAIN) {
            if (english(m_, keys)) add(keys, channel, slips, out);
            return;
        }
        bool effect = false;
        if (!plain_keys(keys)) {
            w_.clear();
            w_.keys = keys;
            word_replay(w_, s_.mode, s_.opt);
            effect = w_.tone != XK_TONE_NONE || w_.letters.size() != keys.size();
            for (const Letter& l : w_.letters) effect = effect || l.mark != MARK_NONE;
        }
        if (!effect) {
            /* Letters without marks: their accented readings, and themselves as English. */
            if (!has_vowel(keys)) return;
            if (m_.vi) {
                readings_.clear();
                shape_readings(*m_.vi, keys, readings_);
                for (const auto& [text, id] : readings_) add(std::string(text), channel, slips, out, id);
            }
            if (english(m_, keys)) add(keys, channel, slips, out);
            return;
        }
        /* Marks typed: the syllable Telex makes of them, and the keys as English ("need"), each
         * a reading of its own for the models to weigh. */
        const std::u32string letters = word_letters(w_);
        if (syllable_valid(letters, w_.tone)) {
            const std::string text = apply_tone_to_syllable(utf8_of(letters), w_.tone);
            if (known_syllable(m_, text)) add(text, channel, slips, out);
        }
        const std::string eff = word_raw_effective(w_);
        if (english(m_, eff)) add(eff, channel, slips, out);
        if (eff != keys && english(m_, keys)) add(keys, channel, slips, out);
    }

   private:
    void add(const std::string& text, double channel, int slips, std::vector<Cand>& out, uint32_t vi = kUnk - 1) {
        const uint32_t vid = vi != kUnk - 1 ? vi : m_.vi ? lm_id(*m_.vi, text) : kUnk;
        const uint32_t eid = m_.en && az(text) ? lm_id(*m_.en, text) : kUnk;
        out.push_back({text, vid, eid, channel, slips});
    }

    const XKStateImpl& s_;
    const Models& m_;
    Word w_;
    std::vector<std::pair<std::string_view, uint32_t>> readings_;
};

struct Fix {
    std::string text;
    double margin = 0;  /* over leaving the word (log10) */
    double share = 0;   /* among every reading, leaving the word included */
    double lead = 0;    /* over leaving the word, a chat spelling's allowance left out */
    double among = 0;   /* among the fixes alone */
};

/* The key sequences one or two slips from `keys`, each with its slips' cost (the likeliest way
 * to it) and count. */
std::unordered_map<std::string, std::pair<double, int>> sequences(const std::string& keys,
                                                                  const std::vector<std::array<double, 26>>& w,
                                                                  int max_slips) {
    std::unordered_map<std::string, std::pair<double, int>> out;
    auto add = [&](std::string k, double cost, int slips) {
        if (cost < kChannelFloor || k.empty()) return;
        auto [it, fresh] = out.try_emplace(std::move(k), cost, slips);
        if (!fresh && cost > it->second.first) it->second = {cost, slips};
    };
    const size_t n = keys.size();
    /* Each tap's likely neighbours, likeliest first. */
    std::vector<std::vector<std::pair<char, double>>> subs(n);
    for (size_t i = 0; i < n; ++i) {
        for (int c = 0; c < 26; ++c)
            if ('a' + c != keys[i] && w[i][static_cast<size_t>(c)] >= kSubFloor)
                subs[i].emplace_back(static_cast<char>('a' + c), w[i][static_cast<size_t>(c)]);
        std::sort(subs[i].begin(), subs[i].end(), [](const auto& a, const auto& b) { return a.second > b.second; });
    }
    /* The slips that aren't a neighbour's key, applied to `k` away from position `skip`; a key
     * left out only as the one slip (with a neighbour's key too, they are rare, and many). */
    auto others = [&](const std::string& k, double base, int slips, size_t skip) {
        const size_t len = k.size();
        for (size_t j = 0; j <= len && slips == 0; ++j)
            for (char c = 'a'; c <= 'z'; ++c) {
                const bool twice = (j > 0 && k[j - 1] == c) || (j < len && k[j] == c);
                add(k.substr(0, j) + c + k.substr(j), base + (twice ? kOmitDouble : kOmit), slips + 1);
            }
        for (size_t i = 0; i < len; ++i) {
            if (i == skip || len < 2) continue;
            double cost = kExtra;
            if ((i > 0 && k[i - 1] == k[i]) || (i + 1 < len && k[i + 1] == k[i])) cost = kExtraTwice;
            else {
                for (size_t nb : {i - 1, i + 1}) {
                    if (nb >= len) continue;
                    const auto& near = keys_near(k[nb]);
                    if (std::find(near.begin(), near.end(), k[i]) != near.end()) cost = kExtraNear;
                }
            }
            add(k.substr(0, i) + k.substr(i + 1), base + cost, slips + 1);
        }
        for (size_t i = 0; i + 1 < len; ++i) {
            if (i == skip || i + 1 == skip || k[i] == k[i + 1]) continue;
            std::string t = k;
            std::swap(t[i], t[i + 1]);
            add(std::move(t), base + kSwap, slips + 1);
        }
    };
    for (size_t i = 0; i < n; ++i)
        for (const auto& [c, cost] : subs[i]) {
            std::string t = keys;
            t[i] = c;
            add(t, cost, 1);
            if (max_slips < 2) continue;
            for (size_t j = i + 1; j < n; ++j)
                for (const auto& [c2, cost2] : subs[j]) {
                    if (cost + cost2 < kChannelFloor) break;
                    std::string t2 = t;
                    t2[j] = c2;
                    add(std::move(t2), cost + cost2, 2);
                }
            if (cost >= kPairedSub) others(t, cost, 1, i);
        }
    others(keys, 0.0, 0, n);
    return out;
}

/* The fix for the word in progress, or none. */
bool find_fix(const XKStateImpl& s, const Models& m, const Context& ctx, const std::string& keys,
              const std::vector<std::array<double, 26>>& w, int level, const std::string& as_typed, bool chat,
              Fix* fix) {
    const int max_slips = keys.size() >= 4 ? 2 : 1;
    const auto seqs = sequences(keys, w, max_slips);
    Resolver resolve(s, m);
    std::vector<Cand> cands;
    for (const auto& [k, cost] : seqs) resolve.words(k, cost.first, cost.second, cands);
    if (cands.empty()) return false;
    /* Each word once, by its likeliest way. */
    std::unordered_map<std::string, double> best;
    for (const Cand& c : cands) {
        if (c.text == as_typed) continue;
        const double score = mix_logprob_ids(m, ctx, c.text, c.vi, c.en) + c.channel;
        auto [it, fresh] = best.try_emplace(c.text, score);
        if (!fresh && score > it->second) it->second = score;
    }
    if (best.empty()) return false;
    const double typed = mix_logprob_ids(m, ctx, as_typed, kUnk, kUnk) + (chat ? kChatSpelling : 0.0);
    const std::string* top = nullptr;
    double top_score = -1e9;
    for (const auto& [text, score] : best)
        if (score > top_score || (score == top_score && top && text < *top)) {
            top = &text;
            top_score = score;
        }
    double fixes = 0;
    for (const auto& [text, score] : best) fixes += std::pow(10.0, score - top_score);
    fix->text = *top;
    fix->margin = top_score - typed;
    fix->share = 1.0 / (fixes + std::pow(10.0, typed - top_score));
    fix->lead = fix->margin + (chat ? kChatSpelling : 0.0);
    fix->among = 1.0 / fixes;
    return fix->margin >= kMargin[level] && fix->share >= kShare[level];
}

/* A syllable as a model token ("hòa") with its tone where the owner's style puts it ("hoà"). */
std::string styled(const std::string& token, bool modern) {
    std::u32string letters;
    int tone;
    if (!modern || !syllable_split(token, &letters, &tone) || tone == XK_TONE_NONE) return token;
    const int carrier = find_tone_vowel_index(utf8_of(letters), true);
    std::string out;
    char buf[8];
    int at = 0;
    for (char32_t c : letters) {
        const int n = utf8_encode(static_cast<uint32_t>(c), buf);
        const char32_t g = at == carrier ? vn_toned(c, tone) : c;
        char put[8];
        const int k = utf8_encode(static_cast<uint32_t>(g), put);
        out.append(put, static_cast<size_t>(k));
        at += n;
    }
    return out;
}

/* What typo_fix found. */
constexpr int kNone = 0, kWord = 1, kFixed = 2, kBelow = 3;

/* The word in progress: a word (kWord), fixed (kFixed, the fix in `fix`), a fix that doesn't
 * lead by the level's margins (kBelow), or nothing to try (kNone). */
int typo_fix(XKState* state, const char* const* context, int ctx_len, const float* touch_x, const float* touch_y,
             int n_touches, int level, Fix* fix) {
    auto* s = reinterpret_cast<XKStateImpl*>(state);
    if (!s || !s->models || s->word.empty() || s->word.literal) return kNone;
    const Models& m = *s->models;
    if (!m.vi && !m.en) return kNone;
    const std::string& raw = s->word.keys;
    std::string keys;
    size_t upper = 0;
    for (char c : raw) {
        if (c >= 'A' && c <= 'Z') {
            ++upper;
            c = static_cast<char>(c | 0x20);
        }
        if (c < 'a' || c > 'z') return kNone;
        keys += c;
    }
    /* What Space commits as things stand, and whether that is a word. */
    const std::string as_typed = context_token(word_final(s->word, s->opt, s));
    level = std::clamp(level, XK_CORRECT_OFF, XK_CORRECT_STRONG);
    const Case cs = upper == 0 ? Case::Lower : (raw[0] >= 'A' && raw[0] <= 'Z') ? Case::Capital : Case::Lower;
    /* The English typos common enough that web text, and so the models, have them. */
    if (level > XK_CORRECT_OFF && az(as_typed) && as_typed == keys)
        if (const char* f = known_english_typo(keys)) {
            fix->text = with_case(f, cs);
            fix->lead = 99.0;
            fix->among = 1.0;
            return kFixed;
        }
    if (is_word(m, as_typed)) return kWord;
    if (level == XK_CORRECT_OFF || keys.size() < 2 || keys.size() > 24) return kNone;
    /* Capitals throughout: an acronym or a code. Chat abbreviations stay, and letters with no
     * vowel are one unless there are four of them ("khpng" is a slip, "thx" is not). */
    if (upper == raw.size() && raw.size() > 1) return kNone;
    if (keep_as_typed(keys) || keep_as_typed(as_typed)) return kNone;

    std::vector<std::array<double, 26>> w(keys.size());
    for (size_t i = 0; i < keys.size(); ++i) {
        const bool have = touch_x && touch_y && n_touches == static_cast<int>(keys.size()) &&
                          !std::isnan(touch_x[i]) && !std::isnan(touch_y[i]);
        tap_weights(m.keyboard, keys[i], have ? touch_x[i] : 0.0, have ? touch_y[i] : 0.0, have, w[i].data());
    }
    if (!has_vowel(keys) && keys.size() < 4) {
        /* Letters with no vowel are an abbreviation ("thx", "ntn"), unless a tap landed by the
         * edge of a vowel's key: "thr" is "the" when the r was hit by the e. */
        bool edge = false;
        for (const auto& t : w)
            for (char v : std::string_view("aeiouy")) edge = edge || t[static_cast<size_t>(v - 'a')] >= kVowelEdge;
        if (!edge) return kNone;
    }
    std::u32string letters;
    int tone;
    const bool chat = !az(as_typed) && syllable_split(as_typed, &letters, &tone) && chat_syllable_valid(letters, tone);
    const Context ctx = context_from(&m, context, ctx_len, false);
    const bool ok = find_fix(*s, m, ctx, keys, w, level, as_typed, chat, fix);
    fix->text = with_case(styled(fix->text, s->opt.modern_style), cs);
    if (fix->text.empty()) return kNone;
    return ok ? kFixed : kBelow;
}

}  // namespace
}  // namespace xrnkey

using namespace xrnkey;

extern "C" {

void xk_models_set_keyboard(XKModels* models, float key_height, const float* sweet_x, const float* sweet_y) {
    if (!models) return;
    Keyboard& kb = reinterpret_cast<Models*>(models)->keyboard;
    kb.key_height = key_height > 0.2f ? key_height : 1.0f;
    for (int i = 0; i < 26; ++i) {
        kb.sweet[i][0] = sweet_x ? std::clamp(static_cast<double>(sweet_x[i]), -0.5, 0.5) : 0.0;
        kb.sweet[i][1] = sweet_y ? std::clamp(static_cast<double>(sweet_y[i]), -0.5, 0.5) : 0.0;
    }
}

char* xk_state_fix_typo(XKState* state, const char* const* context, int ctx_len, const float* touch_x,
                        const float* touch_y, int n_touches, int level, int* known) {
    Fix fix;
    const int r = typo_fix(state, context, ctx_len, touch_x, touch_y, n_touches, level, &fix);
    if (known) *known = r == kWord ? 1 : 0;
    return r == kFixed ? strdup(fix.text.c_str()) : nullptr;
}

char* xk_state_typo_best(XKState* state, const char* const* context, int ctx_len, const float* touch_x,
                         const float* touch_y, int n_touches, int level, float* lead, float* among) {
    Fix fix;
    const int r = typo_fix(state, context, ctx_len, touch_x, touch_y, n_touches, level, &fix);
    if (lead) *lead = static_cast<float>(fix.lead);
    if (among) *among = static_cast<float>(fix.among);
    return (r == kFixed || r == kBelow) && !fix.text.empty() ? strdup(fix.text.c_str()) : nullptr;
}

} /* extern "C" */
