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
| Hebrew | Kroko IW community 64-L | 119 MB | 32% (FLEURS) | 1.7 s | 0.043 | 165 of 169 |
| Korean | Zipformer ko KsponSpeech 2024-06 | 123 MB | 26% of syllables (FLEURS) | 0.8 s | 0.061 | 550 of 550 syllables |
| Chinese | Zipformer zh 14M 2023-02 | 27 MB | 18% of characters (FLEURS) | 0.4 s | 0.023 | 280 of 280 characters |
| Russian (TTS) | Zipformer ru small, built in and as a pack | 23 MB | | 0.8 s | 0.034 | 105 of 105 |

Kroko models recognize in steps of 1.28 s (the built-in Russian, English and Turkish models: 0.32 s), so with a pack the text moves about a second later.

## Not used

- **Bookbot robust-es** (Spanish zipformer, 26 MB): printed nothing usable on MLS (100% WER).
- **Kroko DE, current `.data` file**: 11% WER against 8% for the August 2025 export, at twice the size.
- **Nemotron 3.5 ASR streaming 0.6B** (NVIDIA, int8, 320 ms chunks, 35 languages, 680 MB): 8.3% WER on six Portuguese clips, the same as Kroko. On the S24 FE: load 2.3 s, real-time factor 0.255, the tracker reached 364 of 364 words. It runs in real time on this phone, with shorter steps than Kroko, but takes about six times the computing of a Kroko model, so more battery and heat, and a mid-range phone may not keep up. A candidate for a "large universal pack" (Japanese, Hindi, Ukrainian, Polish, Vietnamese and others) if it is measured on a weaker phone first.
- When the app is in the background, Android gives it slow cores: the same Kroko benches ran at a real-time factor of about 0.2 instead of 0.04. Still five times faster than speech.

## Chinese, Korean, Hebrew

- The Korean and Chinese recognizers print no spaces, so the tracker follows them character by character (`CharNorm`): every Han character or Hangul syllable is one unit, it matches 14 recent units at once instead of 8, and two different characters still match when they sound the same (Chinese: the same Mandarin reading without tones, from Unihan; Korean: the same initial consonant and vowel).
- FLEURS clips are recorded much quieter than a phone microphone; at their original level the Korean model printed nothing for half of them. The numbers above are with the clips normalized to a peak of 0.6.
- Not used: the large Chinese Zipformer (161 MB, 10% wrong characters, real-time factor 0.14 on the laptop): no license is stated for its weights.

## Next languages

Bengali (Vosk 2026-02-09) has a streaming model and needs word rules of its own. Japanese can be followed by characters like Chinese but has no small streaming model.
