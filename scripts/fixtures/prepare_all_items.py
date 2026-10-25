"""Prepare the stopped LOCAL manual server for explicit all-item client testing."""
from contextlib import closing
from datetime import datetime
from pathlib import Path
import os
import re
import shutil
import sqlite3
import subprocess

ROOT=Path(__file__).resolve().parents[2]
SERVER=ROOT / "run/server"
DATABASE=SERVER / "plugins/CloudStorage/storage.sqlite"
with closing(sqlite3.connect(DATABASE)) as db:
    assert db.execute("SELECT value FROM meta WHERE key='active'").fetchone()[0]=="false", "Stop the server cleanly before configuring fixtures"
    assert db.execute("SELECT count(*) FROM journal WHERE state='PENDING'").fetchone()[0]==0, "Audit pending transfers first"

backup=ROOT / "run/backups" / ("all-items-config-"+datetime.now().strftime("%Y%m%d-%H%M%S"))
backup.mkdir(parents=True)
for relative in ("server.properties","permissions.yml","plugins/CloudStorage/config.yml"):
    source=SERVER / relative
    target=backup / relative
    target.parent.mkdir(parents=True,exist_ok=True)
    if source.exists(): shutil.copy2(source,target)

properties=SERVER / "server.properties"
text=properties.read_text(encoding="utf-8-sig")
for key,value in {"gamemode":"survival","force-gamemode":"true","difficulty":"peaceful"}.items():
    text=re.sub(r"(?m)^"+re.escape(key)+r"=.*$",key+"="+value,text)
properties.write_text(text,encoding="utf-8")

config=SERVER / "plugins/CloudStorage/config.yml"
text=config.read_text(encoding="utf-8-sig")
text=re.sub(r"(?m)^default-mode:.*$","default-mode: shared",text)
text=re.sub(r"(?m)^  blocked-materials:.*$","  blocked-materials: []",text)
config.write_text(text,encoding="utf-8")

permissions=SERVER / "permissions.yml"
text=permissions.read_text(encoding="utf-8-sig") if permissions.exists() else ""
if "cloudstorage.local-test:" not in text:
    text+="""
# Explicit loopback-only manual test permissions. No operator/creative grant.
cloudstorage.local-test:
  default: true
  children:
    cloudstorage.use: true
    cloudstorage.shared: true
    cloudstorage.port: true
    cloudstorage.printer: true
"""
permissions.write_text(text,encoding="utf-8")

output=ROOT / "run/fixtures/all-items-helper"
classes=output / "classes"
classes.mkdir(parents=True,exist_ok=True)
classpath=(ROOT / "target/fixture-classpath.txt").read_text(encoding="utf-8").strip()
classpath=str(ROOT / "target/classes")+os.pathsep+classpath
java=Path(os.environ["JAVA_HOME"]) / "bin"
source=Path(__file__).with_name("SeedSharedItems.java")
subprocess.run([str(java / "javac.exe"),"--release","21","-encoding","UTF-8","-proc:none","-cp",classpath,"-d",str(classes),str(source)],check=True)
jar=output / "CloudStorageFixture.jar"
subprocess.run([str(java / "jar.exe"),"--create","--file",str(jar),"-C",str(classes),".","-C",str(source.parent),"plugin.yml"],check=True)
shutil.copy2(jar,SERVER / "plugins/CloudStorageFixture.jar")
print("Prepared local survival/peaceful shared-vault fixture; configuration backup:",backup)
