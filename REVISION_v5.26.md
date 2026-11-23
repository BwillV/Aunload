# Revisión v5.26 (versionCode 56)

Basada en el diagnóstico REAL de v5.25 tomado tras "New reading"
(`equipos/INV/diagnosticos/20261123_071140_Will.json`), donde la app **sí
leyó y sí sugirió**, pero salió una sugerencia vacía.

## Problema 1 — "me tiró una sugerencia que no tiene nada"
En el diagnóstico la pestaña New(4) trajo 7 tarjetas al hacer scroll. Dos de
ellas llegaron **sin su fila "Suggest Location"** porque quedaron cortadas al
borde del scroll:
- `TASK-52431` → 1 orden, sin ubicación → es la "sugerencia que no tiene nada".
- `TASK-52699` → 10 órdenes, sin ubicación capturada en ningún paso.

Las demás (52660, 52661, 52698, 52632, 52633) sí traían ubicación.

**Arreglo:** `PickTrackerStore.getPendingTasks` ahora descarta toda tarjeta sin
ubicación (`locs.isEmpty()`), tenga o no artículos. Antes solo se descartaba si
NO tenía ni artículos ni ubicación (`articles<=0 && locs.isEmpty()`), por eso
52431 (1 artículo) pasaba el filtro y aparecía como pallet sin bin.
`savePendingCards` fusiona y conserva la ubicación si cualquier paso la vio, así
que esto solo quita tarjetas realmente sin ubicación en ninguna lectura.

**Validación offline (ReproFB.java sobre el diagnóstico real):**
7 tarjetas leídas → 2 descartadas (52431, 52699) → 5 pendientes →
**4 pallets sugeridos, todos con ubicación y artículos, ninguno vacío.**

> Nota: 52699 (10 órdenes) también se omitió porque su ubicación no se capturó
> en ningún paso del scroll. No es una tarjeta basura; es una tarjeta real cuya
> fila de ubicación quedó cortada. Si se repite con tareas reales, hay que
> mejorar la captura del scroll (otro diagnóstico). Preferimos omitir una
> tarjeta sin bin antes que mostrar una sugerencia vacía.

## Problema 2 — "quiero que SIEMPRE lea las tareas que voy terminando aunque no haya sugerido bien"
`PickTrackerStore.endTask` solo sumaba la tarea a las estadísticas si se había
cronometrado (`start > 0`). Ahora **toda tarea que se cierra suma +1** a las
estadísticas (hoy y acumulado), aunque no se haya cronometrado ni sugerido.
El tiempo y las ubicaciones solo se aportan si hubo cronómetro, para no ensuciar
el promedio "tiempo por ubicación" con tareas sin medir. El marcado automático
de la orden como completada (checkbox) se mantiene igual.

## Sin cambios
- Strings (3 idiomas, misma paridad), containers, código de equipo, sync
  Firebase de pallets/containers, eliminaciones, conteo, detección WMS,
  UpdateChecker, applicationId, firma, SDKs.

## Validación
- Compilado: **BUILD SUCCESSFUL** (gradle 8.7, Java 17).
- Badging: versionCode 56, versionName 5.26, minSdk 26, targetSdk 28.
- Lógica validada OFFLINE contra el diagnóstico real.
- **NO probado en el escáner físico (iData 50 / Android 9).**
