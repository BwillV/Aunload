# Revisión del modo Picking — PalletTracker v5.18

**Versión:** versionCode 48 · versionName "5.18" · minSdk 26 · targetSdk/compileSdk 28
**APK:** `PalletTracker-5.18-debug.apk` (compilado con `BUILD SUCCESSFUL`)
**Paquete:** `com.demoaccesibilidad.capture`

> ⚠️ El APK está **compilado y verificado en el banco de pruebas offline**, pero **NO se ha probado en el escáner físico iData 50**. Las pruebas sobre pantallas reales del WMS las debe hacer Wilson en el equipo.

Esta versión **rehace por completo el modo Picking**. No se tocó nada del flujo de
containers secuenciales, el código de equipo, la sincronización Firebase de
pallets/containers, las eliminaciones al equipo, el conteo, la detección WMS de
inbound/inventario, `firebase_reglas.json`, el `UpdateChecker`, el `applicationId`,
la firma ni los SDKs. El módulo Picking sigue siendo **local** (SharedPreferences,
sin Firebase).

---

## Requisitos del usuario y cómo se cumplió cada uno

### 1. El modo Picking se activa/desactiva desde el MENÚ DE LA APP (no desde el FAB)
- Se añadió la opción **"📦 Modo Picking"** (`nav_modo_picking`) al drawer de
  `MainActivity` (`res/menu/drawer_menu.xml`).
- En `MainActivity.onNavigationItemSelected` esa opción **alterna** entre Picking e
  Inbound: `AppMode.setMode(this, entrar ? MODE_PICKING : MODE_INBOUND)`, muestra un
  Toast con el modo y llama a `sincronizarSwitchModo()` + `refrescarDatos()`.
- El **switch Inbound/Inventario de la home sigue funcionando** igual que en v5.17.
  `sincronizarSwitchModo()` ya refleja Picking: cuando el modo es Picking el switch
  queda apagado y la etiqueta de la home dice **"Modo: Picking"** (`main_modo_picking`).
  Al mover el switch se sale de Picking hacia Inbound/Inventario.
- En el **menú del FAB ya NO hay selector de Picking**: el botón "Cambiar de modo"
  del FAB cicla **solo Inbound ↔ Inventario** (`AppMode.setInventario(ctx, !isInventario)`),
  sin incluir Picking.

### 2. En modo Picking el menú del FAB muestra SOLO lo esencial
- `CopyTextHelper.showActionMenu` ahora se bifurca con `AppMode.isPicking(context)`:
  - **Modo Picking →** solo: **"Copiar SKU"**, **"Copiar locación"** y el botón
    **"📦 Picking"** (más "✕ Cerrar"). Se quitó todo lo demás en Picking
    (ubicaciones en pantalla, ILP, Yo/Total, cambio de modo, idioma).
  - **Inbound/Inventario →** el FAB queda como en v5.17 (ubicaciones en pantalla,
    Copiar ILP solo en Inbound, Copiar SKU, Copiar locación, Yo/Total solo en Inbound,
    cambiar de modo e idioma) **pero sin el botón "📊 PickTracker"** y con el selector
    de modo ciclando **solo Inbound↔Inventario**.

### 3. Overlay "📦 Picking" con el mismo estilo que "Ubicaciones en pantalla"
- `PickTrackerHelper` reescrito: overlay oscuro `TYPE_APPLICATION_OVERLAY`, botón
  **"✕ Cerrar"** y la **tecla Atrás del PDA (KEYCODE_BACK)** cierra (mismo andamiaje
  `OverlayRoot.dispatchKeyEvent` que el resto de overlays).
- Botón **"🔄 Nueva lectura"**: borra el resultado anterior
  (`PickTrackerStore.clearReading`) y lee la Pick Task List **con scroll automático**
  reutilizando `ScreenCaptureService.collectAllTextsScrolling` (corre en su propio
  hilo para no bloquear la UI → sin ANR); por cada paso aplica
  `PickTrackerParser.parseList` → `PickTrackerStore.savePendingCards`, y vuelve a
  dibujar el plan. Suma además las ubicaciones ya capturadas del detalle por tarea.
- **Persistencia:** el resultado se guarda en SharedPreferences
  (`picktracker_prefs`, sin Firebase) y se reconstruye al reabrir el overlay o la app,
  hasta pulsar "Nueva lectura".
- **Lista de pallets sugeridos:** por cada pallet se muestra cabecera (nº y pasillo o
  "cruce B/C"), **total de artículos, nº de ubicaciones y tiempo estimado**, y una fila
  por orden con **TASK, artículos (DN) y zona/ubicaciones**. Cada orden lleva un
  **CHECKBOX persistente** (`setOrderDone`/`isOrderDone`) para marcarla completada.
- Debajo: **total del día** (pallets, artículos, ubicaciones, minutos), contador de
  órdenes completadas y la pista de recorrido.

### 4. Reglas de sugerencia (PickTrackerAdvisor reescrito)
- **DN = número de artículos** de la orden (`TaskInfo.articles`). Se **eliminó el
  límite de 48 cajas** y toda la lógica de cajas.
- **Capacidad de pallet = 17 artículos** (`PALLET_CAPACITY = 17`, constante fácil de
  cambiar). Ejemplo de Wilson comprobado: una orden de 7 + una de 10 = 17 = pallet llena.
- **Orden pequeña = < 7 artículos** (`SMALL_ORDER_MAX = 6`). Se juntan pequeñas con
  grandes; **normalmente 2 por pallet, hasta 3** si caben en 17
  (`MAX_ORDERS_PER_PALLET = 3`). Una orden que sola **≥ 17** va sola (`SOLO_ORDER_MIN`).
- **Proximidad:** empaque por pasillo primero (B con B, C con C). Si quedan restos de
  una sola orden pegados a la entrega, se **cruza B alto con C bajo** (p. ej. B15 con
  C01), porque la entrega está en medio. Modelo de distancia: posición
  `(letra-'B')*15 + número`, entrega en 15.5; B15 y C01 quedan juntos al lado de la
  entrega.
- **Prioridad:** una orden con muchos artículos pero 1–2 ubicaciones (mismo producto
  repetido) se marca **"⭐ Casi completa"** y su pallet se ordena primero
  (`isBigSingleLocation`, `BIG_SINGLE_LOC_MIN_ARTICLES = 7`).
- **Recorrido:** los pallets se ordenan de lo **más lejano a la entrega hacia el
  centro** (B15/C01).
- **Tiempo:** `~3 min por ubicación` (`SECONDS_PER_LOCATION = 180`). Se muestra por
  pallet y el total del día.

### 5. Textos UI en los 3 idiomas con el mismo número de claves
- Se limpiaron las claves `pt_*` obsoletas (pt_sin_tarea, pt_tarea_actual, pt_hoy,
  pt_sin_datos, pt_mejor, pt_peor, pt_consejo, pt_empezar_ejemplo,
  pt_registrar_ubicacion, pt_leer_lista, pt_cerrar_tarea, pt_pendientes,
  pt_sin_pendientes, pt_progreso) y se añadieron las nuevas (pt_nueva_lectura,
  pt_leyendo, pt_sin_plan, pt_total_dia, pt_completadas, pt_pallet, pt_pallet_cross,
  pt_pallet_resumen, pt_prioridad, pt_orden_linea, pt_orden_zona, pt_orden_zona_ubic,
  pt_ruta) más `nav_modo_picking`. `pt_menu` y `pt_titulo` pasaron a "📦 Picking".
- **`values/`, `values-en/` y `values-zh-rCN/` tienen 359 claves cada uno**, con el
  **mismo conjunto exacto** de nombres (verificado: 0 diferencias, 0 duplicados, XML
  válido en los tres).

---

## Resultado de la compilación

```
BUILD SUCCESSFUL in 16s
package: versionCode='48' versionName='5.18' compileSdkVersion='28'
sdkVersion:'26'
targetSdkVersion:'28'
```

## Resultado de la prueba offline (banco `/home/ubuntu/pttest`)

Se copiaron el `PickTrackerParser` y el `PickTrackerAdvisor` nuevos al banco y se
adaptó `Test.java` a la nueva API (TaskInfo de 5 argumentos con artículos y lista de
ubicaciones; `adviceText(List)`; `plan(List)`). Se ejecutó sobre el **diagnóstico real
de Wilson** (`/home/ubuntu/diag_will_lineas.txt`, 300 pantallas):

```
Pantallas lista=14 detalle=160 con progreso=155

=== TAREAS PENDIENTES DETECTADAS (5) ===
TASK-44275 | envio=FedEx Single            zona=C13 art(DN)=9  ubic=1 locs=[C14-192-3]
TASK-44625 | envio=UPS Siingle (Regular)   zona=C04 art(DN)=10 ubic=0 locs=[]
TASK-44628 | envio=UPS Siingle (Regular)   zona=C04 art(DN)=2  ubic=0 locs=[]
TASK-44792 | envio=UPS - Single Qty (VCDF) zona=B07 art(DN)=8  ubic=5 locs=[B07-091-2, B07-093-4, B07-138-4, B08-193-4, B09-159-4]
TASK-44793 | envio=UPS - Single Qty (VCDF) zona=B04 art(DN)=7  ubic=5 locs=[B04-032-4, B05-165-5, B05-190-3, B06-012-4, B06-124-2]

=== PLAN DE PALLETS ===
Pallet 1 [pasillo C]: TASK-44275(9art,1ubic) => 9 art, 1 ubic, ~3 min PRIORIDAD
Pallet 2 [pasillo B]: TASK-44793(7art,5ubic) + TASK-44792(8art,5ubic) => 15 art, 10 ubic, ~30 min
Pallet 3 [pasillo C]: TASK-44625(10art,0ubic) + TASK-44628(2art,0ubic) => 12 art, 0 ubic, ~0 min
Total: 3 pallets · 36 artículos · 11 ubicaciones · ~33 min.

=== PRUEBA SINTÉTICA DE REGLAS ===
[pasillo C] T-20(20) = 20 art        → una orden ≥17 va sola
[pasillo B] T-10(10) T-7(7) = 17 art → 7+10=17 llena la pallet (ejemplo de Wilson)
[cruce B/C] T-3b(3) T-3c(3) = 6 art  → B15 se cruza con C01 (entrega en medio)
```

**Interpretación:**
- TASK-44275 (9 artículos en 1 sola ubicación) se detecta como **casi completa** y su
  pallet se prioriza. ✔
- Las órdenes del pasillo B (44792+44793 = 15 art ≤ 17) se juntan en una pallet; las
  del pasillo C (44625+44628 = 12 art) en otra. ✔ (junta pequeña con grande, ≤17)
- La prueba sintética confirma las tres reglas clave: orden ≥17 sola, 7+10=17, y el
  cruce B15/C01. ✔

> Recordatorio: lo anterior valida la **lógica pura** (Parser + Advisor) sobre datos
> reales. El comportamiento en vivo (scroll de la lista, overlay, checkboxes,
> persistencia) debe probarse en el escáner físico iData 50.

---

## Archivos modificados
- `app/build.gradle` — versionCode 48, versionName "5.18".
- `app/src/main/java/.../PickTrackerAdvisor.java` — reescrito (reglas nuevas).
- `app/src/main/java/.../PickTrackerStore.java` — reescrito (persistencia de lectura +
  checkboxes, sin cajas).
- `app/src/main/java/.../PickTrackerHelper.java` — reescrito (overlay "📦 Picking").
- `app/src/main/java/.../CopyTextHelper.java` — menú del FAB por modo.
- `app/src/main/java/.../MainActivity.java` — opción de menú "Modo Picking".
- `app/src/main/res/menu/drawer_menu.xml` — item `nav_modo_picking`.
- `app/src/main/res/values{,-en,-zh-rCN}/strings.xml` — claves `pt_*` y `nav_modo_picking`.

**Sin tocar:** containers secuenciales, código de equipo, sync Firebase, conteo,
detección WMS inbound/inventario, firma, SDKs, "Enviar a iPhone", KEYCODE_BACK y el
recuadro verde de "Ubicaciones en pantalla".
