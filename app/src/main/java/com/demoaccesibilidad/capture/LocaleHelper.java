package com.demoaccesibilidad.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.view.ContextThemeWrapper;

import java.util.Locale;

/**
 * Centraliza la seleccion de idioma de la app.
 *
 * Idiomas soportados: "es" (por defecto), "en", "zh".
 * La eleccion se guarda en SharedPreferences y se aplica creando
 * un Context localizado (createConfigurationContext), tanto en
 * Activities (via attachBaseContext) como en servicios/overlays del FAB.
 */
public final class LocaleHelper {

    public static final String PREFS = "locale_prefs";
    public static final String KEY_LANG = "app_lang";

    public static final String ES = "es";
    public static final String EN = "en";
    public static final String ZH = "zh";

    private LocaleHelper() {}

    /** Devuelve el codigo de idioma guardado (por defecto "es"). */
    public static String getLanguage(Context context) {
        if (context == null) return ES;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LANG, ES);
    }

    /** Guarda la eleccion de idioma. */
    public static void setLanguage(Context context, String lang) {
        if (context == null) return;
        if (lang == null) lang = ES;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANG, lang).apply();
    }

    /** Convierte un codigo de idioma a Locale. */
    public static Locale toLocale(String lang) {
        if (lang == null) return new Locale(ES);
        switch (lang) {
            case EN:
                return Locale.ENGLISH;
            case ZH:
                return Locale.SIMPLIFIED_CHINESE; // zh-CN -> carpeta values-zh-rCN
            case ES:
            default:
                return new Locale(ES);
        }
    }

    /** Envuelve el context con el idioma guardado. */
    public static Context wrap(Context base) {
        return wrap(base, getLanguage(base));
    }

    /** Envuelve el context con el idioma indicado. */
    public static Context wrap(Context base, String lang) {
        if (base == null) return null;
        Locale locale = toLocale(lang);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLayoutDirection(locale);
        }
        return base.createConfigurationContext(config);
    }

    /**
     * Devuelve un Context localizado con el tema de la app aplicado.
     * Necesario para inflar layouts en overlays/servicios sin InflateException.
     */
    public static Context themedWrap(Context base) {
        Context localized = wrap(base);
        return new ContextThemeWrapper(localized, R.style.Theme_DiagnosticoDemo);
    }

    public static Context themedWrap(Context base, String lang) {
        Context localized = wrap(base, lang);
        return new ContextThemeWrapper(localized, R.style.Theme_DiagnosticoDemo);
    }
}
