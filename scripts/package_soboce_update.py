"""Package the private SOBOCE domain update from its GitHub-built APK."""
from pathlib import Path
import argparse, json
from package_installers import ROOT, run
from package_licensed import package
from package_companies import company_catalog

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('apk',type=Path);args=p.parse_args()
    info=run([ROOT/'.tools/android-sdk/build-tools/34.0.0/aapt.exe','dump','badging',args.apk])
    assert "name='bo.soboce.comedor.piloto'" in info and "versionCode='23'" in info and "versionName='1.16.0-piloto'" in info
    bundle=ROOT/'private/shared-network/bundle.json';client=ROOT/'private/billing-network/client-soboce.json'
    cfg=json.loads(bundle.read_text(encoding='utf-8'));license=json.loads(client.read_text(encoding='utf-8'))
    assert cfg['url']=='https://comedor.colbert2027.shop' and cfg['endpointRevision']==1
    assert license['url']=='https://licencias.colbert2027.shop' and license['productId']=='soboce-comedor' and license['contractId']=='soboce-comedor'
    assert not {'tunnel','privateKey','contracts'}.intersection(license)
    target=ROOT/'private/SOBOCE-1.16.0'
    result=package(args.apk,target/'SOBOCE-Comedor-1.16.0-Todos.apk',{'assets/companies.json':company_catalog(),'assets/comedor-shared.json':bundle,'assets/license-client.json':client})
    (target/'verificacion.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (target/'LEEME.txt').write_text('SOBOCE COMEDOR 1.16.0\n\nInstala SOBOCE-Comedor-1.16.0-Todos.apk encima de la aplicación correspondiente.\nNo desinstales ni borres datos: se conservan trabajadores, QR, entregas y permisos.\nGuarda el permiso firmado hasta el vencimiento pagado, incluso tras reinicios. Actualiza primero Colbert Cobros a 1.2.0 y comprueba el permiso una vez con su servidor encendido.\nAbre Iniciar sesión y escribe tu usuario y contraseña. Solo la cuenta maestra crea usuarios, cambia contraseñas y elimina accesos. Para migrar, la cuenta maestra entra primero por Acceso anterior con PIN, abre Encargados y permisos y configura usuario y contraseña en CADA CUENTA EXISTENTE. También puede configurar Mi usuario y contraseña. No cree otra cuenta para la misma persona. La primera entrada en un teléfono o con una contraseña nueva requiere internet. Después se admite entrada local con el verificador cifrado y los permisos vigentes. Una entrada en otro celular reemplaza al anterior; los cambios llegan a equipos desconectados cuando sincronizan. La huella es opcional en un teléfono personal con un único perfil.\nPara consultar desde la PC debe haber un teléfono servidor conectado en esa empresa.\nLas redes, los contratos de Colbert Cobros y las tarifas no se modifican.\nEl visor Windows sigue usando la credencial derivada de solo consulta.\nNo se ha desactivado la validación de firma, hora, permiso ni relevo del servidor.\nNo publiques esta APK: contiene datos y credenciales privados de las empresas.\n',encoding='utf-8')
    print(json.dumps(result))

if __name__=='__main__':main()
