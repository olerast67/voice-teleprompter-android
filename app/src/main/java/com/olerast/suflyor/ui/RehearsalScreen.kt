package com.olerast.suflyor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
@Composable
fun RehearsalScreen(onBack: () -> Unit) {
    val app = App.instance
    val s = app.settings
    val state = rememberEngineState()
    @Suppress("UNUSED_VARIABLE")
    val layoutVersion = app.scripts.layoutVersion
    val view = LocalView.current
    var font by remember { mutableIntStateOf(s.fullScreenFontSp) }
    var mirror by remember { mutableStateOf(s.mirror) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        if (app.engine.state.mode != SessionEngine.Mode.IN_APP) app.engine.start(SessionEngine.Mode.IN_APP)
        onDispose {
            view.keepScreenOn = false
            if (app.engine.state.mode == SessionEngine.Mode.IN_APP) app.engine.stop()
        }
    }
    fun setFont(v: Int) {
        font = v.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        s.fullScreenFontSp = font
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
            TextButton(onClick = { setFont(font - FONT_STEP) }) {
                Text("A−", color = Palette.Text, fontWeight = FontWeight.Bold, modifier = Modifier)
            }
            TextButton(onClick = { setFont(font + FONT_STEP) }) {
                Text("A+", color = Palette.Text, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = {
                mirror = !mirror
                s.mirror = mirror
            }) {
                Ic(
                    R.drawable.ic_mirror,
                    stringResource(if (mirror) R.string.rehearsal_cd_mirror_off else R.string.rehearsal_cd_mirror_on),
                    tint = if (mirror) Palette.Accent else Palette.TextSecondary,
                )
            }
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
                mirrorX = mirror,
                mirrorY = mirror && s.mirrorVertical,
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
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
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
            RoundIcon(
                state.scroll.icon,
                stringResource(R.string.common_cd_scroll_mode),
                { app.engine.setScroll(state.scroll.next()) },
                size = 48.dp,
            )
        }
    }
}

private const val MIN_FONT_SP = 18
private const val MAX_FONT_SP = 80
private const val FONT_STEP = 4

@Composable
fun statusOf(s: SessionEngine.State): Pair<Color, String> = when {
    s.starting || s.loadingModel -> Palette.TextMuted to stringResource(R.string.rehearsal_status_starting)
    !s.listening -> Palette.TextMuted to stringResource(R.string.rehearsal_status_idle)
    s.error != null -> Palette.Danger to stringResource(R.string.rehearsal_status_error)
    s.paused -> Palette.Accent to stringResource(R.string.rehearsal_status_paused)
    s.scroll == SessionEngine.Scroll.AUTO ->
        Palette.Accent to stringResource(R.string.rehearsal_status_auto, App.instance.settings.autoScrollWpm)
    s.scroll == SessionEngine.Scroll.SOUND ->
        Palette.Accent to stringResource(R.string.rehearsal_status_sound, App.instance.settings.autoScrollWpm)
    s.silencedBySystem == true -> Palette.Danger to stringResource(R.string.rehearsal_status_mic_busy)
    s.autoFallback -> Palette.Danger to stringResource(R.string.rehearsal_status_fallback)
    else -> Palette.Success to stringResource(R.string.rehearsal_status_listening)
}
