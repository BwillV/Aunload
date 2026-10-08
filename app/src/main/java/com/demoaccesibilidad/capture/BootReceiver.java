package com.demoaccesibilidad.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * BootReceiver — v4.3.
 *
 * Al encender el escáner (BOOT_COMPLETED) comprueba si el servicio de
 * accesibilidad de PalletTracker quedó desactivado (algo que ocurre al apagar
 * los equipos de un día para otro). Si es así:
 *   1. Publica una notificación de PRIORIDAD ALTA que, al tocarla, abre
 *      directamente los Ajustes de accesibilidad.
 *   2. Intenta arrancar el overlay flotante (si ya hay permiso de overlay),
 *      que mostrará el aviso rojo "!" para reactivar la accesibilidad.
 *
 * NOTA IMPORTANTE: Android NO permite reactivar la accesibilidad de forma
 * automática sin root/ADB. Lo máximo que puede hacer una app es avisar y
 * llevar al usuario a la pantalla correcta.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final String CHANNEL_BOOT = "accesibilidad_boot_channel";
    private static final int NOTIF_BOOT_ID = 9100;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }

        try {
            boolean activo = AccessibilityHelper.isServiceEnabled(context);
            Log.d(TAG, "BOOT_COMPLETED — accesibilidad activa=" + activo);

            if (!activo) {
                notificarAccesibilidadDesactivada(context);
            }

            // Arrancar el overlay si ya tenemos permiso (muestra el aviso rojo).
            try {
                if (Settings.canDrawOverlays(context)) {
                    Intent svc = new Intent(context, FloatingButtonService.class);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(svc);
                    } else {
                        context.startService(svc);
                    }
                }
            } catch (Throwable t) {
                PalletTrackerApp.logException(context.getApplicationContext(),
                        "BootReceiver.startOverlay", t);
            }
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "BootReceiver.onReceive", t);
        }
    }

    private void notificarAccesibilidadDesactivada(Context context) {
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_BOOT,
                        "Aviso\u00A0de\u00A0accesibilidad",
                        NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Avisa cuando la accesibilidad de "
                        + "PalletTracker está desactivada tras reiniciar.");
                nm.createNotificationChannel(channel);
            }

            // Al tocar la notificación: abrir Ajustes de accesibilidad.
            Intent settings = AccessibilityHelper.buildAccessibilitySettingsIntent(context);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(context, 0, settings, flags);

            Notification notif = new NotificationCompat.Builder(context, CHANNEL_BOOT)
                    .setContentTitle("⚠️\u00A0Activa\u00A0la\u00A0accesibilidad\u00A0de\u00A0PalletTracker")
                    .setContentText("Sin\u00A0ella\u00A0no\u00A0se\u00A0cuentan\u00A0las\u00A0pallets.\u00A0"
                            + "Toca\u00A0para\u00A0activarla.")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(
                            "El\u00A0servicio\u00A0de\u00A0accesibilidad\u00A0quedó\u00A0desactivado\u00A0"
                                    + "tras\u00A0reiniciar\u00A0el\u00A0equipo.\u00A0Toca\u00A0esta\u00A0"
                                    + "notificación\u00A0para\u00A0activarlo\u00A0y\u00A0seguir\u00A0"
                                    + "contando\u00A0pallets\u00A0automáticamente."))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build();

            nm.notify(NOTIF_BOOT_ID, notif);
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "BootReceiver.notificar", t);
        }
    }
}
