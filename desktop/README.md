# Colbert Reportes para Windows

Visor de consulta de SOBOCE y FRIGOR. Filtra por empresa, planta, día o mes; consulta cada 15 segundos mientras está abierto. La computadora no registra entregas, crea trabajadores ni participa como servidor del túnel.

Actualizar los encargados a 1.12.0 y mantener al menos uno conectado en la empresa que se quiere consultar. El reporte es la copia recibida por ese teléfono; las entregas de equipos desconectados aparecerán cuando se sincronicen. Si se pierde la conexión se conserva la vista en memoria con la hora del último reporte. No se muestra como información en vivo.

El permiso utiliza el contrato de comedor de cada empresa en Colbert Cobros. Se requiere la primera validación y luego se respeta el máximo existente de 24 horas sin validación, sin superar el período pagado. La exportación requiere conexión al comedor y permiso vigente.

Excel se genera en el teléfono con dos hojas, Resumen y Entregas. Se exporta la selección completa, aunque la tabla de pantalla muestre como máximo 200 filas diarias o 100 mensuales. Incluye posibles duplicados señalados, precios pendientes en blanco e identificadores como texto.

## Construcción y configuración privada

El flujo `desktop.yml` compila con PyInstaller en GitHub para Windows x64 y ejecuta pruebas de permisos, firmas, archivo Excel, configuración y apertura del ejecutable. El artefacto público es genérico: no contiene datos ni claves reales. `scripts/package_desktop.py` añade localmente al ejecutable la credencial derivada de solo consulta y los permisos de cliente. Entregar el ejecutable privado solo a las personas autorizadas a ver reportes. No contiene claves maestras de los encargados ni credenciales de túneles.

La ruta autenticada `/reports` acepta solo consultas y exportación. Comprueba firma, fecha, nonce, liderazgo vigente y licencia. Su credencial no autentica peticiones `/rpc` ni autoriza registrar comidas. Las respuestas se firman para la solicitud; el archivo se verifica antes de guardarlo.
