#!/usr/bin/env python3
"""Run on trusted EC2 admin SSH. Export ONLY CA, leaf fingerprint and signing PUBLIC key."""
import argparse, base64, hashlib, ipaddress, json, os, re, subprocess, tempfile
from pathlib import Path

def run(*args, data=None, env=None):
    p = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env, timeout=20)
    if p.returncode:
        raise RuntimeError('Failed: ' + ' '.join(args[:2]) + ' (exit ' + str(p.returncode) + ')')
    return p.stdout

def certs(raw):
    return list(dict.fromkeys(re.findall(rb'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', raw, re.S)))

def main():
    p=argparse.ArgumentParser(); p.add_argument('endpoint'); p.add_argument('reference'); a=p.parse_args()
    for host in (a.endpoint,a.reference):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,252}',host) or '..' in host: raise ValueError('Invalid hostname')
    # Inspect only in process memory. Never print container environment or private key.
    container='staging-tracker-1'
    env=dict(x.split('=',1) for x in json.loads(run('docker','inspect','--format','{{json .Config.Env}}',container)) if '=' in x)
    mounts={x['Destination']:x['Source'] for x in json.loads(run('docker','inspect','--format','{{json .Mounts}}',container))}
    password=env['TRACKER_TLS_KEYSTORE_PASSWORD']; store=mounts[env['TRACKER_TLS_KEYSTORE']]; key=mounts[env['P2P_TICKET_SIGNING_KEY_FILE']]
    process_env={**os.environ,'P2P_EXPORT_PASSWORD':password}
    cas=certs(run('openssl','pkcs12','-in',store,'-nokeys','-cacerts','-passin','env:P2P_EXPORT_PASSWORD',env=process_env))
    leaves=certs(run('openssl','pkcs12','-in',store,'-nokeys','-clcerts','-passin','env:P2P_EXPORT_PASSWORD',env=process_env))
    if len(cas)!=1 or len(leaves)!=1: raise RuntimeError('Expected one CA and one Tracker certificate; inspect offline CA chain')
    ca, leaf=cas[0],leaves[0]
    der=run('openssl','x509','-outform','DER',data=leaf)
    pin=hashlib.sha256(der).hexdigest()
    try: ipaddress.ip_address(a.reference); verify_flag='-verify_ip'
    except ValueError: verify_flag='-verify_hostname'
    with tempfile.TemporaryDirectory() as td:
        c=Path(td)/'ca.pem'; l=Path(td)/'leaf.pem'; c.write_bytes(ca+b'\n'); l.write_bytes(leaf+b'\n')
        run('openssl','verify','-CAfile',str(c),'-purpose','sslserver',verify_flag,a.reference,str(l))
        for port in (9000,9001):
            out=run('openssl','s_client','-connect','127.0.0.1:'+str(port),'-CAfile',str(c),'-verify_return_error',verify_flag,a.reference,'-showcerts',data=b'')
            chain=certs(out)
            if not chain or hashlib.sha256(run('openssl','x509','-outform','DER',data=chain[0])).hexdigest()!=pin: raise RuntimeError('Live certificate does not match keystore on port '+str(port))
    private=base64.b64decode(Path(key).read_text().strip(),validate=True)
    public=run('openssl','pkey','-inform','DER','-pubout','-outform','DER',data=private)
    if not public.startswith(bytes.fromhex('302a300506032b6570032100')) or len(public)!=44: raise RuntimeError('Not an Ed25519 public key')
    ticket_id=env.get('P2P_TICKET_KEY_ID','')
    obj=dict(endpoint=a.endpoint, reference=a.reference, leaf_sha256=pin, ca_pem=ca.decode('ascii'), ticket_public_b64=base64.b64encode(public).decode('ascii'),ticket_key_id=ticket_id)
    print('TLS leaf SHA-256:',pin)
    print('Ticket public SHA-256:',hashlib.sha256(public).hexdigest())
    print('Copy ONLY the following P2P_PUBLIC_V1 line over the trusted admin channel:')
    print('P2P_PUBLIC_V1:'+base64.b64encode(json.dumps(obj,separators=(',',':')).encode()).decode())
if __name__=='__main__':
    try: main()
    except Exception as e:
        # Avoid traceback/exception values that could disclose secret material.
        print('Public export failed:', type(e).__name__, str(e) if isinstance(e,RuntimeError) else 'Check administrator inputs and mounted secrets.',file=__import__('sys').stderr)
        raise SystemExit(1)
