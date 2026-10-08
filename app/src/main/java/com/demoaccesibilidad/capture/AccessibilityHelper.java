package com.demoaccesibilidad.capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityManager;

import java.util.List;

/**
 * AccessibilityHelper — utilidades para saber si el servicio de accesibilidad
 * de PalletTracker (ScreenCaptureService) está realmente activo.
 *
 * v4.3. En los escáner Honeywell/iData, al apagarlos de un día para otro,
 * el sistema a veces le quita el permiso de accesibilidad. Esta clase permite
 * detectarlo de forma fiable y abrir directamente los Ajustes de accesibilidad
 * para reactivarlo.
 */
public final class AccessibilityHelper {

    private AccessibilityHelper() { }

    /** ComponentName completo del servicio de accesibilidad de la app. */
    public static ComponentName getServiceComponent(Context ctx) {
        return new ComponentName(ctx.getPackageName(),
                ScreenCaptureService.class.getName());
    }

    /**
     * Devuelve true si el ScreenCaptureService está habilitado como servicio
     * de accesibilidad. Usa DOS comprobaciones complementarias para máxima
     * fiabilidad en Android 9 sin GMS:
     *  1. AccessibilityManager.getEnabledAccessibilityServiceList()
     *  2. Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES (comparando el
     *     ComponentName aplanado).
     */
    public static boolean isServiceEnabled(Context ctx) {
        if (ctx == null) return false;
        try {
            if (isEnabledViaManager(ctx)) return true;
        } catch (Throwable ignored) { }
        try {
            return isEnabledViaSettings(ctx);
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean isEnabledViaManager(Context ctx) {
        AccessibilityManager am = (AccessibilityManager)
                ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<android.accessibilityservice.AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(
                        android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (list == null) return false;
        String pkg = ctx.getPackageName();
        String cls = ScreenCaptureService.class.getName();
        for (android.accessibilityservice.AccessibilityServiceInfo info : list) {
            if (info == null || info.getResolveInfo() == null) continue;
            android.content.pm.ServiceInfo si = info.getResolveInfo().serviceInfo;
            if (si == null) continue;
            if (pkg.equals(si.packageName) && cls.equals(si.name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEnabledViaSettings(Context ctx) {
        ComponentName expected = getServiceComponent(ctx);
        String expectedFlat = expected.flattenToString();
        String expectedShort = expected.flattenToShortString();

        String enabled = Settings.Secure.getString(
                ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            String component = splitter.next();
            if (component == null) continue;
            if (component.equalsIgnoreCase(expectedFlat)
                    || component.equalsIgnoreCase(expectedShort)) {
                return true;
            }
            ComponentName parsed = ComponentName.unflattenFromString(component);
            if (parsed != null
                    && parsed.getPackageName().equals(expected.getPackageName())
                    && parsed.getClassName().equals(expected.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Crea el Intent que abre los Ajustes de accesibilidad del sistema.
     * Intenta resaltar nuestro servicio con los extras de "show_fragment_args"
     * (funciona en algunas ROM; en Android 9 estándar al menos abre la lista).
     */
    public static Intent buildAccessibilitySettingsIntent(Context ctx) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            String flat = getServiceComponent(ctx).flattenToString();
            android.os.Bundle args = new android.os.Bundle();
            args.putString(":settings:fragment_args_key", flat);
            intent.putExtra(":settings:fragment_args_key", flat);
            intent.putExtra(":settings:show_fragment_args", args);
        } catch (Throwable ignored) { }
        return intent;
    }
}
