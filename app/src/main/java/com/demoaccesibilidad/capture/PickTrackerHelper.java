package com.demoaccesibilidad.capture;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * PickTrackerHelper — Overlay de PickTracker (v5.15, nuevo en este cambio).
 *
 * Se abre desde el menú del FAB (CopyTextHelper.showActionMenu).
 * Sigue el mismo patrón visual de los overlays existentes: TYPE_APPLICATION_OVERLAY,
 * tema oscuro, tecla Atrás del PDA cierra, toques fuera pasan al WMS.
 *
 * Muestra: rendimiento de hoy (tareas, piezas, piezas/hora, seg/ubicación
 * contra la referencia de 183 s), la tarea en curso con su progreso en vivo
 * ("4/5 Skus, 7/8 EA"), las tareas pendientes leídas de la lista de Pick
 * Task y el consejo de tareas. Los datos son locales (PickTrackerStore,
 * SharedPreferences "picktracker_prefs"), sin Firebase.
 *
 * Los botones manuales siguen ahí por si el detector no vio algo, pero ya
 * no hay tarea de ejemplo: si no hay tarea en curso, el botón de registrar
 * ubicación avisa y no inventa ninguna.
 */
public final class PickTrackerHelper {

    private static final int COLOR_BG = 0xFF1a1a2e;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HINT = 0xFF9E9E9E;
    private static final int COLOR_BLUE = 0xFF2196F3;
    private static final int COLOR_GREEN = 0xFF4CAF50;
    private static final int COLOR_GRAY = 0xFF607D8B;
    private static final int COLOR_INDIGO = 0xFF3F51B5;

    private static View sOverlayActual = null;
    private static WindowManager sWmActual = null;

    private PickTrackerHelper() { }

    public static void mostrar(final Context ctxBase, final WindowManager wm) {
        final Context context = LocaleHelper.wrap(ctxBase);
        final OverlayRoot root = crearRoot(context);

        TextView titulo = crearTexto(context,
                context.getString(R.string.pt_titulo), 18, true);
        titulo.setPadding(0, 0, 0, dp(context, 4));
        root.addView(titulo);

        String current = PickTrackerStore.getCurrentTask(context);
        TextView sub = crearTexto(context,
                current == null || current.isEmpty()
                        ? context.getString(R.string.pt_sin_tarea)
                        : context.getString(R.string.pt_tarea_actual, current),
                12, false);
        sub.setTextColor(COLOR_HINT);
        sub.setPadding(0, 0, 0, dp(context, 10));
        root.addView(sub);

        // Progreso en vivo de la tarea en curso (del detalle de Pick Task).
        if (current != null && !current.isEmpty()) {
            int skusDone = PickTrackerStore.getCurrentSkusDone(context);
            int skusTotal = PickTrackerStore.getCurrentSkusTotal(context);
            int eaDone = PickTrackerStore.getCurrentEaDone(context);
            int eaTotal = PickTrackerStore.getCurrentEaTotal(context);
            int locs = PickTrackerStore.getCurrentLocsList(context).size();
            String progreso = context.getString(R.string.pt_progreso,
                    skusDone, skusTotal, eaDone, eaTotal,
                    PickTrackerStore.formatMmSs(
                            PickTrackerStore.getCurrentElapsedSec(context)));
            TextView tvProgreso = crearTexto(context,
                    "📍 " + locs + " ubic. · " + progreso, 14, false);
            tvProgreso.setPadding(0, 0, 0, dp(context, 10));
            root.addView(tvProgreso);
        }

        int secPerLoc = PickTrackerStore.getSecPerLocationToday(context);
        if (secPerLoc <= 0) secPerLoc = PickTrackerStore.getLastSecPerLocation(context);
        String comparacion;
        if (secPerLoc <= 0) {
            comparacion = context.getString(R.string.pt_sin_datos);
        } else if (secPerLoc <= PickTrackerStore.BASELINE_SEC_PER_LOC) {
            comparacion = context.getString(R.string.pt_mejor,
                    PickTrackerStore.formatMmSs(secPerLoc));
        } else {
            comparacion = context.getString(R.string.pt_peor,
                    PickTrackerStore.formatMmSs(secPerLoc));
        }
        String stats = context.getString(R.string.pt_hoy,
                PickTrackerStore.getTasksToday(context),
                PickTrackerStore.getPiecesToday(context),
                PickTrackerStore.getPiecesPerHour(context),
                secPerLoc <= 0 ? "--" : PickTrackerStore.formatMmSs(secPerLoc));
        TextView tvStats = crearTexto(context, stats + "\n" + comparacion, 14, false);
        tvStats.setPadding(0, 0, 0, dp(context, 10));
        root.addView(tvStats);

        TextView tvConsejo = crearTexto(context,
                context.getString(R.string.pt_consejo) + "\n"
                        + PickTrackerAdvisor.adviceText(
                                PickTrackerStore.getPendingTasks(context),
                                PickTrackerStore.getBoxesPerPallet(context)),
                14, false);
        tvConsejo.setPadding(0, 0, 0, dp(context, 10));
        root.addView(tvConsejo);

        // Tareas pendientes leídas de la lista (máximo 5 líneas).
        java.util.List<PickTrackerAdvisor.TaskInfo> pendientes =
                PickTrackerStore.getPendingTasks(context);
        StringBuilder sbPend = new StringBuilder(
                context.getString(R.string.pt_pendientes));
        if (pendientes == null || pendientes.isEmpty()) {
            sbPend.append("\n").append(context.getString(R.string.pt_sin_pendientes));
        } else {
            int n = Math.min(pendientes.size(), 5);
            for (int i = 0; i < n; i++) {
                PickTrackerAdvisor.TaskInfo t = pendientes.get(i);
                sbPend.append("\n").append(t.id).append(" · zona ").append(t.zone)
                        .append(" · ").append(t.shipment)
                        .append(" · ").append(PickTrackerAdvisor.volumeLabel(t));
                if (t.locations > 0) sbPend.append(" · ").append(t.locations).append(" ubic.");
            }
            if (pendientes.size() > 5) {
                sbPend.append("\n… +").append(pendientes.size() - 5).append(" más");
            }
        }
        TextView tvPend = crearTexto(context, sbPend.toString(), 13, false);
        tvPend.setPadding(0, 0, 0, dp(context, 10));
        root.addView(tvPend);

        final WindowManager.LayoutParams params = crearParams(false);

        Button btnUbicacion = crearBoton(context,
                context.getString(R.string.pt_registrar_ubicacion), COLOR_GREEN);
        btnUbicacion.setOnClickListener(v -> {
            if (PickTrackerStore.getCurrentTask(context) == null
                    || PickTrackerStore.getCurrentTask(context).isEmpty()) {
                Toast.makeText(context,
                        context.getString(R.string.pt_sin_tarea),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            PickTrackerStore.addLocation(context, 1);
            removerOverlay(wm, root);
            mostrar(ctxBase, wm);
        });
        root.addView(btnUbicacion);
        addSpacer(context, root, 8);

        Button btnCerrarTarea = crearBoton(context,
                context.getString(R.string.pt_cerrar_tarea), COLOR_INDIGO);
        btnCerrarTarea.setOnClickListener(v -> {
            PickTrackerStore.endTask(context);
            removerOverlay(wm, root);
            mostrar(ctxBase, wm);
        });
        root.addView(btnCerrarTarea);
        addSpacer(context, root, 8);

        // Leer toda la lista: la app desliza sola la lista de Pick Task y
        // lee TODAS las tarjetas (mismo mecanismo que "Ubicaciones en
        // pantalla"). Se cierra primero el overlay para que el WMS quede
        // como ventana activa y el scroll lo encuentre; al terminar se
        // vuelve a abrir el overlay con el consejo ya completo.
        Button btnLeerLista = crearBoton(context,
                context.getString(R.string.pt_leer_lista), COLOR_INDIGO);
        btnLeerLista.setOnClickListener(v -> {
            removerOverlay(wm, root);
            ScreenCaptureService svc = ScreenCaptureService.getInstance();
            if (svc == null) {
                Toast.makeText(context,
                        context.getString(R.string.copy_activa_servicio),
                        Toast.LENGTH_LONG).show();
                mostrar(ctxBase, wm);
                return;
            }
            Toast.makeText(context,
                    context.getString(R.string.copy_buscando),
                    Toast.LENGTH_SHORT).show();
            svc.collectAllTextsScrolling(steps -> {
                try {
                    for (java.util.List<String> paso : steps) {
                        java.util.List<PickTrackerParser.ListCard> cards =
                                PickTrackerParser.parseList(paso);
                        if (cards != null && !cards.isEmpty()) {
                            PickTrackerStore.savePendingCards(context, cards);
                        }
                    }
                } catch (Throwable t) {
                    PalletTrackerApp.logException(context.getApplicationContext(),
                            "PickTrackerHelper.leerLista", t);
                }
                mostrar(ctxBase, wm);
            });
        });
        root.addView(btnLeerLista);
        addSpacer(context, root, 8);

        Button btnCerrar = crearBoton(context,
                "✕\u00A0" + context.getString(R.string.btn_cerrar), COLOR_GRAY);
        btnCerrar.setOnClickListener(v -> removerOverlay(wm, root));
        root.addView(btnCerrar);

        setOnBack(root, () -> removerOverlay(wm, root));

        try {
            mostrarOverlay(wm, root, params);
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "PickTrackerHelper.mostrar", t);
        }
    }

    // ── Helpers de overlay (mismo patrón que CopyTextHelper) ──

    private static class OverlayRoot extends LinearLayout {
        Runnable onBack;
        OverlayRoot(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }
        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP) {
                if (onBack != null) {
                    try { onBack.run(); } catch (Throwable ignored) { }
                    return true;
                }
            }
            return super.dispatchKeyEvent(event);
        }
    }

    private static void setOnBack(View root, Runnable action) {
        if (root instanceof OverlayRoot) ((OverlayRoot) root).onBack = action;
    }

    private static void mostrarOverlay(WindowManager wm, OverlayRoot root,
                                       WindowManager.LayoutParams params) {
        cerrarOverlayActual();
        wm.addView(root, params);
        sOverlayActual = root;
        sWmActual = wm;
        try { root.requestFocus(); } catch (Throwable ignored) { }
    }

    public static void cerrarOverlayActual() {
        if (sOverlayActual != null && sWmActual != null) {
            try { sWmActual.removeView(sOverlayActual); } catch (Throwable ignored) { }
        }
        sOverlayActual = null;
        sWmActual = null;
    }

    private static OverlayRoot crearRoot(Context context) {
        OverlayRoot root = new OverlayRoot(context);
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

    private static WindowManager.LayoutParams crearParams(boolean dimBehind) {
        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        if (dimBehind) flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
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
        if (dimBehind) params.dimAmount = 0.5f;
        return params;
    }

    private static void removerOverlay(WindowManager wm, View view) {
        try {
            wm.removeView(view);
        } catch (Exception ignored) { }
        if (view == sOverlayActual) {
            sOverlayActual = null;
            sWmActual = null;
        }
    }

    private static int dp(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
