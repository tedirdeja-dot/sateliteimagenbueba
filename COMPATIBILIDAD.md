# Compatibilidad Android — revisión inicial

- API mínima: 23 (Android 6); destino: 35 (Android 15). No equivale a soporte garantizado para futuras versiones.
- Se ha movido la decodificación del JPEG del fondo animado fuera del hilo principal.
- Las cargas antiguas se descartan al llegar una actualización más reciente y se liberan los bitmaps descartados.
- El servicio de desbloqueo se detiene si Android rechaza su promoción a primer plano.
- Se desactiva la copia de seguridad automática de datos locales (incluida la configuración y caché).

## Limitaciones pendientes

- Android 12+ limita el inicio de servicios en primer plano desde segundo plano; Android 14+ exige declarar el tipo. La excepción `specialUse` no garantiza aceptación en todas las tiendas, dispositivos ni situaciones.
- Los fabricantes pueden detener procesos en segundo plano y restringir los eventos de desbloqueo.
- La captura de Zoom Earth depende del WebView instalado, de la red y de posibles cambios del sitio.
- La aplicación necesita pruebas reales en API 23, 26, 29, 31, 33, 34 y 35, además de versiones más recientes, antes de declarar compatibilidad universal.
- No se ha podido compilar ni ejecutar una batería de pruebas en este entorno: el proyecto no incluye Gradle Wrapper.
- El repositorio incluye un keystore con contraseñas incrustadas. Para distribución pública, sustituirlo por credenciales seguras y mantener protegida la clave de firma existente para conservar la posibilidad de actualizar instalaciones previas.
