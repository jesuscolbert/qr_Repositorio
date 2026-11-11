"""Package the private Colbert Cobros 1.1.1 owner update from a GitHub-built APK."""
from pathlib import Path
import argparse, json
from package_installers import ROOT, run
from package_licensed import package

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path)
    args=parser.parse_args()
    info=run([ROOT/'.tools/android-sdk/build-tools/34.0.0/aapt.exe','dump','badging',args.apk])
    assert "name='bo.colbert.cobros'" in info and "versionCode='3'" in info and "versionName='1.1.1'" in info
    server=ROOT/'private/billing-network/server.json'
    cfg=json.loads(server.read_text(encoding='utf-8'))
    assert {'soboce-comedor','frigor-comedor'}.issubset({c['productId'] for c in cfg['contracts']})
    target=ROOT/'private/installer-licencias-1.1.1'
    result=package(args.apk,target/'Colbert-Cobros-1.1.1-Solo-Jesus.apk',{'assets/billing-server.json':server})
    (target/'verificacion.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (target/'LEEME.txt').write_text('''COLBERT COBROS — 1.1.1 — SOLO JESÚS

Actualizar sobre la versión anterior de Colbert Cobros en el mismo teléfono servidor, sin desinstalar ni borrar datos.
Abrir y confirmar el PIN/patrón del teléfono. Aparecerá FRIGOR como contrato separado.
Conserva la clave de firma del servidor, los contratos existentes, precios, pagos y comprobantes.
Un contrato FRIGOR nuevo empieza sin cobro; uno existente conserva su estado.
Puedes elegir frecuencia Mensual o Diaria y el monto de cada empresa; puedes definir su precio/frecuencia y activar Cobrar o Suspender.
Tocar Encender servidor; ahora usa https://licencias.colbert2027.shop.
Mantener el servidor con internet antes de validar por primera vez FRIGOR o SOBOCE 1.9.0.
No instalar en los teléfonos de los clientes ni compartir: esta APK incluye el acceso privado del servidor.
''',encoding='utf-8')
    print(json.dumps(result))

if __name__=='__main__':main()
