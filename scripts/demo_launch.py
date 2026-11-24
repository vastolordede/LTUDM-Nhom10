#!/usr/bin/env python3
"""Developer-only launch and packaging. All generated files stay in ignored work/.
No network trust discovery, no TLS downgrade, no DB migrations."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
WORK = ROOT / 'work'
DEMO = WORK / 'local-demo'
PROFILE = DEMO / 'profile'
PREFIXES = ('P2P_', 'TRACKER_', 'DB_', 'RENDEZVOUS_', 'RELAY_', 'HEARTBEAT_')

def read_env(file):
    result = {}
    if not file.is_file():
        return result
    for raw in file.read_text(encoding='utf-8-sig').splitlines():
        line = raw.strip()
        if line.startswith('export '):
            line = line[7:].strip()
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = map(str.strip, line.split('=', 1))
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        result[key] = value
    return result

def tool(name):
    suffix = '.exe' if os.name == 'nt' else ''
    home = os.environ.get('JAVA_HOME')
    candidate = Path(home) / 'bin' / (name + suffix) if home else None
    if candidate and candidate.is_file():
        return str(candidate)
    found = shutil.which(name)
    if not found:
        raise RuntimeError('JDK 21 required: missing ' + name)
    return found

def clean_env():
    return {k: v for k, v in os.environ.items() if not k.upper().startswith(PREFIXES)
            and k.upper() not in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS')}

def launch_env(profile):
    env = clean_env()
    env['P2P_ENV_FILE'] = str(profile.resolve())
    env['P2P_PROFILE'] = 'production'
    env['P2P_ALLOW_INSECURE_DEV'] = 'false'
    return env

def run(args, **kwargs):
    subprocess.run([str(a) for a in args], cwd=ROOT, check=True, **kwargs)

def ensure_profile():
    if not DEMO.exists():
        DEMO.mkdir(parents=True, mode=0o700, exist_ok=True)
        if os.name == 'nt':
            identity = subprocess.check_output(['whoami.exe'], text=True).strip()
            run(['icacls.exe', DEMO, '/grant:r', identity + ':(OI)(CI)F'], stdout=subprocess.DEVNULL)
            run(['icacls.exe', DEMO, '/inheritance:r'], stdout=subprocess.DEVNULL)
    if not PROFILE.exists():
        lock = DEMO / 'provision.lock'
        try:
            lock.mkdir()
        except FileExistsError:
            raise RuntimeError('Local provisioning in progress or interrupted; inspect work/local-demo/provision.lock')
        try:
            if not PROFILE.exists():
                with tempfile.TemporaryDirectory(prefix='provision-', dir=DEMO) as temp:
                    target = Path(temp) / 'profile'
                    run([tool('java'), ROOT / 'scripts/SecureReleaseProvisioner.java', target, '127.0.0.1'], env=clean_env())
                    target.rename(PROFILE)
        finally:
            lock.rmdir()
    server = PROFILE / 'TRACKER_INSTALL'
    client = PROFILE / 'CLIENT_PUBLIC_BOOTSTRAP'
    for path in (server/'.env', server/'certs/tracker-server.p12', server/'certs/tracker-private.key.b64',
                 server/'certs/tracker-public.key.b64', client/'.env', client/'certs/peer-truststore.p12',
                 client/'certs/tracker-public.key.b64'):
        if not path.is_file():
            raise RuntimeError('Incomplete local profile; preserve it and inspect: ' + str(path))
    cfg = read_env(client/'.env')
    if cfg.get('TRACKER_HOST') != '127.0.0.1' or cfg.get('TRACKER_PORT') != '9000':
        raise RuntimeError('Local bootstrap must point to 127.0.0.1:9000')
    if (server/'certs/tracker-public.key.b64').read_bytes() != (client/'certs/tracker-public.key.b64').read_bytes():
        raise RuntimeError('Local Tracker and Peer signing public keys differ; refusing launch')
    return server, client

def local_database():
    existing = read_env(ROOT / '.env')
    keys = ('DB_URL', 'DB_HOST', 'DB_PORT', 'DB_NAME', 'DB_USER', 'DB_USERNAME', 'DB_PASSWORD')
    db = {key: os.environ.get(key) or existing.get(key, '') for key in keys}
    if not db['DB_PASSWORD'] or db['DB_PASSWORD'] == 'CHANGE_ME':
        raise RuntimeError('Local PostgreSQL password is missing. Administrator must configure existing project .env once.')
    if db['DB_URL']:
        if not re.fullmatch(r'jdbc:postgresql://(?:localhost|127\.0\.0\.1)(?::[0-9]{1,5})?/[A-Za-z0-9_-]+', db['DB_URL'], re.I):
            raise RuntimeError('Demo accepts only an explicit loopback PostgreSQL JDBC URL (no remote DB).')
    elif db['DB_HOST'] and db['DB_HOST'].lower() not in ('localhost', '127.0.0.1'):
        raise RuntimeError('Demo refuses non-loopback PostgreSQL host.')
    else:
        db['DB_HOST'] = '127.0.0.1'
    return {k: v for k, v in db.items() if v}

def install_dist(module):
    task = ':' + module + ':installDist'
    if os.name == 'nt':
        run(['cmd.exe', '/d', '/c', 'gradlew.bat', task])
    else:
        run(['sh', 'gradlew', task])
    lib = ROOT / module / 'build/install' / module / 'lib'
    if not lib.is_dir() or not any(lib.glob('*.jar')):
        raise RuntimeError('Missing Gradle distribution libraries: ' + str(lib))
    return lib

def local_server():
    db = local_database()
    server, _ = ensure_profile()
    lib = install_dist('tracker-server')
    env = launch_env(server/'.env')
    env.update(db)
    print('LOCAL Tracker TLS: 127.0.0.1:9000; PostgreSQL local; no AWS connection.', flush=True)
    run([tool('java'), '-cp', str(lib/'*'), 'vn.edu.p2p.tracker.TrackerApplication'], env=env)

def local_client(slot):
    _, client = ensure_profile()
    lib = install_dist('peer-client')
    home = DEMO / ('peer-home-' + str(slot))
    (home/'Downloads').mkdir(parents=True, exist_ok=True)
    env = launch_env(client/'.env')
    env['P2P_LISTEN_PORT'] = str(7000 + slot)
    print('LOCAL Peer', slot, '-> 127.0.0.1:9000; upload port', 7000 + slot, flush=True)
    run([tool('java'), '-Duser.home=' + str(home), '-cp', str(lib/'*'), 'vn.edu.p2p.peer.PeerLauncher'], env=env)

def package(bootstrap, output):
    bootstrap = bootstrap.resolve()
    output = output.resolve()
    if not output.is_relative_to(WORK.resolve()) or output.exists():
        raise RuntimeError('New output directory must be inside ignored work/ and not already exist.')
    if output.is_relative_to(bootstrap) or bootstrap.is_relative_to(output):
        raise RuntimeError('Bootstrap and output must be separate directories.')
    lib = install_dist('peer-client')
    WORK.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='client-release-', dir=WORK) as temp:
        dest = Path(temp)/'release'
        dest.mkdir()
        (dest/'lib').mkdir()
        for jar in lib.glob('*.jar'):
            if jar.is_symlink():
                raise RuntimeError('Unexpected symlink in distribution libraries')
            shutil.copyfile(jar, dest/'lib'/jar.name)
        # Strict allowlist; never copy an entire server/CA/identity directory.
        for name in ('.env', 'certs/peer-truststore.p12', 'certs/tracker-public.key.b64'):
            source = bootstrap/name
            if not source.is_file() or source.is_symlink():
                raise RuntimeError('Missing/unsafe public bootstrap file: ' + name)
            target = dest/'CLIENT_PUBLIC_BOOTSTRAP'/name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        classes = Path(temp)/'classes'
        classes.mkdir()
        run([tool('javac'), '-encoding', 'UTF-8', '-d', classes, ROOT/'scripts/TrustedClientLauncher.java'])
        run([tool('jar'), '--create', '--file', dest/'FileShare-P2P.jar', '--main-class', 'TrustedClientLauncher', '-C', classes, '.'])
        run([tool('java'), '-jar', dest/'FileShare-P2P.jar', '--check-ec2-bootstrap'], env=clean_env())
        (dest/'RUN_CLIENT.cmd').write_text('@echo off\r\ncd /d "%~dp0"\r\njava -jar FileShare-P2P.jar\r\nif errorlevel 1 pause\r\n', encoding='ascii', newline='')
        (dest/'README.txt').write_text('Extract entire folder. With Java 21 installed, double-click RUN_CLIENT.cmd or run java -jar FileShare-P2P.jar. Keep lib and CLIENT_PUBLIC_BOOTSTRAP beside the launcher. No user TLS/key setup. Tracker must be online and protocol v4. This is not a standalone fat JAR or bundled JRE.\n', encoding='utf-8')
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(dest, output)
    archive = output.with_suffix('.zip')
    if archive.exists():
        raise RuntimeError('Release directory ready; existing ZIP preserved: ' + str(archive))
    with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED) as z:
        for f in sorted(output.rglob('*')):
            if f.is_file():
                z.write(f, Path(output.name)/f.relative_to(output))
    print('Release:', archive)

def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest='mode', required=True)
    sub.add_parser('server')
    peer = sub.add_parser('peer')
    peer.add_argument('slot', type=int, choices=range(1, 10), nargs='?', default=1)
    sub.add_parser('prepare')
    pack = sub.add_parser('package')
    pack.add_argument('bootstrap', type=Path)
    pack.add_argument('output', type=Path)
    args = p.parse_args()
    if args.mode == 'server': local_server()
    elif args.mode == 'peer': local_client(args.slot)
    elif args.mode == 'prepare': ensure_profile(); print('Local TLS profile ready (no server started).')
    else: package(args.bootstrap, args.output)

if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, subprocess.CalledProcessError, zipfile.BadZipFile) as exc:
        print('Launch failed:', exc, file=__import__('sys').stderr)
        raise SystemExit(1)
