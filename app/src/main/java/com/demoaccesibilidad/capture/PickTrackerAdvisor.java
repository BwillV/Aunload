package com.demoaccesibilidad.capture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PickTrackerAdvisor — Lógica pura del consejero de tareas (v5.15, nuevo).
 *
 * Sin dependencias de Android: se puede probar sin el escáner.
 *
 * Reglas de Wilson (16-nov-2026, confirmadas por él en llamada):
 *   - Lo que importa es AVANZAR. Los LP no importan para agrupar: cada
 *     tarea mantiene su propio LP (verificado: el LP cambia por tarea y
 *     es estable durante toda la tarea).
 *   - Orden: MENOS artículos primero, después las de más. La cantidad de
 *     artículos de una tarea, antes de abrirla, es el conteo de DNs de
 *     "Order Number" (cada DN es un pedido de un solo artículo:
 *     TASK-44792 8 DNs = 8 EA, TASK-44793 7 DNs = 7 EA, TASK-44625
 *     10 DNs = 10 piezas). Los Skus son menos porque un mismo SKU se
 *     repite en varios pedidos.
 *   - Pallets: se juntan las tareas con MENOS ubicaciones asignadas en
 *     una sola pallet, por pasillo (B con B, C con C). El punto de
 *     referencia NO es el pasillo en sí: es el ESPACIO GRANDE entre B15
 *     y C1 donde se dejan las pallets. La cercanía se mide desde ahí
 *     (B14/B15 quedan cerca de C1/C2).
 *   - Ruta: se empieza por lo MÁS LEJANO y se avanza hacia el punto
 *     donde se dejan las pallets.
 *   - Una tarea con UNA sola ubicación y MUCHOS artículos (ej. 1
 *     ubicación, 10 artículos) es probablemente una pallet completa:
 *     se marca y se combina con otra de menos artículos.
 *   - 1 pieza = 1 caja; cajas por pallet ajustable, por defecto 48.
 *   - Referencia: 183 s por ubicación (3:03).
 *
 * Modelo de distancia (aproximación del layout, según Wilson): los
 * pasillos forman una línea B01..B15, C01..C15, D01... y el punto donde
 * se dejan las pallets está entre B15 y C1. Posición de una ubicación =
 * (letra - 'B') * 15 + número (B07 → 7, C14 → 29, D01 → 31); el punto
 * de drop está en 15.5. Distancia = |posición - 15.5|. Si el layout
 * real es distinto, aquí es donde se ajusta.
 *
 * La zona de una tarea sale del rango Suggested VLG de su tarjeta
 * (ej. "C04-C06 High" → zona "C04"); si no hay rango, del primer segmento
 * de su primera ubicación (ej. "C04-005-4" → zona "C04"). Si no hay
 * ubicación, la zona es "?".
 * Volumen de una tarea: sus piezas totales cuando se conocen (del progreso
 * del detalle); si no, su conteo de pedidos DN de la lista.
 */
public final class PickTrackerAdvisor {

    private PickTrackerAdvisor() { }

    /** Posición del punto donde se dejan las pallets (entre B15 y C1). */
    public static final double DROP_POS = 15.5;
    /** Artículos con los que una tarea de 1 ubicación se marca como probable pallet completa. */
    public static final int BIG_SINGLE_LOC_ARTICLES = 8;

    /** Una tarea pendiente, con lo mínimo para aconsejar. */
    public static final class TaskInfo {
        public final String id;
        public final String shipment;
        public final String zone;
        public final int pieces;
        public final int locations;
        /** Pedidos DN de la tarjeta (0 = no se vio). Volumen de respaldo. */
        public final int dnCount;
        /** Ubicaciones de la tarea, en orden (vacía si no se vieron). */
        public final List<String> locationsList;

        public TaskInfo(String id, String shipment, String firstLocation,
                        int pieces, int locations) {
            this(id, shipment, zoneFromLocation(firstLocation), pieces, locations, 0,
                    firstLocation == null || firstLocation.isEmpty()
                            ? new ArrayList<String>()
                            : Collections.singletonList(firstLocation));
        }

        public TaskInfo(String id, String shipment, String zone,
                        int pieces, int locations, int dnCount) {
            this(id, shipment, zone, pieces, locations, dnCount, new ArrayList<String>());
        }

        public TaskInfo(String id, String shipment, String zone,
                        int pieces, int locations, int dnCount,
                        List<String> locationsList) {
            this.id = id == null ? "" : id;
            this.shipment = shipment == null ? "" : shipment;
            this.zone = (zone == null || zone.isEmpty()) ? "?" : zone;
            this.pieces = pieces;
            this.locations = locations;
            this.dnCount = dnCount;
            this.locationsList = locationsList == null
                    ? new ArrayList<String>() : new ArrayList<>(locationsList);
        }

        /** Volumen para ordenar: piezas si se conocen, si no pedidos DN. */
        public int volume() {
            return pieces > 0 ? pieces : dnCount;
        }
    }

    /** Una pallet sugerida: tareas de un mismo pasillo, en orden de picking. */
    public static final class PalletGroup {
        public final String aisle;
        public final List<TaskInfo> tasks;
        public final double maxDistance;

        PalletGroup(String aisle, List<TaskInfo> tasks, double maxDistance) {
            this.aisle = aisle;
            this.tasks = tasks;
            this.maxDistance = maxDistance;
        }
    }

    public static String zoneFromLocation(String location) {
        if (location == null) return "?";
        String loc = location.trim().toUpperCase(java.util.Locale.ROOT);
        if (loc.isEmpty()) return "?";
        int dash = loc.indexOf('-');
        if (dash <= 0) return loc;
        return loc.substring(0, dash);
    }

    /** Letra del pasillo de una tarea (B, C, D...; "?" si no se sabe). */
    public static String aisleOf(TaskInfo t) {
        if (t == null) return "?";
        String z = t.zone == null ? "" : t.zone.trim().toUpperCase(java.util.Locale.ROOT);
        if (!z.isEmpty() && Character.isLetter(z.charAt(0))) return z.substring(0, 1);
        if (t.locationsList != null && !t.locationsList.isEmpty()) {
            String first = t.locationsList.get(0);
            if (first != null && !first.isEmpty() && Character.isLetter(first.charAt(0))) {
                return first.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            }
        }
        return "?";
    }

    /** Posición de una ubicación en la línea B01..B15, C01..C15, D01... */
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

    /** Distancia de una ubicación al punto donde se dejan las pallets. */
    public static double distanceFromDrop(String location) {
        double pos = positionOf(location);
        if (pos < 0) return -1;
        return Math.abs(pos - DROP_POS);
    }

    /** Distancia de una tarea al punto de drop: su ubicación más lejana. */
    public static double taskDistance(TaskInfo t) {
        if (t == null) return -1;
        double max = -1;
        if (t.locationsList != null) {
            for (String loc : t.locationsList) {
                double d = distanceFromDrop(loc);
                if (d > max) max = d;
            }
        }
        if (max < 0) {
            // Sin ubicaciones: usar la zona (ej. "B07" → posición de B07).
            max = distanceFromDrop(t.zone);
        }
        return max;
    }

    /** ¿Una sola ubicación con muchos artículos? Probable pallet completa. */
    public static boolean isBigSingleLocation(TaskInfo t) {
        if (t == null) return false;
        int locs = t.locations > 0 ? t.locations : t.locationsList.size();
        return locs == 1 && t.volume() >= BIG_SINGLE_LOC_ARTICLES;
    }

    /** Ordena por volumen ASCENDENTE: menos artículos primero (regla de Wilson). */
    public static List<TaskInfo> orderByVolume(List<TaskInfo> tasks) {
        List<TaskInfo> out = new ArrayList<>();
        if (tasks != null) out.addAll(tasks);
        Collections.sort(out, (a, b) -> {
            int c = Integer.compare(a.volume(), b.volume());
            if (c != 0) return c;
            // Desempate: la más lejana primero (se empieza por lo más largo).
            return Double.compare(taskDistance(b), taskDistance(a));
        });
        return out;
    }

    /** Agrupa tareas por zona + envío (las que conviene caminar juntas). */
    public static Map<String, List<TaskInfo>> groupTogether(List<TaskInfo> tasks) {
        Map<String, List<TaskInfo>> groups = new LinkedHashMap<>();
        if (tasks == null) return groups;
        for (TaskInfo t : tasks) {
            String key = t.zone + " | " + t.shipment;
            List<TaskInfo> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(key, g);
            }
            g.add(t);
        }
        return groups;
    }

    /**
     * Agrupa las tareas en pallets por pasillo (B con B, C con C), cada
     * pallet ordenado de menos a más artículos. Los pallets se ordenan por
     * su tarea más lejana al punto de drop: se empieza por lo más largo y
     * se avanza hacia B15/C1.
     */
    public static List<PalletGroup> groupIntoPallets(List<TaskInfo> tasks) {
        List<PalletGroup> out = new ArrayList<>();
        if (tasks == null || tasks.isEmpty()) return out;
        Map<String, List<TaskInfo>> byAisle = new LinkedHashMap<>();
        for (TaskInfo t : tasks) {
            String aisle = aisleOf(t);
            List<TaskInfo> g = byAisle.get(aisle);
            if (g == null) {
                g = new ArrayList<>();
                byAisle.put(aisle, g);
            }
            g.add(t);
        }
        for (Map.Entry<String, List<TaskInfo>> e : byAisle.entrySet()) {
            List<TaskInfo> ordered = orderByVolume(e.getValue());
            double max = -1;
            for (TaskInfo t : ordered) {
                double d = taskDistance(t);
                if (d > max) max = d;
            }
            out.add(new PalletGroup(e.getKey(), ordered, max));
        }
        Collections.sort(out, (a, b) -> Double.compare(b.maxDistance, a.maxDistance));
        return out;
    }

    /** Cuántos pallets llenan esas piezas (redondeo hacia arriba). */
    public static int palletsFor(int pieces, int boxesPerPallet) {
        if (pieces <= 0) return 0;
        if (boxesPerPallet <= 0) boxesPerPallet = PickTrackerStore.DEFAULT_BOXES_PER_PALLET;
        return (pieces + boxesPerPallet - 1) / boxesPerPallet;
    }

    /**
     * Consejo en español, línea por línea, listo para mostrar en el overlay.
     * Usa las tareas reales de la lista. Si no hay ninguna, devuelve "" y
     * el overlay muestra el aviso de "sin pendientes".
     * Formato: una línea por pallet (pasillo + tareas de menos a más
     * artículos), la ruta (empezar por lo más lejano, avanzar hacia
     * B15/C1) y el aviso de pallet probablemente completa.
     */
    public static String adviceText(List<TaskInfo> tasks, int boxesPerPallet) {
        if (tasks == null || tasks.isEmpty()) return "";
        List<PalletGroup> pallets = groupIntoPallets(tasks);
        int totalPieces = 0;
        int totalDn = 0;
        for (TaskInfo t : tasks) {
            totalPieces += t.pieces;
            totalDn += t.dnCount;
        }
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (PalletGroup p : pallets) {
            sb.append("Pallet ").append(n).append(" (pasillo ").append(p.aisle).append("): ");
            for (int i = 0; i < p.tasks.size(); i++) {
                if (i > 0) sb.append(", ");
                TaskInfo t = p.tasks.get(i);
                sb.append(t.id).append(" (").append(volumeLabel(t)).append(")");
            }
            sb.append(".\n");
            n++;
        }
        // Tarea de 1 ubicación con muchos artículos: probable pallet completa.
        for (TaskInfo t : tasks) {
            if (isBigSingleLocation(t)) {
                sb.append("Ojo: ").append(t.id).append(" tiene 1 ubicación con ")
                        .append(t.volume())
                        .append(" artículos: probablemente una pallet completa; combínala con una de menos.\n");
            }
        }
        // Ruta: la tarea más lejana al punto de drop, para empezar por ahí.
        TaskInfo farthest = null;
        double farDist = -1;
        for (TaskInfo t : tasks) {
            double d = taskDistance(t);
            if (d > farDist) {
                farDist = d;
                farthest = t;
            }
        }
        if (farthest != null && farDist >= 0) {
            sb.append("Empieza por lo más lejano (").append(farthest.id)
                    .append(") y avanza hacia B15/C1, donde dejas las pallets.\n");
        }
        if (totalPieces > 0) {
            sb.append("Total pendiente: ").append(totalPieces).append(" piezas = ")
                    .append(palletsFor(totalPieces, boxesPerPallet))
                    .append(" pallet(s) a ").append(boxesPerPallet).append(" cajas.");
        } else {
            sb.append("Total pendiente: ").append(totalDn).append(" pedidos DN.");
        }
        return sb.toString();
    }

    /** "8 piezas" si se conocen las piezas, si no "10 pedidos DN". */
    public static String volumeLabel(TaskInfo t) {
        if (t == null) return "";
        return t.pieces > 0 ? t.pieces + " piezas" : t.dnCount + " pedidos DN";
    }
}
