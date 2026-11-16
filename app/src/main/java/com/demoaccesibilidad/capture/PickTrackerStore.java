package com.demoaccesibilidad.capture;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PickTrackerStore — Datos locales de PickTracker (v5.15, nuevo en este cambio).
 *
 * Solo SharedPreferences propio ("picktracker_prefs"). NO usa Firebase:
 * los tiempos y conteos son personales, no se sincronizan entre escáneres.
 * Sigue el mismo patrón que AppMode y CopyTextHelper (prefs por nombre).
 *
 * Referencia personal de Wilson: 183 segundos por ubicación (3:03),
 * medida el 16-nov-2026. Cajas por pallet por defecto: 48 (1 pieza = 1 caja).
 *
 * v2 (16-nov-2026): además de los contadores del día, guarda las tareas
 * pendientes leídas de la lista de Pick Task (id, envío, zona, ubicaciones,
 * pedidos DN) y el detalle de la tarea en curso (ubicaciones distintas,
 * progreso "A / B Skus, C / D EA Picked"). Al guardar tarjetas de la lista
 * se FUSIONAN con las ya guardadas: solo se actualizan los campos que sí
 * se vieron en esta lectura (una tarjeta puede salir cortada al final de
 * la lista), nunca se borra lo ya guardado con un parseo incompleto.
 *
 * Conteo del día sin doble conteo: los botones manuales suman piezas y
 * ubicaciones al día en el acto (addLocation); el detector automático solo
 * anota la tarea en curso (noteLocation/updateProgress) y suma al día al
 * cerrar la tarea (endTask). Las claves pt_current_day_pieces /
 * pt_current_day_locs recuerdan cuánto de la tarea en curso ya se sumó al
 * día, para no contarlo dos veces si se mezclan ambos caminos.
 */
public final class PickTrackerStore {

    public static final String PREFS = "picktracker_prefs";
    public static final int BASELINE_SEC_PER_LOC = 183;
    public static final int DEFAULT_BOXES_PER_PALLET = 48;

    private static final String KEY_DAY = "pt_day";
    private static final String KEY_TASKS_TODAY = "pt_tasks_today";
    private static final String KEY_PIECES_TODAY = "pt_pieces_today";
    private static final String KEY_LOCS_TODAY = "pt_locs_today";
    private static final String KEY_TIME_TODAY_MS = "pt_time_today_ms";
    private static final String KEY_LAST_SEC_PER_LOC = "pt_last_sec_per_loc";
    private static final String KEY_BOXES_PER_PALLET = "pt_boxes_per_pallet";
    private static final String KEY_CURRENT_TASK = "pt_current_task";
    private static final String KEY_CURRENT_START = "pt_current_start_ms";
    private static final String KEY_CURRENT_LOCS = "pt_current_locs";
    private static final String KEY_CURRENT_PIECES = "pt_current_pieces";
    private static final String KEY_CURRENT_DAY_PIECES = "pt_current_day_pieces";
    private static final String KEY_CURRENT_DAY_LOCS = "pt_current_day_locs";
    private static final String KEY_CURRENT_LOCS_LIST = "pt_current_locs_list";
    private static final String KEY_CURRENT_SKUS_DONE = "pt_current_skus_done";
    private static final String KEY_CURRENT_SKUS_TOTAL = "pt_current_skus_total";
    private static final String KEY_CURRENT_EA_DONE = "pt_current_ea_done";
    private static final String KEY_CURRENT_EA_TOTAL = "pt_current_ea_total";
    private static final String KEY_PENDING = "pt_pending_tasks";

    private PickTrackerStore() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    }

    /** Si cambió el día, reinicia los contadores del día (sin tocar la tarea en curso). */
    private static void ensureDay(Context c) {
        try {
            SharedPreferences p = prefs(c);
            String day = p.getString(KEY_DAY, "");
            if (!today().equals(day)) {
                p.edit().putString(KEY_DAY, today())
                        .putInt(KEY_TASKS_TODAY, 0)
                        .putInt(KEY_PIECES_TODAY, 0)
                        .putInt(KEY_LOCS_TODAY, 0)
                        .putLong(KEY_TIME_TODAY_MS, 0L)
                        .apply();
            }
        } catch (Throwable ignored) { }
    }

    public static void startTask(Context c, String taskId) {
        if (c == null || taskId == null || taskId.trim().isEmpty()) return;
        try {
            ensureDay(c);
            SharedPreferences p = prefs(c);
            String current = p.getString(KEY_CURRENT_TASK, "");
            if (taskId.equals(current)) return;
            p.edit().putString(KEY_CURRENT_TASK, taskId.trim())
                    .putLong(KEY_CURRENT_START, System.currentTimeMillis())
                    .putInt(KEY_CURRENT_LOCS, 0)
                    .putInt(KEY_CURRENT_PIECES, 0)
                    .putInt(KEY_CURRENT_DAY_PIECES, 0)
                    .putInt(KEY_CURRENT_DAY_LOCS, 0)
                    .putString(KEY_CURRENT_LOCS_LIST, "")
                    .putInt(KEY_CURRENT_SKUS_DONE, 0)
                    .putInt(KEY_CURRENT_SKUS_TOTAL, 0)
                    .putInt(KEY_CURRENT_EA_DONE, 0)
                    .putInt(KEY_CURRENT_EA_TOTAL, 0)
                    .apply();
        } catch (Throwable ignored) { }
    }

    public static String getCurrentTask(Context c) {
        try { return prefs(c).getString(KEY_CURRENT_TASK, ""); }
        catch (Throwable t) { return ""; }
    }

    /** Segundos desde que empezó la tarea en curso (0 si no hay). */
    public static int getCurrentElapsedSec(Context c) {
        try {
            long start = prefs(c).getLong(KEY_CURRENT_START, 0L);
            if (start <= 0) return 0;
            return (int) ((System.currentTimeMillis() - start) / 1000L);
        } catch (Throwable t) { return 0; }
    }

    /** Ubicaciones distintas anotadas en la tarea en curso (en orden). */
    public static List<String> getCurrentLocsList(Context c) {
        List<String> out = new ArrayList<>();
        try {
            String s = prefs(c).getString(KEY_CURRENT_LOCS_LIST, "");
            if (s == null || s.isEmpty()) return out;
            for (String p : s.split(",")) {
                if (!p.trim().isEmpty()) out.add(p.trim());
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /**
     * Anota una ubicación de la tarea en curso (detector automático).
     * Solo la primera vez que se ve esa ubicación: no suma al día aquí,
     * eso lo hace endTask. Devuelve true si era nueva.
     */
    public static boolean noteLocation(Context c, String location) {
        if (c == null || location == null || location.trim().isEmpty()) return false;
        try {
            ensureDay(c);
            String loc = location.trim().toUpperCase(Locale.ROOT);
            List<String> list = getCurrentLocsList(c);
            if (list.contains(loc)) return false;
            list.add(loc);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(list.get(i));
            }
            SharedPreferences p = prefs(c);
            p.edit().putString(KEY_CURRENT_LOCS_LIST, sb.toString())
                    .putInt(KEY_CURRENT_LOCS, list.size())
                    .apply();
            return true;
        } catch (Throwable ignored) { return false; }
    }

    /** Guarda el progreso del detalle ("A / B Skus, C / D EA Picked"). */
    public static void updateProgress(Context c, int skusDone, int skusTotal,
                                      int eaDone, int eaTotal) {
        if (c == null) return;
        try {
            prefs(c).edit()
                    .putInt(KEY_CURRENT_SKUS_DONE, Math.max(0, skusDone))
                    .putInt(KEY_CURRENT_SKUS_TOTAL, Math.max(0, skusTotal))
                    .putInt(KEY_CURRENT_EA_DONE, Math.max(0, eaDone))
                    .putInt(KEY_CURRENT_EA_TOTAL, Math.max(0, eaTotal))
                    .apply();
        } catch (Throwable ignored) { }
    }

    public static int getCurrentSkusDone(Context c) {
        try { return prefs(c).getInt(KEY_CURRENT_SKUS_DONE, 0); }
        catch (Throwable t) { return 0; }
    }

    public static int getCurrentSkusTotal(Context c) {
        try { return prefs(c).getInt(KEY_CURRENT_SKUS_TOTAL, 0); }
        catch (Throwable t) { return 0; }
    }

    public static int getCurrentEaDone(Context c) {
        try { return prefs(c).getInt(KEY_CURRENT_EA_DONE, 0); }
        catch (Throwable t) { return 0; }
    }

    public static int getCurrentEaTotal(Context c) {
        try { return prefs(c).getInt(KEY_CURRENT_EA_TOTAL, 0); }
        catch (Throwable t) { return 0; }
    }

    /** Suma una ubicación hecha (y sus piezas) a la tarea en curso y al día (botón manual). */
    public static void addLocation(Context c, int pieces) {
        if (c == null) return;
        try {
            ensureDay(c);
            SharedPreferences p = prefs(c);
            int locs = p.getInt(KEY_CURRENT_LOCS, 0) + 1;
            int pcs = p.getInt(KEY_CURRENT_PIECES, 0) + Math.max(0, pieces);
            p.edit().putInt(KEY_CURRENT_LOCS, locs)
                    .putInt(KEY_CURRENT_PIECES, pcs)
                    .putInt(KEY_CURRENT_DAY_PIECES, pcs)
                    .putInt(KEY_CURRENT_DAY_LOCS, locs)
                    .putInt(KEY_LOCS_TODAY, p.getInt(KEY_LOCS_TODAY, 0) + 1)
                    .putInt(KEY_PIECES_TODAY, p.getInt(KEY_PIECES_TODAY, 0) + Math.max(0, pieces))
                    .apply();
        } catch (Throwable ignored) { }
    }

    /**
     * Cierra la tarea en curso y la suma a las estadísticas del día.
     * Solo suma al día lo que aún no se sumó (piezas y ubicaciones que el
     * detector anotó pero no contó, o que el botón manual no contó).
     */
    public static void endTask(Context c) {
        if (c == null) return;
        try {
            ensureDay(c);
            SharedPreferences p = prefs(c);
            String task = p.getString(KEY_CURRENT_TASK, "");
            if (task == null || task.isEmpty()) return;
            long start = p.getLong(KEY_CURRENT_START, 0L);
            long elapsed = start > 0 ? System.currentTimeMillis() - start : 0L;
            int locs = p.getInt(KEY_CURRENT_LOCS, 0);
            int pcs = p.getInt(KEY_CURRENT_PIECES, 0);
            if (pcs <= 0) pcs = p.getInt(KEY_CURRENT_EA_TOTAL, 0);
            int dayPcs = p.getInt(KEY_CURRENT_DAY_PIECES, 0);
            int dayLocs = p.getInt(KEY_CURRENT_DAY_LOCS, 0);
            int addPcs = Math.max(0, pcs - dayPcs);
            int addLocs = Math.max(0, locs - dayLocs);
            if (locs > 0 && elapsed > 0) {
                int secPerLoc = (int) (elapsed / 1000L / locs);
                p.edit().putInt(KEY_LAST_SEC_PER_LOC, secPerLoc).apply();
            }
            p.edit().putInt(KEY_TASKS_TODAY, p.getInt(KEY_TASKS_TODAY, 0) + 1)
                    .putLong(KEY_TIME_TODAY_MS, p.getLong(KEY_TIME_TODAY_MS, 0L) + elapsed)
                    .putInt(KEY_PIECES_TODAY, p.getInt(KEY_PIECES_TODAY, 0) + addPcs)
                    .putInt(KEY_LOCS_TODAY, p.getInt(KEY_LOCS_TODAY, 0) + addLocs)
                    .putString(KEY_CURRENT_TASK, "")
                    .putLong(KEY_CURRENT_START, 0L)
                    .putInt(KEY_CURRENT_LOCS, 0)
                    .putInt(KEY_CURRENT_PIECES, 0)
                    .putInt(KEY_CURRENT_DAY_PIECES, 0)
                    .putInt(KEY_CURRENT_DAY_LOCS, 0)
                    .putString(KEY_CURRENT_LOCS_LIST, "")
                    .putInt(KEY_CURRENT_SKUS_DONE, 0)
                    .putInt(KEY_CURRENT_SKUS_TOTAL, 0)
                    .putInt(KEY_CURRENT_EA_DONE, 0)
                    .putInt(KEY_CURRENT_EA_TOTAL, 0)
                    .apply();
        } catch (Throwable ignored) { }
    }

    // ── Tareas pendientes de la lista ──

    private static final String FIELD_SEP = "\u0001";
    private static final String TASK_SEP = "\n";

    /**
     * Guarda tarjetas de la lista fusionándolas con las ya guardadas:
     * solo se actualizan los campos que sí se vieron (envío, zona, lista
     * de ubicaciones, conteo DN). Una tarjeta cortada nunca borra datos.
     * Nota: la lista de ubicaciones completa no cabe en TaskInfo (solo su
     * conteo y la zona); se guarda aparte, codificada tras el conteo DN.
     */
    public static void savePendingCards(Context c, List<PickTrackerParser.ListCard> cards) {
        if (c == null || cards == null || cards.isEmpty()) return;
        try {
            Map<String, String> locsById = new LinkedHashMap<>();
            Map<String, PickTrackerAdvisor.TaskInfo> byId = new LinkedHashMap<>();
            for (PickTrackerAdvisor.TaskInfo t : getPendingTasks(c)) {
                byId.put(t.id, t);
            }
            for (PickTrackerAdvisor.TaskInfo t : byId.values()) {
                locsById.put(t.id, "");
            }
            // Recuperar las listas de ubicaciones guardadas.
            String saved = prefs(c).getString(KEY_PENDING, "");
            if (saved != null && !saved.isEmpty()) {
                for (String line : saved.split(TASK_SEP)) {
                    String[] f = line.split(FIELD_SEP, -1);
                    if (f.length >= 6 && byId.containsKey(f[0])) {
                        locsById.put(f[0], f[5]);
                    }
                }
            }
            for (PickTrackerParser.ListCard card : cards) {
                if (card == null || card.id == null || card.id.isEmpty()) continue;
                PickTrackerAdvisor.TaskInfo old = byId.get(card.id);
                String shipment = (old == null) ? "" : old.shipment;
                String zone = (old == null) ? "" : old.zone;
                int locations = (old == null) ? 0 : old.locations;
                int dnCount = (old == null) ? 0 : old.dnCount;
                String locs = locsById.get(card.id);
                if (locs == null) locs = "";
                if (card.shipment != null && !card.shipment.isEmpty()) shipment = card.shipment;
                if (card.zone != null && !card.zone.isEmpty()) zone = card.zone;
                if (card.hasLocations) {
                    locations = card.locations.size();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < card.locations.size(); i++) {
                        if (i > 0) sb.append(",");
                        sb.append(card.locations.get(i));
                    }
                    locs = sb.toString();
                }
                if (card.hasDnCount) dnCount = card.dnCount;
                String firstLoc = locs.isEmpty() ? "" : locs.split(",")[0];
                if ((zone == null || zone.isEmpty() || "?".equals(zone)) && !firstLoc.isEmpty()) {
                    zone = PickTrackerAdvisor.zoneFromLocation(firstLoc);
                }
                byId.put(card.id, new PickTrackerAdvisor.TaskInfo(
                        card.id, shipment, zone, 0, locations, dnCount));
                locsById.put(card.id, locs);
            }
            StringBuilder out = new StringBuilder();
            for (PickTrackerAdvisor.TaskInfo t : byId.values()) {
                if (out.length() > 0) out.append(TASK_SEP);
                out.append(t.id).append(FIELD_SEP)
                        .append(t.shipment).append(FIELD_SEP)
                        .append(t.zone).append(FIELD_SEP)
                        .append(t.locations).append(FIELD_SEP)
                        .append(t.dnCount).append(FIELD_SEP)
                        .append(locsById.get(t.id) == null ? "" : locsById.get(t.id));
            }
            prefs(c).edit().putString(KEY_PENDING, out.toString()).apply();
        } catch (Throwable ignored) { }
    }

    /** Tareas pendientes guardadas, en el orden en que se vieron en la lista. */
    public static List<PickTrackerAdvisor.TaskInfo> getPendingTasks(Context c) {
        List<PickTrackerAdvisor.TaskInfo> out = new ArrayList<>();
        try {
            String saved = prefs(c).getString(KEY_PENDING, "");
            if (saved == null || saved.isEmpty()) return out;
            for (String line : saved.split(TASK_SEP)) {
                String[] f = line.split(FIELD_SEP, -1);
                if (f.length < 5 || f[0].isEmpty()) continue;
                int locations = 0;
                int dnCount = 0;
                try { locations = Integer.parseInt(f[3]); } catch (Throwable ignored) { }
                try { dnCount = Integer.parseInt(f[4]); } catch (Throwable ignored) { }
                List<String> locs = new ArrayList<>();
                if (f.length >= 6 && f[5] != null && !f[5].isEmpty()) {
                    for (String l : f[5].split(",")) {
                        if (!l.isEmpty()) locs.add(l);
                    }
                }
                out.add(new PickTrackerAdvisor.TaskInfo(
                        f[0], f[1], f[2], 0, locations, dnCount, locs));
            }
        } catch (Throwable ignored) { }
        return out;
    }

    /** Ubicaciones guardadas de una tarea pendiente, o lista vacía. */
    public static List<String> getPendingLocations(Context c, String taskId) {
        List<String> out = new ArrayList<>();
        if (c == null || taskId == null) return out;
        try {
            String saved = prefs(c).getString(KEY_PENDING, "");
            if (saved == null || saved.isEmpty()) return out;
            for (String line : saved.split(TASK_SEP)) {
                String[] f = line.split(FIELD_SEP, -1);
                if (f.length >= 6 && taskId.equals(f[0]) && !f[5].isEmpty()) {
                    for (String p : f[5].split(",")) {
                        if (!p.trim().isEmpty()) out.add(p.trim());
                    }
                    return out;
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }

    public static int getTasksToday(Context c) {
        try { ensureDay(c); return prefs(c).getInt(KEY_TASKS_TODAY, 0); }
        catch (Throwable t) { return 0; }
    }

    public static int getPiecesToday(Context c) {
        try { ensureDay(c); return prefs(c).getInt(KEY_PIECES_TODAY, 0); }
        catch (Throwable t) { return 0; }
    }

    public static int getLocationsToday(Context c) {
        try { ensureDay(c); return prefs(c).getInt(KEY_LOCS_TODAY, 0); }
        catch (Throwable t) { return 0; }
    }

    /** Piezas por hora del día (0 si aún no hay tiempo registrado). */
    public static int getPiecesPerHour(Context c) {
        try {
            ensureDay(c);
            SharedPreferences p = prefs(c);
            long ms = p.getLong(KEY_TIME_TODAY_MS, 0L);
            int pieces = p.getInt(KEY_PIECES_TODAY, 0);
            if (ms <= 0 || pieces <= 0) return 0;
            double hours = ms / 3600000.0;
            return (int) Math.round(pieces / hours);
        } catch (Throwable t) { return 0; }
    }

    /** Segundos por ubicación del día (0 si aún no hay datos). */
    public static int getSecPerLocationToday(Context c) {
        try {
            ensureDay(c);
            SharedPreferences p = prefs(c);
            long ms = p.getLong(KEY_TIME_TODAY_MS, 0L);
            int locs = p.getInt(KEY_LOCS_TODAY, 0);
            if (ms <= 0 || locs <= 0) return 0;
            return (int) (ms / 1000L / locs);
        } catch (Throwable t) { return 0; }
    }

    public static int getLastSecPerLocation(Context c) {
        try { return prefs(c).getInt(KEY_LAST_SEC_PER_LOC, 0); }
        catch (Throwable t) { return 0; }
    }

    public static int getBoxesPerPallet(Context c) {
        try { return prefs(c).getInt(KEY_BOXES_PER_PALLET, DEFAULT_BOXES_PER_PALLET); }
        catch (Throwable t) { return DEFAULT_BOXES_PER_PALLET; }
    }

    public static void setBoxesPerPallet(Context c, int boxes) {
        if (c == null || boxes <= 0) return;
        try { prefs(c).edit().putInt(KEY_BOXES_PER_PALLET, boxes).apply(); }
        catch (Throwable ignored) { }
    }

    /** Formato mm:ss para un tiempo en segundos. */
    public static String formatMmSs(int totalSeconds) {
        if (totalSeconds < 0) totalSeconds = 0;
        int m = totalSeconds / 60;
        int s = totalSeconds % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }
}
