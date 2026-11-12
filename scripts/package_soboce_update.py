"""Package the private SOBOCE domain update from its GitHub-built APK."""
from pathlib import Path
import argparse, json
from package_installers import ROOT, run
from package_licensed import package
from package_companies import company_catalog

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('apk',type=Path);args=p.parse_args()
    info=run([ROOT/'.tools/android-sdk/build-tools/34.0.0/aapt.exe','dump','badging',args.apk])
    assert "name='bo.soboce.comedor.piloto'" in info and "versionCode='13'" in info and "versionName='1.10.0-piloto'" in info
    bundle=ROOT/'private/shared-network/bundle.json';client=ROOT/'private/billing-network/client-soboce.json'
    cfg=json.loads(bundle.read_text(encoding='utf-8'));license=json.loads(client.read_text(encoding='utf-8'))
    assert cfg['url']=='https://comedor.colbert2027.shop' and cfg['endpointRevision']==1
    assert license['url']=='https://licencias.colbert2027.shop' and license['productId']=='soboce-comedor' and license['contractId']=='soboce-comedor'
    assert not {'tunnel','privateKey','contracts'}.intersection(license)
    target=ROOT/'private/SOBOCE-1.10.0'
    result=package(args.apk,target/'SOBOCE-Comedor-1.10.0-Todos.apk',{'assets/companies.json':company_catalog(),'assets/comedor-shared.json':bundle,'assets/license-client.json':client})
    (target/'verificacion.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (target/'LEEME.txt').write_text('''SOBOCE COMEDOR — 1.10.0

Actualizar primero Colbert Cobros 1.1.1 en el teléfono de Jesús y tocar Encender servidor.
Instalar SOBOCE-Comedor-1.10.0-Todos.apk en todos los teléfonos SOBOCE, encima de la app anterior.
Menú > Cambiar empresa permite abrir SOBOCE o FRIGOR y elegir cualquiera de sus plantas.
Nuevo trabajador permite elegir la planta, escribir el carnet y generar el QR.
Cada empresa conserva su permiso de pago independiente; Jesús elige el precio y cobro mensual o diario.
Solo se sincroniza la empresa seleccionada. Al cambiar, la anterior conserva todo su historial local.
No desinstalar ni borrar datos: conserva personas, QR, perfil, consumos y permisos de pago.
La misma APK sirve para todos los encargados. No importar JSON ni copiar bases de datos.
Abrir las apps con internet para compartir entregas. Ahora usan comedor.colbert2027.shop.
SOBOCE y FRIGOR mantienen redes, usuarios y contratos separados.
El control de Colbert Cobros sigue obligatorio. El dominio nuevo no reinicia el plazo pagado.
Sin conexión, el permiso vigente dura hasta 24 horas desde su validación sin superar el período pagado.
Un teléfono puede registrar sin los demás; los duplicados entre teléfonos aislados se detectan al sincronizar.
Menú > Imprimir QR en PDF: elegir planta, Carta/Oficio/Legal y QR de 7 u 8 cm.
Generar, ver, guardar o compartir el PDF. Imprimir al 100%, sin Ajustar a página.
Cada planta comienza en una página nueva. Nombre, CI/SAP y planta acompañan al QR.
No publicar este instalador: incluye datos y credenciales privados.
''',encoding='utf-8')
    print(json.dumps(result))

if __name__=='__main__':main()
