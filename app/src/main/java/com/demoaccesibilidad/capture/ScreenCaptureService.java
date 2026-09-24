package com.demoaccesibilidad.capture;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

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
 *  - Task IDs (TASK-XXXX)
 *  - Pallet Codes (ILP-XXXXX)
 *  - SKUs (códigos alfanuméricos)
 *  - Cantidades (decimales)
 *  - Ubicaciones (formato B02-133-3)
 *
 * Patrón "Señal vs Dato":
 *  → Los DATOS se cachean conforme aparecen en pantalla.
 *  → La SEÑAL "Put away success" confirma y guarda el registro.
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
    private String cachedTaskId = null;
    private String cachedPalletCode = null;
    private String cachedSku = null;
    private double cachedQuantity = 0.0;
    private String cachedLocation = null;

    // Pallets ya guardados (evita duplicados en la sesión).
    private final Set<String> savedPalletCodes = new HashSet<>();

    // ====================== PATRONES REGEX ======================
    private static final Pattern PAT_TASK =
            Pattern.compile("TASK[-\\s]?(\\d{3,6})");
    private static final Pattern PAT_PALLET =
            Pattern.compile("ILP[-\\s]?(\\d{4,8})");
    private static final Pattern PAT_LOCATION =
            Pattern.compile("[A-Z]\\d{1,2}-\\d{2,3}-\\d{1,2}");
    private static final Pattern PAT_QUANTITY =
            Pattern.compile("(\\d{1,5}\\.\\d{1,2})");
    private static final Pattern PAT_SKU =
            Pattern.compile("[A-Z]{2,}[_-][A-Z0-9]{4,}");

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
        if (root == null) return;

        boolean esAppPropia = false;
        try {
            CharSequence pkg = root.getPackageName();
            esAppPropia = pkg != null && getPackageName().contentEquals(pkg);
        } catch (Exception ignored) { }

        List<String> textos = new ArrayList<>();
        try {
            recolectarTextos(root, textos, 0);
        } catch (Exception ignored) { }
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
        String taskId = null;
        String palletCode = null;
        String sku = null;
        double quantity = 0.0;
        String location = null;
        boolean putAwaySuccess = false;
        boolean putAwayButton = false;

        for (String t : textos) {
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

            // Task ID.
            Matcher mt = PAT_TASK.matcher(s);
            if (mt.find()) {
                taskId = "TASK-" + mt.group(1);
            }

            // Pallet Code.
            Matcher mp = PAT_PALLET.matcher(s);
            if (mp.find()) {
                palletCode = "ILP-" + mp.group(1);
            }

            // SKU — patrón alfanumérico con guion bajo/medio.
            Matcher ms = PAT_SKU.matcher(s);
            if (ms.find()) {
                sku = ms.group();
            }

            // Ubicación.
            Matcher ml = PAT_LOCATION.matcher(s);
            if (ml.find()) {
                location = ml.group();
            }

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

        // ── Paso 2: Actualizar caché (los datos que aparecen) ──
        if (taskId != null) cachedTaskId = taskId;
        if (palletCode != null) cachedPalletCode = palletCode;
        if (sku != null) cachedSku = sku;
        if (quantity > 0) cachedQuantity = quantity;
        if (location != null) cachedLocation = location;

        // ── Paso 3: SEÑAL — guardar cuando se confirma "Put away success" ──
        if (putAwaySuccess && cachedPalletCode != null) {
            guardarPalletSiNuevo();
        }

        // También intentar valorEtiqueta para campos que pudieran
        // no haberse capturado con regex (etiquetas del WMS).
        if (cachedSku == null) {
            String skuLabel = valorEtiqueta(textos, "SKU", false);
            if (skuLabel != null && skuLabel.length() >= 4) {
                cachedSku = skuLabel;
            }
        }
        if (cachedQuantity <= 0) {
            String qtyLabel = valorEtiqueta(textos, "Qty", true);
            if (qtyLabel == null) qtyLabel = valorEtiqueta(textos, "Quantity", true);
            if (qtyLabel == null) qtyLabel = valorEtiqueta(textos, "CTNs", true);
            if (qtyLabel != null) {
                try {
                    cachedQuantity = Double.parseDouble(qtyLabel);
                } catch (NumberFormatException ignored) { }
            }
        }
        if (cachedLocation == null) {
            String locLabel = valorEtiqueta(textos, "Location", false);
            if (locLabel == null) locLabel = valorEtiqueta(textos, "Loc", false);
            if (locLabel != null) {
                Matcher ml2 = PAT_LOCATION.matcher(locLabel);
                if (ml2.find()) cachedLocation = ml2.group();
            }
        }
    }

    /**
     * Guarda el pallet cacheado si no se ha guardado antes.
     */
    private void guardarPalletSiNuevo() {
        if (cachedPalletCode == null) return;
        if (savedPalletCodes.contains(cachedPalletCode)) return;

        PalletRepository repo = new PalletRepository(this);

        // v3.2: La pantalla de Put away del WMS NO muestra un Task ID. Si no
        // logramos extraer uno real, usamos un código de container secuencial
        // simple ("Container 1", "Container 2", ...) como identificador de
        // agrupación. Así el detalle y el conteo por container funcionan.
        String taskParaGuardar = (cachedTaskId != null && !cachedTaskId.isEmpty())
                ? cachedTaskId
                : repo.getCurrentContainerCode();

        Pallet p = new Pallet(
                taskParaGuardar,
                cachedPalletCode,
                cachedSku != null ? cachedSku : "",
                cachedQuantity,
                cachedLocation != null ? cachedLocation : ""
        );

        boolean inserted = repo.insertPallet(p);

        if (inserted) {
            savedPalletCodes.add(cachedPalletCode);
            Log.d(TAG, "✅\u00A0Pallet\u00A0guardado:\u00A0" + cachedPalletCode
                    + "\u00A0task=" + taskParaGuardar
                    + "\u00A0qty=" + cachedQuantity);

            // Notificar a la UI (MainActivity).
            try {
                Intent broadcast = new Intent(ACTION_PALLET_SAVED);
                broadcast.setPackage(getPackageName());
                sendBroadcast(broadcast);
            } catch (Exception ignored) { }

            // Notificar al overlay flotante.
            try {
                Intent fabUpdate = new Intent(FloatingButtonService.ACTION_UPDATE_FAB);
                fabUpdate.setPackage(getPackageName());
                sendBroadcast(fabUpdate);
            } catch (Exception ignored) { }

            // Limpiar caché para el siguiente pallet.
            cachedPalletCode = null;
            cachedSku = null;
            cachedQuantity = 0.0;
            cachedLocation = null;
            // cachedTaskId se mantiene (el task suele ser el mismo).
        }
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
        sb.append("Task:\u00A0").append(cachedTaskId).append('\n');
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
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            recolectarTextos(node.getChild(i), out, prof + 1);
        }
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

    @Override
    public void onInterrupt() { }
}
