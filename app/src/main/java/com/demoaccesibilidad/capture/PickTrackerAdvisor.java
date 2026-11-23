package com.demoaccesibilidad.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PickTrackerAdvisor — Lógica pura del consejero de picking (v5.18, reescrito).
 *
 * Sin dependencias de Android: se puede probar sin el escáner (banco offline
 * /home/ubuntu/pttest con el diagnóstico real de Wilson).
 *
 * Reglas de Wilson (confirmadas por él):
 *   - DN = número de ARTÍCULOS de la orden (cada DN es un pedido de 1 artículo).
 *   - El límite de la pallet es por ARTÍCULOS, NO por cajas. Capacidad =
 *     {@link #PALLET_CAPACITY} artículos (una orden de 7 + una de 10 = pallet
 *     llena; 10 es el máximo con el que se completa). Constante fácil de cambiar.
 *   - Orden pequeña = menos de {@link #SMALL_ORDER_MAX}+1 artículos (es decir,
 *     &lt; 7). Se juntan pequeñas con grandes; normalmente 2 órdenes por pallet,
 *     3 si caben dentro del límite. Una orden que sola llega o supera el límite
 *     va sola.
 *   - Proximidad: la última ubicación de una orden debe quedar cerca de la
 *     primera de la siguiente. Mismo pasillo primero (B con B, C con C). Si no
 *     se puede, se cruzan números altos de B con números bajos de C
 *     (ej. B15 con C01), porque la entrega de pallets está en medio (entre
 *     B15 y C01).
 *   - Una orden con muchos artículos pero 1–2 ubicaciones (mismo producto
 *     repetido) se prioriza (pallet casi completa de una sola parada).
 *   - Se empieza por lo más lejano al punto de entrega y se avanza hacia el
 *     centro (B alto / C bajo).
 *   - Tiempo estimado: ~{@link #SECONDS_PER_LOCATION} s (3 min) por ubicación.
 *
 * Modelo de distancia: los pasillos forman una línea B01..B15, C01..C15, D01…
 * y el punto de entrega está entre B15 y C01. Posición de una ubicación =
 * (letra - 'B') * 15 + número (B07 → 7, C14 → 29, D01 → 31); la entrega está
 * en 15.5. Distancia = |posición - 15.5|. B15 (15) y C01 (16) quedan juntos
 * al lado de la entrega, por eso cruzar B alto con C bajo es lo más cercano.
 */
public final class PickTrackerAdvisor {

    private PickTrackerAdvisor() { }

    /** Capacidad de una pallet, en ARTÍCULOS (no cajas). Fácil de cambiar.
     *  v5.23: Wilson subió el límite a 20 artículos por pallet. */
    public static final int PALLET_CAPACITY = 20;
    /** Una orden con este número de artículos o más va sola en su pallet. */
    public static final int SOLO_ORDER_MIN = PALLET_CAPACITY;
    /** Orden pequeña: menos artículos que (SMALL_ORDER_MAX + 1), es decir < 7. */
    public static final int SMALL_ORDER_MAX = 6;
    /**
     * v5.23 — Máximo de ubicaciones de una tarea para poder juntarla con otras.
     * Wilson: "Se pueden juntar task que tengan más de 7 artículos, pero que no
     * pasen de tres ubicaciones por tarea". Una tarea con más de 3 ubicaciones
     * recorre demasiado y va sola.
     */
    public static final int MAX_MERGE_LOCS = 3;
    /**
     * v5.23 — Ya NO hay tope fijo de órdenes por pallet: Wilson permite juntar
     * más de tres tareas en una pallet mientras no se superen los
     * {@link #PALLET_CAPACITY} artículos. Se deja la constante como referencia.
     */
    public static final int MAX_ORDERS_PER_PALLET = 99;
    /** Segundos estimados por ubicación (~3 min). */
    public static final int SECONDS_PER_LOCATION = 180;
    /** Artículos mínimos para marcar "pallet casi completa" con 1–2 ubicaciones. */
    public static final int BIG_SINGLE_LOC_MIN_ARTICLES = 7;
    /** Posición del punto de entrega (entre B15 y C01). */
    public static final double DROP_POS = 15.5;
    /** Umbral de cercanía a la entrega para permitir cruzar B con C. */
    public static final double CROSS_BOUNDARY_MAX_DIST = 4.0;

    /** Una orden/tarea de picking, con lo mínimo para aconsejar. */
    public static final class TaskInfo {
        public final String id;
        public final String shipment;
        public final String zone;
        /** Artículos de la orden (conteo de DN). 0 = desconocido. */
        public final int articles;
        /** Ubicaciones de la orden, en el orden en que se vieron. */
        public final List<String> locations;

        public TaskInfo(String id, String shipment, String zone,
                        int articles, List<String> locations) {
            this.id = id == null ? "" : id;
            this.shipment = shipment == null ? "" : shipment;
            this.zone = (zone == null || zone.isEmpty()) ? "?" : zone;
            this.articles = Math.max(0, articles);
            this.locations = locations == null
                    ? new ArrayList<String>() : new ArrayList<>(locations);
        }

        public int locCount() {
            return locations.size();
        }

        /** ¿Muchos artículos concentrados en 1–2 ubicaciones? Pallet casi completa. */
        public boolean isBigSingleLocation() {
            int n = locCount();
            return articles >= BIG_SINGLE_LOC_MIN_ARTICLES && n >= 1 && n <= 2;
        }

        public boolean isSmall() {
            return articles > 0 && articles <= SMALL_ORDER_MAX;
        }
    }

    /** Una pallet sugerida: órdenes en orden de recogida (lo más lejano primero). */
    public static final class Pallet {
        public final List<TaskInfo> orders = new ArrayList<>();
        public boolean crossAisle = false;

        public int totalArticles() {
            int s = 0;
            for (TaskInfo t : orders) s += t.articles;
            return s;
        }

        public int totalLocations() {
            int s = 0;
            for (TaskInfo t : orders) s += t.locCount();
            return s;
        }

        public int estimatedSeconds() {
            return totalLocations() * SECONDS_PER_LOCATION;
        }

        /**
         * Tiempo estimado por pallet usando el promedio PERSONAL por ubicación
         * (segundos). Modelo: nº de ubicaciones × promedio propio por ubicación.
         * Es el mismo indicador que usa la pantalla de la jefa (每库位耗时 /
         * "time per location"). Si no hay datos propios, el llamador pasa el
         * valor por defecto (PickTrackerStore.BASELINE_SEC_PER_LOC = 3:03).
         */
        public int estimatedSeconds(int secPerLocation) {
            int spl = secPerLocation > 0 ? secPerLocation : SECONDS_PER_LOCATION;
            return totalLocations() * spl;
        }

        public boolean hasPriority() {
            for (TaskInfo t : orders) if (t.isBigSingleLocation()) return true;
            return false;
        }

        /** Distancia a la entrega de la ubicación más lejana de la pallet. */
        public double farthestDistance() {
            double max = -1;
            for (TaskInfo t : orders) {
                double d = taskFarthest(t);
                if (d > max) max = d;
            }
            return max;
        }

        /** Letra(s) del pasillo de la pallet ("B", "C", "B/C" si es cruce). */
        public String aisleLabel() {
            java.util.LinkedHashSet<String> aisles = new java.util.LinkedHashSet<>();
            for (TaskInfo t : orders) aisles.add(aisleOf(t));
            StringBuilder sb = new StringBuilder();
            for (String a : aisles) {
                if (sb.length() > 0) sb.append("/");
                sb.append(a);
            }
            return sb.length() == 0 ? "?" : sb.toString();
        }
    }

    // ───────────────────────── Geometría ─────────────────────────

    public static String zoneFromLocation(String location) {
        if (location == null) return "?";
        String loc = location.trim().toUpperCase(java.util.Locale.ROOT);
        if (loc.isEmpty()) return "?";
        int dash = loc.indexOf('-');
        if (dash <= 0) return loc;
        return loc.substring(0, dash);
    }

    public static String aisleOf(TaskInfo t) {
        if (t == null) return "?";
        String z = t.zone == null ? "" : t.zone.trim().toUpperCase(java.util.Locale.ROOT);
        if (!z.isEmpty() && Character.isLetter(z.charAt(0))) return z.substring(0, 1);
        if (t.locations != null && !t.locations.isEmpty()) {
            String first = t.locations.get(0);
            if (first != null && !first.isEmpty() && Character.isLetter(first.charAt(0))) {
                return first.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            }
        }
        return "?";
    }

    /** Posición en la línea B01..B15, C01..C15, D01… (-1 si no se entiende). */
    public static double positionOf(String location) {
        if (location == null) return -1;
        String loc = location.trim().toUpperCase(java.util.Locale.ROOT);
        if (loc.length() < 2 || !Character.isLetter(loc.charAt(0))) return -1;
        int dash = loc.indexOf('-');
        String numPart = dash > 1 ? loc.substring(1, dash) : loc.substring(1);
        try {
            int num = Integer.parseInt(numPart.replaceAll("[^0-9]", ""));
            return (loc.charAt(0) - 'B') * 15.0 + num;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static double distanceFromDrop(String location) {
        double pos = positionOf(location);
        if (pos < 0) return -1;
        return Math.abs(pos - DROP_POS);
    }

    /** Distancia a la entrega de la ubicación MÁS LEJANA de la orden. */
    public static double taskFarthest(TaskInfo t) {
        if (t == null) return -1;
        double max = -1;
        for (String loc : t.locations) {
            double d = distanceFromDrop(loc);
            if (d > max) max = d;
        }
        if (max < 0) max = distanceFromDrop(t.zone);
        return max;
    }

    /** Distancia a la entrega de la ubicación MÁS CERCANA de la orden. */
    public static double taskNearest(TaskInfo t) {
        if (t == null) return -1;
        double min = Double.MAX_VALUE;
        for (String loc : t.locations) {
            double d = distanceFromDrop(loc);
            if (d >= 0 && d < min) min = d;
        }
        if (min == Double.MAX_VALUE) min = distanceFromDrop(t.zone);
        return min;
    }

    // ───────────────────────── Plan de pallets ─────────────────────────

    /**
     * Agrupa las órdenes en pallets siguiendo las reglas de Wilson y devuelve
     * los pallets en orden de recorrido (el más lejano a la entrega primero).
     * Cada pallet trae sus órdenes ya ordenadas de la más lejana a la más
     * cercana a la entrega.
     */
    /**
     * v5.23 — Empaca las órdenes de UN pasillo siguiendo las reglas de Wilson:
     *   - Se prefiere juntar las órdenes grandes con las PEQUEÑAS (cada grande
     *     siembra una pallet y se le añaden pequeñas que quepan en el límite).
     *   - Solo si no quedan pequeñas con las que juntar, se emparejan dos
     *     grandes entre sí (p. ej. dos de 10 = 20), prefiriendo las de MENOS
     *     ubicaciones; como última opción se juntan igual mientras quepan.
     *   - Sin tope de nº de órdenes: el único límite es {@link #PALLET_CAPACITY}
     *     artículos.
     */
    private static List<Pallet> packAisle(List<TaskInfo> group) {
        List<Pallet> out = new ArrayList<>();
        if (group == null || group.isEmpty()) return out;

        List<TaskInfo> smalls = new ArrayList<>();
        List<TaskInfo> larges = new ArrayList<>();
        for (TaskInfo t : group) {
            if (t == null) continue;
            if (t.isSmall()) smalls.add(t); else larges.add(t);
        }
        // Lo más lejano a la entrega primero (orden de recorrido).
        Collections.sort(smalls, (a, b) -> Double.compare(taskFarthest(b), taskFarthest(a)));
        Collections.sort(larges, (a, b) -> Double.compare(taskFarthest(b), taskFarthest(a)));

        boolean[] smallUsed = new boolean[smalls.size()];

        // Fase 1: cada grande siembra una pallet y recoge pequeñas que quepan.
        List<Pallet> largePallets = new ArrayList<>();
        for (TaskInfo L : larges) {
            Pallet p = new Pallet();
            p.orders.add(L);
            for (int i = 0; i < smalls.size(); i++) {
                if (smallUsed[i]) continue;
                if (p.totalArticles() + smalls.get(i).articles <= PALLET_CAPACITY) {
                    p.orders.add(smalls.get(i));
                    smallUsed[i] = true;
                }
            }
            largePallets.add(p);
        }

        // Fase 2: emparejar pallets que quedaron con UNA sola grande (ninguna
        // pequeña pudo entrar). Se juntan dos grandes solo aquí (ya no había
        // pequeñas). Preferimos las de menos ubicaciones.
        List<Pallet> singlesLarge = new ArrayList<>();
        for (Pallet p : largePallets) {
            if (p.orders.size() == 1) singlesLarge.add(p);
            else out.add(p);
        }
        Collections.sort(singlesLarge,
                (a, b) -> Integer.compare(a.totalLocations(), b.totalLocations()));
        boolean[] used = new boolean[singlesLarge.size()];
        for (int i = 0; i < singlesLarge.size(); i++) {
            if (used[i]) continue;
            Pallet a = singlesLarge.get(i);
            for (int j = i + 1; j < singlesLarge.size(); j++) {
                if (used[j]) continue;
                Pallet b = singlesLarge.get(j);
                if (a.totalArticles() + b.totalArticles() <= PALLET_CAPACITY) {
                    a.orders.addAll(b.orders);
                    used[j] = true;
                    break;
                }
            }
            used[i] = true;
            out.add(a);
        }

        // Fase 3: pequeñas sobrantes empaquetadas juntas (hasta el límite).
        Pallet cur = null;
        for (int i = 0; i < smalls.size(); i++) {
            if (smallUsed[i]) continue;
            TaskInfo s = smalls.get(i);
            if (cur == null || cur.totalArticles() + s.articles > PALLET_CAPACITY) {
                cur = new Pallet();
                out.add(cur);
            }
            cur.orders.add(s);
        }

        // Dentro de cada pallet: lo más lejano a la entrega primero.
        for (Pallet p : out) {
            Collections.sort(p.orders,
                    (x, y) -> Double.compare(taskFarthest(y), taskFarthest(x)));
        }
        return out;
    }

    public static List<Pallet> plan(List<TaskInfo> tasks) {
        List<Pallet> pallets = new ArrayList<>();
        if (tasks == null || tasks.isEmpty()) return pallets;

        List<TaskInfo> pool = new ArrayList<>();
        // 1) Van solas en su pallet: las órdenes que solas llegan o superan el
        //    límite de artículos, Y las que tienen más de MAX_MERGE_LOCS
        //    ubicaciones (v5.23: Wilson no quiere juntar tareas de muchas
        //    ubicaciones aunque tengan pocos artículos).
        for (TaskInfo t : tasks) {
            if (t == null) continue;
            if (t.articles >= SOLO_ORDER_MIN || t.locCount() > MAX_MERGE_LOCS) {
                Pallet p = new Pallet();
                p.orders.add(t);
                pallets.add(p);
            } else {
                pool.add(t);
            }
        }

        // 2) Empaque por pasillo (B con B, C con C).
        Map<String, List<TaskInfo>> byAisle = new LinkedHashMap<>();
        for (TaskInfo t : pool) {
            String a = aisleOf(t);
            List<TaskInfo> g = byAisle.get(a);
            if (g == null) { g = new ArrayList<>(); byAisle.put(a, g); }
            g.add(t);
        }
        List<Pallet> perAisle = new ArrayList<>();
        for (Map.Entry<String, List<TaskInfo>> e : byAisle.entrySet()) {
            perAisle.addAll(packAisle(e.getValue()));
        }

        // 3) Cruce de restos en la frontera: un pallet de 1 sola orden de B
        //    cerca de la entrega (B alto) con uno de 1 sola orden de C cerca
        //    de la entrega (C bajo), si juntos caben en el límite.
        List<Pallet> leftoversB = new ArrayList<>();
        List<Pallet> leftoversC = new ArrayList<>();
        List<Pallet> full = new ArrayList<>();
        for (Pallet p : perAisle) {
            boolean soloOrden = p.orders.size() == 1;
            boolean conHueco = p.totalArticles() < PALLET_CAPACITY;
            TaskInfo o = p.orders.get(0);
            String a = aisleOf(o);
            if (soloOrden && conHueco && taskNearest(o) <= CROSS_BOUNDARY_MAX_DIST) {
                if ("B".equals(a)) { leftoversB.add(p); continue; }
                if ("C".equals(a)) { leftoversC.add(p); continue; }
            }
            full.add(p);
        }
        // Emparejar por cercanía a la entrega (lo más pegado a la frontera).
        Collections.sort(leftoversB, (x, y) ->
                Double.compare(taskNearest(x.orders.get(0)), taskNearest(y.orders.get(0))));
        Collections.sort(leftoversC, (x, y) ->
                Double.compare(taskNearest(x.orders.get(0)), taskNearest(y.orders.get(0))));
        int bi = 0, ci = 0;
        while (bi < leftoversB.size() && ci < leftoversC.size()) {
            Pallet pb = leftoversB.get(bi);
            Pallet pc = leftoversC.get(ci);
            if (pb.totalArticles() + pc.totalArticles() <= PALLET_CAPACITY) {
                Pallet merged = new Pallet();
                merged.crossAisle = true;
                merged.orders.addAll(pb.orders);
                merged.orders.addAll(pc.orders);
                // Más lejano primero dentro de la pallet cruzada.
                Collections.sort(merged.orders,
                        (a, b) -> Double.compare(taskFarthest(b), taskFarthest(a)));
                full.add(merged);
                bi++; ci++;
            } else {
                // No caben: dejar el de C solo y probar el siguiente de C.
                full.add(pc);
                ci++;
            }
        }
        while (bi < leftoversB.size()) full.add(leftoversB.get(bi++));
        while (ci < leftoversC.size()) full.add(leftoversC.get(ci++));

        pallets.addAll(full);

        // 4) Orden de los pallets: los que tienen una orden prioritaria
        //    (pallet casi completa) primero; luego lo más lejano a la entrega.
        Collections.sort(pallets, (a, b) -> {
            if (a.hasPriority() != b.hasPriority()) return a.hasPriority() ? -1 : 1;
            return Double.compare(b.farthestDistance(), a.farthestDistance());
        });
        return pallets;
    }

    /** Minutos estimados a partir de segundos (redondeo). */
    public static int minutesOf(int seconds) {
        if (seconds <= 0) return 0;
        return (seconds + 30) / 60;
    }

    /**
     * Consejo en texto plano (para el banco offline y como respaldo). La UI
     * real (PickTrackerHelper) dibuja los pallets con checkboxes; este texto
     * resume el mismo plan.
     */
    public static String adviceText(List<TaskInfo> tasks) {
        if (tasks == null || tasks.isEmpty()) return "";
        List<Pallet> pallets = plan(tasks);
        StringBuilder sb = new StringBuilder();
        int totalArt = 0, totalLoc = 0, totalSec = 0;
        int n = 1;
        for (Pallet p : pallets) {
            sb.append("Pallet ").append(n);
            if (p.crossAisle) sb.append(" (cruce B/C)");
            else sb.append(" (pasillo ").append(p.aisleLabel()).append(")");
            sb.append(": ");
            for (int i = 0; i < p.orders.size(); i++) {
                if (i > 0) sb.append(" + ");
                TaskInfo t = p.orders.get(i);
                sb.append(t.id).append(" (").append(t.articles).append(" art, ")
                        .append(t.locCount()).append(" ubic)");
            }
            sb.append(" = ").append(p.totalArticles()).append(" art, ")
                    .append(p.totalLocations()).append(" ubic, ~")
                    .append(minutesOf(p.estimatedSeconds())).append(" min");
            if (p.hasPriority()) sb.append(" ⭐ casi completa");
            sb.append(".\n");
            totalArt += p.totalArticles();
            totalLoc += p.totalLocations();
            totalSec += p.estimatedSeconds();
            n++;
        }
        sb.append("Total: ").append(pallets.size()).append(" pallets · ")
                .append(totalArt).append(" artículos · ")
                .append(totalLoc).append(" ubicaciones · ~")
                .append(minutesOf(totalSec)).append(" min.\n");
        sb.append("Empieza por lo más lejano y avanza hacia B15/C01 (entrega).");
        return sb.toString();
    }
}
