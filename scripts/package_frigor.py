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
    assert "versionCode='14'" in info and "versionName='1.11.0-frigor'" in info
    license_file=ROOT/'private/billing-network/client-frigor.json'
    license_config=json.loads(license_file.read_text(encoding='utf-8'))
    assert license_config['productId']=='frigor-comedor' and license_config['contractId']=='frigor-comedor'
    assert not {'tunnel','privateKey','contracts'}.intersection(license_config)
    target=ROOT/'private/FRIGOR-1.11.0'
    result=package(args.apk,target/'FRIGOR-Comedor-1.11.0-Todos.apk',{'assets/companies.json':company_catalog(),'assets/comedor-shared.json':bundle,'assets/license-client.json':license_file})
    (target/'verificacion.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    (target/'LEEME.txt').write_text('''FRIGOR COMEDOR — 1.11.0

Las conexiones usan colbert2027.shop y conservan usuarios, QR y consumos.
Primero actualizar Colbert Cobros a 1.1.1 en el teléfono de Jesús y abrirlo con su PIN.
Después instalar FRIGOR-Comedor-1.11.0-Todos.apk en TODOS los teléfonos de encargados.
Menú > Cambiar empresa: SOBOCE y FRIGOR están disponibles en esta misma app.
Puedes elegir cualquier planta y crear obreros en la planta seleccionada.
Solo se sincroniza la empresa elegida; ambas conservan historial y permiso independientes.
Instalar encima de las versiones anteriores, sin desinstalar ni borrar sus datos.
La primera validación de permiso necesita internet y el servidor Colbert Cobros encendido.
Abrir FRIGOR Comedor. Solo una instalación nueva pide el nombre del encargado. El padrón y la red ya están incluidos.
La app tiene almacenamiento, conexión y credenciales separados de SOBOCE. Pueden estar instaladas las dos.
El botón Menú permanece arriba y despliega la navegación lateral.
Menú > Nuevo trabajador: escribir carnet y nombre opcional, guardar y compartir su QR.
Menú > Trabajadores y QR permite buscar por carnet o nombre disponible y compartir tarjetas.
Un carnet ya registrado conserva su persona e historial; no se vuelve a crear.
Las altas se comparten al sincronizar. El QR nuevo también puede darlo a conocer en otro teléfono 1.8 antes de sincronizar.
El número interno del reloj no es el carnet. Los nombres pendientes se completarán sin crear otra persona.
Hay 598 registros; revisar los IDs de las filas 14 y 15 del archivo original, cuya longitud es distinta a la mayoría.
Este archivo de usuarios no contiene las huellas. El acceso biométrico aún requiere un lector compatible.

Una sola APK para todos los encargados. Un teléfono puede registrar sin conexión.
Para sincronizar, mantener las apps abiertas y con internet. Sin conexión no se pueden bloquear duplicados entre teléfonos.
El servidor cambia automáticamente entre los teléfonos mediante el coordinador exclusivo de FRIGOR.
Tarifas de comida pendientes: se cuentan raciones, sin valorar importes. No se copiaron precios ni el contrato de SOBOCE.
FRIGOR tiene contrato propio en Colbert Cobros. No cambia precios ni permisos de SOBOCE.
Desde Colbert Cobros se puede definir precio, cobrar, revisar comprobantes, suspender y habilitar FRIGOR.
El contrato comienza sin cobro hasta que Jesús lo active; el permiso remoto es obligatorio.
Sin conexión, un permiso válido dura hasta 24 horas desde la última validación, sin superar el período pagado.
La suspensión impide nuevas entregas, altas y QR; conserva consultas, exportaciones y sincronización del historial.
La versión antigua FRIGOR 1.7.1 no tiene control de cobros: hay que actualizar todos los teléfonos.
Menú > App del obrero: verificar carnet, elegir trabajador y vincular su celular opcional.
Actualizar TODOS los encargados a 1.11.0 antes de activar credenciales digitales.
Mi Comedor 1.0.0 es otra APK liviana para obreros; nunca entregarles esta APK de encargado.
El QR digital cambia cada 30 segundos y funciona sin internet con credencial vigente.
Activaciones, revocaciones y reemplazos requieren internet del encargado.
Los encargados sin conexión conocen revocaciones al sincronizar.
Menú > Imprimir QR en PDF: elegir planta, Carta/Oficio/Legal y QR de 7 u 8 cm.
Generar, ver, guardar o compartir. Imprimir al 100%, sin Ajustar a página.
No publicar esta APK: incluye datos personales y el acceso privado de la red de FRIGOR.
''',encoding='utf-8')
    print(json.dumps(result))


if __name__=='__main__':main()
