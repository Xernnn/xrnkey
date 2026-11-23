package com.xern.xrnkey.ime

/**
 * What the service asks of the typist in use: the Latin one ([Typist], Vietnamese and English)
 * or the Japanese one ([KanaTypist]). Both edit the field through an [Editor] and keep track of
 * where their own edits leave the cursor, so the service can tell them from the field changing
 * under them.
 */
interface Input {
    /** What the field shows as composing text ("" for nothing). */
    val shown: String

    /** Where the cursor is after this typist's own edits, as a UTF-16 index (-1: not known). */
    fun cursor(): Int

    /** The editor says where the cursor is, and this typist didn't know: it is there now. */
    fun anchorAt(cursor: Int)

    /**
     * The field is new, or changed under the typist: nothing is in progress, and what is before
     * the cursor is read again.
     */
    fun reset(cursor: Int = -1)

    /** The cursor moved, or the field is being left: what is in progress ends as it is. */
    fun finish()

    /** The cursor stopped at the end of a word, or inside one: it may open again. */
    fun reopenBefore(after: String): Boolean

    /** Typed text: each character a key. */
    fun text(t: String)

    fun backspace()

    fun enter()

    /** Text from a panel (an emoji, a clip): what is in progress ends as it is, then the text. */
    fun insert(text: String)

    /** A swipe left from Backspace: what is in progress goes, else the word before the cursor. */
    fun deleteWord()

    /** Nothing is typed yet in the sentence (where auto-capitalise gives Shift). */
    fun atSentenceStart(): Boolean
}
