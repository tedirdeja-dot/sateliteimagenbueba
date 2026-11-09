# Actualización inmediata al cambiar de zona

- Al pulsar «Guardar esta vista», se solicita una descarga forzada de la nueva zona, aunque no haya transcurrido el intervalo mínimo.
- El intervalo configurado sigue aplicándose solamente a las actualizaciones desencadenadas por el desbloqueo.
- Si existe una descarga en curso, la solicitud de la nueva zona queda en cola con prioridad y se ejecuta al concluir la captura activa.
- La captura de una zona antigua no debe sobrescribir el JPG de la nueva zona.
- Se conserva la última imagen válida si la captura nueva falla.

Compilación: ejecutar el workflow `.github/workflows/blank.yml` en GitHub Actions. No se ha ejecutado una compilación Android local en este entorno.
