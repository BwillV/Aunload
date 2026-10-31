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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    // ====================== INSTANCIA (para "Copiar texto") ======================
    // Referencia a la instancia viva del servicio de accesibilidad. La función
    // "Copiar texto" la usa para leer los textos de la pantalla del WMS desde
    // el botón flotante. Es el único punto con acceso a getRootInActiveWindow().
    private static volatile ScreenCaptureService instance;

    public static ScreenCaptureService getInstance() {
        return instance;
    }

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
    // v5.3: la clave ahora es "container|ILP" (antes solo el ILP). Así el MISMO
    // ILP puesto en un container distinto SÍ se cuenta y no se descarta.
    // Thread-safe: se lee en el hilo principal y se modifica desde ioExecutor.
    private final Set<String> savedPalletCodes =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    // v5.3: ejecutor único para TODA la E/S de disco/Firebase del guardado de
    // pallets. El guardado antes se hacía en el hilo de accesibilidad (main):
    // leer+serializar hasta 2000 pallets en JSON y escribir SharedPreferences en
    // cada "Put away success" congelaba la pantalla (ANR / "app bugueada"). Con
    // un solo hilo las escrituras quedan SERIALIZADAS (sin condiciones de
    // carrera) y fuera del hilo principal.
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // v5.3: claves ILP↔ubicación ya aplicadas, para no re-parsear todos los
    // pallets en cada lectura de pantalla (ahorro de CPU) ni repetir trabajo.
    private final Set<String> appliedLocationKeys =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private long lastMapApply = 0L;
    private static final long MAP_APPLY_MIN_MS = 1500;

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
    // v4.6: ventana de reintentos AMPLIADA. Se sondea la pantalla hasta que
    // aparezca la ubicación o llegue el siguiente ILP, con tope de 60 s.
    private long locDeadline = 0L;
    private static final long LOC_WINDOW_MS = 60000;  // 60 s máximo
    private static final long LOC_RETRY_MS = 500;     // sondeo cada 0.5 s

    // ====================== UBICACIÓN RECIENTE (ESCANEO/TEXTO) ======================
    // v4.6: cuando la ubicación se escanea/escribe en un campo ANTES de que la
    // pantalla "Put away success" la muestre, se guarda aquí con su timestamp y
    // se usa para la pallet en curso. No se limpia hasta guardar la pallet o
    // hasta que llega un ILP distinto.
    private String recentLocation = null;
    private long recentLocationTs = 0L;
    private static final long RECENT_LOC_TTL_MS = 120000; // ~2 min

    // ====================== MAPA ILP → UBICACIÓN ======================
    // v4.6: se construye desde CUALQUIER pantalla del WMS donde aparezcan juntos
    // un ILP y una ubicación (incluida "Progress" → "Location View", que lista
    // filas ubicación + ILP). Permite completar pallets de hoy sin ubicación.
    private final Map<String, String> ilpLocationMap = new HashMap<>();
    private static final int MAX_ILP_MAP = 500;

    // ====================== v4.7 — INSTANTÁNEA / CAMPO "PUT AWAY" ======================
    // En la pantalla de put away del WMS la ubicación SIEMPRE está en el campo
    // inferior (junto a GET SUGGESTION) ANTES de tocar PUT AWAY, pero desaparece
    // después (la pantalla "Put away success" no la muestra). v4.7 la captura de
    // dos maneras complementarias:
    //  1) INSTANTÁNEA: al detectar el clic en el botón PUT AWAY se lee la ventana
    //     de inmediato y se toma la ubicación del campo (máxima prioridad para la
    //     pallet que confirme a continuación).
    //  2) CACHÉ CONTINUA: mientras se vea el botón PUT AWAY o GET SUGGESTION se
    //     cachea el valor del campo con timestamp (TTL 5 min). Tiene prioridad
    //     sobre la "ubicación reciente" de v4.6.
    private String putAwaySnapshotLocation = null;
    private String putAwaySnapshotIlp = null;
    private long putAwaySnapshotTs = 0L;
    private static final long PUTAWAY_SNAPSHOT_TTL_MS = 120000; // ~2 min
    private String putAwayFieldLocation = null;
    private long putAwayFieldTs = 0L;
    private static final long PUTAWAY_FIELD_TTL_MS = 300000; // 5 min

    // ====================== v4.7 — DEBOUNCE CON LECTURA FINAL ======================
    // Antes, el throttle (INTERVALO_MIN_MS) DESCARTABA eventos dentro de la
    // ventana y se podía perder el ÚLTIMO estado de pantalla. Ahora hacemos una
    // lectura inmediata (leading) y, además, programamos una lectura final
    // (trailing) cuando cesan los eventos, para no perder nunca el último estado.
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceRunnable = null;
    private static final long DEBOUNCE_MS = 300;
    // v4.1: PAT_SKU por regex ELIMINADO. Causaba el bug: "[A-Z]{2,}[_-][A-Z0-9]{4,}"
    // coincidía con "TASK-2693" (TASK + '-' + 2693) y lo guardaba como SKU.
    // Ahora el SKU es el nodo de texto que aparece JUSTO DESPUÉS del ILP.

    // =============================================================
    //                    PUNTO DE ENTRADA
    // =============================================================

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
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

        // v4.7: clic/enfoque. Si es el botón PUT AWAY, tomar la INSTANTÁNEA de la
        // ubicación de inmediato (sin demora), porque justo después la pantalla
        // "Put away success" ya no la mostrará.
        if (tipo == AccessibilityEvent.TYPE_VIEW_CLICKED
                || tipo == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            if (esEventoPutAway(event)) {
                tomarInstantaneaPutAway();
            }
        }

        // v4.7: debounce con lectura final. Para cualquier evento que cambie la
        // pantalla hacemos una lectura inmediata (si pasó el intervalo) y SIEMPRE
        // programamos una lectura final cuando cesen los eventos, de modo que el
        // último estado nunca se pierda.
        if (tipo == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || tipo == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || tipo == AccessibilityEvent.TYPE_VIEW_CLICKED
                || tipo == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            programarLecturaDebounce();
        }
    }

    // =============================================================
    //        v4.7 — DEBOUNCE CON LECTURA FINAL (TRAILING EDGE)
    // =============================================================

    private void programarLecturaDebounce() {
        long ahora = System.currentTimeMillis();
        // Leading edge: lectura inmediata si ya pasó el intervalo mínimo.
        if (ahora - ultimaLectura > INTERVALO_MIN_MS) {
            ultimaLectura = ahora;
            leerPantallaCompleta();
        }
        // Trailing edge: asegurar la lectura del ÚLTIMO estado al cesar eventos.
        if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        debounceRunnable = () -> {
            ultimaLectura = System.currentTimeMillis();
            leerPantallaCompleta();
        };
        debounceHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
    }

    // =============================================================
    //        v4.7 — INSTANTÁNEA AL PULSAR "PUT AWAY"
    // =============================================================

    /** ¿El evento procede del botón PUT AWAY? (texto, descripción o nodo origen). */
    private boolean esEventoPutAway(AccessibilityEvent event) {
        if (esTextoPutAway(extraerTextoEvento(event))) return true;
        try {
            AccessibilityNodeInfo src = event.getSource();
            if (src != null) {
                CharSequence t = src.getText();
                if (t != null && esTextoPutAway(t.toString())) return true;
                CharSequence cd = src.getContentDescription();
                if (cd != null && esTextoPutAway(cd.toString())) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** "PUT AWAY" / "Put-away" / "putaway" (sin importar mayúsculas), nunca "success". */
    private static boolean esTextoPutAway(String s) {
        if (s == null) return false;
        String compact = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (compact.isEmpty()) return false;
        if (compact.contains("success")) return false;
        return compact.equals("putaway");
    }

    /**
     * Lee la ventana de inmediato y toma la ubicación del campo de put away (y el
     * ILP visible, si lo hay). Se guarda como instantánea con MÁXIMA prioridad
     * para la siguiente pallet confirmada. Si ya hay una pallet pendiente, la
     * completa en el acto.
     */
    private void tomarInstantaneaPutAway() {
        try {
            List<String> textos = recolectarTodo(getRootInActiveWindow());
            if (textos.isEmpty()) return;
            String loc = buscarUbicacion(textos, -1);
            construirMapaDesdeTextos(textos);
            String ilp = null;
            for (String s : textos) {
                if (s == null) continue;
                Matcher mp = PAT_PALLET.matcher(s);
                if (mp.find()) { ilp = "ILP-" + mp.group(1); break; }
            }
            if (loc == null) return;
            putAwaySnapshotLocation = loc;
            putAwaySnapshotIlp = ilp;
            putAwaySnapshotTs = System.currentTimeMillis();
            // Alimenta también la caché del campo y la ubicación reciente.
            putAwayFieldLocation = loc;
            putAwayFieldTs = System.currentTimeMillis();
            recordRecentLocation(loc);
            if (ilp != null) registrarParIlpUbicacion(ilp, loc);
            PalletTrackerApp.logEvento(this, "Ubicación capturada",
                    "Instantánea\u00A0PUT\u00A0AWAY\u00A0→\u00A0" + loc
                            + (ilp != null ? "\u00A0(" + ilp + ")" : ""));
            // Si hay una pallet esperando ubicación, completarla ya mismo.
            if (pendingLocPalletCode != null) {
                completarUbicacionPendiente(loc, "instantánea PUT AWAY");
            }
        } catch (Exception ignored) { }
    }

    /** Instantánea PUT AWAY válida (TTL) y, si tiene ILP, que coincida con el pallet. */
    private String getPutAwaySnapshotValida(String palletCode) {
        if (putAwaySnapshotLocation == null) return null;
        if (System.currentTimeMillis() - putAwaySnapshotTs > PUTAWAY_SNAPSHOT_TTL_MS) return null;
        if (putAwaySnapshotIlp != null && palletCode != null
                && !putAwaySnapshotIlp.equals(palletCode)) return null;
        return putAwaySnapshotLocation;
    }

    /** Valor cacheado del campo de put away si no ha caducado (5 min). */
    private String getPutAwayFieldValida() {
        if (putAwayFieldLocation == null) return null;
        if (System.currentTimeMillis() - putAwayFieldTs > PUTAWAY_FIELD_TTL_MS) return null;
        return putAwayFieldLocation;
    }

    /** Limpia la instantánea y la caché del campo (tras guardar usándolas). */
    private void limpiarCachePutAway() {
        putAwaySnapshotLocation = null;
        putAwaySnapshotIlp = null;
        putAwaySnapshotTs = 0L;
        putAwayFieldLocation = null;
        putAwayFieldTs = 0L;
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
            String nuevo = "ILP-" + m.group(1);
            onNuevoIlpDetectado(nuevo);
            cachedPalletCode = nuevo;
            Log.d(TAG, "Pallet\u00A0escaneado:\u00A0" + cachedPalletCode);
        }

        // v4.6: la ubicación también puede escanearse/escribirse en un campo
        // (EditText) ANTES de que aparezca en la pantalla "Put away success".
        String loc = extraerUbicacionDeTexto(texto);
        if (loc != null) {
            recordRecentLocation(loc);
            if (cachedPalletCode != null) registrarParIlpUbicacion(cachedPalletCode, loc);
            // Si hay un pallet esperando ubicación, completarlo de inmediato.
            if (pendingLocPalletCode != null) {
                completarUbicacionPendiente(loc, "escaneo");
            }
        }
    }

    // =============================================================
    //        v4.6 — UBICACIÓN RECIENTE Y MAPA ILP↔UBICACIÓN
    // =============================================================

    /** Guarda la última ubicación vista/escaneada con su timestamp. */
    private void recordRecentLocation(String loc) {
        if (loc == null || loc.trim().isEmpty()) return;
        recentLocation = loc.trim();
        recentLocationTs = System.currentTimeMillis();
    }

    /** Devuelve la ubicación reciente si aún no ha caducado (~2 min). */
    private String getRecentLocationValida() {
        if (recentLocation != null
                && System.currentTimeMillis() - recentLocationTs <= RECENT_LOC_TTL_MS) {
            return recentLocation;
        }
        return null;
    }

    /** Registra un par ILP↔ubicación en el mapa (con tope de tamaño). */
    private void registrarParIlpUbicacion(String ilp, String loc) {
        if (ilp == null || loc == null || loc.trim().isEmpty()) return;
        if (ilpLocationMap.size() > MAX_ILP_MAP) ilpLocationMap.clear();
        ilpLocationMap.put(ilp, loc.trim());
    }

    /**
     * Cuando aparece un ILP distinto al que teníamos en curso, cerramos la
     * ventana del pallet anterior pendiente de ubicación y descartamos la
     * ubicación reciente (ya pertenece a otro pallet).
     */
    private void onNuevoIlpDetectado(String nuevoIlp) {
        if (nuevoIlp == null) return;
        if (cachedPalletCode != null && !nuevoIlp.equals(cachedPalletCode)) {
            if (pendingLocPalletCode != null && !nuevoIlp.equals(pendingLocPalletCode)) {
                finalizarPendienteSinUbicacion();
            }
            recentLocation = null;
            recentLocationTs = 0L;
        }
    }

    /** Cierra la ventana de reintentos dejando el pallet "Sin ubicación". */
    private void finalizarPendienteSinUbicacion() {
        pendingLocPalletCode = null;
        if (locRunnable != null) locHandler.removeCallbacks(locRunnable);
    }

    /**
     * Construye el mapa ILP↔ubicación a partir de los textos de la pantalla.
     * Empareja por cercanía en el ORDEN DE LECTURA, considerando solo los nodos
     * que son ILP o ubicación. Para cada ILP se prefiere la ubicación
     * inmediatamente ANTERIOR (caso "Location View": fila ubicación → ILP) y, si
     * no la hay, la inmediatamente POSTERIOR (caso "Put away": ILP → ubicación).
     */
    private void construirMapaDesdeTextos(List<String> textos) {
        if (textos == null || textos.isEmpty()) return;
        List<String> tipos = new ArrayList<>();
        List<String> valores = new ArrayList<>();
        for (String s : textos) {
            if (s == null || s.trim().isEmpty()) continue;
            Matcher mp = PAT_PALLET.matcher(s);
            if (mp.find()) {
                tipos.add("ILP");
                valores.add("ILP-" + mp.group(1));
                continue;
            }
            String loc = extraerUbicacionDeTexto(s);
            if (loc != null) {
                tipos.add("LOC");
                valores.add(loc);
            }
        }
        for (int i = 0; i < tipos.size(); i++) {
            if (!"ILP".equals(tipos.get(i))) continue;
            String ilp = valores.get(i);
            String loc = null;
            for (int d = 1; d <= 3 && loc == null; d++) {
                if (i - d >= 0 && "LOC".equals(tipos.get(i - d))) {
                    loc = valores.get(i - d);
                } else if (i + d < tipos.size() && "LOC".equals(tipos.get(i + d))) {
                    loc = valores.get(i + d);
                }
            }
            if (loc != null) registrarParIlpUbicacion(ilp, loc);
        }
    }

    /**
     * Recorre el mapa ILP↔ubicación y completa las pallets de HOY que se
     * guardaron sin ubicación. Nunca pisa una ubicación ya existente.
     */
    private void aplicarMapaAPendientes() {
        if (ilpLocationMap.isEmpty()) return;

        // v5.3: esta rutina se llamaba en CADA lectura de pantalla (cada ~300 ms)
        // y, por cada entrada del mapa (hasta 500), leía+serializaba TODOS los
        // pallets en disco — en el hilo principal. Era una fuente grave de
        // "congelones". Ahora:
        //   • se limita la frecuencia (throttle),
        //   • se saltan las claves ya aplicadas (no re-parsear),
        //   • el trabajo de disco va al ejecutor de fondo.
        long ahora = System.currentTimeMillis();
        if (ahora - lastMapApply < MAP_APPLY_MIN_MS) return;
        lastMapApply = ahora;

        // Copia de las entradas aún NO aplicadas (se recorre en el hilo principal,
        // es solo lectura de memoria).
        final Map<String, String> pendientes = new HashMap<>();
        for (Map.Entry<String, String> e : ilpLocationMap.entrySet()) {
            String key = e.getKey() + "|" + e.getValue();
            if (!appliedLocationKeys.contains(key)) {
                pendientes.put(e.getKey(), e.getValue());
            }
        }
        if (pendientes.isEmpty()) return;

        ioExecutor.submit(() -> {
            PalletRepository repo = new PalletRepository(getApplicationContext());
            for (Map.Entry<String, String> e : pendientes.entrySet()) {
                boolean ok;
                try {
                    ok = repo.completarUbicacionSiHoy(e.getKey(), e.getValue());
                } catch (Throwable t) {
                    ok = false;
                }
                if (ok) {
                    // Solo marcamos como aplicada cuando SÍ se completó, para no
                    // perder la oportunidad de rellenar una pallet que aún no
                    // existía cuando se leyó la ubicación. El throttle evita que
                    // reintentar las no completadas sea costoso.
                    appliedLocationKeys.add(e.getKey() + "|" + e.getValue());
                    final String ilp = e.getKey();
                    final String loc = e.getValue();
                    mainHandler.post(() -> {
                        PalletTrackerApp.logEvento(this, "Ubicación completada",
                                "Pallet " + ilp + " → " + loc
                                        + " (fuente: pantalla Progress/Location View)");
                        notificarPalletGuardado();
                        if (ilp.equals(pendingLocPalletCode)) {
                            finalizarPendienteSinUbicacion();
                        }
                    });
                }
            }
        });
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
        // v4.6: si cambió el ILP en curso, cerrar ventana pendiente anterior.
        if (palletCode != null) {
            onNuevoIlpDetectado(palletCode);
            cachedPalletCode = palletCode;
        }
        if (sku != null) cachedSku = sku;
        if (quantity > 0) cachedQuantity = quantity;
        if (location != null) {
            cachedLocation = location;
            recordRecentLocation(location);
        }

        // v4.7: mientras se vea el botón PUT AWAY o GET SUGGESTION, el campo
        // inferior contiene la ubicación de destino aunque el "success" luego no
        // la muestre. Se cachea continuamente con su timestamp (TTL 5 min). Esta
        // caché tiene prioridad sobre la "ubicación reciente" de v4.6.
        boolean getSuggestionButton = pantallaContiene(textos, "get suggestion")
                || pantallaContiene(textos, "getsuggestion")
                || pantallaContiene(textos, "suggestion");
        if ((putAwayButton || getSuggestionButton) && location != null) {
            putAwayFieldLocation = location;
            putAwayFieldTs = System.currentTimeMillis();
        }

        // v4.6: construir el mapa ILP↔ubicación desde ESTA pantalla (sirve para
        // la pantalla "Progress"/"Location View") y completar pallets de hoy.
        construirMapaDesdeTextos(textos);
        aplicarMapaAPendientes();

        // Si ya hay un pallet guardado esperando su ubicación y por fin
        // apareció una ubicación válida, completarla sin duplicar.
        if (location != null && pendingLocPalletCode != null) {
            completarUbicacionPendiente(location, "pantalla");
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

        // v4.0: SIEMPRE se agrupa por container secuencial simple
        // ("Container 1", "Container 2", ...). No hay Task ID.
        // v5.2: con EQUIPO activo, la fuente de verdad del container es el
        // compartido de Firebase. Usamos su id directamente (mismo formato
        // "yyyy-MM-dd#N") para que el pallet se guarde SIEMPRE en el container
        // vigente del equipo, incluso si el listener aún no sincronizó el
        // puntero local. Así se elimina el bug de "sigue metiendo en el viejo".
        final PalletRepository repo = new PalletRepository(getApplicationContext());

        String containerTmp;
        try {
            FirebaseManager fm = FirebaseManager.getInstance(this);
            String sharedId = fm.getCurrentContainerId();
            if (fm.isTeamActive() && sharedId != null && !sharedId.isEmpty()) {
                containerTmp = sharedId;
            } else {
                containerTmp = repo.getCurrentContainerCode();
            }
        } catch (Throwable t) {
            containerTmp = repo.getCurrentContainerCode();
        }
        final String containerParaGuardar = containerTmp;

        // v5.3: dedupe en memoria por CONTAINER + ILP (no solo ILP). Evita que un
        // mismo ILP legítimo en otro container se descarte, y a la vez impide
        // doble inserción del mismo pallet mientras la escritura va en segundo
        // plano. La comprobación va DESPUÉS de conocer el container.
        final String dedupKey = containerParaGuardar + "|" + cachedPalletCode;
        if (savedPalletCodes.contains(dedupKey)) return;

        // Si el success NO mostró ubicación, intentar completarla antes de guardar
        // para que el pallet pueda nacer ya con su ubicación de destino.
        // Prioridad: (1) ubicación del propio "success"; (2) INSTANTÁNEA tomada al
        // pulsar PUT AWAY; (3) caché del campo de put away; (4) mapa ILP↔ubicación;
        // (5) escaneo reciente (v4.6).
        String locParaGuardar = cachedLocation;
        String fuenteLoc = null;
        if (locParaGuardar == null || locParaGuardar.trim().isEmpty()) {
            String snap = getPutAwaySnapshotValida(cachedPalletCode);
            if (snap != null && !snap.trim().isEmpty()) {
                locParaGuardar = snap;
                fuenteLoc = "instantánea PUT AWAY";
            }
        }
        if (locParaGuardar == null || locParaGuardar.trim().isEmpty()) {
            String campo = getPutAwayFieldValida();
            if (campo != null && !campo.trim().isEmpty()) {
                locParaGuardar = campo;
                fuenteLoc = "campo put away (caché)";
            }
        }
        if (locParaGuardar == null || locParaGuardar.trim().isEmpty()) {
            String mapLoc = ilpLocationMap.get(cachedPalletCode);
            if (mapLoc != null && !mapLoc.trim().isEmpty()) {
                locParaGuardar = mapLoc;
                fuenteLoc = "mapa ILP↔ubicación";
            }
        }
        if (locParaGuardar == null || locParaGuardar.trim().isEmpty()) {
            String recent = getRecentLocationValida();
            if (recent != null) {
                locParaGuardar = recent;
                fuenteLoc = "escaneo reciente";
            }
        }

        final Pallet p = new Pallet(
                containerParaGuardar,
                cachedPalletCode,
                cachedSku != null ? cachedSku : "",
                cachedQuantity,
                locParaGuardar != null ? locParaGuardar : ""
        );

        // Capturar en finales todo lo necesario ANTES de limpiar la caché.
        final String guardado = cachedPalletCode;
        final boolean sinUbicacion = (locParaGuardar == null || locParaGuardar.trim().isEmpty());
        final String locFinal = locParaGuardar;
        final String fuenteLocFinal = fuenteLoc;

        // v5.3: marca optimista en memoria para que eventos repetidos del mismo
        // "success" no reintenten mientras la escritura va en segundo plano.
        savedPalletCodes.add(dedupKey);

        // v5.3: limpiar la caché AHORA (ya guardamos todo en 'p'), en el hilo
        // principal, para no perder datos de la siguiente pallet y evitar
        // condiciones de carrera con el guardado en segundo plano.
        cachedPalletCode = null;
        cachedSku = null;
        cachedQuantity = 0.0;
        cachedLocation = null;
        recentLocation = null;
        recentLocationTs = 0L;
        limpiarCachePutAway();
        // v4.0: el container actual se mantiene hasta que el usuario inicie uno nuevo.

        // v5.3: la E/S pesada (leer+serializar hasta 2000 pallets y escribir
        // SharedPreferences + reportar a Firebase) se ejecuta en el ejecutor de
        // fondo para NO congelar la pantalla (antes era la causa principal de los
        // "congelones" / ANR). Las escrituras quedan serializadas en un solo hilo.
        ioExecutor.submit(() -> {
            boolean ok;
            try {
                ok = repo.insertPallet(p);
            } catch (Throwable t) {
                ok = false;
                // Nunca perder el pallet en silencio: dejar constancia y permitir
                // un reintento posterior quitando la marca optimista.
                PalletTrackerApp.logException(getApplicationContext(),
                        "guardarPalletSiNuevo.insertPallet", t);
                savedPalletCodes.remove(dedupKey);
            }
            final boolean inserted = ok;
            mainHandler.post(() -> {
                if (inserted) {
                    if (!sinUbicacion && fuenteLocFinal != null) {
                        PalletTrackerApp.logEvento(this, "Ubicación completada",
                                "Pallet " + guardado + " → "
                                        + (locFinal != null ? locFinal.trim() : "")
                                        + " (fuente: " + fuenteLocFinal + ")");
                    }
                    Log.d(TAG, "✅\u00A0Pallet\u00A0guardado:\u00A0" + guardado
                            + "\u00A0container=" + containerParaGuardar
                            + "\u00A0qty=" + p.quantity
                            + "\u00A0loc=" + (locFinal != null ? locFinal : ""));

                    // Notificar a la UI (MainActivity) y al overlay.
                    notificarPalletGuardado();

                    // v4.4: si se guardó SIN ubicación, diagnóstico y reintentos.
                    if (sinUbicacion) {
                        volcarDiagnosticoSinUbicacion(guardado);
                        iniciarReintentosUbicacion(guardado);
                    }
                }
            });
        });
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
     * Marca un pallet como "pendiente de ubicación" y programa reintentos hasta
     * 60 s (o hasta que llegue el siguiente ILP). En cada reintento se vuelve a
     * leer TODA la pantalla (todas las ventanas), se reconstruye el mapa
     * ILP↔ubicación y se revisa la ubicación reciente escaneada.
     */
    private void iniciarReintentosUbicacion(String palletCode) {
        pendingLocPalletCode = palletCode;
        // v4.6: ventana extendida hasta 60 s (o hasta que llegue el siguiente ILP).
        locDeadline = System.currentTimeMillis() + LOC_WINDOW_MS;
        if (locRunnable != null) locHandler.removeCallbacks(locRunnable);
        locRunnable = new Runnable() {
            @Override
            public void run() {
                if (pendingLocPalletCode == null) return;

                // 1) Intentar por lectura directa de la pantalla.
                String loc = null;
                try {
                    List<String> textos = recolectarTodo(getRootInActiveWindow());
                    construirMapaDesdeTextos(textos);
                    loc = buscarUbicacion(textos, -1);
                } catch (Exception ignored) { }

                String fuente = "reintento";
                // 2) Intentar por la INSTANTÁNEA tomada al pulsar PUT AWAY.
                if (loc == null) {
                    String snap = getPutAwaySnapshotValida(pendingLocPalletCode);
                    if (snap != null && !snap.trim().isEmpty()) {
                        loc = snap; fuente = "instantánea PUT AWAY";
                    }
                }
                // 3) Intentar por la caché del campo de put away.
                if (loc == null) {
                    String campo = getPutAwayFieldValida();
                    if (campo != null && !campo.trim().isEmpty()) {
                        loc = campo; fuente = "campo put away (caché)";
                    }
                }
                // 4) Intentar por el mapa ILP↔ubicación (p.ej. Location View).
                if (loc == null) {
                    String mapLoc = ilpLocationMap.get(pendingLocPalletCode);
                    if (mapLoc != null && !mapLoc.trim().isEmpty()) {
                        loc = mapLoc; fuente = "mapa ILP↔ubicación";
                    }
                }
                // 5) Intentar por la última ubicación escaneada reciente.
                if (loc == null) {
                    String recent = getRecentLocationValida();
                    if (recent != null) { loc = recent; fuente = "escaneo reciente"; }
                }

                if (loc != null) {
                    completarUbicacionPendiente(loc, fuente);
                    return;
                }
                if (System.currentTimeMillis() < locDeadline) {
                    locHandler.postDelayed(this, LOC_RETRY_MS);
                } else {
                    // Se agotó la ventana: queda "Sin ubicación".
                    pendingLocPalletCode = null;
                }
            }
        };
        locHandler.postDelayed(locRunnable, LOC_RETRY_MS);
    }

    /**
     * Rellena la ubicación del pallet pendiente SIN duplicarlo. Usa
     * PalletRepository.updatePalletLocation, que solo escribe si estaba vacía.
     * Registra en el diagnóstico la FUENTE por la que se completó.
     */
    private void completarUbicacionPendiente(String location, String fuente) {
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
                PalletTrackerApp.logEvento(this, "Ubicación completada",
                        "Pallet " + code + " → " + location.trim()
                                + " (fuente: " + (fuente != null ? fuente : "automática") + ")");
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

    // =============================================================
    //            "COPIAR TEXTO" — textos visibles del WMS
    // =============================================================

    /**
     * Devuelve TODOS los textos visibles de la ventana activa (y demás ventanas
     * accesibles), sin duplicados y en orden de pantalla, excluyendo los textos
     * de la propia app PalletTracker. Lo usa la función "Copiar texto" del botón
     * flotante para que el operario pueda copiar datos del WMS que no son
     * seleccionables dentro del propio WMS.
     */
    public java.util.ArrayList<String> getVisibleTextsForCopy() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        try {
            List<String> textos = recolectarTodo(getRootInActiveWindow());
            if (textos != null) {
                for (String s : textos) {
                    if (s == null) continue;
                    String t = s.trim();
                    if (!t.isEmpty()) set.add(t);
                }
            }
        } catch (Throwable ignored) { }
        return new java.util.ArrayList<>(set);
    }

    /**
     * Busca en la pantalla actual el primer texto que coincida con un ILP
     * (patrón ILP-#####). Devuelve "ILP-#####" normalizado o null si no hay.
     */
    public String findFirstIlpOnScreen() {
        try {
            for (String s : getVisibleTextsForCopy()) {
                Matcher m = PAT_PALLET.matcher(s);
                if (m.find()) return "ILP-" + m.group(1);
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // Patrón ESTRICTO de ubicación tipo "A03-198-2" / "B03-033-4" para la copia
    // directa (evita falsos positivos). Si no coincide, se cae al PAT_LOCATION
    // flexible que ya usa el servicio para extraer ubicaciones.
    private static final Pattern PAT_LOCATION_COPY =
            Pattern.compile("\\b[A-Z]\\d{2}-\\d{3}-\\d\\b");

    /**
     * Busca en la pantalla actual el primer texto que coincida con una ubicación.
     * Prefiere el formato estricto (A03-198-2); si no, usa el patrón flexible.
     * Devuelve la ubicación o null si no hay.
     */
    public String findFirstLocationOnScreen() {
        try {
            List<String> textos = getVisibleTextsForCopy();
            // 1) formato estricto conocido del WMS.
            for (String s : textos) {
                Matcher m = PAT_LOCATION_COPY.matcher(s);
                if (m.find()) return m.group();
            }
            // 2) fallback: patrón flexible ya existente.
            for (String s : textos) {
                if (PAT_PALLET.matcher(s).find()) continue; // no confundir con ILP
                Matcher m = PAT_LOCATION.matcher(s);
                if (m.find()) return m.group();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // Patrón de SKU tipo "PHK_33YJ3X88": prefijo de 2+ letras + "_" + alfanumérico.
    // El "_" evita confundirlo con "TASK-2693" (que usa guion) y con ubicaciones.
    private static final Pattern PAT_SKU_COPY =
            Pattern.compile("\\b[A-Z]{2,}_[A-Z0-9]{4,}\\b");

    /**
     * Busca en la pantalla actual el primer SKU. Reutiliza la lógica existente:
     *   1) Estructural: el nodo de texto que aparece JUSTO DESPUÉS del ILP
     *      (igual que al extraer el SKU en "Put away success").
     *   2) Patrón: formato conocido del WMS (PHK_XXXXXXXX).
     * Nunca devuelve un ILP, una ubicación, un "TASK-..." ni una cantidad pura.
     * Devuelve el SKU o null si no hay.
     */
    public String findFirstSkuOnScreen() {
        try {
            List<String> textos = getVisibleTextsForCopy();
            // 1) Estructural: SKU inmediatamente después del ILP.
            for (int i = 0; i < textos.size(); i++) {
                if (PAT_PALLET.matcher(textos.get(i)).find()) {
                    String sku = buscarSkuDespuesDeIlp(textos, i);
                    if (sku != null && esCandidatoSku(sku)) return sku;
                }
            }
            // 2) Patrón conocido PHK_XXXXXXXX.
            for (String s : textos) {
                Matcher m = PAT_SKU_COPY.matcher(s);
                if (m.find()) {
                    String cand = m.group();
                    if (esCandidatoSku(cand)) return cand;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // =============================================================
    //   VARIANTES "TODOS": devuelven TODAS las coincidencias de la
    //   pantalla, sin duplicados y en orden de aparición. Usan los
    //   mismos patrones/lógica que los findFirst*.
    // =============================================================

    /** Todos los ILP de la pantalla (ILP-#####), sin duplicados, en orden. */
    public java.util.ArrayList<String> findAllIlpsOnScreen() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            for (String s : getVisibleTextsForCopy()) {
                Matcher m = PAT_PALLET.matcher(s);
                while (m.find()) out.add("ILP-" + m.group(1));
            }
        } catch (Throwable ignored) { }
        return new java.util.ArrayList<>(out);
    }

    /** Todas las ubicaciones de la pantalla, sin duplicados, en orden. */
    public java.util.ArrayList<String> findAllLocationsOnScreen() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            List<String> textos = getVisibleTextsForCopy();
            // 1) formato estricto conocido del WMS.
            for (String s : textos) {
                Matcher m = PAT_LOCATION_COPY.matcher(s);
                while (m.find()) out.add(m.group());
            }
            // 2) fallback: patrón flexible ya existente.
            for (String s : textos) {
                if (PAT_PALLET.matcher(s).find()) continue; // no confundir con ILP
                Matcher m = PAT_LOCATION.matcher(s);
                while (m.find()) out.add(m.group());
            }
        } catch (Throwable ignored) { }
        return new java.util.ArrayList<>(out);
    }

    /** Todos los SKU de la pantalla, sin duplicados, en orden. */
    public java.util.ArrayList<String> findAllSkusOnScreen() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        try {
            List<String> textos = getVisibleTextsForCopy();
            // 1) Estructural: SKU inmediatamente después de cada ILP.
            for (int i = 0; i < textos.size(); i++) {
                if (PAT_PALLET.matcher(textos.get(i)).find()) {
                    String sku = buscarSkuDespuesDeIlp(textos, i);
                    if (sku != null && esCandidatoSku(sku)) out.add(sku);
                }
            }
            // 2) Patrón conocido PHK_XXXXXXXX.
            for (String s : textos) {
                Matcher m = PAT_SKU_COPY.matcher(s);
                while (m.find()) {
                    String cand = m.group();
                    if (esCandidatoSku(cand)) out.add(cand);
                }
            }
        } catch (Throwable ignored) { }
        return new java.util.ArrayList<>(out);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        try {
            if (locRunnable != null) locHandler.removeCallbacks(locRunnable);
        } catch (Exception ignored) { }
        try {
            if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        } catch (Exception ignored) { }
        pendingLocPalletCode = null;
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        // Limpieza de recursos para evitar fugas de memoria y Runnables huérfanos
        // cuando el sistema reinicia el servicio de accesibilidad.
        try {
            if (locRunnable != null) locHandler.removeCallbacks(locRunnable);
        } catch (Exception ignored) { }
        try {
            if (debounceRunnable != null) debounceHandler.removeCallbacks(debounceRunnable);
        } catch (Exception ignored) { }
        try {
            mainHandler.removeCallbacksAndMessages(null);
        } catch (Exception ignored) { }
        try {
            // Apaga el hilo de I/O en segundo plano; las tareas ya encoladas
            // terminan, pero no se aceptan nuevas.
            ioExecutor.shutdown();
        } catch (Exception ignored) { }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
