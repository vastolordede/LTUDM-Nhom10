#!/usr/bin/env python3
"""Verify committed SQL migration order/content; does not touch a DB."""
from pathlib import Path
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
info = json.loads((ROOT / 'database/migration_checksums.json').read_text(encoding='utf-8'))
expected = ['database/schema.sql'] + [f'database/migration_v{i}.sql' for i in range(2, 9)]
paths = [entry['path'] for entry in info['migrations']]
issues = []
if paths != expected:
    issues.append('Manifest must list schema then migration_v2..migration_v8 in order')
for entry in info['migrations']:
    path = ROOT / entry['path']
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
        issues.append('Missing/modified SQL without checksum review: ' + entry['path'])
if not (ROOT / 'database/verify_schema.sql').is_file():
    issues.append('Missing database/verify_schema.sql')
for issue in issues:
    print('ERROR:', issue, file=sys.stderr)
print(f'DB SQL manifest: {len(info["migrations"])} scripts; {len(issues)} issue(s)')
sys.exit(bool(issues))
