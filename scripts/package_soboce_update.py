"""Package the private SOBOCE domain update from its GitHub-built APK."""
from pathlib import Path
import argparse, json
from package_installers import ROOT, run
from package_licensed import package

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('apk',type=Path);args=p.parse_args()
    info=run([ROOT/'.tools/android-sdk/build-tools/34.0.0/aapt.exe','dump','badging',args.apk])
    assert "name='bo.soboce.comedor.piloto'" in info and "versionCode='11'" in info and "versionName='1.8.1-piloto'" in info
    bundle=ROOT/'private/shared-network/bundle.json';client=ROOT/'private/billing-network/client-soboce.json'
    cfg=json.loads(bundle.read_text(encoding='utf-8'));license=json.loads(client.read_text(encoding='utf-8'))
    assert cfg['url']=='https://comedor.colbert2027.shop' and cfg['endpointRevision']==1
    assert license['url']=='https://licencias.colbert2027.shop' and license['productId']=='soboce-comedor' and license['contractId']=='soboce-comedor'
    assert not {'tunnel','privateKey','contracts'}.intersection(license)
    target=ROOT/'private/SOBOCE-1.8.1'
    result=package(args.apk,target/'SOBOCE-Comedor-1.8.1-Todos.apk',{'assets/comedor-shared.json':bundle,'assets/license-client.json':client})
    (target/'verificacion.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (target/'LEEME.txt').write_text('''SOBOCE COMEDOR — 1.8.1

Actualizar primero Colbert Cobros 1.1.1 en el teléfono de Jesús y tocar Encender servidor.
Instalar SOBOCE-Comedor-1.8.1-Todos.apk en todos los teléfonos SOBOCE, encima de la app anterior.
No desinstalar ni borrar datos: conserva personas, QR, perfil, consumos y permisos de pago.
La misma APK sirve para todos los encargados. No importar JSON ni copiar bases de datos.
Abrir las apps con internet para compartir entregas. Ahora usan comedor.colbert2027.shop.
SOBOCE y FRIGOR mantienen redes, usuarios y contratos separados.
El control de Colbert Cobros sigue obligatorio. El dominio nuevo no reinicia el plazo pagado.
Sin conexión, el permiso vigente dura hasta 24 horas desde su validación sin superar el período pagado.
Un teléfono puede registrar sin los demás; los duplicados entre teléfonos aislados se detectan al sincronizar.
No publicar este instalador: incluye datos y credenciales privados.
''',encoding='utf-8')
    print(json.dumps(result))

if __name__=='__main__':main()
