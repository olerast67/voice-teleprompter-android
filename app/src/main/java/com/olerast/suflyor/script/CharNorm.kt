package com.olerast.suflyor.script

import java.text.Normalizer
import java.util.Locale

/**
 * Character-by-character rules for languages whose recognizers print no spaces: Chinese (every Han character is a
 * unit) and Korean (every Hangul syllable is a unit; the Korean model joins whole sentences into one run).
 *
 * Recognizers mostly confuse characters that sound alike, so two different characters still match when they sound
 * the same: Chinese by their Mandarin reading without tones (zh_pinyin.txt, from Unihan), Korean by the letters
 * (jamo) of the syllable, where a different final consonant still counts as close.
 *
 * Latin words and numbers inside such a script stay whole units; Latin letters are foreign for the model (weight 0).
 */
class CharNorm private constructor(
    val code: String,
    private val script: Character.UnicodeScript,
    /** Very frequent characters: they prove little about the position. */
    stop: String,
    fillers: String,
    /** Spoken numerals: dropped from what was heard when the script writes digits. */
    numerals: String,
    private val sound: (Char) -> String?,
) : PackRules {
    private fun chars(s: String) = s.filter { !it.isWhitespace() }.map { it.toString() }.toSet()

    private val stop = chars(stop)
    private val numerals = chars(numerals)

    override val wordRegex: Regex = Regex(
        when (script) {
            Character.UnicodeScript.HAN -> "\\p{IsHan}|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+"
            else -> "\\p{IsHangul}|[\\p{L}\\p{N}&&[^\\p{IsHangul}]]+"
        },
    )

    override val fillers: Set<String> = emptySet()

    /** Hesitations are single characters that also occur inside words: dropped only when the script lacks them. */
    override val softFillers: Set<String> = chars(fillers)

    override val conjunctions: Set<String> = emptySet()

    /** A character carries a third of a word: match twice as many recent units, and keep more of them. */
    override val tail: Int get() = 14
    override val history: Int get() = 28

    /** A Chinese word has about 1.7 characters; a Korean word (eojeol) about 3 syllables. */
    override val unitsPerWord: Float get() = if (script == Character.UnicodeScript.HAN) 1.7f else 3f

    override fun normalizeWord(raw: String): String {
        val s = Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        return s.filter { it.isLetterOrDigit() }
    }

    override fun words(text: String): List<String> =
        wordRegex.findAll(text).map { normalizeWord(it.value) }.filter { it.isNotEmpty() }.toList()

    override fun isForeign(raw: String): Boolean {
        var letters = false
        for (ch in raw) {
            if (!ch.isLetter()) continue
            letters = true
            if (Character.UnicodeScript.of(ch.code) == script) return false
        }
        return letters
    }

    override fun weight(norm: String, foreign: Boolean): Float = when {
        foreign -> 0f
        norm.isEmpty() || norm[0].isDigit() -> 0f
        norm in stop -> 0.2f
        // One character is about a third of a word; the tracker's frequency weights lower the common ones further.
        else -> 0.5f
    }

    override fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.length != 1 || b.length != 1) return 0f
        val sa = sound(a[0]) ?: return 0f
        val sb = sound(b[0]) ?: return 0f
        if (sa == sb) return 0.85f
        // Korean: the same initial consonant and vowel, another final consonant ("갔"/"감").
        if (script == Character.UnicodeScript.HANGUL && sa.length >= 2 && sb.length >= 2 && sa.take(2) == sb.take(2)) return 0.75f
        return 0f
    }

    override fun stem(word: String): String = word

    override fun canBePrefix(partial: String): Boolean = false

    override fun prepareHypothesis(hyp: List<String>, vocab: Set<String>, scriptHasDigits: Boolean): List<String> =
        if (!scriptHasDigits) {
            hyp
        } else {
            // "二百万" said for "200万": the numerals are extra units unless the script writes them too.
            hyp.filter { h -> !(h.firstOrNull()?.isDigit() == true || (h in numerals && h !in vocab)) }
        }

    override fun isAbbreviation(word: String): Boolean = false

    companion object {
        /** Mandarin readings without tones, loaded once from the table in the app's Java resources. */
        private val pinyin: Map<Char, String> by lazy {
            val map = HashMap<Char, String>(24000)
            CharNorm::class.java.getResourceAsStream("zh_pinyin.txt")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val reading = line.substring(0, tab)
                    for (k in tab + 1 until line.length) map[line[k]] = reading
                }
            }
            map
        }

        /** A Hangul syllable as its letters: initial, vowel and, if any, final consonant (Unicode decomposition). */
        private fun jamo(ch: Char): String? =
            if (ch in '가'..'힣') Normalizer.normalize(ch.toString(), Normalizer.Form.NFD) else null

        val ZH = CharNorm(
            code = "zh",
            script = Character.UnicodeScript.HAN,
            stop = "的了是在我有和就不人都一这个上也很到说要去你会着没看好自己那他她它们们吗呢吧啊",
            fillers = "嗯啊呃哦额唔",
            numerals = "零〇一二两三四五六七八九十百千万亿",
            sound = { pinyin[it] },
        )

        val KO = CharNorm(
            code = "ko",
            script = Character.UnicodeScript.HANGUL,
            stop = "이가을를은는의에도와과로다고서하한해요니것수있지게기어아라면",
            fillers = "음어아으흠",
            numerals = "영공일이삼사오육칠팔구십백천만억",
            sound = { jamo(it) },
        )

        /** For tests: the reading of a Chinese character, or null. */
        internal fun pinyinOf(ch: Char): String? = pinyin[ch]
    }
}
