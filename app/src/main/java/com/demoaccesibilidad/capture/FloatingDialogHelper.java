package com.demoaccesibilidad.capture;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * FloatingDialogHelper — Muestra diálogos FLOTANTES (overlay) sobre el WMS.
 *
 * Usa WindowManager con TYPE_APPLICATION_OVERLAY para que los diálogos
 * aparezcan sobre todas las apps, no solo dentro de PalletTracker.
 *
 * Diálogos:
 *  1. Container Options: Nuevo Container / Actualizar meta
 *  2. Reset: Confirmar borrado de datos
 *  3. Nuevo Container: Input de código + meta
 *  4. Actualizar meta: Solo cambia la meta
 */
public class FloatingDialogHelper {

    private static final String PREFS_FAB = "fab_prefs";
    private static final String KEY_FAB_MODE = "fab_display_mode";
    private static final String KEY_EXPECTED_PALLETS = "expected_pallets";

    // Colores del tema oscuro.
    private static final int COLOR_BG = 0xFF1a1a2e;
    private static final int COLOR_CARD = 0xFF2a2a4a;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_HINT = 0xFF888888;
    private static final int COLOR_BLUE = 0xFF2196F3;
    private static final int COLOR_GREEN = 0xFF4CAF50;
    private static final int COLOR_RED = 0xFFF44336;
    private static final int COLOR_GRAY = 0xFF607D8B;

    // =============================================================
    //         DIALOG: OPCIONES DE CONTAINER (DOBLE TAP)
    // =============================================================

    public static void showContainerDialog(Context context, WindowManager wm,
                                           Runnable onDismiss) {
        // Layout principal.
        LinearLayout root = crearRootLayout(context);

        // Título.
        TextView titulo = crearTexto(context, "📦\u00A0Container", 20, true);
        titulo.setPadding(0, 0, 0, dp(context, 12));
        root.addView(titulo);

        // Info actual (v4.0 — solo containers).
        PalletRepository repo = new PalletRepository(context);
        int scanned = repo.getPalletCount();
        String containerActual = repo.getCurrentContainerCode();

        String infoText = containerActual + "\u00A0•\u00A0" + scanned + "\u00A0pallets";
        TextView txtInfo = crearTexto(context, infoText, 14, false);
        txtInfo.setTextColor(COLOR_HINT);
        txtInfo.setPadding(0, 0, 0, dp(context, 16));
        root.addView(txtInfo);

        // Overlay params.
        final WindowManager.LayoutParams params = crearOverlayParams();

        // Botón: Nuevo Container (v4.0 — NO borra pallets, solo abre uno nuevo).
        Button btnNuevo = crearBoton(context, "🆕\u00A0Nuevo\u00A0Container", COLOR_BLUE);
        btnNuevo.setOnClickListener(v -> {
            removerOverlay(wm, root);
            PalletRepository r = new PalletRepository(context);
            String nuevo = r.startNewContainer();
            try {
                FirebaseManager.getInstance(context).setContainerCode(nuevo);
            } catch (Exception ignored) { }
            Toast.makeText(context,
                    "🆕\u00A0Empezamos\u00A0" + nuevo + "\u00A0—\u00A0¡a\u00A0escanear!",
                    Toast.LENGTH_LONG).show();
            Intent up = new Intent(FloatingButtonService.ACTION_UPDATE_FAB);
            up.setPackage(context.getPackageName());
            context.sendBroadcast(up);
            if (onDismiss != null) onDismiss.run();
        });
        root.addView(btnNuevo);

        addSpacer(context, root, 8);

        // Botón: Cerrar.
        Button btnCerrar = crearBoton(context, "Cerrar", COLOR_GRAY);
        btnCerrar.setOnClickListener(v -> {
            removerOverlay(wm, root);
            if (onDismiss != null) onDismiss.run();
        });
        root.addView(btnCerrar);

        // Mostrar.
        wm.addView(root, params);
    }

    // =============================================================
    //         DIALOG: NUEVO CONTAINER
    // =============================================================

    private static void showNuevoContainerDialog(Context context, WindowManager wm,
                                                  Runnable onDismiss) {
        LinearLayout root = crearRootLayout(context);

        TextView titulo = crearTexto(context, "🆕\u00A0Nuevo\u00A0Container", 18, true);
        titulo.setPadding(0, 0, 0, dp(context, 8));
        root.addView(titulo);

        TextView subtitulo = crearTexto(context,
                "Se borrarán los pallets actuales.", 13, false);
        subtitulo.setTextColor(COLOR_HINT);
        subtitulo.setPadding(0, 0, 0, dp(context, 12));
        root.addView(subtitulo);

        // Input: código.
        TextView lblCode = crearTexto(context, "Código:", 12, false);
        lblCode.setTextColor(COLOR_HINT);
        root.addView(lblCode);

        final EditText inputCode = crearInput(context, "Ej:\u00A0CNT-001");
        inputCode.setInputType(InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        // Pre-llenar con container actual.
        FirebaseManager fm = FirebaseManager.getInstance(context);
        String current = fm.getContainerCode();
        if (current != null && !current.isEmpty()) inputCode.setText(current);
        root.addView(inputCode);

        addSpacer(context, root, 8);

        // Input: meta.
        TextView lblMeta = crearTexto(context, "Pallets\u00A0esperadas:", 12, false);
        lblMeta.setTextColor(COLOR_HINT);
        root.addView(lblMeta);

        final EditText inputMeta = crearInput(context, "Ej:\u00A020");
        inputMeta.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(inputMeta);

        addSpacer(context, root, 16);

        final WindowManager.LayoutParams params = crearOverlayParamsFocusable();

        // Botones.
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnCancelar = crearBoton(context, "Cancelar", COLOR_GRAY);
        btnCancelar.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnCancelar.setOnClickListener(v -> {
            removerOverlay(wm, root);
            if (onDismiss != null) onDismiss.run();
        });
        btnRow.addView(btnCancelar);

        addSpacerH(context, btnRow, 8);

        Button btnCrear = crearBoton(context, "Crear", COLOR_BLUE);
        btnCrear.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnCrear.setOnClickListener(v -> {
            String code = inputCode.getText().toString().trim().toUpperCase();
            String metaStr = inputMeta.getText().toString().trim();

            int numPallets = 0;
            if (!metaStr.isEmpty()) {
                try { numPallets = Integer.parseInt(metaStr); }
                catch (NumberFormatException ignored) { }
            }

            if (numPallets <= 0 || numPallets >= 10000) {
                Toast.makeText(context,
                        "❌\u00A0Número\u00A0inválido", Toast.LENGTH_SHORT).show();
                return;
            }

            // Guardar.
            if (!code.isEmpty()) fm.setContainerCode(code);

            PalletRepository repo = new PalletRepository(context);
            repo.clearAll();

            fm.createNewContainerSession(code, numPallets);

            SharedPreferences fabPrefs = context.getSharedPreferences(PREFS_FAB, Context.MODE_PRIVATE);
            fabPrefs.edit()
                    .putInt(KEY_EXPECTED_PALLETS, numPallets)
                    .putInt(KEY_FAB_MODE, 2) // MODE_RATIO
                    .apply();

            Toast.makeText(context,
                    "✅\u00A0Nuevo\u00A0container:\u00A0" + code
                            + "\u00A0meta:\u00A0" + numPallets,
                    Toast.LENGTH_SHORT).show();

            removerOverlay(wm, root);
            if (onDismiss != null) onDismiss.run();
        });
        btnRow.addView(btnCrear);

        root.addView(btnRow);

        wm.addView(root, params);
    }

    // =============================================================
    //         DIALOG: ACTUALIZAR META
    // =============================================================

    private static void showActualizarMetaDialog(Context context, WindowManager wm,
                                                  Runnable onDismiss) {
        LinearLayout root = crearRootLayout(context);

        PalletRepository repo = new PalletRepository(context);
        SharedPreferences fabPrefs = context.getSharedPreferences(PREFS_FAB, Context.MODE_PRIVATE);
        int scanned = repo.getPalletCount();
        int currentExpected = fabPrefs.getInt(KEY_EXPECTED_PALLETS, 0);

        TextView titulo = crearTexto(context, "✏️\u00A0Actualizar\u00A0Meta", 18, true);
        titulo.setPadding(0, 0, 0, dp(context, 8));
        root.addView(titulo);

        String info = "Pallets\u00A0escaneadas:\u00A0" + scanned;
        if (currentExpected > 0) info += "\u00A0•\u00A0Meta:\u00A0" + currentExpected;
        TextView txtInfo = crearTexto(context, info, 13, false);
        txtInfo.setTextColor(COLOR_HINT);
        txtInfo.setPadding(0, 0, 0, dp(context, 12));
        root.addView(txtInfo);

        final EditText inputMeta = crearInput(context, "Nueva\u00A0meta");
        inputMeta.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (currentExpected > 0) {
            inputMeta.setText(String.valueOf(currentExpected));
            inputMeta.selectAll();
        }
        root.addView(inputMeta);

        addSpacer(context, root, 16);

        final WindowManager.LayoutParams params = crearOverlayParamsFocusable();

        // Botones.
        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnCancelar = crearBoton(context, "Cancelar", COLOR_GRAY);
        btnCancelar.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnCancelar.setOnClickListener(v -> {
            removerOverlay(wm, root);
            if (onDismiss != null) onDismiss.run();
        });
        btnRow.addView(btnCancelar);

        addSpacerH(context, btnRow, 8);

        Button btnQuitar = crearBoton(context, "Quitar", COLOR_RED);
        btnQuitar.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnQuitar.setOnClickListener(v -> {
            fabPrefs.edit()
                    .putInt(KEY_EXPECTED_PALLETS, 0)
                    .putInt(KEY_FAB_MODE, 0) // MODE_SCANNED
                    .apply();
            Toast.makeText(context,
                    "🔄\u00A0Meta\u00A0eliminada", Toast.LENGTH_SHORT).show();
            removerOverlay(wm, root);
            if (onDismiss != null) onDismiss.run();
        });
        btnRow.addView(btnQuitar);

        addSpacerH(context, btnRow, 8);

        Button btnGuardar = crearBoton(context, "Guardar", COLOR_GREEN);
        btnGuardar.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnGuardar.setOnClickListener(v -> {
            String txt = inputMeta.getText().toString().trim();
            if (!txt.isEmpty()) {
                try {
                    int num = Integer.parseInt(txt);
                    if (num > 0 && num < 10000) {
                        fabPrefs.edit()
                                .putInt(KEY_EXPECTED_PALLETS, num)
                                .putInt(KEY_FAB_MODE, 2) // MODE_RATIO
                                .apply();

                        // Actualizar en Firebase.
                        FirebaseManager fm = FirebaseManager.getInstance(context);
                        if (fm.getCurrentSession() != null) {
                            fm.updateContainerGoal(num);
                        } else {
                            String code = fm.getContainerCode();
                            fm.createNewContainerSession(code != null ? code : "", num);
                        }

                        String estado = scanned >= num
                                ? "\u00A0¡Completado!\u00A0🎉"
                                : "\u00A0faltan\u00A0" + (num - scanned);
                        Toast.makeText(context,
                                "✅\u00A0Meta:\u00A0" + num + estado,
                                Toast.LENGTH_SHORT).show();
                    }
                } catch (NumberFormatException ignored) {
                    Toast.makeText(context,
                            "❌\u00A0Número\u00A0inválido", Toast.LENGTH_SHORT).show();
                }
            }
            removerOverlay(wm, root);
            if (onDismiss != null) onDismiss.run();
        });
        btnRow.addView(btnGuardar);

        root.addView(btnRow);

        wm.addView(root, params);
    }

    // =============================================================
    //         DIALOG: RESET
    // =============================================================

    public static void showResetDialog(Context context, WindowManager wm,
                                       Runnable onDismiss) {
        LinearLayout root = crearRootLayout(context);

        PalletRepository repo = new PalletRepository(context);
        SharedPreferences fabPrefs = context.getSharedPreferences(PREFS_FAB, Context.MODE_PRIVATE);
        int scanned = repo.getPalletCount();
        int expected = fabPrefs.getInt(KEY_EXPECTED_PALLETS, 0);

        TextView titulo = crearTexto(context, "🔄\u00A0Resetear", 18, true);
        titulo.setPadding(0, 0, 0, dp(context, 8));
        root.addView(titulo);

        String msg = "¿Borrar\u00A0" + scanned + "\u00A0pallets?";
        if (expected > 0) msg += "\n(La\u00A0meta\u00A0de\u00A0" + expected + "\u00A0se\u00A0mantiene)";
        TextView txtMsg = crearTexto(context, msg, 14, false);
        txtMsg.setTextColor(COLOR_HINT);
        txtMsg.setPadding(0, 0, 0, dp(context, 16));
        root.addView(txtMsg);

        final WindowManager.LayoutParams params = crearOverlayParams();

        LinearLayout btnRow = new LinearLayout(context);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        Button btnNo = crearBoton(context, "Cancelar", COLOR_GRAY);
        btnNo.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnNo.setOnClickListener(v -> {
            removerOverlay(wm, root);
            if (onDismiss != null) onDismiss.run();
        });
        btnRow.addView(btnNo);

        addSpacerH(context, btnRow, 8);

        Button btnSi = crearBoton(context, "Sí,\u00A0borrar", COLOR_RED);
        btnSi.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnSi.setOnClickListener(v -> {
            repo.clearAll();
            FirebaseManager.getInstance(context).clearSession();
            Toast.makeText(context,
                    "🔄\u00A0Reseteado\u00A0a\u00A00", Toast.LENGTH_SHORT).show();
            removerOverlay(wm, root);
            if (onDismiss != null) onDismiss.run();
        });
        btnRow.addView(btnSi);

        root.addView(btnRow);

        wm.addView(root, params);
    }

    // =============================================================
    //              HELPERS DE UI
    // =============================================================

    private static LinearLayout crearRootLayout(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(context, 24), dp(context, 20),
                dp(context, 24), dp(context, 20));

        // Esquinas redondeadas via shape (simplificado: fondo sólido).
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

    private static EditText crearInput(Context context, String hint) {
        EditText et = new EditText(context);
        et.setHint(hint);
        et.setTextColor(COLOR_TEXT);
        et.setHintTextColor(COLOR_HINT);
        et.setBackgroundColor(COLOR_CARD);
        et.setPadding(dp(context, 12), dp(context, 10),
                dp(context, 12), dp(context, 10));
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        et.setSingleLine(true);
        return et;
    }

    private static Button crearBoton(Context context, String text, int bgColor) {
        Button btn = new Button(context);
        btn.setText(text);
        btn.setTextColor(COLOR_TEXT);
        btn.setBackgroundColor(bgColor);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        btn.setPadding(dp(context, 16), dp(context, 10),
                dp(context, 16), dp(context, 10));
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

    private static WindowManager.LayoutParams crearOverlayParams() {
        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.CENTER;
        params.width = (int) (320 * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
        return params;
    }

    /**
     * Versión focusable para que funcione el teclado en EditTexts.
     */
    private static WindowManager.LayoutParams crearOverlayParamsFocusable() {
        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
        );
        params.dimAmount = 0.5f;
        params.gravity = Gravity.CENTER;
        params.width = (int) (320 * android.content.res.Resources.getSystem()
                .getDisplayMetrics().density);
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
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
