# Análisis de la "pantalla de la jefa" (panel de estadísticas de picking del WMS)

Wilson subió dos capturas del panel que usa la jefa para ver el rendimiento del equipo de
picking. Este documento transcribe exactamente qué muestra, para usarlo de modelo en las
estadísticas personales de PalletTracker v5.19.

---

## Captura 1 (en inglés) — tabla por trabajador

Encabezados de columna (de izquierda a derecha):

| Worker | Tasks | Total Inventory | Total Locations | Picked Inv |
|--------|-------|-----------------|-----------------|------------|

Filas de ejemplo (transcripción literal):

| Worker | Tasks | Total Inventory | Total Locations | Picked Inv |
|--------|-------|-----------------|-----------------|------------|
| Wander Cruz | 8 | 66 | 31 | 48 |
| Yesler Zamora | 7 | 64 | 10 | 64 |
| Ivan Ortiz | 9 | 75 | 48 | 59 |
| Wilson Blandon Velasquez | 15 | 117 | 68 | 89 |
| Leo Munguia | 6 | 41 | 27 | 31 |

> Nota: "Wilson Blandon Velasquez" es el propio Wilson. En esta captura su fila es
> 15 tasks, 117 inventario total, 68 ubicaciones totales, 89 inventario recogido.

---

## Captura 2 (en chino) — mismo panel con tarjetas resumen + tabla ampliada

### Tarjetas resumen (arriba)

| Valor | Etiqueta (chino) | Traducción | Sub-dato |
|-------|------------------|------------|----------|
| 383 | 总任务数 | Total de tareas | 作业人员 24 = 24 trabajadores |
| 2,286 | 总库存 | Inventario total (EA) | 已拣货 1,502 = recogido 1,502 |
| 1394 | 总库位数 | Total de ubicaciones | 已拣库位 916 = ubicaciones recogidas 916 |
| 66% | 平均进度 | Progreso promedio | 每库位耗时 47分21秒 = tiempo por ubicación 47m21s |
| 30天2时 | 总耗时 | Tiempo total (30 días 2 h) | 平均每库位耗时 47分21秒 |

### Tabla por trabajador (columnas ampliadas)

| 作业人员 (Worker) | 任务 (Tasks) | 总库存 (Total Inv) | 总库位数 (Total Loc) | 已拣库存 (Picked Inv) | 已拣库位 (Picked Loc) | 进度 (Progress) | 耗时 (Time) | 每库位耗时 (Time/Loc) |
|---|---|---|---|---|---|---|---|---|
| Abel Dejesus | 26 | 141 | 70 | 128 | 57 | 90% | 1天2时 | 28分10秒 |
| Jose Vegas | 22 | 187 | 130 | 126 | 86 | 67% | 1天2时 | 18分47秒 |
| Gerado Ventura | 13 | 43 | 37 | 43 | 37 | 100% | 1时33分 | 2分31秒 |
| Pedro Guerrero | 9 | 78 | 53 | 77 | 53 | 98% | 3时48秒 | 3分25秒 |
| Temp Outbound2 | 16 | 116 | 51 | 116 | 51 | 100% | 2时18秒 | 2分22秒 |
| Selvin Caal | 16 | 129 | 76 | 122 | 69 | 94% | 1时55分 | 1分40秒 |

---

## Métricas identificadas (modelo para PalletTracker)

Por cada trabajador, el panel muestra:

1. **Tasks (任务)** — número de tareas (TASK-xxxxx) de picking realizadas.
2. **Total Inventory (总库存)** — total de artículos/EA asignados.
3. **Total Locations (总库位数)** — total de ubicaciones asignadas.
4. **Picked Inventory (已拣库存 / Picked Inv)** — artículos/EA efectivamente recogidos.
5. **Picked Locations (已拣库位)** — ubicaciones completadas.
6. **Progress (进度)** — porcentaje = recogido / total (usa EA o ubicaciones).
7. **Time (耗时)** — tiempo total trabajado.
8. **Time per location (每库位耗时)** — tiempo promedio por ubicación.

Totales del equipo (tarjetas): total tareas, total inventario (+recogido), total
ubicaciones (+recogidas), progreso promedio, tiempo total, tiempo promedio por ubicación.

### Cómo se traduce a PalletTracker (SOLO datos propios, 100% local)

PalletTracker ya detecta por accesibilidad el progreso de cada tarea
("N / M Skus, X / Y EA Picked"), las ubicaciones visitadas, y los diálogos
"All steps finished" / SUBMIT. Con eso se pueden calcular, **solo para el propio
dispositivo/usuario**, las mismas métricas de la jefa:

- Tareas completadas (hoy y acumulado)
- Artículos/EA recogidos (= "Picked Inv")
- Ubicaciones completadas (= "Picked Locations")
- Tiempo total
- **Tiempo promedio por ubicación** (= 每库位耗时) → clave para estimar pallets
- Tiempo promedio por artículo (EA)
- Tiempo promedio por tarea
- EA por hora (velocidad)

Y con el **tiempo propio por ubicación** y **por artículo** se estima, en la ventana de
sugerencias de pallets, cuánto tardaría Wilson en cada pallet:

    tiempo_pallet ≈ (nº ubicaciones del pallet × promedio propio por ubicación)
                  + (nº artículos del pallet × promedio propio por artículo)

Si aún no hay datos propios suficientes, se usa el valor por defecto (~3 min/ubicación,
referencia del usuario 3:03), indicando que la estimación es "por defecto" y no "con tus datos".
