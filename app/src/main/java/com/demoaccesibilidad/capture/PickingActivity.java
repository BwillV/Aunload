package com.demoaccesibilidad.capture;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;

import java.util.List;

/**
 * v5.22 — Pantalla de Picking integrada visualmente con el resto de la app.
 *
 * Por qué se volvió a tocar: en las capturas de la v5.21 esta pantalla NO se
 * veía como la pantalla principal. Las causas reales eran:
 *   1) Las "tarjetas" eran LinearLayout planos con esquinas rectas y sin
 *      elevación — el home usa CardView redondeadas (8dp, elevación 2dp,
 *      fondo #16213e, igual que item_task.xml). Por eso se veía como texto
 *      suelto sobre un bloque azul, no como una pantalla nativa.
 *   2) El bloque "Ayer" usaba un azul más claro (#2a2a4a) que formaba un panel
 *      brillante que no pegaba con el resto.
 *   3) Faltaba el "cromado" de la app: la pantalla principal tiene la barra
 *      "Modo: ..." debajo del Toolbar. Aquí se saltaba directo a las stats, así
 *      que se sentía otra app.
 *
 * Arreglo v5.22:
 *   - Toolbar azul #0f3460 con el nombre "PalletTracker" (misma marca que el
 *     home) y flecha de volver.
 *   - Barra "🔀 Modo: Picking — Mis estadísticas" sobre #0d2137, idéntica en
 *     estilo a la barra de modo del home.
 *   - Fondo plano #1a1a2e (sin degradados ni paneles sueltos).
 *   - Todas las secciones son CardView redondeadas #16213e con elevación, y
 *     cada pallet sugerido es una sub-tarjeta redondeada, igual que las de la
 *     pantalla principal.
 *
 * Toda la funcionalidad se conserva: stats de hoy/ayer (reinicio diario),
 * estimado de tiempo por pallet siempre visible ("sin datos aún" si no hay),
 * pallets sugeridos solo de la pestaña New con SLP y check por orden, botón
 * "Reset de lectura" (no borra stats, con confirmación) y "Reiniciar stats".
 * Todo local (SharedPreferences), nada a Firebase. El overlay del FAB sigue
 * igual.
 */
public class PickingActivity extends AppCompatActivity {

    // Paleta EXACTA del home (activity_main.xml / item_task.xml).
    private static final int BG       = Color.parseColor("#1a1a2e"); // fondo app
    private static final int BAR      = Color.parseColor("#0f3460"); // toolbar
    private static final int MODEBAR  = Color.parseColor("#0d2137"); // barra de modo
    private static final int CARD     = Color.parseColor("#16213e"); // tarjetas
    private static final int SUBCARD  = Color.parseColor("#1f2b4d"); // sub-tarjeta pallet
    private static final int FG       = Color.parseColor("#FFFFFF");
    private static final int MUTED    = Color.parseColor("#AAAAAA");
    private static final int MUTED2   = Color.parseColor("#888888");
    private static final int ACCENT   = Color.parseColor("#2196F3");
    private static final int GREEN    = Color.parseColor("#4CAF50");
    private static final int GOLD     = Color.parseColor("#FFC107");
    private static final int GRAY     = Color.parseColor("#607D8B");

    private LinearLayout root;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);

        // ── Toolbar: misma marca y color que el home ──
        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(BAR);
        toolbar.setTitle(getString(R.string.app_name));
        toolbar.setTitleTextColor(FG);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        screen.addView(toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { getWindow().setStatusBarColor(BAR); } catch (Throwable ignored) { }
        }

        // ── Barra "Modo: ..." igual que la de la pantalla principal ──
        LinearLayout modeBar = new LinearLayout(this);
        modeBar.setOrientation(LinearLayout.HORIZONTAL);
        modeBar.setGravity(Gravity.CENTER_VERTICAL);
        modeBar.setBackgroundColor(MODEBAR);
        int mp = dp(12);
        modeBar.setPadding(mp, mp, mp, mp);
        modeBar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView modeIcon = new TextView(this);
        modeIcon.setText("🔀");
        modeIcon.setTextSize(18f);
        modeIcon.setPadding(0, 0, dp(8), 0);
        modeBar.addView(modeIcon);

        TextView modeLabel = new TextView(this);
        modeLabel.setText(getString(R.string.pt_js_titulo));
        modeLabel.setTextColor(FG);
        modeLabel.setTextSize(15f);
        modeLabel.setTypeface(null, Typeface.BOLD);
        modeBar.addView(modeLabel);
        screen.addView(modeBar);

        // ── Contenido desplazable ──
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        screen.addView(scroll);

        setContentView(screen);
        render();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (root != null) render();
    }

    private void render() {
        root.removeAllViews();

        // Asignado hoy = suma de las tareas pendientes (pestaña New).
        List<PickTrackerAdvisor.TaskInfo> pend = PickTrackerStore.getPendingTasks(this);
        int asigArt = 0, asigLoc = 0;
        for (PickTrackerAdvisor.TaskInfo t : pend) {
            asigArt += t.articles;
            asigLoc += t.locCount();
        }

        PickTrackerStore.Stats hoy = PickTrackerStore.getStatsToday(this);
        PickTrackerStore.Stats ayer = PickTrackerStore.getStatsPrevDay(this);

        // ---- Bloque HOY (formato jefa) ----
        LinearLayout hoyCard = card(getString(R.string.pt_js_hoy));
        addRow(hoyCard, R.string.pt_js_tasks, String.valueOf(hoy.tasks));
        addRow(hoyCard, R.string.pt_js_total_inv,
                asigArt > 0 ? String.valueOf(asigArt) : String.valueOf(hoy.ea));
        addRow(hoyCard, R.string.pt_js_total_loc,
                asigLoc > 0 ? String.valueOf(asigLoc) : String.valueOf(hoy.locations));
        addRow(hoyCard, R.string.pt_js_picked_inv, String.valueOf(hoy.ea));
        addRow(hoyCard, R.string.pt_js_picked_loc, String.valueOf(hoy.locations));
        int prog = asigLoc > 0 ? (int) Math.round(100.0 * hoy.locations / asigLoc) : 0;
        addRow(hoyCard, R.string.pt_js_progress, asigLoc > 0 ? (prog + "%") : "—");
        addRow(hoyCard, R.string.pt_js_time, PickTrackerStore.formatDur(hoy.seconds));
        addRow(hoyCard, R.string.pt_js_time_loc,
                hoy.locations > 0 ? PickTrackerStore.formatMmSs(hoy.secPerLocation())
                                  : getString(R.string.pt_js_sin_datos));

        // ---- Bloque AYER ----
        LinearLayout ayerCard = card(getString(R.string.pt_js_ayer));
        if (ayer.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(getString(R.string.pt_js_sin_datos));
            t.setTextColor(MUTED);
            t.setPadding(0, dp(4), 0, dp(4));
            ayerCard.addView(t);
        } else {
            addRow(ayerCard, R.string.pt_js_tasks, String.valueOf(ayer.tasks));
            addRow(ayerCard, R.string.pt_js_picked_inv, String.valueOf(ayer.ea));
            addRow(ayerCard, R.string.pt_js_picked_loc, String.valueOf(ayer.locations));
            addRow(ayerCard, R.string.pt_js_time, PickTrackerStore.formatDur(ayer.seconds));
            addRow(ayerCard, R.string.pt_js_time_loc,
                    ayer.locations > 0 ? PickTrackerStore.formatMmSs(ayer.secPerLocation())
                                       : getString(R.string.pt_js_sin_datos));
        }

        // ---- Pallets sugeridos (solo pestaña New) con SLP + checks ----
        int src = PickTrackerStore.adviceSource(this);
        int spl = PickTrackerStore.secPerLocationForAdvice(this);
        renderPlan(pend, spl, src);

        // ---- Botón "Reset de lectura" (borra lectura, NO stats) ----
        Button resetLectura = new Button(this);
        resetLectura.setText(getString(R.string.pt_reset_lectura));
        resetLectura.setAllCaps(false);
        resetLectura.setTextColor(FG);
        resetLectura.setBackgroundColor(GRAY);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        rlp.leftMargin = dp(4);
        rlp.rightMargin = dp(4);
        resetLectura.setLayoutParams(rlp);
        resetLectura.setOnClickListener(v -> new AlertDialog.Builder(PickingActivity.this)
                .setMessage(getString(R.string.pt_js_reset_lectura_confirm))
                .setPositiveButton(android.R.string.yes, (d, w) -> {
                    PickTrackerStore.clearReading(PickingActivity.this);
                    Toast.makeText(PickingActivity.this,
                            getString(R.string.pt_js_reset_lectura_ok),
                            Toast.LENGTH_LONG).show();
                    render();
                })
                .setNegativeButton(android.R.string.no, null)
                .show());
        root.addView(resetLectura);

        // ---- Botón "Reiniciar stats de hoy" (con confirmación) ----
        Button reset = new Button(this);
        reset.setText(getString(R.string.pt_js_reset));
        reset.setAllCaps(false);
        reset.setTextColor(FG);
        reset.setBackgroundColor(ACCENT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(10);
        blp.leftMargin = dp(4);
        blp.rightMargin = dp(4);
        blp.bottomMargin = dp(8);
        reset.setLayoutParams(blp);
        reset.setOnClickListener(v -> new AlertDialog.Builder(PickingActivity.this)
                .setMessage(getString(R.string.pt_js_reset_confirm))
                .setPositiveButton(android.R.string.yes, (d, w) -> {
                    PickTrackerStore.resetStats(PickingActivity.this);
                    Toast.makeText(PickingActivity.this,
                            getString(R.string.pt_js_reset_ok), Toast.LENGTH_SHORT).show();
                    render();
                })
                .setNegativeButton(android.R.string.no, null)
                .show());
        root.addView(reset);
    }

    /** Dibuja el bloque "Pallets sugeridos" con el mismo criterio que el overlay. */
    private void renderPlan(List<PickTrackerAdvisor.TaskInfo> pend, int spl, int src) {
        LinearLayout plan = card(getString(R.string.pt_js_plan));

        TextView solo = new TextView(this);
        solo.setText(getString(R.string.pt_solo_new));
        solo.setTextColor(MUTED);
        solo.setTextSize(12f);
        solo.setPadding(0, 0, 0, dp(8));
        plan.addView(solo);

        // v5.23 (req 2): tiempo estimado por pallet SIEMPRE visible. Con datos
        // propios (hoy/ayer) se usan; si no, un estimado base (~3 min/ubic).
        final boolean usaBase = spl <= 0;
        final int secUsar = usaBase ? PickTrackerAdvisor.SECONDS_PER_LOCATION : spl;
        TextView fuente = new TextView(this);
        fuente.setTextColor(usaBase ? GOLD : GREEN);
        fuente.setTextSize(12f);
        fuente.setPadding(0, 0, 0, dp(8));
        String mmss = PickTrackerStore.formatMmSs(secUsar);
        if (!usaBase && src == PickTrackerStore.SRC_TODAY) {
            fuente.setText(getString(R.string.pt_fuente_hoy, mmss));
        } else if (!usaBase && src == PickTrackerStore.SRC_PREV) {
            fuente.setText(getString(R.string.pt_fuente_ayer, mmss));
        } else {
            fuente.setText(getString(R.string.pt_fuente_base, mmss));
        }
        plan.addView(fuente);

        if (pend == null || pend.isEmpty()) {
            TextView vacio = new TextView(this);
            vacio.setText(getString(R.string.pt_sin_plan));
            vacio.setTextColor(MUTED);
            vacio.setPadding(0, 0, 0, dp(4));
            plan.addView(vacio);
            return;
        }

        List<PickTrackerAdvisor.Pallet> pallets = PickTrackerAdvisor.plan(pend);

        int totalArt = 0, totalLoc = 0, totalSec = 0, totalOrders = 0;
        for (PickTrackerAdvisor.Pallet p : pallets) {
            totalArt += p.totalArticles();
            totalLoc += p.totalLocations();
            totalSec += p.estimatedSeconds(secUsar);
            totalOrders += p.orders.size();
        }
        TextView tvTotal = new TextView(this);
        tvTotal.setText(getString(R.string.pt_total_dia, pallets.size(), totalArt, totalLoc,
                PickTrackerAdvisor.minutesOf(totalSec)));
        tvTotal.setTextColor(GOLD);
        tvTotal.setTextSize(13f);
        tvTotal.setPadding(0, 0, 0, dp(4));
        plan.addView(tvTotal);

        // v5.23 (req 7): PALLETS completados (todas sus órdenes marcadas), no
        // tareas como pallets. Las órdenes marcadas van aparte.
        int palletsDone = 0;
        for (PickTrackerAdvisor.Pallet p : pallets) {
            boolean all = !p.orders.isEmpty();
            for (PickTrackerAdvisor.TaskInfo o : p.orders) {
                if (!PickTrackerStore.isOrderDone(this, o.id)) { all = false; break; }
            }
            if (all) palletsDone++;
        }
        TextView tvDone = new TextView(this);
        tvDone.setText(getString(R.string.pt_pallets_completados,
                palletsDone, pallets.size(),
                PickTrackerStore.getDoneCount(this), totalOrders));
        tvDone.setTextColor(MUTED);
        tvDone.setTextSize(12f);
        tvDone.setPadding(0, 0, 0, dp(8));
        plan.addView(tvDone);

        // ETA: si empiezas AHORA y haces los pallets/tareas en orden, ¿a qué
        // hora terminas cada uno? Acumulado desde ahora.
        final long etaBase = System.currentTimeMillis();
        int etaAcc = 0;

        int palletNum = 1;
        for (PickTrackerAdvisor.Pallet p : pallets) {
            LinearLayout pc = subCard(plan);

            TextView tvCab = new TextView(this);
            tvCab.setText(p.crossAisle
                    ? getString(R.string.pt_pallet_cross, palletNum)
                    : getString(R.string.pt_pallet, palletNum, p.aisleLabel()));
            tvCab.setTextColor(ACCENT);
            tvCab.setTextSize(15f);
            tvCab.setTypeface(null, Typeface.BOLD);
            pc.addView(tvCab);

            TextView tvRes = new TextView(this);
            tvRes.setText(getString(R.string.pt_pallet_resumen, p.totalArticles(), p.totalLocations(),
                    PickTrackerAdvisor.minutesOf(p.estimatedSeconds(secUsar))));
            tvRes.setTextColor(MUTED);
            tvRes.setTextSize(12f);
            tvRes.setPadding(0, dp(2), 0, dp(4));
            pc.addView(tvRes);

            // Hora a la que terminas este pallet si empiezas ahora (acumulado).
            final int palletSec = p.estimatedSeconds(secUsar);
            TextView tvTermP = new TextView(this);
            tvTermP.setText(getString(R.string.pt_pallet_termina,
                    PickTrackerAdvisor.clockAfter(etaBase, etaAcc + palletSec)));
            tvTermP.setTextColor(GREEN);
            tvTermP.setTextSize(12f);
            tvTermP.setPadding(0, 0, 0, dp(4));
            pc.addView(tvTermP);

            if (p.hasPriority()) {
                TextView tvPrio = new TextView(this);
                tvPrio.setText(getString(R.string.pt_prioridad));
                tvPrio.setTextColor(GOLD);
                tvPrio.setTextSize(12f);
                tvPrio.setPadding(0, 0, 0, dp(4));
                pc.addView(tvPrio);
            }

            for (final PickTrackerAdvisor.TaskInfo t : p.orders) {
                LinearLayout fila = new LinearLayout(this);
                fila.setOrientation(LinearLayout.HORIZONTAL);
                fila.setGravity(Gravity.CENTER_VERTICAL);

                final CheckBox chk = new CheckBox(this);
                chk.setChecked(PickTrackerStore.isOrderDone(this, t.id));
                try {
                    chk.setButtonTintList(
                            android.content.res.ColorStateList.valueOf(GREEN));
                } catch (Throwable ignored) { }
                chk.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                        PickTrackerStore.setOrderDone(PickingActivity.this, t.id, checked);
                    }
                });
                fila.addView(chk);

                String zonaLoc = t.locCount() > 0
                        ? getString(R.string.pt_orden_zona_ubic, t.zone, t.locCount())
                        : getString(R.string.pt_orden_zona, t.zone);
                String linea = getString(R.string.pt_orden_linea, t.id, t.articles, zonaLoc);
                String slp = PickTrackerStore.getTaskLp(this, t.id);
                if (slp != null && !slp.isEmpty()) {
                    linea += "\n" + getString(R.string.pt_orden_slp, slp);
                }
                // Estimado por tarea + hora a la que terminas esta tarea.
                int tSec = PickTrackerAdvisor.taskSeconds(t, secUsar);
                etaAcc += tSec;
                linea += "\n" + getString(R.string.pt_tarea_tiempo,
                        PickTrackerAdvisor.minutesOf(tSec),
                        PickTrackerAdvisor.clockAfter(etaBase, etaAcc));
                TextView tvOrden = new TextView(this);
                tvOrden.setText(linea);
                tvOrden.setTextColor(FG);
                tvOrden.setTextSize(13f);
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                olp.leftMargin = dp(4);
                tvOrden.setLayoutParams(olp);
                fila.addView(tvOrden);

                pc.addView(fila);
            }

            palletNum++;
        }

        TextView tvRuta = new TextView(this);
        tvRuta.setText(getString(R.string.pt_ruta));
        tvRuta.setTextColor(MUTED2);
        tvRuta.setTextSize(12f);
        tvRuta.setPadding(0, dp(4), 0, 0);
        plan.addView(tvRuta);
    }

    /**
     * Crea una tarjeta redondeada con elevación (igual que item_task.xml:
     * #16213e, radio 8dp, elevación 2dp, margen 4dp) y la agrega al root.
     * Devuelve el LinearLayout interno para rellenarlo.
     */
    private LinearLayout card(String titulo) {
        CardView cv = new CardView(this);
        cv.setCardBackgroundColor(CARD);
        cv.setRadius(dp(8));
        cv.setCardElevation(dp(2));
        cv.setUseCompatPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = dp(4);
        lp.setMargins(m, m, m, dp(10));
        cv.setLayoutParams(lp);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        inner.setPadding(p, p, p, p);
        cv.addView(inner);

        TextView h = new TextView(this);
        h.setText(titulo);
        h.setTextColor(ACCENT);
        h.setTextSize(18f);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, 0, 0, dp(8));
        inner.addView(h);

        root.addView(cv);
        return inner;
    }

    /**
     * Sub-tarjeta redondeada (un pallet sugerido) dentro de la tarjeta "plan".
     * Devuelve el LinearLayout interno para rellenarlo.
     */
    private LinearLayout subCard(LinearLayout parent) {
        CardView cv = new CardView(this);
        cv.setCardBackgroundColor(SUBCARD);
        cv.setRadius(dp(8));
        cv.setCardElevation(dp(1));
        cv.setUseCompatPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        cv.setLayoutParams(lp);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(12), dp(10), dp(12), dp(10));
        cv.addView(inner);

        parent.addView(cv);
        return inner;
    }

    private void addRow(LinearLayout parent, int labelRes, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView l = new TextView(this);
        l.setText(getString(labelRes));
        l.setTextColor(MUTED);
        l.setTextSize(15f);
        LinearLayout.LayoutParams llp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l.setLayoutParams(llp);
        row.addView(l);

        TextView vv = new TextView(this);
        vv.setText(value);
        vv.setTextColor(FG);
        vv.setTextSize(15f);
        vv.setGravity(Gravity.END);
        row.addView(vv);

        parent.addView(row);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
