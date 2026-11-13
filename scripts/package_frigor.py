"""Add FRIGOR's private roster/network to its GitHub-built APK; no local compilation."""
from pathlib import Path
import argparse
import json
from package_licensed import package
from package_companies import company_catalog
from package_installers import ROOT, run


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk',type=Path)
    args=p.parse_args()
    bundle=ROOT/'private/frigor/network/bundle.json'
    cfg=json.loads(bundle.read_text(encoding='utf-8'))
    assert cfg['coordinator'].startswith('https://frigor-') and cfg['url']=='https://frigor.colbert2027.shop'
    assert json.loads(cfg['seed'])['plants']==['FRIGOR']
    info=run([ROOT/'.tools/android-sdk/build-tools/34.0.0/aapt.exe','dump','badging',args.apk])
    assert "name='bo.soboce.comedor.frigor'" in info and "application-label:'FRIGOR Comedor'" in info
    assert "versionCode='17'" in info and "versionName='1.12.2-frigor'" in info
    license_file=ROOT/'private/billing-network/client-frigor.json'
    license_config=json.loads(license_file.read_text(encoding='utf-8'))
    assert license_config['productId']=='frigor-comedor' and license_config['contractId']=='frigor-comedor'
    assert not {'tunnel','privateKey','contracts'}.intersection(license_config)
    target=ROOT/'private/FRIGOR-1.12.2'
    result=package(args.apk,target/'FRIGOR-Comedor-1.12.2-Todos.apk',{'assets/companies.json':company_catalog(),'assets/comedor-shared.json':bundle,'assets/license-client.json':license_file})
    (target/'verificacion.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (target/'LEEME.txt').write_text('FRIGOR COMEDOR 1.12.2\n\nInstala FRIGOR-Comedor-1.12.2-Todos.apk encima de la aplicación correspondiente.\nNo desinstales ni borres datos: se conservan trabajadores, QR, entregas y permisos.\nEsta actualización es compatible con la elección de servidores para reportes de PC.\nAbre la app, elige la empresa correcta y revisa Estado de la red.\nPara consultar desde la PC debe haber un teléfono servidor conectado en esa empresa.\nLas redes, los contratos de Colbert Cobros y las tarifas no se modifican.\nEl visor Windows sigue usando la credencial derivada de solo consulta.\nNo se ha desactivado la validación de firma, hora, permiso ni relevo del servidor.\nNo publiques esta APK: contiene datos y credenciales privados de las empresas.\n',encoding='utf-8')
    print(json.dumps(result))


if __name__=='__main__':main()
