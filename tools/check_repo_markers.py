#!/usr/bin/env python3
"""Reject unresolved Git conflict markers in application/build sources."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP = {'.git', '.gradle', 'build', 'bin', 'work', '.idea', 'node_modules', 'out'}
EXT = {'.java', '.gradle', '.fxml', '.xml', '.properties', '.sql', '.yml', '.yaml'}
# Anchored markers only: do not reject ordinary comparison operators.
PATTERN = re.compile(r'^(<{7}|={7}|>{7})(?:\s|$)')
issues = []
for path in ROOT.rglob('*'):
    if not path.is_file() or path.suffix.lower() not in EXT:
        continue
    if any(part in SKIP for part in path.relative_to(ROOT).parts):
        continue
    for number, line in enumerate(path.read_text(encoding='utf-8-sig').splitlines(), 1):
        if PATTERN.match(line):
            issues.append(f'{path.relative_to(ROOT)}:{number}: unresolved Git merge marker')
for issue in issues:
    print('ERROR:', issue, file=sys.stderr)
print(f'Git conflict marker scan: {len(issues)} issue(s)')
sys.exit(bool(issues))
