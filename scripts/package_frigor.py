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
    assert "versionCode='24'" in info and "versionName='1.16.1-frigor'" in info
    license_file=ROOT/'private/billing-network/client-frigor.json'
    license_config=json.loads(license_file.read_text(encoding='utf-8'))
    assert license_config['productId']=='frigor-comedor' and license_config['contractId']=='frigor-comedor'
    assert not {'tunnel','privateKey','contracts'}.intersection(license_config)
    target=ROOT/'private/FRIGOR-1.16.1'
    result=package(args.apk,target/'FRIGOR-Comedor-1.16.1-Todos.apk',{'assets/companies.json':company_catalog(),'assets/comedor-shared.json':bundle,'assets/license-client.json':license_file})
    (target/'verificacion.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (target/'LEEME.txt').write_text('FRIGOR COMEDOR 1.16.1\n\nInstala FRIGOR-Comedor-1.16.1-Todos.apk encima de la aplicación correspondiente.\nNo desinstales ni borres datos: se conservan trabajadores, QR, entregas y permisos.\nGuarda el permiso firmado hasta el vencimiento pagado, incluso tras reinicios. Actualiza primero Colbert Cobros a 1.2.0 y comprueba el permiso una vez con su servidor encendido.\nAbre Iniciar sesión y escribe tu usuario y contraseña. Solo la cuenta maestra crea usuarios, cambia contraseñas y elimina accesos. Para migrar, la cuenta maestra entra primero por Acceso anterior con PIN, abre Encargados y permisos y configura usuario y contraseña en CADA CUENTA EXISTENTE. También puede configurar Mi usuario y contraseña. No cree otra cuenta para la misma persona. La primera entrada en un teléfono o con una contraseña nueva requiere internet. Después se admite entrada local con el verificador cifrado y los permisos vigentes. Una entrada en otro celular reemplaza al anterior; los cambios llegan a equipos desconectados cuando sincronizan. Para la huella abre el menú: Acceso con huella. También está en Configurar acceso con huella desde el login o Mi cuenta. La pantalla explica si falta registrar huellas, configurar la contraseña o si hay varios perfiles. Con un único perfil personal, marca Es mi celular personal: entrar con huella y confirma con el lector. No borres datos para habilitarla.\nPara consultar desde la PC debe haber un teléfono servidor conectado en esa empresa.\nLas redes, los contratos de Colbert Cobros y las tarifas no se modifican.\nEl visor Windows sigue usando la credencial derivada de solo consulta.\nNo se ha desactivado la validación de firma, hora, permiso ni relevo del servidor.\nNo publiques esta APK: contiene datos y credenciales privados de las empresas.\n',encoding='utf-8')
    print(json.dumps(result))


if __name__=='__main__':main()
