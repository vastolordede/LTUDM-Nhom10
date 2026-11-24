#!/usr/bin/env python3
"""Import administrator-provided public token; never fetch trust from unverified network."""
import base64, json, re, hashlib, tempfile
from pathlib import Path
from demo_launch import ROOT, WORK, tool, run, clean_env

def main():
    import argparse
    p=argparse.ArgumentParser(); p.add_argument('token',type=Path); p.add_argument('output',type=Path); a=p.parse_args()
    output=a.output.resolve(); WORK.mkdir(exist_ok=True)
    if not output.is_relative_to(WORK.resolve()) or output.exists(): raise ValueError('Use a new directory under work/')
    lines=[x.strip() for x in a.token.read_text(encoding='utf-8-sig').splitlines() if x.strip().startswith('P2P_PUBLIC_V1:')]
    if len(lines)!=1 or len(lines[0])>20000: raise ValueError('Expected exactly one public token line')
    obj=json.loads(base64.b64decode(lines[0].split(':',1)[1],validate=True))
    if set(obj)!={'endpoint','reference','leaf_sha256','ca_pem','ticket_public_b64','ticket_key_id'}: raise ValueError('Unexpected public token fields')
    for host in (obj['endpoint'],obj['reference']):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]{0,252}',host) or '..' in host: raise ValueError('Invalid hostname')
    if not re.fullmatch('[0-9a-f]{64}',obj['leaf_sha256']): raise ValueError('Invalid leaf fingerprint')
    ca=obj['ca_pem']; public=base64.b64decode(obj['ticket_public_b64'],validate=True)
    if not re.fullmatch(r'-----BEGIN CERTIFICATE-----\s+[A-Za-z0-9+/=\s]+-----END CERTIFICATE-----',ca) or len(public)!=44 or not public.startswith(bytes.fromhex('302a300506032b6570032100')): raise ValueError('Invalid public certificate/key')
    key_id=obj['ticket_key_id']
    if key_id and not re.fullmatch(r'[A-Za-z0-9_-]{1,64}',key_id): raise ValueError('Invalid ticket key ID')
    with tempfile.TemporaryDirectory(dir=WORK,prefix='ec2-public-') as td:
        t=Path(td); (t/'ca.pem').write_text(ca+'\n',encoding='ascii'); (t/'public.b64').write_text(obj['ticket_public_b64'],encoding='ascii')
        result=t/'bootstrap'
        run([tool('java'),ROOT/'scripts/CreatePublicClientBootstrap.java',result,obj['endpoint'],t/'ca.pem',t/'public.b64'],env=clean_env())
        with (result/'.env').open('a',encoding='ascii') as f:
            f.write('TRACKER_TLS_REFERENCE_IDENTITY='+obj['reference']+'\nTRACKER_TLS_LEAF_SHA256='+obj['leaf_sha256']+'\n')
            if key_id: f.write('P2P_TICKET_KEY_ID='+key_id+'\n')
        output.parent.mkdir(parents=True,exist_ok=True); result.rename(output)
    print('Tracker endpoint:',obj['endpoint']); print('TLS identity:',obj['reference']); print('TLS leaf SHA-256:',obj['leaf_sha256']); print('Ticket public SHA-256:',hashlib.sha256(public).hexdigest()); print('Bootstrap:',output)
if __name__=='__main__': main()
