# Speech models: how they were chosen

Measured on 2026-11-04. Test audio: 10 to 20 clips per language from the test split of Multilingual LibriSpeech (read audiobooks), FLEURS dev for Swedish, Common Voice for Turkish. "WER" is the share of wrong words after lowercasing and removing punctuation, measured on a laptop with sherpa-onnx 2.0.1 (2 threads). The phone numbers come from the debug bench (`AsrBench`) on a Galaxy S24 FE with the app in the foreground: 2 to 3 minutes of speech joined from the clips, with the script made of their transcripts.

| Language | Model | Size of the pack | WER | Phone: load | Phone: real-time factor | Tracker on the phone |
|---|---|---|---|---|---|---|
| Portuguese | Kroko PT community 64-L | 119 MB | 8.3% | 1.5 s | 0.043 | 364 of 364 words |
| Spanish | Kroko ES community 64-L | 120 MB | 4.6% | 1.6 s | 0.043 | 336 of 337 |
| German | Kroko DE 2025-08, sherpa-onnx export | 55 MB | 8.0% | 1.3 s | 0.032 | 310 of 310 |
| French | Kroko FR community 64-L | 120 MB | 9.6% | 1.8 s | 0.043 | 446 of 446 |
| Italian | Kroko IT community 64-L | 119 MB | 19% | 1.9 s | 0.043 | 351 of 351 |
| Dutch | Kroko NL community 64-L | 120 MB | 13.5% | 1.9 s | about 0.04 | 421 of 421 |
| Swedish | Kroko SV community 64-L | 120 MB | 11.3% | 1.9 s | 0.041 | 213 of 213 |
| Russian (TTS) | Zipformer ru small, built in and as a pack | 23 MB | | 0.8 s | 0.034 | 105 of 105 |

Kroko models recognize in steps of 1.28 s (the built-in Russian, English and Turkish models: 0.32 s), so with a pack the text moves about a second later.

## Not used

- **Bookbot robust-es** (Spanish zipformer, 26 MB): printed nothing usable on MLS (100% WER).
- **Kroko DE, current `.data` file**: 11% WER against 8% for the August 2025 export, at twice the size.
- **Nemotron 3.5 ASR streaming 0.6B** (NVIDIA, int8, 320 ms chunks, 35 languages, 680 MB): 8.3% WER on six Portuguese clips, the same as Kroko. On the S24 FE: load 2.3 s, real-time factor 0.255, the tracker reached 364 of 364 words. It runs in real time on this phone, with shorter steps than Kroko, but takes about six times the computing of a Kroko model, so more battery and heat, and a mid-range phone may not keep up. A candidate for a "large universal pack" (Japanese, Hindi, Ukrainian, Polish, Vietnamese and others) if it is measured on a weaker phone first.
- When the app is in the background, Android gives it slow cores: the same Kroko benches ran at a real-time factor of about 0.2 instead of 0.04. Still five times faster than speech.

## Next languages

Hebrew (Kroko IW), Korean (sherpa-onnx zipformer 2024-06-16) and Bengali (Vosk 2026-02-09) have streaming models and need word rules of their own. Chinese has small streaming models but is written without spaces: the tracker has to follow characters, compared by their reading (pinyin), because the recognizer mixes up characters that sound alike. Japanese needs the same and has no small streaming model.
