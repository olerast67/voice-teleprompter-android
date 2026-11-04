package com.olerast.suflyor.script

/**
 * How the tracker compares heard and written text in a speech-pack language. [WordNorm] does it word by word
 * (Portuguese, Hebrew...); [CharNorm] character by character, for languages whose recognizers print no spaces
 * (Chinese, Korean).
 */
interface PackRules {
    /** One unit of script or recognizer text: a word, or a single character in [CharNorm]. */
    val wordRegex: Regex

    /** Hesitations the recognizer prints that never appear in a script. */
    val fillers: Set<String>

    /** Fillers that are also real words: dropped from what was heard only when the script lacks them. */
    val softFillers: Set<String>

    /** Words a phrase may start with when a long line is broken (see PhraseBreaker). */
    val conjunctions: Set<String>

    /** How many recent units the tracker matches at once (see ScriptTracker.TAIL): more for single characters. */
    val tail: Int get() = 8

    /** How many finished units of recognizer output are kept for the next match (SessionEngine). */
    val history: Int get() = 16

    /** Units in an average spoken word: word counts, reading time and the timed speed are in words. */
    val unitsPerWord: Float get() = 1f

    fun normalizeWord(raw: String): String

    fun words(text: String): List<String>

    /** Written in another alphabet, so the model can't say it: weight 0, stepped over like a number. */
    fun isForeign(raw: String): Boolean

    fun weight(norm: String, foreign: Boolean): Float

    fun similarity(a: String, b: String): Float

    fun stem(word: String): String

    /** The recognizer's unfinished last unit may stand for the start of a longer script word. */
    fun canBePrefix(partial: String): Boolean

    fun prepareHypothesis(hyp: List<String>, vocab: Set<String>, scriptHasDigits: Boolean): List<String>

    /** A period after this word does not end a sentence ("Sr.", "Dr."). */
    fun isAbbreviation(word: String): Boolean
}
