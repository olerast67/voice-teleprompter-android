package com.olerast.suflyor.script

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument

/** One word of the displayed script. [start]/[end] are offsets in [ScriptModel.displayText], end exclusive. */
data class Token(
    val norm: String,
    val start: Int,
    val end: Int,
    /** False for headings and [stage directions]: shown, but not expected to be spoken. */
    val spoken: Boolean,
    val weight: Float,
    /** First word of a displayed line, sentence or paragraph: where readers restart or jump to. */
    val anchor: Boolean = false,
    /**
     * Joined to the next token by a hyphen in a chain of letter names like "Би-ту-би" or "Эй-Ай" (Russian only, see
     * ScriptLayout.markLetterChains). The recognizer prints such a chain glued ("битуби") or apart ("би ту би").
     */
    val hyphenNext: Boolean = false,
)

class ScriptModel(
    val title: String,
    val displayText: String,
    val tokens: List<Token>,
    val emphasis: List<IntRange>,
    val notes: List<IntRange>,
    /** Language the tokens were normalized for: the tracker and the recognizer must use the same one. */
    val lang: SpeechLang = SpeechLang.RU,
) {
    val spokenTokens: Int = tokens.count { it.spoken }

    /** Reading time at a given pace, words per minute. */
    fun durationSeconds(wpm: Int): Int = if (wpm <= 0) 0 else (spokenTokens * 60 + wpm - 1) / wpm

    companion object {
        val EMPTY = ScriptModel("", "", emptyList(), emptyList(), emptyList())
    }
}

/**
 * Script words to bias the recognizer towards: informative spoken words, as the recognizer of [ScriptModel.lang]
 * would print them. Russian takes the written word, as before English existed; English takes the normalized form, so
 * apostrophes are straight and "Mr." is "mister". The recognizer keeps only what its vocabulary can encode.
 */
fun ScriptModel.biasWords(): List<String> = tokens.indices.asSequence()
    .mapNotNull { i ->
        val t = tokens[i]
        when {
            // A chain of letter names is too short to bias part by part; glued, the model prints it as written.
            t.hyphenNext && (i == 0 || !tokens[i - 1].hyphenNext) -> {
                var k = i
                val glued = StringBuilder()
                while (true) {
                    glued.append(displayText, tokens[k].start, tokens[k].end)
                    if (!tokens[k].hyphenNext) break
                    k++
                }
                glued.toString().lowercase()
            }
            !t.spoken || t.weight < 1f -> null
            lang == SpeechLang.RU -> displayText.substring(t.start, t.end).lowercase()
            // The Turkish model prints what TurkishNorm makes of the written word: tr-TR lowercase, no apostrophe
            // ("İstanbul'da" -> "istanbulda"). Normalized again from the display text so the rule lives in one place.
            lang == SpeechLang.TR -> TurkishNorm.normalizeWord(displayText.substring(t.start, t.end))
            else -> t.norm
        }
    }
    .distinct()
    .take(2000)
    .toList()

object ScriptLayout {
    const val PARAGRAPH_SEPARATOR = "\n\n"

    /**
     * @param phraseMode one phrase per line (break at sentence ends, clause punctuation and long phrases);
     *   otherwise paragraphs are left to wrap at the window width.
     * @param lang language the reader speaks: how words are split, normalized and weighted, and where lines break.
     */
    fun build(doc: ScriptDocument, phraseMode: Boolean, maxWords: Int = 7, lang: SpeechLang = SpeechLang.RU): ScriptModel {
        val sb = StringBuilder()
        val emphasis = mutableListOf<IntRange>()
        val notes = mutableListOf<IntRange>()
        for (p in doc.paragraphs) {
            if (p.text.isBlank()) continue
            if (sb.isNotEmpty()) sb.append(PARAGRAPH_SEPARATOR)
            val offset = sb.length
            val chars = p.text.toCharArray()
            if (phraseMode && p.kind == Paragraph.Kind.BODY) {
                for (pos in PhraseBreaker.breakPositions(p.text, maxWords, lang)) chars[pos] = '\n'
            }
            sb.append(chars)
            p.emphasis.forEach { emphasis += (it.first + offset)..(it.last + offset) }
            if (p.kind == Paragraph.Kind.HEADING) notes += offset until offset + p.text.length
        }
        val text = sb.toString()
        notes += bracketNotes(text)
        val noteChars = BooleanArray(text.length)
        for (r in notes) for (k in r) if (k in noteChars.indices) noteChars[k] = true
        val tokens = lang.wordRegex.findAll(text).mapNotNull { m ->
            val norm = lang.normalize(m.value)
            if (norm.isEmpty()) return@mapNotNull null
            val inNote = noteChars[m.range.first]
            Token(
                norm, m.range.first, m.range.last + 1,
                spoken = !inNote,
                weight = lang.weight(norm, lang.isForeign(m.value)),
                anchor = startsLine(text, m.range.first, lang),
            )
        }.toList()
        val marked = if (lang == SpeechLang.RU) markLetterChains(text, tokens) else tokens
        return ScriptModel(doc.title, text, marked, emphasis, notes.sortedBy { it.first }, lang)
    }

    /** Hyphens inside a word: "-", the Unicode hyphen and the non-breaking hyphen. */
    private val HYPHENS = setOf("-", "\u2010", "\u2011")

    /** Parts of "кое-как", "ну-ка", "из-за": ordinary words, not letter names. */
    private val HYPHEN_PARTICLES = setOf("кое", "ка", "де", "тка", "с")

    /**
     * Marks chains of letter names written with hyphens ("Би-ту-би", "Эй-Ай", "Ай-ти") with [Token.hyphenNext]: two
     * or more spoken Cyrillic parts of up to three letters, none of them a function word or particle ("кто-то",
     * "из-за", "кое-где" stay as they are), four or more letters together.
     */
    private fun markLetterChains(text: String, tokens: List<Token>): List<Token> {
        fun isPart(t: Token) = t.spoken && t.norm.length <= 3 && t.norm.all { it in 'а'..'я' } &&
            TextNorm.weight(t.norm) > 0.2f && t.norm !in HYPHEN_PARTICLES
        var out: MutableList<Token>? = null
        var i = 0
        while (i < tokens.size) {
            var k = i
            while (k + 1 < tokens.size && text.substring(tokens[k].end, tokens[k + 1].start) in HYPHENS) k++
            val run = i..k
            if (k > i && run.all { isPart(tokens[it]) } && run.sumOf { tokens[it].norm.length } >= 4) {
                if (out == null) out = tokens.toMutableList()
                for (j in i until k) out[j] = tokens[j].copy(hyphenNext = true)
            }
            i = k + 1
        }
        return out ?: tokens
    }

    /**
     * A word starts a line if only spaces separate it from a line break, sentence end or stage direction.
     * In English and Turkish, curly quotes are skipped too (“Stop.” ‘Why?’ he asked), and the period of an
     * abbreviation ("Mr.", "e.g.", "Dr.", "vb.") is not a sentence end.
     */
    private fun startsLine(text: String, offset: Int, lang: SpeechLang): Boolean {
        val curly = lang != SpeechLang.RU
        var i = offset - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '"' || text[i] == '«' || text[i] == '(' || text[i] == '—' || text[i] == '–' ||
                (curly && text[i] in "“”‘’"))
        ) i--
        if (i < 0) return true
        if (text[i] !in "\n.!?…:;]") return false
        return !(text[i] == '.' && isAbbreviation(wordEndingAt(text, i), lang))
    }

    /** Russian keeps its old rule: every period ends a sentence. */
    internal fun isAbbreviation(word: String, lang: SpeechLang): Boolean = when (lang) {
        SpeechLang.RU -> false
        SpeechLang.EN -> EnglishNorm.isAbbreviation(word)
        SpeechLang.TR -> TurkishNorm.isAbbreviation(word)
    }

    /** The word (up to whitespace) whose last character is at [last]: "Mr." for the period of "Mr.". */
    private fun wordEndingAt(text: String, last: Int): String {
        var s = last
        while (s > 0 && !text[s - 1].isWhitespace()) s--
        return text.substring(s, last + 1)
    }

    /** [Stage directions in square brackets] are displayed but skipped by the voice tracker. */
    private fun bracketNotes(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = text.indexOf('[')
        while (i >= 0) {
            val close = text.indexOf(']', i + 1)
            if (close < 0) break
            val paraEnd = text.indexOf(PARAGRAPH_SEPARATOR, i).let { if (it < 0) text.length else it }
            val reopen = text.indexOf('[', i + 1)
            // An unmatched "[" (closed only in a later paragraph, or reopened first) is plain text: skip just it.
            if (close > paraEnd || (reopen in 0 until close)) {
                i = reopen
                continue
            }
            out += i..close
            i = text.indexOf('[', close + 1)
        }
        return out
    }
}

/** Splits a paragraph into phrases that can be said in one breath; returns positions of spaces to turn into line breaks. */
object PhraseBreaker {
    /** Words a phrase may start with. The English entries here predate [CONJUNCTIONS_EN] and stay for Russian scripts. */
    private val CONJUNCTIONS = setOf(
        "и", "а", "но", "или", "что", "чтобы", "потому", "поэтому", "если", "когда", "который", "которая",
        "которое", "которые", "которых", "где", "как", "чем", "хотя", "пока", "ведь", "либо", "зато", "однако",
        "then", "and", "but", "because", "which", "that", "when", "if",
    )
    private val CONJUNCTIONS_EN = setOf(
        "and", "but", "or", "so", "because", "which", "that", "when", "if", "while", "although", "though", "where", "who",
        "unless", "until", "then",
    )
    private val CONJUNCTIONS_TR = setOf(
        "ve", "ama", "fakat", "ancak", "çünkü", "ki", "eğer", "veya", "ya", "yani", "oysa", "halbuki", "ayrıca",
        "dolayısıyla", "örneğin", "sonra", "yoksa", "hem", "ne",
    )
    private val SENTENCE_END = Regex("[.!?…]+[\"'»”’)\\]]*$")
    private val CLAUSE_END = Regex("[,;:]+[\"'»”’)\\]]*$")
    private val DASHES = setOf("—", "–", "-")

    fun breakPositions(text: String, maxWords: Int, lang: SpeechLang = SpeechLang.RU): List<Int> {
        val conjunctions = when (lang) {
            SpeechLang.RU -> CONJUNCTIONS
            SpeechLang.EN -> CONJUNCTIONS_EN
            SpeechLang.TR -> CONJUNCTIONS_TR
        }

        // "Mr.", "e.g.", "Dr." and "vb." end with a period, not a sentence. Russian keeps its old rule.
        fun endsSentence(w: String) = SENTENCE_END.containsMatchIn(w) && !ScriptLayout.isAbbreviation(w, lang)

        val starts = mutableListOf<Int>()
        val words = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            while (i < text.length && text[i] == ' ') i++
            if (i >= text.length) break
            val s = i
            while (i < text.length && text[i] != ' ') i++
            starts += s
            words += text.substring(s, i)
        }
        if (words.size <= 1) return emptyList()

        // breakAfter[k] == true -> line break after word k.
        val breakAfter = BooleanArray(words.size)
        var count = 0
        for (k in 0 until words.size - 1) {
            count++
            val w = words[k]
            val next = words[k + 1].lowercase().trim { !it.isLetterOrDigit() }
            val cut = when {
                endsSentence(w) -> true
                (CLAUSE_END.containsMatchIn(w) || w in DASHES) && count >= 3 -> true
                count >= maxWords -> true
                count >= maxWords - 2 && next in conjunctions -> true
                else -> false
            }
            if (cut) {
                breakAfter[k] = true
                count = 0
            }
        }
        // Do not leave a lonely word on its own line unless it is a whole sentence.
        var phraseStart = 0
        for (k in words.indices) {
            val endsPhrase = k == words.size - 1 || breakAfter[k]
            if (!endsPhrase) continue
            if (k == phraseStart && phraseStart > 0 && !endsSentence(words[phraseStart - 1])) {
                breakAfter[phraseStart - 1] = false
            }
            phraseStart = k + 1
        }
        val positions = mutableListOf<Int>()
        for (k in 0 until words.size - 1) {
            if (breakAfter[k]) positions += starts[k + 1] - 1
        }
        return positions.filter { it >= 0 && text[it] == ' ' }
    }
}
