package com.demoaccesibilidad.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
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
        for (Pallet p : getAllPallets()) {
            if (taskId.equals(p.taskId)) {
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

    // ====================== CONTADOR ======================

    public int getCounter() {
        return prefs.getInt(KEY_COUNTER, 0);
    }

    public int getPalletCount() {
        return getAllPallets().size();
    }

    // ====================== RESET ======================

    public void clearAll() {
        prefs.edit()
                .remove(KEY_PALLETS)
                .putInt(KEY_COUNTER, 0)
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
