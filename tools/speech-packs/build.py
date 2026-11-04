"""Builds the speech packs (one zip per language) and the app's catalog of them.

    python tools/speech-packs/build.py OUT_DIR [--cache DIR] [--only pt,es]

For each language: downloads the Kroko community model (Banafo/Kroko-ASR, CC BY-SA) at a pinned revision, checks its
SHA-256, splits the .data container into encoder/decoder/joiner/tokens, adds manifest.json and LICENSE.md, and writes
a zip with fixed timestamps and order, so the same inputs give the same file and hash. Then it rewrites
app/src/main/java/com/olerast/suflyor/speech/SpeechPackCatalog.kt with each zip's name, size and SHA-256.

Upload each zip to the speech-packs-<n> pre-release its catalog entry names (see RELEASING.md). Needs only Python 3.
"""
import argparse
import hashlib
import io
import json
import os
import re
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
    'he': ('HE', 'Kroko-IW-Community-64-L-Streaming-001.data', '1a3542a9cba5e80b73b94815cba3cae4567565c37015cc2aeeaa5c59afcacdaf'),
}

# Kroko names Hebrew by its old ISO code.
KROKO_ISO = {'he': 'iw'}

# The release each pack lives in: published releases are immutable, so later packs go to later releases.
RELEASE_OF = {'he': 'speech-packs-3', 'ko': 'speech-packs-3', 'zh': 'speech-packs-3', 'ar': 'speech-packs-4'}

# sherpa-onnx model type in the manifest: Kroko and the built-in models are zipformer2, the Korean and Chinese ones
# zipformer (v1).
MODEL_TYPE = {'ko': 'zipformer', 'zh': 'zipformer'}

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

# The models the full APK carries in assets (same URLs and SHA-256 as fetchSpeechAssets in app/build.gradle.kts),
# packed for the lite APK. They live in the speech-packs-2 release: speech-packs-1 is immutable.
_RU = 'https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16/resolve/31fa603e4f31279c6e1f7600fed13dc4312663ab'
_EN = 'https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/672fbf1b30579d6585301139bb363f42a0ad4a24'
_TR = 'https://huggingface.co/atasoglu/seda-v0.2-tiny/resolve/7a6fd02a9134bf7c2847b03fe2de9435c3b74f67'
_NOTICE = """# {name} speech pack for Suflyor

The same model files that the full Suflyor APK carries inside, unchanged, for the lite APK.

{credits}

Suflyor itself is GPL-3.0: https://github.com/olerast67/voice-teleprompter-android
"""
BUNDLED = {
    'ru': ('RU', 'speech-packs-2', 'Apache-2.0', 'Zipformer ru small int8', 0.32, [
        ('encoder.int8.onnx', f'{_RU}/encoder.int8.onnx', 'e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d'),
        ('decoder.onnx', f'{_RU}/decoder.onnx', '89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0'),
        ('joiner.int8.onnx', f'{_RU}/joiner.int8.onnx', 'b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5'),
        ('tokens.txt', f'{_RU}/tokens.txt', '93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6'),
        ('bpe.vocab', 'https://huggingface.co/alphacep/vosk-model-small-streaming-ru/resolve/e18123ee13f694036a1eea82eb43f9895387cb59/lang/unigram_500.vocab',
         'b159479dd209823a82698ea4092b2627272d96fbe616b91409ed02bc6cfb8df4'),
    ], _NOTICE.format(name='Russian', credits='Model: vosk-model-small-streaming-ru by Alpha Cephei (https://huggingface.co/alphacep/vosk-model-small-streaming-ru), int8 export by sherpa-onnx. License: Apache-2.0.')),
    'en': ('EN', 'speech-packs-2', 'Apache-2.0', 'Zipformer en LibriSpeech 2023-06-26 int8', 0.32, [
        ('encoder.int8.onnx', f'{_EN}/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx', '563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1'),
        ('decoder.onnx', f'{_EN}/decoder-epoch-99-avg-1-chunk-16-left-128.onnx', '7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef'),
        ('joiner.int8.onnx', f'{_EN}/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx', 'd944208d660d67c8d72cd2acaeac971fa5ceb8c80e76c1968148846fedd6e297'),
        ('tokens.txt', f'{_EN}/tokens.txt', '49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb'),
        ('bpe.vocab', 'https://huggingface.co/csukuangfj/icefall-asr-librispeech-conformer-ctc-jit-bpe-500-2021-11-09/resolve/66448a2164b7a67cc4d2118a2f1b2709d9fde148/data/lang_bpe_500/unigram_500.vocab',
         '28c02989b3cd8c2ffa974b1e33f97ec6cded170bda622ca627b7330b41c6c827'),
    ], _NOTICE.format(name='English', credits='Model: streaming Zipformer by the icefall project (https://github.com/k2-fsa/icefall), trained on LibriSpeech, exported by sherpa-onnx (https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26). License: Apache-2.0.')),
    'tr': ('TR', 'speech-packs-2', 'CC BY-SA 4.0', 'Zipformer tr seda-v0.2-tiny c16 int8', 0.32, [
        ('encoder.int8.onnx', f'{_TR}/c16/encoder.int8.onnx', '1a342823d6dfd9e3d8bb8377fc9002f3f42119d1a330c357ab48d85db521b46f'),
        ('decoder.onnx', f'{_TR}/c16/decoder.onnx', '5e30aa55ec9a83b7ca8fa830cee654b1336d3b67ac32ec4219c260204b7ab7ad'),
        ('joiner.int8.onnx', f'{_TR}/c16/joiner.int8.onnx', 'fff218a9176204908377193313caf01496dd673022cb79347deeb0a195ad8633'),
        ('tokens.txt', f'{_TR}/tokens.txt', 'f6e134fa357558d0c4ab56fbe380323c30e3d50a13fe402a68328d5a13c2236b'),
    ], _NOTICE.format(name='Turkish', credits='Model: seda-v0.2-tiny by atasoglu (https://huggingface.co/atasoglu/seda-v0.2-tiny). License: CC BY-SA 4.0.')),
}

_KO = 'https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16/resolve/ba6078bca4daf3f0dd37f79d0ab505af71df14a6'
_ZH = 'https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23/resolve/204ad334e2e683fd295359930cc16fc0432a23ac'
BUNDLED['ko'] = ('KO', 'speech-packs-3', 'Apache-2.0', 'Zipformer ko KsponSpeech 2024-06 int8', 0.32, [
    ('encoder.int8.onnx', f'{_KO}/encoder-epoch-99-avg-1.int8.onnx', '8d0b1aa24fbedd4e3948564ab7facd151b8ce9b0c48fc987c541de2de3af5697'),
    ('decoder.onnx', f'{_KO}/decoder-epoch-99-avg-1.onnx', 'b29cfb4575141e50a30a22b2c4579934f3d4f45b83c9c8c08c3aef5a3fa7abfc'),
    ('joiner.int8.onnx', f'{_KO}/joiner-epoch-99-avg-1.int8.onnx', '128b80a66a1f718488af8560f9d15895109b99ff3e573f0a0130e03774ef1ced'),
    ('tokens.txt', f'{_KO}/tokens.txt', '016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668'),
], _NOTICE.replace('The same model files that the full Suflyor APK carries inside, unchanged, for the lite APK.', 'The model files, unchanged.').format(
    name='Korean', credits='Model: streaming Zipformer trained on KsponSpeech by johnBamma (https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12), exported by sherpa-onnx (https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16). License: Apache-2.0.'))
BUNDLED['zh'] = ('ZH', 'speech-packs-3', 'Apache-2.0', 'Zipformer zh 14M 2023-02 int8', 0.32, [
    ('encoder.int8.onnx', f'{_ZH}/encoder-epoch-99-avg-1.int8.onnx', '1c556ea57cec304e55ec4b72e52c1cc098bb01476ed7d90f3de939fe126487b1'),
    ('decoder.onnx', f'{_ZH}/decoder-epoch-99-avg-1.onnx', '5ee0f03a2768ff1d5c83ef3a493243c7935d316cd41280037b14783a3467cc78'),
    ('joiner.int8.onnx', f'{_ZH}/joiner-epoch-99-avg-1.int8.onnx', 'a7cf9d82757bdcf786059454495a9ca95e4bd7347f72473fc08d794475c36169'),
    ('tokens.txt', f'{_ZH}/tokens.txt', '8b294db9045d6e5f94647f4c1eec1af4da143a75053c399611444b378ff966ac'),
], _NOTICE.replace('The same model files that the full Suflyor APK carries inside, unchanged, for the lite APK.', 'The model files, unchanged.').format(
    name='Chinese', credits='Model: 14M-parameter streaming Zipformer for Mandarin by the k2-fsa project, exported by sherpa-onnx (https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23). License: Apache-2.0.'))

_AR = 'https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/c6726c1147387ad2a11148b33973135d92a55e6c'
_AR_FC = 'https://huggingface.co/vladadu/stt-ar-fastconformer-sherpa-onnx/resolve/main'
BUNDLED['ar'] = ('AR', 'speech-packs-4', 'Apache-2.0', 'Zipformer ar multilingual 2025-02 int8', 0.32, [
    ('encoder.int8.onnx', f'{_AR}/encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx', 'f9001ed7a9e46d0294438c1a30cd7c72d1cc4bdd4e7880edbcda36f67081e32e'),
    ('decoder.onnx', f'{_AR}/decoder-epoch-75-avg-11-chunk-16-left-128.onnx', '7ebc63f34b21c8efb4a41a5a2eee7fe1448829ce0230ecc5369e67fc14d90d48'),
    ('joiner.int8.onnx', f'{_AR}/joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx', 'db88e3172323551abaa99b91b18fb422a27ea4a834fd0db10389f9478816f917'),
    ('tokens.txt', f'{_AR}/tokens.txt', '784f24950f6bcce1b0021035632dd60fd4617ecd8ca0581ab57d7b39d77ba5ab'),
    ('bpe.vocab', f'{_AR}/bpe.model', '90c6fbafdee6fae4dfe0534f097abab3874ec99b2c15cfad78cf1772a75e565d'),
], _NOTICE.replace('The same model files that the full Suflyor APK carries inside, unchanged, for the lite APK.', 'The model files, unchanged.').format(
    name='Arabic (Egyptian)', credits='Model: streaming Zipformer by PengChengStarling (https://github.com/yangb05/PengChengStarling), exported by sherpa-onnx (https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10). License: Apache-2.0.'))
_AR_ZIPFORMER = BUNDLED['ar']
_AR_FASTCONFORMER = ('AR', 'speech-packs-4', 'CC-BY-4.0', 'FastConformer ar hybrid large int8', 0.32, [
    ('encoder.int8.onnx', f'{_AR_FC}/encoder.int8.onnx', None),
    ('decoder.int8.onnx', f'{_AR_FC}/decoder.int8.onnx', None),
    ('joiner.int8.onnx', f'{_AR_FC}/joiner.int8.onnx', None),
    ('tokens.txt', f'{_AR_FC}/tokens.txt', None),
], _NOTICE.replace('The same model files that the full Suflyor APK carries inside, unchanged, for the lite APK.', 'The model files, unchanged.').format(
    name='Arabic (Egyptian)', credits='Model: stt_ar_fastconformer_hybrid_large_pcd_v1.0 by NVIDIA (https://huggingface.co/nvidia/stt_ar_fastconformer_hybrid_large_pcd_v1.0), sherpa-onnx int8 export by vladadu (https://huggingface.co/vladadu/stt-ar-fastconformer-sherpa-onnx). License: CC-BY-4.0.'))

NAMES = {'pt': 'Portuguese', 'es': 'Spanish', 'de': 'German', 'fr': 'French', 'it': 'Italian', 'nl': 'Dutch', 'sv': 'Swedish', 'he': 'Hebrew', 'ko': 'Korean', 'zh': 'Chinese', 'ar': 'Arabic (Egyptian)'}
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


def build_bundled(code, out, cache):
    """A pack of a model that the full APK carries in assets: the same pinned files, for the lite APK."""
    enum, release, lic, label, chunk, files_in, notice = BUNDLED[code]
    parts = {}
    for name, url, sha in files_in:
        local = f'{code}-{name}'
        path = os.path.join(cache, local)
        if name == 'bpe.vocab' and url.endswith('.model'):
            if not (os.path.isfile(path) and (sha is None or sha256(path) == sha)):
                model_local = f'{code}-bpe.model'
                model_path = os.path.join(cache, model_local)
                if not os.path.isfile(model_path):
                    print('download', url)
                    urllib.request.urlretrieve(url, model_path + '.part')
                    os.replace(model_path + '.part', model_path)
                try:
                    import sentencepiece as spm
                    sp = spm.SentencePieceProcessor()
                    sp.Load(model_path)
                    with open(path + '.part', 'w', encoding='utf-8') as f:
                        for i in range(sp.get_piece_size()):
                            f.write(f'{sp.id_to_piece(i)}\t{sp.get_score(i)}\n')
                    os.replace(path + '.part', path)
                except ImportError:
                    raise SystemExit('sentencepiece is required to export bpe.vocab from bpe.model')
        elif not (os.path.isfile(path) and (sha is None or sha256(path) == sha)):
            print('download', url)
            urllib.request.urlretrieve(url, path + '.part')
            os.replace(path + '.part', path)
        if sha is not None and sha256(path) != sha:
            raise SystemExit(f'{url}: wrong SHA-256')
        parts[name] = open(path, 'rb').read()
    manifest = {
        'format': 1,
        'lang': code,
        'version': PACK_VERSION,
        'model': {'type': MODEL_TYPE.get(code, 'zipformer2'), 'label': label, 'chunkSeconds': chunk},
        'license': lic,
        'source': ', '.join(sorted({u.split('/resolve/')[0] for _, u, _ in files_in})),
    }
    parts['manifest.json'] = (json.dumps(manifest, indent=1) + '\n').encode()
    parts['LICENSE.md'] = notice.encode()
    return write_zip(code, enum, release, lic, label, parts, out)


def write_zip(code, enum, release, lic, label, files, out):
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
    return {'enum': enum, 'file': name, 'bytes': size, 'sha256': digest, 'model': label, 'release': release, 'license': lic}


def build(code, out, cache):
    if code in BUNDLED:
        return build_bundled(code, out, cache)
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
        if header['language']['iso'].lower() != KROKO_ISO.get(code, code):
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
    return write_zip(code, enum, RELEASE_OF.get(code, 'speech-packs-1'), 'CC BY-SA', label, files, out)


def write_catalog(packs, path):
    existing = {}
    if os.path.isfile(path):
        for m in re.finditer(
            r'SpeechPack\(SpeechLang\.(\w+),\s*(\d+),\s*"([^"]+)",\s*(\d+)L,\s*"([0-9a-f]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)"\)',
            open(path, encoding='utf-8').read(),
        ):
            existing[m.group(1)] = {
                'enum': m.group(1),
                'file': m.group(3),
                'bytes': int(m.group(4)),
                'sha256': m.group(5),
                'model': m.group(6),
                'license': m.group(7),
                'release': m.group(8),
            }
    for p in packs:
        existing[p['enum']] = p
    canonical = [SOURCES[c][0] for c in SOURCES] + [BUNDLED[c][0] for c in BUNDLED]
    ordered = [existing[e] for e in canonical if e in existing] + [
        p for e, p in existing.items() if e not in canonical
    ]
    lines = [
        'package com.olerast.suflyor.speech',
        '',
        'import com.olerast.suflyor.script.SpeechLang',
        '',
        '/** Generated by tools/speech-packs/build.py: the speech packs this version of the app accepts. */',
        'internal object SpeechPackCatalog {',
        '    val PACKS: List<SpeechPack> = listOf(',
    ]
    for p in ordered:
        lines.append(f'        SpeechPack(SpeechLang.{p["enum"]}, {PACK_VERSION}, "{p["file"]}", {p["bytes"]}L,')
        lines.append(f'            "{p["sha256"]}", "{p["model"]}", "{p["license"]}", "{p["release"]}"),')
    lines += ['    )', '}', '']
    open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
    print('catalog:', path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('out')
    ap.add_argument('--cache', default=None)
    ap.add_argument('--only', default=','.join(list(SOURCES) + list(BUNDLED)))
    ap.add_argument('--ar-model', choices=['zipformer', 'fastconformer'], default='zipformer')
    a = ap.parse_args()
    if a.ar_model == 'fastconformer':
        BUNDLED['ar'] = _AR_FASTCONFORMER
        MODEL_TYPE['ar'] = 'nemo_transducer'
    else:
        BUNDLED['ar'] = _AR_ZIPFORMER
        MODEL_TYPE.pop('ar', None)
    cache = a.cache or os.path.join(a.out, 'cache')
    os.makedirs(cache, exist_ok=True)
    packs = [build(code, a.out, cache) for code in a.only.split(',')]
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    write_catalog(packs, os.path.join(root, 'app/src/main/java/com/olerast/suflyor/speech/SpeechPackCatalog.kt'))
    json.dump(packs, open(os.path.join(a.out, 'packs.json'), 'w'), indent=1)


if __name__ == '__main__':
    sys.exit(main())
