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
    assert {(d['paper'],d['cm']) for d in documents}=={
        (paper,cm) for paper in ['CARTA','OFICIO','LEGAL'] for cm in range(3,9)}
    expected_capacity={'CARTA':[30,18,9,9,6,4],'OFICIO':[36,21,12,10,8,6],'LEGAL':[42,21,12,12,8,6]}
    count=0
    for document in documents:
        assert (folder/document['file']).is_file()
        assert document['perPage']==expected_capacity[document['paper']][document['cm']-3]
        assert document['columns']*document['rows']==document['perPage']
        assert len(document['pages'])==math.ceil(11/document['perPage'])
        qr=document['cm']*72/2.54
        scale=300/72
        identities=[]
        for number,page in enumerate(document['pages'],1):
            with Image.open(folder/(document['file']+f'-page-{number}.png')) as image:
                assert image.size==(round(document['width']*scale),round(document['height']*scale))
                for slot,card in enumerate(page['cards']):
                    assert card['qr'].startswith('SB5:')
                    assert 0<card['x']<document['width']-qr and 0<card['y']<document['height']-qr
                    x=math.floor(card['x']*scale+.5)
                    y=math.floor(card['y']*scale+.5)
                    width=math.floor(qr*scale+.5)
                    crop=image.crop((x,y,x+width,y+width))
                    decoded=zxingcpp.read_barcodes(crop,formats=zxingcpp.BarcodeFormat.QRCode)
                    assert len(decoded)==1 and decoded[0].text==card['qr'],f"Unreadable or mismatched PDF QR: {document['file']} page={number} card={card['id']}"
                    count+=1
                    identities.append(card['id'])
        assert len(identities)==11 and len(set(identities))==11
    assert count==198,f'Expected 11 cards in each of eighteen layouts, got {count}'
    result=dict(ok=True,documents=len(documents),decodedCards=count,reader='zxing-cpp 3.0.0',dpi=300)
    (folder/'verified.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(f'PDF verification: {count} signed QR cards match exactly across eighteen print layouts.')


if __name__=='__main__':verify(Path(sys.argv[1]))
