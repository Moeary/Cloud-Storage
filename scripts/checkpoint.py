"""Finalize a clean checkpoint ONLY after the actual JVM exited with status zero.

Paper flushes world I/O after plugin disable, so the plugin must not clear its dirty marker there.
"""
from pathlib import Path
from contextlib import closing
import sqlite3
import sys


def finalize_checkpoint(database: Path, exit_code: int) -> bool:
    if exit_code != 0 or not database.exists():
        return False
    try:
        with closing(sqlite3.connect(database)) as db, db:
            db.execute("PRAGMA synchronous=FULL")
            if db.execute("PRAGMA user_version").fetchone()[0] != 1:
                return False
            meta = dict(db.execute("SELECT key,value FROM meta"))
            if not meta.get("session") or meta.get("shutdown_ready") != meta["session"]:
                return False  # Quarantine, failed initialization, hot-disable, or no shutdown acknowledgement.
            if db.execute("SELECT count(*) FROM journal WHERE state='PENDING'").fetchone()[0]:
                return False
            db.execute("UPDATE meta SET value='false' WHERE key='active'")
            db.execute("UPDATE meta SET value='' WHERE key='shutdown_ready'")
        return True
    except sqlite3.Error as error:
        print("Checkpoint left dirty:", error, file=sys.stderr)
        return False


if __name__ == "__main__":
    result = finalize_checkpoint(Path(sys.argv[1]), int(sys.argv[2]))
    print("Cloud checkpoint finalized" if result else "Cloud checkpoint retained (audit may be required)")
