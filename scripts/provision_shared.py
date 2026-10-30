"""Prepare the one private network bundle. Never upload it or print its secrets."""
from pathlib import Path
import argparse, json, secrets
from provision_peers import executable, run, encode

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/'private/shared-network'

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--coordinator', default='')
    args=parser.parse_args()
    if args.coordinator:
        from urllib.parse import urlparse
        u=urlparse(args.coordinator)
        if u.scheme!='https' or not u.hostname or u.path not in ('','/') or u.query or u.fragment:raise ValueError('Coordinator HTTPS URL required')
    DEST.mkdir(exist_ok=True,parents=True)
    old=json.loads((ROOT/'private/peer-network/telefono-1.json').read_text(encoding='utf-8'))
    path=DEST/'bundle.json'
    if path.exists():bundle=json.loads(path.read_text(encoding='utf-8'))
    else:
        bundle={'format':'SOBOCE-SHARED-BUNDLE-1','manifest':old['manifest'],'seed':old['seed'],
                'networkKey':secrets.token_hex(32),'url':'https://comedor.colbertconsorcio.shop','coordinator':''}
        path.write_text(encode(bundle),encoding='utf-8')
    assert bundle['manifest']==old['manifest'] and bundle['seed']==old['seed']
    cert=ROOT/'config/cert.pem';exe=executable();credentials=DEST/'tunnel.json'
    if not credentials.exists():run(exe,'tunnel','--origincert',cert,'--credentials-file',credentials,'create','soboce-comedor-compartido')
    bundle['tunnel']=json.loads(credentials.read_text(encoding='utf-8'))
    if not bundle.get('dnsReady'):
        run(exe,'tunnel','--origincert',cert,'route','dns',bundle['tunnel']['TunnelID'],'comedor.colbertconsorcio.shop')
        bundle['dnsReady']=True
    if args.coordinator:bundle['coordinator']=args.coordinator.rstrip('/')
    path.write_text(encode(bundle),encoding='utf-8')
    print(json.dumps({'url':bundle['url'],'coordinatorConfigured':bool(bundle['coordinator']),'privateBundle':str(path),'legacyTunnelsUnchanged':True}))

if __name__=='__main__':main()
