# Revisión v5.30 (versionCode 60)

Dos cambios pedidos por Wilson.

## 1. Estimado y hora de término por tarea Y por pallet
- Antes el estimado de tiempo solo salía por pallet. Ahora también sale **debajo de cada tarea** (`~N min`), con el mismo modelo (nº de ubicaciones × tiempo por ubicación propio/base).
- Nuevo: **"a qué hora terminarás"** por pallet y por tarea. Es acumulado desde ahora: si empiezas ahora y haces los pallets/tareas en orden, cada línea muestra la hora de reloj (HH:mm) a la que terminas ese pallet o esa tarea.
- Se muestra en los dos lugares donde aparece el plan: la pantalla **Modo Picking** (`PickingActivity`) y el **overlay del FAB 📦** (`PickTrackerHelper`).
- Strings nuevos (ES/EN/ZH, 400 claves c/u): `pt_pallet_termina`, `pt_tarea_tiempo`.

## 2. Agrupar: grande+pequeña primero, grande+grande solo último recurso
- Problema reproducido offline con v5.29: dos tareas grandes "pesadas" (>3 ubicaciones) se juntaban **grande+grande** aunque hubiera pequeñas libres (las pequeñas quedaban en otro pallet aparte).
- `packAisle` reescrito: TODAS las grandes (ligeras y pesadas) reciben pequeñas primero (reparto: una pequeña a cada grande, luego relleno hasta 20). Solo las grandes que quedan **sin ninguna pequeña disponible** se emparejan entre sí (grande+grande = último recurso), prefiriendo las de menos ubicaciones. Las pequeñas sobrantes se empaquetan juntas.
- Capacidad 20 artículos, cruce B/C y "empezar por lo más lejano" sin cambios.

## Validación offline (NO probado en escáner físico)
- Caso 2 grandes pesadas + 2 pequeñas → ahora grande+pequeña, grande+pequeña (antes grande+grande + pequeñas aparte). ✓
- Caso 2 grandes sin pequeñas → grande+grande (20), último recurso. ✓
- Diagnóstico real 23-nov → 3 pallets (13/6, 20/9, 20/9), igual que v5.29. ✓
- Cruces B/C (same-aisle, cerca cruza, lejos no cruza) sin regresión. ✓
- BUILD SUCCESSFUL. Badging: versionCode 60, versionName 5.30, minSdk 26, targetSdk 28.

**Compilado y verificado offline/lógicamente. NO probado en escáner físico (iData 50, Android 9, sin GMS).**
