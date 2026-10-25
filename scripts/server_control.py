"""Hidden local Paper process with a filesystem console queue; no RCON/network control."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid
from checkpoint import finalize_checkpoint

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "run/server"
STATE = SERVER / "control.json"
QUEUE = SERVER / "console.jsonl"
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def alive(pid):
    if os.name == "nt":
        import ctypes
        handle = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
        if not handle:
            return False
        code = ctypes.c_ulong()
        ok = ctypes.windll.kernel32.GetExitCodeProcess(handle, ctypes.byref(code))
        ctypes.windll.kernel32.CloseHandle(handle)
        return bool(ok and code.value == 259)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def state():
    if STATE.exists():
        try:
            value = json.loads(STATE.read_text(encoding="utf-8"))
        except (ValueError, OSError):
            return None  # The worker may be publishing a new state during startup.
        if alive(value["worker_pid"]) and alive(value["java_pid"]):
            return value
    return None


def serve():
    token = uuid.uuid4().hex
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe")
    with (SERVER / "console.log").open("a", encoding="utf-8") as log:
        process = subprocess.Popen([java, "-Xms512M", "-Xmx2G", "-jar", "paper.jar", "--nogui"], cwd=SERVER,
                                   stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", creationflags=FLAGS)
        STATE.write_text(json.dumps({"worker_pid": os.getpid(), "java_pid": process.pid, "token": token, "address": "127.0.0.1:25565"}), encoding="utf-8")
        with QUEUE.open("a+", encoding="utf-8") as queue:
            queue.seek(0, 2)
            while process.poll() is None:
                line = queue.readline()
                if not line:
                    time.sleep(0.15)
                    continue
                try:
                    message = json.loads(line)
                    if message.get("token") != token:
                        continue
                    command = message["command"]
                    if "\n" in command or "\r" in command:
                        continue
                    process.stdin.write(command + "\n")
                    process.stdin.flush()
                except (ValueError, KeyError, BrokenPipeError):
                    continue
        # Retain state and logs as evidence; status checks the actual processes.
        finalize_checkpoint(SERVER / "plugins/CloudStorage/storage.sqlite", process.returncode)
        print("Paper exited with code", process.returncode)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["start", "status", "stop", "console", "_serve"])
    parser.add_argument("command", nargs="*")
    args = parser.parse_args()
    if args.action == "_serve":
        serve()
        return
    running = state()
    if args.action == "status":
        public_state = {k: v for k, v in running.items() if k != "token"} if running else {"running": False}
        print(json.dumps(public_state, ensure_ascii=False))
    elif args.action == "start":
        if running:
            print("Already running at", running["address"])
            return
        subprocess.run(["pwsh", "-NoProfile", "-File", str(ROOT / "scripts/server.ps1"), "-Action", "Prepare"], cwd=ROOT, check=True)
        if "eula=true" not in (SERVER / "eula.txt").read_text(encoding="utf-8-sig"):
            raise RuntimeError("Minecraft EULA must be explicitly accepted")
        plugin = ROOT / "target/cloud-storage-0.2.3.jar"
        if not plugin.exists():
            raise RuntimeError("Run pixi run build first")
        (SERVER / "plugins").mkdir(exist_ok=True)
        shutil.copy2(plugin, SERVER / "plugins/CloudStorage.jar")
        with (SERVER / "worker.log").open("a", encoding="utf-8") as log:
            subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "_serve"], cwd=ROOT,
                             stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, creationflags=FLAGS)
        for _ in range(100):
            if state():
                print("Started local Paper; connect after logs show Done: 127.0.0.1:25565")
                return
            time.sleep(0.1)
        raise RuntimeError("Worker did not start; inspect run/server/worker.log")
    else:
        if not running:
            raise RuntimeError("Local managed server is not running")
        command = "stop" if args.action == "stop" else " ".join(args.command)
        if not command or "\n" in command or "\r" in command:
            raise ValueError("Specify one console command")
        with QUEUE.open("a", encoding="utf-8") as queue:
            queue.write(json.dumps({"token": running["token"], "command": command}) + "\n")
        print("Queued:", command)
        if args.action == "stop":
            for _ in range(300):
                if not alive(running["worker_pid"]) and not alive(running["java_pid"]):
                    print("Local Paper stopped")
                    return
                time.sleep(0.1)
            raise RuntimeError("Stop still pending; inspect console.log (no process was force-killed)")


if __name__ == "__main__":
    main()
