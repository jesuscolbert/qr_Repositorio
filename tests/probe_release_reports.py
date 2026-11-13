"""Exercise the optimized APK over its real HTTP socket using fictional assets.

Runs only on the disposable GitHub Android emulator. The distributed APK is not
modified: the test copy must retain identical DEX/resources/native libraries.
"""
from pathlib import Path
import hashlib,hmac,json,os,re,secrets,subprocess,sys,time,urllib.request,urllib.error,xml.etree.ElementTree as ET,zipfile

ROOT=Path(__file__).resolve().parents[1]
def run(*args):
    result=subprocess.run([str(a) for a in args],text=True,encoding='utf-8',stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    if result.returncode:
        print(result.stdout[-3000:],flush=True)
        raise RuntimeError(f'Test tool failed: {Path(str(args[0])).name}, exit {result.returncode}')
    return result.stdout
def mac(k,v):return hmac.new(k.encode(),v.encode(),hashlib.sha256).hexdigest()
def main():
    apk=Path(sys.argv[1]);target=ROOT/'app/build/reports/release-probe';target.mkdir(parents=True,exist_ok=True)
    tools=Path(os.environ['ANDROID_HOME'])/'build-tools/34.0.0'
    package='bo.soboce.comedor.piloto';key='a'*64
    seed=json.dumps({'people':[],'plants':['Planta de prueba'],'rates':[]},separators=(',',':'))
    nodes=[dict(id=i,name='Encargado de prueba',plant='Planta de prueba',role='encargado',url=f'https://node{i}.example.com',publicKey='') for i in range(4)]
    manifest=json.dumps(dict(installation='ci-read-only-probe',seedHash=hashlib.sha256(seed.encode()).hexdigest(),nodes=nodes),separators=(',',':'))
    cluster=hashlib.sha256(manifest.encode()).hexdigest();report_key=mac(key,cluster+'|desktop-reports-v1')
    bundle=dict(format='SOBOCE-SHARED-BUNDLE-1',seed=seed,manifest=manifest,networkKey=key,url='https://comedor.example.com',coordinator='https://coordinator.example.com',tunnel=dict(TunnelID='00000000-0000-0000-0000-000000000000',TunnelSecret='test-only',AccountTag='test-only'))
    unsigned=target/'unsigned.apk';aligned=target/'aligned.apk';test_apk=target/'probe.apk'
    with zipfile.ZipFile(apk) as src,zipfile.ZipFile(unsigned,'w') as dst:
        assert not any(n.endswith('comedor-shared.json') for n in src.namelist())
        for item in src.infolist():
            if item.filename.startswith('META-INF/'):continue
            dst.writestr(item,src.read(item.filename))
        dst.writestr('assets/comedor-shared.json',json.dumps(bundle).encode())
    run(tools/'zipalign','-f','4',unsigned,aligned)
    # The Gradle action may isolate Android's home; use our own disposable key.
    test_key=target/'test-key.jks';test_key.unlink(missing_ok=True)
    run(Path(os.environ['JAVA_HOME'])/'bin/keytool','-genkeypair','-noprompt','-alias','probe','-keyalg','RSA','-keysize','2048','-validity','1','-keystore',test_key,'-storetype','JKS','-storepass','test-only-key','-keypass','test-only-key','-dname','CN=CI Reports Probe')
    run(tools/'apksigner','sign','--ks',test_key,'--ks-key-alias','probe','--ks-pass','pass:test-only-key','--key-pass','pass:test-only-key','--out',test_apk,aligned)
    with zipfile.ZipFile(apk) as original,zipfile.ZipFile(test_apk) as tested:
        for name in original.namelist():
            if not name.startswith('META-INF/'):assert original.read(name)==tested.read(name)
    try:
        run('adb','install',test_apk);run('adb','shell','pm','grant',package,'android.permission.POST_NOTIFICATIONS')
        run('adb','shell','am','start','-W','-n',package+'/bo.soboce.comedor.MainActivity')
        found=False
        for _ in range(12):
            run('adb','shell','uiautomator','dump','/sdcard/ci-report-ui.xml')
            root=ET.fromstring(run('adb','shell','cat','/sdcard/ci-report-ui.xml'))
            button=next((n for n in root.iter('node') if n.get('text','').casefold()=='guardar y entrar'),None)
            if button is not None:
                x1,y1,x2,y2=map(int,re.findall(r'\d+',button.get('bounds')));run('adb','shell','input','tap',(x1+x2)//2,(y1+y2)//2);found=True;break
            time.sleep(1)
        assert found,'Profile entry did not appear'
        run('adb','forward','tcp:18787','tcp:8787')
        for attempt in range(20):
            try:
                with urllib.request.urlopen('http://127.0.0.1:18787/health',timeout=2) as r:health=json.load(r)
                break
            except (urllib.error.URLError,TimeoutError,ConnectionError):
                if attempt==19:raise
                time.sleep(1)
        assert health['reportsProtocol']==1 and health['appVersion']=='1.12.2-piloto'
        def request(signing_key,old=False):
            now=0 if old else int(time.time()*1000);nonce=secrets.token_hex(32)
            payload=json.dumps(dict(action='report',period='day',date='2026-11-12',month='2026-11',plant=''),separators=(',',':'))
            body=json.dumps(dict(time=now,nonce=nonce,payload=payload,signature=mac(signing_key,f'request|{now}|{nonce}|{payload}'))).encode()
            req=urllib.request.Request('http://127.0.0.1:18787/reports',data=body,headers={'Content-Type':'application/json','User-Agent':'Colbert-Reportes/1.1.0'})
            try:
                with urllib.request.urlopen(req,timeout=5) as response:return response.status,json.load(response),nonce
            except urllib.error.HTTPError as e:return e.code,json.load(e),nonce
        code,reply,nonce=request(report_key)
        assert code==200,(code,reply)
        assert hmac.compare_digest(reply['signature'],mac(report_key,'reply|'+nonce+'|'+reply['payload']))
        assert json.loads(reply['payload'])==dict(ok=False,error='license'),'No test permission was issued: reports must remain blocked'
        assert request('0'*64)[1]['code']=='signature'
        assert request(report_key,True)[1]['code']=='clock'
        (target/'result.json').write_text(json.dumps(dict(ok=True,optimizedApk=True,version=health['appVersion'],validSignatureAccepted=True,licenseStillRequired=True)))
        print('Optimized APK: real HTTP reports route, signatures, clock and licensing verified.')
    finally:
        subprocess.run(['adb','shell','am','force-stop',package],capture_output=True)
        subprocess.run(['adb','uninstall',package],capture_output=True)
        for p in [unsigned,aligned,test_apk,test_apk.with_suffix('.apk.idsig'),test_key]:p.unlink(missing_ok=True)

if __name__=='__main__':main()
