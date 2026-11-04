package com.olerast.suflyor.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olerast.suflyor.App
import com.olerast.suflyor.R
import com.olerast.suflyor.diag.DiagLog
import com.olerast.suflyor.script.SpeechLang
import com.olerast.suflyor.speech.SpeechPack
import com.olerast.suflyor.speech.SpeechPacks
import java.util.Locale

/** What the screens show about speech packs; MainActivity changes it when a pack is installed. */
object SpeechPackUi {
    /** Bumped after an install or delete: the screens re-read what is on disk. */
    var version by mutableIntStateOf(0)

    /** "45 MB" while a pack is being copied and checked, null otherwise. */
    var installing by mutableStateOf<String?>(null)
}

/** A language's name for running text, in the app's language: "Portuguese", "португальский". */
@Composable
fun speechLangDisplayName(lang: SpeechLang): String {
    if (!lang.isPack) return stringResource(speechLangName(lang))
    // The configuration always has at least one locale: the app's language, or the system's.
    val appLocale: Locale = LocalConfiguration.current.locales[0]
    val name = Locale.forLanguageTag(lang.code).getDisplayLanguage(appLocale)
    // Russian and Turkish write language names in lowercase in running text, like the built-in strings.
    return if (appLocale.language == "en" || appLocale.language == "pt") name.replaceFirstChar { it.titlecase(appLocale) } else name
}

/** Settings → Voice following: the downloadable languages, their state, and the install button. */
@Composable
fun SpeechPacksSection(onInstallFile: () -> Unit) {
    val context = LocalContext.current
    val version = SpeechPackUi.version
    SectionTitle(stringResource(R.string.settings_packs_title), Modifier.padding(horizontal = 20.dp).padding(top = 14.dp))
    Text(
        stringResource(R.string.settings_packs_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.TextMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    SpeechPacks.catalog.forEach { pack ->
        val installedVersion = remember(version, pack) { SpeechPacks.installedVersion(context, pack.lang) }
        PackRow(pack, installedVersion, context)
    }
    val installing = SpeechPackUi.installing
    Text(
        if (installing != null) stringResource(R.string.settings_pack_installing, installing) else stringResource(R.string.settings_pack_install_file),
        style = MaterialTheme.typography.labelLarge,
        color = if (installing != null) Palette.TextMuted else Palette.OnAccent,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp).clip(RoundedCornerShape(14.dp))
            .background(if (installing != null) Palette.SurfaceHigh else Palette.Accent)
            .clickable(enabled = installing == null, onClick = onInstallFile).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun PackRow(pack: SpeechPack, installedVersion: Int?, context: Context) {
    val size = Formatter.formatShortFileSize(context, pack.bytes)
    val name = speechLangDisplayName(pack.lang)
    val status = when {
        installedVersion == null -> stringResource(R.string.settings_pack_not_installed, size)
        installedVersion < pack.version -> stringResource(R.string.settings_pack_update, size)
        else -> stringResource(R.string.settings_pack_installed, size)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${pack.lang.label} · $name", style = MaterialTheme.typography.bodyLarge)
            Text(status, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        }
        if (installedVersion == null || installedVersion < pack.version) {
            SmallButton(stringResource(R.string.settings_pack_download), Palette.Accent, Palette.OnAccent) {
                DiagLog.i("Speech pack ${pack.lang.code}: opening the download in the browser")
                openInBrowser(context, SpeechPacks.downloadUrl(pack))
            }
        } else {
            SmallButton(stringResource(R.string.settings_pack_delete), Palette.SurfaceHigh, Palette.Text) {
                SpeechPacks.delete(context, pack.lang)
                DiagLog.i("Speech pack ${pack.lang.code} deleted")
                SpeechPackUi.version++
                App.instance.engine.onRecognizerSettingChanged()
                Toast.makeText(context, context.getString(R.string.pack_deleted, name), Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun SmallButton(text: String, background: Color, foreground: Color, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(12.dp)).background(background)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The browser downloads the pack: Suflyor has no internet permission and never connects anywhere itself. */
private fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
}
