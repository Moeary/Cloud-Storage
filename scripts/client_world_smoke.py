"""Isolated full Fabric modpack join test. Does not use launcher credentials or the manual server."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import subprocess
import time
import zipfile
import integration

ROOT=Path(__file__).resolve().parents[1]

def metadata(path):
    try:
        with zipfile.ZipFile(path) as jar:
            result=json.loads(jar.read('fabric.mod.json'),strict=False)
            return result if isinstance(result,dict) else {}
    except (KeyError,ValueError,zipfile.BadZipFile): return {}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client',type=Path,required=True)
    parser.add_argument('--replacements',type=Path,required=True)
    parser.add_argument('--hold-seconds',type=int,default=30)
    parser.add_argument('--restock-probe',type=Path,help='Separately built mapped Fabric restock harness; disposable client only')
    parser.add_argument('--axshulkers',type=Path,help='Official AxShulkers 1.24.0 JAR; enables cloud priority/physical fallback assertions')
    args=parser.parse_args()
    client=args.client.resolve()
    manifest=args.replacements.resolve()
    replacements=json.loads(manifest.read_text(encoding='utf-8-sig'))
    for replacement in replacements:
        source=manifest.parent/replacement['file']
        if hashlib.sha256(source.read_bytes()).hexdigest()!=replacement['sha256']:
            raise RuntimeError('Replacement checksum mismatch: '+source.name)
    # Never reuse or control a process already listening at the disposable port.
    with socket.socket() as guard: guard.bind(('127.0.0.1',25566))
    run=ROOT/'run'/('client-world-'+time.strftime('%Y%m%d-%H%M%S'))
    game=run/'client'
    server_dir=run/'server'
    game.mkdir(parents=True)
    (game/'mods').mkdir()
    if args.axshulkers: (game/'cloud-coexist.enabled').touch()
    replaced={row['id'] for row in replacements}
    for jar in (client/'mods').glob('*.jar'):
        if metadata(jar).get('id') not in replaced: shutil.copy2(jar,game/'mods'/jar.name)
    for row in replacements: shutil.copy2(manifest.parent/row['file'],game/'mods'/row['file'])
    if args.restock_probe: shutil.copy2(args.restock_probe,game/'mods/cloud-printer-test.jar')
    for folder in ('config','resourcepacks','shaderpacks'):
        if (client/folder).exists(): shutil.copytree(client/folder,game/folder)
    if (client/'options.txt').exists(): shutil.copy2(client/'options.txt',game/'options.txt')
    server_dir.mkdir()
    manual=ROOT/'run/server'
    for name in ('cache','libraries','versions'): shutil.copytree(manual/name,server_dir/name)
    for name in ('paper.jar','eula.txt'): shutil.copy2(manual/name,server_dir/name)
    properties=(manual/'server.properties').read_text(encoding='utf-8-sig')
    properties=properties.replace('server-port=25565','server-port=25566').replace('online-mode=true','online-mode=false')
    if 'server-ip=127.0.0.1' not in properties: raise RuntimeError('Test must stay loopback-only')
    (server_dir/'server.properties').write_text(properties,encoding='utf-8')
    (server_dir/'plugins').mkdir()
    for name in ('CloudStorage.jar','ProtocolLib.jar','PaperAccurateBlockPlacement-1.2.0.jar'):
        shutil.copy2(manual/'plugins'/name,server_dir/'plugins'/name)
    # Test the candidate plugin without deploying it to the user's manual server first.
    shutil.copy2(ROOT/'target/cloud-storage-0.2.3.jar',server_dir/'plugins/CloudStorage.jar')
    if args.axshulkers:
        shutil.copy2(args.axshulkers,server_dir/'plugins/AxShulkers.jar')
        (server_dir/'plugins/AxShulkers').mkdir()
        with zipfile.ZipFile(args.axshulkers) as jar: ax_config=jar.read('config.yml').decode()
        (server_dir/'plugins/AxShulkers/config.yml').write_text(ax_config.replace('type: "h2"','type: "sqlite"'),encoding='utf-8')
        prior=sorted((ROOT/'run').glob('shulker-coexist-*/libraries'))
        if prior: shutil.copytree(prior[-1],server_dir/'libraries',dirs_exist_ok=True)
    (server_dir/'plugins/PaperAccurateBlockPlacement').mkdir()
    shutil.copy2(manual/'plugins/PaperAccurateBlockPlacement/config.yml',server_dir/'plugins/PaperAccurateBlockPlacement/config.yml')
    version=json.loads((client/(client.name+'.json')).read_text(encoding='utf-8-sig'))
    libraries=client.parents[1]/'libraries'
    classpath=[]
    for library in version['libraries']:
        rules=library.get('rules',[])
        allowed=not rules
        for rule in rules:
            if rule.get('os',{}).get('name','windows')=='windows' and not rule.get('features'):
                allowed=rule['action']=='allow'
        if not allowed: continue
        relative=library.get('downloads',{}).get('artifact',{}).get('path')
        if not relative:
            group,name,ver,*classifier=library['name'].split(':')
            relative=group.replace('.','/')+'/'+name+'/'+ver+'/'+name+'-'+ver+('-'+classifier[0] if classifier else '')+'.jar'
        path=libraries/relative
        if not path.exists(): raise RuntimeError('Missing library '+str(path))
        classpath.append(str(path))
    classpath.append(str(client/(client.name+'.jar')))
    probe_classes=run/'probe-classes'
    probe_classes.mkdir()
    subprocess.run([str(Path(os.environ['JAVA_HOME'])/'bin/javac.exe'),'--release','21','-proc:none','-cp',os.pathsep.join(classpath),
                    '-d',str(probe_classes),str(ROOT/'tests/fabric-probe/DependencyProbe.java')],check=True)
    with zipfile.ZipFile(game/'mods/cloud-dependency-probe.jar','w') as jar:
        jar.write(ROOT/'tests/fabric-probe/fabric.mod.json','fabric.mod.json')
        for source in probe_classes.rglob('*.class'): jar.write(source,str(source.relative_to(probe_classes)).replace('\\','/'))
    natives=client/(client.name+'-natives')
    java_home=os.environ['JAVA_HOME']
    cmd=[str(Path(java_home)/'bin/java.exe'),'-Xmx3G','-Dmixin.debug.export=true',
         '-Djava.library.path='+str(natives),'-Djna.tmpdir='+str(natives),
         '-Dorg.lwjgl.system.SharedLibraryExtractPath='+str(natives),'-cp',os.pathsep.join(classpath),version['mainClass'],
         '--username','CloudSmoke','--version',version['id'],'--gameDir',str(game),
         '--assetsDir',str(client.parents[1]/'assets'),'--assetIndex',version['assetIndex']['id'],
         '--uuid','00000000000000000000000000000001','--accessToken','0','--userType','legacy',
         '--versionType','isolated-world-test','--width','854','--height','480',
         '--quickPlayMultiplayer','127.0.0.1:25566','--quickPlayPath',str(game/'quickplay.json')]
    integration.RUN=server_dir
    server=integration.Server()
    process=None
    result={'status':'failed','run':str(run),'client':str(client),'replacements':replacements,'port':25566,'manual_server_touched':False,'client_java_home':java_home}
    print('Isolated Fabric world test: '+str(run),flush=True)
    started=time.monotonic()
    log_path=game/'stdout.log'
    try:
        # Paper retains Java 21; the copied Fabric modpack uses its separate pixi Java 23 environment.
        os.environ['JAVA_HOME']=str(ROOT/'.pixi/envs/default/Library/lib/jvm')
        try: server.start()
        finally: os.environ['JAVA_HOME']=java_home
        with log_path.open('w',encoding='utf-8') as log:
            process=subprocess.Popen(cmd,cwd=game,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
            joined_at=None
            filled=False
            while time.monotonic()-started<210:
                text=log_path.read_text(encoding='utf-8',errors='replace')
                if re.search(r'Incompatible mods found!|Non-unique Mixin config name|InjectionError|Mixin transformation of .+ failed|Unreported exception thrown!|Minecraft has crashed!|CLOUD_DEPENDENCY_PROBE: failed',text):
                    raise RuntimeError('Client failed; see '+str(log_path))
                if process.poll() is not None: raise RuntimeError('Client exited: '+str(process.returncode))
                server_text=(server_dir/'logs/latest.log').read_text(encoding='utf-8',errors='replace')
                if 'CloudSmoke joined the game' in server_text and joined_at is None:
                    joined_at=time.monotonic()
                    print('Fabric client joined isolated Paper; holding for world ticks',flush=True)
                    if args.restock_probe:
                        server.command('give CloudSmoke minecraft:stone 64')
                        server.command('give CloudSmoke minecraft:red_concrete 64')
                        if args.axshulkers:
                            server.command('item replace entity CloudSmoke inventory.0 with minecraft:blue_shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:stone",count:32}},{slot:1,item:{id:"minecraft:gravel",count:16}}]]')
                if joined_at is not None and 'CloudSmoke lost connection' in server_text:
                    raise RuntimeError('Client disconnected before test completion')
                exported=game/'.mixin.out/class/fi/dy/masa/litematica/util'
                targets={name:(exported/(name+'.class')).is_file() for name in ('WorldUtils','EasyPlaceUtils')}
                probe_path=game/'dependency-probe.json'
                probe=json.loads(probe_path.read_text()) if probe_path.exists() else {}
                if args.restock_probe and (game/'cloud-fixture.json').exists() and not filled:
                    fixture=json.loads((game/'cloud-fixture.json').read_text())
                    cloud_slot=fixture['cloud_slot']-9
                    assert 0<=cloud_slot<27
                    # Replace disposable fixtures only; preserve the actual UUID-bound cloud shell.
                    for slot in range(27):
                        if slot!=cloud_slot: server.command(f'item replace entity CloudSmoke inventory.{slot} with minecraft:cobblestone 64')
                    for slot in range(9): server.command(f'item replace entity CloudSmoke hotbar.{slot} with minecraft:oak_planks 64')
                    filled=True
                restock_path=game/'cloud-restock.json'
                restock=json.loads(restock_path.read_text()) if restock_path.exists() else {}
                if restock.get('status')=='failed': raise RuntimeError('Restock probe failed; see '+str(log_path))
                restock_ready=not args.restock_probe or restock.get('status')=='passed'
                if joined_at is not None and probe.get('status')=='passed' and all(targets.values()) and restock_ready and time.monotonic()-joined_at>=args.hold_seconds:
                    result.update(status='passed',world_joined=True,world_hold_seconds=round(time.monotonic()-joined_at,1),transformed_classes=targets,probe=probe)
                    if args.restock_probe:
                        if any(row.get('name')=='CloudSmoke' for row in json.loads((server_dir/'ops.json').read_text())):
                            raise RuntimeError('Restock must be tested as a non-OP player')
                        with sqlite3.connect(server_dir/'plugins/CloudStorage/storage.sqlite') as conn:
                            balances=dict(conn.execute('SELECT i.material,SUM(s.amount) FROM stock s JOIN items i USING(key) GROUP BY i.material'))
                            pending=conn.execute("SELECT count(*) FROM journal WHERE state='PENDING'").fetchone()[0]
                        if balances!={'STONE':64,'COBBLESTONE':64} or pending!=0:
                            raise RuntimeError('Restock conservation mismatch: '+str(balances)+' pending='+str(pending))
                        result.update(restock=restock,balances=balances,pending=pending)
                    break
                time.sleep(1)
            else: raise TimeoutError('Did not complete world join and class-load assertions')
    except Exception as error:
        result['error']=str(error)
        raise
    finally:
        if process is not None and process.poll() is None:
            # This is only our credential-free disposable client, never the user's launched game.
            process.terminate();process.wait(timeout=20)
        server.stop()
        result['elapsed_seconds']=round(time.monotonic()-started,1)
        (run/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False),flush=True)

if __name__=='__main__': main()
