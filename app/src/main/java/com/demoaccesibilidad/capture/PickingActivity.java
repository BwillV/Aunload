package com.demoaccesibilidad.capture;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * v5.20 — Pantalla de Picking dentro de la app.
 *
 * Muestra las estadísticas del DÍA de Wilson con el formato de la pantalla de
 * la jefa (Tasks, Inventario total EA, Ubicaciones totales, Inventario recogido,
 * Ubicaciones recogidas, Progreso %, Tiempo, Tiempo por ubicación) y un bloque
 * con los datos del día ANTERIOR. Todo 100% local (SharedPreferences), nada a
 * Firebase. Se abre desde el drawer "📦 Modo Picking" SIN cambiar de modo.
 */
public class PickingActivity extends AppCompatActivity {

    private static final int BG      = Color.parseColor("#11161C");
    private static final int CARD    = Color.parseColor("#1E2630");
    private static final int CARD2   = Color.parseColor("#182028");
    private static final int FG      = Color.parseColor("#ECEFF1");
    private static final int MUTED   = Color.parseColor("#90A4AE");
    private static final int ACCENT  = Color.parseColor("#4FC3F7");

    private LinearLayout root;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(R.string.pt_js_titulo));
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
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

        // ---- Fuente del consejo de tiempo por pallet ----
        int src = PickTrackerStore.adviceSource(this);
        int spl = PickTrackerStore.secPerLocationForAdvice(this);
        TextView fuente = new TextView(this);
        fuente.setTextColor(MUTED);
        fuente.setPadding(dp(4), dp(10), dp(4), dp(2));
        String mmss = PickTrackerStore.formatMmSs(spl);
        if (src == PickTrackerStore.SRC_TODAY) {
            fuente.setText(getString(R.string.pt_fuente_hoy, mmss));
        } else if (src == PickTrackerStore.SRC_PREV) {
            fuente.setText(getString(R.string.pt_fuente_ayer, mmss));
        } else {
            fuente.setText(getString(R.string.pt_fuente_sin));
        }
        root.addView(fuente);

        // ---- Botón reiniciar stats de hoy ----
        Button reset = new Button(this);
        reset.setText(getString(R.string.pt_js_reset));
        reset.setAllCaps(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        reset.setLayoutParams(blp);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PickTrackerStore.resetStats(PickingActivity.this);
                Toast.makeText(PickingActivity.this,
                        getString(R.string.pt_js_reset_ok), Toast.LENGTH_SHORT).show();
                render();
            }
        });
        root.addView(reset);
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
