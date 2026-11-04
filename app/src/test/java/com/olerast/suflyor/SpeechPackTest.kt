package com.olerast.suflyor

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.LatinNorm
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.speech.SpeechPack
import com.olerast.suflyor.speech.SpeechPacks
import com.olerast.suflyor.speech.SpeechPacks.InstallResult
import com.olerast.suflyor.track.ScriptTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Speech-pack languages: auto-detect, word rules, the tracker on recognizer-like text, and installing a pack file. */
class SpeechPackTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun detect(text: String) = SpeechLang.detect(ScriptDocument("t", "test", text.split("\n").map { Paragraph(it) }))

    @Test
    fun detectsLatinLanguagesByFunctionWords() {
        // Sentences from Multilingual LibriSpeech, the test set the models were measured on.
        assertEquals(SpeechLang.PT, detect("A cabeça ficou segura na carne da vítima e das artérias rotas jorrava o sangue. Não havia mais cães a matar."))
        assertEquals(SpeechLang.ES, detect("Cuando calla el dolor se oye a la muerte, y las almas buscando algún alivio se revuelven ansiosas."))
        assertEquals(SpeechLang.DE, detect("Denken Sie, soeben weilten meine Gedanken bei Ihnen in Adelaide, und ich wünschte mir, Sie herzaubern zu können."))
        assertEquals(SpeechLang.FR, detect("Non, ta mort est certaine, dit le génie. Choisis seulement de quelle sorte tu veux que je te fasse mourir."))
        assertEquals(SpeechLang.IT, detect("Là onde il re ordinò che tutti i medici della città venissero alla sua presenza e liberamente dicessero."))
        assertEquals(SpeechLang.NL, detect("Arm kleinhartig, zei de eik, dat was toch een al te kort leven, en op iedere zomerdag werd dezelfde dans gedanst."))
        assertEquals(SpeechLang.SV, detect("Efter att ha sett krigets skräck och grymheter under första världskriget ville länder undvika att en sådan situation uppstod igen."))
    }

    @Test
    fun englishAndOthersStayAsBefore() {
        assertEquals(SpeechLang.EN, detect("This is a simple English teleprompter script for testing."))
        assertEquals(SpeechLang.EN, detect("A café in Paris where Gödel used to sit."))
        // English with a few Spanish and French names and words.
        assertEquals(SpeechLang.EN, detect("We met Señor García de la Vega at the café, and the chef de cuisine said it was the best of the day."))
        assertEquals(SpeechLang.TR, detect("Merhaba arkadaşlar, bugün sesli takip özelliğini deniyoruz."))
        assertEquals(SpeechLang.RU, detect("Привет всем зрителям этого канала."))
        // Too short to tell: English, as before speech packs.
        assertEquals(SpeechLang.EN, detect("Olá!"))
    }

    @Test
    fun normalizesAndComparesWords() {
        val pt = LatinNorm.PT
        assertEquals(listOf("foi", "se", "cortou", "o", "pescoço"), pt.words("Foi-se, cortou o pescoço."))
        assertEquals(listOf("l", "homme", "est", "là"), LatinNorm.FR.words("L'homme est là."))
        assertEquals("strasse", LatinNorm.DE.fold("straße"))
        // A script typed without accents still matches what the recognizer prints.
        assertTrue(pt.similarity("coracao", "coração") >= 0.95f)
        // Word forms with a long common start count as close.
        assertTrue(pt.similarity("falamos", "falaram") >= 0.8f)
        assertTrue(pt.similarity("casa", "mesa") < 0.6f)
        // A long word split in two by the recognizer is joined back.
        assertEquals(listOf("um", "cadáver", "fantástico"), pt.prepareHypothesis(listOf("um", "cadá", "ver", "fantástico"), setOf("um", "cadáver", "fantástico"), false))
        // Spoken numbers drop out when the script writes digits.
        assertEquals(listOf("em", "fomos"), pt.prepareHypothesis(listOf("em", "dois", "mil", "fomos"), setOf("em", "fomos"), true))
        assertTrue(pt.isAbbreviation("Sr."))
        assertFalse(pt.isAbbreviation("casa."))
    }

    @Test
    fun breaksLinesAtLanguageConjunctions() {
        val model = ScriptLayout.build(
            ScriptDocument("t", "test", listOf(Paragraph("O Sr. Silva chegou cedo à estação de trem e esperou pela filha que vinha da capital."))),
            phraseMode = true,
            maxWords = 7,
            lang = SpeechLang.PT,
        )
        // "Sr." doesn't end a sentence, so the text is one paragraph split into phrases, not at "Sr.".
        assertTrue(model.displayText.lines().none { it.trim() == "O Sr." })
        assertTrue(model.displayText.lines().size >= 2)
    }

    @Test
    fun tracksPortugueseWithRecognizerNoise() {
        val lines = listOf(
            "Foi-se, cortou o pescoço do animal.",
            "A cabeça ficou segura na carne da vítima, e das artérias rotas jorrava o sangue.",
            "Não havia mais cães a matar; o terreiro ficara atravancado de corpos decepados.",
            "Os homens, maltratados e doloridos, deitavam-se na erva com fastio e desânimo.",
        )
        val model = ScriptLayout.build(ScriptDocument("t", "test", lines.map { Paragraph(it) }), phraseMode = true, lang = SpeechLang.PT)
        val tracker = ScriptTracker(model.tokens, model.lang)
        val rnd = Random(7)
        val heard = mutableListOf<String>()
        // What Kroko printed for this text, plus random fillers and a split word.
        val spoken = SpeechLang.PT.words(
            "foi se cortou o pescoço do animal a cabeça ficou segura na carne da vítima e das artérias rotas jorrava o sangue " +
                "não havia mais cães a matar o terreiro ficara astra do de corpos decepados " +
                "os homens maltratados e doloridos deitavam se na erva com hostil e de sânimo",
        )
        for (w in spoken) {
            heard += w
            if (rnd.nextInt(10) == 0) heard += "hum"
            tracker.onHypothesis(heard.takeLast(16))
        }
        val total = model.spokenTokens
        assertTrue("position ${tracker.position} of $total", tracker.position >= total - 2)
    }

    // ---- installing a pack file ------------------------------------------------------------------------------------

    private fun zip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, text) ->
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun packFor(bytes: ByteArray, lang: SpeechLang = SpeechLang.PT) = SpeechPack(
        lang, 1, "suflyor-speech-${lang.code}-1.zip", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "test", "CC BY-SA", "speech-packs-test",
    )

    @Test
    fun catalogHasOnePackPerLanguageAndBuiltInsStayBuiltIn() {
        val langs = SpeechPacks.catalog.map { it.lang }
        assertEquals(langs.size, langs.toSet().size)
        // Every language the app knows can be installed as a pack (the lite APK needs that).
        assertEquals(SpeechLang.entries.toSet(), langs.toSet())
        assertTrue(SpeechPacks.catalog.all { it.sha256.length == 64 && it.releaseTag.startsWith("speech-packs-") })
        // Unit tests run the full build: Russian, English and Turkish are inside, the rest need a pack.
        assertFalse(SpeechPacks.needsPack(SpeechLang.RU))
        assertTrue(SpeechPacks.needsPack(SpeechLang.PT))
        assertEquals(7, SpeechPacks.offered.size)
        assertTrue(SpeechPacks.downloadUrl(SpeechPacks.forLang(SpeechLang.RU)!!).endsWith("/speech-packs-2/suflyor-speech-ru-1.zip"))
    }

    private val goodFiles = mapOf(
        "manifest.json" to """{"format":1,"lang":"pt","version":1,"model":{"type":"zipformer2","label":"test"}}""",
        "encoder.onnx" to "e", "decoder.onnx" to "d", "joiner.onnx" to "j", "tokens.txt" to "t 0",
    )

    @Test
    fun installsAKnownPackAndReplacesTheOldOne() {
        val root = tmp.newFolder("speech")
        val bytes = zip(goodFiles)
        val result = SpeechPacks.installInto(root, bytes.inputStream(), listOf(packFor(bytes)))
        assertTrue(result.toString(), result is InstallResult.Installed)
        assertEquals("e", File(root, "pt/encoder.onnx").readText())
        // Installing again over an existing pack keeps exactly one folder and no temporary files.
        SpeechPacks.installInto(root, bytes.inputStream(), listOf(packFor(bytes)))
        assertEquals(setOf("pt"), root.list()!!.toSet())
    }

    @Test
    fun refusesFilesNotInTheCatalog() {
        val root = tmp.newFolder("speech")
        val bytes = zip(goodFiles)
        val other = zip(goodFiles + ("tokens.txt" to "changed"))
        val result = SpeechPacks.installInto(root, other.inputStream(), listOf(packFor(bytes)))
        assertEquals(InstallResult.UnknownFile, result)
        assertFalse(File(root, "pt").exists())
    }

    @Test
    fun refusesPathsOutsideTheFolderAndWrongLanguage() {
        val root = tmp.newFolder("speech")
        val slip = zip(goodFiles + ("../evil.onnx" to "x"))
        assertTrue(SpeechPacks.installInto(root, slip.inputStream(), listOf(packFor(slip))) is InstallResult.Failed)
        assertFalse(File(root.parentFile, "evil.onnx").exists())
        // A Spanish manifest in a file the catalog lists as Portuguese.
        val wrong = zip(goodFiles + ("manifest.json" to """{"format":1,"lang":"es","version":1}"""))
        assertTrue(SpeechPacks.installInto(root, wrong.inputStream(), listOf(packFor(wrong))) is InstallResult.Failed)
        assertFalse(File(root, "pt").exists())
    }
}
