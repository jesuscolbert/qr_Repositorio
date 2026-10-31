# SOBOCE Comedor

Aplicación Android para encargados de comedor. Android 8 o posterior; teléfonos ARM64. Los usuarios presentan un QR físico o digital; no necesitan otra aplicación.

## Licencias y cobros — 1.7.0

SOBOCE incorpora el cliente de permisos de **Colbert Cobros**, administrado desde otra APK privada en el teléfono de Jesús Colbert. Un contrato cubre todos los equipos del mismo cliente y aplicación. El permiso dura hasta 24 horas desde su validación, sin superar el período pagado. Admite cobros diarios/mensuales, precio y QR de pago editables y comprobantes sujetos a aprobación manual. El contrato inicial no tiene cobro activado. Los registros se conservan y se pueden consultar/exportar durante la suspensión.

Instalar primero el servidor de cobros y luego la actualización del comedor en todos los teléfonos. Mantener el servidor con internet; no puede recibir pagos ni emitir permisos mientras está apagado. Las versiones antiguas no tienen este control. Instrucciones, API, respaldo cifrado e integración de futuros programas en [licensing/README.md](licensing/README.md). La compilación continúa en GitHub; los instaladores privados no se publican allí.

## Diseño y atención continua — 1.6.0

El menú usa tarjetas de colores para Desayuno, Almuerzo, Cena y Dieta. «Iniciar escaneo» abre una cámara continua: cada lectura registra directamente la comida y planta elegidas. Solo una respuesta de entrega guardada produce «ACCESO PERMITIDO» en verde, con marca de confirmación. Un duplicado o resultado recuperado muestra «YA REGISTRADO» en rojo, con aviso sonoro y vibración. No se afirma que la persona haya recibido físicamente dos raciones. El teléfono vuelve al lector después de 1,7 segundos en verde y 3,2 segundos ante un rechazo. Una tarjeta que permanece ante la cámara no genera lecturas nuevas continuamente: debe retirarse para volver a presentarla. Los avisos sonoros respetan la configuración de volumen del teléfono.

Una respuesta incierta queda en ámbar y bloquea el escaneo hasta comprobar la operación pendiente. No se muestra verde ni se procesa otro QR mientras se espera confirmación. El identificador de solicitud se conserva ante pausa, reinicio o respuesta perdida. Salir del lector libera la cámara.

Dieta reemplaza al almuerzo. Comparten la clave diaria de consumo, aunque la etiqueta del reporte conserva «dieta». Se usa la tarifa de almuerzo vigente. Un QR con almuerzo autorizado permite elegir dieta; una autorización de visitante exclusivamente para dieta no permite almuerzo normal. La opción no concede una cuarta ración adicional. Si equipos aislados registran dieta y almuerzo para la misma persona, ambas entregas se mantienen y se marcan como posible duplicado al sincronizar.

Los reportes permiten elegir día o mes con calendario, muestran raciones, importe y desglose por las cuatro comidas y exportan CSV para Excel. El reporte diario muestra todos sus registros; el mensual muestra los últimos 100 y exporta todos. Una fecha imposible se rechaza. «Entregas de hoy» abre el reporte diario, con actualización automática. Los reportes siguen reflejando la copia local conocida por el teléfono.

Actualizar todos los encargados a 1.6. La sincronización usa protocolo 5 para impedir que una versión antigua interprete Dieta como una ración adicional. Se conservan datos, QR, red y firma de instalación. El empaquetador produce `private/installer-1.6.0/SOBOCE-Comedor-1.6.0-Todos.apk` para todos. El código de Apps Script también incorpora Dieta y reporte diario; si se usa ese modo opcional, debe actualizarse su implementación por separado.

## APK única y entregas compartidas — 1.5.0

La misma APK privada se instala en todos los teléfonos, encima de la versión anterior y sin borrar datos. Cada instalación genera un identificador aleatorio propio. Un teléfono nuevo pide solamente nombre del encargado y planta; una actualización conserva su perfil. Ya no se importan JSON ni se copian bases `.db`.

«Entregas de hoy» muestra las entregas de todas las plantas, con hora, comida y encargado, y se actualiza cada cinco segundos mientras está abierta. El total incluye todas las filas; se muestran las últimas 500. El CSV mensual reúne todas las plantas. La sincronización comparte también el historial anterior, no solo el día actual.

### Una URL y relevo automático

Todos usan `https://comedor.colbertconsorcio.shop`. El primer teléfono que obtiene el permiso del coordinador abre el túnel. Los demás envían y reciben páginas de registros con ese servidor cada cinco segundos. Cuando desaparece, otro obtiene el permiso y usa el mismo túnel y URL. La elección puede tardar unos 30 segundos más la reconexión del túnel. Si el servidor conserva internet pero su túnel no responde, cede el puesto después del período de comprobación.

El coordinador es un Worker con un Durable Object SQLite de Cloudflare: guarda solo identidad temporal del servidor, vencimiento y número de relevo, **ningún consumo ni padrón**. Es un servicio externo necesario para arbitrar la URL única; la PC no participa durante el servicio. Solo quien tiene un permiso vigente puede responder; un reloj monotónico y un margen de seguridad detienen el receptor anterior. Los clientes rechazan respuestas de otro relevo. No se confunde una réplica de Cloudflare Tunnel con una copia de la base de datos.

Si no hay coordinación o conexión, cada teléfono sigue registrando localmente. Para compartir datos deben coincidir con internet al menos dos equipos que tengan la app o servicio activos. Android puede detener la sincronización por batería: abrir la app la reanuda. No se garantiza un servidor siempre encendido en segundo plano ni relevo instantáneo.

### Consumos, reintentos y datos sin conexión

Con servidor accesible, las solicitudes de entrega se resuelven en una transacción del servidor y se replican. Si una respuesta se pierde después de enviar la solicitud, esta queda pendiente: no se autoriza automáticamente una segunda entrega local. Un relevo que aún no tenga esa operación necesitará recuperar la copia del servidor anterior para resolver la incertidumbre.

Sin servidor accesible, la operación se guarda en SQLite local. Dos equipos aislados pueden servir la misma comida; al reunirlos se conservan ambas entregas y se marcan `POSIBLE DUPLICADO`. No hay bloqueo global inmediato sin conexión. Un servidor nuevo tampoco puede conocer los datos que solo estaban en un teléfono apagado: se incorporan cuando este regresa. El contador indica pendientes de llegar al servidor; en el servidor indica entregas propias aún sin copia confirmada en otro equipo.

La actualización conserva las tablas, firmas, QR SB1/SB2 y consumos de las versiones anteriores. Los nuevos QR SB3 y recibos se autentican con una clave de esta red privada. El identificador de cada teléfono evita colisiones accidentales, pero el esquema confía en los encargados que reciben la APK: no constituye una identidad individual resistente a la extracción o manipulación de la APK. La clave de red y la credencial del único túnel están incluidas; **no** se incluye ninguna clave de administración de Cloudflare ni las claves privadas de los cuatro equipos anteriores. Distribuir solo a encargados autorizados. Todos deben actualizar a 1.5 para intercambiar registros nuevos; los cuatro túneles anteriores se conservan para no interrumpir instalaciones antiguas.

### Preparación y compilación

1. `scripts/provision_shared.py` crea el túnel exclusivo y el paquete privado base, sin cambiar los otros servicios.
2. Publicar `backend/coordination/wrangler.jsonc` en la cuenta correspondiente, con el secreto `NETWORK_KEY` igual al del paquete privado. Requiere permisos de Workers Scripts; el certificado de túneles no basta. No activar un plan de pago automáticamente.
3. `scripts/provision_shared.py --coordinator https://URL-DEL-WORKER` completa la configuración.
4. La compilación y las pruebas Android se hacen en GitHub Actions, rama `soboce-comedor`.
5. `scripts/package_universal.py APK-DE-GITHUB` añade el paquete privado, comprueba que el código y los recursos permanecen iguales, alinea y firma con la clave estable. Produce **un solo archivo** `private/installer-1.5.0/SOBOCE-Comedor-1.5.0-Todos.apk` para todos. No compila Android localmente.

La APK genérica de GitHub no lleva datos ni credenciales. No publicar la APK privada o su paquete de configuración en el repositorio público. `scripts/package_installers.py` y `provision_peers.py` quedan únicamente para instalaciones antiguas 1.4.

## Funciones del modo Google Sheets

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

Los trabajadores pueden consumir en cualquier planta activa. En Sheets el límite es global inmediato; en P2P se aplica a los registros conocidos localmente y se revisan coincidencias posteriores. Los visitantes solo en su planta anfitriona. Al registrar un visitante, usar siempre el mismo formato de documento (tipo + país + número). Confirmar que no sea ya trabajador: el archivo original no contiene documentos para cruzar automáticamente visitantes con SAP.

## Operación

La pantalla de escaneo indica planta y comida. Confirmar registra la entrega; servir únicamente ante «Entrega permitida». Ante «Registro anterior confirmado», comprobar si ya se entregó la ración: nunca servir una segunda. En P2P se registra sin conexión; «Comprobar pendiente» recupera una operación cuyo resultado no llegó a mostrarse. En Sheets se sigue necesitando conexión. Las tarifas vigentes se guardan en cada entrega para preservar el costo histórico. El corte diario es medianoche de Bolivia; turnos que crucen medianoche requieren una política adicional.

No editar, borrar o agregar filas directamente en Consumos durante la operación. Limitar la edición de las hojas a administración. Una modificación manual no participa del bloqueo de Apps Script. Las correcciones/anulaciones auditadas no están implementadas en esta primera versión.

Un QR estático se puede prestar: el encargado debe contrastar el nombre. No se han incorporado fotografías ni reconocimiento facial. Guardar la tarjeta al emitirla. En Sheets se conserva solo su huella; en P2P las emisiones anteriores forman parte del historial privado replicado; las tarjetas nuevas se emiten localmente y su uso queda registrado al confirmar una comida.

## Compilar

La compilación se realizará en GitHub Actions por la restricción del firewall local. El ejecutor prepara JDK 17, Gradle 8.9 y Android SDK 35. El tipo pilot aplica R8 y reducción de recursos. La clave de firma de producción preparada localmente está en private y debe respaldarse para futuras actualizaciones.

GitHub Actions en `.github/workflows/android.yml` prueba el backend, el protocolo, SQLite y el ejecutable nativo en un emulador, y genera una APK piloto optimizada. La firma estable se restaura desde secretos de Actions; no se publica en el repositorio. Las compilaciones antiguas con firma de prueba distinta no se actualizan directamente: conservar sus datos antes de cualquier cambio de instalación. La rama del proyecto es `soboce-comedor` en `jesuscolbert/qr_Repositorio`. Subir solamente código: .gitignore excluye Excel, padrón, claves y herramientas. El flujo no aloja la base de datos.

## Verificación

`node --test tests/backend.test.cjs` ejecuta casos de duplicados entre plantas, recuperación de respuesta perdida, revocación, permisos, vencimientos y precios históricos con un adaptador de Sheets en memoria. No sustituye una prueba con dos teléfonos y la implementación real de Apps Script. Las cuotas y la latencia de Google deben medirse en la hora de mayor afluencia antes de producción.

## Archivos privados

No subir `private/`, `config/`, `config.zip` ni certificados a GitHub. Incluyen el padrón, credenciales, registros por revisar y firma de Android. La APK genérica de GitHub no incorpora datos reales. La APK privada para todos sí incluye el padrón y el acceso de la red, y configura cada teléfono automáticamente. No compartir el Excel original ni los archivos de instalación como parte de un repositorio público.

## Activación pendiente

Google Sheets y Apps Script todavía deben desplegarse si se elige ese modo. En la red compartida, instalar la misma APK privada en todos, conservar sus datos y probar registro sin red, duplicados locales, convergencia y revisión de conflictos en los equipos reales. Las pruebas de GitHub verifican el protocolo, persistencia SQLite y un túnel real desde el emulador con datos sintéticos. No sustituyen la prueba de servicio en los cuatro teléfonos, especialmente con pantalla apagada y restricciones de batería.
