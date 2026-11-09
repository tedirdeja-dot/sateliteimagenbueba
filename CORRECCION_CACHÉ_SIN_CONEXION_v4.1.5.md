# Satellite Wallpaper 4.1.5 — Conservar la imagen válida si falla la descarga

- La captura de Zoom Earth se rechaza si WebView informa de un fallo de red o de HTTP en la página principal.
- Se examinan el dominio y el contenido de la página antes de hacer una captura, para evitar el error «Página web no disponible / net::ERR_INTERNET_DISCONNECTED».
- Se rechazan imágenes casi blancas y capturas sin contenido válido, incluso cuando contienen texto.
- El JPG de caché solo se sustituye después de validar la descarga; la escritura se hace con `AtomicFile`, que permite recuperar la última versión después de un fallo de escritura.
- Si hay una imagen anterior válida, la pantalla principal continúa mostrándola y permite usarla como fondo cuando falla una descarga. La fecha de actualización solo cambia si el nuevo JPG es válido.
- Se mantienen «Sin esperas», los intervalos disponibles, las descargas inmediatas al cambiar de zona, el botón «Actualizando…» y el icono del satélite.

**Importante:** si alguna versión anterior ya sustituyó el JPG por una pantalla de error, no hay forma segura de recrear automáticamente aquella fotografía. Una descarga correcta con conexión repondrá la imagen. No se ha compilado ni ejecutado en un teléfono Android en este entorno: compilar con GitHub Actions y probarlo con y sin conexión.
