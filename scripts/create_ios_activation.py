"""Create private, company-specific iPhone activation QR images from existing configs.

Does not issue a license, create tunnel credentials, or transmit any secret.
"""
from pathlib import Path
import base64, hashlib, json
import qrcode

ROOT=Path(__file__).resolve().parents[1]
TARGET=ROOT/'private/iPhone-Control-1.0.0'

def main():
    TARGET.mkdir(parents=True,exist_ok=True)
    for company,source in [('soboce','private/shared-network/bundle.json'),('frigor','private/frigor/network/bundle.json')]:
        network=json.loads((ROOT/source).read_text(encoding='utf-8-sig'))
        license=json.loads((ROOT/f'private/billing-network/client-{company}.json').read_text(encoding='utf-8-sig'))
        expected=f'https://{"comedor" if company=="soboce" else "frigor"}.colbert2027.shop'
        assert network['url']==expected and license['url']=='https://licencias.colbert2027.shop'
        assert license['productId']==license['contractId']==company+'-comedor'
        profile={'v':1,'id':company,'url':expected,'cluster':hashlib.sha256(network['manifest'].encode()).hexdigest(),'key':network['networkKey'],'license':license}
        assert not {'tunnel','privateKey','contracts'}.intersection(profile) and not {'tunnel','privateKey','contracts'}.intersection(license)
        raw=json.dumps(profile,ensure_ascii=False,separators=(',',':')).encode()
        value='COLBERT-IOS1:'+base64.urlsafe_b64encode(raw).decode().rstrip('=')
        assert len(value)<=4096
        qr=qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M,box_size=8,border=4)
        qr.add_data(value);qr.make(fit=True);qr.make_image(fill_color='#102039',back_color='white').save(TARGET/f'Activacion-{company.upper()}.png')
        print(f'{company.upper()}: activación privada creada; no contiene credenciales del túnel.')
if __name__=='__main__':main()
