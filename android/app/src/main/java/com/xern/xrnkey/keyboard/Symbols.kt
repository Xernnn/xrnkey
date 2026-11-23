package com.xern.xrnkey.keyboard

/**
 * Unicode symbols in eight pages by kind, each named on the bar by one of its symbols (the
 * brackets by a pair). Written for this project. Every one draws as text in Android's own fonts,
 * none as a colour emoji (EmojiTabsTest and EmojiPanelTest check).
 */
object Symbols {
    val pages = listOf(
        page(
            "Hearts and flowers", "♡",
            "♡ ♥ ❢ ❥ ❣ ❦ ❧ ☙ ღ ✿ ❀ ❁ ✾ ✽ ❃ ❋ ⚘ ☘ ❊ ❉ ❈ ❇ ✺ ✹ ✸ ✷ ✶ ✵ ✴ ✳ ✱ ✣ ✤ ✥ ✼ ❅ ❆ ⚜ " +
                "❖ ⁂ ⊱ ⊰ ༺ ༻ ꕥ ꕤ ꧁ ꧂",
        ),
        page(
            "Signs", "ツ",
            "ツ ッ シ ㋡ ㋛ ☻ ☹ ♂ ♀ ⚥ ⚢ ⚣ ⚤ ⚦ ⚧ ☉ ☽ ☾ ☿ ♁ ♃ ♄ ♅ ♆ ☮ ☯ ☪ ☦ ☥ ✝ ✞ ✡ ☸ ☠ ☢ ☣ ⚠ " +
                "⚕ ⚖ ⚙ ⚛ ⚒ ⚔ ⚐ ⚑ ♻ ☎ ☏ ✉ ✂ ✎ ✏ ⌨ ⌘ ⏎ ⌫",
        ),
        page(
            "Money and letters", "A",
            "₫ \$ € £ ¥ ¢ ₩ ₹ ₽ ฿ ₱ ₺ ₴ ₦ ₪ ₭ ₿ ¤ ℃ ℉ № ™ ℠ © ® ℗ ℡ ℅ ℓ ℮ ℞ ℵ Å ß Æ æ Ø ø " +
                "Ⓐ Ⓑ Ⓒ Ⓓ Ⓔ Ⓕ Ⓖ Ⓗ Ⓘ Ⓙ Ⓚ Ⓛ Ⓜ Ⓝ Ⓞ Ⓟ Ⓠ Ⓡ Ⓢ Ⓣ Ⓤ Ⓥ Ⓦ Ⓧ Ⓨ Ⓩ",
        ),
        page(
            "Numbers", "⑨",
            "⓪ ① ② ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨ ⑩ ⑪ ⑫ ⑬ ⑭ ⑮ ⑯ ⑰ ⑱ ⑲ ⑳ ⓿ ❶ ❷ ❸ ❹ ❺ ❻ ❼ ❽ ❾ ❿ " +
                "⑴ ⑵ ⑶ ⑷ ⑸ ⑹ ⑺ ⑻ ⑼ ⑽ Ⅰ Ⅱ Ⅲ Ⅳ Ⅴ Ⅵ Ⅶ Ⅷ Ⅸ Ⅹ Ⅺ Ⅻ " +
                "⁰ ¹ ² ³ ⁴ ⁵ ⁶ ⁷ ⁸ ⁹ ⁿ ₀ ₁ ₂ ₃ ₄ ₅ ₆ ₇ ₈ ₉ ½ ⅓ ⅔ ¼ ¾",
        ),
        page(
            "Maths and Greek", "Σ",
            "− × ÷ ≠ ± ≈ ≡ ≤ ≥ ∝ ∞ √ ∑ ∏ ∫ ∂ ∇ ∀ ∃ ∅ ∈ ∉ ⊂ ⊃ ∩ ∪ ∧ ∨ ¬ ⊕ ⊥ ∠ ∥ ∴ ∵ ° ‰ " +
                "α β γ δ ε ζ η θ ι κ λ μ ν ξ ο π ρ σ ς τ υ φ χ ψ ω Γ Δ Θ Λ Ξ Π Σ Φ Ψ Ω",
        ),
        page(
            "Brackets and marks", "⟨⟩",
            "「 」 『 』 【 】 〔 〕 〖 〗 〘 〙 〚 〛 《 》 〈 〉 ⟨ ⟩ ⟦ ⟧ « » ‹ › ❮ ❯ ⦅ ⦆ ⌈ ⌉ ⌊ ⌋ " +
                "“ ” ‘ ’ ¡ ¿ ‽ ‼ ⁇ ⁉ … · • ◦ † ‡ § ¶ ‖ ¦ — – 〜 ※ 〃 々 〒 ・ 。 、",
        ),
        page(
            "Arrows", "→",
            "← ↑ → ↓ ↔ ↕ ↖ ↗ ↘ ↙ ↩ ↪ ↺ ↻ ↶ ↷ ↰ ↱ ↲ ↳ ↵ ⤴ ⤵ ↯ ⇐ ⇑ ⇒ ⇓ ⇔ ⇕ ⇖ ⇗ ⇘ ⇙ ⇄ ⇅ ⇆ " +
                "⇦ ⇧ ⇨ ⇩ ⟵ ⟶ ⟷ ⟸ ⟹ ⟺ ➔ ➜ ➡ ➢ ➣ ➤ ➳ ➵ ➸ ➺ ➻ ➼ ➽ ➾ ⬅ ⬆ ⬇",
        ),
        page(
            "Stars, shapes and more", "★",
            "★ ☆ ✩ ✪ ✫ ✭ ✯ ⋆ ✦ ✧ ● ○ ◎ ◉ ◐ ◑ ◔ ◕ ■ □ ▢ ▣ ▪ ▫ ◆ ◇ ◈ ◊ ▲ △ ▼ ▽ ◀ ▶ ⬤ " +
                "♔ ♕ ♖ ♗ ♘ ♙ ♚ ♛ ♜ ♝ ♞ ♟ ♠ ♣ ♦ ♤ ♢ ♧ ⚀ ⚁ ⚂ ⚃ ⚄ ⚅ ♩ ♪ ♫ ♬ ♭ ♯ 𝄞 " +
                "☀ ☁ ☂ ☃ ☄ ☼ ✓ ✔ ✗ ✘ ✖ ☐ ☑ ☒",
        ),
    )

    val list get() = pages.flatMap { it.items }

    private fun page(name: String, sample: String, symbols: String) = TextPage(name, sample, symbols.split(' '))
}
