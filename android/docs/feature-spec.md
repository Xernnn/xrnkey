# xrnkey for Android: feature spec

(The project was renamed from xrnikey to xrnkey. Paths and symbols of the existing code keep the
old name until the rename lands.)

An Android keyboard for one owner's phone (Android 15, HyperOS) that replaces the closed-source
Vietnamese keyboard they have used for years. The owner's verdict on it: it is so good that
nothing open source has matched it. Three things make it worth replacing anyway: it is closed
source, it has internet access, and it syncs the words it learns to an online account. This
keyboard has **no INTERNET permission**, keeps everything it learns on the phone, and shares one
engine with the Fcitx5 input method in this repo, so an improvement reaches both the phone and
the laptop.

The layout and behaviour follow the reference keyboard. The name, icon, artwork and wording are
this project's own, and the reference is named only in the README's parity section. The
reference is described here in words. No screenshots of it are kept in the repo.

What the owner relies on every day, and so what decides whether this is "as good":

- typing without accents and getting them, in context: `khong` → `không`, `duoc` → `được`;
- English and Vietnamese mixed in one message, with no switching;
- auto-correct on space and punctuation, which is "really so good";
- the emoji panel and the clipboard panel;
- the number row, and the layout as a whole.

Swipe typing is not used.

## Terms

- **Key**: a letter, digit or symbol key, or a function key (Shift, Backspace, `?123`, Enter).
- **Word in progress**: the keys typed since the last word boundary, held by the engine. The app
  shows it as Android *composing text*, with no underline.
- **Telex result**: what the engine's Telex rules make of the keys (`dduowcj` → `được`,
  `khong` → `khong`). It is the same code the laptop runs.
- **ASCII shape**: a syllable with every mark and tone removed (`được` → `duoc`). The 5,840
  attested syllables have 1,445 shapes, with about 4 syllables per shape on average and up to 26
  (`do`, `dam`).
- **Accent restoration**: choosing the accented syllable for an ASCII shape typed without marks,
  using the words before it.
- **Look-back**: re-choosing the last one or two restored syllables once the next syllable is
  known (`co gang` first gives `có`, then `cố gắng`).
- **Pass-through**: a word committed exactly as typed, because it is English or an
  abbreviation, not Vietnamese.
- **Strip**: the row above the keys with three suggestion slots.
- **Centre pick**: the strip's middle slot. It is always what Space would commit.
- **Auto-correct**: Space or punctuation committing the centre pick instead of the keys as typed.
- **Revert**: Backspace right after an auto-correct, which puts back what was typed (after Space,
  as the word in progress).
- **Tool row**: the strip's other face, a row of buttons: ≡ (folds the row away), settings,
  clipboard, cursor pad and emoji.
- **Panel**: a full-keyboard-height view in place of the keys: emoji, clipboard, cursor pad or
  the expanded candidate list.
- **Personal words**: words and word pairs learned from what the owner types. They are kept on
  the phone only.
- **Shortcut**: an abbreviation that expands at the end of a word (`ko` → `không`). The same as
  the laptop's macros.
- **Model**: the shipped language data: a Vietnamese syllable n-gram model, an English word
  model, and the lexicons and emoji index.

## Day one (must have)

1. Full Telex through the shared C++ core: `uw` = ư, `ow` = ơ, `aw` = ă, and a `w` alone stays `w`
   as in Laban Key's simple Telex (the setting "W types ư" makes it ư), old-style tones (hòa, thúy), marks and tones
   allowed after the final consonant, a key typed twice undoes itself, and Backspace deletes one
   character. VNI and new-style tones are settings, since the core already has them.
2. Accent restoration of words typed without marks, ranked by a syllable n-gram model with the
   words before them as context, plus look-back over the last two syllables.
3. English and Vietnamese mixed with no language switch. Whether the keys mean English or
   Vietnamese is decided in context, which fixes the laptop's 19 English words that now type
   Vietnamese (`list` → `lít`, `turn` → `tủn`).
4. A strip with three slots: the centre pick, and alternatives, two-word phrases and an emoji
   suggestion. Next-word predictions appear after Space. A panel slid up from the strip holds more.
5. Auto-correct on Space and punctuation at the "flexible" level, Revert on Backspace, and a full
   stop from a double Space.
6. Keys and gestures as the owner set them up: the number row, long-press on the top letter row
   for digits, a key-press popup, a 400 ms long-press, swiping the space bar to move the cursor,
   and holding the comma for a menu: Android's keyboard menu, or the keyboard's language.
7. Emoji panel: recents, categories, search and skin tones.
8. Clipboard panel: history, pin, delete and paste.
9. Personal words learned on the phone, which can be viewed, deleted, exported and imported.
10. Shortcuts, with import and export as a file in the laptop's `macros.txt` format.
11. Settings for each of the above, plus the cursor pad and dark, light or system theme.
12. No INTERNET permission, and no other permission at all.

## Later (agreed, not now)

- Haptics and key sound. The owner has both off.
- Clipboard images, and a chip in the strip for a just-copied item.
- Typing whole words in a Unicode style as they are typed (𝐛𝐨𝐥𝐝, 𝘪𝘵𝘢𝘭𝘪𝘤, 𝓈𝒸𝓇𝒾𝓅𝓉). The `Aa` tab
  has the styled letters one at a time, as Laban Key's.
- Auto-capitalise, smart space after punctuation, and moving the cursor to a word's end. The
  owner has these off in the reference.
- "Improved Telex" (cc=ch …) and quick initial and final consonants. The owner has these off.
- Split and one-handed layouts, and a tablet layout.
- Moving personal words between the phone and the laptop automatically. Day one only does it by
  hand, as files.

## Out of scope

- **Swipe (glide) typing**: not used.
- **Voice input**: no mic key; the keyboard stays fully offline.
- **Stickers, GIFs and a theme store**: they need the network and third-party artwork.
- **Drawing tone marks by hand**: the reference has it, but the owner doesn't use it.
- **Any account, sign-in, cloud sync or telemetry**: the point of the project is to have none.
- **Importing the reference keyboard's learned words**: they live in its online account, and no
  export is known. The new keyboard learns from scratch. The **shortcut table** does come over
  later, as a file.

---

## The keyboard (letters)

### What it shows

Top to bottom, in the dark slate theme by default. Keys have no borders, and their glyphs are
large, in MiSans Latin.

1. **Strip or tool row**, one row high (see "The strip" and "The tool row").
2. **Number row**: `1 2 3 4 5 6 7 8 9 0`, always shown in portrait. In landscape it follows the
   "Number row" setting (on, off or auto, where auto hides it in landscape). Swiped left it shows
   ten emoticons (the emoticon tab's first ten, `:) :)) =)) :D :v ;) :( :(( :'( :P`), each typed
   whole; swiped right, the digits again.
3. **Letters**: QWERTY.
   - `q w e r t y u i o p`.
   - `a s d f g h j k l`, inset by half a key.
   - Shift (1.5 keys wide), `z x c v b n m`, then Backspace (1.5 keys wide).
4. **Bottom row**:
   - `?123`.
   - Comma, with a small globe above it, because holding it opens the keyboard and language menu.
   - The space bar, about 5 keys wide. It shows only a small, faint xrnkey tile, where the
     reference shows its name.
   - Full stop.
   - Enter, as a round key in the accent colour. Its icon follows the field's action: newline,
     send, search, go, next or done.

There is no navigation row, language key or hide key. Android's own navigation bar shows the
keyboard's hide button.

### What you can do

- **Tap a letter**: the key goes to the engine, and the word in progress is shown as composing
  text, without an underline where the app allows it. With "Key-press popup" on, a magnified
  copy of the key shows above the finger for as long as it is down.
- **Long-press a letter** (400 ms): a popup over the key. A vowel's holds every toned form of
  it and of its marked letters, then the capital: `a` à á ả ã ạ, ă ằ ắ ẳ ẵ ặ, â ầ ấ ẩ ẫ ậ, A (18),
  `o` 18, `e` and `u` 12, `i` and `y` 6. `d` gives đ and D, any other letter its capital (with
  Shift on, the capitals, and the small letter last). The first form (the grave one for a vowel)
  is under the finger and typed if it lifts there; the rest sit around it in a grid as square
  as they allow (5 by 4 for 18), so no slide is long. Where the grid doesn't fit above the key,
  it moves and the finger still starts on the first form. Forms go to the engine as the Telex
  keys that make them, so the word goes on taking marks, but the word ends as shown, never as
  its keys: a picked `ò` stays `ò`, though `of` typed stays `of`. With the number row on, no digit is in
  the popup (the row is right there); without it, a top-row letter's digit comes first (`q` →
  1 … `p` → 0, the "Long-press top row for digits" setting).
- **Shift**: one tap gives one capital, and a double tap locks capitals, which a third tap
  undoes. Capitals follow the keys that typed them (`Vieetj` → `Việt`), as on the laptop.
- **Backspace**:
  - with a word in progress, the engine deletes one character (`toán` → `tóa`, `được` → `đượ`);
  - otherwise it deletes the character before the cursor (an emoji with its skin tone or
    joined parts is one) through the field's connection, in order with the edits after it, or
    else sends the key (a selection, the field's start); when that leaves the cursor at
    the end of a word, the word is the word in progress again, as on Laban Key: Telex goes on
    marking it (`việt␣⌫` + `s` is `viết`), the strip offers its suggestions, and Space ends it
    as before. Only a whole word after a space, a bracket, a quote or the field's start opens
    (never the end of `don't`, `e-mail` or `2nd`), and only one its keys make again exactly: an
    English word Telex would change (`of` would come back as `ò`) stays as it is. A word whose
    change Backspace refused stays as typed when it opens again;
  - right after an auto-correct, it reverts (see "Auto-correct");
  - holding it repeats after 400 ms, every 50 ms, and after 2 s it deletes a word at a time;
  - **swiping left** from it (a key and a half) deletes the word before the cursor, with the
    spaces after it, one word a swipe however far ("xin chao ban" → "xin chao "): the word in
    progress if there is one, a run of punctuation or emoji as one word, a new line alone.
    The key asks the system to leave its swipes alone (gesture exclusion), but HyperOS ignores
    that: a quick swipe starting in the screen's outer 62 px (the key's right part) is its Back
    gesture and closes the keyboard, as on the reference. A swipe from the key's left part, or
    one that rests a moment first, deletes.
- **A tap at the end of a word** opens it again the same way (the cursor moved there by the
  finger, with nothing selected).
- **A tap inside a word** opens its part before the cursor, as on Laban Key: in "xin ch|ao"
  the word in progress is `ch`, underlined, and the strip offers what starts so (Laban Key:
  xin cho, chao, chúc mừng). Telex goes on with it, and a pick replaces it and adds a space
  ("xin chào ao"), the rest of the word staying where it is. Space, punctuation and Enter end
  it as typed, since the rest of the word is after it. Not before a digit, nor inside `don't`
  or `e-mail`.
- **Space**: ends the word (see "Auto-correct"). A **horizontal swipe** that starts on the space
  bar moves the cursor one character per 12 dp. The word in progress is committed first.
- **Holding the space bar** types a space, nothing more. (It used to turn the keys into a
  trackpad; the owner doesn't use that. The tool row's cursor pad does the same.)
- **Double Space**: replaces "␠␠" after a word with ". ". This happens only straight after a
  word, a number (`1␠␠` → `1. `) or closing punctuation, and never in password, URL or email
  fields.
- **Comma**: types `,`, which ends the word like Space does, without a space. **Holding the
  comma** opens a menu over it, one row to the right: ⌨ VI EN JA あ, the one in use underlined.
  JA (Japanese on QWERTY) and あ (Japanese on the 12-key pad) are there only when the APK has
  the Japanese dictionary. Slide to a choice and lift: ⌨ opens Android's keyboard menu, a
  language switches the keyboard to it (see "The English keyboard" and "The Japanese
  keyboard"); the space bar names the one in use, and it stays chosen. Lifting where it opened
  is ⌨.
- **Full stop**: types `.` and ends the word. **Holding it** gives the common punctuation,
  `, ? ! ' " : - ; ( ) / @ & % + #`, the comma under the finger, as on the reference.
- **Enter**: ends the word, then sends the field's action or a newline.
- **`?123`**: opens the symbols layout.
- **Rotate or resize**: the layout is never full-screen in landscape (no extract mode).

Fields change the keyboard:

- **Number and phone fields**: a 4×3 number pad.
- **Email and URL fields**: `@` and `.com`/`/` take the comma's and full stop's places, and there
  is no auto-correct.
- **Password fields** (any variation): every key is typed as itself. There is no Telex, no strip,
  no learning, and nothing goes to the clipboard history.
- **Fields that ask for no personalised learning**: everything works, but nothing is learned.
  The same in xrnkey's own fields (the Try-it field, the settings' lists): what is typed there
  to try the keys, or to add a word, is not the owner's writing.

## The symbols layouts

### What it shows

Key for key as the reference has them.

- **Page 1** (`?123`):
  - `1 2 3 4 5 6 7 8 9 0`;
  - ``~ ` | • √ π ÷ × ¶ ∆``;
  - `@ # $ % & - + ( ) /`;
  - `=\<` (a key and a half), `* " ' : ; ! ?`, then Backspace;
  - the bottom row: `ABC`, comma (with its globe), `1234` (the number pad), a narrower space
    (three keys), `_`, full stop and Enter.
- **Page 2** (`=\<`):
  - `1 2 3 4 5 6 7 8 9 0`;
  - ``~ ` | • √ π ÷ × ¶ ∆``;
  - `£ ¢ € ¥ ^ ° = { } …`;
  - `?123`, `\ © ® ™ ℅ [ ]`, then Backspace;
  - the bottom row: `ABC`, comma, `1234`, space, `<`, `>` and Enter.
- Without the number row each page has the letters' four rows: page 1 leaves out the
  ``~ ` | …`` row, page 2 the digits.
- **The number pad** (`1234`), five columns of two keys' width, with the strip above:
  `, 1 2 3 -` / `( 4 5 6 .` / `) 7 8 9 ⌫` / `ABC ?123 0 space ↵`.
- Long-press popups, the first under the finger: `!` → ¡, `'` → ’ ‚ ‘ ‛, `π` → Π ∏ (seen on
  the reference, where `|` and `_` hold nothing), and xrnkey's own: `$` → ₫ ¢ £ € ¥ (₫ is no
  longer a key of its own), `?` → ¿, `"` → “ ” „ « », `*` → † ‡ ★, `-` → – — _ ·, `+` → ±,
  `(` → [ { <, `)` → ] } >, `%` → ‰, `#` → №, `/` → \, `•` → · ♪ ♥ ♠ ♦ ♣, `¶` → §,
  `^` → ↑ ↓ ← →, `°` → ′ ″, `=` → ≠ ≈ ∞, `<` → ≤ « ‹, `>` → ≥ » ›, and the full stop → ….

### What you can do

- **Tap a symbol**: it ends the word in progress, then types the symbol. Digits typed here are
  never Telex keys.
- **`ABC`**: back to letters. After a symbol followed by a space, the letters come back on their
  own, as on the reference.

## The strip

### What it shows

- On the left, a **menu button** (≡). It shows the tool row instead.
- **Three slots.**
  - The centre slot holds the best candidate, with three small dots under it when there are more
    candidates. It is in bold only when Space commits it: a fix (`teh` → `the`), a word Telex
    marked that Space ends as typed (`meet`, shown `mêt`), an abbreviation's expansion.
  - The word as the field shows it takes no slot while there is anything else to offer: Space
    keeps it anyway (tones only by Telex, as on Laban Key). A word finished with its Telex marks
    gives way to what may follow it (`banj`: `bạn sẽ` | `bạn có` | `bạn nên`), a Vietnamese word
    typed as it stands to the words that go with it (`em`: `email` | `em bé` | `em gái`), an
    English word to its longer words and other readings (`the`: `theo` | `thế` | `they`; `ok`:
    `okay`). Only when there is nothing else is the word itself in the centre, in bold.
  - The left and right slots hold the next-best candidates. A slot can hold an accented word, a
    two-word phrase (`không thể`, `dược phẩm`), the keys as typed, a shortcut's expansion, or an
    emoji.
  - An emoji shows only when the word as it stands is one of the strip's emoji keywords
    (`data/emoji/keywords.tsv`): typed bare or with Telex marks, exactly (`cam` → 🍊, `khóc` → 😢,
    never bare `khoc`), or as the last word of a phrase, with or without marks (`chuc mung sinh
    nhat` → 🎂). It takes the right slot. With one word to offer, a second emoji takes the left
    (`sorry`: 🙏 | `sorry` | 😔). When there are no words to offer, the emoji fill the strip from
    the right: three slots for three emoji or fewer (`kk`: — | 🤣 | 😂), else a **row of five**
    even places between ≡ and the emoji button, the best on the right (`haha`: 😁 😄 😆 🤣 😂).
    After Space, the next words keep the left and the centre and the emoji takes the right; a
    wish or thanks of three words or more fills the strip with its emoji (`chuc mung sinh nhat `,
    `cảm ơn bạn nhiều `), as on Laban Key; an emoticon just typed (`:)`, `=))`) shows its emoji
    too.
  - After Space, words typed without marks are read as they most likely stand for what comes
    next (`cam on ` → `bạn`, not English), and a phrase with the word before shows only when that
    word reads as typed: after `hom nay troi dep qua ` (`quá`), no `qua đời`. A sentence of one
    such word that is a word as typed stays so (`bao ` → `bao giờ`).
  - The part of a candidate already in the field is drawn grey, the rest in the slot's colour
    (`bạn` of `bạn bè` while `bạn` is typed).
- On the right, an **emoji button**.

What the slots hold, by situation (the centre in bold only where Space commits it):

| Situation | Left | Centre | Right |
|---|---|---|---|
| `khong` typed | `khổng` | `không` | `không chỉ` |
| `cam` typed | `cảm` | `cảm ơn` | 🍊 |
| `em` typed (Space keeps `em`) | `email` | `em bé` (`em` grey) | `em gái` |
| `the` typed (Space keeps `the`) | `theo` | `thế` | `they` |
| `ok` typed | 👍 | `okay` | 👌 |
| `haha` typed | a row of five: 😁 😄 😆 🤣 😂 |  |  |
| `teh` typed (Space fixes it) | — | **`the`** | — |
| `ko` typed, with shortcut `ko` → `không` | `ko` | **`không`** | — |
| `list` typed after `the` (shown `lít`, Space ends it `list`) | `listing` | **`list`** | `list of` |
| `banj` typed (`bạn`, finished with its marks; Space commits `bạn`) | `bạn sẽ` | `bạn có` (`bạn` grey) | another `bạn …` |
| just after Space, following `không` | `có` | `chỉ` | `không phải` (`không` grey) |
| just after Space, following `một` | `trong` | `một số` (`một` grey) | `người` |
| just after Space, following `cam on` | `các` | `bạn` | 🙏 |
| just after Space, following `chuc mung sinh nhat` | a row of five: 🎈 🎁 🥳 🎉 🎂 |  |  |
| field empty, nothing typed | the tool row instead |  |  |

### What you can do

- **Tap a slot** (or a place of a row of emoji): commits that candidate and a space. A phrase
  commits both words; after Space, a phrase that starts with the word just typed adds its next
  word. An emoji does what its keyword says, with no space after it: most go after the word,
  with one space (`ok` → `ok 👌`); laughs, emoticons and a lone food, drink or animal take the
  word's place (`cam` → `🍊`, `haha` → `😂`, `:)` → `🙂`, `cà phê` → `☕`). After Space, the
  emoji goes at the cursor. The word as typed has no slot: Space keeps it.
- **Slide up from the strip**: the **more-suggestions panel** opens over it, up to 7 more
  candidates (15 on the Japanese keyboard) three to a row under the strip's slots, the best nearest the strip and in the
  middle. Lift on one to commit it with a space; slide back down past the strip to close it
  with nothing taken, as on the reference. Lifted anywhere else, it stays open.
  While it stays open, a tap on a candidate commits it and any key closes it (and types).
- **The dots** under the middle only say there are more: a tap anywhere on the middle slot,
  dots included, commits the middle word.
- **While the panel is open** each word or phrase in the strip has a small remove mark: a tap
  forgets the word from the personal words, as the settings' list does, and hides it, so that
  neither the strip nor the next words offer it again, whichever model it came from. Hidden
  suggestions are kept with the personal words (Settings → Personal words lists them under
  "Hidden", each with Show; Clear all clears them too) and are never learned from.
- **Tap ≡**: the tool row replaces the strip until the next key.

The strip never shows a sentence of explanation, only candidates.

## The tool row

### What it shows

Five buttons, evenly spaced: ≡, settings, clipboard, cursor pad and emoji. There is no mic:
the keyboard is fully offline and has no voice typing, and no theme button (the theme is in
Settings). The tool row shows whenever there is no word in progress and no prediction to show,
and whenever ≡ is tapped. Folded away by its ≡, it gives way to the bare strip, ≡ and emoji
with nothing between, as on Laban Key, in every field after, until ≡ brings it back.

### What you can do

- **≡**: folds the tool row away (see above).
- **Settings**: opens Settings.
- **Clipboard**: opens the clipboard panel.
- **Cursor pad**: opens the cursor pad.
- **Emoji**: opens the emoji panel.

## The English keyboard

Chosen from the comma's menu (EN); the space bar says "English".

- **No marks**: Telex's keys are letters ("vieetj", "off" stay as typed), and a digit or an
  apostrophe after letters stays in the word ("mp3", "don't").
- **English only**: the strip's completions, next words and fixes come from the English model
  alone ("an": another, and, any; "how are ": we, you, they), and the personal words don't
  join in. Emoji keywords still offer their emoji.
- **Space fixes English**: a typo English has no word for ("teh": the), a word typed without its
  apostrophe ("dont": don't, "im": I'm), and "i" alone is "I". Words that are words themselves
  ("its", "ill", "were") stay. Backspace right after puts back what was typed.
- Everything else is as on the Vietnamese keyboard: the strip, the panels, the gestures.

## The Japanese keyboard

Chosen from the comma's menu (JA); the space bar says "日本語". It types as Gboard's Japanese
QWERTY does: romaji become kana, Space converts them to kanji. The dictionary is Mozc's
open-source one (`data/ja/README.md`), in the APK and never downloaded. The same Japanese can
be typed on the 12-key kana pad instead (あ in the menu, "The 12-key pad" below).

- **Letters spell a reading in romaji** (wāpuro), shown as composing hiragana, underlined:
  - each kana as soon as its letters spell it: `ka` か, `kya` きゃ, `shi`/`si` し, `chi`/`ti` ち,
    `tsu`/`tu` つ, `fu`/`hu` ふ, `ji`/`zi` じ, `ja` じゃ, `sha` しゃ, `cha` ちゃ, `wo` を, `fa` ふぁ,
    `thi` てぃ, `va` ゔぁ;
  - small kana with x or l: `xa`/`la` ぁ, `xya` ゃ, `xtu`/`ltu`/`xtsu` っ, `xwa` ゎ;
  - a doubled consonant is っ: `kitte` きって, `matcha` まっちゃ;
  - ん: `nn` and `n'`, an n before a consonant other than n and y (`shinbun` しんぶん), and an n
    left at the end when the reading is converted or committed (`tanin` たにん, `tan'in`
    たんいん). The second n of `nn` also starts the next kana when a vowel or y follows, as
    the word is spelt (`minna` みんな, `konnichiha` こんにちは, `konnyaku` こんにゃく); before
    anything else, or at the end, it goes (`nihonn` にほん), and `konnnichiha` works too;
  - `-` is ー inside a reading (`ra-men` らーめん), and `-` with nothing composing;
  - letters that spell nothing yet wait at the end, as typed (`かk`).
- **Shift**: a capital goes in as it is, and so do the letters after it until the reading ends
  (`Tokyo`), for names. Auto-capitalise never gives Shift here.
- **Holding a letter** gives what Japanese text uses of it, not Vietnamese's marks: a vowel
  with its macron, as romaji writes a long vowel (`ā ī ū ē ō`), the letter full-width (`ａ`),
  then its capital (a: `ā ａ A`; k: `ｋ K`); with Shift, the same as capitals and the small
  letter last. Without the number row the top row's digit comes first, as on the other
  keyboards. Inside a reading they join it, as digits do; with nothing composing they go
  straight in.
- **Digits** stay ASCII. Inside a reading they join it (`3じ`), as other symbols do; with nothing
  composing they go straight in.
- **The comma and full stop keys** are `、` and `。`. Holding `。` gives `、 ？ ！ ・ ー 「 」 … ～ ：
  （ ）`, `、` under the finger. `?` and `!` from the symbols are typed `？` and `！`. Inside a
  reading they join it and convert with it (`かみ、` → `紙、`); with nothing composing they go
  straight in. Holding `、` opens the comma's menu, as on the other keyboards.
- **The strip while composing**, as Gboard's:
  - the middle, in bold, holds what Space gives: the best conversion of the whole reading
    (`わたしはがくせいです`: `私は学生です`);
  - the left and the right hold the next likeliest, whatever they are, each ranked by what it
    costs as a whole sentence after the word before:
    - the owner's choices first (see "Learning" below);
    - the words read exactly so (`かみ`: `神`, `髪`);
    - the reading with its start converted another way and the rest after it (`こんにちは`:
      `今日は`; `わたしはがくせいです`: `わたしは学生です`), when it costs at most 2,000 more than
      the best;
    - predictions, words whose reading goes on from what is typed, a little dearer for each
      kana not typed yet (`ありが`: `ありがとう`, `ありがとうございます`);
  - each text once, wherever it came from;
  - the more panel holds up to 15 more, five rows of three: the next nine of those, then the
    hiragana and the katakana (in a slot only when nothing likelier is left), then words for the
    reading's start alone, for converting piece by piece (`私`, `渡し`, then the other starts
    that are words, longest first), then the rest;
  - a reading that looks foreign keeps its katakana right after the best conversion: one with
    ー, a small vowel (`ふぁ`, `てぃ`) or ゔ, or with a kana no word reads;
  - no remove marks: the dictionary's words are not the owner's to forget.
- **Space**:
  - while composing, it converts, as Mozc and Gboard do, in **phrases** (bunsetsu: a word and
    what goes with it): the field shows the best conversion split into phrases, the first in
    focus, on a tint of the accent (the whole underlined, as composing text is):
    `わたしはがくせいです` is `私は` / `学生です`, `きょうはいいてんきですね` `今日は` / `いい天気ですね`,
    `しごとがおわったらでんわします` `仕事が` / `終わったら` / `電話します`. A particle, an
    auxiliary, a suffix, a dependent word (`いる` of `ている`) or punctuation goes with the word
    before it, a prefix (`お`) with the word after it, a form of `する` with a noun that takes it
    (`勉強します`), by each part of speech Mozc's dictionary has (`data/ja/build_dict.py`,
    `KINDS`);
  - the strip shows the focused phrase's candidates: the one the field shows in the middle on
    the accent, the one before it on the left and the next on the right, the others in the
    panel. Each further Space shows the focused phrase's next, after the last the first again;
    the other phrases stay. The order: the phrase as the whole conversion has it, then the
    phrase's reading converted alone after the phrase before (its owner's choices, the next
    likeliest conversions, and after the first eight of those the hiragana, the katakana, and
    the letters full-width, `Ｔｏｋｙｏ`, for a reading of Latin letters, then the rest, then the
    words for the phrase's start). A start converted another way shows with the rest of its
    phrase converted after it (`渡し葉`). The whole reading's predictions are in the panel while
    it is one phrase;
  - the focus moves a phrase with ◀ and ▶ on the 12-key pad, and on QWERTY by sliding along
    the space bar, a phrase per 30 dp, no further than the ends; the conversion stays;
  - the strip's sides are ⇤ and ⇥ while converting, in place of ≡ and the emoji button: ⇤
    shrinks the focused phrase by a kana, which starts the next phrase (or a new one at the
    end), ⇥ stretches it by one taken from the next (as Mozc's Shift+arrows). Both are converted
    again, the focused one after the phrase before and the next after it; the phrases further
    on stay. A phrase of one kana doesn't shrink, the last doesn't stretch;
  - with nothing composing, it types a full-width space `　`, Gboard's default.
- **Tap a candidate** (a slot or the panel): after Space, the focused phrase's: the field shows
  it, and the focus moves to the next phrase; on the last phrase everything goes in, as shown.
  While composing: it goes in, in place of the part of the reading it stands for, with no space
  after it. A word for the reading's start leaves the rest composing, as typed, and its
  candidates follow that word (`私`, then `はがくせいです` converts to `は学生です`, the
  particle).
- **The word before**: a reading converts after the last word put in by Space, Enter or a tap,
  or the `、`, `。`, `？` or `！` typed after it, while nothing else went in after it: `は` after
  `私` is the particle, not `葉`. A space, Backspace into the text, an emoji or a moved cursor
  ends it.
- **Next words**: with nothing composing, right after a word goes in, the strip offers what may
  follow it, the likeliest in the middle, not bold (Space doesn't take it):
  - the words the owner put in after it before, the latest first;
  - then the words its part of speech goes on with best, by the connection cost plus the word's
    own, each kind under a limit:
    - the short words: particles, the copula and auxiliaries, and the commonest verbs and nouns
      that follow others (`私`: `は`, `の`, `が`; `ありがとう`: `ございます`; `行き`: `たい`,
      `ます`);
    - about 100 common content words: nouns, adjectives, verbs and adverbs (`何`, `私`, `今日`,
      `いい`, `ない`, `する`, `見る` …), which a particle is followed by (`を`: `する`, `思う`,
      `見る`; `が`: `ある`, `できる`, `ない`; `の`: `よう`, `こと`, `人`), and a noun far less;
  - after `、`, `。`, `？` or `！` (typed, or the end of a conversion): what a clause or a
    sentence starts with, by the same costs after that punctuation (`。`: `この`, `その`,
    `また`, `これ`, `そして`, `それ`, `私`);
  - worked out once for each part of speech, the first time it comes, so no key costs more;
  - a tap puts it in with no space, and the strip goes on from it;
  - the settings' "Next word" turns it off, as on the other keyboards.
- **Learning** (the owner's choices, on the phone only):
  - what Space or a tap puts in is learned: each word with its reading (after Space, each
    phrase's choice, as its words), and each pair of a word and the one before it, with how
    often and when. Not what Enter commits as typed, nor Latin
    letters, digits, punctuation or a kana no word has;
  - a word chosen for a reading is offered right after what Space gives, and it costs less than
    every other word read so (more, the more often it was chosen; of two chosen, the later), so
    it wins its reading in the lattice too: `かみ` is `上` in Mozc's dictionary, and once `紙` is
    picked, `かみ` is `紙` and `かみをきる` is `紙を切る`. A particle stays one where it belongs;
  - a word chosen before is predicted from the start of its reading, and one chosen after the
    word before comes first after it;
  - with the settings' "Learn words" (as the personal words); not in password fields, fields
    that ask for no personalised learning, or xrnkey's own;
  - at most 3,000 words and 3,000 pairs, the longest unused going first. Kept in
    `japanese-history.tsv` next to the personal words, written when a field is left; its parts
    of speech are the dictionary's, looked up again when another build of it comes;
  - Settings → Personal words → Japanese lists them with their count; each can be deleted, or
    all cleared.
- **The owner's own words** (a user dictionary, for what Mozc's lacks: a name, a place):
  - Settings → Personal words → Japanese dictionary, under Japanese, with their count: Add takes
    a reading and a word (the reading in hiragana, katakana or romaji: `tanaka`, `タナカ` and
    `たなか` are all `たなか`; one that isn't kana is refused, the form stays); each is listed
    with its reading, to delete. Import takes a file of a reading, a tab and a word per line
    (more columns, as Mozc's and Gboard's exports have, are ignored; `#` lines are comments),
    Export gives one;
  - each is a word of the dictionary for its reading, a noun, costing 3,000 less than the
    cheapest word the dictionary reads so (nothing, where it has none): it wins its reading,
    alone and inside a sentence (`たなか` is `田中` in Mozc's dictionary; with `田仲` added,
    `たなかさんはがくせいです` is `田仲さんは学生です`), is in the words read so, first, and is
    predicted from the reading's start (`ぜる`: `ゼルダ`). Chosen, it is learned as any word;
  - used whether learning is on or off, in every field but passwords (they are the owner's, not
    learned); at most 5,000, kept in `japanese-words.tsv` next to the personal words, and read
    again at the next field once the settings change them.
- **Enter**: while composing, commits what the field shows (the reading, a last n as ん, or
  after Space every phrase as shown), with no new line; otherwise the field's action or a new
  line.
- **Backspace**: takes the last letter waiting or the last kana; after Space, it goes back to the
  reading, unconverted; with nothing composing, the character before the cursor. A swipe from it
  takes the whole reading, or with none the word before the cursor.
- **A new letter after Space** commits the conversion shown and starts a new reading.
- **Moving the cursor, leaving the field**, an emoji or a paste: what the field shows is
  committed as it is (a last n as ん).
- **Password, email and URL fields**: plain letters, with `,` and `.`, as the English keyboard
  types them; nothing converts.
- **Not on this keyboard**: no learning of new words the dictionary lacks by typing them (the
  owner adds them, above), no parts of speech for the owner's words (all are nouns), no next
  words beyond the owner's and the lists above, no Telex, and no emoji in the strip (the emoji
  panel is there).
- The conversion is Mozc's way: the cheapest path through a lattice of the dictionary's words,
  each word's cost plus the cost of its part of speech after the one before (Mozc's
  connection costs), and a kana no word reads stands for itself; of two as cheap, the first
  found, as `data/ja/build_dict.py` converts. On Mozc's evaluation set the best conversion is
  Mozc's for 505 of 683 sentences, Mozc's is in the strip's three for 547 and in the strip or
  the panel for 553. The strip is made on each key in about 0.2 ms on a laptop's JVM with the
  real dictionary and a full history.

### The 12-key pad (flick input)

Chosen from the menu as あ, after JA: the kana pad most Japanese phones type on, as Gboard's
12-key Japanese keyboard has it. It types into the same reading as the QWERTY keys, so the strip,
the more panel, Space, Enter and Backspace work as above. JA stays QWERTY (0.8.0's saved choice
is JA). Password, email and URL fields get plain QWERTY letters, and number fields their pad.

- **What it shows**: four rows, as tall together as the letters with their number row; no
  number row of its own. Kana keys are tiles in a 3 by 4 grid, a shade lighter than the function
  keys either side:

  | | | | | |
  |---|---|---|---|---|
  | ↺ | あ | か | さ | ⌫ |
  | ◀ | た | な | は | ▶ |
  | ?123 | ま | や | ら | 空白 |
  | 🌐 | ゛゜小 | わ | 、。?! | Enter |

  The side columns are 1.7 letter keys wide, the kana keys 2.2. The space says 空白, and 変換
  while a reading is composing; Enter shows its icon, and 確定 while composing.
- **Tap** a kana key for its あ-column kana. **Flick** it left, up, right or down for the い, う,
  え or お one (か: き く け こ). A finger within 24 dp of where it went down is a tap; past that,
  the way it went furthest, and it stays that key's however far it slides. The exceptions:
  - や: left `（`, up ゆ, right `）`, down よ;
  - わ: left を, up ん, right ー;
  - 、。?!: tap `、`, left `。`, up `？`, right `！`.
- **The guide**: while a finger is on a kana key, its kana show in a cross over it, the tap in
  the middle and the flicks around it, the one the finger points at on the accent, as Gboard
  draws it. It is the key's popup: "Key popup" off, no guide.
- **Toggle input** (a setting, on by default, as on Gboard): the same key tapped again within a
  second steps its kana: あ い う え お ぁ ぃ ぅ ぇ ぉ, た … と っ, や ゆ よ ゃ ゅ ょ, わ を ん ゎ ー,
  、 。 ？ ！. A pause, a flick, ▶ or any other key ends it, so the next tap of that key is a kana
  of its own (ああ is あ ▶ あ). Off, each tap is a kana.
- **↺** steps the last kana back along its key's toggle (う to い, あ to ぉ); a toggle goes on
  from there. With nothing composing it does nothing.
- **゛゜小** changes the reading's last kana to its next form: か ⇄ が, は → ば → ぱ, つ → っ → づ,
  う → ぅ → ゔ, あ ⇄ ぁ, や ⇄ ゃ, わ ⇄ ゎ, and so on for every kana that has one; after Space, the
  reading comes back first.
- **Punctuation**: 、。？！ join the reading and convert with it (かみ、 → 紙、), as the QWERTY
  keys' do. With nothing composing they start a reading, so the toggle can step 、 to 。, and a
  kana after a reading of punctuation alone commits it first.
- **◀ and ▶** move the cursor a character, and repeat while held. ◀ with a reading commits it as
  shown, as the space bar's slide does; ▶ at the end of a reading stays put (it ends a toggle).
  After Space (変換) they move the focus between the phrases instead, both ways.
- **?123** opens the number pad below. **🌐** opens the comma's menu at once: slide to a choice
  and lift, or tap 🌐 and the menu stays open for a tap to choose; a tap elsewhere closes it.
- **The number pad**, as Gboard's (Google Japanese Input's 12-key numbers): the same grid and
  side keys, with the digits where the kana are:

  | | | | | |
  |---|---|---|---|---|
  | 記号 | 1 | 2 | 3 | ⌫ |
  | ◀ | 4 | 5 | 6 | ▶ |
  | あいう | 7 | 8 | 9 | 空白 |
  | 🌐 | ()[] | 0 | .,-/ | Enter |

  - a tap types the digit, a flick left, up or right a symbol, each shown small under its
    digit: 1 `☆ ♪ →`, 2 `¥ $ €`, 3 `% ° #`, 4 `○ * ・`, 5 `+ × ÷`, 6 `< = >`, 7 `「 」 :`,
    8 `〒 々 〆`, 9 `^ | \`, 0 `~ …`; `()[]` taps `(` and flicks `) [ ]`, `.,-/` taps `.` and
    flicks `, - /`. The guide shows them as the kana's does;
  - they type as the QWERTY keys' digits and symbols do: straight in with nothing composing,
    into the reading while one is (`か3`). No toggle: 1 tapped twice is `11`;
  - あいう goes back to the kana, 記号 opens the symbols (their way back says あいう too); a
    language chosen from 🌐 switches the letters, as from the kana.
- **Not yet**: godan and other kana layouts, flick for Latin letters, ↺ undoing a commit, and
  moving the cursor inside a reading.

## Auto-correct (Space and punctuation)

What Space, punctuation, Enter or a slot tap commits, by what was typed. The level is "flexible"
unless Settings says otherwise.

1. **A shortcut matches** (the keys, ignoring case): commit its expansion, cased like the keys
   (`ko` → `không`, `Ko` → `Không`, `KO` → `KHÔNG`).
2. **The keys used Telex marks or tones** (`dduowcj`, `khoong`): the owner is accenting by hand,
   so the Telex result is committed. There are three exceptions:
   - The Telex result is not a valid syllable (`đươc`: a stop coda with no tone). Auto-correct
     takes the best valid syllable that keeps every mark that was typed (`được`).
   - The keys are also an English word, and the context says English (see "Mixed English and
     Vietnamese"): the keys are committed as typed (`list`, not `lít`, after `the`).
   - The word can't be Vietnamese at all (`tẽt` from `text`): the keys as typed, as on the
     laptop today. The spellings chat uses count as Vietnamese, as on Laban Key: any
     Vietnamese onset before any rime, whatever quốc ngữ's spelling rules say (`kais` is `kái`,
     `ngeof` is `ngèo`), the onsets `z`, `dz`, `w`, `f`, `j` (`zaayj` is `zậy`, `dzaayj` is
     `dzậy`, `was` shows `wá`), and the rimes `ơu`, `ôu`, `eu` and a bare `ă` or `â` (`ddowu` is
     `đơu`, `quaas` is `quấ`). Marks and tones land on them while they are typed, and they end
     as shown, unless the keys are an English word (`was`, `zoom`, `text`; Backspace right
     after brings the marks back). Onsets and codas Vietnamese has none of (`ck`, `sh`, `br`, a
     final `l`) still leave a word as typed.
   - A digit typed right after the letters makes the word a code or a name: it shows and ends
     as the keys typed (`tooi2`, `abc123`), as on Laban Key.
3. **The keys used no Telex effect** (all ASCII, such as `khong`, `ban`, `can`, `meeting`):
   - Candidates are:
     - every attested syllable with that ASCII shape;
     - the keys as typed, as English or as an unaccented Vietnamese syllable;
     - personal words;
     - for typos, syllables and English words one key away on the layout (see "Typos").
   - They are ranked by the bilingual model with the last three words as context.
   - The centre pick is the best. Space commits it when it is the shape's accented form, or the
     typed form itself.
   - A typo correction (a different ASCII shape) is committed only if it beats the keys as typed
     by the level's margin. Otherwise the keys stay.
4. **Neither a syllable nor a known word, and no close correction** (`dc`, `ns`, `vs`, a name):
   commit as typed. Short chat abbreviations are never "corrected" into words: letters with no
   vowel (`thx`, `fff`, `strs`) never are, and a hand-written list in the core's lexicon holds
   the ones with a vowel (`idk`, `rofl`) and the contractions typed without the apostrophe
   (`youre`, `isnt`, `dont`), which stay as typed rather than gaining one.

Levels:

| Level | Accent restoration on Space | Typo correction on Space |
|---|---|---|
| Off | no: the Telex result is committed | no |
| Flexible (default) | yes | edit distance 1, words of 3 or more letters, wide margin |
| Strong | yes | edit distance 2 for words of 7 or more letters, smaller margin |

- **Revert**: Backspace as the very next key after an auto-correct on Space takes it back with
  the space, and the word is in progress again as it was typed (`không␠` → `khong`, as Laban
  Key does); the next Space keeps it as typed. After punctuation it puts back what was typed and
  the punctuation (`không,` → `khong,`). The same when Space ended a word typed with marks as
  something else than it showed (its keys as English: `wá` ends `was`, `ò` ends `of`):
  Backspace brings back what was shown, open to go on with (`was␠` → `wá`), and the next Space
  keeps it. A second Backspace deletes as usual. When the owner
  reverts the same typed form twice in a similar context, it is learned: that form is committed
  as typed from then on, and its auto-correct moves to the left slot.
- **Look-back** (decided: on, as the reference does it): after Space, the engine re-ranks the
  last three syllables typed without marks as one sequence. If the best sequence changes an
  earlier syllable, the keyboard rewrites it (`có gắng` → `cố gắng`). It does this only if all
  of these hold:
  - the earlier syllable was restored by the engine, not typed with marks, picked from the strip
    or reverted;
  - the cursor never left the run;
  - `getTextBeforeCursor` still shows exactly what the keyboard wrote.

  Otherwise it leaves the text alone, as the laptop's instant commit does. Backspace as the very
  next key after a look-back fix undoes that fix (the earlier syllable goes back to what was
  there), the same way Revert undoes an auto-correct.
- The cursor moving by the owner's own action (a tap in the text, the cursor pad, or a selection
  change the keyboard didn't cause) ends the word in progress as Space would, without
  auto-correct, and starts a new run.

## Accent restoration and ranking (the engine)

This is the part that has to match the reference. The work is done in the core (C++) and
measured by the eval harness ("Done means").

- **Candidates per syllable**: the attested syllables with the same ASCII shape, from the core's
  `vi-syllables.txt` extended with the model's vocabulary. `duoc` gives được, dược, đước, đuốc,
  duốc, and `ban` gives 14 syllables.
- **Score**: log P(syllable | the previous three tokens) from a 4-gram Vietnamese syllable model
  with modified Kneser–Ney smoothing and pruning, stored in a compact binary format.
  - Tokens are lowercase NFC syllables, with the tone placed the engine's way, plus English
    words and `<num>`.
  - English words that occur in Vietnamese text (online, check, file) are ordinary tokens, so
    the model knows them in Vietnamese context.
- **Context**: the last three tokens before the cursor. They come from the engine's own record
  of the run, or from `getTextBeforeCursor` when the word starts in existing text.
- **Phrases** for the side slots: the best next syllable when P(next | this) is high
  (`không` → `thể`, `có`). Phrases are never the centre pick.
- **Look-back**: a beam search (width 16, `xk_restore_run`) over the unaccented run of at most
  three syllables. This is cheap: at most 26 candidates per position. When the key that ends the
  run is known (a full stop, a comma), the run is decoded once more, scored on leading to it:
  `di nhe.` ends `đi nhé`, where `di nhe` alone is `đi nhẹ`.
- **Unknown syllables**: an attested syllable the model never saw gets its share of the unknown
  mass, not all of it. Otherwise any unseen spelling would beat every rare one the model knows
  (`rội` over `rồi`).
- **Personal words** add their counts on top, as an interpolated user unigram and bigram model.
  This is how names, slang and the owner's own phrasing win after a couple of uses.
- **Capitals**: matching is on lowercase, and the commit gets the case of the typed keys back
  (`Khong` → `Không`).

## Mixed English and Vietnamese

There is one keyboard, with no language switch. For every word, the engine weighs both readings
of the keys in context:

- **Vietnamese reading**: the Telex result, or the accent-restored syllable, scored by the
  Vietnamese model.
- **English reading**: the keys as typed, as a mixture of two things. One is the Vietnamese
  model's own estimate: it has the English words Vietnamese text uses ("file", "check") as
  tokens. The other is a switch into English, scored by the English model (order 3).
- **Frames**: P(stay | the previous word's language) and P(switch | …). The values are:
  - after a Vietnamese word, 0.998 and 0.002;
  - after an English one, 0.30 and 0.70 (back to Vietnamese, or on in English);
  - at the start of a sentence, 0.85 and 0.15.

  They come from the owner's mix: mostly Vietnamese, with short English runs. The one after a
  Vietnamese word was then lowered from 0.01 until set V met its target, and no other figure
  moved. Personal learning will adapt them to the owner's own typing.
- **The same mixture scores accent restoration** (`core/score.cpp`). A word typed without marks
  (`can`, `chat`) stays English in English (`we can chat`), and stays Vietnamese in Vietnamese
  (`bạn cần`).

The likelier reading wins. Ties go to Vietnamese, the laptop's rule that "typing Vietnamese
never turns into English". The state keeps its own context:

- finished words, digits (`<num>`) and `, ; :` (`<p>`) are kept;
- `. ! ?`, Return, the cursor keys, Backspace over committed text and a reset start afresh;
- a frontend that knows the text before the cursor can set it (`xk_state_set_context`).

**Why the laptop has 19 wrong words, and how this fixes them.** The core decides at the end of
each word with no context. If the Telex result is an *attested* syllable, Vietnamese always
wins; if not, and the keys spell a common English word, English wins. Commit f6a5021 added
everyday syllables to the attested list (from Tatoeba, plus a hand list): lít, tủn, víu, bốt,
kếp, líp, rơ and others. That fixed 13 Vietnamese words, and turned 19 English words into
Vietnamese everywhere: boost, boots, corn, cups, hangs, keeps, lifts, list, lips, lisp, lust,
photos, row, runs, sits, tips, turn, verse and virus.

A yes-or-no list has to settle each overlapping word once, for every context. No choice is right
in both `the list` and `hai lít nước`. The bilingual score uses what is around the word: `my list`
→ English, `2 lít` → Vietnamese. With no context at all, it uses each reading's frequency and the
owner's language mix. The same choice covers the 128 ASCII shapes that are both an unaccented
syllable and a common English word (`can`/`cần`, `chat`/`chát`, `do`/`đó`, `con`, `bang`, `day`).

This lands in the core, so the laptop gets it too: the Fcitx5 addon already keeps the last
couple of finished words per input context and will pass them in. Without the model loaded, the
core falls back to today's list rule.

"Stop adding tone marks to non-Vietnamese words" (on) is this feature. Turning it off commits
the Telex result whatever the context, like `AutoRestore=False` on the laptop.

## Typos

Built in the core (`core/keys.cpp`, `core/restore.cpp`) as a noisy channel over the QWERTY
layout. Accent restoration offers, beside the readings of what was typed, the readings of the
spellings a key away.

- **Key geometry.** The phone's rows are laid out in key widths: q..p from 0, a..l from 0.5,
  z..m from 1.5. Aiming at a key, the finger lands on a neighbour within 1.5 key widths with
  probability e · w / Σw, where w = exp(−d² / 2·0.6²), and e = 2 % is the typo rate.
- **Cost.** Each slip costs log10 of that probability against typing the key right, about −2.2.
  A correction wins only when the words around it are worth more than that.
- **Later.** A touch keyboard knows where each finger landed, and can send per-tap neighbour
  weights instead of the layout's default. That comes with the Android keyboard.
- **Levels:**
  - flexible allows one slip, or two from 5 letters;
  - strong allows two from 4 letters.

  Words of any length from 2 letters are corrected. Two-letter slips mostly make another word,
  and only context can tell: leaving them out fixed 81 % of the slips in held-out text (dev),
  against 91 % with them. One-letter words and chat abbreviations (`ko`, `dc`, `oke`, `hihi`)
  are never corrected.
- **Calibration** was done on a held-out dev set with 3 % typos, before measuring T. At e = 2 %,
  91 % of dev's slips are fixed and 0.24 % of its correct words change. At 5 %, it is 92 % and
  0.49 %.
- **Measured on T**, made-up chat with 3 % typos and only then: 86.4 % of the mistyped words are
  fixed (target ≥ 85 %), and 0.34 % of correct words change (target ≤ 1 %).
- **Insertions, deletions and swaps** are not corrected yet. Set T has only substitutions. The
  core's older `xk_autocorrect` (Damerau–Levenshtein) still serves the laptop's suggestion
  window.

## Personal words (learning, on the phone only)

### What is learned

- **Counts.** Every committed word and word pair, in fields that allow learning, adds to its
  count. A pair is a word together with the word before it (`<s>` at the start of a sentence).
  This includes English words, names, and Vietnamese phrases the shipped model doesn't know.
- **Scoring is by pair** (`core/learn.cpp`): P = (1 − b) · P_models + b · P_pair(word | word
  before), with b = n / (n + 2) for the n times the word before was typed.
  - A pair counts once it has been typed twice and kept (not reverted): one is taken off every
    count before use.
  - Learning a word on its own, everywhere, was tried and dropped. Once "bốt" was learned, the
    real models made "my boots" Vietnamese.
  - With the real models, the five pairs they get wrong (`ngắn tủn`, `đôi bốt` …) all come out
    right once each has been typed twice, and every English context stays English (xk-eval).
- **Counts of single words** are kept for listing and completing the owner's words.
- **Reverts**: a typed form reverted twice in a similar context is kept as typed from then on.
- **Strip picks**: picking a side slot over the centre pick raises that candidate in that
  context.
- **Decay**: counts are halved every 90 days of typing (`xk_user_decay`), so old habits fade.

### Where it is kept

- One text file, `learned.txt`, in the app's credential-encrypted private storage. The core
  reads it and writes it atomically (a temporary file, then rename).
  - A word's line is `word<TAB>count`, the same shape as the laptop's
    `~/.local/share/xrnkey/user-dict.txt`.
  - A pair's line is `word word<TAB>count`.
  - Bad lines are skipped on load. The file holds at most 50,000 words and 200,000 pairs; the
    rarest go first.
- It is read only after the phone is first unlocked. Before that, the keyboard types with the
  shipped model alone.
- `allowBackup="false"`. Nothing is synced. Export and import are by hand (see Settings).

## Shortcuts

- **The table**: abbreviation → expansion, matched ignoring case, with the expansion's case
  following the keys. The same rules as the laptop's `Macro` and `CapitalizeMacro`. The matching
  code has moved from the Fcitx5 addon into the core (`core/shortcuts.cpp`, `xk_shortcuts_*`), so
  both devices behave the same. The addon now only watches its file.
- **When it expands**: on Space or punctuation, as auto-correct's first rule. The expansion also
  shows in the centre slot while the abbreviation is typed, so a tap works too.
- **Storage**: `shortcuts.txt` in private storage. Each line is `abbreviation<TAB>expansion`,
  exactly the laptop's `macros.txt` format, so one file can serve both devices.
- **Import** (Settings → Shortcuts → Import) reads a file chosen with the system picker, which
  needs no permission. Accepted lines:
  - `abbr<TAB>expansion`;
  - `abbr,expansion` (CSV, with quoted fields allowed);
  - `abbr=expansion`.

  The import shows the counts it found: new, same, changed and skipped. Nothing is written until
  the owner confirms. A changed entry replaces the old one.
  - The separator used is whichever comes first on the line, TAB taking precedence.
  - An abbreviation is one word of at most 32 bytes. Of repeated ones, the last counts.
  - A byte-order mark and Windows line ends are fine (`xk_shortcuts_import`).
- **Export** writes the table in the TAB format.
- The owner's own table is imported later, from their device. **It is never committed to this
  repo.** Tests use made-up tables.

## The emoji panel

### What it shows

The panel replaces the strip and the keys, and is laid out as Laban Key's (measured on the
owner's phone, 1080 × 2400): its layout and behaviour, none of its artwork or data.

1. **A tab row**, 48 dp: a keyboard icon (back to the letters), then emoji ☺, kaomoji `:-)`,
   symbols `Ω` and fancy letters `Aa`, as Laban Key's. The current tab has an underline in the
   accent colour. A messaging app's sticker tab is left out: its stickers are another company's
   artwork.
2. **A grid**, right under the tabs with no row between: 8 columns and exactly 4 rows in view
   (the rows share the height between the tabs and the bar; 135 × 157 px on the phone, as Laban
   Key's). An emoji is drawn about 70 px tall there, as Laban Key's, sized from the phone's
   emoji font's own measurements.
3. **One category to a page**, and on every tab recents first. The emoji tab has seven:
   recents, smileys and people, food and drink, activity, travel and places, animals and
   nature, objects. Unicode's nine groups fold into them: Smileys & Emotion with People & Body,
   and Objects with Symbols and Flags. Each page scrolls up and down within its own category
   and stops at its end. A long page scrolls far enough for its last row to clear the search
   button.
4. **A round search button**, floating over the grid's bottom-right corner, on the emoji tab.
5. **A category bar**, 48 dp: one cell for each page, then Backspace at the far right. On the
   emoji tab the cells are line icons in the palette's colours, like the keys' icons: a clock,
   a smiley, a burger, a ball, a car, a bear's face, a light bulb. Every tab's recents cell is
   the clock; the other tabs name a page by a face, a symbol or a letter from it. The current
   page's cell has a lighter rounded background.
   - Cells are at least 40 dp wide, or as wide as their face and some room. When they fit,
     they share the room left of Backspace (the emoji tab's seven, an eighth of the width each,
     as Laban Key's; the symbols' nine, 105 px each).
   - When they don't (kaomoji, letters), the bar scrolls sideways under a drag or a fling,
     Backspace staying put. The letters' cells are sized so that half of one shows at the edge.
     Turning a page brings its cell into view with a little of the next one.
6. The panel keeps the keyboard's palette, dark or light.

### What you can do

- **Tap an emoji**, a face, a symbol or a letter: inserts it and moves it to the front of that
  tab's recents. Each tab keeps its own recents, newest first: six rows (48 emoji, symbols or
  letters; 24 kaomoji). The panel stays open. On the recents page the order holds while the
  page is in view, so what is under the finger doesn't move; the new order shows when the page
  is left or the panel opens again.
- **Only a tap types**: the finger must lift within the touch slop of where it went down. A
  swipe or a scroll never types, and a tap on a grid that is still moving stops it and types
  nothing.
- **Swipe sideways on the grid**: the pages follow the finger. A fling, or a drag past a
  quarter of the width, turns to the next or previous page; anything shorter slides back.
  Before the first page and after the last the drag gives a little and springs back. The bar's
  highlight moves with the page.
- **Tap a cell on the bar**: jumps to that page. A tap on the bar while it is still flung
  stops it and picks nothing.
- Every page shows at its top when it is turned to. The panel opens on the tab it was left on,
  on its recents when there are any, otherwise on its first category (smileys and people on the
  emoji tab). While the panel stays open, a tab opens again on the page it was left on.
- **Long-press an emoji that takes skin tones**: a popup of the six tones; slide to one and
  lift. The choice is remembered for that emoji. Laban Key shows only a large preview here.
  Holding any other emoji does nothing until the finger lifts, when it types it as a tap does.
- **Search**: the letter keys come back with a search field in the strip's place. Typing goes
  into the search field, not the app. Results: the strip's emoji keyword for what is typed
  first (`haha` → 😂), then CLDR keywords in Vietnamese (with or without accents) and English,
  best first. Back closes the search.
- **Backspace** on the category bar deletes before the cursor in the app, one grapheme at a
  time, so a whole emoji goes at once.
- **Kaomoji tab**: 304 text emoticons and kaomoji, 4 to a row, each shrunk to fit its cell, in
  nine pages by feeling, each named on the bar by a face from it. A hand-written list, part of
  the project (`keyboard/Emoticons.kt`).

  | Page | Bar | Faces |
  |---|---|---|
  | Classic (`:v`, `=))`, `^^`…) | `:-)` | 36 |
  | Happy | `(^▽^)` | 34 |
  | Love and shy | `(♡‿♡)` | 34 |
  | Sad | `(T_T)` | 32 |
  | Angry | ``(`Д´)`` | 32 |
  | Surprised and confused | `(⊙_⊙)` | 32 |
  | Greetings and cheering | `(^o^)/` | 34 |
  | Animals | `ʕ•ᴥ•ʔ` | 36 |
  | Actions and other | `(っ˘ω˘ς)` | 34 |

- **Symbols tab**: 528 Unicode symbols, 8 to a row, in eight pages named on the bar by a symbol
  from each, as Laban Key's (`keyboard/Symbols.kt`).

  | Page | Bar | Symbols |
  |---|---|---|
  | Hearts, flowers and stars as ornaments | `♡` | 48 |
  | Signs: ツ-like faces, gender, planets, peace and faith, hazards, office | `ツ` | 56 |
  | Money (`₫` first), letter-like symbols, circled capitals | `A` | 64 |
  | Numbers: circled, parenthesised, Roman, superscript, subscript, fractions | `⑨` | 80 |
  | Maths and Greek | `Σ` | 72 |
  | Brackets and punctuation | `⟨⟩` | 64 |
  | Arrows | `→` | 64 |
  | Stars, shapes, chess, cards, dice, music, weather, check marks | `★` | 80 |

- **Fancy letters tab** (`Aa`): 27 pages, A to Z and the digits, 8 to a row, the bar a letter
  each. A letter's page holds it, capital and small, in every style Unicode encodes: negative
  squared 🅰, squared 🄰, negative circled 🅐, circled Ⓐ ⓐ, parenthesised ⒜, full width Ａ ａ;
  the 13 alphabets of Mathematical Alphanumeric Symbols in pairs (bold, italic, bold italic,
  script, bold script, fraktur, bold fraktur, double-struck, sans-serif, its bold, italic and
  bold italic, monospace), their holes filled from Letterlike Symbols (ℬ ℰ ℱ ℋ ℐ ℒ ℳ ℛ ℯ ℊ ℴ,
  ℭ ℌ ℑ ℜ ℨ, ℂ ℍ ℕ ℙ ℚ ℝ ℤ, italic ℎ); and the small capital ᴀ. 35 a letter; 34 for X, which
  has no small capital, and Q, whose small capital Android's fonts don't draw. The digits'
  page has a row for each, 0 to 9: bold, double-struck, sans-serif, sans-serif bold, monospace,
  full width, circled, negative circled. Worked out from the code points
  (`keyboard/FancyLetters.kt`); regional indicator letters are never used, as two side by side
  make a flag.
- Only emoji that the phone's emoji font can draw are listed (`Paint.hasGlyph`), and only
  faces, symbols and letters whose every character the panel's font or the system's draws, so
  nothing shows as an empty box. On Android 15's own fonts all of them draw, as text: none comes
  from the colour emoji font (a bare `❤` or `☺` would, so they are left out). The emoji are
  drawn with the system font, and no emoji artwork ships with the app.
- **The data**: `data/emoji/emoji-index.tsv`, read by the core (`xk_emoji_*`).
  - It holds 1,914 emoji from Unicode Emoji 17.0, in Unicode's order and groups, each with its
    CLDR 48 names and keywords in Vietnamese and English (Unicode License v3).
  - Skin-tone variants are folded into the base, which is flagged as taking them.
  - The core can also match the last three, two or one words as a keyword (`sinh nhật` → 🎂,
    `cà phê` → ☕; `xk_emoji_suggest`). Keywords that over 24 emoji share (`mặt`) are left out.
    The panel no longer shows a row of these: Laban Key has none, and the row was empty most
    of the time.
- **The strip's emoji keywords**: `data/emoji/keywords.tsv`, read into the same index. About
  1,900 keywords, each `append` or `replace`: CLDR's names of things and countries, and a hand
  list of chat words, abbreviations, emoticons, feelings and wishes (`data/emoji/README.md`). Of
  1,445 Vietnamese syllables typed bare, 15 are keywords (Laban Key, measured: 34; the CLDR
  index used before: 164).

## The clipboard panel

### What it shows

- A list, newest first, of text the owner copied while this was the keyboard. Each item shows up
  to two lines.
- Pinned items sit above the rest, under a pin icon.
- A trash button at the top clears every unpinned item.
- When the history is empty, the panel shows only the clipboard icon, greyed out.

### What you can do

- **Tap an item**: pastes it at the cursor (`commitText`) and closes the panel.
- **Long-press an item**: pin or unpin it, or delete it.
- **What is kept**: plain text only, at most 50 unpinned items (the oldest goes first), with no
  time limit. Pinned items are kept until they are unpinned. These rules are settings.
- **What is never kept**:
  - a clip marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, which password managers set);
  - a clip copied from a password field;
  - anything copied while the clipboard setting is off.
- Storage is a JSON file in credential-encrypted private storage. It is read only after unlock.

## The cursor pad

### What it shows

The panel holds:

- arrows ← → ↑ ↓;
- Home and End;
- a Select toggle;
- Select all, Copy, Cut, Paste and Delete;
- `ABC` to go back.

### What you can do

- **Arrows**: move the cursor (`setSelection` when the extracted text is known, D-pad key events
  otherwise). With Select on, they extend the selection.
- **Copy, Cut and Paste**: `performContextMenuAction`.

## Settings

A plain Compose activity, opened from the tool row's gear and from the app's launcher icon. It
follows the family's look: the xrnkey tile is the title, the text is MiSans Latin, and the
labels are short with no explanatory sentences.

### First run

Two steps, the only text being their labels: **Enable** opens Android's keyboard settings, and
**Choose** opens the keyboard picker. Each step gets a tick when done. After that, the screen
opens on the settings list.

### What it shows and does

| Section | Setting | Default (the owner's) |
|---|---|---|
| Typing | Method: Telex / VNI | Telex |
| | Tone style: old (hòa) / new (hoà) | old |
| Suggestions | Show suggestions | on |
| | Emoji suggestions | on |
| | Personal words: the list, search, add, delete one, clear all (with confirm), export, import | — |
| | Japanese (under Personal words): the words chosen, delete one, clear all (with confirm) | — |
| | Japanese dictionary (under Personal words): the owner's words with their readings, add, delete one, export, import | — |
| Auto-correct | Level: off / flexible / strong | flexible |
| | Keep non-Vietnamese words as typed | on |
| | Double space for full stop | on |
| | Look-back | on |
| Keys | Number row: on / off / auto | auto |
| | Long-press top row for digits | on |
| | Key-press popup | on |
| | Long-press delay | 400 ms |
| | Swipe space bar to move cursor | on |
| | Hold space bar to move cursor | on |
| | Hold comma to switch keyboard | on |
| | Toggle input (the 12-key pad) | on |
| | Keyboard height: 80–120 % | 100 % |
| Clipboard | Keep history | on |
| | History size | 50 |
| Shortcuts | The table: add, edit, delete, import, export | — |
| Theme | Dark slate / light / true black / system | dark slate |
| About | Version, model version, licences of the code and the model data | — |

- **Export** of personal words or shortcuts writes a `.txt` file through the system picker.
- **Import** reads one, and shows its counts before anything is written.
- The About page also shows the latency figures (p50, p95 and p99 of key-to-text since the
  keyboard started) as numbers only. They are kept in memory and never written down.

## The engine route: JNI to the shared C++ core

**Decision: JNI.** The keyboard calls `libxrnkey` through a thin JNI layer. There is no Kotlin
port.

Why:

- **One engine.** The Telex rules, the word replay, tone placement, the syllable validator and
  auto-restore are about 1,600 lines, with 66 core test cases and 35 addon tests tuned across
  many fixes. A port would be a second copy that drifts, and every later fix would have to be
  made twice. The owner's rule is that improvements reach both devices.
- **It already fits.** The core is standard C++20 plus POSIX `mmap`, behind a C ABI of plain
  structs. Strings that cross the boundary are documented `malloc`'d copies. Its keys are XKB
  keysyms, which equal the character code for every key a phone sends, plus BackSpace
  (`0xFF08`). The engine's preedit mode is exactly Android's composing text: `preedit` →
  `setComposingText`, `commit` → `commitText`. Instant commit isn't needed on Android.
- **Speed.** A JNI call costs about a microsecond. The model's lookups and the beam search stay
  in C++ next to the memory-mapped data.

What it costs, and how each cost is handled:

- **An NDK build.**
  - `android/app/src/main/cpp/CMakeLists.txt` adds `../../../../../core` as a subdirectory, so
    there is one copy of the sources. It builds for `arm64-v8a` only, which is the owner's phone.
  - The core asks for CMake 3.27, but the SDK has 3.22. Either install SDK CMake 3.31 or set
    `cmake.dir` to the system CMake in `local.properties`.
  - NDK 27 is already installed.
  - The core links `pthread` by name. It should use `Threads::Threads`, which works on the
    laptop and on Android.
- **A crash in native code takes the keyboard down.**
  - No C++ exception crosses the C ABI; each entry point catches.
  - Host tests run the C API under ASan and UBSan, with a fuzz test of random key streams.
  - The JNI layer holds no global references between calls.
- **JVM unit tests need the engine.** The same JNI library is also built for the host
  (linux-x86_64, against the JDK's `jni.h`). JVM tests load it with `System.load`, so they run the
  real engine with no phone and no Robolectric.

### Core additions (all in `core/`, used by both frontends)

| Module | What | Used by the laptop |
|---|---|---|
| `lm.cpp` | Binary n-gram model: memory-mapped, 8-bit quantised probabilities and backoffs, sorted arrays with binary search. Opened from a path or from a file descriptor plus offset. Replaces the 132-line toy `vi.arpa` and its ARPA loader. | yes |
| `restore.cpp` | Candidates per ASCII shape, scoring in context, the look-back beam | yes, as suggestions |
| `bilingual.cpp` | The English model, the switch cost, and the context-aware pass-through decision in `word_final` | yes: fixes the 19 words |
| `learn.cpp` | Personal unigrams and pairs, reverts, decay, atomic save | yes, replacing `xk_dict_add_word`'s plain list |
| `shortcuts.cpp` | Moved from `fcitx5-addon/macro_table.cpp` | yes |
| `touch.cpp` | Key-neighbour weights in the edit cost | no |
| `emoji.cpp` | Rewritten over a CLDR-built index (Vietnamese and English keywords) | yes |

The new C API is a *session*: one opened model, the state, the context, and a ranked candidate
list carrying each candidate's kind (accented, as typed, phrase, correction, shortcut, emoji,
personal) and an "auto" flag for the centre pick. Calls commit a candidate, revert the last
commit, and teach. The existing `xk_process_key` contract stays as it is, and the Fcitx5 tests
keep passing.

## The Android app

- **Stack**:
  - Kotlin, with an `InputMethodService`.
  - The keys and the strip are one custom `View` drawn on a `Canvas`. That is the hot path, as in
    AOSP's LatinIME, with no recomposition per key.
  - The panels (emoji grid, clipboard, cursor pad, expanded list) and Settings are Jetpack
    Compose.
  - The Compose views in the IME window get the lifecycle and saved-state owners set on the
    window's decor view.
- **Project**:
  - The package is `com.xern.xrnkey`. The system lists the keyboard as "xrnkey", because the
    OS needs text.
  - `android/` is a Gradle project copied from the family's smallest app setup (`xrn-android-app`
    skill): AGP 9, Kotlin 2.4, compileSdk and targetSdk 35, minSdk 26, Java 17, and release builds
    with R8.
- **Font**: MiSans Latin VF from `res/font/`, credited in the README. The key glyphs use regular
  weight.
- **Before first unlock**: the service is `directBootAware`, so it types with the shipped model.
  Personal files load on `ACTION_USER_UNLOCKED`.
- **HyperOS**: an IME is bound by the system, so it needs no Autostart. Check that HyperOS's
  "secure keyboard" replacing third-party keyboards in password fields does no harm.
- **Tests**:
  - JUnit 4 on the JVM, against the host-built engine. The Telex corpus rows of
    `tests/test_corpus.cpp` move to a shared data file that both the Catch2 suite and a Kotlin
    test read, so the two can't disagree.
  - The InputConnection logic (composing, revert, look-back guards, cursor-moved detection) runs
    against a fake text buffer, like the laptop addon's fake client.
  - The keyboard's screenshots are drawn by the real view with Robolectric's native graphics
    (`KeyboardShotsTest`). This is the only use of Robolectric; each test class gets a JVM of its own,
    since a native library binds to one class loader.
  - A JVM benchmark (`EngineBenchmarkTest`) times a keystroke through JNI (the Telex step plus the
    strip) and the restoration run at a word's end, with the real models when present.
- **CI** (the repo is public, so minutes are free): a second job builds the debug APK, runs the
  JVM tests, and fails if the merged manifest names any `uses-permission`. CI uses a small test
  model built from the made-up test text, so it downloads no corpora.

## The model data

All of it ships in the APK and is never downloaded.

### Sources (redistributable, credited in `data/README.md` and Settings → About)

| Source | Used for | Licence |
|---|---|---|
| Leipzig Corpora Collection, Vietnamese news and web corpora (the downloadable sentence files) | the Vietnamese 4-gram model, phrases, the switch cost | CC BY |
| Leipzig Corpora Collection, English news corpus | English unigrams and bigrams | CC BY |
| Tatoeba Vietnamese sentences (already a source of `vi-syllables.txt`) | short conversational sentences for the model; part held out | CC BY 2.0 FR |
| Mozilla Common Voice Vietnamese sentences (already a source) | short sentences; part held out | CC0 |
| Wikisource public-domain texts (already a source) | the syllable inventory only; too old-fashioned for ranking | public domain |
| Unihan readings (already a source) | the syllable inventory | Unicode License v3 |
| SCOWL (already the source of `english.txt`) | the English vocabulary | SCOWL's permissive notice |
| Unicode CLDR annotations, `vi` and `en`, and `emoji-test.txt` | emoji names, keywords, order and groups | Unicode License v3 |
| Mozc's open-source dictionary (`src/data/dictionary_oss`: the words and the connection costs), itself from IPAdic and the Okinawa dictionary | the Japanese keyboard's conversions (`data/ja`) | BSD 3-Clause, with the IPAdic and ICOT notices (`data/ja/NOTICE`, shown in Settings → About → Licences) |
| A hand list of Vietnamese and English chat words, abbreviations and emoticons, written for this project (`data/emoji/keywords-hand.tsv`) | the strip's emoji keywords | MIT, with the code |
| A made-up chat and test set, written for this project | tuning the switch cost and abbreviations; the eval set C | MIT, with the code |

Rules:

- **Not used**:
  - Wikipedia, whose CC BY-SA would make the model BY-SA;
  - anything non-commercial (OPUS's TED and QED);
  - anything with no clear licence: subtitles, scraped news dumps, the Common Crawl text in
    OSCAR.
- The Leipzig terms page must be read and its exact wording recorded in `data/README.md` before
  a model ships.
- The shipped model is under **CC BY 4.0**, with the attributions above. The code stays MIT.

### Building it

`data/lm/README.md` has the commands. In short:

- `data/lm/sources.json` lists each source's URL, SHA-256 and licence.
- `data/lm/build.py` (Python standard library) works in three steps:
  - **fetch** downloads each source into its own folder and refuses any other hash;
  - **prepare** tokenizes them, streaming the archives without unpacking them: NFC, the engine's
    tone placement, lowercase, numbers as `<num>`. It also cuts the held-out sets by a hash of
    each sentence;
  - **train** runs `tools/lm/xk-lm-build`, this repo's own trainer: interpolated modified
    Kneser–Ney, count pruning, renormalised backoffs, written straight to the binary format. It
    needs no outside tool.
- The outputs (`vi.xklm`, `en.xklm`) are **not in git**. They are rebuilt from the pinned
  sources, and are proposed as release assets of this repo, with `data/lm/model.lock` pinning
  their SHA-256 for the Android and Arch builds.
- CI builds small models from made-up text (`data/lm/test`) at build time.

### Size on the phone

| File | Budget |
|---|---|
| `vi.lm` (about 4 M n-grams after pruning) | ≤ 25 MB |
| `en.lm` | ≤ 6 MB |
| `lexicon.bin`, `emoji.bin` | ≤ 3 MB |
| `libxrnkey.so` + JNI (arm64) | ≤ 3 MB |
| `ja.xkdic` (the Japanese dictionary; 6.8 MB built) | ≤ 10 MB |
| APK total | ≤ 45 MB |

- The model files and the Japanese dictionary are stored uncompressed in the APK
  (`noCompress`). They are memory-mapped straight from it through the asset's file descriptor
  and offset, so nothing is copied and only the pages in use take RAM.
- If that fails on some phone, the files are copied once to `noBackupFilesDir`.

## Data and privacy

- **Permissions**: none, and **no `android.permission.INTERNET`**. A CI test fails the build if
  the merged manifest declares any `uses-permission`. The service only *requires*
  `BIND_INPUT_METHOD` of the system that binds it.
- **On the phone** (credential-encrypted private storage):
  - personal words and pairs;
  - the words chosen on the Japanese keyboard, and pairs of them;
  - the owner's own Japanese words and their readings;
  - the revert memory;
  - the shortcut table;
  - the clipboard history and pins;
  - emoji recents and skin tones;
  - settings.

  These are kept until deleted in Settings, or the app is uninstalled. `allowBackup="false"`.
- **Never stored**:
  - anything typed in a password field;
  - text from fields that ask for no personalised learning;
  - sensitive clips;
  - whole messages. Only word counts and word-pair counts are learned, never sentences.
- **Leaves the phone**: nothing, except files the owner exports by hand through the system
  picker.
- **No microphone**: there is no voice input, and nothing is ever recorded.

## Sync

There is none. The keyboard has no network. Personal words and shortcuts move between the phone
and the laptop only as files the owner exports and imports. Both files use the laptop IME's
formats (`user-dict.txt`, `macros.txt`), so the same file works on both devices.

## Updates

A keyboard with no internet can't update itself, so the owner's hub app (a separate app of
theirs that already has network access and lists every project) does it. The keyboard never
gets INTERNET.

- The release APK is published to the owner's own server next to the hub app's other release
  files.
- The hub app checks the server, and when a newer keyboard is there it shows **Keyboard update
  ready**. The owner taps **Install**.
- It installs the APK with a `PackageInstaller` session, the same way the owner's rules app
  already updates itself. After its first install the hub app is the installer of record, so
  later updates need no extra confirmation (`USER_ACTION_NOT_REQUIRED`).
- Android installs an update only if it is signed with the keyboard's key, so a compromised
  server can't push a different keyboard.
- An IME update keeps the keyboard enabled and chosen as the default.
- Until the hub app has this, a release is installed with adb from the laptop.

## Signing key

The keyboard uses **the owner's release keystore**, the one their apps that share a signature
permission already use. The build reads it from `../xrn-signing.properties`, outside the repo, so
release builds are made from the main checkout. It is never the shared debug key. The choice is
made once and never changes.

Why:

- An input method sees every key typed in every app, so it gets the password-protected release
  key, which the owner's key backup already covers.
- Every debug build on the laptop is signed with the debug keystore, and its password is the
  well-known default. Anything that ever trusted "same signature" would trust them all.
- A signature permission may be needed later between the keyboard and an updater or a sibling
  app, which is the family's rule for using the release key.

Nothing about the key enters this public repo except that relative path.

## Done means

All the checks below are measured by the eval harness `tools/xk-eval`. It is part of the first
PR, prints a table, and runs in CI on the small test model, plus on the laptop against the real
one. The thresholds are first targets. The parity run (last item) replaces them with the
reference's own numbers where it beats them.

### Evaluation sets

| Set | What | Licence |
|---|---|---|
| **H1** | 2,000 held-out sentences from the Leipzig news and web corpora, never used in training, near-duplicates removed | CC BY |
| **H2** | 1,000 held-out short sentences from Tatoeba and Common Voice | CC BY 2.0 FR / CC0 |
| **C** | 300 made-up chat messages: casual register, abbreviations (`ko`, `dc`, `r`), about 1,000 English tokens mixed in, each with its intended text | MIT, written for this project |
| **E** | the 10,972 words of `english.txt` | as that file |
| **V** | the 5,840 syllables of `vi-syllables.txt` | as that file |
| **T** | set C with deterministic adjacent-key typos at 3 % per letter (seeded, using the real layout geometry) | MIT |

### Targets

1. **Accent restoration**, typed syllable by syllable without marks, scored per syllable with
   left context and look-back. The report also gives the figures without look-back.
   - H1: top-1 ≥ 95.0 %, and in the strip (top-3) ≥ 99.0 %.
   - H2: top-1 ≥ 94.0 %.
   - C: top-1 ≥ 93.0 %.
2. **English pass-through.**
   - E, each word typed with Telex after `the`: ≥ 99.5 % committed as typed.
   - C: ≥ 98.0 % of English tokens as typed.
   - V typed with Telex after a Vietnamese syllable, plus the Vietnamese of C: ≤ 0.5 % wrongly
     turned English.
   - Each of the 19 words gets two test rows: it stays English after an English word
     (`my list`), and stays Vietnamese after a Vietnamese word it goes with (`hai lít`,
     `giành cúp`, `ngắn tủn`). All 38 rows pass, on the laptop's Fcitx5 tests too.
3. **Telex parity**: every existing core and addon test passes. The shared corpus rows pass
   through JNI with the same results.
4. **Typos** (T): ≥ 85 % of corrupted words fixed by Space, and ≤ 1 % of correct words changed.
5. **Suggestions** (C and H2):
   - next-word top-3 hit rate ≥ 30 %;
   - after the first two letters, top-3 ≥ 70 %;
   - keystroke savings when the strip is used whenever the word is in it: ≥ 25 %.
6. **Latency**, on the owner's phone, release build, from 1,000 taps typed by script:
   - from the touch event to the commit or composing call returning: p95 ≤ 20 ms, p99 ≤ 35 ms;
   - the strip updated: p95 ≤ 30 ms after the key;
   - the engine call alone: p99 ≤ 5 ms;
   - the keyboard shown after the process was killed: ≤ 300 ms;
   - janky frames in a 200-key burst (`dumpsys gfxinfo`): ≤ 1 %.
7. **Memory and size**:
   - PSS ≤ 80 MB with the model mapped and in use (`dumpsys meminfo`);
   - APK ≤ 45 MB;
   - model files within the budgets above.
8. **Privacy**: `aapt2 dump permissions` lists nothing. The clipboard test shows that a
   sensitive clip and a password-field copy are not kept. A password field gets every key as
   typed and learns nothing.
9. **Parity with the reference** (decided: yes; the owner's side runs it on the phone later):
   - The run uses 300 sentences from H2 and C, with no private text, typed by adb taps at key
     coordinates into a plain text field.
   - Each keyboard types them with its own defaults, and the reference with its learning turned
     off.
   - Result: this keyboard's top-1 restoration and English pass-through are ≥ the reference's on
     the same sentences.
   - This is recorded in `android/docs/status.md` before the reference is uninstalled.
10. **Daily use**: the owner types with it as their only keyboard for 7 days, and the gaps they
    hit are listed in `android/docs/status.md` and fixed or agreed.

## Build order (each step one PR, with its tests)

1. **Model pipeline and eval harness.** `data/lm` (sources, cleaning, training), the binary
   `lm.cpp` and its reader tests, `tools/xk-eval`, and sets H1, H2, C, E, V and T. Baseline
   figures in the PR.
2. **Accent restoration.** `restore.cpp`, the session API, look-back, phrases. Eval target 1.
3. **Context-aware English decision.** `bilingual.cpp`, and the Fcitx5 addon passing its context.
   The 38 rows of target 2. This ships to the laptop first.
4. **Learning, shortcuts in the core, key-distance typos, CLDR emoji index.** Targets 4 and 5.
5. **`android/` skeleton.**
   - Gradle and the JNI build (arm64 plus the host);
   - host JNI tests and the shared corpus file;
   - the service, with letters, the number row, symbols and Telex as composing text;
   - Settings' first run and the no-permission CI check.

   The first build the owner can install.
6. **The strip and auto-correct.** Candidates, Space and punctuation, Revert, the expanded list,
   next-word predictions, double Space, field types.
7. **Gestures and popups.** Moving the cursor with the space bar and the trackpad, long-press
   digits and variants, comma to the picker, the key preview, Backspace repeat.
8. **The emoji panel** and emoji suggestions.
9. **The clipboard panel, cursor pad, tool row, themes and height.**
10. **Settings in full.** Personal words, shortcut import and export, About with licences and
    latency.
11. **A pass on the phone.**
    - latency, memory and the parity run;
    - `android/docs/status.md`;
    - the README's Android section and parity section;
    - registering the project with the owner's other apps.

### Where the build stands (round 4)

Steps 1 to 10 are built, with these differences from the text above:

- **Cursor pad:** the tool row's button turns the keys into a trackpad (drag to move by
  characters and lines, tap to leave), the same pad as holding the space bar. The edit panel
  with Home, End, Select, Copy, Cut and Paste is not built.
- **Settings** are a main list with a page per section (Keyboard, Typing, Suggestions &
  auto-correct, Personal words, Shortcuts, Clipboard, Theme, About and its Licences), in the
  keyboard's theme. Personal words: the list, search, delete one and clear all; adding a word,
  export and import are not built.
- **Theme:** dark slate, light and system. True black is not built.
- **Clipboard:** history size is fixed at 50; no time limit.
- **Look-back** has no setting; it is always on.
- **Next words** come from `xk_predict` (core/predict.cpp). The expanded list (≡ with more
  candidates) is not built; ≡ shows the tool row.
- **Composing underline:** off by default (the owner's setting), by passing the composing text as
  a span-free `Spannable`; editors that draw their own (Chrome, WebView) may still underline it.

- **Space, since 0.4.0**, works as Laban Key does: it keeps a word typed without marks as it was
  typed (and fixes only a word wrong in both languages, "teh" -> "the"); the accented reading is
  the strip's middle slot, bold only when Space will commit it. "Add tones on space" (Off,
  When needed, Always) brings back restoration: When needed only for words that aren't words as
  they stand ("khong", never "ong"); Always as before, with look-back. The rules in
  "Auto-correct" below describe Always.
- **The strip, since 0.4.2**, fills its slots as Laban Key does. Its guesses are the readings of
  the word, a reading with its likely next word ("cảm ơn"), and completions of the letters
  typed (`xk_complete`: common English words, and Vietnamese syllables, accented: "xin" ->
  "xinh"), all ranked in context by the models. A word typed without marks takes no slot of its
  own, since Space keeps it. Laban, measured on the phone, against xrnkey with the real models:

  | Typed | Laban | xrnkey |
  |---|---|---|
  | `hel` | help \| hello \| held | help \| hello \| helping |
  | `xin` | xịn \| xin lỗi \| xinh | xịn \| xin lỗi \| xinh |
  | `meet` | mệt mỏi \| mệt \| mệt rồi | mệt mỏi \| **meet** \| mệt |
  | `cam` | cám ơn \| cảm ơn \| 🍊 | cảm \| cảm ơn \| cám ơn |

  xrnkey ends "meet" as the English word, so that is in the middle, in bold.
- **The middle, since 0.4.3**, is weighed by language for letters typed without marks
  (`xk_word_languages`): a Vietnamese syllable with a common reading keeps a Vietnamese reading
  or pair there, and English completions go to the sides ("cam" after an English word had
  "came"); an English word likelier as English stays itself ("the", "love").

Step 11, the pass on the phone, has not started: nothing has run on a real phone yet.

## Risks

- **Training text.**
  - The reference's maker also runs a huge messaging app, so its model has very likely seen far
    more casual Vietnamese chat than any openly licensed corpus holds.
  - Leipzig's news and web text is formal. Accuracy on chat (set C) is the number most at risk,
    and the one the owner feels.
  - Mitigations: phrases learned on the phone, the made-up chat set for tuning, and the parity
    run to show the real gap.
- **The parity run** happens on the phone, by the owner's side, before the reference is removed.
  Until then the targets stay as written, with no direct comparison.
- **Typo correction** is where mature keyboards are strongest. A neighbour-weighted edit distance
  is simpler than a full touch model, so set T may show a gap.
- **App quirks.** Composing text, `deleteSurroundingText` and `getTextBeforeCursor` behave
  differently across messaging apps and browsers. Look-back and Revert must check the text before
  touching it, and otherwise do nothing, as the laptop's instant commit does.
- **The shared engine.** A change made for the phone can break the laptop. Every core PR runs the
  Catch2 suite, the Fcitx5 tests and the JVM tests.
- **Model hosting.** The model lives as a release asset of a public repo. Losing the asset means
  retraining from `sources.json`, which takes hours, not days.
- **Licences.** Leipzig's CC BY wording has to be confirmed on its own terms page before
  anything ships.

## Decisions (2026-11-21)

The owner answered the spec's open questions:

1. **No mic key at all.** The keyboard is fully offline, with no voice typing.
2. **The parity run** against the reference happens later, on the phone, run by the owner's side.
3. **Updates come through the hub app**: it shows "Keyboard update ready" and installs on a tap.
   The keyboard itself never gets INTERNET.
4. **Look-back is on**: the previous word is fixed in place, as the reference does, and
   Backspace right after undoes the fix.
5. **The emoji, `:-)` and `Ω` tabs stay.** Fancy text comes later. The sticker tab is left out.
   (2026-11-24: the kaomoji were "not enough". The `:-)` and `Ω` tabs now hold what Laban Key's
   do, and the `Aa` tab came, all with their own recents.)

No questions are open.
