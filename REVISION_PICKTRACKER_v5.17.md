# Revisión e integración de PickTracker — PalletTracker v5.17 (vc 47)

Fecha: 16-nov-2026 · Integrado sobre la v5.16 (vc 46, con "Enviar a iPhone" en Diagnóstico).
Autor del módulo PickTracker: **Muse** (otra IA). Revisión, corrección e integración: este paso.

> ⚠️ **Importante:** la app **compila sin errores** (Gradle `assembleDebug`, APK generado y
> verificado con `aapt2 dump badging`), pero **NO se ha probado en el escáner físico iData 50**.
> Toda la lógica de lectura de pantallas depende del WMS real y debe probarse en el dispositivo.

---

## 1. ¿Qué trae PickTracker?

Es un **modo nuevo e independiente** de la app, aparte de Inbound/Inventario, pensado para la
operación de **picking** que Wilson pidió. **No toca la lógica de containers, ni el código de
equipo, ni la sincronización Firebase de pallets/containers.** Sus datos son **100 % locales**
(SharedPreferences `picktracker_prefs`), no se sincronizan entre escáneres.

### Modo "Picking"
- `AppMode` ahora tiene 3 modos: Inbound (0), Inventario (1) y **Picking (2)**.
- El botón de modo del overlay del FAB ahora **cicla entre los 3 modos** (`(getMode+1) % 3`).
- En modo Picking:
  - El FAB se pone **verde** (`0xFF2E7D32`), con el badge vacío (no cuenta pallets).
  - La notificación muestra "🎯 Modo Picking / PickTracker activo".
  - En `MainActivity`, el switch de modo queda apagado y la etiqueta dice "Modo: Picking".

### Lectura real del WMS (AccessibilityService)
Cuando está en modo Picking, y **solo entonces**, el servicio lee las pantallas de Pick Task:
- **Lista "Pick Task List":** detecta cada tarjeta (TASK-xxxxx, envío, zona del "Suggested VLG",
  ubicaciones del "Suggest Location", y conteo de pedidos DN).
- **Detalle de una tarea:** detecta el TASK en curso, cada ubicación nueva visitada y el progreso
  "A / B Skus, C / D EA Picked".
- **Diálogo "All steps finished…":** cierra la tarea y suma su tiempo al día.
- Botón **"📋 Leer toda la lista (desliza sola)"**: la app desplaza automáticamente la lista de
  Pick Task y lee TODAS las tarjetas aunque no quepan en pantalla.

### Medición de tiempos y consejero
- Mide tareas, piezas, piezas/hora y **segundos por ubicación**, comparándolos con la **referencia
  personal de Wilson: 183 s (3:03) por ubicación**.
- **Consejero de tareas** (`PickTrackerAdvisor`): agrupa las tareas pendientes en pallets **por
  pasillo** (B con B, C con C), ordena **de menos a más artículos**, calcula la **ruta empezando por
  lo más lejano** y avanzando hacia el punto de drop entre **B15 y C1**, estima **pallets a 48 cajas**
  (ajustable) y marca una tarea de **1 ubicación con muchos artículos** como "probablemente pallet
  completa".

### Overlay de PickTracker
Se abre desde el menú del FAB con el botón **"📊 PickTracker"** (solo visible en modo Picking).
Muestra rendimiento de hoy, la tarea en curso con progreso en vivo, las tareas pendientes y el
consejo. Botones: registrar ubicación hecha, cerrar tarea, leer toda la lista y **"✕ Cerrar"**.
Sigue el patrón de los overlays existentes: `TYPE_APPLICATION_OVERLAY`, tema oscuro, **cierra con la
tecla Atrás** del PDA, y los toques fuera pasan al WMS.

---

## 2. Archivos agregados / modificados

### Archivos Java nuevos (4) — `app/src/main/java/com/demoaccesibilidad/capture/`
| Archivo | Rol |
|---|---|
| `PickTrackerParser.java` | Parser puro de las pantallas de Pick Task (lista y detalle). Sin dependencias de Android. |
| `PickTrackerStore.java` | Datos locales en SharedPreferences `picktracker_prefs`. **No usa Firebase.** |
| `PickTrackerAdvisor.java` | Lógica pura del consejero (agrupación por pasillo, ruta, pallets). |
| `PickTrackerHelper.java` | Overlay de PickTracker (patrón de `CopyTextHelper`). |

### Archivos Java modificados (5)
| Archivo | Cambio |
|---|---|
| `AppMode.java` | Nuevo `MODE_PICKING=2`, `isPicking()`, `nombre()` devuelve "Picking". |
| `MainActivity.java` | Solo `sincronizarSwitchModo()`: switch apagado y etiqueta "Modo: Picking". |
| `CopyTextHelper.java` | Botón "📊 PickTracker" en el menú (solo en Picking); botón de modo cicla 3 modos. |
| `FloatingButtonService.java` | FAB verde + notificación en modo Picking (`actualizarNotificacionPicking()`). No toca el conteo de pallets. |
| `ScreenCaptureService.java` | Llama `procesarPickTrackerSiAplica()` tras el inventario (en `ioExecutor`); añade `collectAllTextsScrolling()` para leer toda la lista deslizando. |

### Recursos — strings (3 idiomas)
Se añadieron **20 claves** nuevas a cada uno de `values/`, `values-en/` y `values-zh-rCN/`
(4 de modo Picking + 16 `pt_*`). Conteo final verificado: **359 claves en los 3 idiomas** (idénticas).

### Versión
`app/build.gradle`: `versionCode 46 → 47`, `versionName "5.16" → "5.17"`.

---

## 3. Problemas encontrados en el código de Muse y cómo se trataron

1. **Muse no pudo compilar con Gradle** (bloqueo de red en su entorno); solo compiló con `javac` +
   stubs contra `android.jar` API 34, y **dejó la versión en 45/5.15**. → En esta integración se
   compiló con el Gradle real del proyecto (**BUILD SUCCESSFUL**) y se subió la versión a 47/5.17.

2. **Riesgo de pérdida de la v5.16 al fusionar.** El `strings.xml` de Muse se basaba en la v5.15 y
   **no** incluía las 5 claves de Diagnóstico ("Enviar a iPhone") de la v5.16. → **No se sobrescribió**
   ningún `strings.xml`; se **fusionaron** solo las 20 claves nuevas de PickTracker encima del
   `strings.xml` actual del proyecto, conservando todo lo de la v5.16.

3. **Nunca se copiaron archivos con marcadores de secretos.** Se revisó que los 9 archivos de Muse no
   contuvieran tokens/claves (`grep` por `AIza`, `firebaseio`, `databaseURL`, claves privadas, etc.):
   **no hay secretos**. No se tocaron `google-services`, reglas de Firebase ni la firma.

4. **String sobrante sin usar:** `pt_empezar_ejemplo` ("Empezar tarea de ejemplo") quedó en los
   recursos pero el overlay final ya no lo usa (Muse quitó la tarea de ejemplo). Es inofensivo; se
   dejó para no desincronizar el conteo de claves entre idiomas.

5. **No se encontraron errores de compilación ni símbolos faltantes.** Se verificó que todos los
   métodos que el código nuevo invoca ya existen en el proyecto v5.16: `AppMode.getMode/setMode`,
   `ScreenCaptureService.getInstance/recolectarTodo/desplazarLista/mainHandler/ioExecutor`,
   `CopyTextHelper.crearBoton/addSpacer/removerOverlay`, `PalletTrackerApp.logException`, y las claves
   `R.string.btn_cerrar/copy_activa_servicio/copy_buscando`. No hizo falta ninguna corrección de código.

---

## 4. Riesgos / limitaciones (declarados por Muse y confirmados)

- **Sin filtro de paquete del WMS.** El paquete real de la app "Item WMS" se desconoce, así que el
  parser se apoya en los textos de las pantallas ("Pick Task List", "TASK-…", etc.). Si otra app
  mostrara textos parecidos en modo Picking, podría confundirse. Mitigado porque **solo corre en modo
  Picking**.
- **Cambiar de tarea sin cerrarla.** Si Wilson pasa a otra tarea sin que aparezca el diálogo "All
  steps finished…", la tarea anterior queda abierta y su tiempo **no se suma** al día (igual que si se
  cierra el overlay a mano). Puede cerrarla con el botón "Cerrar tarea".
- **Modelo de distancia aproximado.** La ruta asume pasillos en línea B01…B15, C01…C15, D01… con el
  punto de drop entre B15 y C1. Si el layout real difiere, hay que ajustar `positionOf()`/`DROP_POS`
  en `PickTrackerAdvisor`.
- **Datos locales, no sincronizados.** Los tiempos de picking son personales de cada escáner; esto es
  intencional (PickTracker es un módulo aparte y no usa Firebase).

---

## 5. Qué probar en el escáner (iData 50 / Honeywell, Android 9)

1. **Cambio de modo:** con el botón de modo del FAB, confirmar que cicla Inbound → Inventario →
   Picking → Inbound, y que en Picking el **FAB se pone verde** y la notificación dice "Modo Picking".
2. **No romper lo existente:** en Inbound/Inventario, verificar que los containers secuenciales, el
   conteo de pallets, el código de equipo y la sincronización Firebase **siguen igual** (PickTracker
   no debe leer nada fuera de Picking).
3. **Lista de Pick Task:** abrir la lista en el WMS en modo Picking y pulsar **"📋 Leer toda la lista
   (desliza sola)"**; comprobar que detecta las tarjetas (TASK, envío, zona, ubicaciones, DNs) y que
   al terminar **la lista vuelve al inicio**.
4. **Detalle de tarea:** entrar en una tarea y confirmar que el overlay muestra el **TASK en curso**,
   el conteo de ubicaciones y el progreso "A/B Skus, C/D EA".
5. **Cierre de tarea:** terminar una tarea (diálogo "All steps finished…") o pulsar "Cerrar tarea" y
   ver que **suma tiempo, tareas y piezas al día**.
6. **Overlay:** confirmar que **cierra con la tecla Atrás** y con el botón **"✕ Cerrar"**, y que los
   toques fuera del overlay pasan al WMS.
7. **Rendimiento:** comprobar que durante el picking la app no se traba (el trabajo pesado corre en
   `ioExecutor` y el scroll en un hilo aparte; nada pesado en el hilo principal).
8. **"Enviar a iPhone" (v5.16):** confirmar que el botón de Diagnóstico sigue funcionando (no se
   perdió nada de la v5.16).
