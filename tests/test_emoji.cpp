// The emoji index (data/emoji/emoji-index.tsv), as the panel, the search
// and the suggestion strip use it, and the strip's keyword table
// (data/emoji/keywords.tsv).
#include <catch2/catch_test_macros.hpp>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "xrnkey.h"

namespace {

struct Index {
    XKEmojiDB* db = xk_emoji_load(XRNKEY_EMOJI_INDEX);
    ~Index() { xk_emoji_free(db); }
};

/* The index with the strip's keywords. */
struct Keywords : Index {
    int count = xk_emoji_load_keywords(db, XRNKEY_EMOJI_KEYWORDS);
};

/* What the strip offers for `words` (the last as it stands): the emoji, whether they take
 * the words' place, how many words they stand for. */
struct Hit {
    std::vector<std::string> emoji;
    bool replace = false;
    int span = 0;
};

Hit keyword(XKEmojiDB* db, std::vector<const char*> words) {
    char* out[8];
    Hit h;
    int replace = -1;
    const int n = xk_emoji_keyword(db, words.data(), static_cast<int>(words.size()), out, 8, &replace, &h.span);
    for (int i = 0; i < n; ++i) {
        h.emoji.emplace_back(out[i]);
        std::free(out[i]);
    }
    h.replace = replace == 1;
    return h;
}

std::vector<std::string> search(XKEmojiDB* db, const char* q) {
    char* chars[10];
    char* names[10];
    int n = xk_emoji_search(db, q, chars, names, 10);
    std::vector<std::string> out;
    for (int i = 0; i < n; ++i) {
        out.emplace_back(chars[i]);
        std::free(chars[i]);
        std::free(names[i]);
    }
    return out;
}

std::vector<std::string> suggest(XKEmojiDB* db, std::vector<const char*> words) {
    char* out[32];
    int n = xk_emoji_suggest(db, words.data(), static_cast<int>(words.size()), out, 32);
    std::vector<std::string> v;
    for (int i = 0; i < n; ++i) {
        v.emplace_back(out[i]);
        std::free(out[i]);
    }
    return v;
}

bool has(const std::vector<std::string>& v, const std::string& x) {
    for (const auto& s : v)
        if (s == x) return true;
    return false;
}

}  // namespace

TEST_CASE("the panel lists the emoji in Unicode's order with Vietnamese names", "[emoji]") {
    Index x;
    REQUIRE(x.db != nullptr);
    CHECK(xk_emoji_count(x.db) == 1914);  // with the keycap "#️⃣", which starts with '#'
    bool keycap = false;
    for (int i = 0; i < xk_emoji_count(x.db); ++i) {
        const char* k = nullptr;
        xk_emoji_at(x.db, i, &k, nullptr, nullptr, nullptr);
        keycap = keycap || std::string(k) == "#️⃣";
    }
    CHECK(keycap);
    const char *e, *g, *name;
    int skin = -1;
    REQUIRE(xk_emoji_at(x.db, 0, &e, &g, &name, &skin));
    CHECK(std::string(e) == "😀");
    CHECK(std::string(g) == "Smileys & Emotion");
    CHECK(std::string(name) == "mặt cười toe toét");
    CHECK(skin == 0);
    // Skin-tone variants are folded into their base, which says it takes them.
    bool thumbs = false;
    for (int i = 0; i < xk_emoji_count(x.db); ++i) {
        xk_emoji_at(x.db, i, &e, &g, &name, &skin);
        CHECK(std::string(e).find("\U0001F3FD") == std::string::npos);
        if (std::string(e) == "👍") thumbs = skin == 1;
    }
    CHECK(thumbs);
    CHECK(xk_emoji_at(x.db, xk_emoji_count(x.db), &e, &g, &name, &skin) == 0);
    CHECK(xk_emoji_load("/nonexistent/emoji-index.tsv") == nullptr);
}

TEST_CASE("search finds emoji in Vietnamese or English, with or without accents", "[emoji]") {
    Index x;
    REQUIRE(x.db != nullptr);
    CHECK(search(x.db, "cười toe toét").at(0) == "😀");
    CHECK(search(x.db, "cuoi toe toet").at(0) == "😀");
    CHECK(search(x.db, "CA PHE").at(0) == "☕");
    CHECK(search(x.db, "thumbs up").at(0) == "👍");
    // The flag, and the lotus whose CLDR keywords include "Việt Nam".
    CHECK(has(search(x.db, "viet nam"), "🇻🇳"));
    CHECK(has(search(x.db, "viet nam"), "🪷"));
    CHECK(has(search(x.db, "meo"), "🐱"));
    CHECK(search(x.db, "zzqqzz").empty());
    CHECK(search(x.db, "").empty());
}

TEST_CASE("the words just typed suggest emoji, common keywords do not", "[emoji]") {
    Index x;
    REQUIRE(x.db != nullptr);
    CHECK(has(suggest(x.db, {"chúc", "mừng", "sinh", "nhật"}), "🎂"));
    CHECK(has(suggest(x.db, {"uống", "cà", "phê"}), "☕"));
    CHECK(has(suggest(x.db, {"tôi", "yêu"}), "😍"));
    CHECK(has(suggest(x.db, {"Trái", "Tim"}), "❤️"));
    CHECK(suggest(x.db, {"không", "đời", "nào"}).at(0) == "🙅");
    // "ok" is a keyword in both languages: each emoji once.
    auto ok = suggest(x.db, {"ok"});
    for (size_t i = 0; i < ok.size(); ++i)
        for (size_t j = i + 1; j < ok.size(); ++j) CHECK(ok[i] != ok[j]);
    CHECK(suggest(x.db, {"mặt"}).empty());  // too many emoji share it
    CHECK(suggest(x.db, {"bàn", "phím", "qwerty"}).empty());
}

TEST_CASE("the index loads from its text as from its file", "[emoji]") {
    std::ifstream f(XRNKEY_EMOJI_INDEX, std::ios::binary);
    std::string text((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    XKEmojiDB* db = xk_emoji_load_text(text.data(), text.size());
    REQUIRE(db != nullptr);
    CHECK(xk_emoji_count(db) == 1914);
    CHECK(search(db, "cuoi toe toet").at(0) == "😀");
    xk_emoji_free(db);
    CHECK(xk_emoji_load_text("", 0) == nullptr);
    CHECK(xk_emoji_load_text(nullptr, 0) == nullptr);
}

TEST_CASE("one word fires the strip's emoji only as it stands", "[emoji][keywords]") {
    Keywords x;
    REQUIRE(x.db != nullptr);
    REQUIRE(x.count > 1000);
    // A lone fruit typed bare takes the word's place; a feeling goes after it.
    auto cam = keyword(x.db, {"cam"});
    REQUIRE(!cam.emoji.empty());
    CHECK(cam.emoji[0] == "🍊");
    CHECK(cam.replace);
    CHECK(cam.span == 1);
    auto khoc = keyword(x.db, {"khóc"});
    REQUIRE(!khoc.emoji.empty());
    CHECK(khoc.emoji[0] == "😢");
    CHECK(!khoc.replace);
    // Typed bare, a word with marks is another word: "khoc" is not "khóc".
    CHECK(keyword(x.db, {"khoc"}).emoji.empty());
    CHECK(keyword(x.db, {"buon"}).emoji.empty());
    CHECK(keyword(x.db, {"buồn"}).emoji.at(0) == "😞");
    // Any case; the words before do not matter to a keyword of one word.
    CHECK(keyword(x.db, {"KHÓC"}).emoji.at(0) == "😢");
    CHECK(keyword(x.db, {"tôi", "Ok"}).emoji.at(0) == "👌");
    CHECK(keyword(x.db, {"hpbd"}).emoji.at(0) == "🎂");
    // Wherever the tone sits: "hoà" and "hòa", "thuỵ" and "thụy".
    CHECK(keyword(x.db, {"thuỵ", "sĩ"}).emoji.at(0) == "🇨🇭");
    CHECK(keyword(x.db, {"thụy", "sĩ"}).emoji.at(0) == "🇨🇭");
    // Emoticons typed with symbols take their own place.
    auto smile = keyword(x.db, {":)"});
    REQUIRE(!smile.emoji.empty());
    CHECK(smile.emoji[0] == "🙂");
    CHECK(smile.replace);
    CHECK(keyword(x.db, {"=))"}).emoji.at(0) == "🤣");
    CHECK(keyword(x.db, {"<3"}).emoji.at(0) == "❤️");
    CHECK(keyword(x.db, {"zzqqzz"}).emoji.empty());
    CHECK(keyword(x.db, {""}).emoji.empty());
}

TEST_CASE("ordinary words fire no emoji", "[emoji][keywords]") {
    Keywords x;
    REQUIRE(x.db != nullptr);
    // The commonest words, with their marks and typed bare: CLDR's keywords gave "có" a nod,
    // "được" an OK hand, "đỏ" a clipboard.
    for (const char* w : {"có", "co", "không", "khong", "là", "la", "được", "duoc", "của", "cua", "và",
                          "va", "cho", "với", "voi", "đi", "di", "này", "một", "mot", "người", "nguoi",
                          "đã", "da", "đỏ", "do", "nhà", "nha", "mà", "ma", "thì", "thi", "the", "to",
                          "and", "you", "tất", "ví", "khóa", "lịch", "cái", "chơi", "class"})
        CHECK(keyword(x.db, {w}).emoji.empty());
    // Few bare Vietnamese syllables are keywords: each fires for every word typed with those
    // letters ("hoa" is hòa and hóa too). Laban Key, measured, fires for 34 of 1,445.
    std::ifstream f(XRNKEY_EMOJI_KEYWORDS);
    std::string line;
    int bare = 0;
    while (std::getline(f, line)) {
        const std::string k = line.substr(0, line.find('\t'));
        bool ascii = !k.empty() && line[0] != '#';
        for (char c : k) ascii = ascii && c >= 'a' && c <= 'z';
        if (ascii && xk_is_valid_syllable(k.c_str())) ++bare;
    }
    CHECK(bare > 5);
    CHECK(bare <= 20);
}

TEST_CASE("a phrase fires its emoji typed with marks or without", "[emoji][keywords]") {
    Keywords x;
    REQUIRE(x.db != nullptr);
    auto cmsn = keyword(x.db, {"chúc", "mừng", "sinh", "nhật"});
    REQUIRE(!cmsn.emoji.empty());
    CHECK(cmsn.emoji[0] == "🎂");
    CHECK(cmsn.span == 4);
    CHECK(!cmsn.replace);
    CHECK(keyword(x.db, {"chuc", "mung", "sinh", "nhat"}).emoji.at(0) == "🎂");
    CHECK(keyword(x.db, {"Chúc", "mừng", "sinh", "nhat"}).span == 4);  // the last word not marked yet
    CHECK(keyword(x.db, {"cam", "on"}).emoji.at(0) == "🙏");
    CHECK(keyword(x.db, {"bo", "tay"}).emoji.at(0) == "🤦");
    // The longest keyword wins: "buồn ngủ" is sleepy, not sad.
    auto sleepy = keyword(x.db, {"tôi", "buồn", "ngủ"});
    CHECK(sleepy.emoji.at(0) == "😴");
    CHECK(sleepy.span == 2);
    CHECK(keyword(x.db, {"tôi", "buồn"}).span == 1);
    // A word typed with other marks is another word: "cả với" is not "cá voi".
    CHECK(keyword(x.db, {"ca", "voi"}).emoji.at(0) == "🐋");
    CHECK(keyword(x.db, {"cả", "với"}).emoji.empty());
    // A noun of two syllables takes their place.
    auto coffee = keyword(x.db, {"uống", "cà", "phê"});
    CHECK(coffee.emoji.at(0) == "☕");
    CHECK(coffee.replace);
    CHECK(coffee.span == 2);
    CHECK(keyword(x.db, {"chúc", "mừng", "", "nhật"}).span == 0);
}

TEST_CASE("the keyword table is small, and each emoji is in the index", "[emoji][keywords]") {
    Keywords x;
    REQUIRE(x.db != nullptr);
    std::vector<std::string> known;
    for (int i = 0; i < xk_emoji_count(x.db); ++i) {
        const char* e = nullptr;
        xk_emoji_at(x.db, i, &e, nullptr, nullptr, nullptr);
        known.emplace_back(e);
    }
    std::ifstream f(XRNKEY_EMOJI_KEYWORDS);
    std::string line;
    int n = 0, replace = 0;
    while (std::getline(f, line)) {
        if (line.rfind("# ", 0) == 0) continue;
        std::vector<std::string> col;
        size_t at = 0;
        for (size_t tab; (tab = line.find('\t', at)) != std::string::npos; at = tab + 1)
            col.push_back(line.substr(at, tab - at));
        col.push_back(line.substr(at));
        REQUIRE(col.size() == 4);
        CHECK((col[1] == "append" || col[1] == "replace"));
        CHECK((col[3] == "cldr" || col[3] == "hand"));
        replace += col[1] == "replace";
        int emoji = 0;
        size_t from = 0;
        while (from < col[2].size()) {
            size_t sp = col[2].find(' ', from);
            if (sp == std::string::npos) sp = col[2].size();
            CHECK(has(known, col[2].substr(from, sp - from)));
            ++emoji;
            from = sp + 1;
        }
        CHECK((emoji >= 1 && emoji <= 3));
        ++n;
    }
    CHECK(n == x.count);
    CHECK(n < 2500);
    CHECK(replace * 2 < n);  // most go after the word
    // Another table in its place; text as from the file.
    const std::string small = "# a comment\nok\tappend\t👌\nbad line\nhaha\treplace\t😂 🤣\n";
    CHECK(xk_emoji_load_keywords_text(x.db, small.data(), small.size()) == 2);
    CHECK(keyword(x.db, {"haha"}).emoji.size() == 2);
    CHECK(keyword(x.db, {"cam"}).emoji.empty());
    CHECK(xk_emoji_load_keywords(x.db, "/nonexistent/keywords.tsv") == 0);
    CHECK(xk_emoji_load_keywords(nullptr, XRNKEY_EMOJI_KEYWORDS) == 0);
    Index plain;
    CHECK(keyword(plain.db, {"cam"}).emoji.empty());  // no table: no strip emoji
}

TEST_CASE("search puts the strip's keyword first", "[emoji][keywords]") {
    Keywords x;
    REQUIRE(x.db != nullptr);
    CHECK(search(x.db, "haha").at(0) == "😂");
    CHECK(search(x.db, "hpbd").at(0) == "🎂");
    CHECK(search(x.db, "cam").at(0) == "🍊");
    CHECK(search(x.db, ":)").at(0) == "🙂");
    // Each emoji once, and CLDR's matches still follow.
    auto ok = search(x.db, "ok");
    CHECK(ok.at(0) == "👌");
    for (size_t i = 0; i < ok.size(); ++i)
        for (size_t j = i + 1; j < ok.size(); ++j) CHECK(ok[i] != ok[j]);
    CHECK(has(search(x.db, "meo"), "🐱"));
    CHECK(search(x.db, "cuoi toe toet").at(0) == "😀");
}
