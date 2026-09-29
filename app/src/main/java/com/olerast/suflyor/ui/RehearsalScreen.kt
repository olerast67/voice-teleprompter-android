package com.olerast.suflyor.ui

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olerast.suflyor.App
import com.olerast.suflyor.R
import com.olerast.suflyor.session.SessionEngine

/**
 * Full-screen prompter that follows the voice inside the app: practice before recording, or the screen under
 * teleprompter glass (mirror), on a stand next to the camera or on a lectern. Remote keys work here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RehearsalScreen(onBack: () -> Unit) {
    val app = App.instance
    val s = app.settings
    val state = rememberEngineState()
    @Suppress("UNUSED_VARIABLE")
    val layoutVersion = app.scripts.layoutVersion
    val view = LocalView.current
    var font by remember { mutableIntStateOf(s.fullScreenFontSp) }
    var mirror by remember { mutableStateOf(MirrorMode.of(s)) }
    var wpm by remember { mutableIntStateOf(s.autoScrollWpm) }
    var showView by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        if (app.engine.state.mode != SessionEngine.Mode.IN_APP) app.engine.start(SessionEngine.Mode.IN_APP)
        onDispose {
            view.keepScreenOn = false
            if (app.engine.state.mode == SessionEngine.Mode.IN_APP) app.engine.stop()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Ic(R.drawable.ic_back, stringResource(R.string.common_cd_back)) }
            val (color, label) = statusOf(state)
            StatusDot(color)
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            LevelMeter(state.levelDb, state.silencedBySystem == true || state.digitalSilence, Modifier.width(48.dp))
            Spacer(Modifier.width(10.dp))
            // One labelled button for everything about how the text looks: size, mirror, speed.
            Text(
                stringResource(R.string.rehearsal_view),
                style = MaterialTheme.typography.labelLarge,
                color = if (mirror != MirrorMode.OFF) Palette.Accent else Palette.Text,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh)
                    .clickable { showView = true }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        // A failed microphone or recognizer would otherwise look like plain silence.
        state.error?.let { error ->
            Text(
                error.message(LocalContext.current),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Danger,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Prompter(
                model = app.scripts.model,
                nextToken = state.nextToken,
                fontSp = font,
                linesAbove = 1.5f,
                modifier = Modifier.fillMaxSize(),
                mirrorX = mirror.x,
                mirrorY = mirror.y,
                onWordTap = { app.engine.jumpToToken(it) },
            )
            if (state.countdown > 0) {
                Text(
                    state.countdown.toString(),
                    color = Palette.Accent,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIcon(R.drawable.ic_replay, stringResource(R.string.common_cd_restart), { app.engine.restart() }, size = 48.dp)
            RoundIcon(R.drawable.ic_up, stringResource(R.string.common_cd_line_back), { app.engine.stepLine(-1) }, size = 48.dp)
            RoundIcon(
                if (state.paused) R.drawable.ic_play else R.drawable.ic_pause,
                stringResource(if (state.paused) R.string.common_cd_resume else R.string.common_cd_pause),
                { app.engine.togglePause() },
                size = 68.dp,
                bg = Palette.Accent,
                tint = Palette.OnAccent,
            )
            RoundIcon(R.drawable.ic_down, stringResource(R.string.common_cd_line_forward), { app.engine.stepLine(1) }, size = 48.dp)
            // The mode is named, not only drawn: three icons alone don't say what they do.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RoundIcon(
                    state.scroll.icon,
                    stringResource(R.string.common_cd_scroll_mode),
                    { app.engine.setScroll(state.scroll.next()) },
                    size = 48.dp,
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(scrollLabel(state.scroll)), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
            }
        }
    }

    if (showView) {
        ModalBottomSheet(onDismissRequest = { showView = false }, containerColor = Palette.Surface, contentColor = Palette.Text) {
            Column(Modifier.padding(bottom = 28.dp)) {
                Text(
                    stringResource(R.string.rehearsal_view),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_font_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("$font", style = MaterialTheme.typography.titleMedium)
                }
                Slider(
                    value = font.toFloat(),
                    onValueChange = {
                        font = ((it / 2).toInt() * 2).coerceIn(FULLSCREEN_MIN_FONT_SP, FULLSCREEN_MAX_FONT_SP)
                        s.fullScreenFontSp = font
                    },
                    valueRange = FULLSCREEN_MIN_FONT_SP.toFloat()..FULLSCREEN_MAX_FONT_SP.toFloat(),
                    colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    stringResource(R.string.settings_mirror_title),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                )
                Text(
                    stringResource(R.string.settings_mirror_pick_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    MirrorPicker(mirror) {
                        mirror = it
                        it.save(s)
                    }
                }
                SpeedStepper(wpm) {
                    wpm = it
                    s.autoScrollWpm = it
                }
            }
        }
    }
}

@StringRes
fun scrollLabel(scroll: SessionEngine.Scroll): Int = when (scroll) {
    SessionEngine.Scroll.VOICE -> R.string.scroll_mode_voice
    SessionEngine.Scroll.AUTO -> R.string.scroll_mode_auto
    SessionEngine.Scroll.SOUND -> R.string.scroll_mode_sound
}

@Composable
fun statusOf(s: SessionEngine.State): Pair<Color, String> = when {
    s.starting || s.loadingModel -> Palette.TextMuted to stringResource(R.string.rehearsal_status_starting)
    !s.listening -> Palette.TextMuted to stringResource(R.string.rehearsal_status_idle)
    s.error != null -> Palette.Danger to stringResource(R.string.rehearsal_status_error)
    s.paused -> Palette.Accent to stringResource(R.string.rehearsal_status_paused)
    s.scroll == SessionEngine.Scroll.AUTO ->
        Palette.Accent to stringResource(R.string.rehearsal_status_auto, App.instance.settings.autoScrollWpm)
    // Sound mode depends on hearing too: a silenced microphone must show before "while you talk".
    s.silencedBySystem == true -> Palette.Danger to stringResource(R.string.rehearsal_status_mic_busy)
    s.autoFallback -> Palette.Danger to stringResource(R.string.rehearsal_status_fallback)
    s.scroll == SessionEngine.Scroll.SOUND ->
        Palette.Accent to stringResource(R.string.rehearsal_status_sound, App.instance.settings.autoScrollWpm)
    else -> Palette.Success to stringResource(R.string.rehearsal_status_listening)
}
