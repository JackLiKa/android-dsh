#!/usr/bin/env python3
"""Restore one published npm package's omitted sources in a pinned runtime ZIP."""

import argparse
import hashlib
from pathlib import Path
import zipfile

RESTRUCTURE_ROOT = (
    "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/restructure/"
)
REFERENCE_SHA256 = (
    "975146516216ced02299676689c1731c7f390962389dbef56821cd59a3bf69ab"
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def repair(source: Path, package: Path, output: Path, expected_sha: str) -> str:
    if sha256(source) != expected_sha:
        raise ValueError(f"source ZIP checksum mismatch: {source}")
    with zipfile.ZipFile(source) as original:
        for name in ("index.js", "package.json", "LICENSE"):
            if original.read(RESTRUCTURE_ROOT + name) != (package / name).read_bytes():
                raise ValueError(f"installed restructure package differs at {name}")
        missing = sorted((package / "src").rglob("*.js"))
        if not missing:
            raise ValueError("restructure source files are unavailable")
        known = set(original.namelist())
        additions = [(RESTRUCTURE_ROOT + path.relative_to(package).as_posix(), path)
                     for path in missing]
        if any(name in known for name, _ in additions):
            raise ValueError("runtime already contains restructure source files")
        with output.open("xb") as result:
            with zipfile.ZipFile(result, "w", allowZip64=True) as fixed:
                for info in original.infolist():
                    fixed.writestr(info, original.read(info))
                for name, path in additions:
                    fixed.write(path, name, compress_type=zipfile.ZIP_DEFLATED)
    return sha256(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expected-sha", default=REFERENCE_SHA256)
    args = parser.parse_args()
    print(repair(args.source, args.package, args.output, args.expected_sha))


if __name__ == "__main__":
    main()
