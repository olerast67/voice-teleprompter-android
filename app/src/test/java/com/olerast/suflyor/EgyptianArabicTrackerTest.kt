package com.olerast.suflyor

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.script.WordNorm
import com.olerast.suflyor.track.ScriptTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Egyptian Arabic (العامية المصرية) and Modern Standard Arabic normalization, dialectal folding, and voice tracking. */
class EgyptianArabicTrackerTest {
    private fun doc(text: String) = ScriptDocument("t", "test", text.split("\n").map { Paragraph(it) })

    private fun model(text: String) = ScriptLayout.build(doc(text), phraseMode = true, lang = SpeechLang.AR)

    private fun track(text: String, heard: String): Pair<Int, Int> {
        val m = model(text)
        val tracker = ScriptTracker(m.tokens, m.lang)
        val history = mutableListOf<String>()
        for (w in SpeechLang.AR.words(heard)) {
            history += w
            tracker.onHypothesis(history.takeLast(SpeechLang.AR.history))
        }
        return tracker.position to m.spokenTokens
    }

    @Test
    fun detectsArabicScriptIncludingMixedBrandNames() {
        assertEquals(
            SpeechLang.AR,
            SpeechLang.detect(doc("ازيكم يا جماعة! النهارده هنتكلم عن ازاي تصور فيديو احترافي بالموبايل من غير ما تدفع فلوس كتير."))
        )
        assertEquals(
            SpeechLang.AR,
            SpeechLang.detect(doc("أنا جربت iPhone 16 Pro مع تطبيق Instagram و TikTok وكانت النتيجة ممتازة جداً."))
        )
    }

    @Test
    fun normalizesTashkeelTatweelOrthographyAndEasternDigits() {
        val ar = WordNorm.AR
        // Strips tashkeel and tatweel; normalizes Alef variants, Ta Marbuta -> ه, Alef Maqsura -> ي
        assertEquals("النهارده", ar.normalizeWord("النَّهَـارْدَة"))
        assertEquals("مصري", ar.normalizeWord("مِصْرِيّ"))
        assertEquals("مصري", ar.normalizeWord("مصرى"))
        assertEquals("احنا", ar.normalizeWord("إحنا"))
        assertEquals("احنا", ar.normalizeWord("أحنا"))
        assertEquals("مسووليه", ar.normalizeWord("مسؤولية"))
        assertEquals("اسيله", ar.normalizeWord("أسئلة"))
        assertEquals("فيديو", ar.normalizeWord("ڤيديو"))
        // Eastern Arabic digits ٠-٩ convert to 0-9
        assertEquals("2026", ar.normalizeWord("٢٠٢٦"))
        assertEquals(0f, ar.weight("2026", false))
    }

    @Test
    fun matchesEgyptianPrefixesNegationAndPhoneticSwaps() {
        val ar = WordNorm.AR
        // Single and multi-character clitic prefixes (و, ال, وال, بي, بت, هـ)
        assertTrue(ar.similarity(ar.normalizeWord("والكاميرا"), ar.normalizeWord("كاميرا")) >= 0.85f)
        assertTrue(ar.similarity(ar.normalizeWord("بيتكلم"), ar.normalizeWord("يتكلم")) >= 0.85f)
        assertTrue(ar.similarity(ar.normalizeWord("بتصور"), ar.normalizeWord("تصور")) >= 0.85f)
        assertTrue(ar.similarity(ar.normalizeWord("هنجرب"), ar.normalizeWord("نجرب")) >= 0.85f)
        // Egyptian circumfix negation (م...ش) vs affirmative or split form
        assertTrue(ar.similarity(ar.normalizeWord("مقولتش"), ar.normalizeWord("قولت")) >= 0.8f)
        // Phonetic folding between MSA ASR and Egyptian spelling (ث/س, ذ/ز, ظ/ض, ق/ا)
        assertTrue(ar.similarity(ar.normalizeWord("ثلاثة"), ar.normalizeWord("سلاسة")) >= 0.9f)
        assertTrue(ar.similarity(ar.normalizeWord("أستاذ"), ar.normalizeWord("استاز")) >= 0.9f)
        assertTrue(ar.similarity(ar.normalizeWord("مظبوط"), ar.normalizeWord("مضبوط")) >= 0.9f)
        // Foreign Latin words get weight 0
        assertTrue(ar.isForeign("TikTok"))
        assertFalse(ar.isForeign("تيكتوك"))
        assertEquals(0f, ar.weight("tiktok", true))
    }

    @Test
    fun preparesHypothesisJoiningNegationCompoundsAndDroppingSpokenNumbers() {
        val ar = WordNorm.AR
        val vocab = setOf("مقولتش", "علشان", "الكاميرا", "سنه", "تلاته")
        // "ما قولتش" joins into "مقولتش"; "عل شان" joins into "علشان"
        assertEquals(
            listOf("مقولتش", "علشان", "الكاميرا"),
            ar.prepareHypothesis(listOf("ما", "قولتش", "عل", "شان", "الكاميرا"), vocab, scriptHasDigits = false)
        )
        // Spoken numbers ("عشرين", "الف") are dropped when scriptHasDigits = true unless they are in vocab
        assertEquals(
            listOf("سنه", "تلاته"),
            ar.prepareHypothesis(listOf("سنه", "عشرين", "الف", "تلاته"), vocab, scriptHasDigits = true)
        )
    }

    @Test
    fun tracksColloquialEgyptianScriptWithAsrVariations() {
        val script = """
            أهلاً بيكم يا جماعة في فيديو جديد! النهارده هنتكلم عن إزاي تختار أول ميكروفون لتصوير الريلز من غير ما تدفع فلوس كتير.
            [اشرح الفرق بين الموديلات] أهم حاجة تركز عليها هي عزل الصوت والبطارية لما تصور في الشارع.
            لو بتصور بموبايل iPhone أو أندرويد في سنة ٢٠٢٦، الموديل الأول سعره ١٥٠٠ جنيه ومقولتش إنه وحش، بالعكس صوته نقي جداً.
        """.trimIndent()
        // Recognizer output: MSA/dialect mix, spelled-out numbers, hesitations ("يعني", "امم"), split negation ("ما قولتش")
        val heard = "اهلا بيكم يا جماعه في فيديو جديد النهارده هنتكلم عن ازاي تختار اول ميكروفون لتصوير الريلز من غير ما تدفع فلوس كتير " +
            "امم يعني اهم حاجه تركز عليها هي عزل الصوت والبطاريه لما تصور في الشارع " +
            "لو بتصور بموبايل او اندرويد في سنه الفين وسته وعشرين الموديل الاول سعره الف وخمسميت جنيه وما قولتش انه وحش بالعكس صوته نقي جدا"
        val (pos, total) = track(script, heard)
        assertTrue("position $pos of $total", pos >= total - 1)
    }
}
