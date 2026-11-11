"""Provision a separate billing tunnel and private owner/customer installers, without changing meal tunnels."""
from pathlib import Path
from urllib.parse import urlparse
import json, secrets
from provision_peers import executable, run, encode
ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/'private/billing-network'

def main():
    DEST.mkdir(parents=True,exist_ok=True)
    path=DEST/'server.json'
    if path.exists(): data=json.loads(path.read_text(encoding='utf-8'))
    else:
        data={'version':1,'url':'https://licencias.colbert2027.shop','contracts':[{
            'id':'soboce-comedor','customer':'SOBOCE','productId':'soboce-comedor',
            'productName':'SOBOCE Comedor','token':secrets.token_hex(32)}]}
        path.write_text(encode(data),encoding='utf-8')
    exe=executable();cert=Path.home()/'.cloudflared/cert.pem';credentials=DEST/'tunnel.json'
    if not credentials.exists(): run(exe,'tunnel','--origincert',cert,'--credentials-file',credentials,'create','colbert-licencias')
    data['tunnel']=json.loads(credentials.read_text(encoding='utf-8'))
    if not data.get('dnsReady'):
        run(exe,'tunnel','--origincert',cert,'route','dns',data['tunnel']['TunnelID'],urlparse(data['url']).hostname)
        data['dnsReady']=True
    path.write_text(encode(data),encoding='utf-8')
    seed=data['contracts'][0]
    client={'version':1,'url':data['url'],'contractId':seed['id'],'productId':seed['productId'],'productName':seed['productName'],'token':seed['token']}
    for field in ('endpointRevision','previousUrls'):
        if field in data:client[field]=data[field]
    (DEST/'client-soboce.json').write_text(encode(client),encoding='utf-8')
    print(json.dumps({'url':data['url'],'serverConfig':str(path),'separateTunnel':True,'billingInitiallyDisabled':True}))

if __name__=='__main__':main()
