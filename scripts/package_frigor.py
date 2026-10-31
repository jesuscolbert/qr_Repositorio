"""Add FRIGOR's private roster/network to its GitHub-built APK; no local compilation."""
from pathlib import Path
import argparse
import json
from package_licensed import package
from package_installers import ROOT, run


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('apk',type=Path)
    args=p.parse_args()
    bundle=ROOT/'private/frigor/network/bundle.json'
    cfg=json.loads(bundle.read_text(encoding='utf-8'))
    assert cfg['coordinator'].startswith('https://frigor-') and cfg['url']=='https://frigor.colbertconsorcio.shop'
    assert json.loads(cfg['seed'])['plants']==['FRIGOR']
    info=run([ROOT/'.tools/android-sdk/build-tools/34.0.0/aapt.exe','dump','badging',args.apk])
    assert "name='bo.soboce.comedor.frigor'" in info and "application-label:'FRIGOR Comedor'" in info
    target=ROOT/'private/FRIGOR-1.7.1'
    result=package(args.apk,target/'FRIGOR-Comedor-1.7.1-Todos.apk',{'assets/comedor-shared.json':bundle})
    (target/'verificacion.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (target/'LEEME.txt').write_text('''FRIGOR COMEDOR — 1.7.1

Instalar FRIGOR-Comedor-1.7.1-Todos.apk en cada teléfono del encargado.
Abrir FRIGOR Comedor y escribir el nombre del encargado. El padrón y la red ya están incluidos.
La app tiene almacenamiento, conexión y credenciales separados de SOBOCE. Pueden estar instaladas las dos.
Credenciales QR permite buscar por carnet o nombre disponible. Emitir y compartir cada tarjeta.
El número interno del reloj no es el carnet. Los nombres pendientes se completarán sin crear otra persona.
Hay 598 registros; revisar los IDs de las filas 14 y 15 del archivo original, cuya longitud es distinta a la mayoría.
Este archivo de usuarios no contiene las huellas. El acceso biométrico aún requiere un lector compatible.

Una sola APK para todos los encargados. Un teléfono puede registrar sin conexión.
Para sincronizar, mantener las apps abiertas y con internet. Sin conexión no se pueden bloquear duplicados entre teléfonos.
El servidor cambia automáticamente entre los teléfonos mediante el coordinador exclusivo de FRIGOR.
Tarifas de comida pendientes: se cuentan raciones, sin valorar importes. No se copiaron precios ni el contrato de SOBOCE.
El control de cobros no está activado para FRIGOR en este instalador.
No publicar esta APK: incluye datos personales y el acceso privado de la red de FRIGOR.
''',encoding='utf-8')
    print(json.dumps(result))


if __name__=='__main__':main()
