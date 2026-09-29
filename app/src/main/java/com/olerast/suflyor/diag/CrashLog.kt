package com.olerast.suflyor.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import androidx.annotation.RequiresApi
import com.olerast.suflyor.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept in the app's private folder until the user shares or deletes it from the log screen:
 * the in-memory journal dies with the process, so without this a crash on someone else's phone leaves nothing.
 * Journal lines with recognized speech, app names or script titles are left out (see PRIVACY.md).
 */
object CrashLog {
    private const val FILE = "crash-last.txt"
    private const val PREFS = "crash"
    private const val KEY_EXIT_SEEN = "exitSeen"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, "Crash in thread \"${thread.name}\"", e.stackTraceToString()) }
            previous?.uncaughtException(thread, e)
        }
        // Reading an ANR trace can take a while: not on the main thread during startup.
        if (Build.VERSION.SDK_INT >= 30) Thread({ runCatching { checkLastExit(app) } }, "crash-check").start()
    }

    fun read(context: Context): String? = runCatching { String(atomic(context).readFully()) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) = atomic(context).delete()

    private fun atomic(context: Context) = AtomicFile(File(context.filesDir, FILE))

    private fun write(context: Context, title: String, details: String) {
        val text = buildString {
            append("Suflyor ${BuildConfig.VERSION_NAME}, ${Build.MANUFACTURER} ${Build.MODEL}, ")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append("  ").append(title).append("\n\n")
            append(details.take(MAX_DETAILS)).append("\n\n")
            append("Log before it (recognized speech, app names and script titles left out):\n")
            append(
                DiagLog.text().lines()
                    .filterNot { line -> PRIVATE.any { line.contains(it, ignoreCase = true) } }
                    .takeLast(MAX_LOG_LINES).joinToString("\n"),
            )
        }
        val file = atomic(context)
        val out = file.startWrite()
        try {
            out.write(text.toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    /** ANRs and native crashes don't reach the Java handler: Android keeps their record, read it on the next start. */
    @RequiresApi(30)
    private fun checkLastExit(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (info.timestamp <= prefs.getLong(KEY_EXIT_SEEN, 0L)) return
        prefs.edit().putLong(KEY_EXIT_SEEN, info.timestamp).apply()
        val reason = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "failed to start"
            else -> return
        }
        DiagLog.i("Previous run ended: $reason${info.description?.let { " ($it)" } ?: ""}")
        when (info.reason) {
            // Thread stacks of the frozen app: text, and only its beginning is needed.
            ApplicationExitInfo.REASON_ANR -> {
                val trace = runCatching { info.traceInputStream?.bufferedReader()?.use { readAtMost(it, MAX_DETAILS) } }.getOrNull()
                write(context, "Previous run ended: $reason", trace ?: info.description ?: "no trace")
            }
            // A native tombstone is binary and holds memory near the crash (it may contain words of a script):
            // only the reason goes into the report.
            ApplicationExitInfo.REASON_CRASH_NATIVE -> write(context, "Previous run ended: $reason", info.description ?: "no details")
            // A Java crash already wrote its own, better report.
            else -> Unit
        }
    }

    private fun readAtMost(reader: java.io.Reader, max: Int): String {
        val buf = CharArray(max)
        var n = 0
        while (n < max) {
            val got = reader.read(buf, n, max - n)
            if (got < 0) break
            n += got
        }
        return String(buf, 0, n)
    }

    private const val MAX_DETAILS = 60_000
    private const val MAX_LOG_LINES = 150
    /** Journal lines that may carry what the user said or read, app names, or file and script names. */
    private val PRIVATE = listOf(
        "Heard:", "On screen", "Audio capture on device", "Script: ", "Import failed", "Unreadable script",
        "Couldn't accept data", "Backup",
    )
}
