import base64
import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile
ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT/'desktop'),str(ROOT/'licensing/desktop')]
from configuration import append, read, validate
from reports_client import ReportsClient, mac, workbook_bytes, save_workbook
from colbert_license import LicenseBlocked

def config():
    return dict(version=1,companies=[dict(id='soboce',name='SOBOCE',url='https://comedor.colbert2027.shop',reportKey='a'*64,license=dict(contractId='soboce-comedor',productId='soboce-comedor'))])

class ReportsTests(unittest.TestCase):
    def test_appended_configuration_keeps_executable_and_rejects_admin_secrets(self):
        with tempfile.TemporaryDirectory() as d:
            exe=Path(d)/'base.exe';out=Path(d)/'private.exe';exe.write_bytes(b'MZfake-generic-build');append(exe,out,config());self.assertTrue(out.read_bytes().startswith(exe.read_bytes()));self.assertEqual(read(out),config())
            bad=config();bad['companies'][0]['networkKey']='secret'
            with self.assertRaises(ValueError):validate(bad)
            bad=config();bad['companies'][0]['url']='http://localhost'
            with self.assertRaises(ValueError):validate(bad)
    def test_reply_authentication_company_separation_replay_and_expiry(self):
        c=config()['companies'][0];client=ReportsClient(c);payload=json.dumps(dict(ok=True,serverTime=int(time.time()*1000),items=[]));raw=json.dumps(dict(payload=payload,signature=mac(c['reportKey'],'reply|nonce|'+payload)))
        self.assertEqual(client.verify(raw,'nonce')['items'],[])
        with self.assertRaises(ValueError):client.verify(raw,'different-request')
        with self.assertRaises(ValueError):ReportsClient(dict(c,reportKey='b'*64)).verify(raw,'nonce')
        with patch('reports_client.time.time',return_value=time.time()+300):
            with self.assertRaises(ValueError):client.verify(raw,'nonce')
        denied=json.dumps(dict(ok=False,error='license'));signed=json.dumps(dict(payload=denied,signature=mac(c['reportKey'],'reply|nonce|'+denied)))
        with self.assertRaises(LicenseBlocked):client.verify(signed,'nonce')
    def test_xlsx_must_be_verified_and_saved_atomically(self):
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:
            for name in ['[Content_Types].xml','xl/workbook.xml','xl/worksheets/sheet1.xml','xl/worksheets/sheet2.xml']:z.writestr(name,'<test/>')
        raw=data.getvalue();report=dict(file=base64.b64encode(raw).decode(),sha256=hashlib.sha256(raw).hexdigest());self.assertEqual(workbook_bytes(report),raw)
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'reporte.xlsx';save_workbook(path,report);self.assertEqual(path.read_bytes(),raw)
            with self.assertRaises(ValueError):save_workbook(path,dict(report,sha256='0'*64))
            self.assertEqual(path.read_bytes(),raw);self.assertEqual(len(list(Path(d).iterdir())),1)

if __name__=='__main__':unittest.main()
