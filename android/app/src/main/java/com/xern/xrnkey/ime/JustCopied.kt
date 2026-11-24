package com.xern.xrnkey.ime

import android.os.SystemClock

/**
 * The text just copied, for the strip's chip, as Gboard offers it: kept for [LIFE] after the copy,
 * until it is pasted or anything is typed. The service decides what may be offered at all (the
 * history's setting, sensitive clips, password fields) and when the strip has room for it.
 */
class JustCopied(
    /** Time that goes on while the phone sleeps: a copy before the screen went off is old after. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private var text: String? = null
    private var at = 0L

    /** A text was copied, now: the chip offers it. */
    fun copied(text: String) {
        this.text = text
        at = clock()
    }

    /** Typed, pasted, or not to be offered: no chip. */
    fun clear() {
        text = null
    }

    /** What the chip offers now, or null: nothing copied since, or longer ago than [LIFE]. */
    fun current(): String? = text?.takeIf { clock() - at < LIFE }

    companion object {
        /** How long a copy is offered: about a minute, as Gboard's. */
        const val LIFE = 60_000L
    }
}
