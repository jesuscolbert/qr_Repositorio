"""Independently decode every card rasterized from the actual Android PDF output.

The expected payloads are fictional signed test cards. A missing page, an extra
symbol, an unreadable card, or any differing payload fails the release build.
"""
from pathlib import Path
import json, math, sys
from PIL import Image
import zxingcpp


def verify(folder):
    documents=json.loads((folder/'expected.json').read_text(encoding='utf-8'))
    assert {(d['file'].split('-')[2],d['cm']) for d in documents}=={
        (paper,cm) for paper in ['CARTA','OFICIO','LEGAL'] for cm in [7,8]}
    count=0
    for document in documents:
        assert (folder/document['file']).is_file()
        qr=document['cm']*72/2.54
        left=(document['width']-qr*2-12)/2
        top=72/2.54+40
        scale=300/72
        for number,page in enumerate(document['pages'],1):
            with Image.open(folder/(document['file']+f'-page-{number}.png')) as image:
                assert image.size==(round(document['width']*scale),round(document['height']*scale))
                for slot,card in enumerate(page['cards']):
                    x=math.floor((left+(slot%2)*(qr+12))*scale+.5)
                    y=math.floor((top+(slot//2)*(qr+60+12))*scale+.5)
                    width=math.floor(qr*scale+.5)
                    crop=image.crop((x,y,x+width,y+width))
                    decoded=zxingcpp.read_barcodes(crop,formats=zxingcpp.BarcodeFormat.QRCode)
                    assert len(decoded)==1 and decoded[0].text==card['qr'],f"Unreadable or mismatched PDF QR: {document['file']} page={number} card={card['id']}"
                    count+=1
    assert count==66,f'Expected 11 cards in each of six layouts, got {count}'
    result=dict(ok=True,documents=len(documents),decodedCards=count,reader='zxing-cpp 3.0.0',dpi=300)
    (folder/'verified.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(f'PDF verification: {count} signed QR cards match exactly across six print layouts.')


if __name__=='__main__':verify(Path(sys.argv[1]))
