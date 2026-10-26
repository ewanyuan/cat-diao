"""Choosing a nest copies missing files and never deletes user data."""
import importlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest

os.environ.setdefault("CATDIAO_NEST_HOME", "/tmp/catdiao-nest-import")
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cat-diao" / "scripts"))
nest_paths = importlib.import_module("nest_paths")


class NestPathChecks(unittest.TestCase):
    def test_env_beats_config_and_config_beats_repo(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            user = root / "user"
            repo = root / "repo"
            (repo / "data").mkdir(parents=True)
            (repo / ".git").mkdir()
            chosen = root / "chosen"
            chosen.mkdir()
            configured = root / "configured"
            configured.mkdir()
            (user / ".catdiao").mkdir(parents=True)
            (user / ".catdiao" / "config.json").write_text(
                json.dumps({"nest_dir": str(configured)}), encoding="utf-8")
            self.assertEqual(nest_paths.resolve_home({}, user, repo, root / "appdata"), configured)
            self.assertEqual(
                nest_paths.resolve_home({"CATDIAO_NEST_HOME": str(chosen)}, user, repo, root),
                chosen)
            empty_user = root / "empty-user"
            empty_user.mkdir()
            self.assertEqual(
                nest_paths.resolve_home({}, empty_user, repo, root / "appdata"),
                (repo / "data").resolve())

    def test_migrate_copies_missing_files_and_does_not_delete_or_overwrite(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            old = root / "old"
            (old / "收藏台账").mkdir(parents=True)
            (old / "connection.json").write_text('{"token":"old"}', encoding="utf-8")
            (old / "收藏台账" / "note.txt").write_text("keep", encoding="utf-8")
            dest = root / "nest"
            dest.mkdir()
            (dest / "connection.json").write_text('{"token":"kept"}', encoding="utf-8")
            copied = nest_paths.migrate_into(dest, [old], force=True)
            self.assertIn("收藏台账/note.txt", copied)
            self.assertEqual((dest / "connection.json").read_text(encoding="utf-8"), '{"token":"kept"}')
            self.assertTrue((old / "connection.json").is_file())
            self.assertTrue((old / "收藏台账" / "note.txt").is_file())
            occupied = root / "occupied"
            occupied.mkdir()
            (occupied / "collector.env").write_text("A=1\n", encoding="utf-8")
            self.assertEqual(nest_paths.migrate_into(occupied, [old], force=False), [])
            self.assertFalse((occupied / "connection.json").exists())


if __name__ == "__main__":
    unittest.main()
