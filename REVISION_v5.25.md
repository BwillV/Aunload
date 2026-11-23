# Revisión v5.25 (versionCode 55)

## Problema
"Sale vacío siempre al dar New reading" — persistía desde v5.21 pese a v5.22/5.23/5.24.

## Causa raíz (confirmada offline)
`PickTrackerParser.isNewTabActive()` exigía `cards <= New(n)`. El lector en vivo
(`collectAllTextsScrolling`) acumula nodos del RecyclerView del WMS, así que un
solo paso trae MÁS tarjetas que New(n) (p. ej. 8 tarjetas con `New(5)`). La
comparación `8 <= 5` daba false → `parseListNewOnly` devolvía vacío en cada paso
→ lectura vacía. Offline con pasos "limpios" (≤5) no se reproducía; con el paso
acumulado real sí.

## Cambio (mínimo, 1 archivo)
`PickTrackerParser.java` → `isNewTabActive()`: se elimina la comparación frágil
`cards <= newN`. Si `New(n>0)` y no hay marcador "Empty", es la pestaña New → se
lee. Se conservan las guardas que SÍ detectan Done/In Progress:
- "Empty" + tarjetas → NO es New.
- `New(0)` + tarjetas → NO es New.

## Validación offline
- Paso acumulado (8 tarjetas, `New(5)`): antes 0 → ahora devuelve tarjetas (se
  deduplican por id en el store). BUG CORREGIDO.
- Regresión `New(0)+Empty+Done`: sigue devolviendo 0 (no lee Done/In Progress).
- Diagnóstico real 23-nov per-paso: 8 tareas conservadas.

## Build
- versionCode 55, versionName "5.25". minSdk 26 / target 28 / compile 28.
- BUILD SUCCESSFUL. APK: PalletTracker-5.25-debug.apk.
- Strings sin cambios (paridad ES/EN/ZH intacta).

## IMPORTANTE
Compilado y verificado offline/lógicamente. NO probado en escáner físico
(iData 50, Android 9, API 28, sin GMS).
