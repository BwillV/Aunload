package com.demoaccesibilidad.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repositorio de pallets — almacena datos usando SharedPreferences + JSON.
 *
 * v2.3: Soporte de agrupación por container y consulta de tasks únicos.
 *
 *  - Inserta pallets evitando duplicados por palletCode.
 *  - Agrupa por taskId o por containerCode.
 *  - Mantiene un contador total.
 *  - Permite limpiar todo (reset de turno).
 *  - Sincroniza con Firebase si está habilitado.
 *
 * Límite: 2000 pallets máximo para evitar bloat.
 */
public class PalletRepository {

    private static final String TAG = "PalletRepo";
    private static final String PREFS_NAME = "pallet_tracker_prefs";
    private static final String KEY_PALLETS = "pallets_json";
    private static final String KEY_COUNTER = "pallet_counter";
    // v3.2: número de container secuencial (Container 1, Container 2, ...).
    // (legado) se conserva solo para migración de datos viejos.
    private static final String KEY_CONTAINER_NUM = "container_number";
    // v4.4: NUMERACIÓN DIARIA. El número de container EMPIEZA EN 1 CADA DÍA.
    //  - KEY_CONTAINER_DATE guarda la fecha local (yyyy-MM-dd) del container activo.
    //  - KEY_CONTAINER_SEQ guarda el número de container de ESE día (1, 2, 3, ...).
    // El id interno de cada pallet (campo taskId) es "yyyy-MM-dd#N" para que los
    // containers de distintos días NUNCA se mezclen, pero en la UI se muestra
    // siempre como "Container N" (el N del día).
    private static final String KEY_CONTAINER_DATE = "container_date";
    private static final String KEY_CONTAINER_SEQ = "container_seq_today";
    private static final int MAX_PALLETS = 2000;

    private final SharedPreferences prefs;
    private final Context context;

    public PalletRepository(Context ctx) {
        this.context = ctx.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ====================== INSERTAR ======================

    /**
     * Inserta un pallet si no existe otro con el mismo palletCode.
     * Dual write: local + Firebase (si habilitado).
     * @return true si se insertó, false si ya existía.
     */
    public synchronized boolean insertPallet(Pallet pallet) {
        if (pallet == null || pallet.palletCode == null || pallet.palletCode.isEmpty()) {
            return false;
        }

        List<Pallet> all = getAllPallets();

        // Verificar duplicado por palletCode.
        for (Pallet p : all) {
            if (pallet.palletCode.equals(p.palletCode)) {
                return false;
            }
        }

        all.add(0, pallet);

        while (all.size() > MAX_PALLETS) {
            all.remove(all.size() - 1);
        }

        savePallets(all);

        int count = prefs.getInt(KEY_COUNTER, 0) + 1;
        prefs.edit().putInt(KEY_COUNTER, count).apply();

        // ═══ DUAL WRITE: Firebase ═══
        try {
            FirebaseManager fm = FirebaseManager.getInstance(context);
            if (fm.isTeamActive()) {
                // v4.9 — Reportar al container compartido del equipo.
                fm.reportPalletToTeam(pallet);
                Log.d(TAG, "Pallet\u00A0enviado\u00A0al\u00A0equipo:\u00A0" + pallet.palletCode);
            } else if (fm.isSyncEnabled()) {
                fm.reportPalletScanned(pallet);
                Log.d(TAG, "Pallet\u00A0sincronizado\u00A0a\u00A0Firebase:\u00A0" + pallet.palletCode);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error\u00A0sincronizando\u00A0pallet", e);
        }

        return true;
    }

    /**
     * v4.4: Actualiza la UBICACIÓN de un pallet YA guardado, buscándolo por su
     * palletCode. Se usa cuando la ubicación aparece en pantalla DESPUÉS de que
     * se confirmó "Put away success" (la pantalla se llena en varios eventos).
     * NO duplica el pallet: solo rellena la ubicación si estaba vacía.
     * @return true si se actualizó (había un pallet y no tenía ubicación).
     */
    public synchronized boolean updatePalletLocation(String palletCode, String location) {
        return updatePalletLocation(palletCode, location, false);
    }

    /**
     * v4.6: Variante con opción de FORZAR la sobrescritura.
     *  - force=false (automático): solo rellena si la ubicación estaba vacía.
     *    Nunca pisa una ubicación existente con otra distinta.
     *  - force=true (edición manual): el usuario puede corregir una ubicación
     *    ya existente.
     * En ambos casos, si cambió algo, se refleja en la lista local y en
     * Firebase (si la sincronización está activa).
     */
    public synchronized boolean updatePalletLocation(String palletCode, String location, boolean force) {
        if (palletCode == null || location == null || location.trim().isEmpty()) {
            return false;
        }
        String loc = location.trim();
        List<Pallet> all = getAllPallets();
        boolean changed = false;
        for (Pallet p : all) {
            if (palletCode.equals(p.palletCode)) {
                boolean vacia = (p.location == null || p.location.trim().isEmpty());
                if (vacia || (force && !loc.equals(p.location))) {
                    p.location = loc;
                    changed = true;
                }
                break;
            }
        }
        if (changed) {
            savePallets(all);
            sincronizarUbicacionFirebase(palletCode, loc);
            Log.d(TAG, "Ubicación\u00A0rellenada\u00A0para\u00A0" + palletCode
                    + ":\u00A0" + loc);
        }
        return changed;
    }

    /**
     * v4.6: Completa la ubicación de un pallet SOLO si es de HOY y está vacía.
     * Se usa al emparejar ILP↔ubicación desde cualquier pantalla del WMS
     * (p.ej. "Progress" → "Location View"). Nunca pisa una ubicación existente.
     * @return true si se completó.
     */
    public synchronized boolean completarUbicacionSiHoy(String palletCode, String location) {
        if (palletCode == null || location == null || location.trim().isEmpty()) {
            return false;
        }
        String loc = location.trim();
        List<Pallet> all = getAllPallets();
        for (Pallet p : all) {
            if (palletCode.equals(p.palletCode)) {
                if (!esDeHoy(p.timestamp)) return false;
                if (p.location != null && !p.location.trim().isEmpty()) return false;
                p.location = loc;
                savePallets(all);
                sincronizarUbicacionFirebase(palletCode, loc);
                Log.d(TAG, "Ubicación\u00A0(hoy)\u00A0completada\u00A0para\u00A0" + palletCode
                        + ":\u00A0" + loc);
                return true;
            }
        }
        return false;
    }

    /** v4.6: Propaga el cambio de ubicación a Firebase si la sync está activa. */
    private void sincronizarUbicacionFirebase(String palletCode, String location) {
        try {
            FirebaseManager fm = FirebaseManager.getInstance(context);
            if (fm.isTeamActive()) {
                fm.updateTeamPalletLocation(palletCode, location);
            } else if (fm.isSyncEnabled()) {
                fm.updatePalletLocationRemote(palletCode, location);
            }
        } catch (Exception e) {
            Log.w(TAG, "No\u00A0se\u00A0pudo\u00A0sincronizar\u00A0ubicación", e);
        }
    }

    // ====================== CONSULTAS ======================

    public List<Pallet> getAllPallets() {
        List<Pallet> out = new ArrayList<>();
        String json = prefs.getString(KEY_PALLETS, "");
        if (TextUtils.isEmpty(json)) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Pallet.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public List<Pallet> getPalletsForTask(String taskId) {
        List<Pallet> out = new ArrayList<>();
        if (taskId == null) return out;
        // v3.2: coincidencia robusta. Los pallets guardados sin Task ID real
        // llevan taskId="" y en los resúmenes se etiquetan como "SIN\u00A0TASK".
        // Comparamos contra ambos para que el detalle nunca salga vacío.
        final String SIN = "SIN\u00A0TASK";
        for (Pallet p : getAllPallets()) {
            String pid = (p.taskId != null && !p.taskId.isEmpty()) ? p.taskId : SIN;
            if (taskId.equals(pid) || taskId.equals(p.taskId)) {
                out.add(p);
            }
        }
        return out;
    }

    public List<TaskSummary> getAllTaskSummaries() {
        List<Pallet> all = getAllPallets();
        Map<String, TaskSummary> map = new LinkedHashMap<>();

        for (Pallet p : all) {
            String tid = (p.taskId != null && !p.taskId.isEmpty()) ? p.taskId : "SIN\u00A0TASK";
            TaskSummary ts = map.get(tid);
            if (ts == null) {
                ts = new TaskSummary(tid, 0, 0L);
                map.put(tid, ts);
            }
            ts.palletCount++;
            if (p.timestamp > ts.lastTimestamp) {
                ts.lastTimestamp = p.timestamp;
            }
        }

        return new ArrayList<>(map.values());
    }

    /**
     * v2.3: Agrupa todos los tasks como si fuera un solo container.
     * Retorna UN solo TaskSummary con taskIds unidos por " / ".
     * Se usa cuando hay un containerCode configurado.
     */
    public List<TaskSummary> getContainerTaskSummaries() {
        List<Pallet> all = getAllPallets();
        if (all.isEmpty()) return new ArrayList<>();

        // Recolectar todos los taskIds únicos.
        Set<String> uniqueTasks = new LinkedHashSet<>();
        int totalPallets = 0;
        long lastTs = 0L;

        for (Pallet p : all) {
            String tid = (p.taskId != null && !p.taskId.isEmpty()) ? p.taskId : "SIN\u00A0TASK";
            uniqueTasks.add(tid);
            totalPallets++;
            if (p.timestamp > lastTs) lastTs = p.timestamp;
        }

        // Crear un solo TaskSummary con todos los tasks combinados.
        StringBuilder combined = new StringBuilder();
        boolean first = true;
        for (String tid : uniqueTasks) {
            if (!first) combined.append("\u00A0/\u00A0");
            combined.append(tid);
            first = false;
        }

        List<TaskSummary> result = new ArrayList<>();
        result.add(new TaskSummary(combined.toString(), totalPallets, lastTs));
        return result;
    }

    /**
     * v2.3: Retorna set de taskIds únicos de los pallets locales.
     */
    public Set<String> getUniqueTaskIds() {
        Set<String> tasks = new LinkedHashSet<>();
        for (Pallet p : getAllPallets()) {
            if (p.taskId != null && !p.taskId.isEmpty()) {
                tasks.add(p.taskId);
            }
        }
        return tasks;
    }

    // ====================== CONTAINERS (v4.0) ======================

    /**
     * v4.0: Lista de containers con su conteo de pallets, agrupados por el
     * código de container (campo taskId, que ahora SIEMPRE es "Container N").
     * Es el reemplazo simple de la antigua lista de tasks.
     */
    public List<TaskSummary> getContainerList() {
        List<Pallet> all = getAllPallets();
        Map<String, TaskSummary> map = new LinkedHashMap<>();
        for (Pallet p : all) {
            String cid = (p.taskId != null && !p.taskId.isEmpty())
                    ? p.taskId : "Container\u00A01";
            TaskSummary ts = map.get(cid);
            if (ts == null) {
                ts = new TaskSummary(cid, 0, 0L);
                map.put(cid, ts);
            }
            ts.palletCount++;
            if (p.timestamp > ts.lastTimestamp) {
                ts.lastTimestamp = p.timestamp;
            }
        }
        return new ArrayList<>(map.values());
    }

    /**
     * v4.0: Inicia un NUEVO container (Container N+1) SIN borrar los pallets
     * existentes. Los nuevos pallets escaneados se agruparán en el nuevo código.
     * @return el nuevo código de container actual.
     */
    public synchronized String startNewContainer() {
        String hoy = todayKey();
        String fechaGuardada = prefs.getString(KEY_CONTAINER_DATE, "");
        int seq;
        if (!hoy.equals(fechaGuardada)) {
            // Primer container del nuevo día → Container 1.
            seq = 1;
        } else {
            seq = prefs.getInt(KEY_CONTAINER_SEQ, 1) + 1;
        }
        prefs.edit()
                .putString(KEY_CONTAINER_DATE, hoy)
                .putInt(KEY_CONTAINER_SEQ, seq)
                .apply();
        return hoy + "#" + seq;
    }

    /**
     * v4.0: Migración única. Convierte cualquier pallet heredado que NO tenga
     * un código "Container N" (vacío, "SIN TASK", "TASK-xxxx", etc.) a
     * "Container 1", para que tras la actualización la app SOLO muestre
     * containers y desaparezcan las etiquetas de Task. Es idempotente:
     * si no hay datos heredados, no hace nada.
     */
    public synchronized void migrateLegacyToContainers() {
        List<Pallet> all = getAllPallets();
        if (all.isEmpty()) return;

        boolean changed = false;
        int maxNum = 0;
        for (Pallet p : all) {
            Integer n = parseContainerNumber(p.taskId);
            if (n != null) {
                if (n > maxNum) maxNum = n;
            } else {
                // Pallet heredado sin código de container válido.
                p.taskId = "Container\u00A01";
                changed = true;
            }
        }

        if (changed) {
            savePallets(all);
            // Asegurar que el puntero de container actual sea coherente.
            int pointer = Math.max(prefs.getInt(KEY_CONTAINER_NUM, 1), Math.max(maxNum, 1));
            prefs.edit().putInt(KEY_CONTAINER_NUM, pointer).apply();
        }
    }

    /**
     * v4.1: Limpia los SKU heredados que quedaron con un valor "TASK-xxxx"
     * (por el bug del regex de SKU). Deja el SKU vacío para que la UI muestre
     * "—" en lugar del Task. Idempotente.
     */
    public synchronized void migrateFixSkuTask() {
        List<Pallet> all = getAllPallets();
        if (all.isEmpty()) return;
        boolean changed = false;
        for (Pallet p : all) {
            if (p.sku != null) {
                String up = p.sku.toUpperCase(java.util.Locale.ROOT).trim();
                if (up.startsWith("TASK")) {
                    p.sku = "";
                    changed = true;
                }
            }
        }
        if (changed) savePallets(all);
    }

    /**
     * v4.4: Migración a NUMERACIÓN DIARIA. Convierte los ids de container
     * legados ("Container N", vacío, "SIN TASK", etc.) al formato interno con
     * fecha "yyyy-MM-dd#N", asignando a cada día su propia numeración 1..k en
     * orden de entrada (por timestamp). Así cada día empieza en "Container 1"
     * y los días no se mezclan. Idempotente: si todos los pallets ya tienen un
     * id con '#', no hace nada.
     */
    public synchronized void migrateContainersToDaily() {
        List<Pallet> all = getAllPallets();
        if (all.isEmpty()) return;

        boolean hayLegado = false;
        for (Pallet p : all) {
            if (p.taskId == null || p.taskId.indexOf('#') < 0) { hayLegado = true; break; }
        }
        if (!hayLegado) return;

        // Copia ordenada por timestamp ascendente (orden de entrada real).
        List<Pallet> ordenados = new ArrayList<>(all);
        Collections.sort(ordenados, (a, b) -> Long.compare(a.timestamp, b.timestamp));

        // Por fecha: mapa de código legado → nuevo id "fecha#N".
        Map<String, Map<String, String>> porFecha = new LinkedHashMap<>();
        Map<String, Integer> seqPorFecha = new LinkedHashMap<>();

        for (Pallet p : ordenados) {
            if (p.taskId != null && p.taskId.indexOf('#') >= 0) continue; // ya migrado
            String fecha = dateKeyOf(p.timestamp);
            String legado = (p.taskId != null && !p.taskId.isEmpty())
                    ? p.taskId : "Container\u00A01";
            Map<String, String> m = porFecha.get(fecha);
            if (m == null) { m = new LinkedHashMap<>(); porFecha.put(fecha, m); }
            String nuevo = m.get(legado);
            if (nuevo == null) {
                int next = seqPorFecha.containsKey(fecha) ? seqPorFecha.get(fecha) + 1 : 1;
                seqPorFecha.put(fecha, next);
                nuevo = fecha + "#" + next;
                m.put(legado, nuevo);
            }
            p.taskId = nuevo;
        }
        savePallets(all);

        // Ajustar el puntero del día de hoy para continuar la numeración.
        String hoy = todayKey();
        Integer seqHoy = seqPorFecha.get(hoy);
        if (seqHoy != null) {
            prefs.edit()
                    .putString(KEY_CONTAINER_DATE, hoy)
                    .putInt(KEY_CONTAINER_SEQ, seqHoy)
                    .apply();
        }
    }

    /**
     * v4.1: ¿el timestamp cae en el día local de hoy? Un registro viejo sin
     * timestamp (0) se considera que NO es de hoy.
     */
    private static boolean esDeHoy(long ts) {
        if (ts <= 0) return false;
        Calendar ahora = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return ahora.get(Calendar.YEAR) == c.get(Calendar.YEAR)
                && ahora.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * v4.1: Lista de containers con pallets escaneadas HOY (fecha local).
     * Los datos viejos se conservan pero no se muestran. Ordenada por número
     * de container ascendente (Container 1, 2, 3, ...).
     */
    public List<TaskSummary> getContainerListToday() {
        List<Pallet> all = getAllPallets();
        Map<String, TaskSummary> map = new LinkedHashMap<>();
        for (Pallet p : all) {
            if (!esDeHoy(p.timestamp)) continue;
            String cid = (p.taskId != null && !p.taskId.isEmpty())
                    ? p.taskId : "Container\u00A01";
            TaskSummary ts = map.get(cid);
            if (ts == null) {
                ts = new TaskSummary(cid, 0, 0L);
                map.put(cid, ts);
            }
            ts.palletCount++;
            if (p.timestamp > ts.lastTimestamp) {
                ts.lastTimestamp = p.timestamp;
            }
        }
        List<TaskSummary> list = new ArrayList<>(map.values());
        Collections.sort(list, (a, b) -> {
            Integer na = parseContainerNumber(a.taskId);
            Integer nb = parseContainerNumber(b.taskId);
            int va = na != null ? na : Integer.MAX_VALUE;
            int vb = nb != null ? nb : Integer.MAX_VALUE;
            return Integer.compare(va, vb);
        });
        return list;
    }

    /**
     * v4.1: Containers únicos (con pallets de HOY) para el diálogo de borrado.
     */
    public List<String> getUniqueContainersToday() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Pallet p : getAllPallets()) {
            if (!esDeHoy(p.timestamp)) continue;
            String key = (p.taskId != null && !p.taskId.isEmpty()) ? p.taskId : "Container\u00A01";
            if (seen.add(key)) out.add(key);
        }
        return out;
    }

    /**
     * v4.1: Número de pallets escaneadas HOY (fecha local).
     */
    public int getPalletCountToday() {
        int n = 0;
        for (Pallet p : getAllPallets()) {
            if (esDeHoy(p.timestamp)) n++;
        }
        return n;
    }

    /**
     * Extrae el número N de un id de container. Acepta los dos formatos:
     *  - "yyyy-MM-dd#N" (v4.4, id interno con fecha) → N
     *  - "Container N"  (legado) → N
     * Devuelve null si el texto no tiene ninguno de esos formatos.
     */
    private static Integer parseContainerNumber(String code) {
        if (code == null) return null;
        String c = code.replace('\u00A0', ' ').trim();
        int hash = c.indexOf('#');
        if (hash >= 0) {
            String num = c.substring(hash + 1).trim();
            try {
                return Integer.parseInt(num);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (!c.toLowerCase(java.util.Locale.ROOT).startsWith("container")) return null;
        String num = c.substring("container".length()).trim();
        if (num.isEmpty()) return null;
        try {
            return Integer.parseInt(num);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ====================== CONTAINERS (v3.4) ======================

    /**
     * v3.4: Devuelve la lista de containers/tasks únicos que tienen pallets
     * guardados. Usa la misma clave de agrupación que getAllTaskSummaries():
     * el taskId real o la etiqueta "SIN\u00A0TASK" para los que no tienen.
     * Sirve para el diálogo de borrado de containers.
     */
    public List<String> getUniqueContainers() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Pallet p : getAllPallets()) {
            String key = (p.taskId != null && !p.taskId.isEmpty()) ? p.taskId : "SIN\u00A0TASK";
            if (seen.add(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /**
     * v3.4: Borra todos los pallets que pertenecen al container indicado.
     * La coincidencia es robusta: compara contra el taskId real y contra la
     * etiqueta "SIN\u00A0TASK" (pallets guardados sin Task ID).
     * @return número de pallets eliminados.
     */
    public synchronized int deletePalletsByContainer(String containerCode) {
        if (containerCode == null) return 0;
        final String SIN = "SIN\u00A0TASK";
        List<Pallet> all = getAllPallets();
        List<Pallet> keep = new ArrayList<>();
        int removed = 0;
        for (Pallet p : all) {
            String key = (p.taskId != null && !p.taskId.isEmpty()) ? p.taskId : SIN;
            if (containerCode.equals(key) || containerCode.equals(p.taskId)) {
                removed++;
            } else {
                keep.add(p);
            }
        }
        if (removed > 0) {
            savePallets(keep);
            // Mantener el contador acumulado coherente con lo que queda.
            prefs.edit().putInt(KEY_COUNTER, keep.size()).apply();
        }
        return removed;
    }

    /**
     * v5.0: Borra UNA pallet por su código (ILP). Mantiene el contador
     * acumulado coherente con lo que queda.
     * @return true si se eliminó alguna pallet.
     */
    public synchronized boolean deletePallet(String palletCode) {
        if (palletCode == null || palletCode.isEmpty()) return false;
        List<Pallet> all = getAllPallets();
        List<Pallet> keep = new ArrayList<>();
        int removed = 0;
        for (Pallet p : all) {
            if (palletCode.equals(p.palletCode)) {
                removed++;
            } else {
                keep.add(p);
            }
        }
        if (removed > 0) {
            savePallets(keep);
            prefs.edit().putInt(KEY_COUNTER, keep.size()).apply();
            return true;
        }
        return false;
    }

    // ====================== CONTADOR ======================

    public int getCounter() {
        return prefs.getInt(KEY_COUNTER, 0);
    }

    public int getPalletCount() {
        return getAllPallets().size();
    }

    /**
     * v4.2: Número de pallets escaneadas en el CONTAINER ACTUAL por ESTE
     * dispositivo (conteo local). Es el valor por defecto del botón flotante.
     * Al iniciar un nuevo container con startNewContainer() este conteo vuelve
     * a 0 porque el código de container cambia.
     */
    public int getPalletCountInCurrentContainer() {
        String code = getCurrentContainerCode();
        int n = 0;
        for (Pallet p : getAllPallets()) {
            String cid = (p.taskId != null && !p.taskId.isEmpty())
                    ? p.taskId : "Container\u00A01";
            if (code.equals(cid)) n++;
        }
        return n;
    }

    // ====================== CONTAINER ======================

    /**
     * v4.4: Id INTERNO del container activo, con fecha: "yyyy-MM-dd#N".
     * La fecha evita que los containers de días distintos se mezclen; el N
     * es el número de container de HOY (empieza en 1 cada día).
     *
     * Si la fecha guardada no es la de hoy (primer uso del día), se reinicia
     * automáticamente a "Container 1": N vuelve a 1 y el conteo del botón
     * flotante empieza en 0 (porque el código cambia y no hay pallets de hoy
     * con ese código todavía).
     *
     * Para mostrar en pantalla usar displayContainer(code) → "Container N".
     */
    public synchronized String getCurrentContainerCode() {
        String hoy = todayKey();
        String fechaGuardada = prefs.getString(KEY_CONTAINER_DATE, "");
        int seq;
        if (!hoy.equals(fechaGuardada)) {
            // Cambió el día → reiniciar numeración a 1.
            seq = 1;
            prefs.edit()
                    .putString(KEY_CONTAINER_DATE, hoy)
                    .putInt(KEY_CONTAINER_SEQ, seq)
                    .apply();
        } else {
            seq = prefs.getInt(KEY_CONTAINER_SEQ, 1);
            if (seq < 1) seq = 1;
        }
        return hoy + "#" + seq;
    }

    /**
     * v5.2: SINCRONIZA el container local con el container COMPARTIDO del equipo.
     *
     * Causa raíz del bug "al abrir un nuevo container sigue metiendo en el
     * antiguo": el container que se asigna a cada pallet escaneado se tomaba de
     * KEY_CONTAINER_SEQ/KEY_CONTAINER_DATE (puntero LOCAL), que nunca se
     * actualizaba cuando el equipo avanzaba de container en Firebase. Resultado:
     * el FAB (que lee de Firebase) mostraba 0, pero los pallets nuevos se
     * guardaban bajo el código del container viejo y la pantalla principal los
     * seguía metiendo ahí.
     *
     * Con equipo activo, la ÚNICA fuente de verdad es `contenedorActual` de
     * Firebase. El FirebaseManager llama a este método cada vez que el listener
     * recibe el container compartido (fecha + número), de modo que el puntero
     * local queda idéntico al compartido y getCurrentContainerCode() devuelve
     * exactamente el id del equipo ("yyyy-MM-dd#N", mismo formato en ambos
     * lados). El número NUNCA retrocede: solo lo movemos al valor compartido,
     * que únicamente sube dentro del día (y se reinicia a 1 al cambiar de día,
     * por la transacción del equipo).
     */
    public synchronized void syncSharedContainer(String fecha, int numero) {
        if (fecha == null || fecha.trim().isEmpty() || numero < 1) return;
        String f = fecha.trim();
        String fechaGuardada = prefs.getString(KEY_CONTAINER_DATE, "");
        int seqGuardado = prefs.getInt(KEY_CONTAINER_SEQ, 0);
        if (f.equals(fechaGuardada) && seqGuardado == numero) {
            return; // ya está sincronizado, evitar escrituras redundantes
        }
        prefs.edit()
                .putString(KEY_CONTAINER_DATE, f)
                .putInt(KEY_CONTAINER_SEQ, numero)
                .apply();
    }

    /**
     * v4.4: Número del container actual DENTRO de hoy (para "Vas en el
     * Container X"). Empieza en 1 cada día.
     */
    public int getCurrentContainerNumber() {
        Integer n = parseContainerNumber(getCurrentContainerCode());
        return n != null ? n : 1;
    }

    /**
     * v4.4: Convierte un id interno de container a su texto visible.
     *  - "yyyy-MM-dd#N"  → "Container N"
     *  - "Container N"    → "Container N" (datos legado ya migrados)
     *  - vacío/otro       → "Container 1"
     */
    public static String displayContainer(String code) {
        Integer n = parseContainerNumber(code);
        if (n != null) return "Container\u00A0" + n;
        if (code == null || code.trim().isEmpty()) return "Container\u00A01";
        return code;
    }

    /** Fecha local de hoy como clave yyyy-MM-dd. */
    private static String todayKey() {
        return dateKeyOf(System.currentTimeMillis());
    }

    /** Fecha local de un timestamp como clave yyyy-MM-dd. */
    private static String dateKeyOf(long ts) {
        if (ts <= 0) return "1970-01-01";
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return fmt.format(new java.util.Date(ts));
    }

    // ====================== RESET ======================

    public void clearAll() {
        // v4.4: al cerrar el turno/container, avanzamos al siguiente número
        // del día (o 1 si cambió el día) para que los nuevos pallets se
        // agrupen en el siguiente container.
        String hoy = todayKey();
        String fechaGuardada = prefs.getString(KEY_CONTAINER_DATE, "");
        int seq = hoy.equals(fechaGuardada)
                ? prefs.getInt(KEY_CONTAINER_SEQ, 1) + 1 : 1;
        prefs.edit()
                .remove(KEY_PALLETS)
                .putInt(KEY_COUNTER, 0)
                .putString(KEY_CONTAINER_DATE, hoy)
                .putInt(KEY_CONTAINER_SEQ, seq)
                .apply();
    }

    // ====================== INTERNO ======================

    private void savePallets(List<Pallet> pallets) {
        JSONArray arr = new JSONArray();
        for (Pallet p : pallets) {
            arr.put(p.toJson());
        }
        prefs.edit().putString(KEY_PALLETS, arr.toString()).apply();
    }
}
