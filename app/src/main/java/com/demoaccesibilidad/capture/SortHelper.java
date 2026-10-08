package com.demoaccesibilidad.capture;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v4.1: Helper de ordenamiento de pallets, compartido entre MainActivity
 * y TaskDetailActivity. La opción elegida se guarda en SharedPreferences
 * para que ambas pantallas usen el mismo orden.
 */
public final class SortHelper {

    public static final int SORT_ENTRY = 0; // orden de entrada (por defecto)
    public static final int SORT_SKU = 1;   // alfabético por SKU
    public static final int SORT_ILP = 2;   // por parte numérica del ILP

    private static final String PREFS = "sort_prefs";
    private static final String KEY = "pallet_sort_mode";
    private static final Pattern NUM = Pattern.compile("(\\d+)");

    private SortHelper() { }

    public static int getMode(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY, SORT_ENTRY);
    }

    public static void setMode(Context ctx, int mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY, mode).apply();
    }

    public static String label(int mode) {
        switch (mode) {
            case SORT_SKU: return "Por\u00A0SKU";
            case SORT_ILP: return "Por\u00A0ILP\u00A0(menor\u00A0a\u00A0mayor)";
            default:       return "Orden\u00A0de\u00A0entrada";
        }
    }

    /**
     * Devuelve una copia ordenada de la lista según el modo indicado.
     */
    public static List<Pallet> sort(List<Pallet> input, int mode) {
        List<Pallet> list = new ArrayList<>(input != null ? input : new ArrayList<Pallet>());
        switch (mode) {
            case SORT_SKU:
                Collections.sort(list, (a, b) -> {
                    String sa = a.sku == null ? "" : a.sku.trim();
                    String sb = b.sku == null ? "" : b.sku.trim();
                    boolean ea = sa.isEmpty(), eb = sb.isEmpty();
                    if (ea && eb) return Long.compare(a.timestamp, b.timestamp);
                    if (ea) return 1;   // SKU vacío al final
                    if (eb) return -1;
                    int c = sa.compareToIgnoreCase(sb);
                    return c != 0 ? c : Long.compare(a.timestamp, b.timestamp);
                });
                break;
            case SORT_ILP:
                Collections.sort(list, (a, b) -> {
                    long na = ilpNum(a.palletCode), nb = ilpNum(b.palletCode);
                    boolean va = na >= 0, vb = nb >= 0;
                    if (!va && !vb) return Long.compare(a.timestamp, b.timestamp);
                    if (!va) return 1;  // sin número de ILP al final
                    if (!vb) return -1;
                    int c = Long.compare(na, nb);
                    return c != 0 ? c : Long.compare(a.timestamp, b.timestamp);
                });
                break;
            default: // SORT_ENTRY — primera pallet escaneada primero.
                Collections.sort(list, (a, b) -> Long.compare(a.timestamp, b.timestamp));
                break;
        }
        return list;
    }

    private static long ilpNum(String code) {
        if (code == null) return -1;
        Matcher m = NUM.matcher(code);
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }
}
