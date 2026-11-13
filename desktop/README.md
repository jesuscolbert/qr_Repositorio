# Colbert Reportes para Windows

Visor de consulta de SOBOCE y FRIGOR. Filtra por empresa, planta, día o mes; consulta cada 15 segundos mientras está abierto. La computadora no registra entregas, crea trabajadores ni participa como servidor del túnel.

Actualizar los encargados a 1.12.0 y mantener al menos uno conectado en la empresa que se quiere consultar. El reporte es la copia recibida por ese teléfono; las entregas de equipos desconectados aparecerán cuando se sincronicen. Si se pierde la conexión se conserva la vista en memoria con la hora del último reporte. No se muestra como información en vivo.

El permiso utiliza el contrato de comedor de cada empresa en Colbert Cobros. Se requiere la primera validación y luego se respeta el máximo existente de 24 horas sin validación, sin superar el período pagado. La exportación requiere conexión al comedor y permiso vigente.

La versión 1.0.1 identifica las conexiones de Windows como cliente Colbert para evitar el rechazo del identificador genérico de Python en la entrada de Cloudflare. Los fallos de conexión o de validación no se presentan como pagos pendientes. Solo una respuesta firmada de Colbert Cobros puede indicar deuda o suspensión; un error de red no cambia el contrato ni extiende el permiso guardado.

En la versión 1.1.0, Windows convierte el archivo completo y autenticado del teléfono en un Excel ejecutivo. Resumen presenta indicadores, gráficos por comida y planta, importes registrados y entregas sin tarifa. Entregas conserva todos los registros con filtros, encabezados inmovilizados y alertas de revisión. Se exporta la selección completa, aunque la tabla de pantalla muestre como máximo 200 filas diarias o 100 mensuales. Los posibles duplicados se incluyen y se señalan; los precios pendientes quedan en blanco y los identificadores permanecen como texto. El importe registrado no se presenta como cobro ni utilidad.

Los conteos, trabajadores distintos e importes se reconcilian con el detalle antes de guardar. El archivo conserva fórmulas y valores calculados, gráficos nativos y áreas de impresión. El gráfico de plantas muestra como máximo las ocho con más entregas, indicando ese límite; la tabla y el detalle incluyen todas. El conteo de trabajadores distintos es el valor del corte exportado. La plantilla pública contiene únicamente datos ficticios y se creó con artifact-tool; el ejecutable la completa con la biblioteca estándar de Python.

Un HTTP 400 indica que el servidor rechazó la consulta. Por sí solo no demuestra una licencia vencida ni una versión antigua. El visor distingue este rechazo de una respuesta de permisos firmada; no se modifican contratos para resolver fallos de red.

## Construcción y configuración privada

El flujo `desktop.yml` compila con PyInstaller en GitHub para Windows x64 y ejecuta pruebas de permisos, firmas, archivo Excel, configuración y apertura del ejecutable. El artefacto público es genérico: no contiene datos ni claves reales. `scripts/package_desktop.py` añade localmente al ejecutable la credencial derivada de solo consulta y los permisos de cliente. Entregar el ejecutable privado solo a las personas autorizadas a ver reportes. No contiene claves maestras de los encargados ni credenciales de túneles.

La ruta autenticada `/reports` acepta solo consultas y exportación. Comprueba firma, fecha, nonce, liderazgo vigente y licencia. Su credencial no autentica peticiones `/rpc` ni autoriza registrar comidas. Las respuestas se firman para la solicitud; el archivo se verifica antes de guardarlo.
