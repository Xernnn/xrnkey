#include <catch2/catch_test_macros.hpp>
#include <xrnkey.h>
#include <cstring>
#include <string>

static XKState* make_telex() { return xk_state_new(XK_MODE_TELEX); }

static std::string process_string(XKState* s, const char* input) {
    std::string result;
    for (const char* p = input; *p; ++p) {
        XKResult r = xk_process_key(s, static_cast<uint32_t>(*p), 0);
        if (r.commit[0]) result += r.commit;
    }
    XKResult r = xk_process_key(s, 0x20, 0);
    if (r.commit[0]) result += r.commit;
    return result;
}

TEST_CASE("Telex basic vowels", "[telex]") {
    /* Catch2 runs the test case once per SECTION, so a state made out here
     * leaked on every run whose section makes its own. */
    XKState* s = nullptr;

    SECTION("aa -> â") {
        s = make_telex();
        XKResult r = xk_process_key(s, 'a', 0);
        r = xk_process_key(s, 'a', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }

    SECTION("ee -> ê") {
        s = make_telex();
        xk_process_key(s, 'e', 0);
        XKResult r = xk_process_key(s, 'e', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }

    SECTION("oo -> ô") {
        s = make_telex();
        xk_process_key(s, 'o', 0);
        XKResult r = xk_process_key(s, 'o', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }

    SECTION("dd -> đ") {
        s = make_telex();
        xk_process_key(s, 'd', 0);
        XKResult r = xk_process_key(s, 'd', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }

    SECTION("ow -> ơ") {
        s = make_telex();
        xk_process_key(s, 'o', 0);
        XKResult r = xk_process_key(s, 'w', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }

    SECTION("aw -> ă") {
        s = make_telex();
        xk_process_key(s, 'a', 0);
        XKResult r = xk_process_key(s, 'w', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }
}

TEST_CASE("Telex tone marks", "[telex]") {
    SECTION("as -> á (sắc)") {
        XKState* s = make_telex();
        xk_process_key(s, 'a', 0);
        XKResult r = xk_process_key(s, 's', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }

    SECTION("af -> à (huyền)") {
        XKState* s = make_telex();
        xk_process_key(s, 'a', 0);
        XKResult r = xk_process_key(s, 'f', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }

    SECTION("ar -> ả (hỏi)") {
        XKState* s = make_telex();
        xk_process_key(s, 'a', 0);
        XKResult r = xk_process_key(s, 'r', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }

    SECTION("ax -> ã (ngã)") {
        XKState* s = make_telex();
        xk_process_key(s, 'a', 0);
        XKResult r = xk_process_key(s, 'x', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }

    SECTION("aj -> ạ (nặng)") {
        XKState* s = make_telex();
        xk_process_key(s, 'a', 0);
        XKResult r = xk_process_key(s, 'j', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }
}

TEST_CASE("Telex word composition", "[telex]") {
    SECTION("xin chao -> xin chào") {
        XKState* s = make_telex();
        std::string result = process_string(s, "xin chao");
        REQUIRE(result.find("xin") != std::string::npos);
        xk_state_free(s);
    }

    SECTION("viet nam") {
        XKState* s = make_telex();
        std::string result = process_string(s, "viet nam");
        REQUIRE(result.find("viet") != std::string::npos);
        xk_state_free(s);
    }
}

TEST_CASE("Telex backspace undo", "[telex]") {
    XKState* s = make_telex();
    xk_process_key(s, 'a', 0);
    xk_process_key(s, 'a', 0);
    XKResult r = xk_process_key(s, 0xFF08, 0);
    REQUIRE(r.consumed == 1);
    xk_state_free(s);
}

TEST_CASE("Telex pass-through English", "[telex]") {
    XKState* s = make_telex();
    std::string result = process_string(s, "hello");
    REQUIRE(result.find("hello") != std::string::npos);
    xk_state_free(s);
}

TEST_CASE("Telex Ctrl+Shift toggle", "[telex]") {
    XKState* s = make_telex();
    XKResult r = xk_process_key(s, 0, 0x05);
    REQUIRE(r.consumed == 1);
    REQUIRE(r.reset == 1);
    xk_state_free(s);
}

TEST_CASE("Telex multiple vowels", "[telex]") {
    SECTION("uuw -> ư") {
        XKState* s = make_telex();
        xk_process_key(s, 'u', 0);
        xk_process_key(s, 'u', 0);
        XKResult r = xk_process_key(s, 'w', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }
}

TEST_CASE("Telex tone on multi-char syllable", "[telex]") {
    SECTION("tooi -> tôi with tone") {
        XKState* s = make_telex();
        xk_process_key(s, 't', 0);
        xk_process_key(s, 'o', 0);
        xk_process_key(s, 'o', 0);
        xk_process_key(s, 'i', 0);
        XKResult r = xk_process_key(s, 's', 0);
        REQUIRE(r.consumed == 1);
        xk_state_free(s);
    }
}

/* Regression: Telex `w` is a syllable-level modifier. It used to fire only
 * when the buffer ENDED in o/a/u, so "nguoiw" (ending in 'i') appended a
 * literal w and a following tone key landed on the wrong vowel --
 * "nguoiwf" came out "nguoìw". It also horned one vowel and stopped, so the
 * "uo" cluster Vietnamese writes "ươ" came out "uơ". */
TEST_CASE("Telex w applies across the whole vowel nucleus", "[telex]") {
    /* process_string() finalizes with Space, so expectations carry it. */
    SECTION("uo takes a horn on both vowels") {
        XKState* s = make_telex();
        REQUIRE(process_string(s, "uow") == "ươ "); /* ươ */
        xk_state_free(s);
    }

    SECTION("w reaches the nucleus from after a later nucleus vowel") {
        XKState* s = make_telex();
        REQUIRE(process_string(s, "nguoiwf") == "người "); /* người */
        xk_state_free(s);
    }

    SECTION("w reaches the nucleus from behind the coda") {
        XKState* s = make_telex();
        REQUIRE(process_string(s, "duongw") == "dương "); /* dương */
        xk_state_free(s);
    }

    SECTION("canonical spelling still works") {
        XKState* s = make_telex();
        REQUIRE(process_string(s, "nguwowif") == "người "); /* người */
        xk_state_free(s);
    }

    SECTION("w stays literal when the nucleus takes no mark") {
        XKState* s = make_telex();
        REQUIRE(process_string(s, "new") == "new ");
        xk_state_free(s);
    }
}

/* Bilingual auto-switch: a tone key must not fire inside a word Vietnamese
 * could not spell. "word" used to become "wỏd" because 'r' followed a vowel,
 * and "hello" became "helló". The decision comes from the real onset/coda
 * tables via can_begin_vn_syllable(), not from counting vowel-less letters --
 * that older heuristic rejected "ngh", so "nghieeng" never reached
 * "nghiêng". */
TEST_CASE("Telex leaves English words alone", "[telex]") {
    struct Case { const char* keys; const char* want; };
    /* Impossible onsets (w-, sh-, st-, br-, j-, z-, fl-) and impossible
     * codas (hel-, wor-, hel-p) each rule the word out. */
    const Case cases[] = {
        {"word", "word "},   {"show", "show "},   {"hello", "hello "},
        {"star", "star "},   {"brown", "brown "}, {"just", "just "},
        {"zero", "zero "},   {"flow", "flow "},   {"world", "world "},
        {"help", "help "},   {"first", "first "}, {"stress", "stress "},
    };
    for (const auto& c : cases) {
        XKState* s = make_telex();
        INFO("typing " << c.keys);
        REQUIRE(process_string(s, c.keys) == c.want);
        xk_state_free(s);
    }
}

TEST_CASE("Telex still composes real Vietnamese syllables", "[telex]") {
    struct Case { const char* keys; const char* want; };
    const Case cases[] = {
        {"xin", "xin "},
        {"chaof", "chào "},
        {"vieejt", "việt "},
        {"khoong", "không "},
        {"quoocs", "quốc "},
        {"ddaau", "đâu "},
        /* "ngh" is a real onset -- the old vowel-less-letter count rejected it */
        {"nghieeng", "nghiêng "},
        {"nghee", "nghê "},
    };
    for (const auto& c : cases) {
        XKState* s = make_telex();
        INFO("typing " << c.keys);
        REQUIRE(process_string(s, c.keys) == c.want);
        xk_state_free(s);
    }
}

/* Chat spells words outside quốc ngữ, typing their marks on purpose: "đơu"
 * for đâu, "zậy" and "dzậy" for vậy, "kái" for cái, "ngèo" for nghèo, "wá"
 * for quá. They used to fall back to their keys as an English word does once
 * the spelling went wrong ("ddowu", "kais", "ngeof"), or never took the mark
 * ("zaa"). Typing now takes Vietnamese onsets (and z, dz, w, f, j) before any
 * rime, so marks and tones always land; a word whose keys are English still
 * ends as English, and onsets or codas Vietnamese has none of still leave a
 * word as typed. */
TEST_CASE("Telex keeps the spellings chat uses", "[telex]") {
    struct Case { const char* keys; const char* want; };
    const Case cases[] = {
        {"ddowu", "đơu "},   {"ddowus", "đớu "},  {"ddoou", "đôu "},
        {"zaayj", "zậy "},   {"dzaayj", "dzậy "}, {"zeef", "zề "},
        {"zui", "zui "},     {"kais", "kái "},    {"ngeof", "ngèo "},
        {"quaas", "quấ "},   {"moaf", "mòa "},    {"fair", "fair "},
        {"zoom", "zoom "},   {"zone", "zone "},   {"zero", "zero "},
        {"text", "text "},   {"user", "user "},   {"law", "law "},
        {"just", "just "},   {"word", "word "},   {"ckoongf", "ckoongf "},
    };
    for (const auto& c : cases) {
        XKState* s = make_telex();
        INFO("typing " << c.keys);
        REQUIRE(process_string(s, c.keys) == c.want);
        xk_state_free(s);
    }
    /* With w alone a letter (Laban Key's simple Telex): "wá" while typed;
     * its keys are English, so Space ends it "was". */
    XKState* s = make_telex();
    xk_state_set_option(s, XK_OPT_W_SHORTCUT, 0);
    REQUIRE(process_string(s, "waf") == "wà ");
    std::string shown;
    for (const char* p = "was"; *p; ++p) shown = xk_process_key(s, static_cast<uint32_t>(*p), 0).preedit;
    REQUIRE(shown == "wá");
    REQUIRE(std::string(xk_process_key(s, 0x20, 0).commit) == "was ");
    xk_state_free(s);
}

/* Typed for feeling, a word goes on in its last letter: Laban Key keeps the syllable, its tone
 * where it was, and the letters after it as typed, Space too. These are what it showed on the
 * owner's phone. This showed the keys once the word was no syllable ("naofoooooo"); after
 * BackSpace reopened "nào", the tone key among them ("nafoooo"). */
TEST_CASE("A word stretched for feeling stays its syllable, as in Laban Key", "[telex]") {
    struct Case { const char* keys; const char* want; };
    const Case cases[] = {
        {"naofoooooo", "nàooooooo"}, {"vaixiiiiii", "vãiiiiiii"}, {"trowfiiiii", "trờiiiii"},
        {"ddaayyyyyy", "đâyyyyyy"},  {"owiiiiii", "ơiiiiii"},     {"khoongggg", "khôngggg"},
        {"uwfuuuu", "ừuuuu"},        {"naofo", "nàoo"},           {"Naofooo", "Nàoooo"},
        // An a after "quá" is "quấ" while that can still be a syllable; the next takes it back.
        {"quasaaaaa", "quáaaaa"},    {"dajaaaaa", "dạaaaa"},
        // As before, and as in Laban Key: no syllable before the run, or its mark taken back.
        {"naoooooof", "naoooooof"},  {"vaiiiiiix", "vaiiiiiix"},  {"hahaaaaaa", "hahaaaaaa"},
        {"tooooo", "toooo"},         {"ddeeeeee", "đeeeee"},      {"hixxxxx", "hixxxx"},
        // An English word with a key too many is a slip in it, not a syllable stretched.
        {"costt", "costt"},          {"lastt", "lastt"},          {"thesee", "these"},
    };
    for (const auto& c : cases) {
        XKState* s = make_telex();
        xk_state_set_option(s, XK_OPT_W_SHORTCUT, 0);
        INFO("typing " << c.keys);
        std::string shown;
        for (const char* p = c.keys; *p; ++p) shown = xk_process_key(s, static_cast<uint32_t>(*p), 0).preedit;
        if (std::string(c.keys) != "thesee") CHECK(shown == c.want);
        CHECK(std::string(xk_process_key(s, 0x20, 0).commit) == std::string(c.want) + " ");
        xk_state_free(s);
    }
    // "nào", BackSpace, then o's: the o that went comes back, and the rest are stretched.
    XKState* s = make_telex();
    std::string shown;
    for (const char* p = "naof"; *p; ++p) xk_process_key(s, static_cast<uint32_t>(*p), 0);
    xk_process_key(s, 0xFF08, 0);
    for (const char* p = "oooo"; *p; ++p) shown = xk_process_key(s, static_cast<uint32_t>(*p), 0).preedit;
    CHECK(shown == "nàoooo");
    xk_state_free(s);
    // Without auto-restore the tone stays on its syllable, not over the o's.
    s = make_telex();
    xk_state_set_option(s, XK_OPT_AUTO_RESTORE, 0);
    REQUIRE(process_string(s, "naofooo") == "nàoooo ");
    xk_state_free(s);
}

/* The phone's English keyboard: no marks at all, so Telex's keys are letters
 * ("vieetj", "off", "text"); a digit or an apostrophe stays inside the word;
 * punctuation and Space end it as typed. */
TEST_CASE("Plain mode types letters as they are", "[telex]") {
    struct Case { const char* keys; const char* want; };
    const Case cases[] = {
        {"vieetj", "vieetj "}, {"off", "off "},   {"text", "text "}, {"of", "of "},
        {"don't", "don't "},   {"mp3", "mp3 "},   {"ddaau", "ddaau "},
    };
    for (const auto& c : cases) {
        XKState* s = xk_state_new(XK_MODE_PLAIN);
        INFO("typing " << c.keys);
        REQUIRE(process_string(s, c.keys) == c.want);
        xk_state_free(s);
    }
    XKState* s = xk_state_new(XK_MODE_PLAIN);
    REQUIRE(process_string(s, "hi,") == "hi,");  /* the comma ends "hi"; Space then goes to the app */
    xk_state_free(s);
}

/* Enter used to be unhandled: it reached the application while the composing
 * block was still pending, so the word was silently abandoned. It then
 * registered the word but swallowed the Enter, so every line needed two.
 * It now commits the pending syllable the way Space does, minus the
 * trailing character, and still reaches the application. */
TEST_CASE("Enter registers the pending word", "[telex]") {
    SECTION("Enter commits the composed syllable and passes through") {
        XKState* s = make_telex();
        for (const char* p = "chaof"; *p; ++p) {
            xk_process_key(s, static_cast<uint32_t>(*p), 0);
        }
        XKResult r = xk_process_key(s, 0xFF0D, 0); /* Return */
        REQUIRE(std::string(r.commit) == "chào");
        REQUIRE(r.consumed == 0);
        xk_state_free(s);
    }

    SECTION("Space still appends its space") {
        XKState* s = make_telex();
        for (const char* p = "chaof"; *p; ++p) {
            xk_process_key(s, static_cast<uint32_t>(*p), 0);
        }
        XKResult r = xk_process_key(s, 0x20, 0);
        REQUIRE(std::string(r.commit) == "chào ");
        xk_state_free(s);
    }

    SECTION("Enter with nothing pending passes through unconsumed") {
        XKState* s = make_telex();
        XKResult r = xk_process_key(s, 0xFF0D, 0);
        REQUIRE(r.consumed == 0);
        REQUIRE(r.commit[0] == '\0');
        xk_state_free(s);
    }
}

/* Found by round-tripping the whole dictionary: keystrokes -> engine -> word.
 * Open syllables took the tone on the FIRST vowel, which is right for two
 * vowels but wrong for a triphthong -- "ngoai"+f gave "ngòai". And "DD"
 * always produced lowercase đ. */
TEST_CASE("Telex tone lands correctly on open triphthongs", "[telex]") {
    struct Case { const char* keys; const char* want; };
    const Case cases[] = {
        {"ngoaif", "ngoài "},  {"xoaif", "xoài "},   {"loaij", "loại "},
        {"thoaij", "thoại "},  {"ngoaij", "ngoại "},
        /* two-vowel open syllables must be unchanged */
        {"chaof", "chào "},    {"hoaf", "hòa "},     {"cuar", "của "},
        /* coda still takes the last vowel */
        {"toans", "toán "},
    };
    for (const auto& c : cases) {
        XKState* s = make_telex();
        INFO("typing " << c.keys);
        REQUIRE(process_string(s, c.keys) == c.want);
        xk_state_free(s);
    }
}

TEST_CASE("Telex DD keeps its case", "[telex]") {
    XKState* s = make_telex();
    REQUIRE(process_string(s, "DDaf") == "Đà ");
    xk_state_free(s);
    s = make_telex();
    REQUIRE(process_string(s, "ddaf") == "đà ");
    xk_state_free(s);
}
