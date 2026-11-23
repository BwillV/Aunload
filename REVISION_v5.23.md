# Revisión v5.23 — Correcciones del FAB de Picking

**versionCode:** 53 · **versionName:** 5.23
**Build:** `BUILD SUCCESSFUL` (assembleDebug) · APK: `PalletTracker-5.23-debug.apk`
**Validación:** compilado y verificado de forma lógica/offline (banco de pruebas `pttest`). **NO probado en escáner físico** (iData 50, Android 9, API 28, sin GMS).

---

## Los 7 puntos reportados

### 1. El FAB reconocía tasks que NO están en New (Done / In Progress) y tarjetas sin ubicación ni ítems
- **`PickTrackerStore.getPendingTasks()`**: se descarta toda tarjeta sin ubicación **y** sin artículos
  (`if (articles <= 0 && locs.isEmpty()) continue;`). Ya no aparecen "tasks fantasma".
- **`PickTrackerParser`**: nueva señal de pestaña vacía. `isNewTabActive()` devuelve `false` si detecta el
  marcador **"Empty"** junto a tarjetas visibles (significa que New está vacía y lo que se ve es de otra
  pestaña). Nuevos métodos `hasEmptyMarker(...)` y `maxNewTabCount(...)` (máximo `New(n)` entre los pasos de scroll).
- **`PickTrackerHelper`**: guardia agregada al terminar la lectura — si se guardaron más tareas que el
  máximo `New(n)` detectado, se descarta la lectura y se avisa (`pt_no_new`). Esto evita colar tarjetas de
  Done/In Progress cuando el filtro por paso no alcanza.

### 2. No salía el tiempo estimado por pallet
- Antes, si aún no había tiempos reales medidos, no se mostraba estimación.
- Ahora **siempre** se muestra un tiempo: si existen tus tiempos reales (hoy/ayer) se usan esos; si no, se
  usa un **estimado base** (~3 min/ubicación) etiquetado honestamente como base (`pt_fuente_base`), para no
  hacer pasar un promedio inventado por dato real.
- Aplicado tanto en el FAB (`PickTrackerHelper`) como en la pantalla Picking (`PickingActivity.renderPlan()`).

### 3. Juntar tasks con más de 7 artículos pero sin pasar de 3 ubicaciones por tarea
- `PickTrackerAdvisor`: nueva constante `MAX_MERGE_LOCS = 3`. Una tarea va **sola** si tiene ≥20 artículos
  **o** si tiene más de 3 ubicaciones. Las demás sí se pueden combinar aunque superen 7 artículos.

### 4. Check manual = terminado válido, pero sin contar en estadísticas
- Ya se cumplía por diseño: el check manual solo llama `setOrderDone` (marca la orden como hecha), y **nunca**
  `recordCompletion`. Solo la auto-detección de "todos los pasos terminados" registra tiempos/estadísticas.
  Verificado y mantenido.

### 5. Más de 3 tasks por pallet, mientras no supere 20 artículos
- `PALLET_CAPACITY` subido de 17 → **20**. Se eliminó el tope de 3 órdenes por pallet; el único límite real
  es la capacidad de 20 artículos.

### 6. Dos tareas de 10 solo si no hay pequeñas; preferir pocas ubicaciones; última opción juntarlas igual
- Nuevo empaquetado por pasillo `packAisle(...)` en 3 fases:
  1. Cada tarea grande siembra un pallet y recoge pequeñas que quepan (≤20).
  2. Si **no quedaron pequeñas**, se emparejan entre sí los pallets de una sola tarea grande, prefiriendo los
     de menos ubicaciones. Como última opción se juntan igual si no hay alternativa.
  3. Se empaquetan las pequeñas sobrantes.

### 7. En los stats del FAB se contaban tasks como pallets
- `PickTrackerHelper` y `PickingActivity`: nuevo contador `palletsDone` (pallets con **todas** sus órdenes
  terminadas). Se muestra "Pallets completados: X / Y · N / M órdenes" (`pt_pallets_completados`), separando
  pallets de órdenes.

---

## Strings
Se añadieron 3 strings nuevos con paridad en los 3 idiomas (total **398** por idioma):
`pt_fuente_base`, `pt_pallets_completados`, `pt_no_new` (ES/EN/ZH).

## Intacto (sin cambios)
Containers secuenciales, código de equipo, sync Firebase de pallets/containers, eliminaciones a todo el
equipo, conteo, detección WMS, reglas Firebase, UpdateChecker, applicationId y firma. Picking sigue 100% local.
