package com.demoaccesibilidad.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

/**
 * FloatingButtonService — Overlay flotante sobre TODAS las apps.
 *
 * Muestra un FAB con el contador de pallets que se actualiza
 * en tiempo real cuando ScreenCaptureService detecta un pallet.
 *
 * Gestos:
 *  - 1 tap:      Alternar modo (escaneados / faltan / ratio)
 *  - Doble tap:  Abrir dialog de container (overlay)
 *  - Long press:  Resetear contador
 *  - Drag:       Mover el FAB por la pantalla
 *
 * Comunicación:
 *  - Recibe broadcasts ACTION_UPDATE_FLOATING_FAB del ScreenCaptureService
 *  - Lee datos directamente de PalletRepository + SharedPreferences
 */
public class FloatingButtonService extends Service {

    private static final String TAG = "FloatingFAB";
    private static final String CHANNEL_OVERLAY = "floating_overlay_channel";
    private static final int NOTIF_ID = 9001;

    // ═══ Broadcasts ═══
    public static final String ACTION_UPDATE_FAB =
            "com.demoaccesibilidad.capture.UPDATE_FLOATING_FAB";
    public static final String ACTION_STOP_OVERLAY =
            "com.demoaccesibilidad.capture.STOP_FLOATING_OVERLAY";

    // ═══ SharedPreferences (mismas del FAB en MainActivity) ═══
    private static final String PREFS_FAB = "fab_prefs";
    private static final String KEY_FAB_MODE = "fab_display_mode";
    private static final String KEY_EXPECTED_PALLETS = "expected_pallets";
    private static final String PREFS_OVERLAY = "overlay_prefs";
    private static final String KEY_OVERLAY_X = "overlay_x";
    private static final String KEY_OVERLAY_Y = "overlay_y";
    private static final String KEY_OVERLAY_OPACITY = "overlay_opacity";

    private static final int MODE_SCANNED = 0;
    private static final int MODE_REMAINING = 1;
    private static final int MODE_RATIO = 2;

    private static final int COLOR_NORMAL = 0xFF2196F3;
    private static final int COLOR_COMPLETE = 0xFF4CAF50;
    private static final long DOUBLE_TAP_TIMEOUT = 300L;

    // ═══ Vistas ═══
    private WindowManager windowManager;
    private View floatingView;
    private FloatingActionButton fab;
    private TextView fabBadge;
    private WindowManager.LayoutParams layoutParams;

    // ═══ Estado ═══
    private int fabDisplayMode = MODE_SCANNED;
    private int expectedPallets = 0;
    private int currentColor = COLOR_NORMAL;
    private PalletRepository palletRepo;
    private SharedPreferences fabPrefs;
    private SharedPreferences overlayPrefs;

    // ═══ Gestos ═══
    private int tapCount = 0;
    private Handler tapHandler = new Handler(Looper.getMainLooper());
    private Runnable tapRunnable;

    // ═══ Drag ═══
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private static final int DRAG_THRESHOLD = 10;

    // ═══ Refresh periódico ═══
    private Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;

    // ═══ Broadcast receiver ═══
    private final BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_UPDATE_FAB.equals(intent.getAction())) {
                refrescarContador();
            } else if (ACTION_STOP_OVERLAY.equals(intent.getAction())) {
                stopSelf();
            }
        }
    };

    // ─── Singleton check ───
    private static boolean isRunning = false;

    public static boolean isRunning() {
        return isRunning;
    }

    // =============================================================
    //                    LIFECYCLE
    // =============================================================

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
        try {
            palletRepo = new PalletRepository(this);
            fabPrefs = getSharedPreferences(PREFS_FAB, MODE_PRIVATE);
            overlayPrefs = getSharedPreferences(PREFS_OVERLAY, MODE_PRIVATE);

            fabDisplayMode = fabPrefs.getInt(KEY_FAB_MODE, MODE_SCANNED);
            expectedPallets = fabPrefs.getInt(KEY_EXPECTED_PALLETS, 0);

            // Crear canal de notificación y lanzar foreground.
            crearCanalNotificacion();
            startForeground(NOTIF_ID, crearNotificacion());

            // Crear vista flotante.
            crearOverlay();

            // Registrar receivers.
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_UPDATE_FAB);
            filter.addAction(ACTION_STOP_OVERLAY);
            filter.addAction(ScreenCaptureService.ACTION_PALLET_SAVED);
            registerReceiver(updateReceiver, filter);

            // Refresh periódico (cada 2s para mantenerse sincronizado).
            refreshRunnable = () -> {
                refrescarContador();
                refreshHandler.postDelayed(refreshRunnable, 2000);
            };
            refreshHandler.postDelayed(refreshRunnable, 2000);

            // Actualizar inmediatamente.
            refrescarContador();

            Log.d(TAG, "✅\u00A0Overlay\u00A0flotante\u00A0iniciado");
        } catch (Throwable t) {
            // Nunca dejar que el servicio tumbe la app entera. Registramos la
            // traza real y nos detenemos de forma limpia.
            PalletTrackerApp.logException(getApplicationContext(),
                    "FloatingButtonService.onCreate", t);
            try {
                // Salir de foreground y quitar la notificación si se llegó a crear.
                stopForeground(true);
            } catch (Throwable ignored) { }
            isRunning = false;
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Actualizar datos al recibir un start.
        refrescarContador();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();

        // Guardar posición final.
        if (layoutParams != null) {
            overlayPrefs.edit()
                    .putInt(KEY_OVERLAY_X, layoutParams.x)
                    .putInt(KEY_OVERLAY_Y, layoutParams.y)
                    .apply();
        }

        // Limpiar.
        refreshHandler.removeCallbacks(refreshRunnable);
        tapHandler.removeCallbacksAndMessages(null);

        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) { }

        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception ignored) { }
        }

        Log.d(TAG, "🛑\u00A0Overlay\u00A0flotante\u00A0detenido");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // =============================================================
    //            CREAR OVERLAY (VENTANA FLOTANTE)
    // =============================================================

    private void crearOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Sin\u00A0permiso\u00A0de\u00A0overlay");
            stopSelf();
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Inflar layout.
        // IMPORTANTE: el layout usa un FloatingActionButton de Material
        // Components, que EXIGE un tema Theme.MaterialComponents. El contexto
        // de un Service no lo trae, así que envolvemos con ContextThemeWrapper.
        // Sin esto, la inflación lanza excepción, el servicio muere en silencio
        // (por el try/catch de onCreate) y el botón NUNCA aparece.
        Context themedContext = new ContextThemeWrapper(this, R.style.Theme_DiagnosticoDemo);
        floatingView = LayoutInflater.from(themedContext)
                .inflate(R.layout.layout_floating_fab, null);

        fab = floatingView.findViewById(R.id.fab_floating);
        fabBadge = floatingView.findViewById(R.id.fab_floating_badge);

        // Configurar layout params para overlay.
        int overlayType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            overlayType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        // Posición: restaurar la última guardada o esquina inferior derecha.
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = overlayPrefs.getInt(KEY_OVERLAY_X, 800);
        layoutParams.y = overlayPrefs.getInt(KEY_OVERLAY_Y, 1200);

        // Opacidad.
        float opacity = overlayPrefs.getFloat(KEY_OVERLAY_OPACITY, 0.92f);
        floatingView.setAlpha(opacity);

        // Agregar al WindowManager.
        windowManager.addView(floatingView, layoutParams);

        // Configurar gestos.
        configurarGestos();
    }

    // =============================================================
    //              GESTOS: TAP / DOBLE TAP / LONG / DRAG
    // =============================================================

    private void configurarGestos() {
        // IMPORTANTE: poner el listener directamente en el FAB para que capture
        // todos los toques, incluyendo el drag. Si está en floatingView, el
        // FAB puede interceptar los eventos y el drag no funciona.
        fab.setOnTouchListener(new View.OnTouchListener() {
            private long touchStartTime;
            private boolean longPressHandled = false;
            private Handler longPressHandler = new Handler(Looper.getMainLooper());
            private Runnable longPressRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchStartTime = System.currentTimeMillis();
                        isDragging = false;
                        longPressHandled = false;

                        // Programar long press.
                        longPressRunnable = () -> {
                            if (!isDragging) {
                                longPressHandled = true;
                                onLongPressFab();
                            }
                        };
                        longPressHandler.postDelayed(longPressRunnable, 600);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;

                        // Si se mueve más de 10px, es un drag (no tap).
                        if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                            if (!isDragging) {
                                isDragging = true;
                                longPressHandler.removeCallbacks(longPressRunnable);
                            }
                        }

                        // Siempre actualizar posición mientras se mueve el dedo.
                        if (isDragging) {
                            layoutParams.x = initialX + (int) dx;
                            layoutParams.y = initialY + (int) dy;
                            try {
                                windowManager.updateViewLayout(floatingView, layoutParams);
                            } catch (Exception ignored) { }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        longPressHandler.removeCallbacks(longPressRunnable);

                        if (isDragging) {
                            // Guardar posición después de drag.
                            overlayPrefs.edit()
                                    .putInt(KEY_OVERLAY_X, layoutParams.x)
                                    .putInt(KEY_OVERLAY_Y, layoutParams.y)
                                    .apply();
                            return true;
                        }

                        if (longPressHandled) return true;

                        // Click normal → detectar tap / doble tap.
                        long pressDuration = System.currentTimeMillis() - touchStartTime;
                        if (pressDuration < 500) {
                            manejarTap();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void manejarTap() {
        tapCount++;

        if (tapCount == 1) {
            tapRunnable = () -> {
                if (tapCount == 1) {
                    onSingleTapFab();
                }
                tapCount = 0;
            };
            tapHandler.postDelayed(tapRunnable, DOUBLE_TAP_TIMEOUT);
        } else if (tapCount >= 2) {
            tapHandler.removeCallbacks(tapRunnable);
            tapCount = 0;
            onDoubleTapFab();
        }
    }

    // ─── 1 TAP: Cambiar modo ───
    private void onSingleTapFab() {
        if (expectedPallets <= 0) {
            // Sin meta → abrir dialog para configurar.
            onDoubleTapFab();
            return;
        }

        fabDisplayMode = (fabDisplayMode + 1) % 3;
        fabPrefs.edit().putInt(KEY_FAB_MODE, fabDisplayMode).apply();

        // Animación de pulse.
        fab.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                .withEndAction(() ->
                        fab.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                ).start();

        refrescarContador();
    }

    // ─── DOBLE TAP: Dialog flotante para configurar container ───
    private void onDoubleTapFab() {
        FloatingDialogHelper.showContainerDialog(this, windowManager, () -> {
            // Callback: recargar datos.
            expectedPallets = fabPrefs.getInt(KEY_EXPECTED_PALLETS, 0);
            fabDisplayMode = fabPrefs.getInt(KEY_FAB_MODE, MODE_SCANNED);
            refrescarContador();
        });
    }

    // ─── LONG PRESS: Resetear ───
    private void onLongPressFab() {
        FloatingDialogHelper.showResetDialog(this, windowManager, () -> {
            refrescarContador();
        });
    }

    // =============================================================
    //             ACTUALIZAR CONTADOR DEL FAB
    // =============================================================

    private void refrescarContador() {
        // Re-leer prefs (pueden haber cambiado desde MainActivity).
        fabDisplayMode = fabPrefs.getInt(KEY_FAB_MODE, MODE_SCANNED);
        expectedPallets = fabPrefs.getInt(KEY_EXPECTED_PALLETS, 0);

        int scanned = palletRepo.getPalletCount();

        // Considerar sesión Firebase.
        try {
            FirebaseManager fm = FirebaseManager.getInstance(this);
            ContainerSession session = fm.getCurrentSession();
            if (session != null) {
                if (session.scannedPallets > scanned) {
                    scanned = session.scannedPallets;
                }
                if (session.expectedPallets > 0 && expectedPallets != session.expectedPallets) {
                    expectedPallets = session.expectedPallets;
                }
            }
        } catch (Exception ignored) { }

        // Calcular texto.
        String displayText;
        switch (fabDisplayMode) {
            case MODE_REMAINING:
                int remaining = Math.max(0, expectedPallets - scanned);
                displayText = String.valueOf(remaining);
                break;
            case MODE_RATIO:
                if (expectedPallets > 0) {
                    displayText = scanned + "/" + expectedPallets;
                } else {
                    displayText = String.valueOf(scanned);
                }
                break;
            case MODE_SCANNED:
            default:
                displayText = String.valueOf(scanned);
                break;
        }

        // Actualizar badge.
        if (fabBadge != null) {
            fabBadge.setText(displayText);

            if (displayText.length() > 5) {
                fabBadge.setTextSize(12);
            } else if (displayText.length() > 3) {
                fabBadge.setTextSize(15);
            } else {
                fabBadge.setTextSize(20);
            }
        }

        // Color: verde si completo, azul si no.
        int targetColor;
        if (expectedPallets > 0 && scanned >= expectedPallets) {
            targetColor = COLOR_COMPLETE;
            if (fabDisplayMode == MODE_REMAINING && fabBadge != null) {
                fabBadge.setText("✓");
                fabBadge.setTextSize(24);
            }
        } else {
            targetColor = COLOR_NORMAL;
        }

        if (targetColor != currentColor && fab != null) {
            fab.setBackgroundTintList(ColorStateList.valueOf(targetColor));
            currentColor = targetColor;
        }

        // Actualizar notificación persistente.
        actualizarNotificacion(scanned);
    }

    // =============================================================
    //              NOTIFICACIÓN FOREGROUND
    // =============================================================

    private void crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_OVERLAY,
                    "PalletTracker\u00A0Flotante",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Muestra que el botón flotante está activo.");
            channel.setShowBadge(false);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification crearNotificacion() {
        int scanned = palletRepo.getPalletCount();
        String text;
        if (expectedPallets > 0) {
            text = "Registrando\u00A0pallets:\u00A0" + scanned + "/" + expectedPallets;
        } else {
            text = "Registrando\u00A0pallets:\u00A0" + scanned;
        }

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_OVERLAY)
                .setContentTitle("📦\u00A0PalletTracker\u00A0Activo")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void actualizarNotificacion(int scanned) {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                String text;
                if (expectedPallets > 0) {
                    if (scanned >= expectedPallets) {
                        text = "✅\u00A0Completo:\u00A0" + scanned + "/" + expectedPallets;
                    } else {
                        text = "Registrando:\u00A0" + scanned + "/" + expectedPallets;
                    }
                } else {
                    text = "Registrando\u00A0pallets:\u00A0" + scanned;
                }

                Intent openApp = new Intent(this, MainActivity.class);
                openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                        PendingIntent.FLAG_UPDATE_CURRENT);

                Notification notif = new NotificationCompat.Builder(this, CHANNEL_OVERLAY)
                        .setContentTitle("📦\u00A0PalletTracker\u00A0Activo")
                        .setContentText(text)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentIntent(pi)
                        .setOngoing(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .build();

                nm.notify(NOTIF_ID, notif);
            }
        } catch (Exception ignored) { }
    }
}
