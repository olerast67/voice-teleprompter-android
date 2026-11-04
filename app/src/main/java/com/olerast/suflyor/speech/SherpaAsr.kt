package com.olerast.suflyor.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.olerast.suflyor.App
import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.script.TurkishNorm

/**
 * Offline streaming recognizer: sherpa-onnx with a streaming Zipformer per language (int8, in assets). Russian:
 * alphacep's small model (~28 MB). English: icefall's LibriSpeech model of 2023-06-26 (~73 MB), which prints UPPER CASE.
 * Turkish: seda-v0.2-tiny (~7 MB, 0.32 s chunks), which prints lowercase spoken-form Turkish.
 * Other languages come as speech packs on disk ([createFromPack]): Kroko community models (1.28 s chunks).
 * With "hotwords" on, words of the script are boosted through modified beam search (only for models with a BPE vocabulary).
 */
class SherpaAsr private constructor(
    private val recognizer: OnlineRecognizer,
    /** The config can encode hotwords: modified beam search over BPE units with the model's vocabulary loaded. */
    private val hotwordsEnabled: Boolean,
    override val lang: SpeechLang,
    override val description: String,
    override val hotwordsSetting: Boolean,
) : AsrEngine {
    private var stream: OnlineStream = recognizer.createStream()
    private var hotwords = ""
    private var hotwordsChanged = false
    private var lastText = ""
    private var released = false

    @Synchronized
    override fun accept(samples: FloatArray, sampleRate: Int): AsrUpdate? {
        // The engine may be swapped for another language while the audio thread still holds this one.
        if (released) return null
        if (hotwordsChanged) recreateStream()
        stream.acceptWaveform(samples, sampleRate)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        val rawText = recognizer.getResult(stream).text.trim()
        val text = if (lang == SpeechLang.AR && rawText.isNotEmpty() && rawText.none { it in '\u0600'..'\u06FF' }) "" else rawText
        if (recognizer.isEndpoint(stream)) {
            recognizer.reset(stream)
            lastText = ""
            return if (text.isNotEmpty()) AsrUpdate(text, isFinal = true) else null
        }
        if (text == lastText) return null
        lastText = text
        return if (text.isNotEmpty()) AsrUpdate(text, isFinal = false) else null
    }

    @Synchronized
    override fun setBiasWords(words: List<String>) {
        if (!hotwordsEnabled || released) return
        hotwords = hotwordList(lang, words)
        hotwordsChanged = true
    }

    private fun recreateStream() {
        stream.release()
        // Only a config that can encode hotwords gets them, and never an empty list (a script with no word the
        // model's vocabulary can spell): the plain stream is the one sherpa-onnx is tested with.
        stream = if (hotwordsEnabled && hotwords.isNotEmpty()) recognizer.createStream(hotwords) else recognizer.createStream()
        hotwordsChanged = false
        lastText = ""
    }

    @Synchronized
    override fun reset() {
        if (released) return
        recognizer.reset(stream)
        lastText = ""
    }

    @Synchronized
    override fun release() {
        if (released) return
        released = true
        stream.release()
        recognizer.release()
    }

    companion object {
        /** @param hasBpeVocab the model ships a bpe.vocab, so script words can be encoded as hotwords. */
        private class Model(val dir: String, val label: String, val hasBpeVocab: Boolean = true)

        private fun modelFor(lang: SpeechLang) = when (lang) {
            SpeechLang.RU -> Model("asr-ru", "Zipformer ru small int8")
            SpeechLang.EN -> Model("asr-en", "Zipformer en LibriSpeech 2023-06-26 int8")
            SpeechLang.TR -> Model("asr-tr", "Zipformer tr seda-v0.2-tiny c16 int8", hasBpeVocab = false)
            else -> null
        }

        /**
         * Loads the model of [lang] from assets (a second or two; call it off the main thread).
         * @param hotwords boost script words ([setBiasWords]); the app setting unless a caller (the benchmark) overrides it.
         */
        fun create(context: Context, lang: SpeechLang = SpeechLang.RU, hotwords: Boolean = App.instance.settings.useHotwords): SherpaAsr {
            // The lite APK has no models inside: every language, Russian too, comes from its speech pack.
            val model = modelFor(lang)?.takeIf { SpeechPacks.isBundled(lang) } ?: return createFromPack(context, lang, hotwords)
            val dir = model.dir
            // A model without a vocabulary can't encode hotwords: it runs as with the setting off.
            val bpe = hotwords && model.hasBpeVocab
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$dir/encoder.int8.onnx",
                        decoder = "$dir/decoder.onnx",
                        joiner = "$dir/joiner.int8.onnx",
                    ),
                    tokens = "$dir/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "zipformer2",
                    modelingUnit = if (bpe) "bpe" else "",
                    bpeVocab = if (bpe) "$dir/bpe.vocab" else "",
                ),
                // Close an utterance after 0.8 s of silence following speech; the tracker keeps its own history anyway.
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.0f, 0.0f),
                    rule2 = EndpointRule(true, 0.8f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 12.0f),
                ),
                enableEndpoint = true,
                decodingMethod = if (bpe) "modified_beam_search" else "greedy_search",
                maxActivePaths = 4,
                hotwordsScore = 1.5f,
            )
            // sherpa-onnx 1.13.8 builds the BPE encoder for hotwords only under exactly these settings.
            val canEncodeHotwords = config.decodingMethod == "modified_beam_search" &&
                config.modelConfig.modelingUnit == "bpe" && config.modelConfig.bpeVocab.isNotEmpty()
            val recognizer = OnlineRecognizer(assetManager = context.assets, config = config)
            val desc = "sherpa-onnx, ${model.label}, ${config.decodingMethod}" + if (canEncodeHotwords) ", script words" else ""
            return SherpaAsr(recognizer, canEncodeHotwords, lang, desc, hotwords)
        }

        /** The installed speech pack of [lang] (see [SpeechPacks]); [SpeechPackMissing] when there is none. */
        fun createFromPack(context: Context, lang: SpeechLang, hotwords: Boolean = App.instance.settings.useHotwords): SherpaAsr {
            if (!SpeechPacks.isInstalled(context, lang)) throw SpeechPackMissing(lang)
            val model = SpeechPacks.manifest(context, lang)?.optJSONObject("model")
            val label = model?.optString("label").orEmpty().ifEmpty { "${lang.code} speech pack" }
            val type = model?.optString("type").orEmpty().ifEmpty { "zipformer2" }
            return createFromFiles(SpeechPacks.dir(context, lang), lang, label, type, hotwords)
        }

        /**
         * Loads a streaming transducer from files on disk (a speech pack): [dir] holds the encoder, decoder and joiner
         * (int8 or not) and tokens.txt. With a bpe.vocab next to them (the Russian and English packs) and [hotwords] on,
         * script words are boosted as with the built-in models; otherwise greedy search.
         */
        fun createFromFiles(
            dir: java.io.File,
            lang: SpeechLang,
            label: String,
            modelType: String = "zipformer2",
            hotwords: Boolean = false,
        ): SherpaAsr {
            val vocab = java.io.File(dir, "bpe.vocab")
            val bpe = hotwords && vocab.isFile
            fun pick(stem: String): String {
                val files = dir.listFiles { f -> f.name.startsWith(stem) && f.name.endsWith(".onnx") }.orEmpty().sortedBy { it.name }
                val file = files.firstOrNull { "int8" in it.name } ?: files.firstOrNull()
                    ?: throw IllegalStateException("${dir.name}: no $stem*.onnx")
                return file.absolutePath
            }
            val config = OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = pick("encoder"), decoder = pick("decoder"), joiner = pick("joiner")),
                    tokens = java.io.File(dir, "tokens.txt").absolutePath,
                    numThreads = 2,
                    provider = "cpu",
                    modelType = modelType,
                    modelingUnit = if (bpe) "bpe" else "",
                    bpeVocab = if (bpe) vocab.absolutePath else "",
                ),
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.0f, 0.0f),
                    rule2 = EndpointRule(true, 0.8f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 12.0f),
                ),
                enableEndpoint = true,
                decodingMethod = if (bpe) "modified_beam_search" else "greedy_search",
                maxActivePaths = 4,
                hotwordsScore = 1.5f,
            )
            val recognizer = OnlineRecognizer(assetManager = null, config = config)
            val desc = "sherpa-onnx, $label, ${config.decodingMethod}" + if (bpe) ", script words" else ""
            return SherpaAsr(recognizer, hotwordsEnabled = bpe, lang, desc, hotwordsSetting = hotwords)
        }
    }
}

/**
 * The hotwords string for sherpa-onnx ("/"-separated) from script words: only words every piece of which is in the
 * model's BPE vocabulary, at most 1500.
 * - Russian: the vocabulary is lowercase Cyrillic, so plain Cyrillic words of 4+ letters.
 * - English: LibriSpeech pieces are UPPER CASE Latin plus an apostrophe piece, so words of A–Z and "'" with 4+ letters.
 *   The words come normalized (ScriptModel.biasWords), so stop words, numbers and Cyrillic are already gone.
 * - Turkish: lowercase Turkish letters with 4+ letters, as TurkishNorm prints them. Unused until the Turkish model
 *   ships a BPE vocabulary (see [create]).
 */
internal fun hotwordList(lang: SpeechLang, words: List<String>): String {
    val usable = when (lang) {
        SpeechLang.RU -> words.asSequence()
            .map { it.lowercase() }
            .filter { w -> w.length >= 4 && w.all { it in 'а'..'я' || it == 'ё' } }
        SpeechLang.EN -> words.asSequence()
            .map { it.uppercase() }
            .filter { w -> w.count { it in 'A'..'Z' } >= 4 && w.all { it in 'A'..'Z' || it == '\'' } }
        SpeechLang.TR -> words.asSequence()
            .map { TurkishNorm.normalizeWord(it) }
            .filter { w -> w.length >= 4 && w.all { it in 'a'..'z' || it in "çğıöşü" } }
        SpeechLang.AR -> words.asSequence()
            .map { com.olerast.suflyor.script.WordNorm.AR.normalizeWord(it) }
            .filter { w -> w.length >= 3 && w.all { ch -> ch in '\u0621'..'\u064A' } }
        // Speech packs ship no BPE vocabulary, so there is nothing to encode script words with.
        else -> emptySequence()
    }
    return usable.distinct().take(1500).joinToString("/")
}
