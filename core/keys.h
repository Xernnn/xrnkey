/* The letter keys' layout and the typo channel model (keys.cpp). */
#ifndef XRNKEY_KEYS_H
#define XRNKEY_KEYS_H

#include <vector>

namespace xrnkey {

/* The chance a letter is mistyped as a neighbouring key. Measured on
 * held-out text with 3 % typos (xk-eval, dev): 0.02 fixes 91 % of them and
 * changes 0.24 % of correct words; 0.05 fixes 92 % but changes 0.49 %. */
constexpr double kTypoRate = 0.02;

/* The letters whose key lies near `typed` (a-z): what was perhaps meant. */
const std::vector<char>& keys_near(char typed);
/* log10 of how much likelier it is that `meant` was typed as itself than
 * as `typed` (negative; -99 for keys too far apart). */
double typo_cost(char meant, char typed);

/* The phone's letter keys as a touch keyboard has them, for typo correction that knows where
 * each finger landed (typos.cpp). */
struct Keyboard {
    /* A letter key's height in key widths: how far apart the rows are (the phone's keys are
     * taller than wide). */
    double key_height = 1.0;
    /* Where the owner's taps on each letter land on average: an offset from the key's centre,
     * in key widths across and key heights down (0, 0: the centre). */
    double sweet[26][2] = {};
};

/* log10 of what can't happen, for a letter out of the finger's reach. */
constexpr double kFar = -99.0;

/* A tap typed `typed` (a-z), landing (dx, dy) from that key's centre, in key widths across and
 * key heights down; with `known` false, where it landed isn't known, and it counts as the
 * centre. For each letter, out[c - 'a'] is log10 of how much likelier aiming at it makes a tap
 * land there than aiming at `typed` does: 0 for `typed` itself, near 0 for a neighbour when the
 * tap was on the edge between them, about -1.7 for a neighbour when it was in the middle of its
 * key, kFar for letters out of reach. */
void tap_weights(const Keyboard& kb, char typed, double dx, double dy, bool known, double out[26]);

}  // namespace xrnkey

#endif
