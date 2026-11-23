# Revisión v5.24 (versionCode 54)

## Problema reportado por Wilson
En v5.23, al tocar **"New reading"** en Picking (pantalla WMS: New · In Progress · Done),
la lectura **quedaba vacía, "no lee nada"**.

## Causa real (probada con diagnóstico 20261123_063707_Will.json)
El diagnóstico tomado tras pulsar "New reading" mostraba, en cada paso del scroll,
`New(5)` con 5 tarjetas visibles (el filtro por-paso funcionaba bien). Pero el lector
recorre la lista con scroll y acumula tarjetas de varios pasos: la **unión** daba
**8 tareas distintas** (el RecyclerView del WMS recicla/pega nodos durante el scroll).

La **guardia agregada en v5.23** (en `PickTrackerHelper.nuevaLectura`) hacía:

    if (pendientes.size() > maxNewTabCount)  ->  clearReading()  // BORRA TODO

Como `8 > 5`, borraba la lectura completa y la dejaba vacía. **Ese era el bug.**

Reproducción offline (banco pttest, con el JSON real):
- `maxNewTabCount = 5`, tareas guardadas = **8**
- v5.23: `8 > 5` → `clearReading()` → **VACÍO** (bug)
- v5.24: conserva la lectura → **no vacía** (8 tareas)

## Corrección (fail-open) — enfoque opuesto a v5.21–v5.23
Las tres versiones anteriores fueron **añadiendo filtros** cada vez más estrictos, y el
último terminó borrando toda la lectura. v5.24 hace lo contrario:

- Se **elimina** la guardia destructiva que llamaba `clearReading()` cuando la unión de
  tareas superaba a `New(n)`. Nunca más se borra una lectura que sí trajo tareas.
- Se mantiene el filtro **por paso** `parseListNewOnly` (cada paso solo aporta tareas si
  esa pantalla corresponde a la pestaña New), que es correcto.
- Si tras leer todos los pasos **no** se obtuvo ninguna tarea, solo se **avisa** (toast),
  sin borrar nada.

Archivo tocado: `PickTrackerHelper.java` (bloque de `nuevaLectura`).

## Validación
- Compila con Gradle 8.7 / Java 17. BUILD SUCCESSFUL.
- Badging: versionCode 54, versionName 5.24, minSdk 26, targetSdk 28.
- Reproducción offline con el diagnóstico real de Wilson: la lectura ya no queda vacía.
- **NO probado en escáner físico** (iData 50, Android 9, API 28, sin GMS). Verificado por
  lógica y reproducción offline únicamente.
