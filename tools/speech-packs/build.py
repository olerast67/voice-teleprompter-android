"""Builds the speech packs (one zip per language) and the app's catalog of them.

    python tools/speech-packs/build.py OUT_DIR [--cache DIR] [--only pt,es]

For each language: downloads the Kroko community model (Banafo/Kroko-ASR, CC BY-SA) at a pinned revision, checks its
SHA-256, splits the .data container into encoder/decoder/joiner/tokens, adds manifest.json and LICENSE.md, and writes
a zip with fixed timestamps and order, so the same inputs give the same file and hash. Then it rewrites
app/src/main/java/com/olerast/suflyor/speech/SpeechPackCatalog.kt with each zip's name, size and SHA-256.

Upload the zips to the GitHub release named in SpeechPacks.RELEASE_TAG (see RELEASING.md). Needs only Python 3.
"""
import argparse
import hashlib
import io
import json
import os
import struct
import sys
import urllib.request
import zipfile

REPO = 'Banafo/Kroko-ASR'
REVISION = 'd45212aeb212dd66083dd22710c9954f40ff8cc1'
PACK_VERSION = 1

# code -> (Kotlin enum name, .data file, its SHA-256): Kroko's own container at REVISION.
SOURCES = {
    'pt': ('PT', 'Kroko-PT-Community-64-L-Streaming-001.data', '36c4a92f25c9243cbd8f007cfc624b09986debf2919a1e397891e62e063d0e61'),
    'es': ('ES', 'Kroko-ES-Community-64-L-Streaming-001.data', '7b28a828ce99c8053fc6d1d44acb9ff7f2597ae878b565a2609337170ac1f240'),
    'de': ('DE', None, None),
    'fr': ('FR', 'Kroko-FR-Community-64-L-Streaming-001.data', '1af97abc84f28c6765e50d62307d1ac477b50e7fc2e62d6704d6288e7c863d80'),
    'it': ('IT', 'Kroko-IT-Community-64-L-Streaming-001.data', 'e74cd91f89970639476353cfb7ba5044da10e36d8fb99c3a7a41c8b7c253726d'),
    'nl': ('NL', 'Kroko-NL-Community-64-L-Streaming-001.data', 'df37f612613d3e18ee339df8a24c196f0990e5889d725ea8160acc8f65504182'),
    'sv': ('SV', 'Kroko-SV-Community-64-L-Streaming-001.data', 'e987a6909225c7fe939f75dcfbce8f387bd607b1da6849b5f4f6b2cde44afa6d'),
}

# German: the August 2025 Kroko community model as exported for sherpa-onnx by its maintainer. It is half the size of
# the current .data file and made fewer mistakes on our test clips (8% wrong words against 11%).
SPLIT_SOURCES = {
    'de': ('csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06', '887db3d083240198c2d2b99fb66cfcfe6948ced8', {
        'encoder.onnx': '6e83993d6967ec7a3498b055b7e85ace85b5d64d1b1e8773cb29a43a11f5edb5',
        'decoder.onnx': '94a29592b403c53fa2231b478637da1ab4abcef7f5e46e432098416a4a3ed562',
        'joiner.onnx': '28356bff070aea51ab1d725a3278e81d19f9300f860d3248a7014292264df15a',
        'tokens.txt': '86e8370994ff2c01149ba8c4f8709aa93cdc18914b27a717e291e96faf39a6eb',
    }),
}

LICENSE = """# {name} speech pack for Suflyor

Model: Kroko ASR community model `{file}` by Banafo (https://kroko.ai/),
from https://huggingface.co/{repo} at revision {rev}.

License: Creative Commons Attribution-ShareAlike (CC BY-SA), as stated by the model's authors for their community
models. The model files are unchanged; this pack only splits the original `.data` container into the files that
sherpa-onnx loads (encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt) and adds this notice and manifest.json.

Suflyor itself is GPL-3.0: https://github.com/olerast67/voice-teleprompter-android
"""

NAMES = {'pt': 'Portuguese', 'es': 'Spanish', 'de': 'German', 'fr': 'French', 'it': 'Italian', 'nl': 'Dutch', 'sv': 'Swedish'}
ZIP_TIME = (2026, 1, 1, 0, 0, 0)


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def fetch(file, want, cache, repo=REPO, rev=REVISION, local=None):
    path = os.path.join(cache, local or file)
    if not (os.path.isfile(path) and sha256(path) == want):
        url = f'https://huggingface.co/{repo}/resolve/{rev}/{file}'
        print('download', url)
        urllib.request.urlretrieve(url, path + '.part')
        os.replace(path + '.part', path)
    got = sha256(path)
    if got != want:
        raise SystemExit(f'{file}: SHA-256 {got}, expected {want}')
    return path


def split(data):
    """[u32 len][JSON header], then [u32 len][blob]...: encoder, decoder, joiner (ONNX), tokens (text)."""
    pos = 0
    n, = struct.unpack_from('<I', data, pos)
    header = json.loads(data[4:4 + n])
    pos = 4 + n
    blobs = []
    while pos < len(data):
        n, = struct.unpack_from('<I', data, pos)
        blobs.append(data[pos + 4:pos + 4 + n])
        if len(blobs[-1]) != n:
            raise SystemExit('truncated .data file')
        pos += 4 + n
    if len(blobs) != 4 or header.get('type') != 'zipformer2':
        raise SystemExit(f'unexpected layout: {len(blobs)} parts, header {header}')
    return header, dict(zip(['encoder.onnx', 'decoder.onnx', 'joiner.onnx', 'tokens.txt'], blobs))


def build(code, out, cache):
    enum, file, want = SOURCES[code]
    if code in SPLIT_SOURCES:
        repo, rev, wanted = SPLIT_SOURCES[code]
        parts = {name: open(fetch(name, sha, cache, repo, rev, local=f'{code}-{name}'), 'rb').read() for name, sha in wanted.items()}
        label = f'Kroko {code.upper()} community 2025-08 (sherpa-onnx export)'
        source = f'https://huggingface.co/{repo}/tree/{rev}'
        notice = LICENSE.format(name=NAMES[code], file='encoder/decoder/joiner/tokens', repo=repo, rev=rev).replace(
            'this pack only splits the original `.data` container into the files that\nsherpa-onnx loads (encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt) and adds',
            'they are the sherpa-onnx export of the Kroko community model, and this pack only adds')
    else:
        header, parts = split(open(fetch(file, want, cache), 'rb').read())
        if header['language']['iso'].lower() != code:
            raise SystemExit(f'{file} is {header["language"]}')
        label = f'Kroko {code.upper()} community 64-L'
        source = f'https://huggingface.co/{REPO}/blob/{REVISION}/{file}'
        notice = LICENSE.format(name=NAMES[code], file=file, repo=REPO, rev=REVISION)
    manifest = {
        'format': 1,
        'lang': code,
        'version': PACK_VERSION,
        'model': {'type': 'zipformer2', 'label': label, 'chunkSeconds': 1.28},
        'license': 'CC-BY-SA',
        'source': source,
    }
    files = dict(parts)
    files['manifest.json'] = (json.dumps(manifest, indent=1) + '\n').encode()
    files['LICENSE.md'] = notice.encode()
    name = f'suflyor-speech-{code}-{PACK_VERSION}.zip'
    path = os.path.join(out, name)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for fname in sorted(files):
            info = zipfile.ZipInfo(fname, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, files[fname])
    open(path, 'wb').write(buf.getvalue())
    size = os.path.getsize(path)
    digest = sha256(path)
    print(f'{name}: {size} bytes, sha256 {digest}')
    return {'enum': enum, 'file': name, 'bytes': size, 'sha256': digest, 'model': manifest['model']['label']}


def write_catalog(packs, path):
    lines = [
        'package com.olerast.suflyor.speech',
        '',
        'import com.olerast.suflyor.script.SpeechLang',
        '',
        '/** Generated by tools/speech-packs/build.py: the speech packs this version of the app accepts. */',
        'internal object SpeechPackCatalog {',
        '    val PACKS: List<SpeechPack> = listOf(',
    ]
    for p in packs:
        lines.append(f'        SpeechPack(SpeechLang.{p["enum"]}, {PACK_VERSION}, "{p["file"]}", {p["bytes"]}L,')
        lines.append(f'            "{p["sha256"]}", "{p["model"]}", "CC BY-SA"),')
    lines += ['    )', '}', '']
    open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
    print('catalog:', path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('out')
    ap.add_argument('--cache', default=None)
    ap.add_argument('--only', default=','.join(SOURCES))
    a = ap.parse_args()
    cache = a.cache or os.path.join(a.out, 'cache')
    os.makedirs(cache, exist_ok=True)
    packs = [build(code, a.out, cache) for code in a.only.split(',')]
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    write_catalog(packs, os.path.join(root, 'app/src/main/java/com/olerast/suflyor/speech/SpeechPackCatalog.kt'))
    json.dump(packs, open(os.path.join(a.out, 'packs.json'), 'w'), indent=1)


if __name__ == '__main__':
    sys.exit(main())
