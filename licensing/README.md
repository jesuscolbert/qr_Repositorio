# Colbert Licencias y Cobros

Servicio de permisos administrado desde un teléfono Android dedicado. Un contrato representa **cliente + aplicación**: un pago habilita todos sus teléfonos o computadoras. Cada producto integra el cliente de licencias; no se puede controlar automáticamente software que aún no lo incorpora.

## Instalación

- `billing/`: app privada **Colbert · Cobros**, paquete `bo.colbert.cobros`. Su servidor, SQLite, firma de permisos y comprobantes viven en el teléfono del administrador.
- `licensing/`: biblioteca Android reutilizable y pantalla de pagos. `app/` la incorpora desde SOBOCE 1.7.0.
- `licensing/desktop/colbert_license.py`: adaptador Python para programas de escritorio. Requiere `cryptography`. Verifica la misma firma ECDSA P-256/SHA-256 que Android.
- `scripts/provision_billing.py`: crea el túnel exclusivo `colbert-licencias` y prepara configuraciones privadas. No cambia el túnel de consumos.
- `scripts/package_licensed.py OWNER_APK CUSTOMER_APK`: agrega configuración privada a APK compiladas en GitHub, alinea y firma conservando su código. La APK del administrador contiene el acceso de su túnel; la APK cliente solo incluye la credencial de su contrato de cobro, además de la configuración previa de la red del comedor.

Instalar primero la APK de administración **solo en un teléfono del propietario**, desbloquear con el PIN/patrón/contraseña de Android, activar la configuración incluida y encender el servidor. Mantener internet y permitir notificaciones. Después instalar SOBOCE 1.7 encima de 1.6 en todos los encargados, sin desinstalar ni borrar datos. Los clientes realizan una primera validación por internet; no tienen que importar JSON.

El contrato inicial de SOBOCE está **sin cobro activado**. El administrador configura su precio en BOB, frecuencia diaria/mensual, instrucciones y opcionalmente una imagen de QR bancario. No se crea un QR bancario ni se mueve dinero automáticamente.

## Cobrar y aprobar

1. Abrir un cliente, guardar precio/frecuencia/instrucciones/QR.
2. `Solicitar pago y bloquear uso` crea un cobro pendiente. Su importe y frecuencia quedan congelados. Un clic repetido conserva el mismo cobro.
3. El cliente ve el cobro, paga por el medio indicado y adjunta una imagen del comprobante. Se conserva un identificador de envío para reintentar sin duplicarlo.
4. La solicitud entra en la base del administrador y genera una notificación, si Android lo permite. **Un comprobante no prueba el pago ni concede acceso automáticamente.** El administrador debe verificar el movimiento bancario.
5. Aprobar habilita todos los equipos de ese cliente para esa aplicación. La aprobación transaccional e idempotente no agrega otro período por doble clic. Rechazar conserva el bloqueo y permite adjuntar otro comprobante.
6. Al finalizar el período pagado se genera el siguiente cobro al consultar el servidor. Un día son 24 horas; un mes avanza al mismo día del mes siguiente (ajustado al último día válido). Fechas de administración en America/La_Paz.

Cambiar la tarifa afecta los cobros siguientes. `Reemplazar cobro con nueva tarifa / QR` cancela y sustituye el cobro aún impago; exige resolver antes sus comprobantes pendientes. `Habilitar sin cobro` cancela el cobro abierto. `Suspender acceso manualmente` suspende el contrato; para cobrarlo nuevamente usar Solicitar pago. No hay débitos automáticos ni acumulación automática de períodos durante una suspensión.

## Conexión y alcance del bloqueo

La URL de permisos usa un túnel propio, sin rotación de servidores entre clientes. **Solo un teléfono administrador debe ejecutar este túnel.** No instalar su APK en los equipos de clientes ni encender una copia durante una migración.

El permiso firmado dura como máximo 24 horas desde el inicio de su consulta y nunca supera el fin del período pagado. Las apps conectadas consultan aproximadamente cada minuto; la pantalla de pago consulta cada 15 segundos. La suspensión no es instantánea en un teléfono desconectado: su permiso anterior puede durar hasta ese límite. No se usa el reloj editable del cliente para prolongar el permiso: Android usa tiempo monotónico y pide una validación nueva tras reiniciar el teléfono. El cliente Python también exige revalidar tras reinicio o cambios importantes de reloj.

Si el teléfono administrador está apagado, pierde internet o Android detiene su servicio, no recibe comprobantes ni comunica nuevas aprobaciones. Los equipos conservan únicamente el permiso ya validado. No se promete disponibilidad permanente de un servidor Android. El servicio muestra una notificación visible; abrir la app y encenderlo permite reanudarlo. La PC no participa en la operación.

En SOBOCE se suspenden nuevas entregas, emisiones QR y altas. Se conservan los registros, la sincronización y la consulta/exportación del historial. Las versiones 1.6 y anteriores no incorporan esta licencia y **no pueden bloquearse mediante este sistema**: deben actualizarse. El mecanismo protege el uso normal de las versiones distribuidas; no es un DRM resistente a una APK modificada o un equipo comprometido.

## Administración y respaldo

No existen rutas HTTP de administración. Cambiar precios, bloquear y aprobar ocurre en la app local desbloqueada con la credencial de Android. La clave privada de firma se genera al preparar ese teléfono y se guarda cifrada con Android Keystore; no está en el APK público ni en los clientes. Los clientes fijan su clave pública en la primera respuesta autenticada por HTTPS y la credencial de contrato, y rechazan cambios de identidad posteriores.

`Guardar respaldo cifrado` exporta contratos, cobros, comprobantes, auditoría, credenciales y la clave de firma. Usa AES-256-GCM y PBKDF2-HMAC-SHA256 (210 000 iteraciones); requiere contraseña de al menos 10 caracteres. Guardar fuera del teléfono y conservar la contraseña por separado. No hay recuperación de contraseña.

Para cambiar el teléfono administrador: detener el antiguo, instalar la APK en el nuevo y elegir **Restaurar respaldo cifrado antes de activar una configuración nueva**. Restaurar solo en una base vacía conserva la identidad que esperan las aplicaciones. Sin respaldo se pierde la identidad y habrá que volver a vincular los clientes; no se reemplaza una clave fijada silenciosamente.

Los comprobantes se guardan como JPEG/PNG de hasta 512 KiB, con máximo de tres pendientes por contrato. La app Android reduce la foto antes de enviarla. El almacén de imágenes tiene un límite de 25 MiB en esta versión; al agotarse rechaza nuevos adjuntos y pide contactar al administrador, sin borrar archivos automáticamente.

## API reutilizable v1

Cada producto recibe una configuración privada `version`, `url`, `contractId`, `productId`, `productName`, `token`. El administrador puede crear más contratos y exportar su conexión para incorporarla a otra APK o programa. Esa credencial da acceso únicamente a ese contrato, no a la administración ni al túnel.

- `POST /v1/status`, `Authorization: Bearer <token>`, cuerpo `{"nonce":"64 caracteres hexadecimales aleatorios"}`. Respuesta `{claims, signature, publicKey}`. `claims` es una cadena JSON UTF-8 cuya representación exacta se firma con SHA256withECDSA (firma ASN.1 DER, base64 estándar). La clave pública es DER SubjectPublicKeyInfo en base64. Incluye cliente, producto, nonce, issuedAt/expiresAt/paidUntil en milisegundos UTC, allowed, revision y cobro pendiente si corresponde.
- `POST /v1/receipt`, misma autorización, cuerpo `invoiceId`, `requestId`, `image` (base64 JPEG/PNG), `note`. Reintentar con el **mismo identificador y contenido** cuando se pierde la respuesta. El recibo no es un permiso: consultar `/v1/status` tras la aprobación.
- `GET /health`: identificación básica, sin datos personales.

No aceptar `allowed` sin verificar firma, contrato, producto, nonce, identidad fijada y vencimiento. No enviar la credencial a redirecciones HTTP. No colocar claves de administración en el cliente.

### Android

Integrar `implementation project(':licensing')`, incluir `assets/license-client.json` en el instalador privado y llamar a `LicenseClient.get(context).refresh()` en un hilo de trabajo. Antes de cada operación protegida verificar `allowed()`. Si está bloqueada, abrir `LicenseActivity`. Mantener validaciones periódicas mientras la app se usa. La biblioteca por sí sola no intercepta automáticamente las funciones de otra app.

### Python de escritorio

```python
from colbert_license import LicenseClient, LicenseBlocked
license = LicenseClient('conexion-privada.json', 'estado-privado/permiso.json')
license.require_access()  # antes de cada operación protegida
```

El integrador debe presentar los detalles del cobro y permitir enviar el comprobante en la interfaz de su programa. El adaptador no modifica programas de terceros ni implementa una interfaz gráfica de escritorio completa. Mantener los archivos de configuración/cache en el perfil privado del usuario. Un comprobante enviado desde Python debe conservar su `request_id` para los reintentos.

## Validación

GitHub Actions compila las dos APK y ejecuta análisis Android, pruebas del comedor existente, SQLite de cobros, aislamiento de contratos, límites de pago/24 horas, firmas alteradas, reintentos de comprobantes, doble aprobación concurrente, respaldo cifrado y API sin administración pública. También prueba el adaptador Python y captura pantallas con datos sintéticos. La prueba del túnel de consumos en producción sigue desactivada para no interferir con teléfonos activos.
