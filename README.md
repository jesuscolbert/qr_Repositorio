# SOBOCE Comedor

## Credencial opcional del obrero (encargados 1.11.0 / Mi Comedor 1.0.0)

`worker/` genera la APK independiente **Mi Comedor**, paquete `bo.colbert.obrero`. Solo muestra el QR propio y los consumos recibidos de los últimos 30 días. No incorpora padrón, precios, claves del encargado, licencias administrativas, servidor ni túnel. Los QR impresos y la impresión por plantas siguen disponibles. El encargado conserva el control de acceso mediante Colbert Cobros.

La sincronización de credenciales usa protocolo 7 y la base migra a versión 6 sin eliminar datos. Actualizar primero todos los encargados, conservando sus datos y la variante SOBOCE o FRIGOR que ya tienen instalada. Las versiones anteriores no reconocen los recibos del QR dinámico. Desplegar las dos configuraciones existentes de `backend/coordination` sin cambiar sus secretos NETWORK_KEY ni sus Durable Objects.

Activación presencial:

1. El obrero instala Mi Comedor y desbloquea con el PIN, patrón o contraseña del teléfono. En «Activar mi credencial» muestra la solicitud QR.
2. El encargado entra en «App del obrero», busca el registro existente de la empresa/planta y comprueba el carnet original y su titular. Escanea la solicitud y confirma la identidad. Necesita internet y licencia vigente para vincular.
3. El obrero escanea la autorización que muestra el encargado y confirma sus datos. No crea otro usuario: ambos tipos de QR resuelven al mismo trabajador y al mismo límite por día/comida; dieta comparte el límite de almuerzo.

La solicitud dura 5 minutos y está ligada a una clave P-256 no exportable de Android Keystore. La autorización solo sirve para esa clave y esa solicitud. El QR de comida cambia cada 30 segundos y se acepta con 15 segundos de tolerancia de reloj; requiere hora automática. La clave requiere el desbloqueo del teléfono y la app vuelve a bloquearse tras 4 minutos. Android impide las capturas normales de estas pantallas; esto no impide fotografiar la pantalla o prestar el teléfono. El encargado debe comprobar la identidad al escanear el QR digital. No hay fotos ni reconocimiento biométrico del trabajador en esta versión.

El QR funciona sin internet hasta 30 días desde la activación o última renovación. Al abrir la app con conexión consulta el historial y renueva si hay un encargado servidor disponible y la vinculación sigue vigente; también hay actualización manual. El historial descargado se cifra localmente. No registra comidas ni da acceso a otros trabajadores. `/wallet` en el servidor del encargado exige una solicitud firmada por el celular vinculado, controla repeticiones y devuelve solo los movimientos de esa persona. Las respuestas no se almacenan en cachés HTTP.

El coordinador mantiene únicamente la vinculación seudónima, huella de clave, versión y auditoría del encargado. No guarda nombres/carnets de obreros ni consumos. Su transacción impide que dos encargados autoricen simultáneamente dos celulares con la misma versión. Reemplazar o revocar requiere conexión; los encargados reciben los cambios al sincronizar. Un encargado aislado puede seguir aceptando una credencial revocada hasta recibir el cambio o hasta que venza. La operación sin conexión tampoco puede impedir duplicados entre encargados incomunicados; se conservan para revisión posterior. Los consumos legítimos anteriores a una revocación no desaparecen.

Un teléfono perdido se revoca desde el encargado, y el reemplazo exige otra verificación presencial. Reinstalar o borrar datos pierde la clave local y requiere nueva activación. El QR físico sigue siendo válido como alternativa: revocar un celular no revoca la tarjeta física. La app del obrero no sustituye ni elude el control de pagos de la app del encargado.

## Credenciales PDF para impresión — 1.10.0

**Menú → Imprimir QR en PDF** o **Trabajadores y QR → Imprimir QR de esta planta** permite generar un lote de trabajadores activos de la empresa actual. Elegir una planta o todas separadas, QR de **7 × 7 cm** u **8 × 8 cm**, y hoja **Carta (21,59 × 27,94 cm)**, **Oficio (21,59 × 33,02 cm)** o **Legal/Oficio (21,59 × 35,56 cm)**. Se muestran las tarjetas que caben por hoja antes de generar. Cada planta empieza en una página nueva; no se mezclan empresas ni se incluyen visitantes o inactivos.

El tamaño corresponde al cuadrado QR completo con margen blanco; nombre, carnet/SAP y planta van debajo. Los códigos son vectoriales y las líneas punteadas sirven para cortar. Imprimir **al 100% / Tamaño real**, desactivando Ajustar a página. Carta admite 4 QR de ambos tamaños; Oficio admite 6 de 7 cm o 4 de 8 cm; Legal admite 6 de ambos tamaños.

El PDF puede abrirse en un visor, compartirse o guardarse con el selector de archivos Android. El exportador usa la base local: sincronizar previamente para incluir altas de otros teléfonos. Generar o compartir requiere el permiso vigente de esa empresa; no cambia el control de Colbert Cobros. Las tarjetas anteriores permanecen válidas y la exportación no registra comidas. No se regeneran identidades ni se modifican los consumos.

Las pruebas exportan seis PDF con datos ficticios, los rasterizan y vuelven a leer cada QR, verifican el tamaño físico y la separación por plantas, y comprueban un lote de 600 tarjetas. Los PDF de prueba se adjuntan al artefacto de validación de GitHub; no contienen credenciales de producción.

## Empresas y plantas en una misma app — 1.9.0

**Menú → Cambiar empresa** o el nombre de la empresa en la barra superior permite pasar entre SOBOCE y FRIGOR. Inicio muestra todas las plantas de la empresa elegida. **Nuevo trabajador** incorpora su propio selector de planta para registrar el carnet y nombre opcional, y generar el QR. El instalador privado autoriza la gestión de todas las plantas de ambas empresas; no concede acceso a la administración de Colbert Cobros.

Cada empresa conserva una base de datos, perfil, operaciones pendientes y licencia independientes. Un QR de una empresa se rechaza en la otra. Al cambiar se detiene el servicio anterior, se conserva su historial y se abre la red de la empresa seleccionada. **Se sincroniza una empresa a la vez**; abrir cada empresa con internet permite recibir sus registros. Una entrega pendiente debe comprobarse antes de cambiar.

Colbert Cobros mantiene un contrato por empresa y aplicación. El propietario decide el importe y la frecuencia **mensual o diaria**, solicita pagos, revisa comprobantes y habilita o suspende. Un permiso de SOBOCE no habilita FRIGOR ni al revés. Se conserva el límite sin conexión de 24 horas, sin superar el período pagado.

Para conservar los datos Android existentes se mantienen dos variantes de actualización, SOBOCE y FRIGOR: **ambas incluyen el mismo selector y las dos empresas**. Instalar la variante correspondiente sobre la aplicación que ya existe, sin desinstalar. La variante SOBOCE sirve para instalaciones nuevas. Android no copia automáticamente la base privada de otra aplicación: el historial de la segunda empresa llega al sincronizar con un teléfono que ya lo tenga. No copiar archivos JSON ni bases de datos.

El empaquetador incorpora `companies.json` únicamente al instalador privado local. El repositorio y los artefactos base de GitHub no contienen padrones ni credenciales. Los coordinadores y túneles de ambas empresas siguen separados y usan el dominio nuevo.

## Dominio nuevo — comedores 1.8.1 y Colbert Cobros 1.1.1

Las direcciones activas son `https://comedor.colbert2027.shop`, `https://frigor.colbert2027.shop` y `https://licencias.colbert2027.shop`. Los tres registros DNS apuntan a los túneles existentes. Para que respondan, sus conectores deben estar funcionando en los teléfonos.

Actualizar primero Colbert Cobros en el teléfono del propietario, abrirlo y tocar **Encender servidor**. Después actualizar SOBOCE y FRIGOR en sus teléfonos correspondientes. Instalar encima de la versión anterior, sin desinstalar ni borrar datos. Las configuraciones están incluidas en los instaladores privados: no hay que importar JSON.

La actualización cambia la URL guardada únicamente cuando el instalador firmado incluye el origen anterior y una revisión superior. Verifica que la red, túnel y contrato mantienen su identidad. Conserva usuarios, QR, entregas, solicitudes pendientes, precios, pagos y clave de firma. El cambio de dominio no amplía el permiso de 24 horas ni el período pagado. Los manifiestos antiguos son parte de la identidad criptográfica y **no se sustituyen sus URL históricas**.

Las APK base se compilan y prueban en GitHub; los scripts `package_soboce_update.py`, `package_frigor.py` y `package_billing_update.py` añaden la configuración privada localmente y verifican la misma firma de actualización. Nunca publicar esos instaladores privados. Los scripts de provisión usan el certificado de la zona autorizada en `~/.cloudflared/cert.pem` y no migran automáticamente una instalación existente.

## Menú, altas y permisos FRIGOR — 1.8.0

El botón **Menú** queda fijo en la parte superior y abre un panel lateral. **Iniciar escaneo** permanece visible abajo en Inicio, incluso al desplazar el contenido en una pantalla pequeña. Inicio conserva la comida seleccionada y el acceso al reporte diario. Trabajadores, visitantes, reportes, red, pagos y ajustes se abren desde el panel.

**Nuevo trabajador** pide carnet y nombre opcional. Conserva los ceros iniciales y admite complemento con guion. Si ya existe, devuelve su credencial sin sustituir la identidad ni el historial. Las altas se firman como tarjetas `SB4` y se replican en un registro independiente del padrón inicial. El QR puede incorporar al trabajador en otro teléfono actualizado antes de la sincronización. El documento estable mantiene el límite de comida; las altas simultáneas del mismo carnet convergen en una sola persona y se señalan para revisar el nombre. Esto no verifica que un documento declarado sea auténtico.

La sincronización usa **protocolo 6** y SQLite migra a versión 5 conservando los datos. Los rangos de secuencias del padrón permiten recibir primero un QR posterior sin omitir altas anteriores. Se conservan el manifiesto, la red, los QR previos y el historial. Actualizar todos los teléfonos de FRIGOR; 1.7.1 no entiende las nuevas altas.

FRIGOR 1.8 requiere su propio contrato en **Colbert Cobros 1.1**. Instalar primero la actualización de Cobros en el teléfono del propietario y abrirla; añade FRIGOR sin reiniciar contratos, precios, pagos, comprobantes ni la firma del servidor. Después actualizar todos los teléfonos FRIGOR, sin desinstalar. La primera validación necesita al servidor con internet. La licencia cubre todos los equipos del cliente/aplicación. Una suspensión impide nuevas entregas, altas y emisión de tarjetas, conservando el historial, su consulta y sincronización. La tolerancia sin conexión sigue limitada a 24 horas desde la validación y al período pagado. **FRIGOR 1.7.1 no puede bloquearse remotamente; debe actualizarse.**

Las APK de producción fallan cerradas si falta su configuración de licencia o corresponde a otro producto. El único target sin licencia obligatoria es `debug`, usado para pruebas sintéticas; no se distribuye. GitHub compila los binarios genéricos sin datos privados. `scripts/package_billing_update.py APK-COBROS` y `scripts/package_frigor.py APK-FRIGOR` añaden los paquetes privados y verifican la firma estable sin compilar localmente.

## FRIGOR separado — 1.7.1

La variante `frigor` instala **FRIGOR Comedor** (`bo.soboce.comedor.frigor`) junto a SOBOCE. Tiene datos y credenciales independientes y usa el puerto local 8788, evitando interferir con el receptor de SOBOCE (8787). Su red usa `https://frigor.colbertconsorcio.shop`, el túnel `frigor-comedor-compartido` y un Worker de coordinación exclusivo. No se modifican los servicios ni el padrón de SOBOCE.

`scripts/import_frigor.py USUARIO.xlsx` valida el archivo suministrado, conserva los carnets como texto y genera un padrón privado con identidad estable `FRIGOR-CI-<carnet>`. El número interno del reloj es solo una referencia. Rechaza carnets duplicados y no fabrica nombres. Los nombres numéricos o ausentes se muestran como pendientes. Las longitudes poco habituales quedan señaladas para revisión; no se eliminan personas ni se presume que el carnet está verificado. El archivo no contiene plantillas de huellas.

Las listas y tarjetas muestran CI o SAP según corresponda. Buscar por carnet no equivale a autorizar una comida: se sigue usando un QR autenticado. Reemitir tarjetas o completar el nombre de una identidad existente conserva el límite de comida de esa persona. Un trabajador con carnet conocido no puede recibir otro QR como visitante con el mismo documento. Las claves de red separadas impiden usar QR de una empresa en la otra.

El libro privado de revisión permite completar nombres; editarlo no actualiza automáticamente los teléfonos. El padrón inicial forma parte del manifiesto de red: una futura actualización debe conservar ese manifiesto, los identificadores y el historial, no regenerar la red con otro seed.

No se han suministrado tarifas de comida de FRIGOR. Los registros quedan marcados con precio pendiente, el reporte muestra raciones y el CSV deja vacío el importe pendiente. No se copian las tarifas ni el contrato de licencia de SOBOCE. El instalador histórico 1.7.1 no activaba cobros; 1.8 lo integra mediante un contrato propio.

Preparación: importar el archivo, ejecutar `scripts/provision_frigor.py`, desplegar `backend/coordination/wrangler.frigor.jsonc`, configurar su secreto `NETWORK_KEY` desde el paquete privado y completar `--coordinator`. GitHub compila y valida `:app:assembleFrigor`. `scripts/package_frigor.py APK-DE-GITHUB` incorpora únicamente los datos privados de FRIGOR y firma la APK, sin compilar localmente. No publicar la APK privada ni el Excel. Los tests solo usan datos sintéticos; el secreto temporal `FRIGOR_SHARED_TEST_CONFIG_B64` se elimina al terminar la validación inicial de su nueva conexión.

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

Desde 1.8.1 todos usan `https://comedor.colbert2027.shop`. El primer teléfono que obtiene el permiso del coordinador abre el túnel. Los demás envían y reciben páginas de registros con ese servidor cada cinco segundos. Cuando desaparece, otro obtiene el permiso y usa el mismo túnel y URL. La elección puede tardar unos 30 segundos más la reconexión del túnel. Si el servidor conserva internet pero su túnel no responde, cede el puesto después del período de comprobación.

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
