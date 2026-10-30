"""Prepare four PRIVATE Android enrolment files; never upload them to GitHub.

The PC is used only once for installation. It is not part of the running network.
Use --plan to inspect names, then --apply to create the dedicated Cloudflare tunnels.
Existing services and the original config/ files are not modified.
"""
from pathlib import Path
import argparse, base64, hashlib, json, secrets, subprocess, sys, urllib.request
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / 'private' / 'peer-network'
VERSION = '2026.10.6'
EXE_SHA256 = 'd521b3bf68982165f0d06d76732f22d9a105ef057ff3ae4c0986f3a23190950f'
PLANTS = ['OF. COM. SCZ', 'PTA. WARNES', 'RDMX P.I. SCZ', 'PREF SANTA CRUZ']

def encode(data):
    return json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

def sha(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()

def identity():
    key = ec.generate_private_key(ec.SECP256R1())
    private = key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    return base64.b64encode(private).decode('ascii'), base64.b64encode(public).decode('ascii')

def run(exe, *args):
    result = subprocess.run([str(exe), *map(str, args)], capture_output=True, text=True, timeout=90)
    if result.returncode:
        # Do not accidentally echo Cloudflare account credentials or secrets in stderr.
        raise RuntimeError('Cloudflare no completó la operación. Revisa internet, certificado y DNS; no se publicó ninguna credencial.')

def executable():
    path = ROOT / '.tools/cloudflared/cloudflared.exe'
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temporary = path.with_suffix('.download')
        with urllib.request.urlopen(f'https://github.com/cloudflare/cloudflared/releases/download/{VERSION}/cloudflared-windows-amd64.exe', timeout=30) as response:
            with temporary.open('wb') as output:
                while chunk := response.read(1024 * 1024): output.write(chunk)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != EXE_SHA256:
            raise RuntimeError('La descarga no coincide con la firma SHA-256 publicada. No se ejecutará.')
        temporary.replace(path)
    if hashlib.sha256(path.read_bytes()).hexdigest() != EXE_SHA256:
        raise RuntimeError('El conector local no coincide con la versión verificada.')
    return path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--plan', action='store_true')
    args = parser.parse_args()
    nodes = [dict(id=i, name='Jesús Colbert' if i == 0 else f'Encargado {i+1}',
                  plant=p, role='admin' if i == 0 else 'encargado',
                  url=f'https://comedor-{i+1}.colbertconsorcio.shop') for i, p in enumerate(PLANTS)]
    if not args.apply:
        print(json.dumps({'nodes': nodes, 'minimumOnline': 3, 'runtimePC': False,
                          'privateDestination': str(PRIVATE), 'existingTunnelsModified': False}, ensure_ascii=False, indent=2))
        return
    cert = ROOT / 'config/cert.pem'
    if not cert.is_file(): raise RuntimeError('Falta config/cert.pem. No lo subas a GitHub.')
    people = json.loads((ROOT / 'private/padron.json').read_text(encoding='utf-8-sig'))
    if len({p['id'] for p in people}) != len(people): raise RuntimeError('Padrón con identidades duplicadas.')
    if not all(p['planta'] in PLANTS for p in people): raise RuntimeError('Hay plantas desconocidas en el padrón.')
    PRIVATE.mkdir(parents=True, exist_ok=True)
    state_path = PRIVATE / 'installation.json'
    if state_path.exists():
        state = json.loads(state_path.read_text(encoding='utf-8'))
        if [{k:n[k] for k in nodes[i]} for i,n in enumerate(state['nodes'])] != nodes: raise RuntimeError('Conserva la membresía de la instalación existente.')
        if 'identities' not in state: raise RuntimeError('Esta preparación antigua requiere migrar las identidades antes de instalar; no reemplaces una red en uso.')
    else:
        seed = encode({'people': people, 'plants': PLANTS, 'rates': [{'desde':'2026-09-01','desayuno':12,'almuerzo':23,'cena':19}]})
        identities = []
        for node in nodes:
            private, public = identity(); identities.append(private); node['publicKey'] = public
        state = {'nodes': nodes, 'seed': seed, 'identities': identities,
                 'manifest': encode({'installation': secrets.token_hex(16), 'nodes': nodes, 'seedHash': sha(seed)}),
                 'dnsReady': []}
        state_path.write_text(encode(state), encoding='utf-8')
    exe = executable()
    for i in range(4):
        credentials_path = PRIVATE / f'tunnel-{i+1}.json'
        if not credentials_path.exists():
            run(exe, 'tunnel', '--origincert', cert, '--credentials-file', credentials_path, 'create', f'soboce-comedor-{i+1}')
        credentials = json.loads(credentials_path.read_text(encoding='utf-8'))
        if i not in state['dnsReady']:
            # No --overwrite-dns: an existing hostname must never be silently repointed.
            run(exe, 'tunnel', '--origincert', cert, 'route', 'dns', credentials['TunnelID'], f'comedor-{i+1}.colbertconsorcio.shop')
            state['dnsReady'].append(i)
            state_path.write_text(encode(state), encoding='utf-8')
        bundle = {'format':'SOBOCE-P2P-2', 'manifest':state['manifest'], 'seed':state['seed'],
                  'identityPrivate':state['identities'][i], 'node':i, 'tunnel':credentials}
        (PRIVATE / f'telefono-{i+1}.json').write_text(encode(bundle), encoding='utf-8')
        print(f'Teléfono {i+1}: archivo privado preparado. No reutilizarlo en otro equipo.')
    print('Instalación preparada. Cada teléfono importa SU archivo. La PC ya no interviene en el servicio.')

if __name__ == '__main__':
    try: main()
    except Exception as error:
        # Network errors do not contain credentials; subprocess errors are sanitized above.
        print(f'No se pudo completar la preparación: {type(error).__name__}: {error}', file=sys.stderr)
        sys.exit(1)
