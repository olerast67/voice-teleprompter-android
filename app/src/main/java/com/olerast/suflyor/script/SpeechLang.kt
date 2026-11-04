package com.olerast.suflyor.script

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument

/**
 * Language the reader speaks: picks the recognizer model and the rules for comparing heard and written words.
 * Russian calls [TextNorm] exactly as before English existed; English uses [EnglishNorm], Turkish [TurkishNorm].
 * @param label the language's name in that language.
 */
enum class SpeechLang(val code: String, val label: String) {
    RU("ru", "Русский"),
    EN("en", "English"),
    TR("tr", "Türkçe");

    /** One word of script or recognizer text. English and Turkish keep inner apostrophes ("don't", "İstanbul'da"); Russian splits on them. */
    val wordRegex: Regex
        get() = when (this) {
            RU -> TextNorm.WORD
            EN -> EnglishNorm.WORD
            TR -> TurkishNorm.WORD
        }

    /** Hesitations the recognizer prints that never appear in a script. */
    val fillers: Set<String>
        get() = when (this) {
            RU -> TextNorm.FILLERS
            EN -> EnglishNorm.FILLERS
            TR -> TurkishNorm.FILLERS
        }

    /** Fillers that are also real words ("yani", "şey"): dropped from what was heard only if the script lacks them. */
    val softFillers: Set<String>
        get() = when (this) {
            RU, EN -> emptySet()
            TR -> TurkishNorm.SOFT_FILLERS
        }

    fun normalize(raw: String): String = when (this) {
        RU -> TextNorm.normalizeWord(raw)
        EN -> EnglishNorm.normalizeWord(raw)
        TR -> TurkishNorm.normalizeWord(raw)
    }

    /** Normalized words of a text in this language: the recognizer's output on every update, or script text. */
    fun words(text: String): List<String> = when (this) {
        RU -> TextNorm.words(text)
        EN -> EnglishNorm.WORD.findAll(text).map { EnglishNorm.normalizeWord(it.value) }.filter { it.isNotEmpty() }.toList()
        TR -> TurkishNorm.words(text)
    }

    /**
     * Written in the other alphabet: Latin in a Russian script (transliterated, weight 0.5), Cyrillic in an English
     * or Turkish one (weight 0: the model can't say it, so the tracker steps over it like over a number).
     */
    fun isForeign(raw: String): Boolean = when (this) {
        RU -> raw.any { it in 'a'..'z' || it in 'A'..'Z' }
        EN, TR -> raw.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }
    }

    /** How much a matched script word proves the position (before the tracker's frequency adjustment). */
    fun weight(norm: String, foreign: Boolean): Float = when (this) {
        RU -> TextNorm.weight(norm, foreign)
        EN -> EnglishNorm.weight(norm, foreign)
        TR -> TurkishNorm.weight(norm, foreign)
    }

    fun similarity(a: String, b: String): Float = when (this) {
        RU -> TextNorm.similarity(a, b)
        EN -> EnglishNorm.similarity(a, b)
        TR -> TurkishNorm.similarity(a, b)
    }

    /** Groups word forms for the frequency weights: a word repeated in another form still proves little. */
    fun stem(w: String): String = when (this) {
        // Word forms of one Russian word usually share the first five letters.
        RU -> if (w.length > 5) w.substring(0, 5) else w
        EN -> EnglishNorm.stem(w)
        TR -> TurkishNorm.stem(w)
    }

    /**
     * The recognizer's unfinished last word may match the start of a longer script word. A complete English or
     * Turkish function word may not: "the" is not the start of "then", "bu" not of "bugün".
     */
    fun canBePrefix(partial: String): Boolean = when (this) {
        RU -> true
        EN -> partial !in EnglishNorm.STOP
        TR -> partial !in TurkishNorm.STOP
    }

    /**
     * Recognized words (fillers already removed) made comparable with the script words in [vocab].
     * @param chains glued runs of the script's hyphen chains of letter names with their parts (Russian only).
     */
    fun prepareHypothesis(
        hyp: List<String>,
        vocab: Set<String>,
        scriptHasDigits: Boolean,
        chains: Map<String, List<String>> = emptyMap(),
    ): List<String> = when (this) {
        RU -> TextNorm.regroupLetterNames(hyp, vocab, chains)
        EN -> EnglishNorm.prepareHypothesis(hyp, vocab, scriptHasDigits)
        TR -> TurkishNorm.prepareHypothesis(hyp, vocab, scriptHasDigits)
    }

    /**
     * Runs of skipped script words or extra heard words last until the next match (see ScriptTracker.align).
     * English and Turkish need it for spelled-out numbers ("in twenty twenty five we…", "yirmi yirmi beşte…").
     * Russian keeps the old rule, under which a step in the other direction restarts the run, so its behaviour stays
     * exactly as tuned.
     */
    val strictGapRuns: Boolean
        get() = this != RU

    companion object {
        fun fromCode(code: String?): SpeechLang? = entries.firstOrNull { it.code == code }

        /**
         * Majority alphabet of what the reader will say: body paragraphs outside [stage directions].
         * A Latin-majority script is Turkish if it has a letter only Turkish uses (ı İ ğ Ğ ş Ş), or if ç/ö/ü make up
         * at least 2% of its letters with at least three of them (Turkish prose has 5–8%; a stray "Gödel" or "café" in English stays English).
         * Ties and empty scripts count as Russian.
         */
        fun detect(doc: ScriptDocument): SpeechLang {
            var cyrillic = 0
            var latin = 0
            var turkishStrong = 0
            var turkishAll = 0
            for (p in doc.paragraphs) {
                if (p.kind != Paragraph.Kind.BODY) continue
                var note = false
                for (ch in p.text) {
                    when {
                        ch == '[' -> note = true
                        ch == ']' -> note = false
                        note || !ch.isLetter() -> Unit
                        else -> when (Character.UnicodeScript.of(ch.code)) {
                            Character.UnicodeScript.CYRILLIC -> cyrillic++
                            Character.UnicodeScript.LATIN -> {
                                latin++
                                if (TurkishNorm.isTurkishLetter(ch)) turkishAll++
                                if (TurkishNorm.isStrongTurkishLetter(ch)) turkishStrong++
                            }
                            else -> Unit
                        }
                    }
                }
            }
            if (latin <= cyrillic) return RU
            return if (turkishStrong > 0 || (turkishAll >= 3 && turkishAll * 50 >= latin)) TR else EN
        }
    }
}
