# SOBOCE Comedor

Aplicación Android nativa para encargados de comedor. Mínimo Android 8.0; el modo de teléfonos requiere ARM64. La cámara lee QR físicos o digitales. Ofrece dos modos independientes: Google Sheets con Apps Script, y una red de cuatro teléfonos con bases SQLite replicadas. No mezclar ambos registros durante un servicio: no existe sincronización automática entre Sheets y P2P.

## Red de cuatro teléfonos (piloto)

Cada teléfono ejecuta un receptor local y su propio conector Cloudflare. La PC solo prepara la instalación; no participa durante el servicio. Se necesitan cuatro túneles y cuatro nombres diferentes. Las réplicas de un mismo túnel distribuyen conexiones, no replican bases de datos: no reutilizar el túnel de caja, GPS, juego o delivery.

El historial usa Paxos con membresía fija de cuatro equipos. Una decisión exige tres votos persistidos; luego cada teléfono aplica las operaciones en el mismo orden. Un teléfono desconectado descarga las decisiones que le faltan al volver. La clave de consumo sigue siendo persona + fecha de Bolivia + comida, global para todas las plantas. El cambio de QR y las autorizaciones de visitantes se ordenan en el mismo historial. Los reintentos recuperan el resultado sin autorizar otra ración.

Con dos o menos equipos conectados no se autorizan entregas. Los informes muestran la copia local y pueden estar atrasados. Se requieren internet, fecha automática y el servicio de red activo en al menos tres teléfonos. La app muestra una notificación mientras mantiene la conexión. Android puede suspender la red por batería/Doze; eso reduce disponibilidad, no habilita entregas sin mayoría.

Preparación inicial, desde esta carpeta de trabajo:

1. `python scripts/provision_peers.py --plan` muestra los cuatro nombres propuestos.
2. `python scripts/provision_peers.py --apply` verifica el ejecutable oficial de Cloudflare, usa `config/cert.pem` para crear los cuatro túneles exclusivos y genera `private/peer-network/telefono-1.json` a `telefono-4.json`. Conserva el estado para reanudar y no sobrescribe DNS existente. No cambia los archivos originales de `config/`.
3. Instalar la APK compilada por GitHub e importar el archivo privado correspondiente en cada teléfono. Cada archivo incluye padrón y credencial de UN túnel. El certificado administrador nunca se incluye. No duplicar un archivo en dos equipos.
4. Iniciar la red y comprobar tres o cuatro equipos disponibles. Emitir QR y probar simultáneamente el mismo almuerzo desde dos plantas; debe existir una sola autorización. Probar también corte de internet, respuesta perdida, reinicio y recuperación de historial antes de usar consumos reales.

La instalación inicial tiene 169 identidades SAP, las tarifas del modelo y cuatro puestos: Jesús Colbert como administrador; los otros tres como encargados de sus plantas. Los otros 18 registros siguen excluidos. Un supervisor que regresa con su teléfono recupera el historial automáticamente. Agregar un quinto votante, cambiar la membresía, revocar un teléfono perdido y cambiar tarifas/padrón de una red ya iniciada requieren un procedimiento adicional; no editar archivos de instalación de una red en uso.

No borrar los datos ni reinstalar para actualizar: las promesas, votos y decisiones deben conservarse. Un teléfono perdido o borrado debe permanecer fuera de servicio hasta restaurar o sustituir su identidad mediante un procedimiento controlado. No cambiar el mínimo de votos a dos. El protocolo presupone cuatro dispositivos administrados y confiables; no es un consenso resistente a participantes maliciosos. La clave de red autentica mensajes y confirmaciones y se guarda cifrada en Android Keystore. El historial local contiene datos personales y credenciales QR para su replicación: proteger físicamente los equipos.

El conector se compila desde el código oficial de `cloudflare/cloudflared` 2026.10.6 en GitHub, con NDK y Go; aumenta el tamaño de la APK. La compatibilidad real de esta integración Android debe pasar las pruebas del emulador y de los teléfonos antes de producción. Cloudflare transporta las conexiones; las bases permanecen en los teléfonos. No se utiliza un servidor de aplicación en la PC.

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

Un QR estático se puede prestar: el encargado debe contrastar el nombre. No se han incorporado fotografías ni reconocimiento facial. Guardar la tarjeta al emitirla. En Sheets se conserva solo su huella; en P2P las operaciones de emisión forman parte del historial privado replicado.

## Compilar

La compilación se realizará en GitHub Actions por la restricción del firewall local. El ejecutor prepara JDK 17, Gradle 8.9 y Android SDK 35. El tipo pilot aplica R8 y reducción de recursos. La clave de firma de producción preparada localmente está en private y debe respaldarse para futuras actualizaciones.

GitHub Actions en `.github/workflows/android.yml` prueba el backend, el protocolo, SQLite y el ejecutable nativo en un emulador, y genera una APK piloto optimizada. La firma estable se restaura desde secretos de Actions; no se publica en el repositorio. Las compilaciones antiguas con firma de prueba distinta no se actualizan directamente: conservar sus datos antes de cualquier cambio de instalación. La rama del proyecto es `soboce-comedor` en `jesuscolbert/qr_Repositorio`. Subir solamente código: .gitignore excluye Excel, padrón, claves y herramientas. El flujo no aloja la base de datos.

## Verificación

`node --test tests/backend.test.cjs` ejecuta casos de duplicados entre plantas, recuperación de respuesta perdida, revocación, permisos, vencimientos y precios históricos con un adaptador de Sheets en memoria. No sustituye una prueba con dos teléfonos y la implementación real de Apps Script. Las cuotas y la latencia de Google deben medirse en la hora de mayor afluencia antes de producción.

## Archivos privados

No subir `private/`, `config/`, `config.zip` ni certificados a GitHub. Incluyen el padrón, credenciales, registros por revisar y firma de Android. La APK universal no incorpora datos reales: en modo P2P cada teléfono importa solo su propio archivo privado. No compartir el Excel original ni los archivos de instalación como parte de un repositorio público.

## Activación pendiente

Google Sheets y Apps Script todavía deben desplegarse si se elige ese modo. En P2P, importar las cuatro configuraciones privadas y comprobar conectividad, escaneo simultáneo y recuperación en los equipos reales. Las pruebas de GitHub verifican el protocolo, persistencia SQLite y un túnel real desde el emulador con datos sintéticos. No sustituyen la prueba de servicio en los cuatro teléfonos, especialmente con pantalla apagada y restricciones de batería.
