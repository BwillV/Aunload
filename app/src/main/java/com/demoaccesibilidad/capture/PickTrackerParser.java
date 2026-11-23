package com.demoaccesibilidad.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PickTrackerParser — Parser real de las pantallas de Pick Task (v2, nuevo).
 *
 * Lógica pura, sin dependencias de Android: se puede probar sin el escáner,
 * contra el diagnóstico real de Wilson (20261116_173121_Will.json, 300
 * capturas, 16:05-16:54 del 16-nov-2026). Validado con un prototipo en Python
 * antes de escribir este archivo: parsea bien TASK-44813 (4 ubicaciones,
 * 5 Skus, 8 EA, 166 s/ubicación), TASK-44793 (5 ubicaciones, 4 Skus, 7 EA,
 * 151 s/ubicación) y TASK-44792 (5 ubicaciones, 5 Skus, 8 EA, 188 s/ubicación).
 *
 * Dos pantallas, estructura fija (en ese orden):
 *   LISTA ("Pick Task List"): por tarjeta, TASK-xxxxx → Piece Pick →
 *     Wave Pick(By Item) → envío (texto tal cual, incluido el typo del WMS
 *     "UPS Siingle (Regular)") → [Claim Equipment] → Customer | Retailer →
 *     cliente → Suggested VLG → rango ("C04-C06 High" → zona C04) →
 *     Order Number → lista "DN-...,DN-..." → Priority | Creator | Created
 *     Time → "MIDDLE | | 11/16/2026 06:35" → [Suggest Location →
 *     ubicaciones separadas por coma]. Una tarjeta puede salir CORTADA al
 *     final de la lista (sin Suggest Location, o solo el TASK): por eso
 *     ListCard marca qué campos sí se vieron, y el Store solo actualiza
 *     esos campos, sin borrar lo ya guardado.
 *   DETALLE (título TASK-xxxxx): ubicación actual, "Section: 单",
 *     cantidad "X / Y EA" en nodos separados, SKU, nombre EN + ZH,
 *     LP SLP-xxxxxx, prompts "Scan or Input Pick to LP" /
 *     "Input Or Scan Item Code" / "Please scan or input tracking#",
 *     botones NEXT LOCATION / SUBMIT / CONFIRM, progreso
 *     "A / B Skus, C / D EA Picked", diálogo "Close step now?"
 *     (fin de ubicación) y "All steps finished. Are you sure to close
 *     task?" (fin de tarea).
 *
 * El dump de accesibilidad repite cada pantalla dos veces (la misma
 * pantalla aparece dos veces seguidas en la lista de textos). NO se
 * quitan duplicados aquí: las etiquetas se repiten también entre
 * tarjetas ("Wave Pick(By Item)" sale una vez por tarjeta) y quitar
 * duplicados globales borraría las de las tarjetas de abajo. Parsear
 * la lista dos veces es inofensivo: el Store fusiona por id y los
 * valores repetidos son idénticos.
 */
public final class PickTrackerParser {

    private PickTrackerParser() { }

    public static final Pattern PAT_TASK =
            Pattern.compile("^TASK-(\\d{4,8})$");
    public static final Pattern PAT_LOCATION =
            Pattern.compile("^[A-Z]{1,3}\\d{1,3}-\\d{1,4}-\\d{1,3}$");
    public static final Pattern PAT_PROGRESS = Pattern.compile(
            "(\\d+)\\s*/\\s*(\\d+)\\s*Skus,\\s*(\\d+)\\s*/\\s*(\\d+)\\s*EA Picked");
    /** LP destino de la tarea ("SLP-317118"), visible en el detalle. */
    public static final Pattern PAT_LP = Pattern.compile("^(SLP-\\d{3,})$");
    private static final Pattern PAT_VLG =
            Pattern.compile("^([A-Z]\\d{2})-[A-Z]\\d{2} High$");
    private static final Pattern PAT_LOCS_LIST = Pattern.compile(
            "^[A-Z]{1,3}\\d{1,3}-\\d{1,4}-\\d{1,3}(,[A-Z]{1,3}\\d{1,3}-\\d{1,4}-\\d{1,3})*$");
    private static final Pattern PAT_CREATED =
            Pattern.compile("(\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})");

    /** Una tarjeta de la lista, tal cual se leyó (vacío = no se vio). */
    public static final class ListCard {
        public String id = "";
        public String shipment = "";
        public String zone = "";
        public List<String> locations = new ArrayList<>();
        public boolean hasLocations = false;
        public int dnCount = 0;
        public boolean hasDnCount = false;
        public String created = "";
    }

    /** Progreso del detalle: "A / B Skus, C / D EA Picked". */
    public static final class Progress {
        public int skusDone;
        public int skusTotal;
        public int eaDone;
        public int eaTotal;
    }

    public static boolean isListScreen(List<String> textos) {
        if (textos == null) return false;
        for (String s : textos) {
            if (s != null && s.contains("Pick Task List")) return true;
        }
        return false;
    }

    /** Cabecera de pestaña "New(n)" — captura el contador de nuevas. */
    private static final Pattern PAT_NEW_TAB =
            Pattern.compile("^New\\((\\d+)\\)$");

    /**
     * v5.21 — Número de la pestaña "New(n)" de la Pick Task List, o -1 si no
     * aparece (otra pantalla o un WMS con otro diseño). Wilson pidió que SOLO
     * se lean las tareas de la pestaña New, nunca las de In Progress ni Done.
     */
    public static int newTabCount(List<String> textos) {
        if (textos == null) return -1;
        for (String s : textos) {
            if (s == null) continue;
            Matcher m = PAT_NEW_TAB.matcher(s.trim());
            if (m.matches()) {
                try { return Integer.parseInt(m.group(1)); }
                catch (Throwable ignored) { return -1; }
            }
        }
        return -1;
    }

    /**
     * v5.21 — ¿La lista que se ve ahora corresponde a la pestaña New?
     *
     * El volcado de accesibilidad NO marca qué pestaña está activa (no hay
     * "selected"), y la lista comparte el RecyclerView entre pestañas: las
     * tarjetas visibles son las de la pestaña activa. Reglas, de la más fuerte
     * a la más débil:
     *   - Sin cabecera "New(n)" → no es la Pick Task List con pestañas de este
     *     WMS; devolvemos true para no romper otros diseños (el llamador ya
     *     comprobó isListScreen).
     *   - New(0) → la pestaña New NO tiene tarjetas. Si se ven tarjetas, son de
     *     In Progress/Done → NO es New (false). Es el caso real de Wilson.
     *   - New(n>0) → la pestaña New tiene tarjetas. Si el nº de tarjetas en
     *     pantalla supera n, estamos viendo otra pestaña con más elementos
     *     (p. ej. Done(84)) → false. Si cuadra (≤ n), es New → true.
     */
    public static boolean isNewTabActive(List<String> textos) {
        int newN = newTabCount(textos);
        if (newN < 0) return true; // sin pestañas New: no filtramos.
        int cards = countTaskCards(textos);
        // v5.23 — Señal fuerte: el WMS muestra "Empty" cuando la pestaña New no
        // tiene tareas. Si vemos "Empty" y además hay tarjetas, esas tarjetas
        // son de In Progress/Done (otra pestaña activa) → NO es New.
        if (hasEmptyMarker(textos) && cards > 0) return false;
        if (newN == 0) {
            // New vacía: solo es "New" si no hay ninguna tarjeta a la vista.
            return cards == 0;
        }
        // v5.25 — New con tareas (New(n>0)) y sin marcador "Empty": lo visible
        // es la pestaña New → true. Antes exigíamos cards<=newN, pero el lector
        // acumula nodos del RecyclerView al hacer scroll, así que un solo paso
        // suele traer MÁS tarjetas que New(n) aunque SEA la pestaña New. Esa
        // comparación (8<=5) devolvía vacío en cada paso → "sale vacío al dar
        // New reading". Las guardas que de verdad detectan Done/In Progress son
        // "Empty"+tarjetas y New(0)+tarjetas (ambas arriba); esas se mantienen.
        return true;
    }

    /** ¿Aparece el marcador "Empty" (estado vacío de la pestaña New)? */
    private static boolean hasEmptyMarker(List<String> textos) {
        if (textos == null) return false;
        for (String s : textos) {
            if (s != null && "Empty".equalsIgnoreCase(s.trim())) return true;
        }
        return false;
    }

    /**
     * v5.23 — Guardia agregada: nº máximo de tareas que la pestaña New puede
     * tener según su cabecera "New(n)" en cualquiera de los pasos leídos. El
     * llamador la usa para descartar lecturas que trajeron más tareas que las
     * que New dice tener (señal clara de que se leyó In Progress/Done). -1 si
     * nunca se vio la cabecera New.
     */
    public static int maxNewTabCount(List<List<String>> pasos) {
        int max = -1;
        if (pasos == null) return max;
        for (List<String> paso : pasos) {
            int n = newTabCount(paso);
            if (n > max) max = n;
        }
        return max;
    }

    /** Nº de tarjetas distintas (TASK-xxxxx) presentes en los textos. */
    public static int countTaskCards(List<String> textos) {
        if (textos == null) return 0;
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (String s : textos) {
            if (s == null) continue;
            if (PAT_TASK.matcher(s.trim()).matches()) ids.add(s.trim());
        }
        return ids.size();
    }

    /**
     * v5.21 — Igual que {@link #parseList(List)}, pero devuelve las tarjetas
     * SOLO cuando la lista visible es la pestaña New. En cualquier otro caso
     * (New vacía con tarjetas de otra pestaña, o pestaña distinta activa)
     * devuelve una lista vacía: así nunca se leen tareas de In Progress/Done.
     */
    public static List<ListCard> parseListNewOnly(List<String> textos) {
        if (!isNewTabActive(textos)) return new ArrayList<>();
        return parseList(textos);
    }

    private static boolean isKnownLabel(String x) {
        return "Piece Pick".equals(x)
                || "Wave Pick(By Item)".equals(x)
                || "Claim Equipment".equals(x)
                || "Customer | Retailer".equals(x)
                || "Order Number".equals(x)
                || "Priority | Creator | Created Time".equals(x)
                || "Suggested VLG".equals(x)
                || "Suggest Location".equals(x)
                || "Search".equals(x)
                || "Navigate up".equals(x)
                || "Pick Task List".equals(x)
                || "No more data".equals(x)
                || x.startsWith("Starmerx")
                || x.startsWith("New(")
                || x.startsWith("In Progress(")
                || x.startsWith("Done(");
    }

    /**
     * Parsea las tarjetas de la lista, en orden. El envío se toma como la
     * primera línea no-etiqueta después de "Wave Pick(By Item)", tal cual
     * lo escribe el WMS (así sobrevive a carriers nuevos y al typo
     * "Siingle"). La zona sale del rango Suggested VLG ("C04-C06 High" →
     * "C04"); si no hay rango, se deriva de la primera ubicación.
     */
    public static List<ListCard> parseList(List<String> textos) {
        List<ListCard> cards = new ArrayList<>();
        if (textos == null) return cards;
        ListCard cur = null;
        boolean expectShipment = false;
        for (String x : textos) {
            if (x == null) continue;
            Matcher tm = PAT_TASK.matcher(x);
            if (tm.matches()) {
                cur = new ListCard();
                cur.id = x;
                cards.add(cur);
                expectShipment = false;
                continue;
            }
            if (cur == null) continue;
            if ("Wave Pick(By Item)".equals(x)) {
                expectShipment = true;
                continue;
            }
            if (expectShipment && !isKnownLabel(x)) {
                cur.shipment = x;
                expectShipment = false;
                continue;
            }
            if (isKnownLabel(x)) continue;
            Matcher vm = PAT_VLG.matcher(x);
            if (vm.matches()) {
                cur.zone = vm.group(1);
                continue;
            }
            if (x.startsWith("DN-")) {
                int n = 0;
                for (String p : x.split(",")) {
                    if (p.trim().startsWith("DN-")) n++;
                }
                cur.dnCount = n;
                cur.hasDnCount = true;
                continue;
            }
            if (PAT_LOCS_LIST.matcher(x).matches()) {
                cur.locations = new ArrayList<>();
                for (String p : x.split(",")) {
                    cur.locations.add(p.trim().toUpperCase(java.util.Locale.ROOT));
                }
                cur.hasLocations = true;
                continue;
            }
            if (x.startsWith("MIDDLE |")) {
                Matcher cm = PAT_CREATED.matcher(x);
                if (cm.find()) cur.created = cm.group(1);
            }
        }
        // Zona de respaldo: primer segmento de la primera ubicación.
        for (ListCard c : cards) {
            if ((c.zone == null || c.zone.isEmpty()) && !c.locations.isEmpty()) {
                c.zone = PickTrackerAdvisor.zoneFromLocation(c.locations.get(0));
            }
        }
        return cards;
    }

    /** TASK-xxxxx del título del detalle, o null si no es el detalle. */
    public static String detailTaskId(List<String> textos) {
        if (textos == null || textos.size() < 2) return null;
        if (!"Navigate up".equals(textos.get(0))) return null;
        return PAT_TASK.matcher(textos.get(1)).matches() ? textos.get(1) : null;
    }

    /** Ubicación actual del detalle (4ª línea), en mayúsculas, o null. */
    public static String detailLocation(List<String> textos) {
        if (textos == null || textos.size() < 4) return null;
        String loc = textos.get(3);
        if (loc == null) return null;
        return PAT_LOCATION.matcher(loc.trim()).matches()
                ? loc.trim().toUpperCase(java.util.Locale.ROOT) : null;
    }

    /** LP destino "SLP-xxxxxx" del detalle, o null si no aparece. */
    public static String detailLp(List<String> textos) {
        if (textos == null) return null;
        for (String s : textos) {
            if (s == null) continue;
            Matcher m = PAT_LP.matcher(s.trim());
            if (m.matches()) return m.group(1);
        }
        return null;
    }

    /** Progreso "A / B Skus, C / D EA Picked" del detalle, o null. */
    public static Progress parseProgress(List<String> textos) {
        if (textos == null) return null;
        for (String s : textos) {
            if (s == null) continue;
            Matcher m = PAT_PROGRESS.matcher(s);
            if (m.find()) {
                Progress p = new Progress();
                try {
                    p.skusDone = Integer.parseInt(m.group(1));
                    p.skusTotal = Integer.parseInt(m.group(2));
                    p.eaDone = Integer.parseInt(m.group(3));
                    p.eaTotal = Integer.parseInt(m.group(4));
                } catch (Throwable ignored) {
                    return null;
                }
                return p;
            }
        }
        return null;
    }

    /** Diálogo "Close step now?" (fin de una ubicación). */
    public static boolean isCloseStepDialog(List<String> textos) {
        if (textos == null) return false;
        for (String s : textos) {
            if (s != null && s.contains("Close step now?")) return true;
        }
        return false;
    }

    /** Diálogo "All steps finished..." (fin de la tarea). */
    public static boolean isCloseTaskDialog(List<String> textos) {
        if (textos == null) return false;
        for (String s : textos) {
            if (s != null && s.contains("All steps finished")) return true;
        }
        return false;
    }
}
