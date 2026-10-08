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
    private static final String KEY_CONTAINER_NUM = "container_number";
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
            if (fm.isSyncEnabled()) {
                fm.reportPalletScanned(pallet);
                Log.d(TAG, "Pallet\u00A0sincronizado\u00A0a\u00A0Firebase:\u00A0" + pallet.palletCode);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error\u00A0sincronizando\u00A0pallet", e);
        }

        return true;
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
        int next = prefs.getInt(KEY_CONTAINER_NUM, 1) + 1;
        prefs.edit().putInt(KEY_CONTAINER_NUM, next).apply();
        return getCurrentContainerCode();
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
     * Extrae el número N de un código "Container N". Devuelve null si el texto
     * no tiene ese formato.
     */
    private Integer parseContainerNumber(String code) {
        if (code == null) return null;
        String c = code.replace('\u00A0', ' ').trim();
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

    // ====================== CONTADOR ======================

    public int getCounter() {
        return prefs.getInt(KEY_COUNTER, 0);
    }

    public int getPalletCount() {
        return getAllPallets().size();
    }

    // ====================== CONTAINER ======================

    /**
     * v3.2: Código de container secuencial actual (ej. "Container 1").
     * La pantalla de Put away del WMS no muestra Task ID, así que usamos
     * este código simple como identificador de agrupación de los pallets.
     */
    public String getCurrentContainerCode() {
        return "Container\u00A0" + prefs.getInt(KEY_CONTAINER_NUM, 1);
    }

    // ====================== RESET ======================

    public void clearAll() {
        // v3.2: al cerrar el turno/container, avanzamos al siguiente número
        // para que los nuevos pallets se agrupen en "Container N+1".
        int nextContainer = prefs.getInt(KEY_CONTAINER_NUM, 1) + 1;
        prefs.edit()
                .remove(KEY_PALLETS)
                .putInt(KEY_COUNTER, 0)
                .putInt(KEY_CONTAINER_NUM, nextContainer)
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
