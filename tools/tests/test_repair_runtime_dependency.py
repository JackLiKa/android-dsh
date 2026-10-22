import tempfile
import unittest
from pathlib import Path
import zipfile

from tools.repair_runtime_dependency import RESTRUCTURE_ROOT, repair, sha256


class RepairRuntimeDependencyTest(unittest.TestCase):
    def test_publishes_missing_source_without_replacing_original(self):
        with tempfile.TemporaryDirectory() as workspace:
            root = Path(workspace)
            package = root / "restructure"
            (package / "src").mkdir(parents=True)
            for name in ("index.js", "package.json", "LICENSE"):
                (package / name).write_text(name)
            (package / "src" / "EncodeStream.js").write_text("export class EncodeStream {}")
            source = root / "original.zip"
            with zipfile.ZipFile(source, "w") as bundle:
                for name in ("index.js", "package.json", "LICENSE"):
                    bundle.write(package / name, RESTRUCTURE_ROOT + name)
            before = sha256(source)
            output = root / "successor.zip"
            successor = repair(source, package, output, before)

            self.assertEqual(before, sha256(source))
            self.assertEqual(successor, sha256(output))
            with zipfile.ZipFile(output) as bundle:
                self.assertIn(RESTRUCTURE_ROOT + "src/EncodeStream.js", bundle.namelist())

    def test_rejects_source_drift(self):
        with tempfile.TemporaryDirectory() as workspace:
            source = Path(workspace) / "source.zip"
            source.write_bytes(b"drift")
            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                repair(source, Path(workspace), Path(workspace) / "out.zip", "0" * 64)


if __name__ == "__main__":
    unittest.main()
