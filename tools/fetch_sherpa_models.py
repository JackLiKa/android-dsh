#!/usr/bin/env python3
"""Fetch and install the bundled sherpa-onnx speech stack for adsh.

Downloads the sherpa-onnx AAR plus the streaming Zipformer, Silero VAD and
CT-Transformer punctuation models (about 232 MiB of assets, 47 MiB AAR), and the
78 MiB Paraformer small model used for the optional second-pass final decode.
They are not committed; run this once after cloning, before building an APK with
the "本地增强" speech engine. Without the assets the app falls back to Vosk or
the system recognizer.

Usage:
    python3 tools/fetch_sherpa_models.py
    python3 tools/fetch_sherpa_models.py --force
    python3 tools/fetch_sherpa_models.py --skip-second-pass
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AAR_DEST = ROOT / "app/libs/sherpa-onnx-2.0.1.aar"
ASSET_ROOT = ROOT / "app/src/main/assets/sherpa"

AAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
AAR_SHA256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"

ZIPFORMER_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2"
)
ZIPFORMER_SHA256 = "5a2832047ea1f97dd0dc595b816c230c4bafad65cfc0341fa57517cadc50afd0"
ZIPFORMER_ROOT = "sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30"

PUNCT_URL = (
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/"
    "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2"
)
PUNCT_SHA256 = "c0d5aa5f8eeb686032345e180bedf39319dc2e0556781c6264bcadba8328a6e1"
PUNCT_ROOT = "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8"

VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.int8.onnx"
VAD_SHA256 = "c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20"

# Optional second-pass decoder. Served from HuggingFace because the GitHub release asset is
# unreliable; the file contents are the same model published in the asr-models release.
PARAFORMER_BASE = (
    "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09/resolve/main"
)
PARAFORMER_MODEL_SHA256 = "3ef6c19369b912f7caf3cef8e545c5ccd1a33d9d7ec792a46668dc41c4b229ec"
PARAFORMER_TOKENS_SHA256 = "4b2d964e18b9cf139b473003b6698fb2ed9a2a5ec55b93daa677b28f578897aa"

HOTWORDS = ASSET_ROOT / "hotwords.txt"
DEFAULT_HOTWORDS = """\
# One phrase per line. Only characters present in sherpa/model/tokens.txt are usable;
# the recognizer silently filters lines containing unknown characters.
深度求索
人工智能
大模型
语音助手
小助手
广州
北京
上海
成都
重庆
微信
支付宝
高德地图
百度地图
网易云音乐
京东
美团
饿了么
天气预报
空气质量
蓝牙
飞行模式
移动数据
无线网络
相机
闹钟
提醒
导航
打电话
发短信
"""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path) -> None:
    print(f"download {url}")
    with urllib.request.urlopen(url, timeout=180) as response, destination.open("wb") as out:
        shutil.copyfileobj(response, out)


def fetch(url: str, expected: str, destination: Path, force: bool) -> None:
    if destination.is_file() and not force:
        if sha256(destination) == expected:
            print(f"already installed: {destination}")
            return
        print(f"checksum mismatch, reinstalling: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="adsh-sherpa-") as tmp:
        archive = Path(tmp) / destination.name
        download(url, archive)
        print(f"verify sha256 {expected}")
        actual = sha256(archive)
        if actual != expected:
            raise SystemExit(f"sha256 mismatch: expected {expected}, got {actual}")
        shutil.move(str(archive), str(destination))
    print(f"installed: {destination}")


def require_assets(path: Path, names: list[str]) -> None:
    missing = [name for name in names if not (path / name).is_file()]
    if missing:
        raise SystemExit(f"model incomplete under {path}: missing {', '.join(missing)}")


def install_model(url: str, expected: str, archive_root: str, files: list[Path]) -> None:
    if all(path.is_file() for path in files):
        print("model already installed:", files[0].parent)
        return
    with tempfile.TemporaryDirectory(prefix="adsh-sherpa-") as tmp:
        archive = Path(tmp) / "model.tar.bz2"
        download(url, archive)
        print(f"verify sha256 {expected}")
        actual = sha256(archive)
        if actual != expected:
            raise SystemExit(f"sha256 mismatch: expected {expected}, got {actual}")
        with tarfile.open(archive, "r:bz2") as tar:
            tar.extractall(tmp, filter="data")
        source = Path(tmp) / archive_root
        for target in files:
            origin = source / target.name
            if not origin.is_file():
                raise SystemExit(f"archive is missing {origin.name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(origin, target)
    print("installed model files into", files[0].parent)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="re-download even if present")
    parser.add_argument(
        "--skip-second-pass",
        action="store_true",
        help="skip the optional 78 MiB Paraformer second-pass model",
    )
    args = parser.parse_args()

    fetch(AAR_URL, AAR_SHA256, AAR_DEST, force=args.force)

    model_dir = ASSET_ROOT / "model"
    install_model(
        ZIPFORMER_URL,
        ZIPFORMER_SHA256,
        ZIPFORMER_ROOT,
        [
            model_dir / "encoder.int8.onnx",
            model_dir / "decoder.onnx",
            model_dir / "joiner.int8.onnx",
            model_dir / "tokens.txt",
        ],
    )
    require_assets(model_dir, ["encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"])

    install_model(
        PUNCT_URL,
        PUNCT_SHA256,
        PUNCT_ROOT,
        [ASSET_ROOT / "punct/model.int8.onnx"],
    )
    require_assets(ASSET_ROOT / "punct", ["model.int8.onnx"])

    fetch(VAD_URL, VAD_SHA256, ASSET_ROOT / "vad/silero_vad.int8.onnx", force=args.force)
    require_assets(ASSET_ROOT / "vad", ["silero_vad.int8.onnx"])

    if not args.skip_second_pass:
        fetch(
            f"{PARAFORMER_BASE}/model.int8.onnx",
            PARAFORMER_MODEL_SHA256,
            ASSET_ROOT / "paraformer/model.int8.onnx",
            force=args.force,
        )
        fetch(
            f"{PARAFORMER_BASE}/tokens.txt",
            PARAFORMER_TOKENS_SHA256,
            ASSET_ROOT / "paraformer/tokens.txt",
            force=args.force,
        )
        require_assets(ASSET_ROOT / "paraformer", ["model.int8.onnx", "tokens.txt"])

    if not HOTWORDS.is_file():
        HOTWORDS.write_text(DEFAULT_HOTWORDS, encoding="utf-8")
        print(f"wrote default hotwords: {HOTWORDS}")

    total = sum(path.stat().st_size for path in ASSET_ROOT.rglob("*") if path.is_file())
    print(f"speech assets ready: {total / 1024 / 1024:.1f} MiB in {ASSET_ROOT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
