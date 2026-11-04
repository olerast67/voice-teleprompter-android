package com.olerast.suflyor.script

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument

/**
 * Language the reader speaks: picks the recognizer model and the rules for comparing heard and written words.
 * Russian calls [TextNorm] exactly as before English existed; English uses [EnglishNorm], Turkish [TurkishNorm],
 * the speech-pack languages their [PackRules]: word by word ([WordNorm]) or, for Chinese and Korean, character by
 * character ([CharNorm]).
 * @param label the language's name in that language.
 * @param rules the rules of a speech-pack language; null for Russian, English and Turkish, which have their own.
 */
enum class SpeechLang(val code: String, val label: String, val rules: PackRules? = null) {
    RU("ru", "Русский"),
    EN("en", "English"),
    TR("tr", "Türkçe"),
    PT("pt", "Português", WordNorm.PT),
    ES("es", "Español", WordNorm.ES),
    DE("de", "Deutsch", WordNorm.DE),
    FR("fr", "Français", WordNorm.FR),
    IT("it", "Italiano", WordNorm.IT),
    NL("nl", "Nederlands", WordNorm.NL),
    SV("sv", "Svenska", WordNorm.SV),
    HE("he", "עברית", WordNorm.HE),
    KO("ko", "한국어", CharNorm.KO),
    ZH("zh", "中文", CharNorm.ZH);

    /** The recognizer model comes as a speech pack the user installs (see SpeechPacks), not inside the APK. */
    val isPack: Boolean
        get() = rules != null

    /** One word of script or recognizer text. English and Turkish keep inner apostrophes ("don't", "İstanbul'da"); Russian splits on them. */
    val wordRegex: Regex
        get() = when (this) {
            RU -> TextNorm.WORD
            EN -> EnglishNorm.WORD
            TR -> TurkishNorm.WORD
            else -> rules!!.wordRegex
        }

    /** Hesitations the recognizer prints that never appear in a script. */
    val fillers: Set<String>
        get() = when (this) {
            RU -> TextNorm.FILLERS
            EN -> EnglishNorm.FILLERS
            TR -> TurkishNorm.FILLERS
            else -> rules!!.fillers
        }

    /** Fillers that are also real words ("yani", "şey"): dropped from what was heard only if the script lacks them. */
    val softFillers: Set<String>
        get() = when (this) {
            RU, EN -> emptySet()
            TR -> TurkishNorm.SOFT_FILLERS
            else -> rules!!.softFillers
        }

    fun normalize(raw: String): String = when (this) {
        RU -> TextNorm.normalizeWord(raw)
        EN -> EnglishNorm.normalizeWord(raw)
        TR -> TurkishNorm.normalizeWord(raw)
        else -> rules!!.normalizeWord(raw)
    }

    /** Normalized words of a text in this language: the recognizer's output on every update, or script text. */
    fun words(text: String): List<String> = when (this) {
        RU -> TextNorm.words(text)
        EN -> EnglishNorm.WORD.findAll(text).map { EnglishNorm.normalizeWord(it.value) }.filter { it.isNotEmpty() }.toList()
        TR -> TurkishNorm.words(text)
        else -> rules!!.words(text)
    }

    /**
     * Written in the other alphabet: Latin in a Russian script (transliterated, weight 0.5), Cyrillic in an English
     * or Turkish one (weight 0: the model can't say it, so the tracker steps over it like over a number).
     */
    fun isForeign(raw: String): Boolean = when (this) {
        RU -> raw.any { it in 'a'..'z' || it in 'A'..'Z' }
        EN, TR -> raw.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }
        else -> rules!!.isForeign(raw)
    }

    /** How much a matched script word proves the position (before the tracker's frequency adjustment). */
    fun weight(norm: String, foreign: Boolean): Float = when (this) {
        RU -> TextNorm.weight(norm, foreign)
        EN -> EnglishNorm.weight(norm, foreign)
        TR -> TurkishNorm.weight(norm, foreign)
        else -> rules!!.weight(norm, foreign)
    }

    fun similarity(a: String, b: String): Float = when (this) {
        RU -> TextNorm.similarity(a, b)
        EN -> EnglishNorm.similarity(a, b)
        TR -> TurkishNorm.similarity(a, b)
        else -> rules!!.similarity(a, b)
    }

    /** Groups word forms for the frequency weights: a word repeated in another form still proves little. */
    fun stem(w: String): String = when (this) {
        // Word forms of one Russian word usually share the first five letters.
        RU -> if (w.length > 5) w.substring(0, 5) else w
        EN -> EnglishNorm.stem(w)
        TR -> TurkishNorm.stem(w)
        else -> rules!!.stem(w)
    }

    /**
     * The recognizer's unfinished last word may match the start of a longer script word. A complete English or
     * Turkish function word may not: "the" is not the start of "then", "bu" not of "bugün".
     */
    fun canBePrefix(partial: String): Boolean = when (this) {
        RU -> true
        EN -> partial !in EnglishNorm.STOP
        TR -> partial !in TurkishNorm.STOP
        else -> rules!!.canBePrefix(partial)
    }

    /** How many recent units the tracker matches at once: 8 words, or 14 characters in Chinese and Korean. */
    val tail: Int
        get() = rules?.tail ?: 8

    /** Units (characters in Chinese and Korean) per spoken word, for word counts, reading time and timed speed. */
    val unitsPerWord: Float
        get() = rules?.unitsPerWord ?: 1f

    /** How many finished units of recognizer output the session keeps for the next match. */
    val history: Int
        get() = rules?.history ?: 16

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
        else -> rules!!.prepareHypothesis(hyp, vocab, scriptHasDigits)
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
         * Any other Latin script is English unless the function words of a speech-pack language outnumber the English
         * ones (see [latinByFunctionWords]). Ties and empty scripts count as Russian.
         */
        fun detect(doc: ScriptDocument): SpeechLang {
            var cyrillic = 0
            var latin = 0
            var turkishStrong = 0
            var turkishAll = 0
            var hangul = 0
            var hebrew = 0
            var han = 0
            val spoken = StringBuilder()
            for (p in doc.paragraphs) {
                if (p.kind != Paragraph.Kind.BODY) continue
                var note = false
                for (ch in p.text) {
                    if (!note && ch != '[') spoken.append(ch)
                    when {
                        ch == '[' -> note = true
                        ch == ']' -> { note = false; spoken.append(' ') }
                        note || !ch.isLetter() -> Unit
                        else -> when (Character.UnicodeScript.of(ch.code)) {
                            Character.UnicodeScript.CYRILLIC -> cyrillic++
                            Character.UnicodeScript.HANGUL -> hangul++
                            Character.UnicodeScript.HEBREW -> hebrew++
                            Character.UnicodeScript.HAN -> han++
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
            // Another alphabet in the majority picks its language; Han characters count double (one stands for a word part).
            val other = maxOf(hangul, hebrew, han * 2)
            if (other > maxOf(latin, cyrillic)) {
                return when (other) {
                    hangul -> KO
                    hebrew -> HE
                    else -> ZH
                }
            }
            if (latin <= cyrillic) return RU
            if (turkishStrong > 0 || (turkishAll >= 3 && turkishAll * 50 >= latin)) return TR
            return latinByFunctionWords(spoken)
        }

        /**
         * Counts the function words of English and of each speech-pack language ("the", "and"; "de", "que", "não";
         * "der", "und") in a Latin script. A pack language wins only with at least three hits and a fifth more than
         * English, so an English script with a few French names stays English.
         */
        internal fun latinByFunctionWords(text: CharSequence): SpeechLang {
            val words = WordNorm.WORD.findAll(text).take(4000).map { it.value.lowercase() }.toList()
            val english = words.count { it in EnglishNorm.STOP }
            var best = EN
            var bestHits = 0
            for (lang in entries) {
                val norm = (lang.rules as? WordNorm)?.takeIf { it.isLatin } ?: continue
                val hits = norm.stopHits(words.map { norm.normalizeWord(it) })
                if (hits > bestHits) {
                    best = lang
                    bestHits = hits
                }
            }
            return if (bestHits >= 3 && bestHits * 5 > english * 6) best else EN
        }
    }
}
