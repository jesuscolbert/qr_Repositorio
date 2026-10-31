"""Prepare private owner and customer APKs from the APKs compiled in GitHub."""
from pathlib import Path
import argparse, hashlib, json, os, re, zipfile
from package_installers import run, ROOT

def package(base, final, assets):
    final.parent.mkdir(parents=True,exist_ok=True)
    unsigned=final.with_suffix('.unsigned.apk');aligned=final.with_suffix('.aligned.apk')
    with zipfile.ZipFile(base) as src,zipfile.ZipFile(unsigned,'w') as dst:
        assert not set(assets).intersection(src.namelist())
        assert not any(n.endswith(('billing-server.json','license-client.json','comedor-shared.json','shared-live-test.json','billing-live-test.json')) for n in src.namelist())
        for entry in src.infolist():
            if re.match(r'^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$',entry.filename,re.I):continue
            dst.writestr(entry,src.read(entry.filename))
        for name,path in assets.items():dst.writestr(name,path.read_bytes(),compress_type=zipfile.ZIP_DEFLATED)
    sdk=ROOT/'.tools/android-sdk/build-tools/34.0.0';java=next((ROOT/'.tools/jdk').glob('*/bin/java.exe'));signer=sdk/'lib/apksigner.jar'
    props=dict(line.split('=',1) for line in (ROOT/'private/signing.properties').read_text(encoding='utf-8').splitlines() if '=' in line)
    env=os.environ.copy();env['COLBERT_KS_PASS']=props['storePassword'];env['COLBERT_KEY_PASS']=props['keyPassword']
    run([sdk/'zipalign.exe','-f','4',unsigned,aligned])
    run([java,'-jar',signer,'sign','--ks',ROOT/'private/release.jks','--ks-key-alias','comedor','--ks-pass','env:COLBERT_KS_PASS','--key-pass','env:COLBERT_KEY_PASS','--out',final,aligned],env)
    assert 'e4c013a5c590715c9360bc34ad9ecdff086c92cb002d71a1c5ac5e5d914fcd7e' in run([java,'-jar',signer,'verify','--print-certs',final])
    run([sdk/'zipalign.exe','-c','4',final])
    with zipfile.ZipFile(base) as src,zipfile.ZipFile(final) as dst:
        for n in src.namelist():
            if not n.startswith('META-INF/'):assert src.read(n)==dst.read(n)
        for n,p in assets.items():assert dst.read(n)==p.read_bytes()
    for p in [unsigned,aligned,final.with_suffix('.apk.idsig')]:
        if p.exists():p.unlink()
    return {'file':str(final),'bytes':final.stat().st_size,'sha256':hashlib.sha256(final.read_bytes()).hexdigest(),'baseSha256':hashlib.sha256(base.read_bytes()).hexdigest(),'private':True}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('owner',type=Path);p.add_argument('customer',type=Path);args=p.parse_args()
    target=ROOT/'private/installer-licencias-1.0'
    client=json.loads((ROOT/'private/billing-network/client-soboce.json').read_text(encoding='utf-8'))
    assert not {'tunnel','privateKey','contracts'}.intersection(client)
    results=[package(args.owner,target/'Colbert-Cobros-1.0.0-Solo-Jesus.apk',{'assets/billing-server.json':ROOT/'private/billing-network/server.json'}),package(args.customer,target/'SOBOCE-Comedor-1.7.0-Todos.apk',{'assets/comedor-shared.json':ROOT/'private/shared-network/bundle.json','assets/license-client.json':ROOT/'private/billing-network/client-soboce.json'})]
    (target/'verificacion.json').write_text(json.dumps(results,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(results))

if __name__=='__main__':main()
