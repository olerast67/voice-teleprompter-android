package com.olerast.suflyor.script

import java.text.Normalizer
import java.util.Locale

/**
 * Word normalization and fuzzy comparison for the Latin-script languages that come as speech packs (Portuguese,
 * Spanish, German, French, Italian). Their recognizers (Kroko community models) print cased text with punctuation
 * ("Foi-se, cortou o pescoço."), sometimes split a long word ("cadá ver"); scripts have accents, apostrophes
 * ("l'homme", "dell'anno") and digits. One class, one instance per language: only the word lists differ.
 */
class LatinNorm private constructor(
    val code: String,
    val locale: Locale,
    stop: String,
    fillers: String,
    softFillers: String,
    numberWords: String,
    abbreviations: String,
    conjunctions: String,
) {
    private fun set(words: String) = words.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()

    /** Function words: they prove little about the position (weight 0.2) and may not stand for a longer word's start. */
    val stop: Set<String> = set(stop)

    /** Hesitations the recognizer prints that never appear in a script. */
    val fillers: Set<String> = set(fillers)

    /** Fillers that are also real words: dropped from what was heard only when the script lacks them. */
    val softFillers: Set<String> = set(softFillers)

    /** How the recognizer spells out numbers. Dropped from what was heard when the script writes numbers in digits. */
    val numberWords: Set<String> = set(numberWords)

    /** Lowercase, without the final period: "Sr." or "z.B." does not end a sentence. */
    private val abbreviations: Set<String> = set(abbreviations)

    /** Words a phrase may start with when a long line is broken (see PhraseBreaker). */
    val conjunctions: Set<String> = set(conjunctions)

    /**
     * Lowercase, without punctuation, symbols or combining marks except the accents of the language, so that a script
     * word and the recognizer's word compare char by char: "Coração," -> "coração", "Straße" -> "straße".
     */
    fun normalizeWord(raw: String): String {
        val lower = Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(locale)
        val sb = StringBuilder(lower.length)
        for (ch in lower) if (ch.isLetterOrDigit()) sb.append(ch)
        return sb.toString()
    }

    /** Normalized words of a text: apostrophes and hyphens split ("l'homme" -> l, homme; "Foi-se" -> foi, se). */
    fun words(text: String): List<String> =
        WORD.findAll(text).map { normalizeWord(it.value) }.filter { it.isNotEmpty() }.toList()

    /** How much a matched word proves the position: function words prove little, long words a lot, numbers nothing. */
    fun weight(norm: String, foreign: Boolean): Float = when {
        // Cyrillic in a Latin script: the model can't say it, so it is skipped like a number.
        foreign -> 0f
        norm.isEmpty() || norm[0].isDigit() -> 0f
        norm in stop -> 0.2f
        norm.length <= 2 -> 0.3f
        norm.length == 3 -> 0.6f
        else -> 1f
    }

    /** Groups word forms for the frequency weights: the first five letters without accents ("cantamos", "cantava"). */
    fun stem(word: String): String {
        val w = fold(word)
        return if (w.length > 5) w.substring(0, 5) else w
    }

    /** Without accents, ß as ss: scripts are sometimes typed without them, and the recognizer may drop or add them. */
    fun fold(word: String): String {
        val decomposed = Normalizer.normalize(word, Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (ch == 'ß') sb.append("ss") else sb.append(ch)
        }
        return sb.toString()
    }

    /**
     * Word forms share a stem and differ in their endings ("falamos"/"falaram", "gehen"/"gehst"), so a long common
     * start counts as a match. Words that differ only in accents match almost fully.
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
        // Verb endings of the Romance languages differ in two or three letters ("falamos"/"falaram").
        if (minLen >= 6 && p >= 4) return 0.82f
        return 1f - TextNorm.levenshtein(fa, fb).toFloat() / maxLen
    }

    /**
     * Makes recognized words comparable with the script:
     * - two or three heard words are joined when the joined form is a script word of five letters or more
     *   ("cadá ver" for "cadáver"; German compounds heard apart, "haus tür" for "haustür");
     * - spoken numbers are dropped when the script writes numbers in digits and does not use that word itself.
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
            if (scriptHasDigits && (h.firstOrNull()?.isDigit() == true || (h in numberWords && h !in vocab))) continue
            out += h
        }
        return out
    }

    /** "Sr.", "Dra.", "z.B.", "M." (an initial): the period does not end a sentence. */
    fun isAbbreviation(word: String): Boolean {
        val core = word.trimStart { !it.isLetterOrDigit() }.trimEnd { !it.isLetterOrDigit() }.lowercase(locale)
        if (core.length == 1 && core[0].isLetter() && core !in stop) return true
        return core in abbreviations || core.replace(".", "") in abbreviations
    }

    /** How many of [words] (normalized) are this language's function words: picks the language of a Latin script. */
    fun stopHits(words: List<String>): Int = words.count { it in stop }

    companion object {
        /** A word is letters, digits and combining marks; apostrophes and hyphens split. */
        val WORD = Regex("[\\p{L}\\p{N}\\p{M}]+")

        val PT = LatinNorm(
            code = "pt",
            locale = Locale.forLanguageTag("pt-BR"),
            stop = """
                o a os as um uma uns umas de do da dos das em no na nos nas por pelo pela pelos pelas para com sem
                e ou mas que se não nem como mais muito já também só ao à aos às é são foi ser ter tem há está
                eu tu ele ela nós vós eles elas você vocês me te lhe nos vos lhes meu minha seu sua isso isto aquilo
                esse essa este esta aquele aquela quando onde porque então
            """,
            fillers = "ah eh hum hmm humm é ãh uhm ahn",
            softFillers = "tipo né então bom assim",
            numberWords = """
                zero um uma dois duas três quatro cinco seis sete oito nove dez onze doze treze catorze quatorze quinze
                dezesseis dezessete dezoito dezenove vinte trinta quarenta cinquenta sessenta setenta oitenta noventa cem
                cento duzentos trezentos quatrocentos quinhentos seiscentos setecentos oitocentos novecentos mil milhão
                milhões bilhão vírgula por cento
            """,
            abbreviations = "sr sra srta dr dra prof profa av r etc pág ex obs nº n",
            conjunctions = "e mas ou porque que quando se então porém embora pois como enquanto",
        )

        val ES = LatinNorm(
            code = "es",
            locale = Locale.forLanguageTag("es"),
            stop = """
                el la los las un una unos unas de del al en por para con sin y o u pero que se no ni como más muy
                ya también solo es son fue ser estar está hay ha he yo tú él ella nosotros vosotros ellos ellas usted
                ustedes me te le lo nos os les mi tu su mis tus sus esto eso aquello este esta ese esa cuando donde
                porque entonces
            """,
            fillers = "eh ah em mm mmm hmm este pues",
            softFillers = "bueno pues o sea vale entonces",
            numberWords = """
                cero uno una dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce quince dieciséis
                diecisiete dieciocho diecinueve veinte treinta cuarenta cincuenta sesenta setenta ochenta noventa cien
                ciento doscientos trescientos cuatrocientos quinientos seiscientos setecientos ochocientos novecientos
                mil millón millones coma por ciento
            """,
            abbreviations = "sr sra srta dr dra prof ud uds etc pág p ej núm av",
            conjunctions = "y pero o porque que cuando si entonces aunque pues como mientras",
        )

        val DE = LatinNorm(
            code = "de",
            locale = Locale.GERMAN,
            stop = """
                der die das den dem des ein eine einen einem einer eines und oder aber doch dass wenn als wie so
                nicht kein keine ist sind war waren sein hat haben wird werden ich du er sie es wir ihr mich dich sich
                mir dir ihm ihn uns euch mein dein sein ihr unser in im an am auf aus bei mit nach von vom zu zum zur
                für über unter vor um auch nur noch schon sehr ja da dann
            """,
            fillers = "äh ähm öh hm hmm mm ehm",
            softFillers = "also halt ja eben quasi sozusagen",
            numberWords = """
                null eins ein eine zwei drei vier fünf sechs sieben acht neun zehn elf zwölf dreizehn vierzehn fünfzehn
                sechzehn siebzehn achtzehn neunzehn zwanzig dreißig vierzig fünfzig sechzig siebzig achtzig neunzig
                hundert tausend million millionen milliarde komma prozent
            """,
            abbreviations = "dr prof hr fr nr str bzw ca usw zb z.b dh d.h vgl ggf evtl inkl s",
            conjunctions = "und aber oder denn weil dass wenn als ob obwohl dann sondern während",
        )

        val FR = LatinNorm(
            code = "fr",
            locale = Locale.FRENCH,
            stop = """
                le la les l un une des de du d au aux à en dans par pour avec sans et ou mais que qu qui ne n pas
                plus se s ce c cet cette ces est sont était être avoir a ont il elle ils elles je j tu nous vous on
                me m te t lui leur leurs mon ma mes ton ta tes son sa ses y ça cela comme quand où si donc alors
                aussi très
            """,
            fillers = "euh heu hum hmm bah ben mm",
            softFillers = "bon alors voilà quoi enfin",
            numberWords = """
                zéro un une deux trois quatre cinq six sept huit neuf dix onze douze treize quatorze quinze seize vingt
                trente quarante cinquante soixante cent cents mille million millions milliard virgule pour
            """,
            abbreviations = "m mme mlle dr pr st ste etc cf p ex av bd",
            conjunctions = "et mais ou donc car que quand si alors parce puis comme lorsque tandis",
        )

        val NL = LatinNorm(
            code = "nl",
            locale = Locale.forLanguageTag("nl"),
            stop = """
                de het een en of maar dat die dit deze van in op aan met voor door bij naar uit om over als dan
                niet geen is zijn was waren wordt worden heeft hebben had ik je jij u hij zij ze wij we jullie mij me
                hem haar ons hun mijn zijn er hier daar ook nog al wel zo
            """,
            fillers = "eh ehm uh uhm hm hmm mm",
            softFillers = "nou dus zeg ja",
            numberWords = """
                nul een twee drie vier vijf zes zeven acht negen tien elf twaalf dertien veertien vijftien zestien
                zeventien achttien negentien twintig dertig veertig vijftig zestig zeventig tachtig negentig honderd
                duizend miljoen miljard komma procent
            """,
            abbreviations = "dhr mevr dr prof drs ir mr bijv bv enz nr blz ca d.w.z dwz o.a oa",
            conjunctions = "en maar of want omdat dat als toen terwijl dus hoewel zodat",
        )

        val SV = LatinNorm(
            code = "sv",
            locale = Locale.forLanguageTag("sv"),
            stop = """
                och eller men att som det den de en ett i på av för med till från om är var har hade blir blev
                inte jag du han hon vi ni dem mig dig honom henne oss er sig min din sin vår här där så också
                bara när då nu sedan
            """,
            fillers = "eh öh hm hmm mm äh",
            softFillers = "alltså liksom typ ju",
            numberWords = """
                noll en ett två tre fyra fem sex sju åtta nio tio elva tolv tretton fjorton femton sexton sjutton
                arton nitton tjugo trettio fyrtio femtio sextio sjuttio åttio nittio hundra tusen miljon miljoner
                miljard komma procent
            """,
            abbreviations = "dr prof bl.a bla t.ex tex osv m.m mm ca nr s",
            conjunctions = "och men eller för att som när medan eftersom fast så därför",
        )

        val IT = LatinNorm(
            code = "it",
            locale = Locale.ITALIAN,
            stop = """
                il lo la i gli le l un uno una un di del dello della dei degli delle d a al allo alla ai agli alle
                da dal dalla in nel nella con su sul sulla per tra fra e o ma che chi non né come più molto già
                anche solo è sono era essere avere ha hanno c ci si io tu lui lei noi voi loro mi ti gli ne mio mia
                suo sua questo questa quello quella quando dove perché allora
            """,
            fillers = "eh ehm uhm mm mmm hmm ah",
            softFillers = "cioè allora insomma tipo ecco",
            numberWords = """
                zero uno una due tre quattro cinque sei sette otto nove dieci undici dodici tredici quattordici quindici
                sedici diciassette diciotto diciannove venti trenta quaranta cinquanta sessanta settanta ottanta novanta
                cento mille mila milione milioni miliardo virgola per cento
            """,
            abbreviations = "sig sigra dott dottssa prof ing avv ecc pag es n",
            conjunctions = "e ma o perché che quando se allora però mentre come poiché quindi",
        )
    }
}
