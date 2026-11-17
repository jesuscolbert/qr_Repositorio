# Colbert Comedor para iPhone

Aplicación nativa SwiftUI para encargados de SOBOCE y FRIGOR. Requiere iOS 16 o posterior. Los datos reales y las activaciones privadas no se incluyen en el repositorio.

## Funciones

- Empresa y planta desde el menú superior; desayuno, almuerzo, cena y dieta. Dieta comparte el cupo diario de almuerzo.
- Escáner vertical continuo, confirmación verde y advertencia roja de repetición. Las credenciales digitales requieren comprobar presencialmente la identidad del titular.
- Alta de trabajadores por carnet, credenciales físicas, visitas con fecha de vencimiento y vinculación/revocación de la app Android Mi Comedor.
- Entregas locales y sincronización posterior. Informes diarios y mensuales en Excel `.xlsx`, con resumen, detalle y resultados por planta; PDF de tarjetas de 7 u 8 cm, Carta, Oficio de 33 cm o Legal.
- Permiso firmado del contrato existente de cada empresa. El permiso mensual versión 2 permanece válido hasta la fecha pagada; uno temporal conserva el límite de 24 horas. Aviso durante los últimos dos días.
- Base cifrada con AES-GCM, clave en Keychain `WhenUnlockedThisDeviceOnly`, archivos protegidos y excluidos del respaldo, desbloqueo con código/Face ID/Touch ID y cubierta de privacidad al salir de la app.

## Conexión y límites operativos

El iPhone es un cliente, no un servidor de túnel. El Android elegido por la red existente continúa atendiendo la URL de cada empresa. Todos los Android que puedan tomar el relevo deben usar la versión 1.14 o posterior para ofrecer `/control`. No se modifica el coordinador ni se retiran los clientes antiguos automáticamente. Los teléfonos antiguos conservan su operación anterior, pero si toman el lugar del servidor no podrán atender al iPhone.

Primero se escanea una activación privada por empresa y se sincronizan padrón, tarifas, vinculaciones y permiso. Luego pueden registrarse comidas sin conexión. En ese estado, la detección de duplicados conoce únicamente las entregas disponibles en el iPhone; no puede impedir simultáneamente una entrega en otro equipo desconectado. Las copias coincidentes se conservan para revisión, igual que en Android.

La sincronización descarga los últimos 35 días y conserva el historial previamente recibido. Un equipo recién activado no tiene todo el historial de meses anteriores. El informe indica que refleja los datos disponibles; otros equipos pueden tener movimientos pendientes.

Antes de un registro en línea se guardan el identificador y la solicitud. Si se pierde la respuesta, el equipo solo consulta esa misma operación. No cambia silenciosamente a una segunda aprobación local. Una respuesta recuperada indica que ya se registró la entrega y que no debe servirse otra ración. Si ningún servidor puede confirmar la operación, queda pendiente para revisión; no se borra automáticamente.

La activación concede acceso de encargado administrador a la empresa; entrégala solo a personal autorizado. No contiene credenciales de Cloudflare ni administración de cobros. El teléfono registra un identificador de instalación propio; no copiar su base entre equipos. Borrar la app elimina su copia local y las entregas aún no sincronizadas.

La aplicación de trabajador Mi Comedor continúa siendo Android. Esta versión de iPhone es para el encargado. La revocación de credenciales llega a los dispositivos cuando vuelven a sincronizar; un QR físico estático todavía requiere comprobar al titular.

## Compilación y distribución

GitHub Actions ejecuta las pruebas de Swift en macOS, verifica firmas y Excel con Python, genera el proyecto con XcodeGen, prueba las pantallas en simulador y archiva para iPhone. No compila Android localmente.

```sh
swift test --package-path ios
cd ios
xcodegen generate
open ComedorControl.xcodeproj
```

El artefacto `Colbert-Control-iOS-SIN-FIRMA.ipa` **no se instala directamente en un iPhone**. Sirve para verificar la compilación antes de disponer de firma Apple. El ZIP del simulador sí puede ejecutarse en un simulador de Xcode compatible; no sustituye la prueba de cámara y biometría en hardware.

Para TestFlight se requiere la membresía Apple Developer, registrar el identificador `bo.colbert.comedor.control`, seleccionar el equipo de firma y crear la ficha correspondiente en App Store Connect. No se ha comprado una membresía ni publicado en App Store/TestFlight. El responsable debe revisar el aviso de privacidad y completar los datos de distribución antes de enviarla a revisión.

Las activaciones se generan localmente con `scripts/create_ios_activation.py` usando las configuraciones privadas ya existentes. Instalar `qrcode==8.2` y Pillow si faltan. Se guardan en `private/iPhone-Control-1.0.0/`; no subirlas a GitHub ni compartirlas con trabajadores.

## Validación

Las pruebas cubren permisos pagados/temporales y vencimiento, reloj tras reinicio, firmas, empresas diferentes, almuerzo/dieta, carnet con ceros iniciales, QR digital vencido/revocado, cifrado, persistencia de la cola ante fallos y cambios de servidor. El modo `--demo` contiene solo personas ficticias y se compila únicamente en Debug; no existe en el IPA Release.

La validación en simulador no acredita una instalación física ni una sincronización con los teléfonos reales del cliente. Antes de comenzar el servicio, probar dos equipos reales, un QR impreso y uno digital, duplicados, funcionamiento sin conexión, sincronización y continuidad de licencia.
