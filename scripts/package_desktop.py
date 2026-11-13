"""Configure the GitHub-built viewer locally; private credentials never enter CI."""
from pathlib import Path
import hashlib
import hmac
import json
import sys
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'desktop'))
from configuration import append

def main():
    companies=[]
    for identity,source in [('soboce','private/shared-network/bundle.json'),('frigor','private/frigor/network/bundle.json')]:
        network=json.loads((ROOT/source).read_text(encoding='utf-8'))
        cluster=hashlib.sha256(network['manifest'].encode()).hexdigest()
        key=hmac.new(network['networkKey'].encode(),(cluster+'|desktop-reports-v1').encode(),hashlib.sha256).hexdigest()
        permission=json.loads((ROOT/f'private/billing-network/client-{identity}.json').read_text(encoding='utf-8'))
        companies.append(dict(id=identity,name=identity.upper(),url=network['url'],reportKey=key,plants=json.loads(network['seed'])['plants'],license=permission))
    target=ROOT/'private/Colbert-Reportes-1.0.0/Colbert-Reportes-1.0.0.exe';target.parent.mkdir(parents=True,exist_ok=True)
    append(Path(sys.argv[1]),target,dict(version=1,companies=companies))
    print(json.dumps({'file':str(target),'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()}))
if __name__=='__main__':main()
