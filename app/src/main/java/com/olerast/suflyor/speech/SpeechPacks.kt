package com.olerast.suflyor.speech

import android.content.Context
import com.olerast.suflyor.script.SpeechLang
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * One downloadable speech pack: a zip with a streaming transducer model for one language.
 * @param sha256 of the zip file; the app installs only files whose hash is in [SpeechPacks.catalog].
 */
data class SpeechPack(
    val lang: SpeechLang,
    val version: Int,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
    val model: String,
    val license: String,
)

/** The model of a speech pack is missing: the session runs without voice following until it is installed. */
class SpeechPackMissing(val lang: SpeechLang) : IllegalStateException("speech pack ${lang.code} is not installed")

/**
 * Speech packs: the app has no internet permission, so the browser downloads a pack from GitHub Releases and the user
 * picks the file (or opens it with Suflyor). The file is accepted only if its SHA-256 is in [catalog], which ships
 * inside the signed APK: a pack can't be swapped on the way, and any other file is refused before it is unpacked.
 * Installed packs live in filesDir/speech/<code>/ with the manifest.json of the zip.
 */
object SpeechPacks {
    /** GitHub release that holds the pack files. tools/speech-packs/build.py writes the catalog below. */
    const val RELEASE_TAG = "speech-packs-1"
    private const val BASE_URL = "https://github.com/olerast67/voice-teleprompter-android/releases/download/$RELEASE_TAG/"

    val catalog: List<SpeechPack> = SpeechPackCatalog.PACKS

    fun forLang(lang: SpeechLang): SpeechPack? = catalog.firstOrNull { it.lang == lang }

    fun downloadUrl(pack: SpeechPack): String = BASE_URL + pack.fileName

    private fun root(context: Context) = File(context.filesDir, "speech")

    fun dir(context: Context, lang: SpeechLang) = File(root(context), lang.code)

    fun isInstalled(context: Context, lang: SpeechLang): Boolean = File(dir(context, lang), MANIFEST).isFile

    /** Pack version on disk, or null. An older version than the catalog's still works; the screen offers the update. */
    fun installedVersion(context: Context, lang: SpeechLang): Int? = manifest(context, lang)?.optInt("version")

    /** The model's description from the pack ("Kroko PT community 64-L"), for the journal. */
    fun manifest(context: Context, lang: SpeechLang): JSONObject? =
        File(dir(context, lang), MANIFEST).takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    /** Disk space a pack takes, for the settings screen. */
    fun installedBytes(context: Context, lang: SpeechLang): Long =
        dir(context, lang).listFiles().orEmpty().sumOf { it.length() }

    sealed interface InstallResult {
        data class Installed(val pack: SpeechPack) : InstallResult

        /** The file is not one of the packs this version of the app knows (another file, a damaged download). */
        data object UnknownFile : InstallResult

        data class NoSpace(val needBytes: Long) : InstallResult

        data class Failed(val detail: String) : InstallResult
    }

    /**
     * Copies [input] to a temporary file while hashing it, checks the hash against [catalog], then unpacks the zip into
     * the language's folder. The old pack of that language is replaced only after the new one is complete.
     * Runs for a few seconds on a 150 MB pack: call it off the main thread.
     * @param onProgress bytes read so far.
     */
    fun install(context: Context, input: InputStream, onProgress: (Long) -> Unit = {}): InstallResult =
        installInto(root(context), input, catalog, onProgress)

    /** [install] without Android: the packs' folder and the accepted packs are given (tests use their own). */
    internal fun installInto(root: File, input: InputStream, catalog: List<SpeechPack>, onProgress: (Long) -> Unit = {}): InstallResult {
        root.mkdirs()
        val incoming = File(root, "incoming.zip")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            incoming.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    total += n
                    if (total and 0xFFFFF < n) onProgress(total)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val pack = catalog.firstOrNull { it.sha256 == hash && it.bytes == total } ?: return InstallResult.UnknownFile
            // Unpacked files take about as much as the zip (onnx barely compresses).
            val need = pack.bytes + (64L shl 20)
            if (root.usableSpace < need) return InstallResult.NoSpace(need)
            val fresh = File(root, "${pack.lang.code}.new")
            fresh.deleteRecursively()
            fresh.mkdirs()
            unzip(incoming, fresh)
            // A plain match, not org.json: this check also runs in JVM tests, where Android's JSON classes are stubs.
            val lang = MANIFEST_LANG.find(File(fresh, MANIFEST).readText())?.groupValues?.get(1)
            check(lang == pack.lang.code) { "the pack's manifest is for $lang" }
            val target = File(root, pack.lang.code)
            val old = File(root, "${pack.lang.code}.old")
            old.deleteRecursively()
            if (target.exists() && !target.renameTo(old)) error("can't move the old pack aside")
            if (!fresh.renameTo(target)) {
                old.renameTo(target)
                error("can't move the new pack into place")
            }
            old.deleteRecursively()
            return InstallResult.Installed(pack)
        } catch (t: Throwable) {
            return InstallResult.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            incoming.delete()
        }
    }

    fun delete(context: Context, lang: SpeechLang) {
        dir(context, lang).deleteRecursively()
    }

    /** Only flat file names from the expected set: a zip can't write outside the folder ("../") or drop other files. */
    private fun unzip(zip: File, into: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                require(ALLOWED.matches(name)) { "unexpected file in the pack: $name" }
                File(into, name).outputStream().use { zin.copyTo(it) }
            }
        }
        require(File(into, MANIFEST).isFile) { "the pack has no $MANIFEST" }
    }

    const val MANIFEST = "manifest.json"
    private val MANIFEST_LANG = Regex("\"lang\"\\s*:\\s*\"([a-z]{2,3})\"")
    private val ALLOWED = Regex("[A-Za-z0-9_.-]{1,64}\\.(onnx|txt|json|md)")
}
