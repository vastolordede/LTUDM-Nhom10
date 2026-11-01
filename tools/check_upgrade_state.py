#!/usr/bin/env python3
"""Hash app-local state before and after Windows app upgrade; never export secrets."""
import argparse
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('action', choices=('snapshot', 'compare'))
parser.add_argument('--snapshot', required=True, type=Path)
parser.add_argument('--state-dir', type=Path, default=Path.home() / '.fileshare-p2p')
args = parser.parse_args()

# Only list relative names, sizes and SHA-256; never copy .env, passwords or binary contents.
def current():
    result={}
    if not args.state_dir.exists(): return result
    for file in sorted(args.state_dir.rglob('*')):
        if not file.is_file() or file.is_symlink(): continue
        relative=file.relative_to(args.state_dir).as_posix()
        if file.name.endswith(('.tmp','.part','.log')): continue
        h=hashlib.sha256()
        with file.open('rb') as f:
            for buf in iter(lambda:f.read(1048576),b''):h.update(buf)
        result[relative]={'bytes':file.stat().st_size,'sha256':h.hexdigest()}
    return result

data=current()
if args.action=='snapshot':
    if args.snapshot.exists(): raise SystemExit('Snapshot exists; choose a new path')
    args.snapshot.parent.mkdir(parents=True,exist_ok=True)
    args.snapshot.write_text(json.dumps(data,indent=2,ensure_ascii=False),encoding='utf-8')
    print(f'Captured {len(data)} local state files at {args.snapshot}; keep this list private')
else:
    old=json.loads(args.snapshot.read_text(encoding='utf-8'))
    changed=[key for key,value in old.items() if data.get(key)!=value]
    for key in changed:print('MISMATCH (missing or changed):',key)
    print('Upgrade state files verified:',len(old)-len(changed),'/',len(old))
    if changed: raise SystemExit(1)
