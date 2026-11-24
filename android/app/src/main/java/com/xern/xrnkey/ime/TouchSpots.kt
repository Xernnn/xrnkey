package com.xern.xrnkey.ime

import java.io.File
import java.io.IOException

/**
 * Where a letter key was touched: the offset from its centre in key widths across ([dx], to the
 * right) and key heights down ([dy]), and how tall the key is in key widths ([keyHeight]).
 */
data class Touch(val dx: Float, val dy: Float, val keyHeight: Float)

/**
 * Where the owner's taps on each letter land on average, LatinIME's "sweet spots" learned from
 * the owner's own typing: a thumb hits the keys at its side a little off their centres, always
 * the same way. Learned from the taps of words that ended as typed and are words, so the keys
 * they hit were the keys meant. Space's typo fixes measure each tap from these points instead of
 * the key's centre (core/keys.cpp).
 *
 * Kept on the phone only, beside the personal words ([OnDevice]) and with the same rules: never
 * from password, address or incognito fields or this app's own, nothing when learning is off.
 * Bounded: one mean and a count per letter; the count stops at [CAP], so the means follow the
 * owner's typing as it changes.
 */
class TouchSpots {
    private val x = FloatArray(26)
    private val y = FloatArray(26)
    private val n = IntArray(26)

    /** Changes with each tap learned, so the models are given the spots again. */
    var version = 0
        private set

    /** Taps learned since the last load or save. */
    var unsaved = 0
        private set

    /** The taps of a word's keys, one per key (null: not a tap, nothing to learn). */
    fun learn(keys: String, touches: List<Touch?>) {
        for ((i, c) in keys.withIndex()) {
            val t = touches.getOrNull(i) ?: continue
            val k = c.lowercaseChar() - 'a'
            if (k !in 0..25 || Math.abs(t.dx) > ON_KEY || Math.abs(t.dy) > ON_KEY) continue
            val count = minOf(n[k] + 1, CAP)
            n[k] = count
            x[k] += (t.dx - x[k]) / count
            y[k] += (t.dy - y[k]) / count
            unsaved++
            version++
        }
    }

    /** How many taps on `c` were learned (at most [CAP]). */
    fun count(c: Char): Int = n.getOrElse(c.lowercaseChar() - 'a') { 0 }

    /**
     * Where the taps on each letter land, as the models take it: nothing until [MIN] taps, then
     * the mean drawn toward the centre while there are few, and never more than [MAX] off it.
     */
    fun sweetX(): FloatArray = FloatArray(26) { spot(x[it], n[it]) }

    fun sweetY(): FloatArray = FloatArray(26) { spot(y[it], n[it]) }

    private fun spot(mean: Float, count: Int): Float =
        if (count < MIN) 0f else (mean * count / (count + PRIOR)).coerceIn(-MAX, MAX)

    /** Replaces what is held with the file's: "letter TAB dx TAB dy TAB count" lines. */
    fun load(file: File) {
        x.fill(0f)
        y.fill(0f)
        n.fill(0)
        unsaved = 0
        version++
        val lines = try {
            if (file.exists()) file.readLines() else return
        } catch (e: IOException) {
            return
        }
        for (line in lines) {
            val p = line.split('\t')
            if (p.size != 4 || p[0].length != 1) continue
            val k = p[0][0] - 'a'
            val dx = p[1].toFloatOrNull() ?: continue
            val dy = p[2].toFloatOrNull() ?: continue
            val count = p[3].toIntOrNull() ?: continue
            if (k !in 0..25 || !dx.isFinite() || !dy.isFinite() || count <= 0) continue
            x[k] = dx.coerceIn(-ON_KEY, ON_KEY)
            y[k] = dy.coerceIn(-ON_KEY, ON_KEY)
            n[k] = minOf(count, CAP)
        }
    }

    /** Writes to a temporary file and renames it; false when it couldn't (the old file stays). */
    fun save(file: File): Boolean {
        val tmp = File(file.path + ".tmp")
        return try {
            tmp.writeText(
                (0 until 26).filter { n[it] > 0 }
                    .joinToString("") { "${'a' + it}\t${x[it]}\t${y[it]}\t${n[it]}\n" },
            )
            val ok = tmp.renameTo(file)
            if (ok) unsaved = 0
            ok
        } catch (e: IOException) {
            tmp.delete()
            false
        }
    }

    companion object {
        /** The count stops here: the latest taps weigh 1/200 each. */
        const val CAP = 200

        /** Taps on a letter before its spot moves at all. */
        const val MIN = 10

        /** How many taps' worth of the centre the mean is drawn toward. */
        const val PRIOR = 20f

        /** The farthest a spot moves from the centre (key widths or heights). */
        const val MAX = 0.3f

        /** A tap further off than this wasn't on the key (a slide onto it): not learned. */
        const val ON_KEY = 0.55f
    }
}
