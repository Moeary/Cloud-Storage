"""Official AxShulkers + CloudStorage on disposable real Paper. Never touches manual worlds."""
from pathlib import Path
from contextlib import closing
import argparse,hashlib,json,os,shutil,socket,sqlite3,time,zipfile
import integration

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--axshulkers',type=Path,required=True)
    parser.add_argument('--port',type=int,default=25568)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]
    digest=hashlib.sha256(args.axshulkers.read_bytes()).hexdigest()
    if digest!='ce53478f169d2099e827aa5c4dda4a75c280a4ee12664fce46479587521f6dfb':
        raise RuntimeError('Expected official AxShulkers 1.24.0 release')
    with socket.socket() as guard: guard.bind(('127.0.0.1',args.port))
    run=root/'run'/('shulker-coexist-'+time.strftime('%Y%m%d-%H%M%S'))
    run.mkdir(parents=True)
    manual=root/'run/server'
    for name in ('cache','libraries','versions'): shutil.copytree(manual/name,run/name)
    for name in ('paper.jar','eula.txt'): shutil.copy2(manual/name,run/name)
    (run/'plugins').mkdir()
    shutil.copy2(root/'target/cloud-storage-0.2.3.jar',run/'plugins/CloudStorage.jar')
    shutil.copy2(args.axshulkers,run/'plugins/AxShulkers.jar')
    (run/'plugins/AxShulkers').mkdir()
    with zipfile.ZipFile(args.axshulkers) as jar: config=jar.read('config.yml').decode()
    # Keep the upstream safety switches; SQLite permits independent read-only record counts.
    config=config.replace('type: "h2"','type: "sqlite"')
    (run/'plugins/AxShulkers/config.yml').write_text(config,encoding='utf-8')
    properties=(manual/'server.properties').read_text(encoding='utf-8-sig').replace('server-port=25565','server-port='+str(args.port)).replace('online-mode=true','online-mode=false')
    assert 'server-ip=127.0.0.1' in properties
    (run/'server.properties').write_text(properties,encoding='utf-8')
    integration.RUN=run
    integration.PORT=args.port
    server=integration.Server()
    started=time.monotonic()
    result={'status':'failed','run':str(run),'axshulkers_sha256':digest,'manual_server_touched':False}
    try:
        for phase in ('coexist','coexist-restart'):
            server.start()
            log=(run/'logs/latest.log').read_text(encoding='utf-8')
            assert 'supported=true' in log, 'Isolation must install successfully'
            integration.clients(server,phase)
            server.stop()
            with closing(sqlite3.connect(run/'plugins/AxShulkers/data.db')) as db:
                count=db.execute('SELECT count(*) FROM axshulkers_data').fetchone()[0]
                assert count==1, 'Only the ordinary physical shulker may enter Ax database: '+str(count)
        # Test fixture changes metadata only; never deploys or alters the official source JAR.
        # A future unknown upstream version must not silently inherit permission to read catalogues.
        with zipfile.ZipFile(args.axshulkers) as source, zipfile.ZipFile(run/'plugins/AxShulkers.jar','w') as target:
            for info in source.infolist():
                data=source.read(info.filename)
                if info.filename=='plugin.yml': data=data.replace(b"version: '1.24.0'",b"version: '1.24.0-unsupported-test'")
                target.writestr(info,data)
        server.start()
        integration.clients(server,'coexist-blocked')
        server.stop()
        with closing(sqlite3.connect(run/'plugins/AxShulkers/data.db')) as db:
            assert db.execute('SELECT count(*) FROM axshulkers_data').fetchone()[0]==1
        result.update(status='passed',ax_records=1,phases=['coexist','coexist-restart','coexist-blocked'])
    finally:
        server.stop()
        result['elapsed_seconds']=round(time.monotonic()-started,1)
        (run/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(result,ensure_ascii=False),flush=True)

if __name__=='__main__': main()
