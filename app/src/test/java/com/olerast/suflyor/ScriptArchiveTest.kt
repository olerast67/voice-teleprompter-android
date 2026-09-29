package com.olerast.suflyor

import com.olerast.suflyor.doc.ImportError
import com.olerast.suflyor.doc.ImportException
import com.olerast.suflyor.doc.MarkdownImporter
import com.olerast.suflyor.doc.ScriptArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScriptArchiveTest {
    private fun archive(scripts: List<Pair<String, String>>): ByteArray =
        ByteArrayOutputStream().also { ScriptArchive.write(scripts, it) }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun roundTripKeepsTitlesTextAndOrder() {
        val scripts = listOf(
            "Дрон для путешествий" to "# Вступление\n\nСегодня **разберём**, как выбрать дрон.\n\n[пауза] И не переплатить.",
            "Second" to "Plain English text.",
        )
        val bytes = archive(scripts)
        assertTrue(ScriptArchive.isArchive(bytes))
        assertEquals(scripts, ScriptArchive.read(bytes))
        // What the app does with an entry: the Markdown parses back into the same document.
        val doc = MarkdownImporter.parse(ScriptArchive.read(bytes)[0].second, "t")
        assertEquals(3, doc.paragraphs.size)
    }

    @Test
    fun sameAndUnsafeTitlesGetDistinctSafeNames() {
        val bytes = archive(listOf("a/b:c?" to "one", "a/b:c?" to "two", "" to "three", "..." to "four"))
        val titles = ScriptArchive.read(bytes).map { it.first }
        assertEquals(listOf("a b c", "a b c (2)", "script", "script (2)"), titles)
    }

    @Test
    fun officeDocumentsAndForeignZipsAreNotArchives() {
        assertFalse(ScriptArchive.isArchive(zip("word/document.xml" to "<w/>".toByteArray(), "notes.md" to "x".toByteArray())))
        assertFalse(ScriptArchive.isArchive(zip("mimetype" to "application/vnd.oasis.opendocument.text".toByteArray())))
        assertFalse(ScriptArchive.isArchive(zip("photo.jpg" to ByteArray(10))))
        assertFalse(ScriptArchive.isArchive("not a zip".toByteArray()))
    }

    @Test
    fun onlyTextFilesAreRead() {
        val bytes = zip(
            "folder/one.md" to "first".toByteArray(),
            "__MACOSX/folder/._one.md" to ByteArray(4),
            "picture.png" to ByteArray(100),
            "two.txt" to "second".toByteArray(),
        )
        assertEquals(listOf("one" to "first", "two" to "second"), ScriptArchive.read(bytes))
    }

    @Test
    fun hugeEntryIsRefused() {
        // 20 MB of spaces compresses to a few KB: a zip bomb for a text reader.
        val bytes = zip("big.md" to ByteArray(20 * 1024 * 1024) { ' '.code.toByte() })
        val error = runCatching { ScriptArchive.read(bytes) }.exceptionOrNull()
        assertTrue("$error", error is ImportException && error.error == ImportError.ZipTooLarge)
    }
}
