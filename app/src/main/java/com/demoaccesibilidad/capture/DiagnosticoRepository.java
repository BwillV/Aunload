package com.demoaccesibilidad.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Repositorio del sistema de diagnóstico.
 *
 * Maneja almacenamiento de volcados de pantalla capturados por
 * el servicio de accesibilidad. Usa SharedPreferences.
 * Límite: 300 capturas máximo.
 */
public class DiagnosticoRepository {

    private static final String PREFS_NAME = "diagnostico_prefs";
    private static final String KEY_DUMPS = "diag_dumps";
    private static final String KEY_GRABANDO = "diag_grabando";
    private static final String KEY_ULTIMA_CAPTURA = "diag_ultima_captura";
    private static final int MAX_DUMPS = 300;

    private final SharedPreferences prefs;

    public DiagnosticoRepository(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ====================== GRABACIÓN ======================

    public void setDiagGrabando(boolean grabando) {
        prefs.edit().putBoolean(KEY_GRABANDO, grabando).apply();
    }

    public boolean isDiagGrabando() {
        return prefs.getBoolean(KEY_GRABANDO, false);
    }

    // ====================== VOLCADOS ======================

    public void guardarDump(String dump) {
        if (dump == null || dump.isEmpty()) return;
        List<String> dumps = getDumps();
        dumps.add(0, dump);
        while (dumps.size() > MAX_DUMPS) dumps.remove(dumps.size() - 1);
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String d : dumps) arr.put(d);
        prefs.edit()
                .putString(KEY_DUMPS, arr.toString())
                .putLong(KEY_ULTIMA_CAPTURA, System.currentTimeMillis())
                .apply();
    }

    public List<String> getDumps() {
        List<String> out = new ArrayList<>();
        String json = prefs.getString(KEY_DUMPS, "");
        if (TextUtils.isEmpty(json)) return out;
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                out.add(arr.getString(i));
            }
        } catch (Exception ignored) { }
        return out;
    }

    public String getUltimoDump() {
        List<String> dumps = getDumps();
        return dumps.isEmpty() ? "" : dumps.get(0);
    }

    public void limpiarDiagnostico() {
        prefs.edit().remove(KEY_DUMPS).apply();
    }

    // ====================== TIMESTAMPS ======================

    public long getUltimaCapturaMs() {
        return prefs.getLong(KEY_ULTIMA_CAPTURA, 0L);
    }

    public String getUltimaCapturaTexto() {
        long ms = getUltimaCapturaMs();
        if (ms == 0L) return "";
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        return fmt.format(new Date(ms));
    }
}
