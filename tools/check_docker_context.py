#!/usr/bin/env python3
"""Simple static release checks; does not build an image or inspect Docker cache."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
dockerfile = (root / 'infra/staging/Tracker.Dockerfile').read_text()
ignore = (root / '.dockerignore').read_text()
compose = (root / 'infra/staging/compose.yml').read_text()
issues = []
if 'COPY . .' in dockerfile:
    issues.append('Dockerfile must not COPY entire repository into image')
for required in ('COPY common/src', 'COPY tracker-server/src', 'COPY peer-client/build.gradle'):
    if required not in dockerfile:
        issues.append('Missing explicit COPY: ' + required)
for required in ('.env', 'certs/', 'local-secrets/', 'work/', '*.key.b64'):
    if required not in ignore:
        issues.append('Missing Docker context exclusion: ' + required)
if 'P2P_PROFILE: production' not in compose:
    issues.append('Staging compose must enforce production security profile')
if "TRACKER_TLS_ENABLED: 'true'" not in compose:
    issues.append('Tracker must use TLS in staging compose')
for issue in issues:
    print('ERROR:', issue, file=sys.stderr)
print(f'Docker/Compose static checks: {len(issues)} issue(s)')
sys.exit(bool(issues))
