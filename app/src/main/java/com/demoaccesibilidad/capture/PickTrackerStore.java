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

    // ── Estadísticas personales (v5.19), 100% locales, solo de este escáner ──
    // Tarea en curso (transitorio): momento de inicio y EA recogidos vistos.
    private static final String KEY_CUR_START_TS = "pt_cur_start_ts";
    private static final String KEY_CUR_EA = "pt_cur_ea";
    // Contadores del DÍA (se reinician al cambiar de día).
    private static final String KEY_STATS_DAY = "pt_stats_day";
    private static final String KEY_DAY_TASKS = "pt_day_tasks";
    private static final String KEY_DAY_LOCS = "pt_day_locs";
    private static final String KEY_DAY_ART = "pt_day_art";
    private static final String KEY_DAY_EA = "pt_day_ea";
    private static final String KEY_DAY_SEC = "pt_day_sec";
    // Contadores ACUMULADOS (no se reinician salvo "Reiniciar estadísticas").
    private static final String KEY_TOT_TASKS = "pt_tot_tasks";
    private static final String KEY_TOT_LOCS = "pt_tot_locs";
    private static final String KEY_TOT_ART = "pt_tot_art";
    private static final String KEY_TOT_EA = "pt_tot_ea";
    private static final String KEY_TOT_SEC = "pt_tot_sec";

    /** Segundos por ubicación por defecto (3:03, referencia real de Wilson). */
    public static final int BASELINE_SEC_PER_LOC = 183;
    /** Tiempo máximo razonable de una tarea (6 h); por encima se descarta. */
    private static final long MAX_TASK_SEC = 6L * 3600L;

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
            // Al abrir el detalle de una tarea nueva se arranca el cronómetro
            // personal y se reinicia el contador de EA de esta tarea.
            prefs(c).edit()
                    .putString(KEY_CURRENT_TASK, taskId.trim())
                    .putLong(KEY_CUR_START_TS, System.currentTimeMillis())
                    .putInt(KEY_CUR_EA, 0)
                    .apply();
        } catch (Throwable ignored) { }
    }

    public static String getCurrentTask(Context c) {
        try { return prefs(c).getString(KEY_CURRENT_TASK, ""); }
        catch (Throwable t) { return ""; }
    }

    public static void endTask(Context c) {
        if (c == null) return;
        try {
            // Al cerrarse la tarea ("All steps finished") se cierra el
            // cronómetro y se suman las estadísticas personales de ESTA tarea.
            String task = getCurrentTask(c);
            long start = prefs(c).getLong(KEY_CUR_START_TS, 0L);
            if (task != null && !task.isEmpty() && start > 0L) {
                long sec = (System.currentTimeMillis() - start) / 1000L;
                if (sec > 0L && sec < MAX_TASK_SEC) {
                    int locs = 0;
                    try {
                        List<String> list = readTaskLocs(c).get(task);
                        if (list != null) locs = list.size();
                    } catch (Throwable ignored) { }
                    int ea = prefs(c).getInt(KEY_CUR_EA, 0);
                    int art = 0;
                    try {
                        String[] f = readPending(c).get(task);
                        if (f != null) art = Integer.parseInt(f[2]);
                    } catch (Throwable ignored) { }
                    recordCompletion(c, sec, locs, art, ea);
                }
            }
            prefs(c).edit()
                    .putString(KEY_CURRENT_TASK, "")
                    .remove(KEY_CUR_START_TS)
                    .remove(KEY_CUR_EA)
                    .apply();
        } catch (Throwable ignored) { }
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

    /**
     * Progreso del detalle. El plan de pallets no depende de esto, pero sí se
     * usa para las ESTADÍSTICAS personales: guarda el máximo de EA recogidos
     * visto en la tarea en curso, para sumarlo al cerrarse la tarea.
     */
    public static void updateProgress(Context c, int skusDone, int skusTotal,
                                      int eaDone, int eaTotal) {
        if (c == null) return;
        try {
            String task = getCurrentTask(c);
            if (task == null || task.isEmpty()) return;
            int prev = prefs(c).getInt(KEY_CUR_EA, 0);
            if (eaDone > prev) {
                prefs(c).edit().putInt(KEY_CUR_EA, eaDone).apply();
            }
        } catch (Throwable ignored) { }
    }

    // ───────────────────────── Estadísticas personales ─────────────────────────

    /** Instantánea de métricas personales (del día o acumuladas). */
    public static final class Stats {
        public int tasks;
        public int locations;
        public int articles;
        public int ea;
        public long seconds;

        /** Segundos promedio por ubicación (0 si no hay datos). */
        public long secPerLocation() { return locations > 0 ? seconds / locations : 0L; }
        /** Segundos promedio por tarea (0 si no hay datos). */
        public long secPerTask() { return tasks > 0 ? seconds / tasks : 0L; }
        /** Segundos promedio por artículo/EA (0 si no hay datos). */
        public long secPerArticle() { return articles > 0 ? seconds / articles : 0L; }
        /** Artículos (EA) por hora (0 si no hay tiempo). */
        public int eaPerHour() {
            return seconds > 0 ? (int) Math.round(ea * 3600.0 / seconds) : 0;
        }
        public boolean isEmpty() { return tasks == 0 && locations == 0 && seconds == 0L; }
    }

    private static void recordCompletion(Context c, long sec, int locs, int art, int ea) {
        try {
            rolloverDayIfNeeded(c);
            SharedPreferences p = prefs(c);
            p.edit()
                    .putInt(KEY_DAY_TASKS, p.getInt(KEY_DAY_TASKS, 0) + 1)
                    .putInt(KEY_DAY_LOCS, p.getInt(KEY_DAY_LOCS, 0) + Math.max(0, locs))
                    .putInt(KEY_DAY_ART, p.getInt(KEY_DAY_ART, 0) + Math.max(0, art))
                    .putInt(KEY_DAY_EA, p.getInt(KEY_DAY_EA, 0) + Math.max(0, ea))
                    .putLong(KEY_DAY_SEC, p.getLong(KEY_DAY_SEC, 0L) + sec)
                    .putInt(KEY_TOT_TASKS, p.getInt(KEY_TOT_TASKS, 0) + 1)
                    .putInt(KEY_TOT_LOCS, p.getInt(KEY_TOT_LOCS, 0) + Math.max(0, locs))
                    .putInt(KEY_TOT_ART, p.getInt(KEY_TOT_ART, 0) + Math.max(0, art))
                    .putInt(KEY_TOT_EA, p.getInt(KEY_TOT_EA, 0) + Math.max(0, ea))
                    .putLong(KEY_TOT_SEC, p.getLong(KEY_TOT_SEC, 0L) + sec)
                    .apply();
        } catch (Throwable ignored) { }
    }

    /** Si cambió el día, reinicia los contadores del día (no los acumulados). */
    private static void rolloverDayIfNeeded(Context c) {
        try {
            SharedPreferences p = prefs(c);
            String hoy = today();
            String guardado = p.getString(KEY_STATS_DAY, "");
            if (!hoy.equals(guardado)) {
                p.edit()
                        .putString(KEY_STATS_DAY, hoy)
                        .putInt(KEY_DAY_TASKS, 0)
                        .putInt(KEY_DAY_LOCS, 0)
                        .putInt(KEY_DAY_ART, 0)
                        .putInt(KEY_DAY_EA, 0)
                        .putLong(KEY_DAY_SEC, 0L)
                        .apply();
            }
        } catch (Throwable ignored) { }
    }

    private static String today() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** Estadísticas del día de hoy (0 si ya es otro día). */
    public static Stats getStatsToday(Context c) {
        Stats s = new Stats();
        try {
            rolloverDayIfNeeded(c);
            SharedPreferences p = prefs(c);
            s.tasks = p.getInt(KEY_DAY_TASKS, 0);
            s.locations = p.getInt(KEY_DAY_LOCS, 0);
            s.articles = p.getInt(KEY_DAY_ART, 0);
            s.ea = p.getInt(KEY_DAY_EA, 0);
            s.seconds = p.getLong(KEY_DAY_SEC, 0L);
        } catch (Throwable ignored) { }
        return s;
    }

    /** Estadísticas acumuladas (desde el último reinicio). */
    public static Stats getStatsTotal(Context c) {
        Stats s = new Stats();
        try {
            SharedPreferences p = prefs(c);
            s.tasks = p.getInt(KEY_TOT_TASKS, 0);
            s.locations = p.getInt(KEY_TOT_LOCS, 0);
            s.articles = p.getInt(KEY_TOT_ART, 0);
            s.ea = p.getInt(KEY_TOT_EA, 0);
            s.seconds = p.getLong(KEY_TOT_SEC, 0L);
        } catch (Throwable ignored) { }
        return s;
    }

    /** ¿Hay datos propios suficientes para estimar con el promedio del usuario? */
    public static boolean hasPersonalData(Context c) {
        try {
            SharedPreferences p = prefs(c);
            return p.getInt(KEY_TOT_LOCS, 0) > 0 && p.getLong(KEY_TOT_SEC, 0L) > 0L;
        } catch (Throwable t) { return false; }
    }

    /**
     * Segundos por ubicación a usar para estimar (acumulado propio si existe,
     * si no el valor por defecto {@link #BASELINE_SEC_PER_LOC}).
     */
    public static int secPerLocation(Context c) {
        try {
            SharedPreferences p = prefs(c);
            int locs = p.getInt(KEY_TOT_LOCS, 0);
            long sec = p.getLong(KEY_TOT_SEC, 0L);
            if (locs > 0 && sec > 0L) {
                int v = (int) (sec / locs);
                if (v > 0) return v;
            }
        } catch (Throwable ignored) { }
        return BASELINE_SEC_PER_LOC;
    }

    /** Reinicia TODAS las estadísticas personales (día + acumulado + tarea en curso). */
    public static void resetStats(Context c) {
        if (c == null) return;
        try {
            prefs(c).edit()
                    .remove(KEY_CUR_START_TS).remove(KEY_CUR_EA)
                    .remove(KEY_STATS_DAY)
                    .remove(KEY_DAY_TASKS).remove(KEY_DAY_LOCS).remove(KEY_DAY_ART)
                    .remove(KEY_DAY_EA).remove(KEY_DAY_SEC)
                    .remove(KEY_TOT_TASKS).remove(KEY_TOT_LOCS).remove(KEY_TOT_ART)
                    .remove(KEY_TOT_EA).remove(KEY_TOT_SEC)
                    .apply();
        } catch (Throwable ignored) { }
    }

    /** Formatea segundos como "M:SS" (ej. 183 → "3:03"). */
    public static String formatMmSs(long sec) {
        if (sec < 0) sec = 0;
        long m = sec / 60, s = sec % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    /** Formatea una duración como "Xh YYm" o "Mm SSs". */
    public static String formatDur(long sec) {
        if (sec < 0) sec = 0;
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) return h + "h " + (m < 10 ? "0" + m : String.valueOf(m)) + "m";
        return m + "m " + (s < 10 ? "0" + s : String.valueOf(s)) + "s";
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
