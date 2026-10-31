import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.exceptions import InvalidSignature

spec = importlib.util.spec_from_file_location('colbert_license', Path(__file__).parents[1]/'licensing/desktop/colbert_license.py')
lic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lic)

class DesktopLicenseTest(unittest.TestCase):
    def setUp(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.pub = base64.b64encode(self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()
        self.claims = {'version':1,'contractId':'client','productId':'app','nonce':'n','revision':1,'issuedAt':100000,'expiresAt':100000+lic.OFFLINE_MS,'paidUntil':0,'allowed':True}
    def envelope(self):
        raw = json.dumps(self.claims)
        return {'claims':raw,'publicKey':self.pub,'signature':base64.b64encode(self.key.sign(raw.encode(),ec.ECDSA(hashes.SHA256()))).decode()}
    def test_signature_and_identity(self):
        e=self.envelope()
        self.assertTrue(lic.verify(e,self.pub,'client','app','n')['allowed'])
        for contract,product,nonce in [('other','app','n'),('client','other','n'),('client','app','old')]:
            with self.assertRaises(ValueError): lic.verify(e,self.pub,contract,product,nonce)
        self.claims['allowed']=False
        e['claims']=json.dumps(self.claims)
        with self.assertRaises(InvalidSignature): lic.verify(e,self.pub,'client','app','n')
    def test_paid_bound_and_day_cap(self):
        self.claims['paidUntil']=100001
        with self.assertRaises(ValueError): lic.verify(self.envelope(),self.pub,'client','app','n')
        self.claims.update(paidUntil=0,expiresAt=100000+lic.OFFLINE_MS+1)
        with self.assertRaises(ValueError): lic.verify(self.envelope(),self.pub,'client','app','n')
    def test_offline_cache_and_expiry(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);config=root/'config.json';cache=root/'cache.json'
            config.write_text(json.dumps({'version':1,'url':'https://example.com','contractId':'client','productId':'app','token':'a'*64}))
            client=lic.LicenseClient(config,cache)
            state={'publicKey':self.pub,'envelope':self.envelope(),'monotonic':time.monotonic(),'bootEstimate':time.time()-time.monotonic()}
            cache.write_text(json.dumps(state));self.assertTrue(client.allowed())
            state['monotonic']-=86400;cache.write_text(json.dumps(state));self.assertFalse(client.allowed())
            state['monotonic']=time.monotonic();state['bootEstimate']-=3600;cache.write_text(json.dumps(state));self.assertFalse(client.allowed())

if __name__=='__main__': unittest.main()
