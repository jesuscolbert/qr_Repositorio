"""Add the private shared-network asset to a GitHub-built APK, align and re-sign."""
from pathlib import Path
import argparse, hashlib, json, os, re, zipfile
from package_installers import run, ROOT

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('apk',type=Path);args=parser.parse_args()
    base=args.apk.resolve();assert base.is_file()
    bundle=json.loads((ROOT/'private/shared-network/bundle.json').read_text(encoding='utf-8'))
    assert bundle['format']=='SOBOCE-SHARED-BUNDLE-1' and bundle['coordinator'].startswith('https://')
    assert not {'identityPrivate','identities','device','operator','node','apiToken'}.intersection(bundle)
    assert len(bundle['networkKey'])==64 and len(json.loads(bundle['manifest'])['nodes'])==4
    data=json.dumps(bundle,ensure_ascii=False,separators=(',',':')).encode('utf-8')
    target=ROOT/'private/installer-1.6.0';target.mkdir(parents=True,exist_ok=True)
    unsigned=target/'unsigned.apk';aligned=target/'aligned.apk';final=target/'SOBOCE-Comedor-1.6.0-Todos.apk'
    with zipfile.ZipFile(base) as src,zipfile.ZipFile(unsigned,'w') as dst:
        assert not {'assets/comedor-setup.json','assets/comedor-shared.json'}.intersection(src.namelist())
        for entry in src.infolist():
            if re.match(r'^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$',entry.filename,re.I):continue
            dst.writestr(entry,src.read(entry.filename))
        dst.writestr('assets/comedor-shared.json',data,compress_type=zipfile.ZIP_DEFLATED)
    sdk=ROOT/'.tools/android-sdk/build-tools/34.0.0';java=next((ROOT/'.tools/jdk').glob('*/bin/java.exe'));signer=sdk/'lib/apksigner.jar'
    props=dict(line.split('=',1) for line in (ROOT/'private/signing.properties').read_text(encoding='utf-8').splitlines() if '=' in line)
    env=os.environ.copy();env['SOBOCE_KS_PASS']=props['storePassword'];env['SOBOCE_KEY_PASS']=props['keyPassword']
    run([sdk/'zipalign.exe','-f','4',unsigned,aligned])
    run([java,'-jar',signer,'sign','--ks',ROOT/'private/release.jks','--ks-key-alias','comedor','--ks-pass','env:SOBOCE_KS_PASS','--key-pass','env:SOBOCE_KEY_PASS','--out',final,aligned],env)
    assert 'e4c013a5c590715c9360bc34ad9ecdff086c92cb002d71a1c5ac5e5d914fcd7e' in run([java,'-jar',signer,'verify','--print-certs',final])
    run([sdk/'zipalign.exe','-c','4',final])
    with zipfile.ZipFile(base) as src,zipfile.ZipFile(final) as dst:
        assert dst.read('assets/comedor-shared.json')==data
        for entry in src.infolist():
            if not entry.filename.startswith('META-INF/'):assert src.read(entry.filename)==dst.read(entry.filename)
    for p in [unsigned,aligned,final.with_suffix('.apk.idsig')]:
        if p.exists():p.unlink()
    verification={'file':str(final),'bytes':final.stat().st_size,'sha256':hashlib.sha256(final.read_bytes()).hexdigest(),'baseSha256':hashlib.sha256(base.read_bytes()).hexdigest(),'sameApkForEveryone':True,'private':True}
    (target/'verificacion.json').write_text(json.dumps(verification,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(verification))

if __name__=='__main__':main()
