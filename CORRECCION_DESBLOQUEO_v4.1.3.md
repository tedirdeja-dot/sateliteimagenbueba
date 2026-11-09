# Satellite Wallpaper 4.1.3 — corrección de la actualización al desbloquear

## Qué se ha corregido

La versión anterior registraba `ACTION_USER_PRESENT` únicamente en `UnlockService`, un servicio en primer plano que puede ser detenido por el sistema o el fabricante. Por eso podía funcionar la descarga manual al abrir la aplicación y fallar la actualización automática tras desbloquear.

En esta versión:

- `SatelliteWallpaperService`, vinculado al fondo animado del dispositivo, registra también los eventos `ACTION_USER_PRESENT`, `ACTION_SCREEN_OFF` y `ACTION_SCREEN_ON` durante su ciclo de vida.
- Se mantiene `UnlockService` como escucha adicional cuando Android lo permite.
- `UnlockRefreshCoordinator` evita las llamadas duplicadas y confirma que la pantalla esté encendida y desbloqueada.
- La comprobación adicional al volver a mostrarse el fondo **solo** se realiza si antes se ha detectado una pantalla apagada. Volver a la pantalla de inicio desde otra aplicación no inicia descargas.
- Se sigue respetando íntegramente el intervalo seleccionado; no existen temporizadores periódicos de descarga.
- Si no hay red o la captura falla, se conserva la imagen anterior y la operación no se cuenta como actualización correcta.
- El registro en la pantalla principal sigue mostrando los intentos de actualización, ahora con su origen.

**Importante:** estas mejoras reducen el riesgo de perder el evento al desbloquear, pero las restricciones de Android y del fabricante pueden impedir la captura en segundo plano. Se necesita una prueba real en el dispositivo.

## Prueba en Android

1. Instalar la versión 4.1.3 y mantener aplicado el fondo animado de la aplicación.
2. Seleccionar un intervalo corto (p. ej., 5 minutos).
3. Dejar pasar el intervalo desde la última descarga válida, apagar y desbloquear la pantalla.
4. Esperar un poco para la captura; verificar que cambia la fecha de la imagen.
5. Si no cambia, abrir la aplicación y consultar **Últimos intentos** para distinguir entre evento no recibido, intento omitido por intervalo o fallo de captura.

La descarga al cambiar de zona continúa siendo inmediata e independiente del intervalo de desbloqueo. El botón «Usar como fondo» conserva el estado gris «Actualizando…» y naranja cuando está disponible.
