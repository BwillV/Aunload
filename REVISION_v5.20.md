# PalletTracker v5.20 — Revisión (versionCode 50, versionName "5.20")

Correcciones sobre la v5.19 según lo reportado por Wilson. **Compilado con Gradle 8.7 y
validado offline** contra el diagnóstico real (`diag_will_lineas.txt`). **No probado en el
escáner físico** (iData 50, Android 9 sin GMS): no hay hardware disponible en este entorno.

## Cambios por punto

1. **Fin de task (no marcaba al terminar) — CORREGIDO.**
   En v5.19, `ScreenCaptureService.procesarPickTrackerSiAplica()` empezaba con
   `if (!AppMode.isPicking(this)) return;`, así que si el diagnóstico se tomaba en modo
   Inventario **no se procesaba nada**. Se **eliminó ese guard**: ahora las pantallas de
   Pick Task del WMS se procesan siempre que se reconocen inequívocamente (lista/detalle de
   pick y diálogos de cierre), en `ioExecutor`, sin afectar la detección inbound/inventario.
   Además la task activa se marca **completada** automáticamente al cerrar
   ("All steps finished…") o cuando SKUs y EA llegan al total (`endTask()` llama a
   `setOrderDone(...)`).

2. **Lectura de tasks mal leídas — CORREGIDO/VALIDADO.**
   Causa raíz: el mismo guard anterior. El parser de la lista New ya era correcto; validado
   offline con el diagnóstico real (detecta las 5 tasks: TASK-44275, 44625, 44628, 44792,
   44793 con su zona, DN y ubicaciones).

3. **La pantalla de picking (overlay del FAB) se cortaba — CORREGIDO.**
   `PickTrackerHelper` ahora envuelve todo el contenido en un `ScrollView` exterior acotado
   (máx. 80% de la pantalla) con el botón **Cerrar fuera del scroll** (siempre visible).
   Se **quitó el panel "Mis stats"** del overlay: en el FAB quedan solo las herramientas.
   Las estadísticas están ahora en la pantalla de Picking de la app (punto 4).

4. **Pantalla de Picking dentro de la app (stats del día) — AÑADIDA.**
   Nueva `PickingActivity`, abierta desde el drawer **"📦 Modo Picking" sin cambiar de modo**.
   Muestra las estadísticas del día de Wilson con **formato de la pantalla de la jefa**:
   Tareas, Inventario total (EA), Ubicaciones totales, Inventario recogido, Ubicaciones
   recogidas, **Progreso %**, Tiempo y Tiempo por ubicación; además un bloque con los datos
   del **día anterior** y botón para reiniciar las stats de hoy. **Reinicio automático diario**
   guardando el día anterior. 100% local (SharedPreferences), nada a Firebase.

5. **Tiempo por pallet — regla 3:03 eliminada.**
   Se eliminó el valor por defecto `BASELINE_SEC_PER_LOC = 183` (3:03) y todas sus referencias.
   Ahora: mientras **no** se hayan terminado las asignadas de hoy se usa el **promedio por
   ubicación del día anterior**; una vez terminadas las asignadas, se usan **las de hoy**;
   si no hay datos → **"sin datos aún"**.

6. **Icono del FAB en modo Picking — AÑADIDO.**
   Nuevo vector `ic_picking_white.xml` (caja de picking). `FloatingButtonService` lo asigna
   en modo Picking y usa el icono normal en los demás modos.

7. **Número SLP junto a cada pallet (petición de Wilson) — AÑADIDO.**
   En el overlay del FAB, cada orden muestra ahora su **LP destino** (`SLP-######`) debajo de
   la línea de la orden. El SLP se captura de la pantalla de detalle de pick
   (`PickTrackerParser.detailLp` + `PickTrackerStore.noteLp`), guardado por tarea para que no
   se mezcle entre órdenes.

8. **Strings:** se añadieron 20 claves nuevas en `values/`, `values-en/` y `values-zh-rCN/`
   (368 → 388 en los tres locales, con paridad).

## No se tocó
Containers secuenciales, código de equipo, sync Firebase de pallets/containers,
eliminaciones a todo el equipo, conteo, detección WMS inbound/inventario,
`firebase_reglas.json`, UpdateChecker, applicationId, firma ni SDKs. Se mantiene todo lo de
v5.19 (scroll automático, KEYCODE_BACK + ✕ Cerrar, selector de modo, reglas de pallets, etc.).

## Estado
- Build: **BUILD SUCCESSFUL** (Gradle 8.7, JDK 17).
- APK: `PalletTracker-5.20-debug.apk` — badging: versionCode **50**, versionName **5.20**,
  minSdk 26, targetSdk 28.
- Validación offline del parser: OK.
- **Pendiente de prueba en escáner real** (no disponible aquí).
