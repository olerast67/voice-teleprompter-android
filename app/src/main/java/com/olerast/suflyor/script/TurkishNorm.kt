package com.olerast.suflyor.script

import java.text.Normalizer
import java.util.Locale

/**
 * Word normalization and fuzzy comparison for Turkish speech. The Turkish recognizer (seda-v0.2-tiny) prints lowercase
 * spoken-form Turkish without punctuation or apostrophes ("istanbulda", "yirmi beş"); scripts have any case,
 * apostrophes before suffixes ("İstanbul'da") and digits. [SpeechLang] picks this, [TextNorm] or [EnglishNorm].
 */
object TurkishNorm {
    /** Case rules of Turkish: "İ" lowers to "i" and "I" to "ı", unlike the root locale ("I" -> "i"). */
    @Suppress("DEPRECATION")
    val LOCALE: Locale = Locale("tr", "TR")

    /**
     * One word: letters, digits and combining marks. An inner apostrophe ("İstanbul'da", "Ali’nin") stays inside the
     * word and is dropped by [normalizeWord], since the recognizer prints the name and its suffix as one word.
     * Hyphens split ("e-posta", "Türk-İslam").
     */
    val WORD = Regex("[\\p{L}\\p{N}\\p{M}]+(?:['’ʼ‘`][\\p{L}\\p{N}\\p{M}]+)*")

    /** Function words: they prove little about the position (weight 0.2) and may not stand for a longer word's start. */
    val STOP: Set<String> = """
        ve veya ya ile ama fakat ancak çünkü ki de da mi mı mu mü ne bir bu şu o için gibi kadar daha çok en her hem
        ben sen biz siz onlar bana sana ona bize size onlara beni seni onu bizi sizi onları benim senin onun bizim sizin
        bunu şunu bunun şunun buna şuna bunlar şunlar olan olarak var yok değil diye ise eğer hiç sonra önce şey yani
        işte evet hayır tamam nasıl neden niye nerede hangi kim
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** Hesitations the recognizer prints that never appear in a script: always dropped from what was heard. */
    val FILLERS = setOf("ı", "ıı", "ııı", "ıhı", "ee", "eee", "eeee", "hı", "hıı", "hıh", "hım", "hmm", "hm", "mm", "mmm", "ımm", "eem", "aa", "ah", "eh")

    /**
     * Discourse fillers that people add while reading ("şey", "yani", "işte"), but that scripts also use as real
     * words: dropped from what was heard only when the script does not contain them (see ScriptTracker.onHypothesis).
     */
    val SOFT_FILLERS = setOf("şey", "yani", "işte", "evet", "hani", "falan", "filan", "ya", "tamam", "peki")

    /** How the recognizer spells out numbers. Dropped from what was heard when the script writes numbers in digits. */
    val NUMBER_WORDS: Set<String> = """
        sıfır bir iki üç dört beş altı yedi sekiz dokuz on yirmi otuz kırk elli altmış yetmiş seksen doksan yüz bin
        milyon milyar trilyon buçuk yüzde virgül nokta birinci ikinci üçüncü dördüncü beşinci altıncı yedinci sekizinci
        dokuzuncu onuncu
    """.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** Lowercase, without the final period: "Dr." and "vb." do not end a sentence. */
    private val ABBREVIATIONS = setOf(
        "dr", "prof", "doç", "av", "sn", "vb", "vs", "örn", "bkz", "yy", "no", "sf", "mah", "cad", "sok", "apt", "tel",
        "yrd", "uzm", "op", "müh", "öğr", "gör", "st", "bşk", "gn", "alb", "org", "kur",
    )

    private const val APOSTROPHES = "'’ʼ‘`"

    /** Circumflexed vowels of loanwords (kâr, hâlâ, millî): the recognizer prints them plain. */
    private val CIRCUMFLEX = mapOf('â' to 'a', 'î' to 'i', 'û' to 'u')

    /**
     * Lowercase with Turkish case rules, without apostrophes, punctuation, symbols or combining marks, so that a
     * script word and the recognizer's word compare char by char: "İSTANBUL'DA" -> "istanbulda", "IŞIK" -> "ışık",
     * "Hâlâ" -> "hala".
     */
    fun normalizeWord(raw: String): String {
        // NFC first: "ş" or "ğ" typed as letter + combining mark become one character, so they are not stripped below.
        val lower = Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(LOCALE)
        val sb = StringBuilder(lower.length)
        for (ch in lower) {
            val type = Character.getType(ch)
            // Combining marks left over, e.g. the dot of "i̇" from text lowercased elsewhere with the wrong rules.
            if (type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt()
            ) continue
            if (ch in APOSTROPHES) continue
            if (!ch.isLetterOrDigit()) continue
            sb.append(CIRCUMFLEX[ch] ?: ch)
        }
        return sb.toString()
    }

    fun words(text: String): List<String> =
        WORD.findAll(text).map { normalizeWord(it.value) }.filter { it.isNotEmpty() }.toList()

    /** How much a matched word proves the position: function words prove little, long words a lot, numbers nothing. */
    fun weight(norm: String, foreign: Boolean): Float = when {
        // Cyrillic in a Turkish script: the Turkish model can't say it, so it is skipped like a number.
        foreign -> 0f
        // 2025, 3'te -> "3te", 1. : said as number words.
        norm.isEmpty() || norm[0].isDigit() -> 0f
        norm in STOP -> 0.2f
        norm.length <= 2 -> 0.3f
        norm.length == 3 -> 0.6f
        else -> 1f
    }

    /**
     * Groups word forms for the frequency weights. Turkish stacks suffixes on a stem that rarely changes
     * ("kitap", "kitaplar", "kitaplarımız"), so the first five letters (ASCII-folded) do the job.
     */
    fun stem(word: String): String {
        val w = fold(word)
        return if (w.length > 5) w.substring(0, 5) else w
    }

    /**
     * Scripts are often typed without Turkish letters ("gunaydin", "Istanbul" -> "ıstanbul"), while the recognizer
     * prints them: compare in a folded form where ç/ğ/ı/ö/ş/ü are c/g/i/o/s/u.
     */
    fun fold(word: String): String {
        val sb = StringBuilder(word.length)
        for (ch in word) {
            sb.append(
                when (ch) {
                    'ç' -> 'c'
                    'ğ' -> 'g'
                    'ı' -> 'i'
                    'ö' -> 'o'
                    'ş' -> 's'
                    'ü' -> 'u'
                    else -> ch
                },
            )
        }
        return sb.toString()
    }

    /**
     * Turkish words share a stem and differ in their suffixes ("geliyorum"/"geliyoruz", "evde"/"evden"), so a long
     * common start counts as a match, as in Russian. Words that differ only in Turkish letters match almost fully.
     */
    fun similarity(a: String, b: String): Float {
        if (a == b) return 1f
        if (a.isEmpty() || b.isEmpty()) return 0f
        val fa = fold(a)
        val fb = fold(b)
        if (fa == fb) return 0.95f
        val minLen = minOf(fa.length, fb.length)
        val maxLen = maxOf(fa.length, fb.length)
        if (maxLen <= 2) return 0f
        var p = 0
        while (p < minLen && fa[p] == fb[p]) p++
        if (p >= 5) return 0.92f
        if (minLen >= 4 && p >= 4 && p >= minLen - 2) return 0.88f
        if (minLen >= 4 && p >= 3 && p >= minLen - 1) return 0.8f
        return 1f - TextNorm.levenshtein(fa, fb).toFloat() / maxLen
    }

    /**
     * Makes recognized words comparable with the script:
     * - two or three heard words are joined when the joined form is a script word of five letters or more
     *   ("her şey" for "herşey", "hiç bir" for "hiçbir");
     * - spoken numbers ("yirmi yirmi beş") are dropped when the script writes numbers in digits and does not use that
     *   word itself: the digits are transparent to the tracker, the words would be unmatched extra words.
     */
    fun prepareHypothesis(hyp: List<String>, vocab: Set<String>, scriptHasDigits: Boolean): List<String> {
        val out = ArrayList<String>(hyp.size)
        var i = 0
        outer@ while (i < hyp.size) {
            if (hyp[i] !in vocab) {
                for (k in minOf(3, hyp.size - i) downTo 2) {
                    val joined = hyp.subList(i, i + k).joinToString("")
                    if (joined.length >= 5 && joined in vocab) {
                        out += joined
                        i += k
                        continue@outer
                    }
                }
            }
            val h = hyp[i++]
            if (scriptHasDigits && (h.firstOrNull()?.isDigit() == true || (h in NUMBER_WORDS && h !in vocab))) continue
            out += h
        }
        return out
    }

    /** "Dr.", "vb.", "Prof." or an initial ("M."): the period does not end a sentence. */
    fun isAbbreviation(word: String): Boolean {
        val core = word.trimStart { !it.isLetterOrDigit() }.trimEnd { !it.isLetterOrDigit() }.lowercase(LOCALE)
        return core in ABBREVIATIONS || (core.length == 1 && core[0].isLetter() && core != "o")
    }

    /** Turkish letters that English and most other Latin-script languages don't use. */
    private const val STRONG_LETTERS = "ıİğĞşŞ"

    /** Turkish letters shared with French or German (ç, ö, ü): count only in quantity. */
    private const val SHARED_LETTERS = "çÇöÖüÜ"

    fun isStrongTurkishLetter(ch: Char): Boolean = ch in STRONG_LETTERS

    fun isTurkishLetter(ch: Char): Boolean = ch in STRONG_LETTERS || ch in SHARED_LETTERS
}
