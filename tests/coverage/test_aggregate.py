"""Verify the coverage parser with independent, fabricated XML fixtures."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

SRC = Path(__file__).with_name("aggregate.py")
spec = importlib.util.spec_from_file_location("bioserver_coverage_aggregate", SRC)
assert spec is not None and spec.loader is not None
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class CoverageSummaryTest(unittest.TestCase):
    def test_cobertura_rate(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cobertura.xml"
            path.write_text('<coverage line-rate="0.625" />')
            self.assertEqual("62.5%", mod.percent(path, "cobertura"))

    def test_cobertura_uses_counts_when_available(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cobertura.xml"
            path.write_text('<coverage lines-covered="3" lines-valid="4" line-rate="0.5" />')
            self.assertEqual("75.0%", mod.percent(path, "cobertura"))

    def test_jacoco_line_counter(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "jacoco.xml"
            path.write_text('<report name="bio"><counter type="LINE" missed="3" covered="7"/></report>')
            self.assertEqual("70.0%", mod.percent(path, "jacoco"))

    def test_missing_artifacts_are_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            report = mod.render(Path(tmp))
            self.assertIn("Not generated", report)
            self.assertIn("no cross-language average", report)


if __name__ == "__main__":
    unittest.main()
