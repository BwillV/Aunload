# PalletTracker v5.19 (versionCode 49)

Partiendo de v5.18. Cuatro cambios pedidos por Wilson. Todo el texto en
español, con traducciones en `values-en` y `values-zh-rCN` (368 claves por idioma).

## 1. Pantalla principal: selector de modo (ya no switch)
- Se reemplazó el `Switch` de modo por un **selector segmentado** (RadioGroup
  con 3 botones: **Inbound · Inventario · Picking**) en `activity_main.xml`,
  con fondo `drawable/mode_segment_bg.xml` (azul el seleccionado).
- Elegir **Picking** hace lo mismo que "📦 Modo Picking" del menú lateral
  (usa `AppMode`). El menú lateral sigue funcionando y queda sincronizado.
- El selector se sincroniza en `onResume` y cada vez que el modo cambia desde
  otro sitio (`sincronizarSwitchModo` / `actualizarEtiquetaModo`).

## 2. Scroll del WMS: ahora es vertical (antes horizontal)
- **Causa:** el scroll automático tomaba el **primer** nodo desplazable que
  encontraba. En la pantalla de Pick Task del WMS ese primer nodo es el
  carrusel/pestañas **horizontal** (New / In Progress / Done), así que
  `ACTION_SCROLL_FORWARD` movía la pantalla de lado.
- **Arreglo** (`ScreenCaptureService`): nuevo `buscarScrollableVertical()` que
  recoge **todos** los nodos desplazables y elige el **vertical más grande**
  (mayor altura), descartando los claramente horizontales (ViewPager,
  HorizontalScrollView, Gallery, o cuyo ancho supera al alto). Si la acción no
  desplaza, hay un **respaldo por gesto** (swipe vertical, `dispatchGesture`).
  Todo sigue en hilo aparte (sin bloquear la UI / sin ANR).

## 3. Estadísticas personales de Picking (solo mías, 100% locales)
Al estilo de la "pantalla de la jefa", pero de un solo dispositivo y sin
Firebase (SharedPreferences `picktracker_prefs`). En el overlay 📦 Picking,
tarjeta **"📊 Mis estadísticas (solo yo)"** con vista **Hoy** y **Acumulado**:
- Tareas completadas, ubicaciones recogidas, artículos (EA), tiempo total.
- Promedios: **tiempo por ubicación**, tiempo por tarea, **EA/hora**.
- Se miden solas: al abrir el detalle de una tarea arranca el cronómetro
  (`startTask`), se cuentan ubicaciones y EA, y al cerrarse la tarea
  ("All steps finished", `endTask`) se suman al día y al acumulado.
- Los contadores del día se reinician solos al cambiar de día.
- Si todavía no hay datos propios, se avisa y se usa **3:03 por ubicación**
  (referencia real de Wilson) como valor por defecto.

## 4. Tiempo estimado por pallet (ventana de sugerencias)
- Cada pallet y el total del día muestran el tiempo estimado calculado con
  **tu** promedio por ubicación: `tiempo_pallet = nº ubicaciones × promedio
  propio por ubicación` (el mismo indicador "time per location / 每库位耗时"
  que usa la pantalla de la jefa).
- Una nota indica si el tiempo es **con tus datos** (verde) o **con el valor
  por defecto** 3:03 (gris), mostrando el valor usado por ubicación.
- **Botón "🔄 Reiniciar mis estadísticas"** en el panel.

## Validación
- Lógica del Advisor validada offline con el banco `/home/ubuntu/pttest` y el
  diagnóstico real `diag_will_lineas.txt` (Java 17). El plan de pallets sigue
  igual (reglas 7+10=17 llena, cruce B15↔C01) y el nuevo cálculo de tiempo por
  pallet da, p. ej., Pallet de 10 ubicaciones = ~30 min por defecto vs ~25 min
  con un promedio propio de 2:30.

## No se tocó
Containers secuenciales, código de equipo, sync Firebase de pallets/containers,
conteo, detección WMS inbound/inventario, firma, applicationId ni SDKs. El FAB
en Picking sigue solo con Copiar SKU, Copiar locación y 📦 Picking.

## Importante
APK **compilado pero NO probado en el escáner físico iData 50** (Android 9).

versionCode=49 · versionName=5.19 · minSdk 26 · targetSdk 28
