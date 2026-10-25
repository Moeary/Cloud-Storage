"""Create and verify a new complete mods/config snapshot; never overwrite a previous snapshot."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--client',type=Path,required=True)
parser.add_argument('--destination',type=Path,required=True)
parser.add_argument('--mirror',type=Path)
args=parser.parse_args()
client=args.client.resolve()
destination=args.destination.resolve()
destination.mkdir(parents=True,exist_ok=False)
for name in ('mods','config','logs','crash-reports'):
    if (client/name).exists(): shutil.copytree(client/name,destination/name)
for name in (client.name+'.json',client.name+'.jar','options.txt','servers.dat','servers.dat_old'):
    if (client/name).exists(): shutil.copy2(client/name,destination/name)
rows=[]
for path in sorted(destination.rglob('*')):
    if path.is_file():
        original=client/path.relative_to(destination)
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        if digest!=hashlib.sha256(original.read_bytes()).hexdigest(): raise RuntimeError('Snapshot mismatch: '+str(path))
        rows.append({'file':path.relative_to(destination).as_posix(),'bytes':path.stat().st_size,'sha256':digest})
(destination/'manifest.json').write_text(json.dumps({'client':str(client),'files':rows},ensure_ascii=False,indent=2),encoding='utf-8')
(destination/'README.txt').write_text('这是更新前完整 mods（含 disabled 文件）及 config 快照，附实例 JSON/JAR、options、服务器列表与诊断日志。\n恢复时先关闭游戏，将当前 mods/config 移到另一个备份目录，再从本快照复制 mods/config；不要直接混合两套 JAR。\n未修改资源包、光影、存档和账号文件。manifest.json 记录每个文件 SHA256。\n',encoding='utf-8')
if args.mirror:
    mirror=args.mirror.resolve()
    if mirror.exists(): raise RuntimeError('Mirror already exists')
    shutil.copytree(destination,mirror)
    for path in destination.rglob('*'):
        if path.is_file() and hashlib.sha256(path.read_bytes()).digest()!=hashlib.sha256((mirror/path.relative_to(destination)).read_bytes()).digest():
            raise RuntimeError('Mirror mismatch: '+str(path))
print(json.dumps({'snapshot':str(destination),'mirror':str(args.mirror),'verified_files':len(rows)},ensure_ascii=False))
