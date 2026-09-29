package com.olerast.suflyor.doc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of the whole library: a zip with one Markdown file per script, readable in any editor and by Obsidian,
 * plus a small text file with each script's own speech language. The same archive restores into the app; only
 * .md and .txt files inside become scripts, everything else is skipped.
 */
object ScriptArchive {
    data class Entry(val title: String, val text: String, val speechLang: String? = null)

    /** What a restore found: the scripts, and how many script files were left out (too long, too many). */
    data class Contents(val entries: List<Entry>, val dropped: Int)

    /** "code<TAB>file name" per line, for the scripts that have their own speech language. */
    private const val LANGUAGES_FILE = "suflyor-languages.txt"

    private const val MAX_ENTRIES = 2_000
    private const val MAX_SCRIPTS = 500

    /** A script longer than this can't be imported anyway (DocumentImporter.MAX_CHARS, up to 4 bytes a char). */
    private const val MAX_ENTRY_BYTES = DocumentImporter.MAX_CHARS * 4L

    /** Everything the archive inflates to, skipped files included (zip bombs). */
    private const val MAX_TOTAL = 32L * 1024 * 1024

    fun write(scripts: List<Entry>, out: OutputStream) {
        val used = HashSet<String>()
        val languages = StringBuilder()
        ZipOutputStream(out).use { zip ->
            for (script in scripts) {
                val base = fileName(script.title)
                var name = "$base.md"
                var n = 2
                while (!used.add(name.lowercase())) name = "$base ($n).md".also { n++ }
                zip.putNextEntry(ZipEntry(name))
                zip.write(script.text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                script.speechLang?.let { languages.append(it).append('\t').append(name).append('\n') }
            }
            if (languages.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(LANGUAGES_FILE))
                zip.write(languages.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    /** A zip that holds text files and is not a DOCX or ODT (those are zips too). */
    fun isArchive(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) return false
        val names = runCatching { Zip.entryNames(bytes) }.getOrDefault(emptyList())
        if ("word/document.xml" in names || "content.xml" in names || "mimetype" in names) return false
        return names.any { isScriptFile(it) }
    }

    /** Scripts in archive order, with their speech language when the archive recorded one. */
    fun read(bytes: ByteArray): Contents {
        val files = ArrayList<Pair<String, String>>()
        var languages = ""
        var budget = MAX_TOTAL
        var count = 0
        var dropped = 0
        val chunk = ByteArray(64 * 1024)
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw ImportException(ImportError.ZipTooManyFiles)
                val isLanguages = entry.name == LANGUAGES_FILE
                val wanted = !entry.isDirectory && (isLanguages || isScriptFile(entry.name))
                if (wanted && !isLanguages && files.size >= MAX_SCRIPTS) dropped++
                val keep = wanted && (isLanguages || files.size < MAX_SCRIPTS)
                // Every entry is read through, skipped ones too, so that all of them count against the budget.
                val buf = if (keep) ByteArrayOutputStream() else null
                var size = 0L
                var tooLong = false
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    budget -= n
                    if (budget < 0) throw ImportException(ImportError.ZipTooLarge)
                    size += n
                    if (buf != null && !tooLong) {
                        if (size > MAX_ENTRY_BYTES) tooLong = true else buf.write(chunk, 0, n)
                    }
                }
                when {
                    buf == null -> Unit
                    tooLong -> if (!isLanguages) dropped++
                    isLanguages -> languages = buf.toString(Charsets.UTF_8.name())
                    else -> files += entry.name to TextDecoding.decode(buf.toByteArray())
                }
            }
        }
        val langOf = languages.lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(tab + 1) to line.substring(0, tab)
        }.toMap()
        val entries = files.map { (name, text) ->
            Entry(name.substringAfterLast('/').substringBeforeLast('.').trim(), text, langOf[name])
        }
        return Contents(entries, dropped)
    }

    private fun isScriptFile(name: String): Boolean {
        val base = name.substringAfterLast('/')
        // macOS adds "__MACOSX/._name" resource forks next to every file.
        if (base.startsWith("._") || name.startsWith("__MACOSX/")) return false
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext == "md" || ext == "markdown" || ext == "txt"
    }

    /** A title that is safe as a file name on every system and is not hidden; never empty. */
    internal fun fileName(title: String): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').trim()
        return cleaned.take(80).ifEmpty { "script" }
    }
}
