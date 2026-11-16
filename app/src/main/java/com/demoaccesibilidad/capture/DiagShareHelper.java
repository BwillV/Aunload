package com.demoaccesibilidad.capture;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Sube el texto del diagnóstico a Firebase RTDB vía REST (sin SDK, sin auth)
 * y devuelve una URL pública legible en Safari (?print=pretty con líneas
 * numeradas). También poda diagnósticos viejos para no llenar la base.
 *
 * Rutas (ambas permitidas por las reglas actuales sin auth):
 *   - Con equipo:  equipos/{código}/diagnosticos/{id}
 *   - Sin equipo:  sessions/_diag_{deviceId}/{id}   (sessions/$sessionId es público)
 */
public final class DiagShareHelper {

    private static final int MAX_DIAGNOSTICOS = 10;
    private static final int TIMEOUT_MS = 20000;

    public interface Callback {
        void onSuccess(String urlPublica);
        void onError(String mensaje);
    }

    private DiagShareHelper() {}

    /**
     * Sube el diagnóstico en un hilo de fondo. Los callbacks se entregan en el
     * hilo principal.
     */
    public static void enviar(final Context appContext,
                              final String textoCompleto,
                              final Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String url = subir(appContext, textoCompleto);
                    main.post(() -> callback.onSuccess(url));
                } catch (final Exception e) {
                    final String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    main.post(() -> callback.onError(msg));
                }
            }
        }, "diag-share").start();
    }

    // ───────────────────────── interno ─────────────────────────

    private static String subir(Context ctx, String texto) throws Exception {
        if (texto == null) texto = "";

        FirebaseManager fm = FirebaseManager.getInstance(ctx);
        String base = FirebaseManager.getDatabaseUrl();
        if (base == null || base.isEmpty()) {
            throw new Exception("URL de base de datos no configurada");
        }
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        String deviceName = fm.getDeviceName();
        String deviceId = fm.getDeviceId();
        if (deviceName == null || deviceName.isEmpty()) deviceName = "dispositivo";
        if (deviceId == null || deviceId.isEmpty()) deviceId = "sin_id";

        // Carpeta destino según haya equipo o no.
        String carpeta;
        if (fm.hasTeam()) {
            carpeta = "equipos/" + san(fm.getTeamCode()) + "/diagnosticos";
        } else {
            carpeta = "sessions/_diag_" + san(deviceId);
        }

        // Id único basado en fecha-hora + dispositivo (saneado, ordenable).
        String fechaId = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        String fechaLegible = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        String id = fechaId + "_" + san(deviceName);

        // Construir payload: texto completo + líneas numeradas (legibles en Safari).
        JSONObject payload = new JSONObject();
        payload.put("fecha", fechaLegible);
        payload.put("device", deviceName);
        payload.put("texto", texto);

        String[] lineas = texto.split("\n", -1);
        JSONObject lineasObj = new JSONObject();
        int width = Math.max(4, String.valueOf(lineas.length).length());
        for (int i = 0; i < lineas.length; i++) {
            String key = pad(i + 1, width);
            lineasObj.put(key, lineas[i]);
        }
        payload.put("lineas", lineasObj);

        // Subir (PUT) al nodo del diagnóstico.
        String putUrl = base + "/" + carpeta + "/" + id + ".json";
        int code = httpPut(putUrl, payload.toString());
        if (code < 200 || code >= 300) {
            throw new Exception("Error al subir (HTTP " + code + ")");
        }

        // Poda: quedarse con los últimos MAX_DIAGNOSTICOS.
        try {
            podar(base, carpeta);
        } catch (Exception ignored) {
            // La poda es best-effort; no debe hacer fallar el envío.
        }

        // URL pública legible en Safari.
        return base + "/" + carpeta + "/" + id + ".json?print=pretty";
    }

    private static void podar(String base, String carpeta) throws Exception {
        String listUrl = base + "/" + carpeta + ".json?shallow=true";
        String body = httpGet(listUrl);
        if (body == null || body.equals("null") || body.isEmpty()) return;

        JSONObject obj = new JSONObject(body);
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = obj.keys(); it.hasNext(); ) keys.add(it.next());
        if (keys.size() <= MAX_DIAGNOSTICOS) return;

        // Orden ascendente: el prefijo yyyyMMdd_HHmmss ordena cronológicamente.
        Collections.sort(keys);
        int sobran = keys.size() - MAX_DIAGNOSTICOS;
        for (int i = 0; i < sobran; i++) {
            String delUrl = base + "/" + carpeta + "/" + keys.get(i) + ".json";
            httpDelete(delUrl);
        }
    }

    private static int httpPut(String urlStr, String json) throws Exception {
        HttpURLConnection c = open(urlStr);
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }
        int code = c.getResponseCode();
        drain(c);
        c.disconnect();
        return code;
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = open(urlStr);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        String body = readBody(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        return body;
    }

    private static void httpDelete(String urlStr) throws Exception {
        HttpURLConnection c = open(urlStr);
        c.setRequestMethod("DELETE");
        c.getResponseCode();
        drain(c);
        c.disconnect();
    }

    private static HttpURLConnection open(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        return c;
    }

    private static void drain(HttpURLConnection c) {
        try {
            InputStream is = c.getInputStream();
            if (is != null) { readBody(is); }
        } catch (Exception e) {
            try {
                InputStream es = c.getErrorStream();
                if (es != null) readBody(es);
            } catch (Exception ignored) { }
        }
    }

    private static String readBody(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    /** Mismo criterio que FirebaseManager.sanitizeKey: claves válidas en RTDB. */
    private static String san(String key) {
        if (key == null || key.isEmpty()) return "unknown";
        return key.replaceAll("[.#$\\[\\]/]", "_");
    }

    private static String pad(int n, int width) {
        StringBuilder sb = new StringBuilder(String.valueOf(n));
        while (sb.length() < width) sb.insert(0, '0');
        return sb.toString();
    }
}
