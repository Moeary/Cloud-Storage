"""Real Paper process + protocol clients. Never connects to the user's manual server."""
from __future__ import annotations
from contextlib import closing

import json
import os
from pathlib import Path
import queue
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
from checkpoint import finalize_checkpoint

ROOT = Path(__file__).resolve().parents[1]
RUN = ROOT / "run" / "integration" / time.strftime("%Y%m%d-%H%M%S")
PORT = 25566
CREATE_FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


class Server:
    def __init__(self):
        self.process = None
        self.lines = queue.Queue()
        self.log = None

    def start(self):
        self.lines = queue.Queue()
        self.log = (RUN / f"process-{time.time_ns()}.log").open("w", encoding="utf-8")
        self.process = subprocess.Popen(
            [str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe"), "-Xms512M", "-Xmx2G", "-jar", "paper.jar", "--nogui"],
            cwd=RUN, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", creationflags=CREATE_FLAGS,
        )

        def reader():
            for line in self.process.stdout:
                self.log.write(line)
                self.log.flush()
                self.lines.put(line)
        self.reader = threading.Thread(target=reader, daemon=True)
        self.reader.start()
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("Paper exited before ready. See " + str(RUN))
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                continue
            if "Done (" in line:
                self.command("gamerule sendCommandFeedback false")
                self.command("gamerule doDaylightCycle false")
                self.command("gamerule doMobSpawning false")
                print("Paper 1.21.1 ready", flush=True)
                return
        raise TimeoutError("Paper startup timeout")

    def command(self, command):
        if "\n" in command or "\r" in command:
            raise ValueError("Multiline console command rejected")
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def stop(self, crash=False):
        if self.process is None:
            return
        if self.process.poll() is None:
            if crash:
                self.process.kill()  # Only our disposable, tracked test child.
            else:
                self.command("stop")
            try:
                self.process.wait(timeout=45)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
                raise RuntimeError("Paper did not stop cleanly")
        self.reader.join(timeout=3)
        self.log.close()
        if not crash:
            finalize_checkpoint(RUN / "plugins/CloudStorage/storage.sqlite", self.process.returncode)
        self.process = None


def clients(server, phase):
    env = dict(os.environ, CLOUD_TEST_PORT=str(PORT), CLOUD_TEST_PHASE=phase)
    proc = subprocess.Popen(
        [shutil.which("node"), "paper-client.cjs"], cwd=ROOT / "tests", env=env,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=None,
        text=True, encoding="utf-8", creationflags=CREATE_FLAGS,
    )
    failures = []
    try:
        for line in proc.stdout:
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                print(line.rstrip(), flush=True)
                continue
            if "console" in message:
                server.command(message["console"])
                response = {"id": message["id"], "result": True}
            elif "sql" in message:
                with closing(sqlite3.connect(f"file:{RUN / 'plugins/CloudStorage/storage.sqlite'}?mode=ro", uri=True)) as conn:
                    conn.row_factory = sqlite3.Row
                    response = {"id": message["id"], "result": [dict(r) for r in conn.execute(message["sql"], message.get("params", []))]}
            else:
                print(json.dumps(message, ensure_ascii=False), flush=True)
                if message.get("status") == "fail":
                    failures.append(message)
                continue
            proc.stdin.write(json.dumps(response) + "\n")
            proc.stdin.flush()
        if proc.wait(timeout=20) != 0 or failures:
            raise RuntimeError("Protocol integration failed: " + json.dumps(failures))
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait(timeout=10)


def main():
    eula = ROOT / "run/server/eula.txt"
    if not eula.exists() or "eula=true" not in eula.read_text(encoding="utf-8-sig"):
        raise RuntimeError("Explicit EULA acceptance is required: pixi run server-prepare -AcceptEula")
    plugin = ROOT / "target/cloud-storage-0.2.0-SNAPSHOT.jar"
    if not plugin.exists():
        raise RuntimeError("Run pixi run build first")
    npm_cli = ROOT / ".pixi/envs/default/node_modules/npm/bin/npm-cli.js"
    subprocess.run([shutil.which("node"), str(npm_cli), "ci", "--ignore-scripts", "--no-audit", "--no-fund"], cwd=ROOT / "tests", check=True)
    RUN.mkdir(parents=True)
    (RUN / "plugins").mkdir()
    shutil.copy2(plugin, RUN / "plugins/CloudStorage.jar")
    placement = os.environ.get("CLOUD_TEST_PLACEMENT") == "1"
    if placement:
        fixture = ROOT / ".cache/placement-protocol"
        for name in ("ProtocolLib.jar", "PaperAccurateBlockPlacement-1.2.0.jar"):
            shutil.copy2(fixture / name, RUN / "plugins" / name)
        (RUN / "plugins/PaperAccurateBlockPlacement").mkdir()
        shutil.copy2(fixture / "config.yml", RUN / "plugins/PaperAccurateBlockPlacement/config.yml")
    shutil.copy2(ROOT / "run/server/paper.jar", RUN / "paper.jar")
    shutil.copy2(eula, RUN / "eula.txt")
    # Reuse only immutable downloaded server artifacts; each world/database is fresh.
    for name in ("cache", "libraries", "versions"):
        source = ROOT / "run/server" / name
        if source.exists():
            shutil.copytree(source, RUN / name)
    properties = (ROOT / "run/server/server.properties").read_text(encoding="utf-8-sig")
    properties = properties.replace("server-port=25565", f"server-port={PORT}").replace("online-mode=true", "online-mode=false")
    (RUN / "server.properties").write_text(properties, encoding="utf-8")
    server = Server()
    started = time.time()
    print("Disposable server: " + str(RUN), flush=True)
    try:
        server.start()
        clients(server, "main")
        server.stop()
        server.start()
        clients(server, "restart")
        server.stop(crash=True)
        server.start()
        clients(server, "crash")
        server.stop()
        with closing(sqlite3.connect(RUN / "plugins/CloudStorage/storage.sqlite")) as conn, conn:
            conn.execute("PRAGMA user_version=99")  # Disposable fixture: exercise fail-closed initialization.
        server.start()
        clients(server, "guard")
        server.stop()
        evidence = {"status": "passed", "paper": "1.21.1-133", "run": str(RUN), "elapsed_seconds": round(time.time()-started, 1), "phases": ["main", "clean-restart", "forced-stop-recovery", "initialization-guard"], "fabric_client": "not tested"}
        evidence["placement_protocol"] = "PaperAccurateBlockPlacement 1.2.0 / ProtocolLib 5.4.0" if placement else "not installed"
        (RUN / "result.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
        print(json.dumps(evidence), flush=True)
    finally:
        server.stop()


if __name__ == "__main__":
    main()
