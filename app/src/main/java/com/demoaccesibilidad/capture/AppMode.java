package com.demoaccesibilidad.capture;

import android.content.Context;

/**
 * Modo de trabajo de la app: Inbound (recepción, por defecto), Inventario
 * o Picking (v5.15, por Muse). Se guarda en SharedPreferences para que lo
 * compartan la pantalla principal y el menú del botón flotante (FAB).
 * No sincroniza entre dispositivos: cada escáner elige su propio modo
 * según la tarea que esté haciendo.
 *
 * Picking es un modo APARTE, para que PickTracker quede ordenado: solo en
 * modo Picking el detector lee la lista y el detalle de Pick Task, y solo
 * ahí aparece el botón 📊 PickTracker en el menú del FAB. En Inbound e
 * Inventario PickTracker no hace nada (ni lee pantallas ni toca datos).
 */
public final class AppMode {

    public static final int MODE_INBOUND = 0;
    public static final int MODE_INVENTARIO = 1;
    public static final int MODE_PICKING = 2;

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

    public static boolean isPicking(Context c) {
        return getMode(c) == MODE_PICKING;
    }

    public static void setInventario(Context c, boolean inventario) {
        setMode(c, inventario ? MODE_INVENTARIO : MODE_INBOUND);
    }

    public static String nombre(Context c) {
        Context lc = LocaleHelper.wrap(c);
        int mode = getMode(c);
        if (mode == MODE_INVENTARIO) return lc.getString(R.string.appmode_inventario);
        if (mode == MODE_PICKING) return lc.getString(R.string.appmode_picking);
        return lc.getString(R.string.appmode_inbound);
    }
}
