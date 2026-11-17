package com.demoaccesibilidad.capture;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PickTrackerStore — Datos locales del modo Picking (v5.18, reescrito).
 *
 * Solo SharedPreferences propio ("picktracker_prefs"). NO usa Firebase: el
 * plan de picking es personal de cada escáner, no se sincroniza con el equipo.
 *
 * Qué guarda (y persiste al cerrar/reabrir el overlay o la app, hasta pulsar
 * "Nueva lectura"):
 *   1. Tareas pendientes leídas de la "Pick Task List" (id, envío, zona,
 *      número de artículos = conteo de DN, ubicaciones vistas en la tarjeta).
 *   2. Las ubicaciones capturadas en el DETALLE de cada tarea por el detector
 *      automático (startTask + noteLocation), fusionadas por tarea.
 *   3. Qué órdenes marcó el usuario como completadas (checkbox por orden).
 *
 * Al leer (getPendingTasks) se fusionan las ubicaciones de la tarjeta con las
 * capturadas en el detalle, se eliminan duplicados y se entrega un
 * {@link PickTrackerAdvisor.TaskInfo} por tarea (articles = conteo de DN).
 *
 * Fusión segura: guardar tarjetas nunca borra datos previos; una tarjeta que
 * sale cortada al final de la lista solo actualiza los campos que sí trae.
 */
public final class PickTrackerStore {

    public static final String PREFS = "picktracker_prefs";

    private static final String KEY_PENDING = "pt_pending_tasks";
    private static final String KEY_TASK_LOCS = "pt_task_locs";
    private static final String KEY_DONE = "pt_done_orders";
    private static final String KEY_CURRENT_TASK = "pt_current_task";
    private static final String KEY_READING_TS = "pt_reading_ts";

    private static final String FIELD_SEP = "\u0001";
    private static final String TASK_SEP = "\n";

    private PickTrackerStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ───────────────────────── Detector automático ─────────────────────────

    public static void startTask(Context c, String taskId) {
        if (c == null || taskId == null || taskId.trim().isEmpty()) return;
        try {
            prefs(c).edit().putString(KEY_CURRENT_TASK, taskId.trim()).apply();
        } catch (Throwable ignored) { }
    }

    public static String getCurrentTask(Context c) {
        try { return prefs(c).getString(KEY_CURRENT_TASK, ""); }
        catch (Throwable t) { return ""; }
    }

    public static void endTask(Context c) {
        if (c == null) return;
        try { prefs(c).edit().putString(KEY_CURRENT_TASK, "").apply(); }
        catch (Throwable ignored) { }
    }

    /**
     * Anota una ubicación vista en el DETALLE de la tarea en curso. La fusiona
     * con las ubicaciones ya capturadas de esa tarea (sin duplicar). Devuelve
     * true si era nueva.
     */
    public static boolean noteLocation(Context c, String location) {
        if (c == null || location == null || location.trim().isEmpty()) return false;
        try {
            String task = getCurrentTask(c);
            if (task == null || task.isEmpty()) return false;
            String loc = location.trim().toUpperCase(Locale.ROOT);
            Map<String, List<String>> map = readTaskLocs(c);
            List<String> list = map.get(task);
            if (list == null) { list = new ArrayList<>(); map.put(task, list); }
            if (list.contains(loc)) return false;
            list.add(loc);
            writeTaskLocs(c, map);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** Progreso del detalle: no se usa en el plan, se conserva como no-op estable. */
    public static void updateProgress(Context c, int skusDone, int skusTotal,
                                      int eaDone, int eaTotal) {
        // El plan de pallets no depende del progreso de una tarea en curso.
        // Se mantiene la firma porque ScreenCaptureService la llama.
    }

    // ───────────────────────── Lista de Pick Task ─────────────────────────

    /**
     * Guarda/fusiona tarjetas de la "Pick Task List". Para cada tarjeta se
     * actualizan solo los campos que trae; nunca se borra lo ya guardado.
     * Guardar la primera tarjeta de una lectura nueva NO limpia lo anterior:
     * eso lo hace {@link #clearReading(Context)} ("Nueva lectura").
     */
    public static void savePendingCards(Context c, List<PickTrackerParser.ListCard> cards) {
        if (c == null || cards == null || cards.isEmpty()) return;
        try {
            Map<String, String[]> byId = readPending(c); // id -> {shipment, zone, dnCount, cardLocsCSV}
            for (PickTrackerParser.ListCard card : cards) {
                if (card == null || card.id == null || card.id.isEmpty()) continue;
                String[] old = byId.get(card.id);
                String shipment = old != null ? old[0] : "";
                String zone = old != null ? old[1] : "";
                String dn = old != null ? old[2] : "0";
                String locs = old != null ? old[3] : "";
                if (card.shipment != null && !card.shipment.isEmpty()) shipment = card.shipment;
                if (card.zone != null && !card.zone.isEmpty()) zone = card.zone;
                if (card.hasDnCount) dn = String.valueOf(card.dnCount);
                if (card.hasLocations && !card.locations.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < card.locations.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(card.locations.get(i));
                    }
                    locs = sb.toString();
                }
                if ((zone == null || zone.isEmpty() || "?".equals(zone)) && !locs.isEmpty()) {
                    zone = PickTrackerAdvisor.zoneFromLocation(locs.split(",")[0]);
                }
                byId.put(card.id, new String[]{ shipment, zone, dn, locs });
            }
            writePending(c, byId);
            if (prefs(c).getLong(KEY_READING_TS, 0L) <= 0L) {
                prefs(c).edit().putLong(KEY_READING_TS, System.currentTimeMillis()).apply();
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Tareas pendientes, en el orden en que se vieron. articles = conteo DN;
     * locations = ubicaciones de la tarjeta fusionadas con las capturadas en
     * el detalle (sin duplicados).
     */
    public static List<PickTrackerAdvisor.TaskInfo> getPendingTasks(Context c) {
        List<PickTrackerAdvisor.TaskInfo> out = new ArrayList<>();
        try {
            Map<String, String[]> byId = readPending(c);
            Map<String, List<String>> detail = readTaskLocs(c);
            for (Map.Entry<String, String[]> e : byId.entrySet()) {
                String id = e.getKey();
                String[] f = e.getValue();
                int articles = 0;
                try { articles = Integer.parseInt(f[2]); } catch (Throwable ignored) { }
                List<String> locs = new ArrayList<>();
                if (f[3] != null && !f[3].isEmpty()) {
                    for (String l : f[3].split(",")) {
                        String v = l.trim().toUpperCase(Locale.ROOT);
                        if (!v.isEmpty() && !locs.contains(v)) locs.add(v);
                    }
                }
                List<String> det = detail.get(id);
                if (det != null) {
                    for (String l : det) {
                        String v = l.trim().toUpperCase(Locale.ROOT);
                        if (!v.isEmpty() && !locs.contains(v)) locs.add(v);
                    }
                }
                String zone = f[1];
                if ((zone == null || zone.isEmpty() || "?".equals(zone)) && !locs.isEmpty()) {
                    zone = PickTrackerAdvisor.zoneFromLocation(locs.get(0));
                }
                out.add(new PickTrackerAdvisor.TaskInfo(id, f[0], zone, articles, locs));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static boolean hasReading(Context c) {
        try { return !readPending(c).isEmpty(); }
        catch (Throwable t) { return false; }
    }

    /** "Nueva lectura": borra las tareas, las ubicaciones de detalle y los checks. */
    public static void clearReading(Context c) {
        if (c == null) return;
        try {
            prefs(c).edit()
                    .remove(KEY_PENDING)
                    .remove(KEY_TASK_LOCS)
                    .remove(KEY_DONE)
                    .remove(KEY_CURRENT_TASK)
                    .remove(KEY_READING_TS)
                    .apply();
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── Checkboxes por orden ─────────────────────────

    public static boolean isOrderDone(Context c, String taskId) {
        if (c == null || taskId == null) return false;
        try {
            String s = prefs(c).getString(KEY_DONE, "");
            if (s == null || s.isEmpty()) return false;
            for (String id : s.split(",")) {
                if (taskId.equals(id)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    public static void setOrderDone(Context c, String taskId, boolean done) {
        if (c == null || taskId == null || taskId.isEmpty()) return;
        try {
            List<String> ids = new ArrayList<>();
            String s = prefs(c).getString(KEY_DONE, "");
            if (s != null && !s.isEmpty()) {
                for (String id : s.split(",")) {
                    if (!id.isEmpty() && !id.equals(taskId)) ids.add(id);
                }
            }
            if (done) ids.add(taskId);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(ids.get(i));
            }
            prefs(c).edit().putString(KEY_DONE, sb.toString()).apply();
        } catch (Throwable ignored) { }
    }

    public static int getDoneCount(Context c) {
        try {
            String s = prefs(c).getString(KEY_DONE, "");
            if (s == null || s.isEmpty()) return 0;
            int n = 0;
            for (String id : s.split(",")) if (!id.isEmpty()) n++;
            return n;
        } catch (Throwable t) { return 0; }
    }

    // ───────────────────────── Serialización interna ─────────────────────────

    /** id -> {shipment, zone, dnCount, cardLocsCSV}, en orden de inserción. */
    private static Map<String, String[]> readPending(Context c) {
        Map<String, String[]> map = new LinkedHashMap<>();
        try {
            String saved = prefs(c).getString(KEY_PENDING, "");
            if (saved == null || saved.isEmpty()) return map;
            for (String line : saved.split(TASK_SEP)) {
                String[] f = line.split(FIELD_SEP, -1);
                if (f.length < 5 || f[0].isEmpty()) continue;
                map.put(f[0], new String[]{ f[1], f[2], f[3], f[4] });
            }
        } catch (Throwable ignored) { }
        return map;
    }

    private static void writePending(Context c, Map<String, String[]> map) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String[]> e : map.entrySet()) {
            String[] f = e.getValue();
            if (out.length() > 0) out.append(TASK_SEP);
            out.append(e.getKey()).append(FIELD_SEP)
                    .append(f[0]).append(FIELD_SEP)
                    .append(f[1]).append(FIELD_SEP)
                    .append(f[2]).append(FIELD_SEP)
                    .append(f[3]);
        }
        prefs(c).edit().putString(KEY_PENDING, out.toString()).apply();
    }

    private static Map<String, List<String>> readTaskLocs(Context c) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        try {
            String saved = prefs(c).getString(KEY_TASK_LOCS, "");
            if (saved == null || saved.isEmpty()) return map;
            for (String line : saved.split(TASK_SEP)) {
                String[] f = line.split(FIELD_SEP, -1);
                if (f.length < 2 || f[0].isEmpty()) continue;
                List<String> locs = new ArrayList<>();
                if (!f[1].isEmpty()) {
                    for (String l : f[1].split(",")) {
                        if (!l.trim().isEmpty()) locs.add(l.trim());
                    }
                }
                map.put(f[0], locs);
            }
        } catch (Throwable ignored) { }
        return map;
    }

    private static void writeTaskLocs(Context c, Map<String, List<String>> map) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<String>> e : map.entrySet()) {
            if (out.length() > 0) out.append(TASK_SEP);
            StringBuilder locs = new StringBuilder();
            List<String> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) locs.append(",");
                locs.append(list.get(i));
            }
            out.append(e.getKey()).append(FIELD_SEP).append(locs);
        }
        prefs(c).edit().putString(KEY_TASK_LOCS, out.toString()).apply();
    }
}
