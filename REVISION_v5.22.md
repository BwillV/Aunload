# Revisión v5.22 — Picking mode se ve igual que la app

**versionCode = 52 · versionName = "5.22"**
APK: `PalletTracker-5.22-debug.apk` (compileSdk/targetSdk 28, minSdk 26).

## Qué se vio en las capturas de la v5.21

Wilson envió 3 fotos del escáner (iData 50, Android 9):

1. **Pantalla principal (home).** Toolbar azul marino `#0f3460` con "PalletTracker",
   debajo la barra "Modo: Picking" sobre `#0d2137` con el selector segmentado
   **Inbound / Inventario / Picking**, fondo marino plano `#1a1a2e` y tarjetas
   redondeadas con sombra. Se ve como una app Material normal.

2. **Drawer abierto.** Cabecera con el logo, "PalletTracker v5.21" y el menú
   (Home, Picking mode, Language, Settings, Errors, About, Check for updates).

3. **Pantalla de Picking (PickingActivity, "Picking — My statistics").** Se veía
   **distinta a la app**: parecía una pantalla "pegada" encima. Las secciones
   (Today / Yesterday / Suggested pallets) eran rectángulos planos de bordes
   rectos, sin sombra, y como sus colores eran casi iguales al fondo se leían
   como grandes bloques azules con texto suelto. El bloque "Yesterday" usaba un
   azul más claro (`#2a2a4a`) que resaltaba como un panel aparte. Además saltaba
   directo a las estadísticas: **le faltaba la barra "Modo:"** que sí tiene el
   home, por eso "no se miraba natural".

## Causa

`PickingActivity` dibujaba las tarjetas como `LinearLayout` planos (esquinas
rectas, sin elevación) en vez de las `CardView` redondeadas que usa el resto de
la app (`item_task.xml`: `#16213e`, radio 8dp, elevación 2dp, margen 4dp). Y no
mostraba ningún "cromado" de la app (barra de modo), así que se sentía otra
pantalla.

## Qué se cambió (v5.22)

Se reescribió **solo la parte visual** de `PickingActivity.java`, conservando
toda la funcionalidad:

- **Toolbar** azul `#0f3460` con el nombre **"PalletTracker"** (misma marca que
  el home) y flecha de volver.
- **Barra "🔀 Modo: Picking — Mis estadísticas"** sobre `#0d2137`, igual en
  estilo a la barra de modo de la pantalla principal.
- **Fondo plano `#1a1a2e`** (sin degradados ni paneles sueltos).
- Todas las secciones (Hoy, Ayer, Pallets sugeridos) ahora son **`CardView`
  redondeadas** `#16213e` con radio 8dp y elevación, idénticas a las tarjetas
  del home. Cada pallet sugerido es una **sub-tarjeta redondeada** `#1f2b4d`.
- "Ayer" ya no usa el azul claro que desentonaba: usa el mismo `#16213e`.

### Funcionalidad conservada (sin cambios de lógica)

- Estadísticas de **Hoy** y **Ayer** (reinicio diario; "sin datos aún").
- **Estimado de tiempo por pallet siempre visible** (promedio personal de
  hoy/ayer; "sin datos aún" si no hay) — sin regla 3:03.
- **Pallets sugeridos solo de la pestaña New**, con su **SLP** por orden y
  **check** por orden terminada.
- Botón **"🔄 Reset de lectura"** (borra la lectura, NO las stats, con
  confirmación) y botón **"Reiniciar stats"** (con confirmación).
- Flecha de volver / `KEYCODE_BACK`.
- El overlay del FAB 📦 Picking (`PickTrackerHelper`) **no se tocó**.

## Strings

No se agregaron claves nuevas: se reutilizaron las existentes. Paridad mantenida:
**395 claves** en `values/`, `values-en/` y `values-zh-rCN/`.

## Lo que NO se tocó

Containers secuenciales, código de equipo, sync Firebase de pallets/containers,
eliminaciones al equipo, conteo, detección WMS inbound/inventario,
`firebase_reglas.json`, `UpdateChecker`, `applicationId`, firma, SDKs, rama main.
No se reintrodujo Portapapeles en el FAB. Scroll automático de "Ubicaciones en
pantalla" intacto.

## Validación

- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**.
- Badging: `package com.demoaccesibilidad.capture versionCode=52 versionName=5.22`,
  `sdkVersion=26`, `targetSdkVersion=28`.
- **Compilado y validado offline. NO probado en el escáner físico iData 50.**
