#!/usr/bin/env python3
"""Fail CI if secrets or key material are included in tracked files."""
from pathlib import Path
import subprocess
import re
import sys

root = Path(__file__).resolve().parents[1]
try:
    files = subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0')
except (OSError, subprocess.CalledProcessError):
    files = [str(p.relative_to(root)) for p in root.rglob('*') if p.is_file()
             and not any(s in p.parts for s in ('.git','build','work','.gradle'))]
violations = []
private_exts = ('.pem', '.p12', '.pfx', '.jks', '.key', '.pk8', '.der')
private_names = {'.env', '.env.local', 'id_rsa', 'id_ed25519', '.npmrc', '.pypirc'}
patterns = [
    re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'),
    re.compile(r'ghp_[A-Za-z0-9]{25,}'),
    re.compile(r'github_pat_[A-Za-z0-9_]{25,}'),
    re.compile(r'\bAKIA[0-9A-Z]{16}\b'),
]
for entry in files:
    if not entry: continue
    path = root / entry
    if not path.is_file(): continue
    name = path.name.lower()
    if name in private_names or (name.startswith('.env.') and name != '.env.example') \
            or name.endswith(private_exts):
        violations.append(f'{entry}: secret/certificate filename must not be tracked')
        continue
    if path.stat().st_size > 2_000_000: continue
    if path.suffix.lower() not in ('.java','.md','.json','.yml','.yaml','.properties','.txt','.py','.gradle','.xml','.sql','.ps1'):
        continue
    try: content = path.read_text(encoding='utf-8')
    except UnicodeDecodeError: continue
    for pat in patterns:
        if pat.search(content):
            violations.append(f'{entry}: suspicious secret payload ({pat.pattern[:30]})')
            break
for v in violations: print('ERROR:', v, file=sys.stderr)
print(f'Release secret scan: {len(files)} files; {len(violations)} issue(s)')
sys.exit(bool(violations))
