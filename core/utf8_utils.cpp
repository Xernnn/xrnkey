#include "xrnkey_internal.h"

namespace xrnkey {

/* One character from `s`, a NUL-terminated string. A lead byte not followed
 * by its continuation bytes (10xxxxxx) is an error of length 1: the check
 * stops at the first byte that isn't one, the NUL included, so a string cut
 * in the middle of a character is never read past its end. */
int utf8_decode(const char* s, uint32_t* cp, int* len) {
    const auto at = [s](int i) { return static_cast<unsigned char>(s[i]); };
    const auto cont = [&at](int i) { return (at(i) & 0xC0) == 0x80; };
    unsigned char c = at(0);
    if (c < 0x80) { *cp = c; *len = 1; return 0; }
    if ((c & 0xE0) == 0xC0 && cont(1)) {
        *cp = (c & 0x1F) << 6 | (at(1) & 0x3F);
        *len = 2; return 0;
    }
    if ((c & 0xF0) == 0xE0 && cont(1) && cont(2)) {
        *cp = (c & 0x0F) << 12 | (at(1) & 0x3F) << 6 | (at(2) & 0x3F);
        *len = 3; return 0;
    }
    if ((c & 0xF8) == 0xF0 && cont(1) && cont(2) && cont(3)) {
        *cp = (c & 0x07) << 18 | (at(1) & 0x3F) << 12 | (at(2) & 0x3F) << 6 | (at(3) & 0x3F);
        *len = 4; return 0;
    }
    *cp = '?'; *len = 1; return -1;
}

int utf8_encode(uint32_t cp, char* out) {
    if (cp < 0x80) { out[0] = static_cast<char>(cp); return 1; }
    if (cp < 0x800) {
        out[0] = static_cast<char>(0xC0 | (cp >> 6));
        out[1] = static_cast<char>(0x80 | (cp & 0x3F));
        return 2;
    }
    if (cp < 0x10000) {
        out[0] = static_cast<char>(0xE0 | (cp >> 12));
        out[1] = static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out[2] = static_cast<char>(0x80 | (cp & 0x3F));
        return 3;
    }
    out[0] = static_cast<char>(0xF0 | (cp >> 18));
    out[1] = static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
    out[2] = static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
    out[3] = static_cast<char>(0x80 | (cp & 0x3F));
    return 4;
}

uint32_t keysym_to_char(uint32_t keysym) {
    if (keysym >= 0x20 && keysym <= 0x7E) return keysym;
    if (keysym >= 0x0100 && keysym <= 0x10FFFF) return keysym;
    return 0;
}

bool is_vowel_char(char c) {
    return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' ||
           c == 'y' || c == 'A' || c == 'E' || c == 'I' || c == 'O' ||
           c == 'U' || c == 'Y';
}

bool has_vietnamese_vowel_pattern(const std::string& s) {
    for (char c : s) {
        if (is_vowel_char(c)) return true;
    }
    return false;
}

} /* namespace xrnkey */
