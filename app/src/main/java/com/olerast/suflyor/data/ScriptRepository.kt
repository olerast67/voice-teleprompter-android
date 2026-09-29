package com.olerast.suflyor.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olerast.suflyor.App
import com.olerast.suflyor.R
import com.olerast.suflyor.Settings
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.doc.ImportWarning
import com.olerast.suflyor.doc.MarkdownImporter
import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.ScriptLayout
import com.olerast.suflyor.script.ScriptModel
import com.olerast.suflyor.script.SpeechLang
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Library of scripts, one JSON file per script in filesDir/scripts. Keeps the imported paragraphs (so layout
 * settings can change later) and the layout of the current script, which the session engine follows.
 * List and selection are Compose state, so screens update by themselves.
 */
class ScriptRepository(context: Context, private val settings: Settings) {
    data class Meta(
        val id: String,
        val title: String,
        val format: String,
        val words: Int,
        val updatedAt: Long,
    )

    /** Resources are looked up when needed, so the sample and fallback titles follow the current app language. */
    private val appContext = context.applicationContext

    /** Declared before init: a save failure there already posts a toast. */
    private val main = Handler(Looper.getMainLooper())
    private val dir = File(context.filesDir, "scripts").apply { mkdirs() }
    private val legacyFile = File(context.filesDir, "script.json")

    var items by mutableStateOf<List<Meta>>(emptyList())
        private set
    var currentId by mutableStateOf<String?>(null)
        private set

    var document: ScriptDocument = ScriptDocument("", "", emptyList())
        private set
    var model: ScriptModel = ScriptModel.EMPTY
        private set

    /** Bumped whenever [model] is rebuilt, so screens showing the current script recompose. */
    var layoutVersion by mutableIntStateOf(0)
        private set

    init {
        migrateLegacy()
        items = loadAllMeta()
        if (items.isEmpty()) add(sample())
        val last = settings.currentScriptId?.takeIf { id -> items.any { it.id == id } } ?: items.firstOrNull()?.id
        if (last != null) select(last) else showUnsaved(sample())
    }

    /** Storage is full or broken: show the script from memory rather than crash on every start. */
    private fun showUnsaved(doc: ScriptDocument) {
        currentId = null
        document = doc
        relayout()
    }

    fun select(id: String) {
        val doc = read(id) ?: return
        currentId = id
        settings.currentScriptId = id
        document = doc
        relayout()
    }

    /** Adds a script and makes it current. @return its id, or null when it couldn't be saved (the user is told). */
    fun add(doc: ScriptDocument): String? {
        val id = newId()
        val saved = write(id, doc, createdAt = System.currentTimeMillis())
        items = loadAllMeta()
        if (!saved) return null
        select(id)
        return id
    }

    /**
     * Writes several scripts (restoring a backup) without changing the current one. Safe off the main thread;
     * call [refresh] on the main thread afterwards. @return how many were saved.
     */
    fun writeAll(docs: List<ScriptDocument>): Int {
        val now = System.currentTimeMillis()
        // Older timestamps for later files keep the archive's order at the top of the library.
        return docs.withIndex().count { (i, doc) -> write(newId(), doc, createdAt = now - i, updatedAt = now - i) }
    }

    /** Re-reads the library list (after [writeAll]). */
    fun refresh() {
        items = loadAllMeta()
    }

    /** A copy next to the original, for trying another version of the text. @return the copy's id. */
    fun duplicate(id: String, copyTitle: (String) -> String): String? {
        val doc = documentOf(id) ?: return null
        return add(doc.copy(title = copyTitle(doc.title)))
    }

    fun update(id: String, doc: ScriptDocument) {
        val created = runCatching { JSONObject(readText(file(id))).optLong("createdAt") }.getOrDefault(System.currentTimeMillis())
        write(id, doc, created)
        items = loadAllMeta()
        if (id == currentId) {
            document = doc
            relayout()
        }
    }

    fun delete(id: String) {
        AtomicFile(file(id)).delete()
        items = loadAllMeta()
        if (items.isEmpty()) add(sample())
        if (id == currentId) {
            val next = items.firstOrNull()?.id
            if (next != null) select(next) else showUnsaved(sample())
        }
    }

    /** Every script, newest first: what a backup archive holds. Call on the main thread (saves happen there too). */
    fun allDocuments(): List<ScriptDocument> = items.mapNotNull { meta -> documentOf(meta.id) }

    private fun newId() = UUID.randomUUID().toString().substring(0, 8)

    fun documentOf(id: String): ScriptDocument? = if (id == currentId) document else read(id)

    /** Rebuilds the layout of the current script after layout settings change and hands it to the engine. */
    fun relayout() {
        model = ScriptLayout.build(document, settings.phraseMode, settings.maxWords, settings.speechLangFor(document))
        App.instance.engine.setScript(model)
        layoutVersion++
    }

    private fun file(id: String) = File(dir, "$id.json")

    /** @return whether the script is on disk now; a failure is logged and shown to the user. */
    private fun write(id: String, doc: ScriptDocument, createdAt: Long, updatedAt: Long = System.currentTimeMillis()): Boolean {
        // Counted with the speech language's rules: "don't" is one English word, not two.
        val lang = settings.speechLangFor(doc)
        val words = ScriptLayout.build(doc, phraseMode = false, lang = lang).spokenTokens
        val json = JSONObject().apply {
            put("id", id)
            put("title", doc.title)
            put("format", doc.format)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            put("words", words)
            put(KEY_WORDS_LANG, lang.code)
            put(KEY_DETECTED_LANG, SpeechLang.detect(doc).code)
            put(KEY_WARNING_CODES, JSONArray(doc.warnings.map { it.code }))
            doc.speechLang?.let { put(KEY_SPEECH_LANG, it) }
            put("paragraphs", JSONArray().apply {
                doc.paragraphs.forEach { p ->
                    put(JSONObject().apply {
                        put("text", p.text)
                        put("kind", p.kind.name)
                        put("em", JSONArray().apply { p.emphasis.forEach { put(it.first); put(it.last) } })
                    })
                }
            })
        }
        return writeText(file(id), json.toString()).onFailure {
            DiagLog.e("Couldn't save script", it)
            val msg = appContext.getString(R.string.library_save_failed, it.message ?: it.javaClass.simpleName)
            main.post { Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show() }
        }.isSuccess
    }

    /**
     * Whole-file replace: a full storage or a killed process leaves the previous version, never half a file.
     * AtomicFile also restores its backup if an earlier write was cut short.
     */
    private fun writeText(f: File, text: String): Result<Unit> = runCatching {
        val atomic = AtomicFile(f)
        val out = atomic.startWrite()
        try {
            out.write(text.toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private fun readText(f: File): String = String(AtomicFile(f).readFully())

    private fun read(id: String): ScriptDocument? = runCatching { parse(JSONObject(readText(file(id)))) }.getOrNull()

    private fun parse(o: JSONObject): ScriptDocument {
        val arr = o.getJSONArray("paragraphs")
        val paragraphs = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val em = p.optJSONArray("em") ?: JSONArray()
            Paragraph(
                p.getString("text"),
                runCatching { Paragraph.Kind.valueOf(p.optString("kind", "BODY")) }.getOrDefault(Paragraph.Kind.BODY),
                (0 until em.length() / 2).map { k -> em.getInt(2 * k)..em.getInt(2 * k + 1) },
            )
        }
        // Unknown codes (saved by a newer version) are skipped; before 0.4 warnings were saved as Russian sentences.
        val warnings = o.optJSONArray(KEY_WARNING_CODES)?.strings()?.mapNotNull { ImportWarning.fromCode(it) }
            ?: o.optJSONArray("warnings")?.strings()?.map { ImportWarning.fromLegacyText(it) }
            ?: emptyList()
        return ScriptDocument(
            o.optString("title", appContext.getString(R.string.common_untitled)),
            formatCode(o.optString("format", "?")),
            paragraphs,
            warnings,
            speechLang = o.optString(KEY_SPEECH_LANG).takeIf { SpeechLang.fromCode(it) != null },
        )
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun loadAllMeta(): List<Meta> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { f ->
            runCatching {
                val o = JSONObject(readText(f))
                val format = formatCode(o.optString("format"))
                Meta(f.nameWithoutExtension, o.optString("title"), format, wordCount(f, o), o.optLong("updatedAt"))
            }.onFailure {
                // Only the class: an org.json message quotes the whole file, that is the script's text.
                if (it is JSONException) {
                    // Broken JSON: kept for recovery by hand, out of the list so it isn't read on every start.
                    DiagLog.e("Unreadable script file ${f.name} (${it.javaClass.simpleName}), set aside as .broken")
                    runCatching { f.renameTo(File(f.parentFile, f.name + ".broken")) }
                } else {
                    // Out of memory, a read error: the file may be fine, so it stays and is tried again next time.
                    DiagLog.e("Couldn't read script file ${f.name} (${it.javaClass.simpleName})")
                }
            }.getOrNull()
        }
        .sortedByDescending { it.updatedAt }

    /**
     * The saved word count, recounted when it was made with another speech language than the one that applies now
     * (files from 0.3 were always counted the Russian way). Only the count is rewritten, so the order stays.
     */
    private fun wordCount(f: File, o: JSONObject): Int {
        val counted = SpeechLang.fromCode(o.optString(KEY_WORDS_LANG))
        val own = SpeechLang.fromCode(o.optString(KEY_SPEECH_LANG))
        val fixed = SpeechLang.fromCode(settings.speechLang)
        val detected = SpeechLang.fromCode(o.optString(KEY_DETECTED_LANG))
        if (counted != null && counted == (own ?: fixed ?: detected)) return o.optInt("words")
        val doc = parse(o)
        val lang = settings.speechLangFor(doc)
        val words = ScriptLayout.build(doc, phraseMode = false, lang = lang).spokenTokens
        o.put("words", words)
        o.put(KEY_WORDS_LANG, lang.code)
        o.put(KEY_DETECTED_LANG, SpeechLang.detect(doc).code)
        writeText(f, o.toString()).onFailure { DiagLog.e("Couldn't update the word count", it) }
        return words
    }

    /** After the speech language setting changes: the current layout and every library word count follow it. */
    fun onSpeechLangChanged() {
        relayout()
        items = loadAllMeta()
    }

    /** One script's speech language (a SpeechLang code), or null to follow the default in Settings. */
    fun setSpeechLang(id: String, code: String?) {
        val doc = documentOf(id) ?: return
        if (doc.speechLang == code) return
        update(id, doc.copy(speechLang = code))
    }

    private fun migrateLegacy() {
        if (!legacyFile.exists()) return
        runCatching {
            val doc = parse(JSONObject(legacyFile.readText()))
            if (!doc.isEmpty && doc.title != LEGACY_SAMPLE_TITLE) {
                write(UUID.randomUUID().toString().substring(0, 8), doc, System.currentTimeMillis())
            }
        }
        legacyFile.delete()
    }

    /** Created in the UI language of the moment and saved like any script: it doesn't change with the language later. */
    private fun sample(): ScriptDocument = MarkdownImporter.parse(
        appContext.getString(R.string.sample_script),
        appContext.getString(R.string.sample_title),
    ).copy(format = ScriptDocument.FORMAT_SAMPLE)

    companion object {
        private const val KEY_WARNING_CODES = "warningCodes"

        /** The script's own speech language; absent = follow the default in Settings. */
        private const val KEY_SPEECH_LANG = "speechLang"

        /** Language whose rules made "words", and the language the script's letters point to. */
        private const val KEY_WORDS_LANG = "wordsLang"
        private const val KEY_DETECTED_LANG = "detectedLang"

        /** The sample of the single-script storage (script.json) had this title and is not carried over. Data, not UI text. */
        private const val LEGACY_SAMPLE_TITLE = "Пример"

        /** Before 0.4 typed and built-in scripts saved Russian names instead of format codes. */
        private fun formatCode(saved: String): String = when (saved) {
            "Текст" -> ScriptDocument.FORMAT_TEXT
            "встроенный" -> ScriptDocument.FORMAT_SAMPLE
            else -> saved
        }
    }
}
