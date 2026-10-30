"""Package private per-phone installers from the APK built and tested in GitHub.

This only adds a private enrolment asset and re-signs; it does not compile Android.
Never publish these installers or their contents to a public repository/release.
"""
from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, zipfile

ROOT = Path(__file__).resolve().parents[1]

def run(args, env=None):
    result = subprocess.run(list(map(str,args)), capture_output=True, text=True, encoding='utf-8', errors='replace', env=env)
    if result.returncode:
        raise RuntimeError(f'Packaging step failed ({Path(str(args[0])).name}, exit {result.returncode}); no credentials were printed.')
    return result.stdout

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path)
    args=parser.parse_args()
    base=args.apk.resolve();assert base.is_file()
    target=ROOT/'private/installers-1.4.0';target.mkdir(parents=True,exist_ok=True)
    sdk=ROOT/'.tools/android-sdk/build-tools/34.0.0'
    java=next((ROOT/'.tools/jdk').glob('*/bin/java.exe'))
    signer=sdk/'lib/apksigner.jar'
    props=dict(line.split('=',1) for line in (ROOT/'private/signing.properties').read_text(encoding='utf-8').splitlines() if '=' in line)
    env=os.environ.copy();env['SOBOCE_KS_PASS']=props['storePassword'];env['SOBOCE_KEY_PASS']=props['keyPassword']
    results=[]
    for number in range(1,5):
        source=ROOT/f'private/peer-network/telefono-{number}.json'
        data=source.read_bytes();config=json.loads(data.decode('utf-8-sig'))
        assert config['node']==number-1 and config['format']=='SOBOCE-P2P-2'
        unsigned=target/f'unsigned-{number}.apk';aligned=target/f'aligned-{number}.apk'
        final=target/f'SOBOCE-1.4.0-Telefono-{number}.apk'
        with zipfile.ZipFile(base) as src,zipfile.ZipFile(unsigned,'w') as dst:
            assert 'assets/comedor-setup.json' not in src.namelist()
            for entry in src.infolist():
                if re.match(r'^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$',entry.filename,re.I):continue
                dst.writestr(entry,src.read(entry.filename))
            dst.writestr('assets/comedor-setup.json',data,compress_type=zipfile.ZIP_DEFLATED)
        run([sdk/'zipalign.exe','-f','4',unsigned,aligned])
        run([java,'-jar',signer,'sign','--ks',ROOT/'private/release.jks','--ks-key-alias','comedor','--ks-pass','env:SOBOCE_KS_PASS','--key-pass','env:SOBOCE_KEY_PASS','--out',final,aligned],env)
        cert=run([java,'-jar',signer,'verify','--print-certs',final])
        assert 'e4c013a5c590715c9360bc34ad9ecdff086c92cb002d71a1c5ac5e5d914fcd7e' in cert
        run([sdk/'zipalign.exe','-c','4',final])
        with zipfile.ZipFile(base) as src,zipfile.ZipFile(final) as dst:
            assert dst.read('assets/comedor-setup.json')==data
            for entry in src.infolist():
                if not entry.filename.startswith('META-INF/'):
                    assert hashlib.sha256(src.read(entry.filename)).digest()==hashlib.sha256(dst.read(entry.filename)).digest()
        unsigned.unlink();aligned.unlink()
        # V4 signing sidecars are unnecessary for normal APK installation.
        sidecar=final.with_suffix('.apk.idsig')
        if sidecar.exists():sidecar.unlink()
        manifest=json.loads(config['manifest']);node=manifest['nodes'][config['node']]
        results.append({'phone':number,'name':node['name'],'plant':node['plant'],'file':final.name,'bytes':final.stat().st_size,'sha256':hashlib.sha256(final.read_bytes()).hexdigest()})
    (target/'verificacion.json').write_text(json.dumps({'baseSha256':hashlib.sha256(base.read_bytes()).hexdigest(),'private':True,'installers':results},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (target/'LEEME.txt').write_text('''APK PRIVADAS LISTAS PARA INSTALAR — 1.4.0

Cada archivo es para UN teléfono. Instalar y abrir; el encargado, la planta y la base local se preparan automáticamente. No hace falta importar JSON ni copiar una base .db.

Teléfono 1: Jesús Colbert — OF. COM. SCZ (administrador).
Teléfono 2: Encargado 2 — PTA. WARNES.
Teléfono 3: Encargado 3 — RDMX P.I. SCZ.
Teléfono 4: Encargado 4 — PREF SANTA CRUZ.

Instalar encima de la versión anterior, sin borrar datos. Si dos equipos usaron el mismo número, la APK correcta reasigna únicamente una base sin historial; una base con consumos o votos se conserva y requiere revisión.

No instalar Teléfono-1 en ambos equipos. Cada APK contiene el padrón y el acceso privado de ese encargado: distribuir solo al encargado correspondiente y no subir a páginas públicas ni GitHub.

Abrir Estado de la red para ver el número del equipo, su dirección, el estado del túnel y de los otros teléfonos. No hace falta un servidor central: los equipos disponibles se sincronizan entre sí. Registrar funciona sin internet; sincronizar requiere al menos dos conectados. Los duplicados entre equipos desconectados se marcan al reunir los registros.
''',encoding='utf-8')
    print(json.dumps({'privateDirectory':str(target),'installers':results},ensure_ascii=False))

if __name__=='__main__':main()
