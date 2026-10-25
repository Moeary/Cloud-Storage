"""Read-only SQLite audit and consistent online backup. Does not resolve or replay transfers."""
import argparse
from contextlib import closing
import base64
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--database", type=Path, default=ROOT / "run/server/plugins/CloudStorage/storage.sqlite")
parser.add_argument("--journal", help="Full journal UUID; include base64 before/after inventory bytes")
parser.add_argument("--backup", type=Path, help="New backup file, never overwrite an existing one")
args = parser.parse_args()
with closing(sqlite3.connect(args.database.resolve().as_uri() + "?mode=ro", uri=True)) as db:
    db.row_factory = sqlite3.Row
    if args.backup:
        if args.backup.exists():
            raise SystemExit("Backup target already exists")
        args.backup.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(args.backup)) as destination:
            db.backup(destination)
        print("Consistent SQLite backup:", args.backup.resolve())
    else:
        result = {
            "inspected_utc": datetime.now(timezone.utc).isoformat(),
            "integrity": db.execute("PRAGMA quick_check").fetchone()[0],
            "meta": [dict(r) for r in db.execute("SELECT * FROM meta")],
            "vaults": [dict(r) for r in db.execute("SELECT vault,count(*) types,sum(amount) amount FROM stock GROUP BY vault")],
            "journal_states": [dict(r) for r in db.execute("SELECT state,count(*) n FROM journal GROUP BY state")],
            "pending": [dict(r) for r in db.execute("SELECT id,vault,key,delta,endpoint,created FROM journal WHERE state='PENDING' ORDER BY created")],
        }
        if args.journal:
            row = db.execute("SELECT * FROM journal WHERE id=?", (args.journal,)).fetchone()
            if row is None:
                raise SystemExit("Journal entry not found")
            result["operation"] = {k: base64.b64encode(v).decode() if isinstance(v, bytes) else v for k, v in dict(row).items()}
        print(json.dumps(result, ensure_ascii=False, indent=2))
