# Satélite Zoom Earth 4.1.2 — botón de fondo

- Mientras se está descargando o actualizando la imagen desde la pantalla principal, el botón muestra «Actualizando…», aparece en gris y está deshabilitado.
- Cuando termina **correctamente** y hay una imagen válida, recupera «Usar como fondo», el color naranja y la posibilidad de pulsarlo.
- Si la descarga falla o aún no hay imagen, el botón queda gris y deshabilitado para no aplicar una imagen no válida.
- Si se cambia de zona mientras existe una descarga pendiente, una respuesta antigua no puede volver a activar el botón naranja.
- Se conservan el icono del satélite con nubes, las descargas inmediatas al cambiar de zona y el intervalo mínimo al desbloquear.

No incluye APK: compilar `assembleDebug` mediante GitHub Actions. El proyecto mantiene la firma de prueba incluida en la versión anterior para compatibilidad al instalar sobre ella; **no es una clave apta para publicación pública de producción**.
