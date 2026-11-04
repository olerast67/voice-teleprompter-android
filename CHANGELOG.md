# Changelog

All notable changes to Suflyor. Versions follow `versionName` in `app/build.gradle.kts`; each release's notes are taken from its section here.

## Unreleased

- **Speech packs: Hebrew, Korean and Chinese.** Hebrew (119 MB) is followed word by word, with or without one-letter prefixes ("ו", "ה", "ב"...), and the line marker stands on the right of right-to-left text. Korean (123 MB) and Chinese (27 MB) recognizers print no spaces, so Suflyor follows them character by character and treats characters that sound alike as a match (Chinese by their Mandarin reading, Korean by the letters of the syllable).
- **Auto** recognizes Hangul, Hebrew and Chinese scripts.
- In Chinese and Korean, word counts, reading time and the timed speed are in words, not characters.

## 0.10 — 2026-11-04

- **Lite version:** `Suflyor-<version>-lite.apk`, about 30 MB instead of 133 MB, without speech models inside. Every language is a speech pack you download once, Russian (23 MB), English (60 MB) and Turkish (6 MB) too; Russian and English packs keep the script-word hints. It comes as a separate pre-release next to each version, so Obtainium keeps installing the full APK, and both versions install over each other with your scripts.
- In the lite version, the setup card and Settings → Ready to record over the camera include the speech pack; a script whose language has no pack says so.
- Speech languages list: built-in languages first, and no repeated name ("Русский · русский").

## 0.9 — 2026-11-04

- **Speech packs: Portuguese, Spanish, German, French, Italian, Dutch and Swedish.** Settings → Voice following → Speech languages: Download opens the pack in the browser (55 to 120 MB), then Install downloaded pack, or open the file with Suflyor from the downloads. The app still has no internet permission; a pack installs only if its SHA-256 is in the list inside the APK. The models are Kroko community models (CC BY-SA); they recognize in steps of 1.28 s, so the text follows about a second later than with Russian, English or Turkish.
- **Auto picks these languages** from the script's function words ("de", "que", "não"; "der", "und"; "het", "och"). An English script with a few foreign names stays English.
- A script in a language whose pack is missing says so in the window and in the language list instead of listening with the wrong model.

## 0.8 — 2026-11-04

- **Turkish** (thanks to @Higgstor): voice following with the seda-v0.2-tiny streaming model (about 7 MB), Turkish word rules (dotted and dotless i, suffixes after an apostrophe, numbers said as words, "şey" and "yani") and a Turkish interface. Auto picks Turkish for a script with Turkish letters.
- **Brazilian Portuguese interface** (issue #7). Voice following in Portuguese needs a speech model and is not there yet; scrolling by sound and by timer work in any language.
- **Install help:** what to do when Play Protect says "App blocked to protect your device" (issue #4).

## 0.7 — 2026-10-27

- **Text margins you can change** (thanks to @Alexandrsv): left and right, in the floating window (Aa → Left / Right) and in the full-screen prompter (View). Useful in landscape and on tablets. Changing the margins now re-wraps the text; before, the right margin had no effect. Very wide margins shrink together so the text never disappears.
- **Full-screen prompter in landscape:** all controls share one row under the text instead of taking about half the height. A new button next to View hides the controls, so the whole screen is text; a small button in the corner brings them back. Remote keys keep working, and errors still show.

## 0.6 — 2026-10-21

- **Letter names in Russian scripts.** Abbreviations written with hyphens, like "Би-ту-би" or "Эй-Ай", are now boosted in the recognizer as one word, and the text follows whether the recognizer prints them glued ("битуби") or apart ("би ту би", "би тубе"). Before, a line like "Это мои Би-ту-би Эй-Ай СааС проекты" could leave the highlight several words behind until the next line. Ordinary hyphenated words ("кто-то", "из-за") are not affected.
- **Names heard with reduced vowels.** Unstressed о/а and е/и in long words count as close: a name written "Олераст" and heard as "алираст" now moves the text.
- **Bug reports without a GitHub account:** a form on olerast.com, linked from the README, the website and the issue chooser.

## 0.5 — 2026-09-29

- **Scrolling by sound.** A third scroll mode next to voice and timer: the text moves at the set speed while you talk and waits while you're quiet. It needs no speech model, so it works for a script in any language.
- **Full-screen prompter** (the rehearsal screen): text up to 80 sp, remote keys, and a mirror for teleprompter glass, all under the View button.
- **Scripts:** share a script's text, duplicate it, save every script to a zip of Markdown files and restore such a zip. The editor counts words and the length of the take as you type.
- **Errors are visible.** When the recognizer fails to load or the microphone stops, the window turns red and says why instead of showing "listening". A microphone that stops is reopened (three tries with growing pauses); if that fails, the text keeps moving at the set speed and the chosen mode stays for the next session.
- **Crash report:** a crash or a freeze leaves one report in the app's private folder, without recognized phrases, app names or script titles; the log screen offers to share or delete it. Errors in the log now include a few stack frames.
- **Memory and battery:** the speech model is freed 2.5 minutes after a session and when Android is short of memory; recognition no longer runs while paused, on the timer or when the microphone gives only silence.
- **Fixes:** turning "boost script words" on or off takes effect at once, not after a language change. Scripts are written atomically, so a full storage or a killed app can't leave half a file; an unreadable file is set aside as .broken instead of stopping the library from loading. The floating window uses a cut-out mode that exists on Android 10.
- **About:** links to the website, the source code, bug reports and donations, and the license.
- **Settings in their place.** Settings are now a short list of topics, each on its own page. What you change while filming is where you film: the Aa button in the floating window opens text size, lines, background and speed right over the camera; View on the full-screen prompter holds text size, mirror and speed; the script screen has the speech language and the speed; the ⋮ menu of the script list saves and restores backups.
- **Speech language per script.** Each script can have its own language; the one in Settings is the default for the rest.
- **Mirror** can flip left to right, upside down or both, to match the rig.

## 0.4 — 2026-09-26

First public version. Suflyor now works in English as well as Russian; everything from 0.3 below is included.

- **English interface.** The app follows the phone's language; on Android 13+ it can be set just for Suflyor. Russian stays as it was.
- **English speech**, on the phone like Russian:
  - a streaming Zipformer model trained on LibriSpeech, next to the Russian one;
  - the speech language is picked from the script's letters, or fixed in Settings; opening a script in the other language swaps the model;
  - English-aware following: contractions and curly apostrophes, numbers written as digits but said as words, abbreviations (Mr., Dr., e.g.), acronyms spelled out (A I, C E O), fillers (um, uh), and "the" is no longer taken for the start of "then".
- **Built-in sample script** in the phone's language, with its bold text fixed.
- **Log** lines are English in every language, so they can go straight into bug reports.
- **Accessibility:** the floating window's pause button announces Pause or Resume, and the text size buttons have labels.

## 0.3 — 2026-09-25

Not published; these changes ship in 0.4.

- **Floating prompter over any app** — Instagram, TikTok, the camera:
  - it follows your voice while that app records video;
  - the text starts right under the front camera;
  - lock mode lets touches pass through to the camera buttons;
  - for landscape recording the text turns towards the lens.
- **Voice following**, offline Russian speech recognition (sherpa-onnx):
  - searches the whole script, and rare words count more than common ones;
  - finds a neighbouring line after about two words, a far jump after three or four;
  - stays put in pauses and when you talk off script.
- **Quick Settings tile** starts the prompter over whatever app is open.
- **Remote controls**: volume keys, Bluetooth selfie remotes, rings, clickers and keyboards. Keys can be reassigned.
- **Script library**:
  - import of TXT (UTF-8, UTF-16, Windows-1251, KOI8-R), Markdown and Obsidian, DOCX, ODT, RTF, HTML and PDF;
  - share-to and open-with from other apps;
  - a simple editor with `**emphasis**`, `# headings` and `[notes that are not read]`.
- **Rehearsal** mode and timed scrolling with a 3-2-1 countdown.
- **Private by design**:
  - no `INTERNET` permission, analytics or ads;
  - the accessibility service works only during a session and can't read the screen;
  - scripts are excluded from cloud backup, and release builds don't copy the journal to the system log.
- **Hardened importers**: limits against zip bombs, XML entity tricks and oversized files; DOCX tracked changes and Strict OOXML; RTF font code pages.
- **Signed releases** built by GitHub Actions, with SHA-256 checksums and build provenance attestations.
