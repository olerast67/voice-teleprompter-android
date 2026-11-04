"""Writes the Chinese reading table the tracker uses to match characters that sound alike.

    python tools/zh-pinyin/build.py [Unihan.zip]

Source: Unihan_Readings.txt (kMandarin) from the Unicode Character Database, Unicode License v3. Every CJK Unified
Ideograph of the basic block (U+4E00..U+9FFF) with a Mandarin reading gets its first reading without tones ("zhōng"
-> "zhong", "lǜ" -> "lv"). Output: app/src/main/resources/com/olerast/suflyor/script/zh_pinyin.txt, one line per
syllable: the syllable, a tab, then every character read that way. Without an argument the pinned Unihan.zip is
downloaded and checked by SHA-256.
"""
import hashlib
import io
import os
import sys
import unicodedata
import urllib.request
import zipfile

URL = 'https://www.unicode.org/Public/16.0.0/ucd/Unihan.zip'
SHA256 = 'b8f000df69de7828d21326a2ffea462b04bc7560022989f7cc704f10521ef3e0'

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, 'app/src/main/resources/com/olerast/suflyor/script/zh_pinyin.txt')


def toneless(p):
    s = unicodedata.normalize('NFD', p.lower())
    s = ''.join(c for c in s if unicodedata.category(c) != 'Mn' or c == '̈')
    return unicodedata.normalize('NFC', s).replace('ü', 'v')


def main():
    if len(sys.argv) > 1:
        data = open(sys.argv[1], 'rb').read()
    else:
        data = urllib.request.urlopen(URL, timeout=120).read()
    digest = hashlib.sha256(data).hexdigest()
    print('Unihan.zip sha256', digest)
    if SHA256 and digest != SHA256:
        raise SystemExit('Unihan.zip: unexpected SHA-256')
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        text = z.read('Unihan_Readings.txt').decode('utf-8')
    by = {}
    for line in text.splitlines():
        if not line.startswith('U+'):
            continue
        cp, field, value = line.split('\t', 2)
        if field != 'kMandarin':
            continue
        code = int(cp[2:], 16)
        if not 0x4E00 <= code <= 0x9FFF:
            continue
        reading = toneless(value.split()[0])
        if reading.isascii() and reading.isalpha():
            by.setdefault(reading, []).append(chr(code))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Mandarin readings without tones, from Unihan kMandarin (Unicode License v3). tools/zh-pinyin/build.py\n')
        for reading in sorted(by):
            f.write(reading + '\t' + ''.join(by[reading]) + '\n')
    print(OUT, len(by), 'syllables', sum(len(v) for v in by.values()), 'characters', os.path.getsize(OUT), 'bytes')


if __name__ == '__main__':
    main()
