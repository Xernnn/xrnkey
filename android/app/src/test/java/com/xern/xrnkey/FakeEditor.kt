package com.xern.xrnkey

import com.xern.xrnkey.ime.Editor

/**
 * A text field as InputConnection edits it: committed text, then the composing word at the
 * cursor, then [after]. A commit replaces the composing word; finishing keeps it as text.
 */
class FakeEditor(initial: String = "") : Editor {
    val committed = StringBuilder(initial)
    var composing = ""
        private set
    val log = mutableListOf<String>()

    /** What the field shows. */
    val text: String get() = committed.toString() + composing + after

    override fun setComposing(text: String) {
        composing = text
        focus = null
        log += "compose:$text"
    }

    /** The part of [composing] marked as in focus (a Japanese conversion's phrase), or null. */
    var focus: IntRange? = null
        private set

    /** What [focus] marks of [composing] ("" for nothing). */
    val focused: String get() = focus?.let { composing.substring(it.first, it.last + 1) } ?: ""

    override fun setComposing(text: String, focus: IntRange) {
        composing = text
        this.focus = focus
        log += "compose:$text[$focus]"
    }

    override fun commit(text: String) {
        composing = ""
        focus = null
        committed.append(text)
        log += "commit:$text"
    }

    override fun finishComposing() {
        committed.append(composing)
        composing = ""
        focus = null
        log += "finish"
    }

    override fun backspace(last: String): Boolean {
        if (last.isNotEmpty() && committed.endsWith(last)) {
            committed.setLength(committed.length - last.length)
            log += "delete:$last"
            return true
        }
        if (committed.isNotEmpty()) {
            val n = Character.charCount(committed.codePointBefore(committed.length))
            committed.setLength(committed.length - n)
        }
        log += "backspace"
        return false
    }

    override fun deleteBefore(codePoints: Int) {
        var n = codePoints
        while (n-- > 0 && committed.isNotEmpty()) {
            committed.setLength(committed.length - Character.charCount(committed.codePointBefore(committed.length)))
        }
        log += "delete:$codePoints"
    }

    override fun enter() {
        committed.append('\n')
        log += "enter"
    }

    override fun textBefore(n: Int): String = committed.takeLast(n).toString()

    /** What follows the cursor (the fake's cursor is at the end of [committed] + [composing]). */
    var after = ""

    override fun textAfter(n: Int): String = after.take(n)
}
