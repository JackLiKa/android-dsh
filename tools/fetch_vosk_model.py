#!/usr/bin/env python3
"""Fetch and install the bundled Vosk Chinese speech model for adsh.

The model is not committed (65 MiB); run this once after cloning, before building an
APK that includes offline speech recognition. Without it the app falls back to the
system SpeechRecognizer if one exists.

Usage:
    python3 tools/fetch_vosk_model.py
    python3 tools/fetch_vosk_model.py --force
    python3 tools/fetch_vosk_model.py --keep-zip
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
MODEL_SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"
MODEL_ARCHIVE_ROOT = "vosk-model-small-cn-0.22"
TARGET = Path(__file__).resolve().parent.parent / "app/src/main/assets/vosk/model-cn"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path) -> None:
    print(f"download {url}")
    with urllib.request.urlopen(url, timeout=120) as response, destination.open("wb") as out:
        shutil.copyfileobj(response, out)


def install(archive: Path, target: Path, force: bool) -> None:
    if target.is_dir() and not force and (target / "am" / "final.mdl").is_file():
        print(f"model already installed: {target}")
        return
    staging = Path(tempfile.mkdtemp(prefix="vosk-model-", dir=str(target.parent)))
    try:
        with zipfile.ZipFile(archive) as zf:
            bad = zf.testzip()
            if bad is not None:
                raise SystemExit(f"corrupt model archive entry: {bad}")
            zf.extractall(staging)
        root = staging / MODEL_ARCHIVE_ROOT
        payload = root if root.is_dir() else staging
        if not (payload / "am" / "final.mdl").is_file():
            raise SystemExit("downloaded model does not contain am/final.mdl")
        uuid_file = payload / "uuid"
        if not uuid_file.is_file():
            import uuid

            uuid_file.write_text(str(uuid.uuid4()), encoding="utf-8")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.rmtree(target, ignore_errors=True)
        shutil.move(str(payload), str(target))
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    print(f"installed model: {target}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="reinstall even if present")
    parser.add_argument("--keep-zip", action="store_true", help="keep the downloaded archive")
    parser.add_argument("--archive", type=Path, help="use an existing model zip instead of downloading")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="adsh-vosk-") as tmp:
        archive = args.archive or Path(tmp) / "vosk-model-small-cn-0.22.zip"
        if not args.archive:
            download(MODEL_URL, archive)
        print(f"verify sha256 {MODEL_SHA256}")
        actual = sha256(archive)
        if actual != MODEL_SHA256:
            raise SystemExit(f"sha256 mismatch: expected {MODEL_SHA256}, got {actual}")
        install(archive, TARGET, force=args.force)
        if args.keep_zip and not args.archive:
            kept = Path(__file__).resolve().parent.parent / "runtime" / "vosk-model-small-cn-0.22.zip"
            kept.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(archive, kept)
            print(f"kept archive: {kept}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
