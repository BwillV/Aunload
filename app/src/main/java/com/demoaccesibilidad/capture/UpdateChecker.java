package com.demoaccesibilidad.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UpdateChecker — Buscar e instalar actualizaciones directamente desde GitHub.
 *
 * v4.2. Pensado para los escáner Honeywell/iData con Android 9 y SIN Google
 * Play Services, por eso NO usa ninguna librería de Google: solo
 * HttpURLConnection contra la API pública de GitHub.
 *
 * Flujo:
 *  1. Consulta los releases del repositorio PÚBLICO (no requiere token).
 *  2. Busca el release de PalletTracker más reciente (por versionCode).
 *  3. Compara con la versión instalada (PackageManager).
 *  4. Si hay una versión nueva, ofrece descargar el APK y lo instala con
 *     FileProvider + ACTION_VIEW. Si no hay permiso de "instalar apps
 *     desconocidas", lleva al usuario a concederlo.
 *
 * El APK se firma siempre con la MISMA llave debug, así que se instala encima
 * de la versión anterior sin desinstalar.
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    // Repositorio PÚBLICO donde se publican los releases de PalletTracker.
    private static final String OWNER = "BwillV";
    private static final String REPO = "Aunload";
    private static final String RELEASES_API =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases?per_page=30";

    private final Handler main = new Handler(Looper.getMainLooper());

    public void checkForUpdates(final Activity activity) {
        if (activity == null) return;

        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setMessage("Buscando\u00A0actualizaciones…");
        progress.setCancelable(true);
        progress.show();

        new Thread(() -> {
            try {
                String json = httpGet(RELEASES_API);
                JSONArray releases = new JSONArray(json);

                // Buscar el release de PalletTracker con mayor versionCode.
                JSONObject best = null;
                int bestCode = -1;
                String bestName = null;
                String bestApkUrl = null;

                for (int i = 0; i < releases.length(); i++) {
                    JSONObject rel = releases.optJSONObject(i);
                    if (rel == null) continue;
                    if (rel.optBoolean("draft", false)) continue;

                    String apkUrl = findPalletTrackerApk(rel);
                    if (apkUrl == null) continue;   // no es un release de PalletTracker

                    int code = parseVersionCode(rel);
                    String vName = parseVersionName(rel);

                    if (code > bestCode) {
                        bestCode = code;
                        best = rel;
                        bestName = vName;
                        bestApkUrl = apkUrl;
                    }
                }

                final int releaseCode = bestCode;
                final String releaseName = bestName;
                final String apkUrl = bestApkUrl;

                // Versión instalada.
                final int installedCode = getInstalledVersionCode(activity);
                final String installedName = getInstalledVersionName(activity);

                main.post(() -> {
                    dismiss(progress);
                    if (activity.isFinishing()) return;

                    if (apkUrl == null || releaseCode < 0) {
                        showInfo(activity, "Sin\u00A0actualizaciones",
                                "No\u00A0se\u00A0encontró\u00A0ningún\u00A0release\u00A0de\u00A0PalletTracker\u00A0en\u00A0GitHub.");
                        return;
                    }

                    boolean hayNueva = releaseCode > installedCode;
                    if (hayNueva) {
                        String msg = "Tienes\u00A0la\u00A0versión\u00A0" + installedName
                                + "\u00A0(" + installedCode + ").\n\n"
                                + "Nueva\u00A0versión\u00A0disponible:\u00A0"
                                + (releaseName != null ? releaseName : "?")
                                + "\u00A0(" + releaseCode + ").\n\n¿Descargar\u00A0e\u00A0instalar?";
                        new AlertDialog.Builder(activity)
                                .setTitle("🔄\u00A0Actualización\u00A0disponible")
                                .setMessage(msg)
                                .setPositiveButton("Descargar\u00A0e\u00A0instalar",
                                        (d, w) -> descargarEInstalar(activity, apkUrl,
                                                releaseName != null ? releaseName : "nueva"))
                                .setNegativeButton("Ahora\u00A0no", null)
                                .show();
                    } else {
                        showInfo(activity, "✅\u00A0Todo\u00A0al\u00A0día",
                                "Ya\u00A0tienes\u00A0la\u00A0última\u00A0versión\u00A0instalada\u00A0("
                                        + installedName + ").");
                    }
                });

            } catch (Throwable t) {
                Log.e(TAG, "Error buscando actualizaciones", t);
                PalletTrackerApp.logException(activity.getApplicationContext(),
                        "UpdateChecker.checkForUpdates", t);
                main.post(() -> {
                    dismiss(progress);
                    if (activity.isFinishing()) return;
                    showInfo(activity, "❌\u00A0Error\u00A0de\u00A0red",
                            "No\u00A0se\u00A0pudo\u00A0consultar\u00A0GitHub.\n\nDetalle:\n" + t);
                });
            }
        }).start();
    }

    // ============================ DESCARGA + INSTALACIÓN ============================

    private void descargarEInstalar(final Activity activity, final String apkUrl,
                                    final String versionName) {
        // Verificar permiso de "instalar apps desconocidas" (API 26+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            boolean allowed = activity.getPackageManager().canRequestPackageInstalls();
            if (!allowed) {
                new AlertDialog.Builder(activity)
                        .setTitle("Permiso\u00A0necesario")
                        .setMessage("Para\u00A0instalar\u00A0la\u00A0actualización,\u00A0activa\u00A0"
                                + "\"Permitir\u00A0de\u00A0esta\u00A0fuente\"\u00A0e\u00A0inténtalo\u00A0de\u00A0nuevo.")
                        .setPositiveButton("Abrir\u00A0ajustes", (d, w) -> {
                            try {
                                Intent intent = new Intent(
                                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + activity.getPackageName()));
                                activity.startActivity(intent);
                            } catch (Throwable t) {
                                showInfo(activity, "Error",
                                        "No\u00A0se\u00A0pudo\u00A0abrir\u00A0los\u00A0ajustes:\u00A0" + t);
                            }
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                return;
            }
        }

        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMessage("Descargando\u00A0versión\u00A0" + versionName + "…");
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                File dir = activity.getExternalFilesDir("updates");
                if (dir == null) dir = activity.getFilesDir();
                if (!dir.exists()) dir.mkdirs();
                final File apkFile = new File(dir, "PalletTracker-update.apk");
                if (apkFile.exists()) apkFile.delete();

                HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("Accept", "application/octet-stream");
                conn.connect();

                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new RuntimeException("HTTP\u00A0" + code + "\u00A0al\u00A0descargar\u00A0el\u00A0APK");
                }

                int total = conn.getContentLength();
                InputStream in = new BufferedInputStream(conn.getInputStream());
                FileOutputStream out = new FileOutputStream(apkFile);
                byte[] buf = new byte[8192];
                int read;
                long downloaded = 0;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    if (total > 0) {
                        final int pct = (int) (downloaded * 100 / total);
                        main.post(() -> progress.setProgress(pct));
                    }
                }
                out.flush();
                out.close();
                in.close();
                conn.disconnect();

                main.post(() -> {
                    dismiss(progress);
                    if (activity.isFinishing()) return;
                    instalarApk(activity, apkFile);
                });

            } catch (Throwable t) {
                Log.e(TAG, "Error descargando APK", t);
                PalletTrackerApp.logException(activity.getApplicationContext(),
                        "UpdateChecker.descargar", t);
                main.post(() -> {
                    dismiss(progress);
                    if (activity.isFinishing()) return;
                    showInfo(activity, "❌\u00A0Error\u00A0al\u00A0descargar",
                            "No\u00A0se\u00A0pudo\u00A0descargar\u00A0la\u00A0actualización.\n\nDetalle:\n" + t);
                });
            }
        }).start();
    }

    private void instalarApk(Activity activity, File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apkFile);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Error instalando APK", t);
            PalletTrackerApp.logException(activity.getApplicationContext(),
                    "UpdateChecker.instalar", t);
            showInfo(activity, "❌\u00A0Error\u00A0al\u00A0instalar",
                    "No\u00A0se\u00A0pudo\u00A0iniciar\u00A0la\u00A0instalación.\n\nDetalle:\n" + t);
        }
    }

    // ============================ PARSEO ============================

    /** Devuelve la URL del asset .apk de PalletTracker, o null si no hay. */
    private String findPalletTrackerApk(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            String name = a.optString("name", "");
            String lower = name.toLowerCase();
            if (lower.contains("pallettracker") && lower.endsWith(".apk")) {
                return a.optString("browser_download_url", null);
            }
        }
        return null;
    }

    /** versionCode: primero del cuerpo (versionCode=NN), si no, -1. */
    private int parseVersionCode(JSONObject release) {
        String body = release.optString("body", "");
        Matcher m = Pattern.compile("versionCode\\s*[=:]?\\s*(\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(body);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) { }
        }
        // Fallback: derivar de un versionName tipo 4.2 → 4*100+2.
        String vn = parseVersionName(release);
        if (vn != null) {
            try {
                String[] parts = vn.split("\\.");
                int major = parts.length > 0 ? Integer.parseInt(parts[0].trim()) : 0;
                int minor = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
                return major * 100 + minor;
            } catch (Exception ignored) { }
        }
        return -1;
    }

    /** versionName a partir del tag (p.ej. "pallettracker-v4.2" → "4.2"). */
    private String parseVersionName(JSONObject release) {
        String tag = release.optString("tag_name", "");
        Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(tag);
        if (m.find()) return m.group(1);
        String name = release.optString("name", "");
        m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(name);
        if (m.find()) return m.group(1);
        return null;
    }

    private int getInstalledVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pi.getLongVersionCode();
            }
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    private String getInstalledVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ============================ HTTP + UI ============================

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "PalletTracker-UpdateChecker");
        conn.connect();

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
        }
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("GitHub respondió HTTP " + code + ": " + sb);
        }
        return sb.toString();
    }

    private void showInfo(Activity activity, String title, String message) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Entendido", null)
                .show();
    }

    private void dismiss(ProgressDialog d) {
        try { if (d != null && d.isShowing()) d.dismiss(); } catch (Throwable ignored) { }
    }
}
