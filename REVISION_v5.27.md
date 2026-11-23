# Revisión v5.27 (versionCode 57)

## Petición de Wilson
> "O sea yo quiero que lo cronometres siempre" — "Siempre y cuando esté en modo picking"

## Cambio
`PickTrackerStore.endTask()`:
- Antes (v5.26): al cerrar una tarea sumaba +1 a las estadísticas, pero el
  **tiempo medido y las ubicaciones solo contaban "si hubo cronómetro"**
  (`if (sec > 0L)` envolvía el conteo de ubicaciones).
- Ahora (v5.27): toda tarea que se cierra aporta **siempre** su tiempo real
  medido (si se abrió el detalle, `start > 0`) y **siempre** sus ubicaciones
  capturadas. Se eliminó el guard `if (sec > 0L)` sobre las ubicaciones.

## Alcance — solo picking
El cronometrado sigue limitado al picking por diseño: el detector
(`procesarPickTrackerSiAplica` en `ScreenCaptureService`) solo se activa al
reconocer las pantallas de Pick Task del WMS (lista "New", detalle "TASK-",
diálogo "All steps finished"). Nunca cuenta tiempo en las pantallas de
inbound/inventario. No se toca el `AppMode` de la app.

## Sin tocar
- Parser/Advisor (sin cambios), strings (398 claves c/u sin cambios).
- Containers, código de equipo, sync Firebase, eliminaciones, conteo,
  detección WMS, firebase_reglas.json, UpdateChecker, applicationId, firma, SDKs.

## Validación
- BUILD SUCCESSFUL. Badging: versionCode 57, versionName 5.27, sdk26, target28.
- Verificado offline/lógicamente. **NO probado en escáner físico** (iData 50,
  Android 9, API 28, sin GMS).

## Archivos
- `PickTrackerStore.java` (endTask)
- `app/build.gradle` (vc57, 5.27)
- `REVISION_v5.27.md`
- `PalletTracker-5.27-debug.apk`
