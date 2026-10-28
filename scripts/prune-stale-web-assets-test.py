#!/usr/bin/env python3
import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("prune", Path(__file__).with_name("prune-stale-web-assets.py"))
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)

class PruneTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); root = Path(self.tmp.name)
        self.source = root / "dist"; self.target = root / "public"; self.receipt = root / "plan.json"
        for folder in (self.source, self.target):
            (folder / "assets").mkdir(parents=True); (folder / "index.html").write_text("current", encoding="utf-8")
            (folder / "assets" / "index-ABCDEFGH.js").write_text("current", encoding="utf-8")
        (self.target / "assets" / "index-OLDHASH1.js").write_text("old", encoding="utf-8")
        (self.target / "assets" / "custom.js").write_text("keep", encoding="utf-8")
    def tearDown(self): self.tmp.cleanup()
    def test_only_stale_hashed_files_removed_and_plan_preserved(self):
        with contextlib.redirect_stdout(io.StringIO()): module.run(self.source, self.target, self.receipt, True)
        self.assertFalse((self.target / "assets/index-OLDHASH1.js").exists())
        self.assertTrue((self.target / "assets/index-ABCDEFGH.js").exists())
        self.assertTrue((self.target / "assets/custom.js").exists())
        self.assertEqual(json.loads(self.receipt.read_text(encoding="utf-8"))["stale"][0]["name"], "index-OLDHASH1.js")
    def test_mismatched_index_or_asset_does_not_delete(self):
        (self.target / "index.html").write_text("different", encoding="utf-8")
        with self.assertRaises(ValueError): module.run(self.source, self.target, self.receipt, True)
        self.assertTrue((self.target / "assets/index-OLDHASH1.js").exists())
        self.assertFalse(self.receipt.exists())
    def test_receipt_cannot_overwrite(self):
        self.receipt.write_text("keep", encoding="utf-8")
        with self.assertRaises(FileExistsError): module.run(self.source, self.target, self.receipt, True)
        self.assertTrue((self.target / "assets/index-OLDHASH1.js").exists())
        self.assertEqual(self.receipt.read_text(encoding="utf-8"), "keep")

if __name__ == "__main__": unittest.main()
