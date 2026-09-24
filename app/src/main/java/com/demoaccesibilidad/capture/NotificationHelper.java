package com.demoaccesibilidad.capture;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;

/**
 * NotificationHelper — notificaciones para eventos multi-dispositivo.
 *
 * Tipos de notificación:
 *  1. Otro dispositivo escaneó un pallet
 *  2. Container completado
 *  3. Dispositivo conectado/desconectado
 *
 * Sonido y vibración opcionales (controlados por SharedPreferences).
 */
public class NotificationHelper {

    private static final String CHANNEL_SYNC = "pallet_sync_channel";
    private static final String CHANNEL_COMPLETE = "pallet_complete_channel";
    private static final String PREFS_NAME = "notification_prefs";
    private static final String KEY_SOUND = "notification_sound";
    private static final String KEY_VIBRATION = "notification_vibration";

    private final Context context;
    private final NotificationManager notifManager;
    private final SharedPreferences prefs;
    private int notifIdCounter = 1000;

    public NotificationHelper(Context ctx) {
        this.context = ctx.getApplicationContext();
        this.notifManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        crearCanales();
    }

    // ====================== CANALES ======================

    private void crearCanales() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Canal para sincronización.
            NotificationChannel syncChannel = new NotificationChannel(
                    CHANNEL_SYNC,
                    "Sincronización\u00A0de\u00A0pallets",
                    NotificationManager.IMPORTANCE_LOW
            );
            syncChannel.setDescription("Notificaciones cuando otros dispositivos escanean.");

            // Canal para completado (más prominente).
            NotificationChannel completeChannel = new NotificationChannel(
                    CHANNEL_COMPLETE,
                    "Container\u00A0completado",
                    NotificationManager.IMPORTANCE_HIGH
            );
            completeChannel.setDescription("Alerta cuando se completa un container.");
            completeChannel.enableVibration(true);
            completeChannel.setVibrationPattern(new long[]{0, 300, 100, 300});
            completeChannel.setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .build()
            );

            if (notifManager != null) {
                notifManager.createNotificationChannel(syncChannel);
                notifManager.createNotificationChannel(completeChannel);
            }
        }
    }

    // ====================== NOTIFICACIONES ======================

    /**
     * Notificación discreta: otro dispositivo escaneó un pallet.
     */
    public void notifyRemotePalletScanned(String deviceName, String palletCode,
                                          int currentTotal, int expected) {
        if (!isSoundEnabled() && !isVibrationEnabled()) return;

        String title = "📦\u00A0" + deviceName + "\u00A0escaneó";
        String text = palletCode + "\u00A0—\u00A0" + currentTotal + "/" + expected;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_SYNC)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true);

        if (!isSoundEnabled()) builder.setSound(null);

        if (notifManager != null) {
            notifManager.notify(notifIdCounter++, builder.build());
        }

        if (isVibrationEnabled()) vibrarCorto();
    }

    /**
     * Notificación prominente: ¡container completo!
     */
    public void notifyContainerComplete(String taskId, int totalPallets) {
        String title = "✅\u00A0¡Container\u00A0completo!";
        String text = taskId + "\u00A0—\u00A0" + totalPallets + "\u00A0pallets\u00A0escaneados";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_COMPLETE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        if (notifManager != null) {
            notifManager.notify(notifIdCounter++, builder.build());
        }

        vibrarCompletado();
    }

    /**
     * Notificación: dispositivo conectado/desconectado.
     */
    public void notifyDeviceStatusChanged(String deviceName, boolean connected) {
        String emoji = connected ? "🟢" : "⚫";
        String estado = connected ? "conectado" : "desconectado";
        String title = emoji + "\u00A0" + deviceName + "\u00A0" + estado;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_SYNC)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true);

        if (notifManager != null) {
            notifManager.notify(notifIdCounter++, builder.build());
        }
    }

    // ====================== VIBRACIÓN ======================

    /**
     * Vibración corta para feedback táctil del FAB.
     */
    public void vibrarCorto() {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(50);
            }
        } catch (Exception ignored) { }
    }

    /**
     * Vibración de completado (patrón de celebración).
     */
    public void vibrarCompletado() {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            long[] pattern = {0, 200, 100, 200, 100, 400};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (Exception ignored) { }
    }

    // ====================== CONFIGURACIÓN ======================

    public boolean isSoundEnabled() {
        return prefs.getBoolean(KEY_SOUND, true);
    }

    public void setSoundEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SOUND, enabled).apply();
    }

    public boolean isVibrationEnabled() {
        return prefs.getBoolean(KEY_VIBRATION, true);
    }

    public void setVibrationEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VIBRATION, enabled).apply();
    }
}
