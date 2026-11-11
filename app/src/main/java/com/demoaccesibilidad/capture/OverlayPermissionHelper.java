package com.demoaccesibilidad.capture;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/**
 * Helper para verificar y solicitar el permiso de overlay (SYSTEM_ALERT_WINDOW).
 *
 * Flujo:
 *  1. canDrawOverlays() → ¿ya tiene permiso?
 *  2. Si no → mostrar diálogo explicativo
 *  3. Abrir Settings.ACTION_MANAGE_OVERLAY_PERMISSION
 *  4. Al regresar, verificar de nuevo
 */
public class OverlayPermissionHelper {

    public static final int REQUEST_OVERLAY = 5678;

    /**
     * Verifica si la app tiene permiso de overlay.
     */
    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; // Pre-M no necesita permiso explícito.
    }

    /**
     * Muestra un diálogo explicativo y luego abre la configuración de overlay.
     */
    public static void requestOverlayPermission(Activity activity) {
        if (hasOverlayPermission(activity)) return;

        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.dlg_overlay_titulo))
                .setMessage(activity.getString(R.string.dlg_overlay_mensaje))
                .setPositiveButton(activity.getString(R.string.dlg_overlay_abrir), (d, w) -> {
                    openOverlaySettings(activity);
                })
                .setNegativeButton(activity.getString(R.string.dlg_overlay_despues), null)
                .setCancelable(true)
                .show();
    }

    /**
     * Abre directamente la pantalla de permisos de overlay.
     */
    public static void openOverlaySettings(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())
            );
            try {
                activity.startActivityForResult(intent, REQUEST_OVERLAY);
            } catch (Exception e) {
                // Fallback: abrir ajustes generales de apps.
                Intent fallback = new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
                activity.startActivity(fallback);
            }
        }
    }
}
