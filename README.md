# SOBOCE Comedor

Aplicación Android nativa para encargados de comedor. Mínimo Android 8.0; el modo de teléfonos requiere ARM64. La cámara lee QR físicos o digitales. Ofrece dos modos independientes: Google Sheets con Apps Script, y una red de cuatro teléfonos con bases SQLite replicadas. No mezclar ambos registros durante un servicio: no existe sincronización automática entre Sheets y P2P.

## Registro con un solo teléfono — piloto 1.3.0

Un encargado puede registrar desayuno, almuerzo y cena con un solo teléfono, aunque no haya internet, los demás estén apagados o el servicio de sincronización esté detenido. La entrega se confirma después de guardar el consumo y su resultado en una transacción SQLite local. Cada teléfono bloquea un segundo consumo conocido de la misma persona, fecha de Bolivia y comida. Los reintentos y el reinicio recuperan el resultado sin autorizar otra ración.

**Límite del funcionamiento sin conexión:** dos teléfonos aislados pueden autorizar la misma comida antes de conocer el registro del otro. No existe bloqueo global inmediato en ese caso. Al sincronizar, ambos consumos se conservan y aparecen como `POSIBLE DUPLICADO` en «Revisar duplicados» y en el CSV mensual. Los totales incluyen todas las entregas registradas; no se elimina ninguna automáticamente ni se decide cuál fue realmente servida. La revisión es informativa: todavía no hay anulación o resolución auditada dentro de la app.

### Sincronización posterior

Cada dispositivo firma sus registros y mantiene una secuencia propia. Los equipos intercambian páginas de registros faltantes mediante los túneles existentes; cada uno puede retransmitir los registros de los demás. El receptor verifica la firma, el origen, la planta y la coherencia del registro, rechaza cambios en una secuencia ya conocida y guarda cada página de forma atómica. Reenviar una página no vuelve a crear consumos. Se conservan las cuatro identidades y las configuraciones `SOBOCE-P2P-2` existentes; no se generan claves o túneles nuevos al actualizar.

Registrar requiere **un teléfono**. Sincronizar requiere que al menos dos coincidan con internet y la app/servicio activos; los otros reciben el historial cuando vuelvan a coincidir con un equipo que lo tenga. Sin una PC o servidor de datos siempre encendido, no se puede sincronizar entre teléfonos que nunca están disponibles al mismo tiempo. El contador de pendientes indica cuántas entregas locales todavía no han sido confirmadas en todos los otros equipos; puede ser mayor que cero aunque ya exista una copia en alguno.

El servicio intenta sincronizar periódicamente. «Sincronizar ahora» inicia otra comprobación sin bloquear el registro local. Si Android detiene el servicio por batería/Doze, abrir la app al recuperar internet reanuda los intentos. Mantener fecha y hora automáticas: la fecha de entrega se toma del teléfono en `America/La_Paz`, porque sin conexión no hay un reloj central. Un retroceso importante respecto de la última entrega local exige corregir la hora.

### Actualizar sin perder el historial

Instalar la versión 1.3.0 sobre la anterior, sin desinstalar ni borrar datos. Actualizar los cuatro teléfonos antes de usarlos juntos: las versiones 1.1/1.2 no reciben los registros nuevos. Un teléfono actualizado ya puede registrar solo. Se conserva el historial anterior, los consumos, los resultados de reintentos y los votos históricos. Se pueden recuperar decisiones antiguas de otros teléfonos; ya no se emiten votos nuevos ni se exige una mayoría para entregar comidas. Los datos nuevos se guardan en un registro independiente de recibos firmados. Si llega después un consumo antiguo que coincide con uno local, también queda visible para revisión.

### Emitir y compartir QR

Cada encargado puede crear, guardar y compartir QR de trabajadores de su planta con su teléfono solamente. También puede generar QR temporales para visitantes, indicando documento, planta, comidas, centro de costo y vencimiento de hasta 30 días. Crear otra tarjeta no revoca las anteriores. Todas identifican al mismo trabajador por SAP o al mismo visitante por su documento normalizado; el bloqueo usa los consumos conocidos por cada teléfono. Los QR anteriores siguen reconociéndose cuando su registro de emisión ya está en la copia local. Los visitantes solo consumen en la planta y comidas autorizadas por su tarjeta.

### Instalación

Cada teléfono ejecuta un receptor local y su propio conector Cloudflare. La PC no participa durante el servicio. Los cuatro túneles y direcciones existentes son distintos; no reutilizar los túneles de caja, GPS, juego o delivery.

1. La preparación inicial se realiza con `scripts/provision_peers.py --plan` y `--apply`. Usa `config/cert.pem` para crear los túneles exclusivos y genera los archivos privados `telefono-1.json` a `telefono-4.json`. No ejecutar para reemplazar una red en uso.
2. Instalar la APK e importar el archivo privado correspondiente a ese teléfono. Cada archivo contiene el padrón, una identidad y la credencial de un túnel. No copiar una identidad a dos equipos.
3. Emitir un QR y probar las tres comidas con los otros teléfonos apagados. Repetir la misma comida debe bloquearse localmente, incluso después de reiniciar la app.
4. Reconectar dos equipos, comprobar la copia de consumos y luego incorporar los demás. Probar también un consumo repetido deliberadamente en dos equipos desconectados: deben conservarse ambos registros y marcarse para revisión.

La instalación inicial contiene 169 identidades SAP y cuatro puestos: Jesús Colbert como administrador y tres encargados de sus plantas. Los otros 18 registros del archivo original siguen excluidos. Un equipo nuevo con una identidad ya preparada descarga el historial disponible al conectarse. Agregar un quinto miembro, sustituir un teléfono perdido o cambiar tarifas/padrón requiere un procedimiento adicional. No editar archivos de una red en uso ni borrar sus bases.

Cada teléfono tiene una clave ECDSA privada diferente, cifrada con Android Keystore. La replicación presupone dispositivos administrados y confiables; una firma acredita quién registró una entrega, no demuestra que la ración fue físicamente servida. El historial privado incluye datos personales y las credenciales usadas para registrar. Cloudflare transporta conexiones; las bases permanecen en los teléfonos. El conector oficial `cloudflare/cloudflared` 2026.10.6 se compila para Android en GitHub.

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

No subir `private/`, `config/`, `config.zip` ni certificados a GitHub. Incluyen el padrón, credenciales, registros por revisar y firma de Android. La APK universal no incorpora datos reales: en modo P2P cada teléfono importa solo su propio archivo privado. No compartir el Excel original ni los archivos de instalación como parte de un repositorio público.

## Activación pendiente

Google Sheets y Apps Script todavía deben desplegarse si se elige ese modo. En P2P, conservar/importar las configuraciones privadas y probar registro sin red, duplicados locales, convergencia y revisión de conflictos en los equipos reales. Las pruebas de GitHub verifican el protocolo, persistencia SQLite y un túnel real desde el emulador con datos sintéticos. No sustituyen la prueba de servicio en los cuatro teléfonos, especialmente con pantalla apagada y restricciones de batería.
