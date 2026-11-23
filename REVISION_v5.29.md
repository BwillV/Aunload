# Revisión v5.29 (versionCode 59)

## Problema reportado por Wilson
El consejero de picking **no juntaba tareas hasta llegar a 20 artículos**. Con su
diagnóstico real (6 tareas de ~10 artículos, 4-5 ubicaciones cada una) sugería
**6 pallets de 10** en vez de llenarlos a 20.

## Causa
En `PickTrackerAdvisor.plan()` la regla de v5.23 mandaba SOLA a cualquier tarea
con más de 3 ubicaciones (`t.locCount() > MAX_MERGE_LOCS`). Como las tareas de 10
artículos del almacén suelen tener 4-5 ubicaciones, TODAS iban solas y nunca se
emparejaban dos para llegar a 20.

## Reglas de Wilson aplicadas (confirmadas en este turno)
1. Se pueden juntar **más de 3 tareas** en una pallet mientras no se superen los
   **20 artículos** (sin tope de nº de órdenes).
2. **Dos tareas de ~10**: solo se emparejan cuando no se pueden juntar con
   pequeñas (o no hay pequeñas). Se prefieren las de **menos ubicaciones**; como
   **último recurso** se juntan aunque tengan muchas ubicaciones, si no hay
   alternativa.
3. Se pueden juntar tareas de **más de 7 artículos** si **no pasan de 3
   ubicaciones** (tareas "ligeras").
4. Cruce **B↔C solo cerca del punto de entrega** (B alto ↔ C bajo, tipo
   B15↔C01). Nunca los lejanos (B01↔C16). Se respeta la lógica de cruce por
   cercanía a la entrega ya existente.

## Cambios (`PickTrackerAdvisor.java`)
- `plan()`: ya **no** se manda sola una tarea solo por tener >3 ubicaciones.
  Solo va sola la que por sí misma ya llega a 20 artículos (`SOLO_ORDER_MIN`).
- `packAisle()` reescrito. Clasifica cada tarea en:
  - **PEQUEÑA** (≤6 art): se junta libremente.
  - **LIGERA** (grande con ≤3 ubicaciones): se junta normalmente.
  - **PESADA** (grande con >3 ubicaciones): normalmente sola, pero se empareja
    de a dos como último recurso (dos de 10 = 20) aunque tengan muchas
    ubicaciones.
  - Fases: 1) ligeras siembran y recogen pequeñas; 2) emparejar dos ligeras;
    3) emparejar dos pesadas (preferir menos ubicaciones); 4) rellenar huecos con
    pequeñas sobrantes; 5) empaquetar pequeñas restantes.
- Nuevo helper `pairPallets()` para emparejar de a dos preferiendo pocas
  ubicaciones.

## Validación OFFLINE (banco /home/ubuntu/pttest)
- Diagnóstico real `20261123_063707_Will.json`: antes 6 pallets de 10 → ahora
  **3 pallets (20, 13, 20)**:
  - Pallet C: TASK-52632 (10) + TASK-52699 (10) = 20
  - Pallet C: TASK-52698 (10) + TASK-52633 (3) = 13
  - Pallet B: TASK-52661 (10) + TASK-52660 (10) = 20
- Cruce cerca de entrega (B14/B15 ↔ C01/C02): **cruza** (20).
- Cruce lejano (B01 ↔ C16): **NO cruza** (quedan separados).
- Same-aisle tiene prioridad sobre el cruce.

## Build
- `versionCode 59`, `versionName "5.29"`, minSdk 26, targetSdk/compileSdk 28.
- BUILD SUCCESSFUL. APK: `PalletTracker-5.29-debug.apk`.

## IMPORTANTE
Compilado y **verificado offline / lógicamente**. **NO probado en el escáner
físico** (iData 50, Android 9, API 28, sin GMS). Falta confirmación en hardware.
