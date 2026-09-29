package com.olerast.suflyor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import com.olerast.suflyor.App
import com.olerast.suflyor.R
import com.olerast.suflyor.doc.MarkdownImporter
import com.olerast.suflyor.doc.Paragraph
import com.olerast.suflyor.doc.ScriptDocument
import com.olerast.suflyor.script.ScriptLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Plain-text editor. Formatting is Markdown-light: **bold**, # heading, [note that is not read]. */
@Composable
fun EditorScreen(
    isNew: Boolean,
    initialTitle: String,
    initialText: String,
    /** The script's own speech language, so the count follows the same word rules as the saved script. */
    speechLang: String? = null,
    onCancel: () -> Unit,
    onSave: (title: String, text: String) -> Unit,
) {
    // Saveable: typed text survives the app being killed in the background.
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var text by rememberSaveable { mutableStateOf(initialText) }
    val canSave = text.isNotBlank()
    val untitled = stringResource(R.string.common_untitled)
    // Live length of the take: counted like the saved script, off the main thread, after typing pauses.
    var words by remember { mutableIntStateOf(-1) }
    LaunchedEffect(text) {
        delay(300)
        words = withContext(Dispatchers.Default) { countSpokenWords(text, speechLang) }
    }
    val wpm = App.instance.settings.autoScrollWpm

    Column(Modifier.fillMaxSize().background(Palette.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(stringResource(if (isNew) R.string.editor_title_new else R.string.editor_title_edit), onBack = onCancel) {
            TextButton(onClick = { if (canSave) onSave(title.ifBlank { untitled }, text) }) {
                Text(
                    stringResource(R.string.editor_action_done),
                    color = if (canSave) Palette.Accent else Palette.TextMuted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box {
                if (title.isEmpty()) {
                    Text(
                        stringResource(R.string.editor_hint_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Palette.TextMuted,
                    )
                }
                BasicTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(color = Palette.Text),
                    cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Palette.Outline)
            Box {
                if (text.isEmpty()) {
                    Text(
                        stringResource(R.string.editor_hint_text),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
                        color = Palette.TextMuted,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Text, fontSize = 18.sp, lineHeight = 27.sp),
                    cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp),
                )
            }
        }
        if (words >= 0) {
            Text(
                stringResource(
                    R.string.editor_stats,
                    pluralStringResource(R.plurals.words_count, words, words),
                    formatDuration(if (wpm > 0) (words * 60 + wpm - 1) / wpm else 0),
                    wpm,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            )
        }
        Text(
            stringResource(R.string.editor_syntax_help),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** Words that will be read aloud, with the rules of the script's speech language (headings and [notes] don't count). */
private fun countSpokenWords(text: String, speechLang: String?): Int {
    if (text.isBlank()) return 0
    val doc = MarkdownImporter.parse(text, "").copy(speechLang = speechLang)
    val lang = App.instance.settings.speechLangFor(doc)
    return ScriptLayout.build(doc, phraseMode = false, lang = lang).spokenTokens
}

/** Turns a document back into the editor's Markdown-light text. */
fun ScriptDocument.toEditableText(): String = paragraphs.joinToString("\n\n") { p ->
    when (p.kind) {
        Paragraph.Kind.HEADING -> "# ${p.text}"
        Paragraph.Kind.BODY -> buildString {
            var last = 0
            for (r in p.emphasis.sortedBy { it.first }) {
                if (r.first < last || r.last >= p.text.length) continue
                append(p.text, last, r.first)
                append("**").append(p.text, r.first, r.last + 1).append("**")
                last = r.last + 1
            }
            append(p.text, last, p.text.length)
        }
    }
}
