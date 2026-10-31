package com.demoaccesibilidad.capture;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * CopyTextHelper — Función "Copiar texto" (v5.4).
 *
 * Permite copiar al portapapeles textos de la pantalla del WMS que NO son
 * seleccionables dentro del propio WMS. Se dispara desde el botón flotante.
 *
 * Dos variantes (ambas aprobadas por el usuario):
 *   1) Lista para elegir: overlay con TODOS los textos visibles de la ventana
 *      activa. Al tocar uno se copia; botones "Copiar todo" y "Cerrar".
 *   2) Copiado directo: "Copiar ILP" y "Copiar ubicación" buscan el primer
 *      texto que coincida con el patrón y lo copian al instante.
 *
 * Los textos se obtienen de ScreenCaptureService (único con acceso al árbol de
 * accesibilidad). Todas las ventanas usan TYPE_APPLICATION_OVERLAY, el mismo
 * tipo que el FAB, para poder dibujarse sobre el WMS.
 *
 * Android 9 (API 28, sin GMS): el portapapeles se usa desde el contexto de
 * servicio en el hilo principal.
 */
public class CopyTextHelper {

    // ═══ Ajuste "Modo copiar texto" ═══
    private static final String PREFS = "copy_prefs";
    private static final String KEY_COPY_MODE = "copy_mode_enabled";

    // Colores (mismo tema oscuro que los demás overlays).
    private static final int COLOR_BG = 0xFF1a1a2e;
    private static final int COLOR_CARD = 0xFF2a2a4a;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HINT = 0xFF9E9E9E;
    private static final int COLOR_BLUE = 0xFF2196F3;
    private static final int COLOR_GREEN = 0xFF4CAF50;
    private static final int COLOR_GRAY = 0xFF607D8B;
    private static final int COLOR_INDIGO = 0xFF3F51B5;

    // =============================================================
    //                 AJUSTE: MODO COPIAR TEXTO
    // =============================================================

    /** Activado por defecto. */
    public static boolean isEnabled(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_COPY_MODE, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setEnabled(Context c, boolean enabled) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_COPY_MODE, enabled).apply();
        } catch (Exception ignored) { }
    }

    // =============================================================
    //                 PORTAPAPELES
    // =============================================================

    /** Copia al portapapeles y muestra un Toast "Copiado: <texto>". */
    public static void copyToClipboard(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return;
        try {
            ClipboardManager clip = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clip != null) {
                clip.setPrimaryClip(ClipData.newPlainText("PalletTracker", text));
            }
            String preview = text.length() > 40 ? text.substring(0, 40) + "…" : text;
            Toast.makeText(context, "Copiado:\u00A0" + preview, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "CopyTextHelper.copyToClipboard", t);
        }
    }

    // =============================================================
    //        MENÚ DE ACCIONES (se abre desde el FAB)
    // =============================================================

    /**
     * Pequeño menú overlay con las acciones de copiado. onToggleYoTotal preserva
     * la acción original del toque del FAB (alternar entre "Yo" y "Total").
     */
    public static void showActionMenu(final Context context, final WindowManager wm,
                                      final Runnable onToggleYoTotal) {
        final LinearLayout root = crearRoot(context);

        TextView titulo = crearTexto(context, "📋\u00A0Copiar\u00A0texto", 18, true);
        titulo.setPadding(0, 0, 0, dp(context, 4));
        root.addView(titulo);

        TextView sub = crearTexto(context,
                "Elige\u00A0qué\u00A0copiar\u00A0de\u00A0la\u00A0pantalla\u00A0del\u00A0WMS", 12, false);
        sub.setTextColor(COLOR_HINT);
        sub.setPadding(0, 0, 0, dp(context, 14));
        root.addView(sub);

        final WindowManager.LayoutParams params = crearParams(false);

        // Lista de todos los textos.
        Button btnLista = crearBoton(context, "📝\u00A0Elegir\u00A0texto\u00A0de\u00A0la\u00A0lista", COLOR_BLUE);
        btnLista.setOnClickListener(v -> {
            removerOverlay(wm, root);
            abrirListaTextos(context, wm);
        });
        root.addView(btnLista);
        addSpacer(context, root, 8);

        // Copiar ILP directo.
        Button btnIlp = crearBoton(context, "🏷\u00A0Copiar\u00A0ILP", COLOR_GREEN);
        btnIlp.setOnClickListener(v -> {
            removerOverlay(wm, root);
            copiarDirecto(context, true);
        });
        root.addView(btnIlp);
        addSpacer(context, root, 8);

        // Copiar ubicación directa.
        Button btnLoc = crearBoton(context, "📍\u00A0Copiar\u00A0ubicación", COLOR_GREEN);
        btnLoc.setOnClickListener(v -> {
            removerOverlay(wm, root);
            copiarDirecto(context, false);
        });
        root.addView(btnLoc);
        addSpacer(context, root, 8);

        // Alternar Yo / Total (acción original del toque del FAB, preservada).
        if (onToggleYoTotal != null) {
            Button btnModo = crearBoton(context, "🔁\u00A0Alternar\u00A0Yo\u00A0/\u00A0Total", COLOR_INDIGO);
            btnModo.setOnClickListener(v -> {
                removerOverlay(wm, root);
                onToggleYoTotal.run();
            });
            root.addView(btnModo);
            addSpacer(context, root, 8);
        }

        // Cerrar.
        Button btnCerrar = crearBoton(context, "Cerrar", COLOR_GRAY);
        btnCerrar.setOnClickListener(v -> removerOverlay(wm, root));
        root.addView(btnCerrar);

        try {
            wm.addView(root, params);
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "CopyTextHelper.showActionMenu", t);
        }
    }

    // =============================================================
    //        VARIANTE 1: LISTA DE TEXTOS PARA ELEGIR
    // =============================================================

    private static void abrirListaTextos(final Context context, final WindowManager wm) {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) {
            Toast.makeText(context,
                    "Activa\u00A0el\u00A0servicio\u00A0de\u00A0Accesibilidad\u00A0para\u00A0copiar",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final List<String> textos = svc.getVisibleTextsForCopy();
        if (textos == null || textos.isEmpty()) {
            Toast.makeText(context,
                    "No\u00A0se\u00A0encontraron\u00A0textos\u00A0en\u00A0esta\u00A0pantalla",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final LinearLayout root = crearRoot(context);

        TextView titulo = crearTexto(context,
                "📝\u00A0Toca\u00A0un\u00A0texto\u00A0para\u00A0copiarlo", 16, true);
        titulo.setPadding(0, 0, 0, dp(context, 4));
        root.addView(titulo);

        TextView sub = crearTexto(context,
                textos.size() + "\u00A0textos\u00A0en\u00A0pantalla", 12, false);
        sub.setTextColor(COLOR_HINT);
        sub.setPadding(0, 0, 0, dp(context, 10));
        root.addView(sub);

        // Lista desplazable.
        ScrollView scroll = new ScrollView(context);
        int maxH = (int) (context.getResources().getDisplayMetrics().heightPixels * 0.5f);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, maxH));
        LinearLayout lista = new LinearLayout(context);
        lista.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(lista);

        final WindowManager.LayoutParams params = crearParams(false);

        for (final String texto : textos) {
            TextView item = new TextView(context);
            item.setText(texto);
            item.setTextColor(COLOR_TEXT);
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            item.setBackgroundColor(COLOR_CARD);
            item.setPadding(dp(context, 12), dp(context, 12),
                    dp(context, 12), dp(context, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(context, 6);
            item.setLayoutParams(lp);
            item.setOnClickListener(v -> {
                copyToClipboard(context, texto);
                removerOverlay(wm, root);
            });
            lista.addView(item);
        }

        root.addView(scroll);
        addSpacer(context, root, 12);

        // Botones Copiar todo / Cerrar.
        LinearLayout fila = new LinearLayout(context);
        fila.setOrientation(LinearLayout.HORIZONTAL);

        Button btnTodo = crearBoton(context, "Copiar\u00A0todo", COLOR_BLUE);
        btnTodo.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnTodo.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (String s : textos) sb.append(s).append("\n");
            copyToClipboard(context, sb.toString().trim());
            removerOverlay(wm, root);
        });
        fila.addView(btnTodo);

        addSpacerH(context, fila, 8);

        Button btnCerrar = crearBoton(context, "Cerrar", COLOR_GRAY);
        btnCerrar.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnCerrar.setOnClickListener(v -> removerOverlay(wm, root));
        fila.addView(btnCerrar);

        root.addView(fila);

        try {
            wm.addView(root, params);
        } catch (Throwable t) {
            PalletTrackerApp.logException(context.getApplicationContext(),
                    "CopyTextHelper.abrirListaTextos", t);
        }
    }

    // =============================================================
    //        VARIANTE 2: COPIADO DIRECTO (ILP / UBICACIÓN)
    // =============================================================

    private static void copiarDirecto(Context context, boolean ilp) {
        ScreenCaptureService svc = ScreenCaptureService.getInstance();
        if (svc == null) {
            Toast.makeText(context,
                    "Activa\u00A0el\u00A0servicio\u00A0de\u00A0Accesibilidad\u00A0para\u00A0copiar",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String valor = ilp ? svc.findFirstIlpOnScreen() : svc.findFirstLocationOnScreen();
        if (valor == null || valor.trim().isEmpty()) {
            Toast.makeText(context,
                    ilp ? "No\u00A0se\u00A0encontró\u00A0ILP\u00A0en\u00A0esta\u00A0pantalla"
                        : "No\u00A0se\u00A0encontró\u00A0ubicación\u00A0en\u00A0esta\u00A0pantalla",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        copyToClipboard(context, valor);
    }

    // =============================================================
    //                 HELPERS DE UI
    // =============================================================

    private static LinearLayout crearRoot(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(context, 20), dp(context, 18),
                dp(context, 20), dp(context, 18));
        root.setElevation(dp(context, 16));
        return root;
    }

    private static TextView crearTexto(Context context, String text, int sizeSp, boolean bold) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(COLOR_TEXT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) tv.setTypeface(null, android.graphics.Typeface.BOLD);
        return tv;
    }

    private static Button crearBoton(Context context, String text, int bgColor) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextColor(COLOR_TEXT);
        btn.setBackgroundColor(bgColor);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setPadding(dp(context, 16), dp(context, 12),
                dp(context, 16), dp(context, 12));
        return btn;
    }

    private static void addSpacer(Context context, LinearLayout parent, int dpHeight) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, dpHeight)));
        parent.addView(spacer);
    }

    private static void addSpacerH(Context context, LinearLayout parent, int dpWidth) {
        View spacer = new View(context);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                dp(context, dpWidth), LinearLayout.LayoutParams.MATCH_PARENT));
        parent.addView(spacer);
    }

    private static WindowManager.LayoutParams crearParams(boolean focusable) {
        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        int flags = focusable
                ? WindowManager.LayoutParams.FLAG_DIM_BEHIND
                : (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                flags,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;
        params.width = (int) (340 * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
        if (focusable) params.dimAmount = 0.5f;
        return params;
    }

    private static void removerOverlay(WindowManager wm, View view) {
        try {
            wm.removeView(view);
        } catch (Exception ignored) { }
    }

    private static int dp(Context context, int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
