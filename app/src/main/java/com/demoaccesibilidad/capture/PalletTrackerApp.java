package com.demoaccesibilidad.capture;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * PalletTrackerApp — clase Application con logger de crashes persistente.
 *
 * ╔══════════════════════════════════════════════════════╗
 * ║  v2.6 — ESTABILIDAD Y DIAGNÓSTICO                    ║
 * ║  • Captura CUALQUIER crash (Exception o Error) y lo   ║
 * ║    guarda en un archivo de texto legible.            ║
 * ║  • El archivo queda en:                              ║
 * ║      Android/data/com.demoaccesibilidad.capture/     ║
 * ║      files/crash_log.txt                              ║
 * ║  • Así, si la app se cierra sola, podemos ver el      ║
 * ║    error EXACTO sin necesidad de un cable/logcat.    ║
 * ╚══════════════════════════════════════════════════════╝
 *
 * Nota: usa Throwable (no solo Exception) para atrapar también
 * NoClassDefFoundError / LinkageError, típicos en dispositivos
 * sin Google Play Services (como los escáner Honeywell).
 */
public class PalletTrackerApp extends Application {

    private static final String TAG = "PalletTrackerApp";
    private static final String CRASH_FILE = "crash_log.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        instalarManejadorDeCrashes();
    }

    /**
     * Instala un handler global que atrapa cualquier excepción/error no
     * controlado, lo escribe a un archivo legible y luego deja que el
     * sistema muestre el diálogo normal.
     */
    private void instalarManejadorDeCrashes() {
        final Thread.UncaughtExceptionHandler defaultHandler =
                Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                guardarCrash(getApplicationContext(), thread, throwable);
            } catch (Throwable ignored) {
                // Nunca fallar dentro del handler.
            }
            // Pasar el control al handler por defecto (muestra "keeps stopping").
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            } else {
                System.exit(2);
            }
        });
    }

    /**
     * Registra un error NO fatal (atrapado en un try/catch) en el mismo
     * archivo de log, sin cerrar la app. Útil para diagnosticar fallos
     * en pantallas concretas (p.ej. Ajustes) en dispositivos sin cable.
     */
    public static void logException(Context ctx, String origen, Throwable throwable) {
        try {
            Thread t = Thread.currentThread();
            guardarCrash(ctx, t, new RuntimeException("[NO-FATAL] " + origen, throwable));
        } catch (Throwable ignored) { }
    }

    /**
     * Escribe el stack trace completo a crash_log.txt (external files dir,
     * accesible por el administrador de archivos, sin permisos extra).
     */
    private static void guardarCrash(Context ctx, Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);

        String fecha = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());

        pw.println("======================================================");
        pw.println("  PalletTracker CRASH");
        pw.println("  Fecha:    " + fecha);
        pw.println("  Hilo:     " + (thread != null ? thread.getName() : "?"));
        pw.println("  Modelo:   " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("  Android:  " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println("------------------------------------------------------");
        throwable.printStackTrace(pw);
        pw.println("======================================================");
        pw.println();
        pw.flush();

        String contenido = sw.toString();

        // 1) A logcat.
        Log.e(TAG, "CRASH capturado:\n" + contenido);

        // 2) A archivo en external files dir (accesible por el usuario).
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir != null) {
                File f = new File(dir, CRASH_FILE);
                // Append para conservar historial.
                FileWriter fw = new FileWriter(f, true);
                fw.write(contenido);
                fw.close();
            }
        } catch (Throwable ignored) { }

        // 3) Copia también en internal files dir por si acaso.
        try {
            File f = new File(ctx.getFilesDir(), CRASH_FILE);
            FileWriter fw = new FileWriter(f, true);
            fw.write(contenido);
            fw.close();
        } catch (Throwable ignored) { }
    }
}
