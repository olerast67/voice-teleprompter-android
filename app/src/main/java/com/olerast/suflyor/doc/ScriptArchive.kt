package com.olerast.suflyor.doc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup of the whole library: a zip with one Markdown file per script, readable in any editor and by Obsidian.
 * The same archive restores into the app; only .md and .txt files inside are read, everything else is skipped.
 */
object ScriptArchive {
    private const val MAX_ENTRIES = 2_000
    private const val MAX_SCRIPTS = 500

    /** All text of one archive together; each script is also held to [DocumentImporter.MAX_CHARS] when parsed. */
    private const val MAX_TOTAL = 64L * 1024 * 1024

    /** Writes (title, Markdown text) pairs, one file each, named after the title. */
    fun write(scripts: List<Pair<String, String>>, out: OutputStream) {
        val used = HashSet<String>()
        ZipOutputStream(out).use { zip ->
            for ((title, text) in scripts) {
                val base = fileName(title)
                var name = "$base.md"
                var n = 2
                while (!used.add(name.lowercase())) name = "$base ($n).md".also { n++ }
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
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

    /** @return (title, text) of every script file in the archive, in archive order. */
    fun read(bytes: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var budget = MAX_TOTAL
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw ImportException(ImportError.ZipTooManyFiles)
                if (entry.isDirectory || !isScriptFile(entry.name)) continue
                if (out.size >= MAX_SCRIPTS) break
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    budget -= n
                    if (budget < 0 || buf.size() + n > DocumentImporter.MAX_TEXT_BYTES) {
                        throw ImportException(ImportError.ZipTooLarge)
                    }
                    buf.write(chunk, 0, n)
                }
                val title = entry.name.substringAfterLast('/').substringBeforeLast('.').trim()
                out += title to TextDecoding.decode(buf.toByteArray())
            }
        }
        return out
    }

    private fun isScriptFile(name: String): Boolean {
        val base = name.substringAfterLast('/')
        // macOS adds "__MACOSX/._name" resource forks next to every file.
        if (base.startsWith(".") || name.startsWith("__MACOSX/")) return false
        val ext = base.substringAfterLast('.', "").lowercase()
        return ext == "md" || ext == "markdown" || ext == "txt"
    }

    /** A title that is safe as a file name on every system; never empty. */
    internal fun fileName(title: String): String {
        val cleaned = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trimEnd('.')
        return cleaned.take(80).ifEmpty { "script" }
    }
}
