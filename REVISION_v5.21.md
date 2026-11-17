# PalletTracker v5.21 (versionCode 51)

Versión centrada en el modo **Picking**, según lo que pediste.

## 1. La pantalla de estadísticas ya se ve natural dentro de la app
**Problema:** al abrir "📦 Modo Picking" desde el menú, la pantalla de
estadísticas parecía una ventana sobrepuesta/flotando encima de la app, distinta
de la pantalla principal.

**Causa:** `PickingActivity` usaba un tema **sin barra superior** y una paleta
propia muy oscura (`#11161C`), por lo que no tenía barra, ni título, ni flecha de
volver, y los colores no eran los del resto de la app.

**Arreglo:** ahora la pantalla tiene:
- Barra superior azul `#0f3460` con el título y la **flecha para volver**.
- El mismo fondo `#1a1a2e` y las mismas tarjetas/colores que la pantalla
  principal (azul `#2196F3`, verde `#4CAF50`, dorado para los totales).
- Scroll vertical completo: **nada queda cortado**, se puede bajar hasta el final.

## 2. La lista ya no se corta
Todo el contenido va dentro de un `ScrollView` a pantalla completa, con la barra
fija arriba. En el overlay del FAB la lista ya iba acotada con su scroll y el
botón **Cerrar** visible (se mantiene).

## 3. Solo se lee la pestaña **New**
Al leer la "Pick Task List" del WMS, ahora **solo** se consideran las tareas de la
pestaña **New**; nunca las de **In Progress** ni **Done**.
- Nuevo filtro en `PickTrackerParser` (`newTabCount`, `isNewTabActive`,
  `parseListNewOnly`). Si la pestaña New está en 0 o lo que se ve en pantalla no
  es la lista de New, no se lee ninguna tarea.
- Aplicado en los dos puntos donde se leen tareas: el detector automático
  (`ScreenCaptureService`) y "Nueva lectura" del overlay (`PickTrackerHelper`).

**Prueba offline** contra tu diagnóstico real (pestañas `New(0)`,
`In Progress(1)`, `Done(84)`):
- Antes se leían 5 tareas (de In Progress/Done). **Ahora se leen 0** (correcto,
  porque New está en 0).
- Caso sintético con `New(2)` y 2 tarjetas → lee 2 (las de New).
- Caso sintético con `New(0)` pero con tarjetas de Done a la vista → lee 0.

## 4. Botón **Reset de lectura**
- En la pantalla de Picking: botón **"🔄 Reset de lectura"** (con confirmación)
  que borra la lectura de tareas guardada (tareas, ubicaciones, SLP y checks)
  para volver a leer bien, **sin borrar tus estadísticas personales**.
- En el overlay del FAB: se añadió también un botón **"Reset de lectura"**,
  separado de "Nueva lectura".
- El botón de **reiniciar estadísticas** ahora también pide confirmación y queda
  claramente diferenciado del de lectura.

## 5. El estimado de tiempo por pallet se mantiene
No se eliminó. Se sigue mostrando el tiempo estimado por pallet y por día, con tu
promedio personal (hoy/ayer) y "sin datos aún" cuando no hay datos. El SLP de
cada orden sigue apareciendo junto al pallet.

## Qué NO se tocó
Containers secuenciales, código de equipo, sincronización de Firebase, conteo,
detección de inbound/inventario, UpdateChecker, firma, SDKs. El FAB sigue igual
(Copiar SKU, Copiar locación y 📦 Picking). Picking sigue 100% local.

## Archivos cambiados
- `PickTrackerParser.java` — filtro solo-New.
- `ScreenCaptureService.java` y `PickTrackerHelper.java` — usan el filtro.
- `PickingActivity.java` — barra superior + paleta del home + pallets + Reset de
  lectura + confirmaciones.
- `res/values*/strings.xml` (ES/EN/ZH) — 7 textos nuevos (395 claves c/u).
- `app/build.gradle` — versionCode 51 / versionName "5.21".

## Nota importante
El APK compila y se validó **offline** con tu diagnóstico real, pero **NO se ha
probado en el escáner físico iData 50**. Conviene instalarlo y hacer una lectura
real con la pestaña New abierta para confirmarlo en el equipo.
