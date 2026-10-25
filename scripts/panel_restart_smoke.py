"""Direct-JAR panel restart test against an explicitly supplied Paper JAR. No external finalizer."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,socket,sqlite3,time
from contextlib import closing
import integration

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--paper',type=Path,required=True)
    parser.add_argument('--build',required=True)
    parser.add_argument('--port',type=int,default=25567)
    parser.add_argument('--cycles',type=int,default=3)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    with socket.socket() as guard: guard.bind(('127.0.0.1',args.port))
    run=root/'run'/('panel-restart-'+args.build+'-'+time.strftime('%Y%m%d-%H%M%S'))
    run.mkdir(parents=True)
    manual=root/'run/server'
    for name in ('cache','libraries','versions'): shutil.copytree(manual/name,run/name)
    shutil.copy2(args.paper,run/'paper.jar')
    shutil.copy2(manual/'eula.txt',run/'eula.txt')
    (run/'plugins').mkdir()
    plugin=root/'target/cloud-storage-0.2.2.jar'
    shutil.copy2(plugin,run/'plugins/CloudStorage.jar')
    properties=(manual/'server.properties').read_text(encoding='utf-8-sig').replace('server-port=25565','server-port='+str(args.port)).replace('online-mode=true','online-mode=false')
    assert 'server-ip=127.0.0.1' in properties
    (run/'server.properties').write_text(properties,encoding='utf-8')
    integration.RUN=run
    server=integration.Server()
    database=run/'plugins/CloudStorage/storage.sqlite'
    started=time.monotonic()
    cycles=[]
    print('Panel restart test: '+str(run),flush=True)
    try:
        for cycle in range(args.cycles):
            server.start()
            server.command('cs status')
            server.wait_for('version=0.2.2 frozen=false pending=0')
            log=(run/'logs/latest.log').read_text(encoding='utf-8')
            assert 'Standalone shutdown checkpoint unsupported' not in log
            if cycle>0: assert 'standalone checkpoint accepted' in log
            process=server.process
            server.stop()
            assert process.returncode==0
            with closing(sqlite3.connect(database)) as db:
                meta=dict(db.execute('SELECT * FROM meta'))
                assert meta['active']=='true' and meta['shutdown_ready']==meta['session']
            receipt=database.with_name(database.name+'.shutdown.properties').read_text(encoding='latin-1')
            assert 'session='+meta['session'] in receipt
            cycles.append({'cycle':cycle+1,'exit_code':process.returncode,'frozen':False,'receipt_written':True})
            print('Direct-JAR normal restart cycle '+str(cycle+1)+' passed',flush=True)
        result={'status':'passed','build':args.build,'port':args.port,'supervisor_finalizer':False,'cycles':cycles,'jar_sha256':hashlib.sha256(plugin.read_bytes()).hexdigest(),'paper_sha256':hashlib.sha256(args.paper.read_bytes()).hexdigest(),'elapsed_seconds':round(time.monotonic()-started,1),'run':str(run)}
        (run/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False),flush=True)
    finally: server.stop()

if __name__=='__main__': main()
