"""Prepare both private company profiles, without server administration credentials."""
import json
from package_installers import ROOT

def company_catalog():
    companies=[]
    for identity,source in [('soboce','private/shared-network/bundle.json'),('frigor','private/frigor/network/bundle.json')]:
        network=json.loads((ROOT/source).read_text(encoding='utf-8'))
        license=json.loads((ROOT/f'private/billing-network/client-{identity}.json').read_text(encoding='utf-8'))
        assert network['format']=='SOBOCE-SHARED-BUNDLE-1' and network['url'].endswith('.colbert2027.shop')
        assert license['productId']==identity+'-comedor' and license['contractId']==identity+'-comedor'
        assert license['url']=='https://licencias.colbert2027.shop'
        assert not {'privateKey','contracts','tunnel'}.intersection(license)
        companies.append({'id':identity,'network':network,'license':license})
    assert companies[0]['network']['networkKey']!=companies[1]['network']['networkKey']
    assert companies[0]['network']['manifest']!=companies[1]['network']['manifest']
    path=ROOT/'private/company-installer/companies.json';path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps({'format':'COLBERT-COMPANIES-1','companies':companies},ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    return path
