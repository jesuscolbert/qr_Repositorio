"""Provision FRIGOR separately from SOBOCE. Never print or publish bundle contents."""
from pathlib import Path
import argparse
import hashlib
import json
import secrets
from provision_peers import executable, run, encode, identity

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/'private/frigor/network'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--coordinator', default='')
    args=p.parse_args()
    if args.coordinator:
        from urllib.parse import urlparse
        u=urlparse(args.coordinator)
        if u.scheme!='https' or not u.hostname or u.path not in ('','/') or u.query or u.fragment:
            raise ValueError('Coordinator HTTPS URL required')
    DEST.mkdir(parents=True,exist_ok=True)
    people=json.loads((ROOT/'private/frigor/padron.json').read_text(encoding='utf-8'))
    path=DEST/'bundle.json'
    if path.exists():
        bundle=json.loads(path.read_text(encoding='utf-8'))
        if json.loads(bundle['seed'])['people']!=people:
            raise ValueError('El padrón ya instalado requiere actualización conservando su manifiesto y QR.')
    else:
        # The dormant four-node fields preserve wire compatibility; FRIGOR has no legacy identities.
        # The public keys have no saved private counterparts and the .invalid URLs are never used in shared mode.
        nodes=[dict(id=i,name=f'Reserva {i+1}',plant='FRIGOR',role='encargado',
                    publicKey=identity()[1],url=f'https://frigor-legacy-{i+1}.invalid') for i in range(4)]
        seed=encode(dict(people=people,plants=['FRIGOR'],rates=[dict(desde='2020-01-01',desayuno=0,almuerzo=0,cena=0)],ratesConfigured=False))
        manifest=encode(dict(installation='frigor-'+secrets.token_hex(16),nodes=nodes,
                             seedHash=hashlib.sha256(seed.encode()).hexdigest()))
        bundle=dict(format='SOBOCE-SHARED-BUNDLE-1',manifest=manifest,seed=seed,
                    networkKey=secrets.token_hex(32),url='https://frigor.colbertconsorcio.shop',coordinator='')
        path.write_text(encode(bundle),encoding='utf-8')
    cert=ROOT/'config/cert.pem'
    creds=DEST/'tunnel.json'
    exe=executable()
    if not creds.exists():
        run(exe,'tunnel','--origincert',cert,'--credentials-file',creds,'create','frigor-comedor-compartido')
    bundle['tunnel']=json.loads(creds.read_text(encoding='utf-8'))
    if not bundle.get('dnsReady'):
        run(exe,'tunnel','--origincert',cert,'route','dns',bundle['tunnel']['TunnelID'],'frigor.colbertconsorcio.shop')
        bundle['dnsReady']=True
    if args.coordinator:
        bundle['coordinator']=args.coordinator.rstrip('/')
    path.write_text(encode(bundle),encoding='utf-8')
    (DEST/'worker-secrets.json').write_text(json.dumps({'NETWORK_KEY':bundle['networkKey']}),encoding='utf-8')
    print(json.dumps(dict(url=bundle['url'],coordinatorConfigured=bool(bundle['coordinator']),
                         users=len(people),existingNetworksUnchanged=True)))


if __name__=='__main__':main()
