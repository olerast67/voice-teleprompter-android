package com.olerast.suflyor

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.ScriptModel
import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.script.TurkishNorm
import com.olerast.suflyor.script.biasWords
import com.olerast.suflyor.speech.hotwordList
import com.olerast.suflyor.track.ScriptTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurkishTrackerTest {
    private val tr = SpeechLang.TR

    private fun build(lines: List<String>, phraseMode: Boolean = true): ScriptModel =
        ScriptLayout.build(ScriptDocument("t", "test", lines.map { Paragraph(it) }), phraseMode = phraseMode, lang = tr)

    @Test
    fun detectsTurkishLanguage() {
        fun detect(vararg p: Paragraph) = SpeechLang.detect(ScriptDocument("t", "test", p.toList()))

        // Turkish with strong letters (ş, ı, ğ, İ)
        assertEquals(SpeechLang.TR, detect(Paragraph("Merhaba arkadaşlar, bugün sesli takip özelliğini deniyoruz.")))
        assertEquals(SpeechLang.TR, detect(Paragraph("İstanbul'da hava bugün çok güzel ve ılık.")))
        assertEquals(SpeechLang.TR, detect(Paragraph("Işıklar söndüğünde sahneye çıkacağız.")))

        // Turkish with shared letters (ç, ö, ü)
        assertEquals(SpeechLang.TR, detect(Paragraph("Çözüm üretmek için bütün gücümüzle çalışıyoruz.")))

        // English scripts should stay English
        assertEquals(SpeechLang.EN, detect(Paragraph("This is a simple English teleprompter script for testing.")))
        assertEquals(SpeechLang.EN, detect(Paragraph("A café in Paris where Gödel used to sit.")))

        // Russian scripts should stay Russian
        assertEquals(SpeechLang.RU, detect(Paragraph("Привет всем зрителям этого канала.")))

        // Code lookup
        assertEquals(SpeechLang.TR, SpeechLang.fromCode("tr"))
        assertEquals("tr", SpeechLang.TR.code)
        assertEquals("Türkçe", SpeechLang.TR.label)
    }

    @Test
    fun normalizesTurkishCaseAndApostrophes() {
        // Turkish specific case conversions
        assertEquals("istanbulda", TurkishNorm.normalizeWord("İSTANBUL'DA"))
        assertEquals("istanbulda", TurkishNorm.normalizeWord("İstanbul’da"))
        assertEquals("ışık", TurkishNorm.normalizeWord("IŞIK"))
        assertEquals("ılık", TurkishNorm.normalizeWord("ILIK"))
        assertEquals("hala", TurkishNorm.normalizeWord("Hâlâ"))
        assertEquals("kar", TurkishNorm.normalizeWord("Kâr"))

        // Punctuation and word splitting
        val words = tr.words("Merhaba! Bugün İstanbul'da hava 25 derece.")
        assertEquals(listOf("merhaba", "bugün", "istanbulda", "hava", "25", "derece"), words)
    }

    @Test
    fun handlesTurkishFillersAndStopWords() {
        assertTrue("ı" in tr.fillers)
        assertTrue("hmm" in tr.fillers)
        assertTrue("hı" in tr.fillers)
        assertTrue("ee" in tr.fillers)

        assertTrue("şey" in tr.softFillers)
        assertTrue("yani" in tr.softFillers)
        assertTrue("işte" in tr.softFillers)

        assertTrue("ve" in TurkishNorm.STOP)
        assertTrue("bir" in TurkishNorm.STOP)
        assertTrue("için" in TurkishNorm.STOP)
    }

    @Test
    fun generatesTurkishBiasWords() {
        val model = build(listOf("İstanbul'un tarihi sokaklarında yürürken Dr. Ahmet ile karşılaştık."))
        val bias = model.biasWords()

        // "İstanbul'un" normalized to "istanbulun"
        assertTrue(bias.contains("istanbulun"))
        assertTrue(bias.contains("tarihi"))
        assertTrue(bias.contains("sokaklarında"))

        val hotwords = hotwordList(SpeechLang.TR, bias)
        assertTrue(hotwords.contains("istanbulun"))
    }

    @Test
    fun tracksTurkishSpeech() {
        val script = listOf(
            "Teleprompter ile konuşma yapmak artık çok daha kolay.",
            "Kelimeleri sırayla takip ederek ekrandan rahatça okuyabilirsiniz.",
        )
        val model = build(script)
        val tracker = ScriptTracker(model.tokens, model.lang)

        val history = mutableListOf<String>()
        val positions = mutableListOf<Int>()

        // The tracker uses similarity with fold(), so even if recognizer heard "konusma" for "konuşma", it matches
        val heardWords = listOf("teleprompter", "ile", "konusma", "yapmak", "artik", "cok", "daha", "kolay")
        for (w in heardWords) {
            history += w
            tracker.onHypothesis(history.takeLast(16))
            positions += tracker.position
        }

        // Tracker should have advanced through the first sentence
        assertTrue(tracker.position > 0)
        assertEquals(8, tracker.position)
    }
}
