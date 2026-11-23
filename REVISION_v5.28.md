# Revisión v5.28 (versionCode 58)

## Problema reportado por Wilson
> "Cuando le doy nueva lectura, el scroll baja normal en la pantalla como que
> está leyendo, pero después cuando me vuelve a aparecer la pantalla donde está
> new reading no sale nada, sale vacío."

El scroll leía bien, pero la lista de sugerencias salía vacía después.

## Causa raíz (encontrada y reproducida offline)
En `PickTrackerParser.isNewTabActive()` la guardia:

```java
if (hasEmptyMarker(textos) && cards > 0) return false;
```

se evaluaba **antes** de mirar el contador `New(n)`. El servicio de
accesibilidad usa `flagIncludeNotImportantViews` (en `accessibility_config.xml`),
por lo que el volcado de pantalla incluye el placeholder **"Empty"** de las
pestañas que están **fuera de pantalla** (In Progress / Done vacías).

Resultado: aunque la pestaña **New(5)** tuviera tareas reales visibles, ese
"Empty" suelto hacía que `isNewTabActive` devolviera `false` en **todos** los
pasos del scroll → `parseListNewOnly` devolvía 0 tarjetas en cada paso →
`savePendingCards` nunca se llamaba → la lectura quedaba vacía.

Esto explica exactamente el síntoma: el lector hace scroll (lee la pantalla)
pero al volver no hay nada guardado.

## Cambio aplicado
`isNewTabActive()` ahora usa el marcador "Empty" **solo** cuando `New(0)` (o sin
cabecera), que es el único caso en que "Empty" realmente pertenece a la pestaña
New. Con `New(n>0)` la pestaña New tiene tareas por definición, así que un
"Empty" del volcado proviene de otra pestaña fuera de pantalla y ya **no anula**
la lectura.

Archivo tocado: `PickTrackerParser.java` (método `isNewTabActive`).
No se tocó nada más (sin cambios de strings, sin tocar containers, código de
equipo, sync Firebase, UpdateChecker, firma ni SDKs).

## Verificación offline
- Paso `New(5)` + "Empty" suelto: antes devolvía **0 tarjetas**; ahora lee la
  tarjeta correctamente (`isNewTabActive=true`).
- Diagnóstico real de Wilson (23/11 06:37): sigue produciendo **6 pallets**
  sugeridos (sin regresión).
- `New(0)` con tarjetas de Done visibles: sigue devolviendo `false` (correcto, no
  lee Done como New).
- `New(0)` sin tarjetas: `true` (correcto).

## Estado
Compilado y verificado por lógica/offline. **NO probado en el escáner físico**
(iData 50, Android 9, API 28, sin GMS). Si tras actualizar sigue saliendo vacío,
mándame un diagnóstico tomado justo después de "Nueva lectura" para revisar el
volcado real.

Badging esperado: versionCode 58, versionName 5.28, minSdk 26, targetSdk 28.
