/* Where the letter keys are, and how likely one is hit for another: the
 * channel model behind typo correction (restore.cpp).
 *
 * The phone's QWERTY rows, in key widths: q..p from x = 0, a..l from 0.5,
 * z..m from 1.5, one row apart. Aiming at key i, the finger lands on key t
 * with probability
 *
 *   P(t | i) = 1 - e                         when t == i
 *            = e * w(i, t) / sum_t' w(i, t')  otherwise, w = exp(-d^2 / 2s^2)
 *
 * for the keys t within 1.5 key widths of i, and 0 further away. That is
 * the layout's default, for accent restoration's typo channel.
 *
 * A touch keyboard knows where each finger landed (tap_weights), as
 * LatinIME's proximity info does: the chance that a tap aimed at key i lands
 * at point p is a Gaussian around i's centre, in key widths, plus the
 * outright slips onto i's neighbours,
 *
 *   L(p | i) = (1 - e) g(p; c_i) + e * sum_j w(i, j) g(p; c_j)
 *
 * where g(p; c) = exp(-dx^2 / 2sx^2 - dy^2 / 2sy^2) and c_k is key k's
 * centre moved by where the owner's own taps on it land (Keyboard::sweet).
 * A tap on the edge between two keys then counts for both, one in the
 * middle of its key mostly for that key. The rows are as far apart as a key
 * is tall: the phone's keys are about 1.4 key widths tall. */
#include "keys.h"

#include <array>
#include <cmath>

namespace xrnkey {

namespace {

struct Pos {
    double x, y;
};

std::array<Pos, 26> positions() {
    std::array<Pos, 26> p{};
    const char* rows[3] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    const double start[3] = {0.0, 0.5, 1.5};
    for (int r = 0; r < 3; ++r)
        for (int i = 0; rows[r][i]; ++i)
            p[static_cast<size_t>(rows[r][i] - 'a')] = {start[r] + i, static_cast<double>(r)};
    return p;
}

struct Table {
    /* log10 P(typed t | meant i) - log10 P(typed i | meant i), or 0 for
     * keys too far apart to be confused. */
    double cost[26][26]{};
    bool near[26][26]{};
};

Table build() {
    Table t;
    const auto p = positions();
    constexpr double kSigma = 0.6, kReach = 1.5;
    for (int i = 0; i < 26; ++i) {
        double w[26]{}, sum = 0;
        for (int j = 0; j < 26; ++j) {
            if (i == j) continue;
            const double dx = p[static_cast<size_t>(i)].x - p[static_cast<size_t>(j)].x;
            const double dy = p[static_cast<size_t>(i)].y - p[static_cast<size_t>(j)].y;
            const double d = std::sqrt(dx * dx + dy * dy);
            if (d > kReach) continue;
            w[j] = std::exp(-d * d / (2 * kSigma * kSigma));
            sum += w[j];
        }
        for (int j = 0; j < 26; ++j) {
            if (w[j] <= 0) continue;
            t.near[i][j] = true;
            t.cost[i][j] = std::log10(kTypoRate * w[j] / sum) - std::log10(1.0 - kTypoRate);
        }
    }
    return t;
}

const Table& table() {
    static const Table t = build();
    return t;
}

}  // namespace

const std::vector<char>& keys_near(char typed) {
    static const std::array<std::vector<char>, 26> near = [] {
        std::array<std::vector<char>, 26> n;
        const Table& t = table();
        for (int typed_key = 0; typed_key < 26; ++typed_key)
            for (int meant = 0; meant < 26; ++meant)
                if (t.near[meant][typed_key]) n[static_cast<size_t>(typed_key)].push_back(static_cast<char>('a' + meant));
        return n;
    }();
    static const std::vector<char> none;
    if (typed < 'a' || typed > 'z') return none;
    return near[static_cast<size_t>(typed - 'a')];
}

namespace {

/* Where the finger lands around the point it aims at, in key widths: measured spreads of
 * thumb taps are about a third of a key across and a little more up and down. */
constexpr double kSigmaX = 0.36, kSigmaY = 0.42;
/* An outright slip onto a neighbour, and how far one reaches (key widths). */
constexpr double kSlip = 0.02, kSlipReach = 1.6, kSlipSigma = 0.6;

/* Each letter's centre on a keyboard whose rows are `h` key widths apart. */
std::array<Pos, 26> centres(double h) {
    auto p = positions();
    for (auto& c : p) c.y *= h;
    return p;
}

}  // namespace

void tap_weights(const Keyboard& kb, char typed, double dx, double dy, bool known, double out[26]) {
    for (int i = 0; i < 26; ++i) out[i] = kFar;
    if (typed < 'a' || typed > 'z') return;
    const double h = kb.key_height > 0.2 ? kb.key_height : 1.0;
    const auto at = centres(h);
    const int t = typed - 'a';
    /* Where the tap landed, and each key's centre as the owner hits it. */
    const double px = at[static_cast<size_t>(t)].x + (known ? dx : 0.0);
    const double py = at[static_cast<size_t>(t)].y + (known ? dy * h : 0.0);
    Pos aim[26];
    for (int k = 0; k < 26; ++k)
        aim[k] = {at[static_cast<size_t>(k)].x + kb.sweet[k][0], at[static_cast<size_t>(k)].y + kb.sweet[k][1] * h};
    double g[26];
    for (int k = 0; k < 26; ++k) {
        const double ex = (px - aim[k].x) / kSigmaX, ey = (py - aim[k].y) / kSigmaY;
        g[k] = std::exp(-0.5 * (ex * ex + ey * ey));
    }
    /* L(p | i) for every letter: its own Gaussian, and its slips onto its neighbours. */
    double like[26];
    for (int i = 0; i < 26; ++i) {
        double w[26]{}, sum = 0;
        for (int j = 0; j < 26; ++j) {
            if (j == i) continue;
            const double ddx = at[static_cast<size_t>(i)].x - at[static_cast<size_t>(j)].x;
            const double ddy = at[static_cast<size_t>(i)].y - at[static_cast<size_t>(j)].y;
            const double d2 = ddx * ddx + ddy * ddy;
            if (d2 > kSlipReach * kSlipReach) continue;
            w[j] = std::exp(-d2 / (2 * kSlipSigma * kSlipSigma));
            sum += w[j];
        }
        double slips = 0;
        for (int j = 0; j < 26; ++j)
            if (w[j] > 0) slips += w[j] / sum * g[j];
        like[i] = (1 - kSlip) * g[i] + kSlip * slips;
    }
    const double base = like[t];
    for (int i = 0; i < 26; ++i) {
        if (like[i] <= 0 || base <= 0) continue;
        const double r = std::log10(like[i] / base);
        out[i] = r < -8.0 ? kFar : r;
    }
    out[t] = 0.0;
}

double typo_cost(char meant, char typed) {
    if (meant < 'a' || meant > 'z' || typed < 'a' || typed > 'z' || meant == typed) return 0.0;
    const Table& t = table();
    return t.near[meant - 'a'][typed - 'a'] ? t.cost[meant - 'a'][typed - 'a'] : -99.0;
}

}  // namespace xrnkey
