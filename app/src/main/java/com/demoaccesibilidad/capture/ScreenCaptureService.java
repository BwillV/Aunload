package com.demoaccesibilidad.capture;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio de Accesibilidad — PalletTracker
 *
 * Lee textos visibles en pantalla y detecta automáticamente:
 *  - Pallet Codes (ILP-XXXXX)
 *  - SKUs (el texto que aparece justo después del ILP)
 *  - Cantidades (decimales)
 *  - Ubicaciones (B03-033-4, DOCK-01, STAGE 3, ...)
 *
 * NUNCA se extrae ni se guarda ningún "TASK-...".
 *
 * Patrón "Señal vs Dato":
 *  → Los DATOS se cachean conforme aparecen en pantalla.
 *  → La SEÑAL "Put away success" confirma y guarda el registro.
 *
 * v4.4 — detección de ubicación mucho más robusta:
 *  → Patrón de ubicación flexible (distinto nº de segmentos, sufijos, DOCK/STAGE...).
 *  → Prioridad por etiqueta (Location / To Location / Dest...), luego estructural
 *    (texto junto al SKU) y por último patrón.
 *  → Se recorren TODAS las ventanas (getWindows) y se leen text + contentDescription + hint.
 *  → Reintentos temporales: si un pallet se guarda sin ubicación, se reintenta
 *    durante ~1.5 s y se actualiza la MISMA pallet (sin duplicar).
 *  → Diagnóstico: al guardar sin ubicación se vuelca todo lo leído al registro.
 */
public class ScreenCaptureService extends AccessibilityService {

    private static final String TAG = "PalletTracker";

    // ====================== BROADCAST ======================
    public static final String ACTION_PALLET_SAVED =
            "com.demoaccesibilidad.capture.PALLET_SAVED";

    // ====================== ANTI-DUPLICADOS ======================
    private String ultimaFirmaPantalla = "";
    private String ultimaFirmaDiag = "";
    private long ultimaLectura = 0L;

    private static final long INTERVALO_MIN_MS = 300;
    private static final int MAX_PROFUNDIDAD = 40;
    private static final int MAX_TEXTOS = 600;

    // ====================== CACHE DE DATOS ACTUALES ======================
    // v4.0: cachedTaskId eliminado — ya no se maneja Task ID.
    private String cachedPalletCode = null;
    private String cachedSku = null;
    private double cachedQuantity = 0.0;
    private String cachedLocation = null;

    // Pallets ya guardados (evita duplicados en la sesión).
    private final Set<String> savedPalletCodes = new HashSet<>();

    // ====================== PATRONES REGEX ======================
    // v4.0: PAT_TASK eliminado — ya no se extrae Task ID.
    private static final Pattern PAT_PALLET =
            Pattern.compile("ILP[-\\s]?(\\d{4,8})");

    // v4.4: Ubicación FLEXIBLE. Antes solo valía "B02-133-3" (demasiado estricto,
    // por eso "detectaba una y otras no"). Ahora acepta:
    //   - 2 ó 3 (o más) segmentos separados por "-" o "." con distinta cantidad
    //     de dígitos: B03-033-4, A1-12-3, BIN-12, 12-034-5-2, ...
    //   - un prefijo de 1 a 3 letras opcional por segmento (B03, A1, AA12...).
    //   - sufijo opcional.
    // El primer segmento debe tener alguna letra O al menos dos dígitos, para
    // no confundir con una cantidad suelta.
    private static final Pattern PAT_LOCATION =
            Pattern.compile("\\b([A-Z]{0,3}\\d{1,4})([-.][A-Z]{0,3}\\d{1,4}){1,4}[A-Z]?\\b");

    // v4.4: Ubicaciones "con nombre" tipo DOCK-01, STAGE 3, DOOR 12, BAY-7...
    private static final Pattern PAT_LOCATION_NAMED =
            Pattern.compile("\\b(DOCK|STAGE|STAGING|DOOR|BAY|LANE|SLOT|SPOT|AISLE|ZONE|RACK|SHELF|BIN|PICK|DROP)[\\s-]*([A-Z]?\\d{1,4})\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PAT_QUANTITY =
            Pattern.compile("(\\d{1,5}\\.\\d{1,2})");

    // Etiquetas típicas del WMS que anteceden al valor de la ubicación.
    private static final String[] ETIQUETAS_UBICACION = {
            "To Location", "To Loc", "Location", "Loc", "Ubicación", "Ubicacion",
            "Destination", "Dest", "Put to", "Putaway to", "Bin", "Slot", "Position"
    };

    // ====================== REINTENTOS DE UBICACIÓN ======================
    // Si un pallet se guarda sin ubicación, se reintenta leer la pantalla
    // varias veces durante ~1.5 s y se ACTUALIZA la misma pallet (sin duplicar).
    private final Handler locHandler = new Handler(Looper.getMainLooper());
    private Runnable locRunnable = null;
    private String pendingLocPalletCode = null;   // pallet a completar
    private int locRetries = 0;
    private static final int MAX_LOC_RETRIES = 7;
    private static final long LOC_RETRY_MS = 220;
    // v4.1: PAT_SKU por regex ELIMINADO. Causaba el bug: "[A-Z]{2,}[_-][A-Z0-9]{4,}"
    // coincidía con "TASK-2693" (TASK + '-' + 2693) y lo guardaba como SKU.
    // Ahora el SKU es el nodo de texto que aparece JUSTO DESPUÉS del ILP.

    // =============================================================
    //                    PUNTO DE ENTRADA
    // =============================================================

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "✅\u00A0Servicio\u00A0de\u00A0accesibilidad\u00A0conectado");

        // Auto-iniciar overlay flotante si tiene permiso.
        try {
            SharedPreferences overlayPrefs = getSharedPreferences("overlay_prefs", MODE_PRIVATE);
            boolean overlayEnabled = overlayPrefs.getBoolean("overlay_enabled", true);

            if (overlayEnabled && Settings.canDrawOverlays(this)
                    && !FloatingButtonService.isRunning()) {
                Intent overlayIntent = new Intent(this, FloatingButtonService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(overlayIntent);
                } else {
                    startService(overlayIntent);
                }
                Log.d(TAG, "🚀\u00A0Overlay\u00A0flotante\u00A0iniciado\u00A0automáticamente");
            }
        } catch (Exception e) {
            Log.w(TAG, "No\u00A0se\u00A0pudo\u00A0iniciar\u00A0overlay", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        CharSequence pkg = event.getPackageName();
        if (pkg != null && getPackageName().contentEquals(pkg)) return;

        int tipo = event.getEventType();

        if (tipo == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || tipo == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            String texto = extraerTextoEvento(event);
            if (texto != null && !texto.isEmpty()) {
                onTextoIndividualCapturado(texto);
            }
        }

        if (tipo == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || tipo == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            long ahora = System.currentTimeMillis();
            if (ahora - ultimaLectura > INTERVALO_MIN_MS) {
                ultimaLectura = ahora;
                leerPantallaCompleta();
            }
        }
    }

    // =============================================================
    //              LECTURA COMPLETA DE PANTALLA
    // =============================================================

    private void leerPantallaCompleta() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Exception ignored) { }

        boolean esAppPropia = false;
        try {
            if (root != null) {
                CharSequence pkg = root.getPackageName();
                esAppPropia = pkg != null && getPackageName().contentEquals(pkg);
            }
        } catch (Exception ignored) { }

        // v4.4: leer la ventana activa Y todas las demás ventanas (getWindows),
        // porque algunos WMS muestran la ubicación en un panel/overlay separado.
        List<String> textos = recolectarTodo(root);
        if (textos.isEmpty()) return;

        String firma = String.valueOf(textos);
        if (firma.equals(ultimaFirmaPantalla)) return;
        ultimaFirmaPantalla = firma;

        // DIAGNÓSTICO: grabar si está activo.
        DiagnosticoRepository repoDiag = new DiagnosticoRepository(this);
        if (repoDiag.isDiagGrabando() && !esAppPropia) {
            if (!firma.equals(ultimaFirmaDiag)) {
                ultimaFirmaDiag = firma;
                guardarDiagnostico(repoDiag, textos);
            }
        }

        // Lógica de negocio: detección de pallets.
        if (!esAppPropia) {
            procesarTextos(textos);
        }
    }

    // =============================================================
    //          DETECCIÓN AUTOMÁTICA DE PALLETS (WMS)
    // =============================================================

    protected void onTextoIndividualCapturado(String texto) {
        // Texto escrito en un campo — podría ser un scan.
        if (texto == null) return;
        Matcher m = PAT_PALLET.matcher(texto);
        if (m.find()) {
            cachedPalletCode = "ILP-" + m.group(1);
            Log.d(TAG, "Pallet\u00A0escaneado:\u00A0" + cachedPalletCode);
        }
    }

    protected void procesarTextos(List<String> textos) {
        // ── Paso 1: Extraer datos de la pantalla actual ──
        // v4.0: YA NO se extrae Task ID. Todo pallet se agrupa por container
        // secuencial simple (Container 1, Container 2, ...).
        String palletCode = null;
        String sku = null;
        double quantity = 0.0;
        String location = null;
        boolean putAwaySuccess = false;
        boolean putAwayButton = false;
        int idxIlp = -1;   // posición del ILP, para buscar ubicación de forma estructural

        for (int i = 0; i < textos.size(); i++) {
            String t = textos.get(i);
            if (t == null || t.trim().isEmpty()) continue;
            String s = t.trim();
            String lower = s.toLowerCase(Locale.ROOT);

            // Detectar "Put away success".
            if (lower.contains("put away success")
                    || lower.contains("putaway success")
                    || lower.contains("put-away success")) {
                putAwaySuccess = true;
            }

            // Detectar botón "PUT AWAY".
            if (lower.equals("put away") || lower.equals("putaway")
                    || lower.equals("put-away")) {
                putAwayButton = true;
            }

            // v4.1: Task ID eliminado por completo — ni se extrae ni se usa.

            // Pallet Code (ILP). El SKU es el nodo que aparece JUSTO DESPUÉS.
            Matcher mp = PAT_PALLET.matcher(s);
            if (mp.find()) {
                palletCode = "ILP-" + mp.group(1);
                idxIlp = i;
                String skuSiguiente = buscarSkuDespuesDeIlp(textos, i);
                if (skuSiguiente != null) {
                    sku = skuSiguiente;
                }
                continue; // este nodo ya se consumió como ILP
            }

            // La ubicación se resuelve DESPUÉS del bucle con buscarUbicacion(),
            // que prioriza el valor junto a la etiqueta y evita confundirla con
            // el ILP / SKU / cantidad.

            // Cantidad (decimal).
            Matcher mq = PAT_QUANTITY.matcher(s);
            if (mq.find()) {
                try {
                    double q = Double.parseDouble(mq.group(1));
                    if (q > 0 && q < 99999) {
                        quantity = q;
                    }
                } catch (NumberFormatException ignored) { }
            }
        }

        // ── Ubicación: prioridad etiqueta → estructural (junto al SKU/ILP) → patrón ──
        location = buscarUbicacion(textos, idxIlp);

        // Fallbacks de SKU/cantidad por etiqueta (por si no se capturaron con regex).
        if (sku == null) {
            String skuLabel = valorEtiqueta(textos, "SKU", false);
            if (skuLabel != null && skuLabel.length() >= 4 && esCandidatoSku(skuLabel)) {
                sku = skuLabel;
            }
        }
        if (quantity <= 0) {
            String qtyLabel = valorEtiqueta(textos, "Qty", true);
            if (qtyLabel == null) qtyLabel = valorEtiqueta(textos, "Quantity", true);
            if (qtyLabel == null) qtyLabel = valorEtiqueta(textos, "CTNs", true);
            if (qtyLabel != null) {
                try {
                    quantity = Double.parseDouble(qtyLabel);
                } catch (NumberFormatException ignored) { }
            }
        }

        // ── Paso 2: Actualizar caché (los datos que aparecen) ──
        if (palletCode != null) cachedPalletCode = palletCode;
        if (sku != null) cachedSku = sku;
        if (quantity > 0) cachedQuantity = quantity;
        if (location != null) cachedLocation = location;

        // Si ya hay un pallet guardado esperando su ubicación y por fin
        // apareció una ubicación válida, completarla sin duplicar.
        if (location != null && pendingLocPalletCode != null) {
            completarUbicacionPendiente(location);
        }

        // ── Paso 3: SEÑAL — guardar cuando se confirma "Put away success" ──
        if (putAwaySuccess && cachedPalletCode != null) {
            guardarPalletSiNuevo();
        }
    }

    /**
     * Guarda el pallet cacheado si no se ha guardado antes.
     */
    private void guardarPalletSiNuevo() {
        if (cachedPalletCode == null) return;
        if (savedPalletCodes.contains(cachedPalletCode)) return;

        PalletRepository repo = new PalletRepository(this);

        // v4.0: SIEMPRE se agrupa por container secuencial simple
        // ("Container 1", "Container 2", ...). No hay Task ID.
        String containerParaGuardar = repo.getCurrentContainerCode();

        Pallet p = new Pallet(
                containerParaGuardar,
                cachedPalletCode,
                cachedSku != null ? cachedSku : "",
                cachedQuantity,
                cachedLocation != null ? cachedLocation : ""
        );

        boolean inserted = repo.insertPallet(p);

        if (inserted) {
            savedPalletCodes.add(cachedPalletCode);
            String guardado = cachedPalletCode;
            boolean sinUbicacion = (cachedLocation == null || cachedLocation.trim().isEmpty());
            Log.d(TAG, "✅\u00A0Pallet\u00A0guardado:\u00A0" + guardado
                    + "\u00A0container=" + containerParaGuardar
                    + "\u00A0qty=" + cachedQuantity
                    + "\u00A0loc=" + cachedLocation);

            // Notificar a la UI (MainActivity).
            notificarPalletGuardado();

            // Limpiar caché para el siguiente pallet.
            cachedPalletCode = null;
            cachedSku = null;
            cachedQuantity = 0.0;
            cachedLocation = null;
            // v4.0: el container actual se mantiene hasta que el usuario inicie uno nuevo.

            // v4.4: si se guardó SIN ubicación, dejar diagnóstico y reintentar leerla.
            if (sinUbicacion) {
                volcarDiagnosticoSinUbicacion(guardado);
                iniciarReintentosUbicacion(guardado);
            }
        }
    }

    /** Avisa a MainActivity y al overlay flotante de que cambió algo. */
    private void notificarPalletGuardado() {
        try {
            Intent broadcast = new Intent(ACTION_PALLET_SAVED);
            broadcast.setPackage(getPackageName());
            sendBroadcast(broadcast);
        } catch (Exception ignored) { }
        try {
            Intent fabUpdate = new Intent(FloatingButtonService.ACTION_UPDATE_FAB);
            fabUpdate.setPackage(getPackageName());
            sendBroadcast(fabUpdate);
        } catch (Exception ignored) { }
    }

    // =============================================================
    //       REINTENTOS: COMPLETAR UBICACIÓN DE UN PALLET YA GUARDADO
    // =============================================================

    /**
     * Marca un pallet como "pendiente de ubicación" y programa varios reintentos
     * durante ~1.5 s. En cada reintento se vuelve a leer TODA la pantalla (todas
     * las ventanas) por si la ubicación aparece un instante después del guardado.
     */
    private void iniciarReintentosUbicacion(String palletCode) {
        pendingLocPalletCode = palletCode;
        locRetries = 0;
        if (locRunnable != null) locHandler.removeCallbacks(locRunnable);
        locRunnable = new Runnable() {
            @Override
            public void run() {
                if (pendingLocPalletCode == null) return;
                locRetries++;

                String loc = null;
                try {
                    List<String> textos = recolectarTodo(getRootInActiveWindow());
                    loc = buscarUbicacion(textos, -1);
                } catch (Exception ignored) { }

                if (loc != null) {
                    completarUbicacionPendiente(loc);
                    return;
                }
                if (locRetries < MAX_LOC_RETRIES) {
                    locHandler.postDelayed(this, LOC_RETRY_MS);
                } else {
                    // Se agotaron los reintentos: queda "Sin ubicación".
                    pendingLocPalletCode = null;
                }
            }
        };
        locHandler.postDelayed(locRunnable, LOC_RETRY_MS);
    }

    /**
     * Rellena la ubicación del pallet pendiente SIN duplicarlo. Usa
     * PalletRepository.updatePalletLocation, que solo escribe si estaba vacía.
     */
    private void completarUbicacionPendiente(String location) {
        String code = pendingLocPalletCode;
        if (code == null || location == null || location.trim().isEmpty()) return;
        pendingLocPalletCode = null;
        if (locRunnable != null) locHandler.removeCallbacks(locRunnable);
        try {
            PalletRepository repo = new PalletRepository(this);
            boolean ok = repo.updatePalletLocation(code, location.trim());
            if (ok) {
                Log.d(TAG, "📍\u00A0Ubicación\u00A0completada:\u00A0" + code + "\u00A0→\u00A0" + location);
                notificarPalletGuardado();
            }
        } catch (Exception ignored) { }
    }

    /**
     * Vuelca al registro (crash_log / pestaña Errores) TODOS los textos leídos,
     * en orden, para entender por qué no se detectó la ubicación.
     */
    private void volcarDiagnosticoSinUbicacion(String palletCode) {
        try {
            List<String> textos = recolectarTodoConIds(getRootInActiveWindow());
            StringBuilder sb = new StringBuilder();
            sb.append("Pallet guardado SIN ubicación: ").append(palletCode).append('\n');
            sb.append("Textos leídos de pantalla (text / contentDescription / hint):\n");
            int n = 0;
            for (String t : textos) {
                if (t == null || t.isEmpty()) continue;
                sb.append(String.format(Locale.ROOT, "[%02d] ", n++)).append(t).append('\n');
                if (n >= 150) { sb.append("...(recortado)\n"); break; }
            }
            PalletTrackerApp.logEvento(this, "Ubicación no detectada", sb.toString());
        } catch (Exception ignored) { }
    }

    // =============================================================
    //                  SISTEMA DE DIAGNÓSTICO
    // =============================================================

    private void guardarDiagnostico(DiagnosticoRepository repo, List<String> textos) {
        StringBuilder sb = new StringBuilder();
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT);
        sb.append("=== DIAGNÓSTICO ").append(fmt.format(new java.util.Date())).append(" ===\n");
        sb.append("--- Textos en pantalla (").append(textos.size()).append(") ---\n");
        int n = 0;
        for (String t : textos) {
            if (t == null || t.isEmpty()) continue;
            sb.append(String.format(Locale.ROOT, "[%02d]\u00A0", n++)).append(t).append('\n');
            if (n >= 120) { sb.append("...(recortado)\n"); break; }
        }

        // Agregar datos cacheados para debug.
        sb.append("--- Cache ---\n");
        sb.append("Container:\u00A0").append(new PalletRepository(this).getCurrentContainerCode()).append('\n');
        sb.append("Pallet:\u00A0").append(cachedPalletCode).append('\n');
        sb.append("SKU:\u00A0").append(cachedSku).append('\n');
        sb.append("Qty:\u00A0").append(cachedQuantity).append('\n');
        sb.append("Loc:\u00A0").append(cachedLocation).append('\n');

        try {
            repo.guardarDump(sb.toString());
        } catch (Exception ignored) { }
    }

    // =============================================================
    //                 HELPERS DE LECTURA
    // =============================================================

    private void recolectarTextos(AccessibilityNodeInfo node, List<String> out, int prof) {
        if (node == null || prof > MAX_PROFUNDIDAD || out.size() > MAX_TEXTOS) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) out.add(t.toString().trim());
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.length() > 0) out.add(cd.toString().trim());
        // v4.4: también el "hint" (texto de ayuda del campo), API 26+.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CharSequence hint = node.getHintText();
                if (hint != null && hint.length() > 0) out.add(hint.toString().trim());
            }
        } catch (Throwable ignored) { }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            recolectarTextos(node.getChild(i), out, prof + 1);
        }
    }

    /**
     * v4.4: Recolecta los textos de la ventana activa Y de TODAS las demás
     * ventanas accesibles (getWindows). Algunos WMS pintan la ubicación en un
     * panel/overlay distinto de la ventana con foco.
     */
    private List<String> recolectarTodo(AccessibilityNodeInfo activeRoot) {
        List<String> textos = new ArrayList<>();
        if (activeRoot != null) {
            try { recolectarTextos(activeRoot, textos, 0); } catch (Exception ignored) { }
        }
        try {
            List<AccessibilityWindowInfo> ventanas = getWindows();
            if (ventanas != null) {
                for (AccessibilityWindowInfo w : ventanas) {
                    if (w == null) continue;
                    AccessibilityNodeInfo r = null;
                    try { r = w.getRoot(); } catch (Exception ignored) { }
                    if (r == null) continue;
                    // Saltar nuestra propia app (overlay flotante).
                    try {
                        CharSequence pkg = r.getPackageName();
                        if (pkg != null && getPackageName().contentEquals(pkg)) continue;
                    } catch (Exception ignored) { }
                    try { recolectarTextos(r, textos, 0); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return textos;
    }

    /**
     * Igual que recolectarTodo pero añadiendo el viewId de cada nodo, para el
     * volcado de diagnóstico (ayuda a identificar el campo de la ubicación).
     */
    private List<String> recolectarTodoConIds(AccessibilityNodeInfo activeRoot) {
        List<String> out = new ArrayList<>();
        if (activeRoot != null) {
            try { recolectarConIds(activeRoot, out, 0); } catch (Exception ignored) { }
        }
        try {
            List<AccessibilityWindowInfo> ventanas = getWindows();
            if (ventanas != null) {
                for (AccessibilityWindowInfo w : ventanas) {
                    if (w == null) continue;
                    AccessibilityNodeInfo r = null;
                    try { r = w.getRoot(); } catch (Exception ignored) { }
                    if (r == null) continue;
                    try {
                        CharSequence pkg = r.getPackageName();
                        if (pkg != null && getPackageName().contentEquals(pkg)) continue;
                    } catch (Exception ignored) { }
                    try { recolectarConIds(r, out, 0); } catch (Exception ignored) { }
                }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private void recolectarConIds(AccessibilityNodeInfo node, List<String> out, int prof) {
        if (node == null || prof > MAX_PROFUNDIDAD || out.size() > MAX_TEXTOS) return;
        String vid = null;
        try { vid = node.getViewIdResourceName(); } catch (Throwable ignored) { }
        String idTag = (vid != null && !vid.isEmpty())
                ? " {" + vid.substring(vid.indexOf('/') + 1) + "}" : "";
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) out.add(t.toString().trim() + idTag);
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.length() > 0) out.add("(desc) " + cd.toString().trim() + idTag);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CharSequence hint = node.getHintText();
                if (hint != null && hint.length() > 0) out.add("(hint) " + hint.toString().trim() + idTag);
            }
        } catch (Throwable ignored) { }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            recolectarConIds(node.getChild(i), out, prof + 1);
        }
    }

    // =============================================================
    //            DETECCIÓN DE UBICACIÓN (v4.4, robusta)
    // =============================================================

    /**
     * Resuelve la ubicación con esta prioridad:
     *   1) Valor junto a una etiqueta conocida (Location / To Location / Dest...).
     *   2) Estructural: el texto que aparece justo después del SKU/ILP.
     *   3) Patrón: primer texto de la pantalla que parezca una ubicación.
     * Nunca devuelve un ILP, un SKU ni una cantidad.
     */
    private String buscarUbicacion(List<String> textos, int idxIlp) {
        if (textos == null || textos.isEmpty()) return null;

        // 1) Por etiqueta.
        for (String etiqueta : ETIQUETAS_UBICACION) {
            String val = valorEtiqueta(textos, etiqueta, false);
            if (val != null) {
                String loc = extraerUbicacionDeTexto(val);
                if (loc != null) return loc;
            }
        }

        // 2) Estructural: unos pocos nodos después del ILP.
        if (idxIlp >= 0) {
            for (int j = idxIlp + 1; j < textos.size() && j <= idxIlp + 6; j++) {
                String s = textos.get(j) == null ? "" : textos.get(j).trim();
                if (s.isEmpty()) continue;
                String loc = extraerUbicacionDeTexto(s);
                if (loc != null) return loc;
            }
        }

        // 3) Patrón: recorrer toda la pantalla.
        for (String s : textos) {
            if (s == null || s.trim().isEmpty()) continue;
            String loc = extraerUbicacionDeTexto(s.trim());
            if (loc != null) return loc;
        }
        return null;
    }

    /**
     * Intenta extraer una ubicación de un texto. Devuelve la ubicación
     * normalizada o null si no hay nada que parezca una ubicación válida.
     */
    private static String extraerUbicacionDeTexto(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;

        // No confundir con un ILP ni con un "TASK-...".
        if (t.toUpperCase(Locale.ROOT).startsWith("TASK")) return null;
        if (PAT_PALLET.matcher(t).find()) return null;

        // Ubicaciones con nombre: DOCK-01, STAGE 3...
        Matcher mn = PAT_LOCATION_NAMED.matcher(t);
        if (mn.find()) {
            return mn.group().replaceAll("\\s+", "\u00A0").toUpperCase(Locale.ROOT);
        }

        // Ubicaciones por segmentos: B03-033-4, A1-12-3...
        Matcher ml = PAT_LOCATION.matcher(t.toUpperCase(Locale.ROOT));
        while (ml.find()) {
            String cand = ml.group();
            if (esUbicacionValida(cand)) return cand;
        }
        return null;
    }

    /**
     * Valida un candidato por segmentos para que no sea una cantidad, un
     * teléfono ni un decimal. Debe tener al menos 2 segmentos y, o bien alguna
     * letra, o bien un segmento con 3+ dígitos (típico de un rack: 033).
     */
    private static boolean esUbicacionValida(String cand) {
        if (cand == null) return false;
        String c = cand.trim();
        if (c.length() < 3) return false;
        // Un decimal puro (32.5) no es ubicación.
        if (c.matches("\\d+\\.\\d+")) return false;
        String[] segs = c.split("[-.]");
        if (segs.length < 2) return false;
        boolean tieneLetra = c.matches(".*[A-Za-z].*");
        boolean segLargo = false;
        for (String seg : segs) {
            if (seg.replaceAll("[^0-9]", "").length() >= 3) segLargo = true;
        }
        return tieneLetra || segLargo;
    }

    private String extraerTextoEvento(AccessibilityEvent event) {
        List<CharSequence> partes = event.getText();
        if (partes != null && !partes.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (CharSequence cs : partes) {
                if (cs != null) sb.append(cs).append(' ');
            }
            String s = sb.toString().trim();
            if (!s.isEmpty()) return s;
        }
        if (event.getContentDescription() != null) {
            return event.getContentDescription().toString().trim();
        }
        return null;
    }

    protected static boolean pantallaContiene(List<String> textos, String clave) {
        String claveNorm = clave.toLowerCase(Locale.ROOT).trim();
        for (String t : textos) {
            if (t != null && t.toLowerCase(Locale.ROOT).contains(claveNorm)) {
                return true;
            }
        }
        return false;
    }

    protected static String valorEtiqueta(List<String> textos, String etiqueta, boolean soloNumero) {
        String etNorm = etiqueta.toLowerCase(Locale.ROOT).trim();
        for (int i = 0; i < textos.size(); i++) {
            String s = textos.get(i) == null ? "" : textos.get(i).trim();
            if (s.isEmpty()) continue;
            String norm = s.toLowerCase(Locale.ROOT).replaceAll("[*:#]", "").trim();

            if (norm.equals(etNorm)) {
                for (int j = i + 1; j < textos.size() && j <= i + 3; j++) {
                    String sig = textos.get(j) == null ? "" : textos.get(j).trim();
                    if (sig.isEmpty()) continue;
                    return soloNumero ? extraerPrimerNumeroDecimal(sig) : sig;
                }
            } else if (norm.startsWith(etNorm) && norm.length() > etNorm.length()
                    && !Character.isLetterOrDigit(norm.charAt(etNorm.length()))) {
                String resto = s.substring(etiqueta.length())
                        .replaceFirst("^[\\s:#.\\-=]+", "").trim();
                if (!resto.isEmpty()) {
                    return soloNumero ? extraerPrimerNumeroDecimal(resto) : resto;
                }
            }
        }
        return null;
    }

    private static String extraerPrimerNumeroDecimal(String s) {
        if (s == null || s.isEmpty()) return null;
        Matcher m = Pattern.compile("(\\d{1,7}(?:\\.\\d{1,2})?)").matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }

    protected static String normalizar(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[*:#]", "").trim();
    }

    // =============================================================
    //            SKU = NODO JUSTO DESPUÉS DEL ILP (v4.1)
    // =============================================================

    /**
     * v4.1: Busca el SKU como el primer nodo de texto significativo que
     * aparece DESPUÉS del nodo del ILP en la lista leída de la pantalla.
     * Se descartan los nodos que claramente NO son SKU (otro ILP, una
     * ubicación, una cantidad pura o cualquier texto "TASK-...").
     */
    private static String buscarSkuDespuesDeIlp(List<String> textos, int idxIlp) {
        for (int j = idxIlp + 1; j < textos.size() && j <= idxIlp + 4; j++) {
            String sig = textos.get(j) == null ? "" : textos.get(j).trim();
            if (sig.isEmpty()) continue;
            if (esCandidatoSku(sig)) return sig;
        }
        return null;
    }

    /**
     * Decide si un texto puede ser un SKU válido. Nunca acepta "TASK-...",
     * ni otro ILP, ni una ubicación, ni un número puro (cantidad).
     */
    private static boolean esCandidatoSku(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() < 2) return false;
        String up = t.toUpperCase(Locale.ROOT);
        if (up.startsWith("TASK")) return false;             // nunca el Task
        if (PAT_PALLET.matcher(t).find()) return false;       // no es otro ILP
        if (extraerUbicacionDeTexto(t) != null) return false; // no es ubicación
        if (t.matches("\\d{1,7}(\\.\\d{1,2})?")) return false; // no es cantidad pura
        return true;
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            if (locRunnable != null) locHandler.removeCallbacks(locRunnable);
        } catch (Exception ignored) { }
        pendingLocPalletCode = null;
        return super.onUnbind(intent);
    }
}
