import importlib.util
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("checkpoint", Path(__file__).resolve().parents[1] / "scripts/checkpoint.py")
checkpoint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checkpoint)


class CheckpointTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.database = Path(self.temp.name) / "storage.sqlite"
        with closing(sqlite3.connect(self.database)) as db, db:
            db.executescript("PRAGMA user_version=1; CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT); CREATE TABLE journal(state TEXT);")
            db.executemany("INSERT INTO meta VALUES (?,?)", [("active", "true"), ("session", "session-1"), ("shutdown_ready", "session-1")])

    def tearDown(self):
        self.temp.cleanup()

    def active(self):
        with closing(sqlite3.connect(self.database)) as db, db:
            return db.execute("SELECT value FROM meta WHERE key='active'").fetchone()[0]

    def test_normal_exit_and_matching_candidate_finalize(self):
        self.assertTrue(checkpoint.finalize_checkpoint(self.database, 0))
        self.assertEqual(self.active(), "false")
        self.assertFalse(checkpoint.finalize_checkpoint(self.database, 0))

    def test_killed_jvm_keeps_dirty_even_with_candidate(self):
        self.assertFalse(checkpoint.finalize_checkpoint(self.database, 1))
        self.assertEqual(self.active(), "true")

    def test_stale_candidate_cannot_clear_new_session(self):
        with closing(sqlite3.connect(self.database)) as db, db:
            db.execute("UPDATE meta SET value='session-2' WHERE key='session'")
        self.assertFalse(checkpoint.finalize_checkpoint(self.database, 0))
        self.assertEqual(self.active(), "true")

    def test_pending_transfer_cannot_be_marked_clean(self):
        with closing(sqlite3.connect(self.database)) as db, db:
            db.execute("INSERT INTO journal VALUES ('PENDING')")
        self.assertFalse(checkpoint.finalize_checkpoint(self.database, 0))
        self.assertEqual(self.active(), "true")

    def test_newer_schema_is_not_modified(self):
        with closing(sqlite3.connect(self.database)) as db, db:
            db.execute("PRAGMA user_version=99")
        self.assertFalse(checkpoint.finalize_checkpoint(self.database, 0))
        self.assertEqual(self.active(), "true")


if __name__ == "__main__":
    unittest.main()
