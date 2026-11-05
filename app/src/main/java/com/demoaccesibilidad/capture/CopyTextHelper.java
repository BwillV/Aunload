package com.demoaccesibilidad.capture;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * CopyTextHelper — Función "Copiar texto" (v5.4).
 *
 * Permite copiar al portapapeles textos de la pantalla del WMS que NO son
 * seleccionables dentro del propio WMS. Se dispara desde el botón flotante.
 *
 * Dos variantes (ambas aprobadas por el usuario):
 *   1) Lista para elegir: overlay con TODOS los textos visibles de la ventana
 *      activa. Al tocar uno se copia; botones "Copiar todo" y "Cerrar".
 *   2) Copiado directo: "Copiar ILP" y "Copiar ubicación" buscan el primer
 *      texto que coincida con el patrón y lo copian al instante.
 *
 * Los textos se obtienen de ScreenCaptureService (único con acceso al árbol de
 * accesibilidad). Todas las ventanas usan TYPE_APPLICATION_OVERLAY, el mismo
 * tipo que el FAB, para poder dibujarse sobre el WMS.
 *
 * Android 9 (API 28, sin GMS): el portapapeles se usa desde el contexto de
 * servicio en el hilo principal.
 */
public class CopyTextHelper {

    // ═══ Ajuste "Modo copiar texto" ═══
    private static final String PREFS = "copy_prefs";
    private static final String KEY_COPY_MODE = "copy_mode_enabled";

    // Colores (mismo tema oscuro que los demás overlays).
    private static final int COLOR_BG = 0xFF1a1a2e;
    private static final int COLOR_CARD = 0xFF2a2a4a;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HINT = 0xFF9E9E9E;
    private static final int COLOR_BLUE = 0xFF2196F3;
    private static final int COLOR_GREEN = 0xFF4CAF50;
    private static final int COLOR_GRAY = 0xFF607D8B;
    private static final int COLOR_INDIGO = 0xFF3F51B5;

    // =============================================================
    //                 AJUSTE: MODO COPIAR TEXTO
    // =============================================================

    /** Activado por defecto. */
    public static boolean isEnabled(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_COPY_MODE, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setEnabled(Context c, boolean enabled) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_COPY_MODE, enabled).apply();
        } catch (Exception ignored) { }
    }

    // =============================================================
    //                 PORTAPAPELES
    // =============================================================

    /** Copia al portapapeles y muestra un Toast. */
    public static void copyToClipboard(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return;
        final String value = text.trim();
        try {
            ClipboardManager clip = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clip != null) {
                clip.setPrimaryClip(ClipData.newPlainText("PalletTracker", value));
            }
            String preview = value.length() > 40 ? value.substring(0, 40) + "…" : value;
            Toast.makeText(context, "Copiado:\u00A0" + preview, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "CopyTextHelper.copyToClipboard", t);
        }
    }

    // =============================================================
    //        MENÚ DE ACCIONES (se abre desde el FAB)
    // =============================================================

    /**
     * Pequeño menú overlay con las acciones de copiado. onToggleYoTotal preserva
     * la acción original del toque del FAB (alternar entre "Yo" y "Total").
     */
    public static void showActionMenu(final Context context, final WindowManager wm,
                                      final Runnable onToggleYoTotal) {
        final LinearLayout root = crearRoot(context);

        TextView titulo = crearTexto(context, "📋\u00A0Copiar\u00A0texto", 18, true);
        titulo.setPadding(0, 0, 0, dp(context, 4));
        root.addView(titulo);

        TextView sub = crearTexto(context,
                "Elige\u00A0qué\u00A0copiar\u00A0de\u00A0la\u00A0pantalla\u00A0del\u00A0WMS", 12, false);
        sub.setTextColor(COLOR_HINT);
        sub.setPadding(0, 0, 0, dp(context, 14));
        root.addView(sub);

        final WindowManager.LayoutParams params = crearParams(false);

        // Ubicaciones en pantalla (ordenadas y con checkbox).
        Button btnLista = crearBoton(context, "📍\u00A0Ubicaciones\u00A0en\u00A0pantalla", COLOR_BLUE);
        btnLista.setOnClickListener(v -> {
            removerOverlay(wm, root);
            abrirListaUbicaciones(context, wm, true);
        });
        root.addView(btnLista);
        addSpacer(context, root, 8);

        // Copiar ILP directo. (Solo en modo Inbound: en Inventario no hay ILP.)
        if (!AppMode.isInventario(context)) {
            Button btnIlp = crearBoton(context, "🏷\u00A0Copiar\u00A0ILP", COLOR_GREEN);
            btnIlp.setOnClickListener(v -> {
                removerOverlay(wm, root);
                copiarDirecto(context, wm, true);
            });
            root.addView(btnIlp);
            addSpacer(context, root, 8);
        }

        // Copiar SKU directo.
        Button btnSku = crearBoton(context, "📦\u00A0Copiar\u00A0SKU", COLOR_GREEN);
        btnSku.setOnClickListener(v -> {
            removerOverlay(wm, root);
            copiarSku(context, wm);
        });
        root.addView(btnSku);
        addSpacer(context, root, 8);

        // Copiar ubicación directa.
        Button btnLoc = crearBoton(context, "📍\u00A0Copiar\u00A0ubicación", COLOR_GREEN);
        btnLoc.setOnClickListener(v -> {
            removerOverlay(wm, root);
            copiarDirecto(context, wm, false);
        });
        root.addView(btnLoc);
        addSpacer(context, root, 8);

        // Alternar Yo / Total (acción original del toque del FAB).
        // En modo Inventario no aplica (no se cuentan pallets), así que se oculta.
        if (onToggleYoTotal != null && !AppMode.isInventario(context)) {
            Button btnModo = crearBoton(context, "🔁\u00A0Alternar\u00A0Yo\u00A0/\u00A0Total", COLOR_INDIGO);
            btnModo.setOnClickListener(v -> {
                removerOverlay(wm, root);
                onToggleYoTotal.run();
            });
            root.addView(btnModo);
            addSpacer(context, root, 8);
        }

        // Cambiar de modo (Inbound / Inventario). También disponible en la
        // pantalla principal de la app. Toca para alternar.
        Button btnCambioModo = crearBoton(context,
                "🔀\u00A0Modo:\u00A0" + (AppMode.isInventario(context) ? "Inventario" : "Inbound"),
                COLOR_INDIGO);
        btnCambioModo.setOnClickListener(v -> {
            boolean inv = !AppMode.isInventario(context);
            AppMode.setInventario(context, inv);
            removerOverlay(wm, root);
            Toast.makeText(context,
                    "Modo:\u00A0" + (inv ? "Inventario" : "Inbound"),
                    Toast.LENGTH_SHORT).show();
        });
        root.addView(btnCambioModo);
        addSpacer(context, root, 8);

        // Cerrar.
        Button btnCerrar = crearBoton(context, "Cerrar", COLOR_GRAY);
        btnCerrar.setOnClickListener(v -> removerOverlay(wm, root));
        root.addView(btnCerrar);

        try {
            wm.addView(root, params);
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "CopyTextHelper.showActionMenu", t);
        }
    }

    // =============================================================
    //   UBICACIONES EN PANTALLA — vista ordenada con checkboxes
    //   (reemplaza "Elegir texto de la lista")
    // =============================================================

    /**
     * Muestra TODAS las ubicaciones detectadas en la pantalla, ordenadas por:
     *   1) Primer segmento (ej. C15) — agrupa letras + número.
     *   2) Segundo segmento / número de en medio (ej. 071) — orden principal.
     *   3) Tercer segmento (ej. 2) — orden secundario.
     * Cada fila tiene un CheckBox a la derecha (solo visual, sin acción de copia).
     * Un botón permite alternar entre orden ascendente y descendente.
     *
     * Optimización: se obtiene la lista UNA sola vez desde el servicio; al
     * cambiar de orden se recicla la misma lista (solo se reordena en memoria)
     * en vez de recorrer otra vez el árbol de accesibilidad.
     */
    private static void abrirListaUbicaciones(final Context context,
                                               final WindowManager wm,
                                               final boolean ascending) {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) {
            Toast.makeText(context,
                    "Activa\u00A0el\u00A0servicio\u00A0de\u00A0Accesibilidad",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Recolección con scroll: desplaza la lista del WMS para capturar TODAS
        // las ubicaciones cuando hay más de las que caben en pantalla. El
        // resultado se abre al terminar (callback en el hilo UI).
        Toast.makeText(context,
                "Buscando\u00A0todas\u00A0las\u00A0ubicaciones…", Toast.LENGTH_SHORT).show();
        svc.collectAllLocationsScrolling(result ->
                abrirListaUbicaciones(context, wm, ascending, result, null));
    }

    /**
     * Variante interna: si {@code cached} no es null se reutiliza (evita
     * re-recorrer el árbol de accesibilidad al cambiar de orden).
     * {@code checked} conserva las ubicaciones marcadas al alternar el orden.
     */
    private static void abrirListaUbicaciones(final Context context,
                                               final WindowManager wm,
                                               final boolean ascending,
                                               java.util.ArrayList<String> cached,
                                               final java.util.Set<String> checked) {
        // Conjunto de ubicaciones marcadas (se conserva entre reordenamientos).
        final java.util.Set<String> seleccionadas =
                (checked != null) ? checked : new java.util.HashSet<>();
        // v5.11 — Modo Inventario: el que busca ve marcarse solo las ubicaciones
        // que el compañero ya recogió (vienen del equipo por Firebase).
        final boolean inventario = AppMode.isInventario(context);
        final FirebaseManager fmInv = inventario
                ? FirebaseManager.getInstance(context) : null;
        if (fmInv != null) {
            try { fmInv.ensureListeningIfEnabled(); } catch (Throwable ignored) { }
        }
        // Mapa ubicación → checkbox (para el marcado automático).
        final java.util.Map<String, android.widget.CheckBox> checksPorUb =
                new java.util.HashMap<>();
        // v5.12 — ubicación → fila (para auto-scroll a la siguiente ubicación).
        final java.util.Map<String, View> filasPorUb =
                new java.util.HashMap<>();
        // ── Obtener ubicaciones (una sola lectura del árbol) ──
        final java.util.ArrayList<String> ubicaciones;
        if (cached != null && !cached.isEmpty()) {
            ubicaciones = cached;  // reutilizar sin releer
        } else {
            ScreenCaptureService svc = ScreenCaptureService.getInstance();
            if (svc == null) {
                Toast.makeText(context,
                        "Activa\u00A0el\u00A0servicio\u00A0de\u00A0Accesibilidad",
                        Toast.LENGTH_LONG).show();
                return;
            }
            ubicaciones = svc.findAllLocationsOnScreen();
        }
        if (ubicaciones == null || ubicaciones.isEmpty()) {
            Toast.makeText(context,
                    "No\u00A0se\u00A0encontraron\u00A0ubicaciones\u00A0en\u00A0esta\u00A0pantalla",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // ── Ordenar en memoria (ligero, sin I/O) ──
        java.util.Collections.sort(ubicaciones,
                (a, b) -> compararUbicaciones(a, b, ascending));

        // ── Construir overlay ──
        final LinearLayout root = crearRoot(context);

        TextView titulo = crearTexto(context,
                "📍\u00A0Ubicaciones\u00A0en\u00A0pantalla", 16, true);
        titulo.setPadding(0, 0, 0, dp(context, 4));
        root.addView(titulo);

        TextView sub = crearTexto(context,
                ubicaciones.size()
                        + (ubicaciones.size() == 1
                        ? "\u00A0ubicación\u00A0detectada"
                        : "\u00A0ubicaciones\u00A0detectadas"),
                12, false);
        sub.setTextColor(COLOR_HINT);
        sub.setPadding(0, 0, 0, dp(context, 8));
        root.addView(sub);

        // ── v5.12: en Inventario, RECUADRO VERDE "Siguiente ubicación" ──
        // Se construye aquí pero se agrega al root DEBAJO del botón de orden
        // (ver más abajo). La etiqueta va pequeña y la ubicación grande; el
        // recuadro es más grande que el botón de orden.
        final LinearLayout siguienteCard;
        final TextView txtSigLabel;
        final TextView txtSigLoc;
        if (inventario) {
            siguienteCard = new LinearLayout(context);
            siguienteCard.setOrientation(LinearLayout.VERTICAL);
            siguienteCard.setBackgroundColor(COLOR_GREEN);
            siguienteCard.setGravity(Gravity.CENTER_HORIZONTAL);
            siguienteCard.setPadding(dp(context, 16), dp(context, 14),
                    dp(context, 16), dp(context, 16));
            LinearLayout.LayoutParams lpCard = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lpCard.topMargin = dp(context, 4);
            lpCard.bottomMargin = dp(context, 10);
            siguienteCard.setLayoutParams(lpCard);

            // Etiqueta pequeña ("Siguiente ubicación").
            txtSigLabel = new TextView(context);
            txtSigLabel.setText("Siguiente\u00A0ubicación");
            txtSigLabel.setTextColor(COLOR_TEXT);
            txtSigLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            txtSigLabel.setGravity(Gravity.CENTER);
            siguienteCard.addView(txtSigLabel);

            // Ubicación grande (más grande que el texto de la etiqueta y que
            // el botón de orden).
            txtSigLoc = new TextView(context);
            txtSigLoc.setText("—");
            txtSigLoc.setTextColor(COLOR_TEXT);
            txtSigLoc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            txtSigLoc.setTypeface(null, android.graphics.Typeface.BOLD);
            txtSigLoc.setGravity(Gravity.CENTER);
            siguienteCard.addView(txtSigLoc);
        } else {
            siguienteCard = null;
            txtSigLabel = null;
            txtSigLoc = null;
        }

        // ── v5.8: bloque Artículo (SKU) + Total Qty (EN/ES) ──
        String sku = null, total = null;
        try {
            ScreenCaptureService svcInfo = ScreenCaptureService.getInstance();
            if (svcInfo != null) {
                sku = svcInfo.findItemSkuOnScreen();
                total = svcInfo.findTotalQtyOnScreen();
            }
        } catch (Throwable ignored) { }
        TextView infoSku = crearTexto(context,
                "Artículo\u00A0(SKU):\u00A0"
                        + ((sku != null && !sku.isEmpty())
                        ? sku.replace(' ', '\u00A0') : "—"),
                14, true);
        infoSku.setTextColor(COLOR_TEXT);
        infoSku.setPadding(0, 0, 0, dp(context, 2));
        root.addView(infoSku);
        TextView infoTotal = crearTexto(context,
                "Total\u00A0Qty:\u00A0"
                        + ((total != null && !total.isEmpty())
                        ? total.replace(' ', '\u00A0') : "—"),
                14, true);
        infoTotal.setTextColor(COLOR_TEXT);
        infoTotal.setPadding(0, 0, 0, dp(context, 8));
        root.addView(infoTotal);

        // Botón de orden (toggle asc / desc).
        String sortLabel = ascending
                ? "↑\u00A0Menor\u00A0a\u00A0mayor"
                : "↓\u00A0Mayor\u00A0a\u00A0menor";
        Button btnSort = crearBoton(context, sortLabel, COLOR_INDIGO);
        btnSort.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnSort.setPadding(dp(context, 12), dp(context, 8),
                dp(context, 12), dp(context, 8));
        btnSort.setOnClickListener(v -> {
            if (fmInv != null) { try { fmInv.setRecogidasObserver(null); } catch (Throwable ignored) { } }
            removerOverlay(wm, root);
            // Reutilizar la misma lista y conservar los checks marcados.
            abrirListaUbicaciones(context, wm, !ascending, ubicaciones, seleccionadas);
        });
        root.addView(btnSort);
        addSpacer(context, root, 8);

        // ── v5.12: recuadro verde "Siguiente ubicación" DEBAJO del botón de orden ──
        if (siguienteCard != null) {
            root.addView(siguienteCard);
        }

        // ── Lista desplazable ──
        ScrollView scroll = new ScrollView(context);
        int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.5f);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        LinearLayout lista = new LinearLayout(context);
        lista.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(lista);

        final WindowManager.LayoutParams params = crearParams(false);

        for (final String ubicacion : ubicaciones) {
            // Fila: [texto ───────── ☐]
            LinearLayout fila = new LinearLayout(context);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            fila.setGravity(Gravity.CENTER_VERTICAL);
            fila.setBackgroundColor(COLOR_CARD);
            fila.setPadding(dp(context, 12), dp(context, 10),
                    dp(context, 8), dp(context, 10));
            LinearLayout.LayoutParams lpFila = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lpFila.bottomMargin = dp(context, 5);
            fila.setLayoutParams(lpFila);

            // Texto de la ubicación (peso = 1, ocupa el espacio restante).
            TextView item = new TextView(context);
            item.setText(ubicacion);
            item.setTextColor(COLOR_TEXT);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            fila.addView(item);

            // CheckBox a la derecha (solo visual / seleccionable).
            final android.widget.CheckBox check = new android.widget.CheckBox(context);
            check.setChecked(seleccionadas.contains(ubicacion));
            check.setButtonTintList(
                    android.content.res.ColorStateList.valueOf(COLOR_BLUE));
            check.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            // Mantener el conjunto de seleccionadas al marcar/desmarcar.
            check.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) seleccionadas.add(ubicacion);
                else seleccionadas.remove(ubicacion);
            });
            fila.addView(check);

            // Tocar la fila alterna el check.
            fila.setOnClickListener(v -> check.setChecked(!check.isChecked()));

            // v5.11 — registrar para el marcado automático en Inventario.
            checksPorUb.put(ubicacion, check);
            // v5.12 — registrar la fila para el auto-scroll a la siguiente.
            filasPorUb.put(ubicacion, fila);

            lista.addView(fila);
        }

        root.addView(scroll);
        addSpacer(context, root, 12);

        // Botón Cerrar.
        Button btnCerrar = crearBoton(context, "Cerrar", COLOR_GRAY);
        btnCerrar.setOnClickListener(v -> {
            if (fmInv != null) { try { fmInv.setRecogidasObserver(null); } catch (Throwable ignored) { } }
            removerOverlay(wm, root);
        });
        root.addView(btnCerrar);

        try {
            wm.addView(root, params);
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "CopyTextHelper.abrirListaUbicaciones", t);
        }

        // ── v5.11: marcado automático en Inventario ──
        // Marca solo las ubicaciones que el equipo ya recogió y muestra la
        // "Siguiente ubicación" (primera sin recoger según el orden actual) o
        // "SKU completo ✅" cuando ya están todas. Se refresca en vivo cuando
        // el compañero recoge (Firebase → observador).
        if (inventario && fmInv != null && siguienteCard != null) {
            final ScrollView scrollRef = scroll;
            final Runnable updater = () -> {
                try {
                    java.util.Set<String> recogidas = fmInv.getRecogidas();
                    // 1) Marcar los checks de lo ya recogido y saber si falta algo.
                    boolean todas = true;
                    for (String u : ubicaciones) {
                        boolean done = recogidas.contains(u);
                        android.widget.CheckBox cb = checksPorUb.get(u);
                        if (cb != null && cb.isChecked() != done) cb.setChecked(done);
                        if (done) seleccionadas.add(u);
                        else todas = false;
                    }

                    // 2) "Siguiente ubicación": la primera SIN recoger DESPUÉS de
                    //    la última recogida, siguiendo el orden activo (menor o
                    //    mayor). Si se empieza a la mitad, continúa desde ahí; al
                    //    llegar al final da la vuelta para tomar las que queden
                    //    antes (no exige empezar por la primera ni la última).
                    String siguiente = null;
                    if (!todas) {
                        String ultima = fmInv.getUltimaRecogida();
                        int idxUltima = -1;
                        if (ultima != null) {
                            for (int i = 0; i < ubicaciones.size(); i++) {
                                if (ubicaciones.get(i).equalsIgnoreCase(ultima)) {
                                    idxUltima = i;
                                    break;
                                }
                            }
                        }
                        int n = ubicaciones.size();
                        for (int off = 1; off <= n; off++) {
                            int i = ((idxUltima + off) % n + n) % n;
                            String u = ubicaciones.get(i);
                            if (!recogidas.contains(u)) { siguiente = u; break; }
                        }
                    }

                    // 3) Pintar el recuadro verde.
                    if (ubicaciones.isEmpty()) {
                        siguienteCard.setVisibility(View.GONE);
                    } else if (todas) {
                        siguienteCard.setVisibility(View.VISIBLE);
                        txtSigLabel.setText("Inventario");
                        txtSigLoc.setText("Completo\u00A0✅");
                    } else {
                        siguienteCard.setVisibility(View.VISIBLE);
                        txtSigLabel.setText("Siguiente\u00A0ubicación");
                        txtSigLoc.setText(siguiente == null
                                ? "—" : siguiente.replace(' ', '\u00A0'));
                        // 4) Auto-scroll: llevar la lista hasta la siguiente
                        //    ubicación a medida que se van marcando los checks.
                        final View fila = (siguiente != null)
                                ? filasPorUb.get(siguiente) : null;
                        if (fila != null && scrollRef != null) {
                            scrollRef.post(() -> {
                                try {
                                    scrollRef.smoothScrollTo(0,
                                            Math.max(0, fila.getTop() - dp(context, 8)));
                                } catch (Throwable ignored) { }
                            });
                        }
                    }
                } catch (Throwable ignored) { }
            };
            // El observador llega en el hilo de Firebase/UI; lo encolamos en la
            // vista para tocar los checkboxes siempre en el hilo principal.
            fmInv.setRecogidasObserver(set -> {
                try { siguienteCard.post(updater); } catch (Throwable ignored) { }
            });
            // Estado inicial inmediato.
            updater.run();
        }
    }

    // ── Comparador de ubicaciones ──────────────────────────────────
    // Orden: 1º primer segmento (C15 < C16 < D01), 2º número de en
    // medio (071 < 072), 3º tercer número (2 < 4).
    // Si ascending=false se invierte todo el orden.

    private static int compararUbicaciones(String a, String b, boolean asc) {
        String[] pa = a.split("[-.]");
        String[] pb = b.split("[-.]");

        // Primer segmento (ej. "C15"): 1º la(s) letra(s) como texto,
        // 2º el número como ENTERO (así C2 va antes que C15).
        String s0a = pa.length > 0 ? pa[0] : "";
        String s0b = pb.length > 0 ? pb[0] : "";
        String letraA = s0a.replaceAll("[0-9].*$", "");  // letras iniciales
        String letraB = s0b.replaceAll("[0-9].*$", "");
        int cmp = letraA.compareToIgnoreCase(letraB);
        if (cmp != 0) return asc ? cmp : -cmp;
        cmp = Integer.compare(extraerNumero(s0a), extraerNumero(s0b));
        if (cmp != 0) return asc ? cmp : -cmp;

        // Segundo segmento (número de en medio) — orden principal.
        int midA = pa.length > 1 ? extraerNumero(pa[1]) : 0;
        int midB = pb.length > 1 ? extraerNumero(pb[1]) : 0;
        cmp = Integer.compare(midA, midB);
        if (cmp != 0) return asc ? cmp : -cmp;

        // Tercer segmento — orden secundario.
        int t3A = pa.length > 2 ? extraerNumero(pa[2]) : 0;
        int t3B = pb.length > 2 ? extraerNumero(pb[2]) : 0;
        cmp = Integer.compare(t3A, t3B);
        return asc ? cmp : -cmp;
    }

    /** Extrae la parte numérica de un segmento (ej. "033" → 33, "A2" → 2). */
    private static int extraerNumero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // =============================================================
    //        VARIANTE 2: COPIADO DIRECTO (ILP / SKU / UBICACIÓN)
    //   0 resultados → Toast · 1 → copia directa · 2+ → lista overlay
    // =============================================================

    private static void copiarDirecto(Context context, WindowManager wm, boolean ilp) {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) {
            Toast.makeText(context,
                    "Activa\u00A0el\u00A0servicio\u00A0de\u00A0Accesibilidad\u00A0para\u00A0copiar",
                    Toast.LENGTH_LONG).show();
            return;
        }
        java.util.ArrayList<String> valores =
                ilp ? svc.findAllIlpsOnScreen() : svc.findAllLocationsOnScreen();
        manejarResultados(context, wm, valores,
                ilp ? "ILP\u00A0detectados" : "Ubicaciones\u00A0detectadas",
                ilp ? "No\u00A0se\u00A0encontró\u00A0ILP\u00A0en\u00A0esta\u00A0pantalla"
                    : "No\u00A0se\u00A0encontró\u00A0ubicación\u00A0en\u00A0esta\u00A0pantalla");
    }

    private static void copiarSku(Context context, WindowManager wm) {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) {
            Toast.makeText(context,
                    "Activa\u00A0el\u00A0servicio\u00A0de\u00A0Accesibilidad\u00A0para\u00A0copiar",
                    Toast.LENGTH_LONG).show();
            return;
        }
        manejarResultados(context, wm, svc.findAllSkusOnScreen(),
                "SKU\u00A0detectados",
                "No\u00A0se\u00A0encontró\u00A0SKU\u00A0en\u00A0esta\u00A0pantalla");
    }

    /** 0 → Toast; 1 → copia directa; 2+ → overlay con la lista. */
    private static void manejarResultados(Context context, WindowManager wm,
                                          java.util.ArrayList<String> valores,
                                          String tituloBase, String msgVacio) {
        if (valores == null || valores.isEmpty()) {
            Toast.makeText(context, msgVacio, Toast.LENGTH_SHORT).show();
            return;
        }
        if (valores.size() == 1) {
            copyToClipboard(context, valores.get(0));
            return;
        }
        abrirListaResultados(context, wm, valores, tituloBase);
    }

    /**
     * Overlay con TODAS las coincidencias detectadas (2+). Mismo estilo y tipo
     * de ventana que "Elegir texto de la lista". Al tocar un valor se copia y se
     * cierra. "Copiar todos" une los valores con salto de línea.
     */
    private static void abrirListaResultados(final Context context, final WindowManager wm,
                                             final java.util.ArrayList<String> valores,
                                             final String tituloBase) {
        final LinearLayout root = crearRoot(context);

        TextView titulo = crearTexto(context,
                tituloBase + "\u00A0(" + valores.size() + ")", 16, true);
        titulo.setPadding(0, 0, 0, dp(context, 4));
        root.addView(titulo);

        TextView sub = crearTexto(context,
                "Toca\u00A0un\u00A0valor\u00A0para\u00A0copiarlo", 12, false);
        sub.setTextColor(COLOR_HINT);
        sub.setPadding(0, 0, 0, dp(context, 10));
        root.addView(sub);

        // Lista desplazable.
        ScrollView scroll = new ScrollView(context);
        int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.5f);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        LinearLayout lista = new LinearLayout(context);
        lista.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(lista);

        final WindowManager.LayoutParams params = crearParams(false);

        for (final String valor : valores) {
            TextView item = new TextView(context);
            item.setText(valor);
            item.setTextColor(COLOR_TEXT);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setBackgroundColor(COLOR_CARD);
            item.setPadding(dp(context, 12), dp(context, 12),
                    dp(context, 12), dp(context, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(context, 6);
            item.setLayoutParams(lp);
            item.setOnClickListener(v -> {
                copyToClipboard(context, valor);
                removerOverlay(wm, root);
            });
            lista.addView(item);
        }

        root.addView(scroll);
        addSpacer(context, root, 12);

        // Botones Copiar todos / Cerrar.
        LinearLayout fila = new LinearLayout(context);
        fila.setOrientation(LinearLayout.HORIZONTAL);

        Button btnTodos = crearBoton(context, "Copiar\u00A0todos", COLOR_BLUE);
        btnTodos.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnTodos.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (String s : valores) sb.append(s).append("\n");
            copyToClipboard(context, sb.toString().trim());
            removerOverlay(wm, root);
        });
        fila.addView(btnTodos);

        addSpacerH(context, fila, 8);

        Button btnCerrar = crearBoton(context, "Cerrar", COLOR_GRAY);
        btnCerrar.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnCerrar.setOnClickListener(v -> removerOverlay(wm, root));
        fila.addView(btnCerrar);

        root.addView(fila);

        try {
            wm.addView(root, params);
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "CopyTextHelper.abrirListaResultados", t);
        }
    }

    // =============================================================
    //                 HELPERS DE UI
    // =============================================================

    private static LinearLayout crearRoot(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(context, 20), dp(context, 18),
                dp(context, 20), dp(context, 18));
        root.setElevation(dp(context, 16));
        return root;
    }

    private static TextView crearTexto(Context context, String text, int sizeSp, boolean bold) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private static Button crearBoton(Context context, String text, int bgColor) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextColor(COLOR_TEXT);
        btn.setBackgroundColor(bgColor);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setPadding(dp(context, 16), dp(context, 12),
                dp(context, 16), dp(context, 12));
        return btn;
    }

    private static void addSpacer(Context context, LinearLayout parent, int dpHeight) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, dpHeight)));
        parent.addView(spacer);
    }

    private static void addSpacerH(Context context, LinearLayout parent, int dpWidth) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                dp(context, dpWidth), LinearLayout.LayoutParams.MATCH_PARENT));
        parent.addView(spacer);
    }

    private static WindowManager.LayoutParams crearParams(boolean focusable) {
        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        int flags = focusable
                ? WindowManager.LayoutParams.FLAG_DIM_BEHIND
                : (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;
        params.width = (int) (340 * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
        if (focusable) params.dimAmount = 0.5f;
        return params;
    }

    private static void removerOverlay(WindowManager wm, View view) {
        try {
            wm.removeView(view);
        } catch (Exception ignored) { }
    }

    private static int dp(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
