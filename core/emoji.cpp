/* The emoji index (data/emoji/emoji-index.tsv: Unicode's list with CLDR's
 * English and Vietnamese names and keywords): the panel's emoji in order,
 * search by keyword in either language with or without accents, and the
 * emoji that go with the words just typed. The suggestion strip's emoji
 * come from a smaller table of exact keywords (data/emoji/keywords.tsv), read
 * into the same index: a word as it stands, or a phrase. */
#include "xrnkey.h"
#include "xrnkey_internal.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

namespace xrnkey {

namespace {

struct Entry {
    std::string emoji, group, en_name, vi_name;
    std::vector<std::string> en_kw, vi_kw;
    bool skin = false;
};

/* Lowercase, and each Vietnamese letter folded to its plain one, so "cuoi"
 * finds "cười". */
std::string fold(const std::string& s) {
    static const std::unordered_map<char32_t, char> table = [] {
        std::unordered_map<char32_t, char> t;
        const char* groups[][2] = {
            {"a", "àáảãạăằắẳẵặâầấẩẫậÀÁẢÃẠĂẰẮẲẴẶÂẦẤẨẪẬ"}, {"e", "èéẻẽẹêềếểễệÈÉẺẼẸÊỀẾỂỄỆ"},
            {"i", "ìíỉĩịÌÍỈĨỊ"}, {"o", "òóỏõọôồốổỗộơờớởỡợÒÓỎÕỌÔỒỐỔỖỘƠỜỚỞỠỢ"},
            {"u", "ùúủũụưừứửữựÙÚỦŨỤƯỪỨỬỮỰ"}, {"y", "ỳýỷỹỵỲÝỶỸỴ"}, {"d", "đĐ"},
        };
        for (const auto& g : groups) {
            const std::string chars = g[1];
            for (size_t i = 0; i < chars.size();) {
                uint32_t cp;
                int len;
                utf8_decode(chars.c_str() + i, &cp, &len);
                t[static_cast<char32_t>(cp)] = g[0][0];
                i += static_cast<size_t>(len);
            }
        }
        return t;
    }();
    std::string out;
    for (size_t i = 0; i < s.size();) {
        uint32_t cp;
        int len;
        utf8_decode(s.c_str() + i, &cp, &len);
        auto it = table.find(static_cast<char32_t>(cp));
        if (it != table.end()) out += it->second;
        else if (cp >= 'A' && cp <= 'Z') out += static_cast<char>(cp + 0x20);
        else out.append(s, i, static_cast<size_t>(len));
        i += static_cast<size_t>(len);
    }
    return out;
}

std::string lower_vi(const std::string& s) {
    /* Lowercase without folding: keywords in the index are lowercase
     * already, and the typed words only need their capitals lowered. */
    std::string out;
    char buf[8];
    for (size_t i = 0; i < s.size();) {
        uint32_t cp;
        int len;
        utf8_decode(s.c_str() + i, &cp, &len);
        char32_t c = static_cast<char32_t>(cp);
        if (c >= 0x1EA0 && c <= 0x1EF9 && !(c & 1)) c += 1;  /* Ạ..Ỹ: upper even, lower odd */
        else if (c >= U'A' && c <= U'Z') c += 0x20;
        else if (c >= 0xC0 && c <= 0xDE && c != 0xD7) c += 0x20;
        else if (c == 0x0102 || c == 0x0110 || c == 0x0128 || c == 0x0168 || c == 0x01A0 || c == 0x01AF)
            c = c == 0x0110 ? 0x0111 : c + 1;
        const int k = utf8_encode(static_cast<uint32_t>(c), buf);
        out.append(buf, static_cast<size_t>(k));
        i += static_cast<size_t>(len);
    }
    return out;
}

std::vector<std::string> split(const std::string& s, char sep) {
    std::vector<std::string> out;
    std::string cur;
    std::istringstream in(s);
    while (std::getline(in, cur, sep))
        if (!cur.empty()) out.push_back(cur);
    return out;
}

/* A word as the keyword table compares it: lowercase, a Vietnamese syllable
 * with the engine's tone placement ("Hoà" and "hòa" alike), anything else
 * ("ok", ":)", "hpbd") lowercase as it is. */
std::string canon(const std::string& word) {
    std::u32string letters;
    int tone;
    if (syllable_split(word, &letters, &tone) && syllable_valid(letters, tone))
        return apply_tone_to_syllable(utf8_of(letters), tone);
    return lower_vi(word);
}

/* The word has no Vietnamese marks (đ counts as one): typed bare. */
bool bare(const std::string& word) { return fold(word) == lower_vi(word); }

}  // namespace

/* The most words a keyword has ("chúc mừng sinh nhật"). */
constexpr int kPhraseWords = 4;

/* A line of the strip's keyword table: its words (canon), its emoji, best
 * first, and whether they take the words' place or go after them. */
struct Keyword {
    std::vector<std::string> words;
    std::vector<std::string> emoji;
    bool replace = false;
};

struct EmojiDB {
    std::vector<Entry> entries;
    /* keyword (lowercase, accents kept) -> entries, for suggestions */
    std::unordered_map<std::string, std::vector<int>> by_keyword;
    std::vector<Keyword> keywords;
    /* one word (canon) -> its keyword */
    std::unordered_map<std::string, int> by_word;
    /* several words, without marks ("chuc mung") -> the keywords they may be */
    std::unordered_map<std::string, std::vector<int>> by_phrase;
    /* a keyword's words joined (canon) -> the keyword, for search */
    std::unordered_map<std::string, int> by_text;
};

namespace {

/* The keyword of the table that the last `len` of `words` are, or -1. One
 * word must be the keyword as it stands; in a phrase, each word as the
 * keyword has it, or typed bare ("chuc mung sinh nhật"). */
int keyword_at(const EmojiDB& d, const char* const* words, int n, int len) {
    std::vector<std::string> typed;
    for (int i = n - len; i < n; ++i) {
        if (!words[i] || !*words[i]) return -1;
        typed.emplace_back(words[i]);
    }
    if (len == 1) {
        auto it = d.by_word.find(canon(typed[0]));
        return it == d.by_word.end() ? -1 : it->second;
    }
    std::string key;
    for (const auto& w : typed) key += (key.empty() ? "" : " ") + fold(w);
    auto it = d.by_phrase.find(key);
    if (it == d.by_phrase.end()) return -1;
    for (int k : it->second) {
        const auto& kw = d.keywords[static_cast<size_t>(k)];
        bool ok = true;
        for (size_t i = 0; ok && i < typed.size(); ++i)
            ok = canon(typed[i]) == kw.words[i] || bare(typed[i]);
        if (ok) return k;
    }
    return -1;
}

int load_keywords(EmojiDB* d, std::istream& f) {
    d->keywords.clear();
    d->by_word.clear();
    d->by_phrase.clear();
    d->by_text.clear();
    std::string line;
    while (std::getline(f, line)) {
        if (line.empty() || (line[0] == '#' && (line.size() == 1 || line[1] == ' '))) continue;
        std::vector<std::string> col;
        std::string cur;
        std::istringstream in(line);
        while (std::getline(in, cur, '\t')) col.push_back(cur);
        if (col.size() < 3 || col[0].empty() || (col[1] != "append" && col[1] != "replace")) continue;
        Keyword kw;
        kw.replace = col[1] == "replace";
        kw.emoji = split(col[2], ' ');
        std::string text, folded;
        for (const auto& w : split(col[0], ' ')) {
            kw.words.push_back(canon(w));
            text += (text.empty() ? "" : " ") + kw.words.back();
            folded += (folded.empty() ? "" : " ") + fold(w);
        }
        if (kw.words.empty() || kw.emoji.empty() || d->by_text.count(text)) continue;
        const int idx = static_cast<int>(d->keywords.size());
        d->by_text[text] = idx;
        if (kw.words.size() == 1) d->by_word[text] = idx;
        else d->by_phrase[folded].push_back(idx);
        d->keywords.push_back(std::move(kw));
    }
    return static_cast<int>(d->keywords.size());
}

}  // namespace

}  // namespace xrnkey

using xrnkey::EmojiDB;

extern "C" {

namespace {

XKEmojiDB* load_from(std::istream& f) {
    auto* db = new EmojiDB;
    std::string line;
    while (std::getline(f, line)) {
        /* "# " starts a comment; "#️⃣" (the keycap) is an emoji. */
        if (line.empty() || (line[0] == '#' && (line.size() == 1 || line[1] == ' '))) continue;
        std::vector<std::string> col;
        std::string cur;
        std::istringstream in(line);
        while (std::getline(in, cur, '\t')) col.push_back(cur);
        if (col.size() < 4 || col[0].empty()) continue;
        col.resize(7);
        xrnkey::Entry e;
        e.emoji = col[0];
        e.group = col[1];
        e.skin = col[2] == "1";
        e.en_name = col[3];
        e.en_kw = xrnkey::split(col[4], '|');
        e.vi_name = col[5];
        e.vi_kw = xrnkey::split(col[6], '|');
        const int idx = static_cast<int>(db->entries.size());
        for (const auto* kws : {&e.vi_kw, &e.en_kw})
            for (const auto& k : *kws) {
                auto& list = db->by_keyword[xrnkey::lower_vi(k)];
                if (list.empty() || list.back() != idx) list.push_back(idx);  /* "ok" in both languages */
            }
        db->entries.push_back(std::move(e));
    }
    if (db->entries.empty()) {
        delete db;
        return nullptr;
    }
    return reinterpret_cast<XKEmojiDB*>(db);
}

}  // namespace

XKEmojiDB* xk_emoji_load(const char* path) {
    if (!path) return nullptr;
    std::ifstream f(path);
    if (!f) return nullptr;
    return load_from(f);
}

XKEmojiDB* xk_emoji_load_text(const char* text, size_t length) {
    if (!text) return nullptr;
    std::istringstream in(std::string(text, length));
    return load_from(in);
}

void xk_emoji_free(XKEmojiDB* db) { delete reinterpret_cast<EmojiDB*>(db); }

int xk_emoji_count(const XKEmojiDB* db) {
    return db ? static_cast<int>(reinterpret_cast<const EmojiDB*>(db)->entries.size()) : 0;
}

int xk_emoji_at(const XKEmojiDB* db, int i, const char** emoji, const char** group,
                const char** name, int* skin_tones) {
    if (!db || i < 0) return 0;
    const auto& v = reinterpret_cast<const EmojiDB*>(db)->entries;
    if (static_cast<size_t>(i) >= v.size()) return 0;
    const auto& e = v[static_cast<size_t>(i)];
    if (emoji) *emoji = e.emoji.c_str();
    if (group) *group = e.group.c_str();
    if (name) *name = e.vi_name.empty() ? e.en_name.c_str() : e.vi_name.c_str();
    if (skin_tones) *skin_tones = e.skin ? 1 : 0;
    return 1;
}

int xk_emoji_search(XKEmojiDB* db, const char* query, char** chars, char** names, int max_results) {
    if (!db || !query || !chars || !names || max_results <= 0) return 0;
    const auto* d = reinterpret_cast<const EmojiDB*>(db);
    const std::string q = xrnkey::fold(query);
    if (q.empty()) return 0;
    struct Match { int idx; int score; };
    std::vector<Match> matches;
    /* The strip's keyword for the query, as typed, first ("haha": 😂). */
    std::string text;
    for (const auto& w : xrnkey::split(query, ' ')) text += (text.empty() ? "" : " ") + xrnkey::canon(w);
    if (auto it = d->by_text.find(text); it != d->by_text.end()) {
        int rank = 0;
        for (const auto& e : d->keywords[static_cast<size_t>(it->second)].emoji)
            for (size_t i = 0; i < d->entries.size(); ++i)
                if (d->entries[i].emoji == e) matches.push_back({static_cast<int>(i), 1000 - rank++});
    }
    const size_t from_table = matches.size();
    for (size_t i = 0; i < d->entries.size(); ++i) {
        const auto& e = d->entries[i];
        int best = 0;
        auto consider = [&](const std::string& text, int exact, int prefix, int inside) {
            const std::string t = xrnkey::fold(text);
            if (t == q) best = std::max(best, exact);
            else if (t.compare(0, q.size(), q) == 0) best = std::max(best, prefix);
            else if (t.find(q) != std::string::npos) best = std::max(best, inside);
        };
        consider(e.vi_name, 100, 60, 30);
        consider(e.en_name, 95, 55, 25);
        for (const auto& k : e.vi_kw) consider(k, 90, 50, 20);
        for (const auto& k : e.en_kw) consider(k, 85, 45, 15);
        const bool listed = std::any_of(matches.begin(), matches.begin() + static_cast<long>(from_table),
                                        [&](const Match& m) { return m.idx == static_cast<int>(i); });
        if (best > 0 && !listed) matches.push_back({static_cast<int>(i), best});
    }
    std::stable_sort(matches.begin(), matches.end(),
                     [](const Match& a, const Match& b) { return a.score > b.score; });
    const int n = std::min(max_results, static_cast<int>(matches.size()));
    for (int i = 0; i < n; ++i) {
        const auto& e = d->entries[static_cast<size_t>(matches[static_cast<size_t>(i)].idx)];
        chars[i] = strdup(e.emoji.c_str());
        names[i] = strdup((e.vi_name.empty() ? e.en_name : e.vi_name).c_str());
    }
    return n;
}

int xk_emoji_suggest(const XKEmojiDB* db, const char* const* words, int n, char** out, int max_out) {
    if (!db || !words || n <= 0 || !out || max_out <= 0) return 0;
    const auto* d = reinterpret_cast<const EmojiDB*>(db);
    /* A keyword shared by many emoji ("mặt", "tay", "face") says little
     * about any; "yêu" (17) and "trái tim" (13) still count. */
    constexpr size_t kTooCommon = 24;
    std::vector<int> picked;
    auto take = [&](const std::string& key) {
        auto it = d->by_keyword.find(xrnkey::lower_vi(key));
        if (it == d->by_keyword.end() || it->second.size() > kTooCommon) return;
        for (int idx : it->second)
            if (std::find(picked.begin(), picked.end(), idx) == picked.end()) picked.push_back(idx);
    };
    /* The last three words together first ("không đời nào"), then the last
     * two ("trái tim"), then the last one. */
    for (int len = std::min(n, 3); len >= 1; --len) {
        std::string key;
        bool ok = true;
        for (int i = n - len; i < n; ++i) {
            if (!words[i] || !*words[i]) ok = false;
            else key += (key.empty() ? "" : " ") + std::string(words[i]);
        }
        if (ok) take(key);
    }
    const int k = std::min(max_out, static_cast<int>(picked.size()));
    for (int i = 0; i < k; ++i) out[i] = strdup(d->entries[static_cast<size_t>(picked[static_cast<size_t>(i)])].emoji.c_str());
    return k;
}

int xk_emoji_load_keywords(XKEmojiDB* db, const char* path) {
    if (!db || !path) return 0;
    std::ifstream f(path);
    return f ? xrnkey::load_keywords(reinterpret_cast<EmojiDB*>(db), f) : 0;
}

int xk_emoji_load_keywords_text(XKEmojiDB* db, const char* text, size_t length) {
    if (!db || !text) return 0;
    std::istringstream in(std::string(text, length));
    return xrnkey::load_keywords(reinterpret_cast<EmojiDB*>(db), in);
}

int xk_emoji_keyword(const XKEmojiDB* db, const char* const* words, int n, char** out, int max_out,
                     int* replace, int* span) {
    if (replace) *replace = 0;
    if (span) *span = 0;
    if (!db || !words || n <= 0 || !out || max_out <= 0) return 0;
    const auto* d = reinterpret_cast<const EmojiDB*>(db);
    /* The longest keyword first: "buồn ngủ" before "ngủ". */
    for (int len = std::min(n, xrnkey::kPhraseWords); len >= 1; --len) {
        const int k = xrnkey::keyword_at(*d, words, n, len);
        if (k < 0) continue;
        const auto& kw = d->keywords[static_cast<size_t>(k)];
        const int m = std::min(max_out, static_cast<int>(kw.emoji.size()));
        for (int i = 0; i < m; ++i) out[i] = strdup(kw.emoji[static_cast<size_t>(i)].c_str());
        if (replace) *replace = kw.replace ? 1 : 0;
        if (span) *span = len;
        return m;
    }
    return 0;
}

}  /* extern "C" */
