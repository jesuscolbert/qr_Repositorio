"""Verify the Swift-produced OOXML and receipt using Android's documented HMAC wire format."""
from pathlib import Path
import base64,hashlib,hmac,json,sys,zipfile,xml.etree.ElementTree as ET
root=Path(sys.argv[1]);f=json.loads((root/'interop.json').read_text());p=f['profile']
def mac(text):return hmac.new(p['key'].encode(),(p['cluster']+'|'+text).encode(),hashlib.sha256).hexdigest()
sealed=json.loads(f['receipt']);assert hmac.compare_digest(sealed['signature'],mac('receipt|'+sealed['body']))
body=json.loads(sealed['body']);assert body['kind']=='shared-meal-v1' and body['seq']==1
r=body['record'];assert r['clave']==r['persona']+'|'+r['fecha']+'|almuerzo' and r['qrHash']==hashlib.sha256(f['qr'].encode()).hexdigest()
parts=f['qr'][4:].split('.');raw=base64.urlsafe_b64decode(parts[0]+'='*(-len(parts[0])%4));signature=base64.urlsafe_b64decode(parts[1]+'='*(-len(parts[1])%4)).decode();assert hmac.compare_digest(signature,mac('card|'+raw.decode()))
with zipfile.ZipFile(root/'reporte-demostracion.xlsx') as z:
 assert z.testzip() is None
 for name in z.namelist():ET.fromstring(z.read(name))
 assert all('xl/worksheets/sheet'+str(i)+'.xml' in z.namelist() for i in range(1,4))
 assert b'<f>' not in z.read('xl/worksheets/sheet2.xml')
result={'ok':True,'xlsxSheets':3,'androidReceiptMac':True,'androidWorkerQrMac':True}
(root/'verified.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
