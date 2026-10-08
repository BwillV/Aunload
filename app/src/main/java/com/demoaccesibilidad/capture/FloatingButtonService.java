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
 * Muestra un FAB con el CONTEO de pallets del container actual, que se
 * actualiza en tiempo real cuando ScreenCaptureService detecta un pallet.
 *
 * v4.2 — Botón simplificado: SOLO cuenta pallets (sin metas ni "faltan").
 * Gestos:
 *  - 1 tap:        Alternar entre "este dispositivo" (Yo) y "Total" de todos
 *                  los dispositivos del container compartido.
 *  - Mantener pulsado: iniciar un NUEVO container (el contador vuelve a 0),
 *                  conservando los containers anteriores. Si hay sync, se
 *                  propaga para que los demás dispositivos empiecen en 0.
 *  - Drag:         Mover el FAB por la pantalla.
 *  (El doble tap ya NO hace nada: se eliminó.)
 *
 * Comunicación:
 *  - Recibe broadcasts ACTION_UPDATE_FLOATING_FAB del ScreenCaptureService
 *  - Lee datos directamente de PalletRepository + Firebase (modo Total)
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
    private static final String PREFS_OVERLAY = "overlay_prefs";
    private static final String KEY_OVERLAY_X = "overlay_x";
    private static final String KEY_OVERLAY_Y = "overlay_y";
    private static final String KEY_OVERLAY_OPACITY = "overlay_opacity";

    // v4.2: solo dos modos de visualización del conteo.
    private static final int MODE_SELF = 0;    // conteo de ESTE dispositivo
    private static final int MODE_TOTAL = 1;   // total de TODOS los dispositivos

    private static final int COLOR_SELF = 0xFF2196F3;   // azul  → "Yo"
    private static final int COLOR_TOTAL = 0xFF3F51B5;  // índigo → "Total"
    private static final int COLOR_ALERT = 0xFFD32F2F;  // rojo  → accesibilidad OFF

    // ═══ Vistas ═══
    private WindowManager windowManager;
    private View floatingView;
    private FloatingActionButton fab;
    private TextView fabBadge;
    private WindowManager.LayoutParams layoutParams;

    // ═══ Estado ═══
    private int fabDisplayMode = MODE_SELF;
    private int currentColor = COLOR_SELF;
    // v4.3: estado de la accesibilidad (se vigila en cada refresco).
    private boolean accesibilidadOk = true;
    private PalletRepository palletRepo;
    private SharedPreferences fabPrefs;
    private SharedPreferences overlayPrefs;

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
            String action = intent != null ? intent.getAction() : null;
            if (ACTION_UPDATE_FAB.equals(action)
                    || ScreenCaptureService.ACTION_PALLET_SAVED.equals(action)) {
                // v3.2: log de depuración para verificar que el broadcast llega
                // y que el contador se actualiza en tiempo real.
                int count = palletRepo != null ? palletRepo.getPalletCount() : -1;
                Log.d(TAG, "🔄\u00A0Broadcast\u00A0recibido\u00A0(" + action
                        + ")\u00A0→\u00A0refrescando\u00A0contador,\u00A0total=" + count);
                refrescarContador();
            } else if (ACTION_STOP_OVERLAY.equals(action)) {
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

            fabDisplayMode = fabPrefs.getInt(KEY_FAB_MODE, MODE_SELF);

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

        // v3.3: garantizar que el número quede SIEMPRE por encima del FAB azul.
        // El FAB Material tiene elevation propia; si el badge no la supera, el
        // número queda oculto. Reforzamos por código además del XML.
        if (fabBadge != null) {
            fabBadge.setVisibility(View.VISIBLE);
            fabBadge.setElevation(24f);
            fabBadge.setTranslationZ(24f);
            fabBadge.bringToFront();
            fabBadge.setText(String.valueOf(
                    palletRepo != null ? palletRepo.getPalletCountInCurrentContainer() : 0));
        }

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
        // Máquina de estados simple (v4.2, sin doble tap):
        //   - Drag: movimiento > DRAG_THRESHOLD (10 px)
        //   - Long press: dedo quieto > 600 ms → nuevo container (vuelve a 0)
        //   - Single tap: toque corto → alternar Yo / Total
        fab.setOnTouchListener(new View.OnTouchListener() {
            private long touchDownTime;
            private boolean longPressFired = false;
            private final Handler lpHandler = new Handler(Looper.getMainLooper());
            private Runnable lpRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        // Guardar estado inicial.
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchDownTime = System.currentTimeMillis();
                        isDragging = false;
                        longPressFired = false;

                        // Programar detección de long press (600 ms).
                        lpRunnable = () -> {
                            if (!isDragging) {
                                longPressFired = true;
                                v.performHapticFeedback(
                                        android.view.HapticFeedbackConstants.LONG_PRESS);
                                onLongPressFab();
                            }
                        };
                        lpHandler.postDelayed(lpRunnable, 600);
                        return true;   // consumir DOWN

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;

                        if (!isDragging &&
                                (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
                            isDragging = true;
                            lpHandler.removeCallbacks(lpRunnable);  // cancelar long press
                        }

                        if (isDragging) {
                            layoutParams.x = initialX + (int) dx;
                            layoutParams.y = initialY + (int) dy;
                            try {
                                windowManager.updateViewLayout(floatingView, layoutParams);
                            } catch (Exception ignored) { }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        lpHandler.removeCallbacks(lpRunnable);

                        // ── Drag terminó: guardar posición ──
                        if (isDragging) {
                            overlayPrefs.edit()
                                    .putInt(KEY_OVERLAY_X, layoutParams.x)
                                    .putInt(KEY_OVERLAY_Y, layoutParams.y)
                                    .apply();
                            isDragging = false;
                            return true;
                        }

                        // ── Long press ya se disparó: nada más que hacer ──
                        if (longPressFired) return true;

                        // ── Toque corto → responder al instante (sin doble tap) ──
                        long elapsed = System.currentTimeMillis() - touchDownTime;
                        if (elapsed < 500) {
                            onSingleTapFab();
                        }
                        return true;
                }
                return true;  // consumir cualquier otro evento
            }
        });
    }

    // ─── 1 TAP: alternar entre "Yo" y "Total" ───
    private void onSingleTapFab() {
        // v4.3 — Si la accesibilidad está OFF, el tap abre directamente los
        // Ajustes de accesibilidad para reactivarla (no alterna el modo).
        if (!accesibilidadOk) {
            abrirAjustesAccesibilidad();
            return;
        }

        fabDisplayMode = (fabDisplayMode == MODE_SELF) ? MODE_TOTAL : MODE_SELF;
        fabPrefs.edit().putInt(KEY_FAB_MODE, fabDisplayMode).apply();

        // Aviso sutil del modo activo.
        String aviso = (fabDisplayMode == MODE_TOTAL)
                ? "Mostrando:\u00A0TOTAL\u00A0de\u00A0dispositivos"
                : "Mostrando:\u00A0este\u00A0dispositivo";
        Toast.makeText(this, aviso, Toast.LENGTH_SHORT).show();

        // Animación de pulse.
        fab.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80)
                .withEndAction(() ->
                        fab.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                ).start();

        refrescarContador();
    }

    // ─── v4.3: abrir Ajustes de accesibilidad desde el overlay ───
    private void abrirAjustesAccesibilidad() {
        try {
            Intent intent = AccessibilityHelper.buildAccessibilitySettingsIntent(this);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this,
                    "Activa\u00A0PalletTracker\u00A0en\u00A0Accesibilidad",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            PalletTrackerApp.logException(getApplicationContext(),
                    "FloatingButtonService.abrirAjustesAccesibilidad", t);
        }
    }

    // ─── LONG PRESS: iniciar NUEVO container (el contador vuelve a 0) ───
    private void onLongPressFab() {
        // v4.3 — Si la accesibilidad está OFF, priorizar su reactivación.
        if (!accesibilidadOk) {
            abrirAjustesAccesibilidad();
            return;
        }
        try {
            // Avanzar al siguiente container (Container N+1) SIN borrar los
            // anteriores. El conteo del container nuevo es 0.
            String nuevo = palletRepo.startNewContainer();

            // Si la sincronización está activa, propagar el nuevo container
            // para que los demás dispositivos también empiecen en 0.
            try {
                FirebaseManager fm = FirebaseManager.getInstance(this);
                if (fm.isSyncEnabled()) {
                    fm.setContainerCode(nuevo);
                    fm.createNewContainerSession(nuevo, 0);
                }
            } catch (Throwable ignored) { }

            Toast.makeText(this,
                    "🔄\u00A0Nuevo\u00A0container\u00A0—\u00A0conteo\u00A0en\u00A00",
                    Toast.LENGTH_SHORT).show();

            refrescarContador();
        } catch (Throwable t) {
            PalletTrackerApp.logException(getApplicationContext(),
                    "FloatingButtonService.onLongPressFab", t);
        }
    }

    // =============================================================
    //             ACTUALIZAR CONTADOR DEL FAB
    // =============================================================

    private void refrescarContador() {
        // v4.3 — Vigilar el servicio de accesibilidad. Si está desactivado,
        // el conteo no funciona, así que avisamos en el propio overlay:
        // FAB rojo con "!" para que el operario lo note y lo reactive tocándolo.
        accesibilidadOk = AccessibilityHelper.isServiceEnabled(this);
        if (!accesibilidadOk) {
            if (fabBadge != null) {
                fabBadge.setText("!");
                fabBadge.setTextSize(22);
            }
            if (fab != null && currentColor != COLOR_ALERT) {
                fab.setBackgroundTintList(ColorStateList.valueOf(COLOR_ALERT));
                currentColor = COLOR_ALERT;
            }
            actualizarNotificacion(-1);
            return;
        }

        // Re-leer el modo (puede haber cambiado).
        fabDisplayMode = fabPrefs.getInt(KEY_FAB_MODE, MODE_SELF);

        // Conteo de ESTE dispositivo en el container actual.
        int self = palletRepo.getPalletCountInCurrentContainer();
        int shown = self;

        if (fabDisplayMode == MODE_TOTAL) {
            // v4.8 — Total REAL de todos los dispositivos del container
            // compartido. Antes se pasaba el código local diario
            // ("yyyy-MM-dd#N"), que nunca coincide con el código compartido en
            // Firebase, así que el total siempre caía al conteo propio. Ahora
            // pedimos directamente el total de la sesión compartida.
            try {
                FirebaseManager fm = FirebaseManager.getInstance(this);
                // Si la sync está activa pero aún no hay sesión escuchando
                // (p.ej. el proceso se reinició), intentar reconectar para que
                // el total se mantenga vivo.
                fm.ensureListeningIfEnabled();
                int total = fm.getTotalScannedShared();
                // El total nunca debe ser menor que lo propio (coherencia).
                shown = (total >= self) ? total : Math.max(total, self);
                if (total < 0) shown = self;
            } catch (Throwable ignored) {
                shown = self;
            }
        }

        String displayText = String.valueOf(shown);

        // Actualizar badge.
        if (fabBadge != null) {
            fabBadge.setText(displayText);
            if (displayText.length() > 4) {
                fabBadge.setTextSize(14);
            } else if (displayText.length() > 3) {
                fabBadge.setTextSize(16);
            } else {
                fabBadge.setTextSize(20);
            }
        }

        // Color según el modo (indicador sutil): azul = Yo, índigo = Total.
        int targetColor = (fabDisplayMode == MODE_TOTAL) ? COLOR_TOTAL : COLOR_SELF;
        if (targetColor != currentColor && fab != null) {
            fab.setBackgroundTintList(ColorStateList.valueOf(targetColor));
            currentColor = targetColor;
        }

        // Actualizar notificación persistente.
        actualizarNotificacion(shown);
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
        int scanned = palletRepo.getPalletCountInCurrentContainer();
        String text = "Registrando\u00A0pallets:\u00A0" + scanned;

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
                boolean alerta = (scanned < 0);
                String title = alerta
                        ? "⚠️\u00A0Accesibilidad\u00A0desactivada"
                        : "📦\u00A0PalletTracker\u00A0Activo";
                String text = alerta
                        ? "Toca\u00A0el\u00A0botón\u00A0flotante\u00A0para\u00A0reactivarla"
                        : "Registrando\u00A0pallets:\u00A0" + scanned;

                Intent openApp = new Intent(this, MainActivity.class);
                openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                        PendingIntent.FLAG_UPDATE_CURRENT);

                Notification notif = new NotificationCompat.Builder(this, CHANNEL_OVERLAY)
                        .setContentTitle(title)
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
