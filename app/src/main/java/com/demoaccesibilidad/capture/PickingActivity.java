package com.demoaccesibilidad.capture;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
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

import java.util.List;

/**
 * v5.21 — Pantalla de Picking dentro de la app, AHORA integrada como una
 * pantalla más (igual que la principal): barra superior azul (#0f3460) con
 * título y flecha de volver, mismo fondo (#1a1a2e) y las mismas tarjetas que el
 * resto de la app. Antes usaba un tema sin barra y una paleta propia muy
 * oscura, por eso parecía una ventana "sobrepuesta" flotando encima; eso se
 * eliminó.
 *
 * Muestra:
 *   1. Estadísticas del DÍA de Wilson con el formato de la pantalla de la jefa
 *      (Tareas, Inventario EA, Ubicaciones, Progreso %, Tiempo, Tiempo/ubic) y
 *      el bloque del día ANTERIOR.
 *   2. El estimado de tiempo por pallet (promedio personal de hoy/ayer; "sin
 *      datos aún" si no hay) — NO se elimina.
 *   3. Los pallets sugeridos (solo de la pestaña New del WMS), con su SLP junto
 *      a cada orden y un check por orden terminada.
 *   4. Botón "Reset de lectura" (borra la lectura de tareas, NO las stats) y
 *      botón "Reiniciar stats de hoy", ambos con confirmación.
 *
 * Todo 100% local (SharedPreferences), nada a Firebase. Se abre desde el drawer
 * "📦 Modo Picking" SIN cambiar de modo. El overlay del FAB (📦 Picking) sigue
 * existiendo tal cual; esta pantalla es complementaria.
 */
public class PickingActivity extends AppCompatActivity {

    // Paleta del home (activity_main.xml), para que se vea natural en la app.
    private static final int BG      = Color.parseColor("#1a1a2e");
    private static final int BAR     = Color.parseColor("#0f3460");
    private static final int CARD    = Color.parseColor("#16213e");
    private static final int CARD2   = Color.parseColor("#2a2a4a");
    private static final int FG      = Color.parseColor("#FFFFFF");
    private static final int MUTED   = Color.parseColor("#AAAAAA");
    private static final int ACCENT  = Color.parseColor("#2196F3");
    private static final int GREEN   = Color.parseColor("#4CAF50");
    private static final int GOLD    = Color.parseColor("#FFC107");
    private static final int GRAY    = Color.parseColor("#607D8B");

    private LinearLayout root;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Contenedor vertical: barra superior + contenido desplazable.
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);

        Toolbar toolbar = new Toolbar(this);
        toolbar.setBackgroundColor(BAR);
        toolbar.setTitle(getString(R.string.pt_js_titulo));
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

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
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
        LinearLayout hoyCard = card(getString(R.string.pt_js_hoy), CARD);
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
        root.addView(hoyCard);

        // ---- Bloque AYER ----
        LinearLayout ayerCard = card(getString(R.string.pt_js_ayer), CARD2);
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
        root.addView(ayerCard);

        // ---- Fuente del consejo de tiempo por pallet (estimado por pallet) ----
        int src = PickTrackerStore.adviceSource(this);
        int spl = PickTrackerStore.secPerLocationForAdvice(this);
        TextView fuente = new TextView(this);
        fuente.setTextColor(spl > 0 ? GREEN : MUTED);
        fuente.setPadding(dp(4), dp(6), dp(4), dp(2));
        String mmss = PickTrackerStore.formatMmSs(spl);
        if (src == PickTrackerStore.SRC_TODAY) {
            fuente.setText(getString(R.string.pt_fuente_hoy, mmss));
        } else if (src == PickTrackerStore.SRC_PREV) {
            fuente.setText(getString(R.string.pt_fuente_ayer, mmss));
        } else {
            fuente.setText(getString(R.string.pt_fuente_sin));
        }
        root.addView(fuente);

        // ---- Pallets sugeridos (solo pestaña New) con SLP + checks ----
        renderPlan(pend, spl, src);

        // ---- Botón "Reset de lectura" (borra lectura, NO stats) ----
        Button resetLectura = new Button(this);
        resetLectura.setText(getString(R.string.pt_reset_lectura));
        resetLectura.setAllCaps(false);
        resetLectura.setTextColor(FG);
        resetLectura.setBackgroundColor(GRAY);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(18);
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
        LinearLayout plan = card(getString(R.string.pt_js_plan), CARD);

        TextView solo = new TextView(this);
        solo.setText(getString(R.string.pt_solo_new));
        solo.setTextColor(MUTED);
        solo.setTextSize(12f);
        solo.setPadding(0, 0, 0, dp(8));
        plan.addView(solo);

        if (pend == null || pend.isEmpty()) {
            TextView vacio = new TextView(this);
            vacio.setText(getString(R.string.pt_sin_plan));
            vacio.setTextColor(MUTED);
            vacio.setPadding(0, 0, 0, dp(4));
            plan.addView(vacio);
            root.addView(plan);
            return;
        }

        List<PickTrackerAdvisor.Pallet> pallets = PickTrackerAdvisor.plan(pend);
        final boolean sinTiempo = spl <= 0;

        int totalArt = 0, totalLoc = 0, totalSec = 0, totalOrders = 0;
        for (PickTrackerAdvisor.Pallet p : pallets) {
            totalArt += p.totalArticles();
            totalLoc += p.totalLocations();
            totalSec += p.estimatedSeconds(spl);
            totalOrders += p.orders.size();
        }
        TextView tvTotal = new TextView(this);
        tvTotal.setText(sinTiempo
                ? getString(R.string.pt_total_dia_sin, pallets.size(), totalArt, totalLoc)
                : getString(R.string.pt_total_dia, pallets.size(), totalArt, totalLoc,
                        PickTrackerAdvisor.minutesOf(totalSec)));
        tvTotal.setTextColor(GOLD);
        tvTotal.setTextSize(13f);
        tvTotal.setPadding(0, 0, 0, dp(4));
        plan.addView(tvTotal);

        TextView tvDone = new TextView(this);
        tvDone.setText(getString(R.string.pt_completadas,
                PickTrackerStore.getDoneCount(this), totalOrders));
        tvDone.setTextColor(MUTED);
        tvDone.setTextSize(12f);
        tvDone.setPadding(0, 0, 0, dp(8));
        plan.addView(tvDone);

        int palletNum = 1;
        for (PickTrackerAdvisor.Pallet p : pallets) {
            LinearLayout pc = new LinearLayout(this);
            pc.setOrientation(LinearLayout.VERTICAL);
            pc.setBackgroundColor(CARD2);
            pc.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams pclp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pclp.bottomMargin = dp(8);
            pc.setLayoutParams(pclp);

            TextView tvCab = new TextView(this);
            tvCab.setText(p.crossAisle
                    ? getString(R.string.pt_pallet_cross, palletNum)
                    : getString(R.string.pt_pallet, palletNum, p.aisleLabel()));
            tvCab.setTextColor(ACCENT);
            tvCab.setTextSize(15f);
            tvCab.setTypeface(null, android.graphics.Typeface.BOLD);
            pc.addView(tvCab);

            TextView tvRes = new TextView(this);
            tvRes.setText(sinTiempo
                    ? getString(R.string.pt_pallet_resumen_sin, p.totalArticles(), p.totalLocations())
                    : getString(R.string.pt_pallet_resumen, p.totalArticles(), p.totalLocations(),
                            PickTrackerAdvisor.minutesOf(p.estimatedSeconds(spl))));
            tvRes.setTextColor(MUTED);
            tvRes.setTextSize(12f);
            tvRes.setPadding(0, dp(2), 0, dp(4));
            pc.addView(tvRes);

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

            plan.addView(pc);
            palletNum++;
        }

        TextView tvRuta = new TextView(this);
        tvRuta.setText(getString(R.string.pt_ruta));
        tvRuta.setTextColor(MUTED);
        tvRuta.setTextSize(12f);
        plan.addView(tvRuta);

        root.addView(plan);
    }

    private LinearLayout card(String titulo, int bg) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(bg);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);

        TextView h = new TextView(this);
        h.setText(titulo);
        h.setTextColor(ACCENT);
        h.setTextSize(18f);
        h.setPadding(0, 0, 0, dp(8));
        c.addView(h);
        return c;
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
