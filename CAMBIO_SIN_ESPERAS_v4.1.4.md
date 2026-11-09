# Versión 4.1.4 — opción «Sin esperas»

En **Intervalo mínimo entre actualizaciones al desbloquear** aparece ahora la opción **Sin esperas**.

- Al elegirla, cada evento real de desbloqueo (sin duplicar eventos del mismo desbloqueo) solicita una nueva imagen independientemente del tiempo transcurrido.
- Las demás opciones conservan sus intervalos de 5, 10, 15, 30, 60, 120, 360, 720 y 1440 minutos.
- El cambio de zona sigue iniciando la descarga inmediatamente.
- No se inicia una descarga por mantener el teléfono bloqueado ni por simple paso del tiempo.
- El estado «Actualizando…» gris y el botón naranja «Usar como fondo» siguen igual.

Cambios de código: `MainActivity.kt`, `WallpaperRepository.kt`, y versión en `app/build.gradle.kts`.

Nota: compilación y pruebas en un dispositivo Android real pendientes.
