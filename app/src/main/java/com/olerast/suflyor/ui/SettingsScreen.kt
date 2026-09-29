package com.olerast.suflyor.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olerast.suflyor.App
import com.olerast.suflyor.BuildConfig
import com.olerast.suflyor.R
import com.olerast.suflyor.overlay.KeyAction
import com.olerast.suflyor.overlay.KeyBindings
import com.olerast.suflyor.overlay.PrompterTileService
import com.olerast.suflyor.script.SpeechLang

/**
 * Settings hold defaults and rarely changed things, one page per topic. What is changed while filming lives where
 * it is used: speed and text size in the floating window and on the full-screen prompter, the speech language and
 * speed on the script screen, backups in the library menu.
 */
enum class SettingsPage(val key: String, @StringRes val title: Int, @DrawableRes val icon: Int) {
    READINESS("readiness", R.string.settings_section_readiness, R.drawable.ic_check),
    OVERLAY("overlay", R.string.settings_section_overlay, R.drawable.ic_layers),
    FULLSCREEN("fullscreen", R.string.settings_section_fullscreen, R.drawable.ic_mirror),
    VOICE("voice", R.string.settings_section_voice, R.drawable.ic_mic),
    TIMED("timed", R.string.settings_section_autoscroll, R.drawable.ic_speed),
    TEXT("text", R.string.settings_section_text, R.drawable.ic_text),
    KEYS("keys", R.string.settings_section_keys, R.drawable.ic_remote),
    SCRIPTS("scripts", R.string.settings_section_scripts, R.drawable.ic_file),
    ADVANCED("advanced", R.string.settings_section_advanced, R.drawable.ic_settings),
    ABOUT("about", R.string.settings_section_about, R.drawable.ic_info),
    ;

    companion object {
        fun from(key: String?): SettingsPage? = entries.firstOrNull { it.key == key }
    }
}

@Composable
fun SettingsScreen(
    page: SettingsPage?,
    readiness: Readiness,
    onBack: () -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onRequestPermissions: () -> Unit,
    onJournal: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding()) {
        TopBar(stringResource(page?.title ?: R.string.settings_title), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (page) {
                null -> SettingsHome(readiness, onOpen)
                SettingsPage.READINESS -> ReadinessPage(readiness, onRequestPermissions)
                SettingsPage.OVERLAY -> OverlayPage()
                SettingsPage.FULLSCREEN -> FullScreenPage()
                SettingsPage.VOICE -> VoicePage()
                SettingsPage.TIMED -> TimedPage()
                SettingsPage.TEXT -> TextPage()
                SettingsPage.KEYS -> KeysPage()
                SettingsPage.SCRIPTS -> ScriptsPage(onBackup, onRestore)
                SettingsPage.ADVANCED -> AdvancedPage()
                SettingsPage.ABOUT -> AboutPage(onJournal)
            }
        }
    }
}

// ---- home --------------------------------------------------------------------------------------------------------

@Composable
private fun SettingsHome(readiness: Readiness, onOpen: (SettingsPage) -> Unit) {
    val s = App.instance.settings
    val ready = readiness.doneCount == readiness.total
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsPage.entries.forEach { p ->
            val summary = when (p) {
                SettingsPage.READINESS -> if (ready) {
                    stringResource(R.string.settings_sum_ready_all)
                } else {
                    stringResource(R.string.settings_sum_ready_part, readiness.doneCount, readiness.total)
                }
                SettingsPage.OVERLAY -> stringResource(R.string.settings_sum_overlay, s.overlayLines, s.fontSp, s.overlayAlpha)
                SettingsPage.FULLSCREEN ->
                    stringResource(R.string.settings_sum_fullscreen, s.fullScreenFontSp, stringResource(MirrorMode.of(s).label))
                SettingsPage.VOICE -> stringResource(R.string.settings_sum_voice, stringResource(defaultLangLabel(s.speechLang)))
                SettingsPage.TIMED -> stringResource(
                    R.string.settings_sum_timed,
                    s.autoScrollWpm,
                    if (s.countdownSec == 0) {
                        stringResource(R.string.settings_countdown_off)
                    } else {
                        stringResource(R.string.settings_countdown_value, s.countdownSec)
                    },
                )
                SettingsPage.TEXT -> if (s.phraseMode) {
                    stringResource(R.string.settings_sum_text_phrases, s.maxWords)
                } else {
                    stringResource(R.string.settings_sum_text_paragraphs)
                }
                SettingsPage.KEYS -> stringResource(if (s.keyControl) R.string.settings_sum_keys_on else R.string.settings_sum_keys_off)
                SettingsPage.SCRIPTS -> stringResource(R.string.settings_sum_scripts)
                SettingsPage.ADVANCED -> stringResource(R.string.settings_sum_advanced)
                SettingsPage.ABOUT -> stringResource(R.string.settings_sum_about, BuildConfig.VERSION_NAME)
            }
            val warn = p == SettingsPage.READINESS && !ready
            Card(Modifier.fillMaxWidth(), onClick = { onOpen(p) }) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (warn) Palette.AccentSoft else Palette.SurfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Ic(
                            if (warn) R.drawable.ic_warning else p.icon,
                            null,
                            tint = if (warn || p == SettingsPage.READINESS) Palette.Accent else Palette.TextSecondary,
                            size = 20.dp,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(p.title), style = MaterialTheme.typography.titleMedium, color = Palette.Text)
                        Text(summary, style = MaterialTheme.typography.bodyMedium, color = if (warn) Palette.Accent else Palette.TextMuted)
                    }
                    Ic(R.drawable.ic_chevron, null, tint = Palette.TextMuted, size = 20.dp)
                }
            }
        }
    }
}

@StringRes
private fun defaultLangLabel(code: String): Int = when (SpeechLang.fromCode(code)) {
    SpeechLang.RU -> R.string.speech_lang_ru
    SpeechLang.EN -> R.string.speech_lang_en
    null -> R.string.speech_lang_auto
}

// ---- pages -------------------------------------------------------------------------------------------------------

@Composable
private fun ReadinessPage(readiness: Readiness, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    ReadyRow(
        ok = readiness.mic,
        title = stringResource(R.string.settings_mic_title),
        subtitle = stringResource(R.string.settings_mic_hint),
        action = stringResource(R.string.settings_action_allow),
        onAction = onRequestPermissions,
    )
    ReadyRow(
        ok = readiness.a11yRunning,
        title = stringResource(R.string.settings_a11y_title),
        subtitle = stringResource(
            if (readiness.a11yEnabled && !readiness.a11yRunning) R.string.settings_a11y_not_running else R.string.settings_a11y_hint,
        ),
        action = stringResource(R.string.settings_action_open),
        onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
    )
    if (!readiness.a11yRunning) {
        Hint(stringResource(R.string.settings_a11y_restricted, systemLanguageAppName(context)))
        Text(
            stringResource(R.string.settings_app_info),
            style = MaterialTheme.typography.labelMedium,
            color = Palette.Accent,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp).clickable {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            },
        )
    }
    ReadyRow(
        ok = readiness.notifications,
        title = stringResource(R.string.settings_notifications_title),
        subtitle = stringResource(R.string.settings_notifications_hint),
        action = stringResource(R.string.settings_action_allow),
        onAction = onRequestPermissions,
    )

    SectionTitle(stringResource(R.string.settings_section_tile))
    Hint(stringResource(R.string.settings_tile_hint))
    if (Build.VERSION.SDK_INT >= 33) {
        Text(
            stringResource(R.string.settings_tile_add),
            style = MaterialTheme.typography.labelMedium,
            color = Palette.OnAccent,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp))
                .background(Palette.Accent).clickable { requestAddTile(context) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    } else {
        Text(
            stringResource(R.string.settings_tile_manual),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun OverlayPage() {
    val s = App.instance.settings
    var lines by remember { mutableIntStateOf(s.overlayLines) }
    var font by remember { mutableIntStateOf(s.fontSp) }
    var alpha by remember { mutableIntStateOf(s.overlayAlpha) }
    var autoRotate by remember { mutableStateOf(s.autoRotate) }
    Hint(stringResource(R.string.settings_overlay_page_hint))
    StepperRow(
        stringResource(R.string.settings_lines_title), "$lines", null,
        onMinus = { lines = (lines - 1).coerceAtLeast(1); s.overlayLines = lines },
        onPlus = { lines = (lines + 1).coerceAtMost(8); s.overlayLines = lines },
    )
    StepperRow(
        stringResource(R.string.settings_font_title), "$font", null,
        onMinus = { font = (font - 2).coerceAtLeast(14); s.fontSp = font },
        onPlus = { font = (font + 2).coerceAtMost(48); s.fontSp = font },
    )
    StepperRow(
        stringResource(R.string.settings_opacity_title),
        stringResource(R.string.settings_percent_value, alpha),
        stringResource(R.string.settings_opacity_hint),
        onMinus = { alpha = (alpha - 8).coerceAtLeast(24); s.overlayAlpha = alpha },
        onPlus = { alpha = (alpha + 8).coerceAtMost(96); s.overlayAlpha = alpha },
    )
    ToggleRow(stringResource(R.string.settings_rotate_title), stringResource(R.string.settings_rotate_hint), autoRotate) {
        autoRotate = it
        s.autoRotate = it
    }
}

@Composable
private fun FullScreenPage() {
    val s = App.instance.settings
    var font by remember { mutableIntStateOf(s.fullScreenFontSp) }
    var mirror by remember { mutableStateOf(MirrorMode.of(s)) }
    Hint(stringResource(R.string.settings_fullscreen_page_hint))
    StepperRow(
        stringResource(R.string.settings_font_title), "$font", null,
        onMinus = { font = (font - 4).coerceAtLeast(FULLSCREEN_MIN_FONT_SP); s.fullScreenFontSp = font },
        onPlus = { font = (font + 4).coerceAtMost(FULLSCREEN_MAX_FONT_SP); s.fullScreenFontSp = font },
    )
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.settings_mirror_title), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.settings_mirror_pick_hint), style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        Spacer(Modifier.height(8.dp))
        MirrorPicker(mirror) {
            mirror = it
            it.save(s)
        }
    }
}

@Composable
private fun VoicePage() {
    val app = App.instance
    val s = app.settings
    var speechLang by remember { mutableStateOf(s.speechLang) }
    var lead by remember { mutableIntStateOf(s.leadWords) }
    var wordHighlight by remember { mutableStateOf(s.wordHighlight) }
    var hotwords by remember { mutableStateOf(s.useHotwords) }
    // Auto names the language it picked for the current script, so a wrong guess is visible here.
    // What the letters say, not the script's own choice: this hint explains what Auto does.
    val detected = remember(speechLang, app.scripts.layoutVersion) { SpeechLang.detect(app.scripts.document) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(stringResource(R.string.settings_speech_lang_default_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_speech_lang_default_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
        )
    }
    Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            AUTO to R.string.speech_lang_auto,
            SpeechLang.RU.code to R.string.speech_lang_ru,
            SpeechLang.EN.code to R.string.speech_lang_en,
        ).forEach { (value, label) ->
            Pill(stringResource(label), speechLang == value) {
                if (speechLang != value) {
                    speechLang = value
                    s.speechLang = value
                    // Word matching and counting depend on the language: re-layout, recount the library.
                    app.scripts.onSpeechLangChanged()
                }
            }
        }
    }
    if (speechLang == AUTO) {
        Hint(stringResource(R.string.settings_speech_lang_auto_hint, stringResource(speechLangName(detected))))
    }
    StepperRow(
        stringResource(R.string.settings_lead_title),
        pluralStringResource(R.plurals.settings_lead_value, lead, lead),
        stringResource(R.string.settings_lead_hint),
        onMinus = { lead = (lead - 1).coerceAtLeast(0); s.leadWords = lead },
        onPlus = { lead = (lead + 1).coerceAtMost(4); s.leadWords = lead },
    )
    ToggleRow(stringResource(R.string.settings_highlight_title), stringResource(R.string.settings_highlight_hint), wordHighlight) {
        wordHighlight = it
        s.wordHighlight = it
    }
    ToggleRow(stringResource(R.string.settings_hotwords_title), stringResource(R.string.settings_hotwords_hint), hotwords) {
        hotwords = it
        s.useHotwords = it
        // The model is built with or without hints: reload it now, not at the next language change.
        app.engine.onRecognizerSettingChanged()
    }
}

@Composable
private fun TimedPage() {
    val s = App.instance.settings
    var wpm by remember { mutableIntStateOf(s.autoScrollWpm) }
    var countdown by remember { mutableIntStateOf(s.countdownSec) }
    Hint(stringResource(R.string.settings_timed_page_hint))
    StepperRow(
        stringResource(R.string.settings_wpm_title), "$wpm", stringResource(R.string.settings_wpm_hint),
        onMinus = { wpm = (wpm - WPM_STEP).coerceAtLeast(MIN_WPM); s.autoScrollWpm = wpm },
        onPlus = { wpm = (wpm + WPM_STEP).coerceAtMost(MAX_WPM); s.autoScrollWpm = wpm },
    )
    StepperRow(
        stringResource(R.string.settings_countdown_title),
        if (countdown == 0) stringResource(R.string.settings_countdown_off) else stringResource(R.string.settings_countdown_value, countdown),
        stringResource(R.string.settings_countdown_hint),
        onMinus = { countdown = (countdown - 1).coerceAtLeast(0); s.countdownSec = countdown },
        onPlus = { countdown = (countdown + 1).coerceAtMost(5); s.countdownSec = countdown },
    )
}

@Composable
private fun TextPage() {
    val app = App.instance
    val s = app.settings
    var phraseMode by remember { mutableStateOf(s.phraseMode) }
    var maxWords by remember { mutableIntStateOf(s.maxWords) }
    ToggleRow(stringResource(R.string.settings_phrase_title), stringResource(R.string.settings_phrase_hint), phraseMode) {
        phraseMode = it
        s.phraseMode = it
        app.scripts.relayout()
    }
    if (phraseMode) {
        StepperRow(
            stringResource(R.string.settings_max_words_title), stringResource(R.string.settings_max_words_value, maxWords), null,
            onMinus = { maxWords = (maxWords - 1).coerceAtLeast(3); s.maxWords = maxWords; app.scripts.relayout() },
            onPlus = { maxWords = (maxWords + 1).coerceAtMost(14); s.maxWords = maxWords; app.scripts.relayout() },
        )
    }
}

@Composable
private fun KeysPage() {
    val context = LocalContext.current
    val s = App.instance.settings
    var keyControl by remember { mutableStateOf(s.keyControl) }
    var volumeKeys by remember { mutableStateOf(s.volumeKeys) }
    @Suppress("UNUSED_VARIABLE")
    val keyRevision = KeyLearning.revision // re-read bindings after a key is assigned
    val bindings = s.keyBindings
    DisposableEffect(Unit) { onDispose { KeyLearning.action = null } }

    ToggleRow(stringResource(R.string.settings_keys_title), stringResource(R.string.settings_keys_hint), keyControl) {
        keyControl = it
        s.keyControl = it
    }
    ToggleRow(stringResource(R.string.settings_volume_keys_title), stringResource(R.string.settings_volume_keys_hint), volumeKeys) {
        volumeKeys = it
        s.volumeKeys = it
    }
    KeyAction.entries.forEach { action ->
        val keys = bindings.filterValues { it == action }.keys.map { KeyBindings.keyName(context, it) }
        val learning = KeyLearning.action == action
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(action.label), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (learning) stringResource(R.string.settings_key_waiting) else keys.joinToString(", ").ifEmpty { stringResource(R.string.settings_key_none) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (learning) Palette.Accent else Palette.TextMuted,
                )
            }
            Pill(stringResource(if (learning) R.string.common_cancel else R.string.settings_key_assign), learning) {
                KeyLearning.action = if (KeyLearning.action == action) null else action
            }
        }
    }
    Text(
        stringResource(R.string.settings_keys_reset),
        style = MaterialTheme.typography.labelMedium,
        color = Palette.Accent,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable {
            s.keyBindings = KeyBindings.DEFAULT
            KeyLearning.revision++
        },
    )
}

@Composable
private fun ScriptsPage(onBackup: () -> Unit, onRestore: () -> Unit) {
    Hint(stringResource(R.string.settings_scripts_page_hint))
    LinkRow(stringResource(R.string.settings_backup_title), stringResource(R.string.settings_backup_hint), onBackup)
    LinkRow(stringResource(R.string.settings_restore_title), stringResource(R.string.settings_restore_hint), onRestore)
}

@Composable
private fun AdvancedPage() {
    val s = App.instance.settings
    var source by remember { mutableIntStateOf(s.audioSource) }
    var rate by remember { mutableIntStateOf(s.sampleRate) }
    var diagnostics by remember { mutableStateOf(s.showDiagnostics) }
    SectionTitle(stringResource(R.string.settings_section_mic))
    Text(
        stringResource(R.string.settings_audio_source),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION to R.string.settings_source_speech,
            MediaRecorder.AudioSource.MIC to R.string.settings_source_standard,
            MediaRecorder.AudioSource.UNPROCESSED to R.string.settings_source_raw,
        ).forEach { (value, label) ->
            Pill(stringResource(label), source == value) {
                source = value
                s.audioSource = value
            }
        }
    }
    Text(
        stringResource(R.string.settings_sample_rate),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(48000, 16000).forEach { value ->
            Pill(stringResource(R.string.settings_rate_khz, value / 1000), rate == value) {
                rate = value
                s.sampleRate = value
            }
        }
    }
    SectionTitle(stringResource(R.string.settings_section_overlay))
    ToggleRow(stringResource(R.string.settings_diag_title), stringResource(R.string.settings_diag_hint), diagnostics) {
        diagnostics = it
        s.showDiagnostics = it
    }
}

@Composable
private fun AboutPage(onJournal: () -> Unit) {
    val context = LocalContext.current
    // Android 13+ has a per-app language screen; older versions have none, and the app follows the phone's language.
    if (Build.VERSION.SDK_INT >= 33) {
        LinkRow(stringResource(R.string.settings_language_title), stringResource(R.string.settings_language_value)) {
            openAppLanguageSettings(context)
        }
    }
    LinkRow(stringResource(R.string.journal_title), stringResource(R.string.settings_log_hint), onJournal)
    // Links open in the browser: the app itself has no internet permission.
    LinkRow(stringResource(R.string.settings_link_site), SITE_URL) { openUrl(context, SITE_URL) }
    LinkRow(stringResource(R.string.settings_link_code), REPO_URL) { openUrl(context, REPO_URL) }
    LinkRow(stringResource(R.string.settings_link_issue), stringResource(R.string.settings_link_issue_hint)) {
        openUrl(context, "$REPO_URL/issues/new/choose")
    }
    LinkRow(stringResource(R.string.settings_link_donate), stringResource(R.string.settings_link_donate_hint)) {
        openUrl(context, "$REPO_URL/blob/main/DONATE.md")
    }
    Hint(stringResource(R.string.settings_about_license))
    Hint(stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME))
}

// ---- pieces ------------------------------------------------------------------------------------------------------

/** [com.olerast.suflyor.Settings.speechLang] value that picks the language from the script's alphabet. */
private const val AUTO = "auto"

private const val REPO_URL = "https://github.com/olerast67/voice-teleprompter-android"
private const val SITE_URL = "https://olerast67.github.io/voice-teleprompter-android/"

/** Timed and sound scrolling speed, shared by Settings, the script screen, the floating window and the prompter. */
const val MIN_WPM = 60
const val MAX_WPM = 260
const val WPM_STEP = 10

const val FULLSCREEN_MIN_FONT_SP = 18
const val FULLSCREEN_MAX_FONT_SP = 80

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.TextMuted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
    }
}

/** Language name for running text ("now: English"); the pills use the names written in their own language. */
@StringRes
fun speechLangName(lang: SpeechLang): Int = when (lang) {
    SpeechLang.RU -> R.string.settings_speech_lang_name_ru
    SpeechLang.EN -> R.string.settings_speech_lang_name_en
}

/** Which action waits for a key press on the settings screen (MainActivity routes the next key here). */
object KeyLearning {
    var action by mutableStateOf<KeyAction?>(null)
    var revision by mutableIntStateOf(0)

    /** @return true if the key was taken for the action being assigned. */
    fun offer(keyCode: Int): Boolean {
        val a = action ?: return false
        val s = App.instance.settings
        s.keyBindings = s.keyBindings.toMutableMap().apply { put(keyCode, a) }
        action = null
        revision++
        return true
    }
}

private fun requestAddTile(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val sbm = context.getSystemService(StatusBarManager::class.java) ?: return
    sbm.requestAddTileService(
        ComponentName(context, PrompterTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_layers),
        context.mainExecutor,
    ) { result ->
        val msg = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.settings_tile_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.settings_tile_already_added
            else -> null
        }
        if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}

private fun openAppLanguageSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val app = Uri.fromParts("package", context.packageName, null)
    // Some vendor builds drop this screen; their App info page still has the Language entry.
    runCatching { context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, app)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)) } }
}

@Composable
private fun ReadyRow(ok: Boolean, title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Ic(if (ok) R.drawable.ic_check else R.drawable.ic_warning, null, tint = if (ok) Palette.Success else Palette.Accent, size = 22.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        }
        if (!ok) {
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.OnAccent,
                modifier = Modifier.padding(start = 10.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Accent)
                    .clickable(onClick = onAction).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * The app's name as Android's own Settings list it: in the system language, which may differ from the language
 * picked for this app (Android 13+ per-app language).
 */
private fun systemLanguageAppName(context: Context): String {
    val config = Configuration(context.resources.configuration)
    config.setLocales(Resources.getSystem().configuration.locales)
    return context.createConfigurationContext(config).getString(R.string.app_name)
}
