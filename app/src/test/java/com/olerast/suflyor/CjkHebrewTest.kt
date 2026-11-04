package com.olerast.suflyor

import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.CharNorm
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.script.WordNorm
import com.olerast.suflyor.track.ScriptTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chinese and Korean (followed character by character) and Hebrew (word by word, right to left). */
class CjkHebrewTest {
    private fun doc(text: String) = ScriptDocument("t", "test", text.split("\n").map { Paragraph(it) })

    private fun model(text: String, lang: SpeechLang) = ScriptLayout.build(doc(text), phraseMode = true, lang = lang)

    /** Feeds the heard units one by one, as the recognizer's growing hypothesis; returns the final position. */
    private fun track(text: String, lang: SpeechLang, heard: String): Pair<Int, Int> {
        val m = model(text, lang)
        val tracker = ScriptTracker(m.tokens, m.lang)
        val history = mutableListOf<String>()
        for (w in lang.words(heard)) {
            history += w
            tracker.onHypothesis(history.takeLast(lang.history))
        }
        return tracker.position to m.spokenTokens
    }

    @Test
    fun detectsByAlphabet() {
        assertEquals(SpeechLang.ZH, SpeechLang.detect(doc("大家好！今天我来告诉大家，怎样挑选第一架旅行拍摄用的无人机。")))
        assertEquals(SpeechLang.KO, SpeechLang.detect(doc("안녕하세요! 오늘은 여행 촬영용 첫 드론을 고르는 방법을 알려드리겠습니다.")))
        assertEquals(SpeechLang.HE, SpeechLang.detect(doc("שלום לכולם! היום אני אראה לכם איך לבחור את הרחפן הראשון.")))
        // A Chinese script with a few English product names stays Chinese; a Russian one with one Hangul word stays Russian.
        assertEquals(SpeechLang.ZH, SpeechLang.detect(doc("我买了 DJI Mini 4 Pro 无人机，它的重量不到二百五十克。")))
        assertEquals(SpeechLang.RU, SpeechLang.detect(doc("Сегодня расскажу про корейское слово 안녕 и как его произносить.")))
    }

    @Test
    fun chineseCharactersMatchBySound() {
        val zh = CharNorm.ZH
        assertEquals("zhong", CharNorm.pinyinOf('中'))
        assertEquals(listOf("我", "买", "了", "dji", "无", "人", "机"), zh.words("我买了DJI无人机"))
        // 再 and 在 are both "zai": a recognizer swap still matches.
        assertTrue(zh.similarity("再", "在") >= 0.8f)
        assertEquals(0f, zh.similarity("大", "家"))
        // Latin inside Chinese can't be said by the model.
        assertTrue(zh.isForeign("DJI"))
        assertEquals(0f, zh.weight("dji", true))
    }

    @Test
    fun koreanSyllablesMatchByLetters() {
        val ko = CharNorm.KO
        assertEquals(listOf("안", "녕", "하", "세", "요"), ko.words("안녕하세요!"))
        // 갔 / 감: the same initial consonant and vowel, another final consonant.
        assertTrue(ko.similarity("갔", "감") >= 0.72f)
        assertEquals(0f, ko.similarity("가", "나"))
    }

    @Test
    fun hebrewPrefixesAndFinalLetters() {
        val he = WordNorm.HE
        // With or without the prefix "ו" (and): the same word.
        assertTrue(he.similarity("והבית", "הבית") >= 0.85f)
        // Vowel points are dropped; final mem compares as mem.
        assertEquals("שלום", he.normalizeWord("שָׁלוֹם"))
        assertEquals(he.fold("עולם"), he.fold("עולמ"))
        assertTrue(he.isForeign("DJI"))
    }

    @Test
    fun tracksChineseWithHomophoneErrors() {
        val script = "大家好！今天我来告诉大家，怎样挑选第一架旅行拍摄用的无人机，而且不多花钱。首先要看的是重量。"
        // What a recognizer may print: 再 for 在-like swaps, 架 heard as 家, no punctuation.
        val heard = "大家好今天我来告诉大家怎样挑选第一家旅行拍摄用的无人机而且不多花钱首先要看的是重量"
        val (pos, total) = track(script, SpeechLang.ZH, heard)
        assertTrue("position $pos of $total", pos >= total - 1)
    }

    @Test
    fun tracksKoreanWithoutSpaces() {
        val script = "안녕하세요! 오늘은 여행 촬영용 첫 드론을 고르는 방법과 돈을 아끼는 방법을 알려드리겠습니다. 가장 먼저 볼 것은 무게입니다."
        // The Korean model prints whole sentences without spaces.
        val heard = "안녕하세요오늘은여행촬영용첫드론을고르는방법과돈을아끼는방법을알려드리겠습니다가장먼저볼것은무게입니다"
        val (pos, total) = track(script, SpeechLang.KO, heard)
        assertTrue("position $pos of $total", pos >= total - 1)
    }

    @Test
    fun tracksHebrew() {
        val script = "שלום לכולם! היום אני אראה לכם איך לבחור את הרחפן הראשון לצילומי טיולים ולא לשלם יותר מדי."
        val heard = "שלום לכולם היום אני אראה לכם איך לבחור את הרחפן הראשון לצילומי טיולים ולא לשלם יותר מדי"
        val (pos, total) = track(script, SpeechLang.HE, heard)
        assertTrue("position $pos of $total", pos >= total - 1)
    }

    @Test
    fun countsWordsNotCharacters() {
        val zh = model("大家好！今天我来告诉大家，怎样挑选第一架旅行拍摄用的无人机。", SpeechLang.ZH)
        assertTrue(zh.spokenTokens > 25)
        assertEquals(Math.round(zh.spokenTokens / 1.7f), zh.spokenWords)
        val ru = model("Привет всем, сегодня покажу дрон.", SpeechLang.RU)
        assertEquals(ru.spokenTokens, ru.spokenWords)
    }
}
