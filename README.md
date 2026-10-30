# SOBOCE Comedor

Aplicación Android nativa para encargados de comedor. Mínimo Android 8.0. La cámara lee QR físicos o digitales. Google Sheets almacena el padrón y las entregas; un único proyecto de Apps Script autoriza todos los escaneos.

## Funciones

- Acceso individual cifrado en Android Keystore, con planta y rol verificados en el servidor.
- Un desayuno, almuerzo y cena por persona y día, en el conjunto de plantas. Fecha del servidor en America/La_Paz.
- Validación y escritura bajo un bloqueo global de Apps Script. No usar varios proyectos de Apps Script que escriban sobre el mismo registro.
- Reintentos con identificador persistente: una respuesta perdida se recupera sin autorizar otra ración.
- QR de trabajadores emitidos por administración; reemplazarlos revoca la credencial anterior, sin borrar consumos.
- Visitantes individuales con documento normalizado, comidas autorizadas, centro de costo, planta anfitriona y vencimiento.
- Consulta diaria y exportación mensual CSV compatible con Excel.
- Alta de encargados desde la cuenta administradora. Desactivación desde la hoja Operadores (activo = FALSE).

## Activar Google Sheets en la cuenta de Jesús Colbert

Atajo: `private/InstalarComedor.gs` reúne el servicio y el padrón. Pegar este único archivo en Code.gs y ejecutar `instalarComedor` crea las hojas, importa el padrón y genera el primer acceso de Jesús Colbert. Luego completar los pasos 6–8. Si Google pide permisos, debe autorizarlos el titular de la cuenta. No publicar este instalador en GitHub: contiene datos de personas.

1. Entrar en https://script.google.com/ con la cuenta correcta y crear un proyecto llamado SOBOCE Comedor.
2. Copiar `backend/Code.gs` al archivo Code.gs. En Configuración del proyecto, habilitar el manifiesto y sustituir appsscript.json por `backend/appsscript.json`.
3. Ejecutar `setup` y autorizar el acceso a hojas de cálculo. Se crea un archivo privado; su enlace aparece en el registro de ejecución. Volver a ejecutar setup no borra las hojas existentes.
4. Crear otro archivo de script llamado ImportarPadron. Copiar el contenido de `private/ImportarPadron.gs` y ejecutar `importRoster`. Importa 169 códigos SAP individuales una sola vez. Revisar los 18 registros excluidos de `private/revisar_identidades.json` antes de habilitarlos.
5. Ejecutar `createFirstAdmin` UNA VEZ. Guardar la clave de 64 caracteres mostrada en el registro. Esta clave permite administrar el sistema; no compartirla con encargados.
6. Implementar > Nueva implementación > Aplicación web. Ejecutar como el propietario y permitir acceso a Cualquier usuario (incluidos anónimos). La API verifica una credencial aleatoria de 64 caracteres hexadecimales en cada operación; el archivo de Sheets debe permanecer privado. Una cuenta Workspace podría restringir esta modalidad: comprobarlo antes del piloto.
7. Instalar la APK y pegar la URL terminada en /exec y la clave del administrador. No se incluyen credenciales ni nombres de trabajadores dentro de la APK.
8. Elegir planta, emitir las credenciales y compartir/guardar las tarjetas. Crear una clave propia por encargado y configurar cada teléfono.
9. Modificar el código requiere editar la implementación y seleccionar una nueva versión para mantener la misma URL.

## Preparación del padrón

`scripts/import_roster.py` lee el Excel original sin modificarlo. Los consumos históricos no se importan debido a las inconsistencias detectadas. Las 169 personas con SAP se preparan con las tres comidas habilitadas: revisar permisos individuales antes de operar. Los códigos 1–18 incluyen personas externas y conceptos agregados; requieren identificación individual para cumplir una ración por persona. No se habilitan QR colectivos ilimitados.

Los trabajadores pueden consumir en cualquier planta activa, con límite global. Los visitantes solo en su planta anfitriona. Al registrar un visitante, usar siempre el mismo formato de documento (tipo + país + número). Confirmar que no sea ya trabajador: el archivo original no contiene documentos para cruzar automáticamente visitantes con SAP.

## Operación

La pantalla de escaneo indica planta y comida. Confirmar registra la entrega; servir únicamente ante «Entrega permitida». Ante «Registro anterior confirmado», comprobar si ya se entregó la ración: nunca servir una segunda. Si hay corte de red, usar Comprobar pendiente. No se autoriza sin conexión. Las tarifas vigentes se guardan en cada entrega para preservar el costo histórico. El corte diario es medianoche de Bolivia; turnos que crucen medianoche requieren una política adicional.

No editar, borrar o agregar filas directamente en Consumos durante la operación. Limitar la edición de las hojas a administración. Una modificación manual no participa del bloqueo de Apps Script. Las correcciones/anulaciones auditadas no están implementadas en esta primera versión.

Un QR estático se puede prestar: el encargado debe contrastar el nombre. No se han incorporado fotografías ni reconocimiento facial. Guardar la tarjeta al emitirla: por seguridad el servidor conserva solo la huella del QR, no su contenido recuperable.

## Compilar

La compilación se realizará en GitHub Actions por la restricción del firewall local. El ejecutor prepara JDK 17, Gradle 8.9 y Android SDK 35. El tipo pilot aplica R8 y reducción de recursos. La clave de firma de producción preparada localmente está en private y debe respaldarse para futuras actualizaciones.

GitHub Actions en `.github/workflows/android.yml` prueba el backend y genera una APK piloto optimizada, firmada con una clave de prueba del ejecutor. La identidad de firma del piloto puede cambiar entre compilaciones; para actualizaciones estables se necesita configurar la firma de producción. La rama del proyecto es `soboce-comedor` en `jesuscolbert/qr_Repositorio`. Subir solamente código: .gitignore excluye Excel, padrón, claves y herramientas. El flujo no aloja la base de datos.

## Verificación

`node --test tests/backend.test.cjs` ejecuta casos de duplicados entre plantas, recuperación de respuesta perdida, revocación, permisos, vencimientos y precios históricos con un adaptador de Sheets en memoria. No sustituye una prueba con dos teléfonos y la implementación real de Apps Script. Las cuotas y la latencia de Google deben medirse en la hora de mayor afluencia antes de producción.

## Archivos privados

No subir `private/` a GitHub. Incluye el padrón, registros por revisar y la firma de Android. La APK no necesita ninguno de esos archivos durante la instalación. No compartir el Excel original como parte de un repositorio público.

## Activación pendiente

Google Sheets y Apps Script todavía deben desplegarse en la cuenta administradora siguiendo los pasos anteriores. Antes de operar el comedor, configurar la URL y las credenciales en los teléfonos y probar dos dispositivos contra la misma implementación. La compilación de la APK no activa por sí sola el servicio de Google.
