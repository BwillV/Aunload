package com.demoaccesibilidad.capture;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * PickTrackerHelper — Overlay "📦 Picking" (v5.18, reescrito).
 *
 * Se abre desde el menú del FAB (CopyTextHelper.showActionMenu) solo en modo
 * Picking. Mismo estilo que el overlay "Ubicaciones en pantalla": tema oscuro,
 * TYPE_APPLICATION_OVERLAY, la tecla Atrás del PDA (KEYCODE_BACK) cierra, los
 * toques fuera pasan al WMS.
 *
 * Contenido:
 *   - Botón "🔄 Nueva lectura": borra el resultado anterior y vuelve a leer la
 *     "Pick Task List" deslizándola sola (ScreenCaptureService.collectAllTexts-
 *     Scrolling, en hilo aparte para no bloquear la UI) más las ubicaciones ya
 *     capturadas del detalle (PickTrackerStore). Reconstruye el plan.
 *   - El plan PERSISTE (PickTrackerStore, SharedPreferences, sin Firebase) al
 *     cerrar/reabrir el overlay o la app, hasta pulsar "Nueva lectura".
 *   - Lista de pallets sugeridos (PickTrackerAdvisor.plan): por pallet, total
 *     de artículos, nº de ubicaciones y tiempo estimado (~3 min/ubicación);
 *     cada orden con su TASK, artículos (DN) y zona/ubicaciones, y un CHECKBOX
 *     por orden para marcarla completada (persistente).
 *   - Total del día (todas las pallets) y pista de recorrido.
 */
public final class PickTrackerHelper {

    private static final int COLOR_BG = 0xFF1a1a2e;
    private static final int COLOR_CARD = 0xFF2a2a4a;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HINT = 0xFF9E9E9E;
    private static final int COLOR_BLUE = 0xFF2196F3;
    private static final int COLOR_GREEN = 0xFF4CAF50;
    private static final int COLOR_GRAY = 0xFF607D8B;
    private static final int COLOR_GOLD = 0xFFFFC107;

    private static View sOverlayActual = null;
    private static WindowManager sWmActual = null;

    private PickTrackerHelper() { }

    public static void mostrar(final Context ctxBase, final WindowManager wm) {
        final Context context = LocaleHelper.wrap(ctxBase);
        final OverlayRoot root = crearRoot(context);

        TextView titulo = crearTexto(context,
                context.getString(R.string.pt_titulo), 18, true);
        titulo.setPadding(0, 0, 0, dp(context, 8));
        root.addView(titulo);

        // Botón "Nueva lectura": lee toda la lista con scroll automático.
        Button btnNueva = crearBoton(context,
                context.getString(R.string.pt_nueva_lectura), COLOR_BLUE);
        btnNueva.setOnClickListener(v -> {
            removerOverlay(wm, root);
            ScreenCaptureService svc = ScreenCaptureService.getInstance();
            if (svc == null) {
                Toast.makeText(context,
                        context.getString(R.string.copy_activa_servicio),
                        Toast.LENGTH_LONG).show();
                mostrar(ctxBase, wm);
                return;
            }
            PickTrackerStore.clearReading(context);
            Toast.makeText(context,
                    context.getString(R.string.pt_leyendo),
                    Toast.LENGTH_SHORT).show();
            svc.collectAllTextsScrolling(steps -> {
                try {
                    if (steps != null) {
                        for (List<String> paso : steps) {
                            List<PickTrackerParser.ListCard> cards =
                                    PickTrackerParser.parseList(paso);
                            if (cards != null && !cards.isEmpty()) {
                                PickTrackerStore.savePendingCards(context, cards);
                            }
                        }
                    }
                } catch (Throwable t) {
                    PalletTrackerApp.logException(context.getApplicationContext(),
                            "PickTrackerHelper.nuevaLectura", t);
                }
                mostrar(ctxBase, wm);
            });
        });
        root.addView(btnNueva);
        addSpacer(context, root, 10);

        // Plan de pallets a partir de las tareas pendientes guardadas.
        List<PickTrackerAdvisor.TaskInfo> pendientes =
                PickTrackerStore.getPendingTasks(context);

        if (pendientes == null || pendientes.isEmpty()) {
            TextView vacio = crearTexto(context,
                    context.getString(R.string.pt_sin_plan), 14, false);
            vacio.setTextColor(COLOR_HINT);
            vacio.setPadding(0, 0, 0, dp(context, 10));
            root.addView(vacio);
        } else {
            List<PickTrackerAdvisor.Pallet> pallets =
                    PickTrackerAdvisor.plan(pendientes);

            // Resumen del día (todas las pallets).
            int totalArt = 0, totalLoc = 0, totalSec = 0, totalOrders = 0;
            for (PickTrackerAdvisor.Pallet p : pallets) {
                totalArt += p.totalArticles();
                totalLoc += p.totalLocations();
                totalSec += p.estimatedSeconds();
                totalOrders += p.orders.size();
            }
            TextView tvTotal = crearTexto(context,
                    context.getString(R.string.pt_total_dia,
                            pallets.size(), totalArt, totalLoc,
                            PickTrackerAdvisor.minutesOf(totalSec)),
                    13, true);
            tvTotal.setTextColor(COLOR_GOLD);
            tvTotal.setPadding(0, 0, 0, dp(context, 4));
            root.addView(tvTotal);

            TextView tvDone = crearTexto(context,
                    context.getString(R.string.pt_completadas,
                            PickTrackerStore.getDoneCount(context), totalOrders),
                    12, false);
            tvDone.setTextColor(COLOR_HINT);
            tvDone.setPadding(0, 0, 0, dp(context, 8));
            root.addView(tvDone);

            // Lista desplazable de pallets.
            ScrollView scroll = new ScrollView(context);
            int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.5f);
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, maxH));
            LinearLayout lista = new LinearLayout(context);
            lista.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(lista);

            int palletNum = 1;
            for (PickTrackerAdvisor.Pallet p : pallets) {
                LinearLayout card = new LinearLayout(context);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundColor(COLOR_CARD);
                card.setPadding(dp(context, 12), dp(context, 10),
                        dp(context, 12), dp(context, 10));

                String cab = p.crossAisle
                        ? context.getString(R.string.pt_pallet_cross, palletNum)
                        : context.getString(R.string.pt_pallet, palletNum, p.aisleLabel());
                TextView tvCab = crearTexto(context, cab, 15, true);
                tvCab.setTextColor(COLOR_BLUE);
                card.addView(tvCab);

                TextView tvRes = crearTexto(context,
                        context.getString(R.string.pt_pallet_resumen,
                                p.totalArticles(), p.totalLocations(),
                                PickTrackerAdvisor.minutesOf(p.estimatedSeconds())),
                        12, false);
                tvRes.setTextColor(COLOR_HINT);
                tvRes.setPadding(0, dp(context, 2), 0, dp(context, 4));
                card.addView(tvRes);

                if (p.hasPriority()) {
                    TextView tvPrio = crearTexto(context,
                            context.getString(R.string.pt_prioridad), 12, true);
                    tvPrio.setTextColor(COLOR_GOLD);
                    tvPrio.setPadding(0, 0, 0, dp(context, 4));
                    card.addView(tvPrio);
                }

                // Una fila con checkbox por cada orden de la pallet.
                for (final PickTrackerAdvisor.TaskInfo t : p.orders) {
                    LinearLayout fila = new LinearLayout(context);
                    fila.setOrientation(LinearLayout.HORIZONTAL);
                    fila.setGravity(Gravity.CENTER_VERTICAL);

                    final CheckBox chk = new CheckBox(context);
                    chk.setChecked(PickTrackerStore.isOrderDone(context, t.id));
                    try { chk.setButtonTintList(
                            android.content.res.ColorStateList.valueOf(COLOR_GREEN)); }
                    catch (Throwable ignored) { }
                    chk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton b, boolean checked) {
                            PickTrackerStore.setOrderDone(context, t.id, checked);
                        }
                    });
                    fila.addView(chk);

                    String zonaLoc = t.locCount() > 0
                            ? context.getString(R.string.pt_orden_zona_ubic, t.zone, t.locCount())
                            : context.getString(R.string.pt_orden_zona, t.zone);
                    TextView tvOrden = crearTexto(context,
                            context.getString(R.string.pt_orden_linea,
                                    t.id, t.articles, zonaLoc),
                            13, false);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    lp.leftMargin = dp(context, 4);
                    tvOrden.setLayoutParams(lp);
                    fila.addView(tvOrden);

                    card.addView(fila);
                }

                lista.addView(card);
                addSpacer(context, lista, 8);
                palletNum++;
            }

            root.addView(scroll);
            addSpacer(context, root, 8);

            TextView tvRuta = crearTexto(context,
                    context.getString(R.string.pt_ruta), 12, false);
            tvRuta.setTextColor(COLOR_HINT);
            tvRuta.setPadding(0, 0, 0, dp(context, 8));
            root.addView(tvRuta);
        }

        final WindowManager.LayoutParams params = crearParams(false);

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
        if (bold) tv.setTypeface(null, Typeface.BOLD);
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
