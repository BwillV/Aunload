package com.demoaccesibilidad.capture;

import android.content.Context;

/**
 * Modo de trabajo de la app: Inbound (recepción, por defecto) o Inventario.
 * Se guarda en SharedPreferences para que lo compartan la pantalla principal
 * y el menú del botón flotante (FAB). No sincroniza entre dispositivos: cada
 * escáner elige su propio modo según la tarea que esté haciendo.
 */
public final class AppMode {

    public static final int MODE_INBOUND = 0;
    public static final int MODE_INVENTARIO = 1;

    private static final String PREFS = "app_mode_prefs";
    private static final String KEY = "app_mode";

    private AppMode() { }

    public static int getMode(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY, MODE_INBOUND);
        } catch (Throwable t) {
            return MODE_INBOUND;
        }
    }

    public static void setMode(Context c, int mode) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY, mode).apply();
        } catch (Throwable ignored) { }
    }

    public static boolean isInventario(Context c) {
        return getMode(c) == MODE_INVENTARIO;
    }

    public static void setInventario(Context c, boolean inventario) {
        setMode(c, inventario ? MODE_INVENTARIO : MODE_INBOUND);
    }

    public static String nombre(Context c) {
        Context lc = LocaleHelper.wrap(c);
        return isInventario(c)
                ? lc.getString(R.string.appmode_inventario)
                : lc.getString(R.string.appmode_inbound);
    }
}
