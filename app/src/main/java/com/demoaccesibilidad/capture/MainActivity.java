package com.demoaccesibilidad.capture;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Activity principal de PalletTracker v2.3
 *
 * ╔══════════════════════════════════════════════════════╗
 * ║  v2.3 — TRABAJO EN EQUIPO                           ║
 * ║  • Container code compartido entre dispositivos      ║
 * ║  • Múltiples tasks agrupados por container           ║
 * ║  • BottomSheet: Nuevo Container / Actualizar meta    ║
 * ║  • Header con info del container activo              ║
 * ╚══════════════════════════════════════════════════════╝
 *
 * FAB avanzado:
 *  - Un tap:    Alternar entre 3 modos de visualización
 *  - Doble tap: BottomSheet (Nuevo Container / Actualizar)
 *  - Long press: Resetear contador
 */
public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
        TaskAdapter.OnTaskClickListener {

    // ====================== CONSTANTES FAB ======================
    private static final String PREFS_FAB = "fab_prefs";
    private static final String KEY_FAB_MODE = "fab_display_mode";
    private static final String KEY_EXPECTED_PALLETS = "expected_pallets";
    private static final int COLOR_NORMAL = 0xFF2196F3;
    private static final int COLOR_COMPLETE = 0xFF4CAF50;
    private static final long DOUBLE_TAP_TIMEOUT = 300L;

    private static final int MODE_SCANNED = 0;
    private static final int MODE_REMAINING = 1;
    private static final int MODE_RATIO = 2;

    // ====================== VISTAS ======================
    private DrawerLayout drawerLayout;
    private RecyclerView recyclerTasks;
    private TextView txtEmpty;
    private FloatingActionButton fab;
    private TextView fabBadge;
    private View homeContainer;
    private View fragmentContainer;

    // Header del container (v2.3).
    private LinearLayout containerHeader;
    private TextView txtContainerCode;
    private TextView txtContainerTasks;
    private TextView txtDeviceIndicator;

    // ====================== DATOS ======================
    private TaskAdapter taskAdapter;
    private PalletRepository palletRepo;
    private NotificationHelper notifHelper;
    private Handler refreshHandler;
    private Runnable refreshRunnable;

    // ====================== FAB STATE ======================
    private SharedPreferences fabPrefs;
    private int fabDisplayMode = MODE_SCANNED;
    private int expectedPallets = 0;
    private int currentFabColor = COLOR_NORMAL;
    private boolean containerWasComplete = false;

    private int tapCount = 0;
    private Handler tapHandler = new Handler();
    private Runnable tapRunnable;

    // Receiver para pallet guardado.
    private final BroadcastReceiver palletReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refrescarDatos();
            FirebaseManager fm = FirebaseManager.getInstance(MainActivity.this);
            if (fm.isSyncEnabled() && fm.getCurrentSession() != null) {
                fm.updateDeviceStatus();
            }
        }
    };

    // ====================== LIFECYCLE ======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        palletRepo = new PalletRepository(this);
        // v4.0 — Migrar datos heredados (TASK-xxxx / SIN TASK / vacío) a "Container 1".
        palletRepo.migrateLegacyToContainers();
        // v4.1 — Limpiar SKU que quedaron con "TASK-xxxx" por el bug del regex.
        palletRepo.migrateFixSkuTask();
        notifHelper = new NotificationHelper(this);
        fabPrefs = getSharedPreferences(PREFS_FAB, MODE_PRIVATE);

        fabDisplayMode = fabPrefs.getInt(KEY_FAB_MODE, MODE_SCANNED);
        expectedPallets = fabPrefs.getInt(KEY_EXPECTED_PALLETS, 0);

        // Toolbar.
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("PalletTracker");
        }

        // Drawer.
        drawerLayout = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawerLayout, toolbar,
                R.string.nav_open, R.string.nav_close);
        drawerLayout.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navView = findViewById(R.id.nav_view);
        navView.setNavigationItemSelectedListener(this);

        // Vistas principales.
        homeContainer = findViewById(R.id.home_container);
        fragmentContainer = findViewById(R.id.fragment_container);
        recyclerTasks = findViewById(R.id.recycler_tasks);
        txtEmpty = findViewById(R.id.txt_empty);
        fab = findViewById(R.id.fab_counter);
        fabBadge = findViewById(R.id.fab_badge);

        // Header del container (v2.3).
        containerHeader = findViewById(R.id.container_header);
        txtContainerCode = findViewById(R.id.txt_container_code);
        txtContainerTasks = findViewById(R.id.txt_container_tasks);
        txtDeviceIndicator = findViewById(R.id.txt_device_indicator);

        // RecyclerView.
        taskAdapter = new TaskAdapter(this);
        recyclerTasks.setLayoutManager(new LinearLayoutManager(this));
        recyclerTasks.setAdapter(taskAdapter);

        // ═══════════ FAB — BOTÓN DE BASURA (v3.4) ═══════════
        // El FAB dentro de la app deja de cambiar el modo del contador y pasa
        // a ser un botón de "papelera" para borrar containers completos.
        fabBadge.setVisibility(View.GONE);            // ya no mostramos el contador aquí
        fab.setImageResource(android.R.drawable.ic_menu_delete);
        fab.setBackgroundTintList(ColorStateList.valueOf(0xFFF44336)); // rojo destructivo
        fab.setContentDescription("Borrar\u00A0containers");
        fab.setOnClickListener(v -> mostrarDialogoBorrarContainers());
        fab.setOnLongClickListener(v -> {
            mostrarDialogoBorrarContainers();
            return true;
        });

        // Refresh periódico.
        refreshHandler = new Handler();
        refreshRunnable = () -> {
            refrescarContador();
            refrescarContainerHeader();
            refreshHandler.postDelayed(refreshRunnable, 3000);
        };

        // ═══════════ FIREBASE SYNC ═══════════
        FirebaseManager fm = FirebaseManager.getInstance(this);
        fm.setSyncListener(new FirebaseManager.SyncListener() {
            @Override
            public void onRemotePalletAdded(Pallet pallet, String fromDeviceName) {
                FirebaseManager fmRef = FirebaseManager.getInstance(MainActivity.this);
                ContainerSession sess = fmRef.getCurrentSession();
                int total = sess != null ? sess.scannedPallets : 0;
                int expected = sess != null ? sess.expectedPallets : 0;

                String containerCode = fmRef.getContainerCode();
                String displayName = (containerCode != null && !containerCode.isEmpty())
                        ? fromDeviceName + "\u00A0(" + containerCode + ")"
                        : fromDeviceName;

                notifHelper.notifyRemotePalletScanned(
                        displayName, pallet.palletCode, total, expected);
                refrescarDatos();
            }

            @Override
            public void onSessionUpdated(ContainerSession session) {
                runOnUiThread(() -> {
                    refrescarContador();
                    refrescarContainerHeader();
                });
            }

            @Override
            public void onDeviceStatusChanged(DeviceInfo device) {
                Log.d("FirebaseSync", "Device\u00A0status:\u00A0" + device.getSummaryDisplay());
                runOnUiThread(() -> refrescarContainerHeader());
            }

            @Override
            public void onSyncError(String message) {
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                "Sync:\u00A0" + message, Toast.LENGTH_SHORT).show());
            }
        });

        mostrarHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            registerReceiver(palletReceiver,
                    new IntentFilter(ScreenCaptureService.ACTION_PALLET_SAVED));
        } catch (Exception ignored) { }
        refrescarDatos();
        refreshHandler.postDelayed(refreshRunnable, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(palletReceiver);
        } catch (Exception ignored) { }
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    // ═══════════════════════════════════════════════════════════
    //              CONTAINER HEADER (v2.3)
    // ═══════════════════════════════════════════════════════════

    /**
     * Actualiza el header del container con la info actual.
     */
    private void refrescarContainerHeader() {
        // v4.1 — Header simple, solo containers y solo datos de HOY.
        int palletsHoy = palletRepo.getPalletCountToday();

        if (palletsHoy <= 0) {
            containerHeader.setVisibility(View.GONE);
            return;
        }

        containerHeader.setVisibility(View.VISIBLE);
        txtContainerCode.setText("Actual:\u00A0" + palletRepo.getCurrentContainerCode());

        int numContainers = palletRepo.getContainerListToday().size();
        txtContainerTasks.setVisibility(View.VISIBLE);
        txtContainerTasks.setText(palletsHoy + "\u00A0pallets\u00A0hoy\u00A0•\u00A0"
                + numContainers + (numContainers == 1 ? "\u00A0container" : "\u00A0containers"));

        // Dispositivos activos.
        FirebaseManager fm = FirebaseManager.getInstance(this);
        int deviceCount = fm.getActiveDeviceCount();
        if (deviceCount > 1) {
            txtDeviceIndicator.setText("📱\u00A0" + deviceCount);
            txtDeviceIndicator.setTextColor(0xFF4CAF50);
        } else {
            txtDeviceIndicator.setText("📱\u00A01");
            txtDeviceIndicator.setTextColor(0xFF888888);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //                  FAB — GESTOS Y MODOS
    // ═══════════════════════════════════════════════════════════

    private void manejarTapFab() {
        tapCount++;

        if (tapCount == 1) {
            tapRunnable = () -> {
                if (tapCount == 1) {
                    cambiarModoFab();
                }
                tapCount = 0;
            };
            tapHandler.postDelayed(tapRunnable, DOUBLE_TAP_TIMEOUT);
        } else if (tapCount >= 2) {
            tapHandler.removeCallbacks(tapRunnable);
            tapCount = 0;
            mostrarBottomSheetContainer();
        }
    }

    private void cambiarModoFab() {
        if (expectedPallets <= 0) {
            notifHelper.vibrarCorto();
            Toast.makeText(this,
                    "💡\u00A0Doble\u00A0tap\u00A0para\u00A0configurar\u00A0container",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        fabDisplayMode = (fabDisplayMode + 1) % 3;
        fabPrefs.edit().putInt(KEY_FAB_MODE, fabDisplayMode).apply();

        notifHelper.vibrarCorto();

        String modeName;
        switch (fabDisplayMode) {
            case MODE_SCANNED:  modeName = "Escaneados"; break;
            case MODE_REMAINING: modeName = "Faltan"; break;
            case MODE_RATIO:    modeName = "Ratio"; break;
            default: modeName = ""; break;
        }
        Toast.makeText(this, "📊\u00A0Modo:\u00A0" + modeName, Toast.LENGTH_SHORT).show();

        animarCambioModo();
        refrescarContador();
    }

    // ═══════════════════════════════════════════════════════════
    //         BOTTOM SHEET — NUEVO / ACTUALIZAR CONTAINER
    // ═══════════════════════════════════════════════════════════

    /**
     * Muestra el BottomSheet con opciones:
     *  1. Nuevo Container
     *  2. Actualizar Container Actual
     */
    private void mostrarBottomSheetContainer() {
        notifHelper.vibrarCorto();

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_container_options);

        // Fondo transparente para que se vea el diseño.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            // Posicionar al fondo como un bottom sheet.
            dialog.getWindow().setGravity(android.view.Gravity.BOTTOM);
        }

        // Info del container actual.
        TextView txtContainerInfo = dialog.findViewById(R.id.txt_container_info);
        FirebaseManager fm = FirebaseManager.getInstance(this);
        String containerCode = fm.getContainerCode();
        int scanned = palletRepo.getPalletCount();

        if (containerCode != null && !containerCode.isEmpty()) {
            txtContainerInfo.setText("Container\u00A0actual:\u00A0" + containerCode
                    + "\u00A0•\u00A0" + scanned + "\u00A0escaneadas");
        } else if (expectedPallets > 0) {
            txtContainerInfo.setText(scanned + "/" + expectedPallets
                    + "\u00A0pallets\u00A0escaneadas");
        } else {
            txtContainerInfo.setText("Sin\u00A0container\u00A0configurado");
        }

        // Subtítulo de opción 2.
        TextView txtUpdateSubtitle = dialog.findViewById(R.id.txt_update_subtitle);
        if (scanned > 0) {
            txtUpdateSubtitle.setText("Tienes\u00A0" + scanned
                    + "\u00A0pallets.\u00A0Solo\u00A0cambia\u00A0la\u00A0meta.");
        }

        // Opción 1: Nuevo Container.
        CardView optionNew = dialog.findViewById(R.id.option_new_container);
        optionNew.setOnClickListener(v -> {
            dialog.dismiss();
            mostrarDialogoNuevoContainer();
        });

        // Opción 2: Actualizar Actual.
        CardView optionUpdate = dialog.findViewById(R.id.option_update_container);
        optionUpdate.setOnClickListener(v -> {
            dialog.dismiss();
            mostrarDialogoActualizarContainer();
        });

        dialog.show();
    }

    /**
     * Diálogo: Crear Nuevo Container.
     * Limpia todo y empieza de cero.
     */
    private void mostrarDialogoNuevoContainer() {
        FirebaseManager fm = FirebaseManager.getInstance(this);
        String currentContainerCode = fm.getContainerCode();

        // Input para código de container (opcional si ya está en ajustes).
        final EditText inputCode = new EditText(this);
        inputCode.setInputType(android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        inputCode.setHint("Código\u00A0(Ej:\u00A0CNT-001)");
        inputCode.setTextColor(0xFFFFFFFF);
        inputCode.setHintTextColor(0xFF888888);
        inputCode.setBackgroundColor(0xFF2a2a4a);
        inputCode.setPadding(40, 25, 40, 25);
        if (currentContainerCode != null && !currentContainerCode.isEmpty()) {
            inputCode.setText(currentContainerCode);
        }

        // Input para pallets esperadas.
        final EditText inputPallets = new EditText(this);
        inputPallets.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        inputPallets.setHint("Pallets\u00A0esperadas\u00A0(Ej:\u00A020)");
        inputPallets.setTextColor(0xFFFFFFFF);
        inputPallets.setHintTextColor(0xFF888888);
        inputPallets.setBackgroundColor(0xFF2a2a4a);
        inputPallets.setPadding(40, 25, 40, 25);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 0);
        layout.addView(inputCode);

        // Separador.
        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 16));
        layout.addView(spacer);

        layout.addView(inputPallets);

        new AlertDialog.Builder(this)
                .setTitle("🆕\u00A0Nuevo\u00A0Container")
                .setMessage("Se\u00A0borrarán\u00A0los\u00A0pallets\u00A0actuales\u00A0y\u00A0se\u00A0creará\u00A0una\u00A0sesión\u00A0nueva.")
                .setView(layout)
                .setPositiveButton("Crear", (d, w) -> {
                    String code = inputCode.getText().toString().trim().toUpperCase();
                    String palletsStr = inputPallets.getText().toString().trim();

                    int numPallets = 0;
                    if (!palletsStr.isEmpty()) {
                        try {
                            numPallets = Integer.parseInt(palletsStr);
                        } catch (NumberFormatException ignored) { }
                    }

                    if (numPallets <= 0 || numPallets >= 10000) {
                        Toast.makeText(this,
                                "❌\u00A0Número\u00A0de\u00A0pallets\u00A0inválido",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // Guardar container code.
                    if (!code.isEmpty()) {
                        fm.setContainerCode(code);
                    }

                    // Limpiar datos locales.
                    palletRepo.clearAll();
                    containerWasComplete = false;

                    // Crear nueva sesión en Firebase.
                    fm.createNewContainerSession(code, numPallets);

                    // Actualizar FAB.
                    expectedPallets = numPallets;
                    fabPrefs.edit().putInt(KEY_EXPECTED_PALLETS, numPallets).apply();
                    fabDisplayMode = MODE_RATIO;
                    fabPrefs.edit().putInt(KEY_FAB_MODE, MODE_RATIO).apply();

                    refrescarDatos();
                    refrescarContainerHeader();

                    String msg = "✅\u00A0Nuevo\u00A0container";
                    if (!code.isEmpty()) msg += "\u00A0" + code;
                    msg += "\u00A0—\u00A0meta:\u00A0" + numPallets;
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /**
     * Diálogo: Actualizar meta del Container Actual.
     * Mantiene pallets escaneadas, solo cambia la meta.
     */
    private void mostrarDialogoActualizarContainer() {
        int scanned = palletRepo.getPalletCount();

        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("Nueva\u00A0meta\u00A0de\u00A0pallets");
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setBackgroundColor(0xFF2a2a4a);
        input.setPadding(40, 30, 40, 30);
        if (expectedPallets > 0) {
            input.setText(String.valueOf(expectedPallets));
            input.selectAll();
        }

        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(50, 20, 50, 0);
        container.addView(input, params);

        String message = "Tienes\u00A0" + scanned + "\u00A0pallets\u00A0escaneadas.\n"
                + "Solo\u00A0se\u00A0actualizará\u00A0la\u00A0meta.";
        if (expectedPallets > 0) {
            message += "\nMeta\u00A0actual:\u00A0" + expectedPallets;
        }

        new AlertDialog.Builder(this)
                .setTitle("✏️\u00A0Actualizar\u00A0Meta")
                .setMessage(message)
                .setView(container)
                .setPositiveButton("Actualizar", (d, w) -> {
                    String txt = input.getText().toString().trim();
                    if (!txt.isEmpty()) {
                        try {
                            int num = Integer.parseInt(txt);
                            if (num > 0 && num < 10000) {
                                expectedPallets = num;
                                fabPrefs.edit().putInt(KEY_EXPECTED_PALLETS, num).apply();

                                if (fabDisplayMode == MODE_SCANNED) {
                                    fabDisplayMode = MODE_RATIO;
                                    fabPrefs.edit().putInt(KEY_FAB_MODE, MODE_RATIO).apply();
                                }

                                // Actualizar en Firebase.
                                FirebaseManager fm = FirebaseManager.getInstance(this);
                                if (fm.getCurrentSession() != null) {
                                    fm.updateContainerGoal(num);
                                } else {
                                    // No hay sesión, crear una.
                                    String code = fm.getContainerCode();
                                    fm.createNewContainerSession(
                                            code != null ? code : "", num);
                                }

                                refrescarContador();
                                refrescarContainerHeader();

                                String estado;
                                if (scanned >= num) {
                                    estado = "\u00A0—\u00A0¡Ya\u00A0completado!\u00A0🎉";
                                } else {
                                    estado = "\u00A0—\u00A0faltan\u00A0" + (num - scanned);
                                }
                                Toast.makeText(this,
                                        "✅\u00A0Meta:\u00A0" + num + estado,
                                        Toast.LENGTH_SHORT).show();
                            }
                        } catch (NumberFormatException ignored) {
                            Toast.makeText(this,
                                    "❌\u00A0Número\u00A0inválido",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Quitar\u00A0meta", (d, w) -> {
                    expectedPallets = 0;
                    fabPrefs.edit().putInt(KEY_EXPECTED_PALLETS, 0).apply();
                    fabDisplayMode = MODE_SCANNED;
                    fabPrefs.edit().putInt(KEY_FAB_MODE, MODE_SCANNED).apply();
                    containerWasComplete = false;
                    refrescarContador();
                    Toast.makeText(this,
                            "🔄\u00A0Meta\u00A0eliminada",
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ═══════════════════════════════════════════════════════════
    //              FAB — ACTUALIZACIÓN Y COLORES
    // ═══════════════════════════════════════════════════════════

    private void refrescarContador() {
        // v3.4: el FAB ahora es un botón de basura (borrar containers), ya no
        // muestra el contador ni cambia de color. El conteo se ve en el header
        // del container y en la lista de tasks. Aquí solo mantenemos la
        // detección de "container completo" para disparar la notificación.
        int scanned = palletRepo.getPalletCount();

        FirebaseManager fm = FirebaseManager.getInstance(this);
        ContainerSession session = fm.getCurrentSession();
        if (session != null && session.scannedPallets > scanned) {
            scanned = session.scannedPallets;
        }
        if (session != null && session.expectedPallets > 0 && expectedPallets != session.expectedPallets) {
            expectedPallets = session.expectedPallets;
            fabPrefs.edit().putInt(KEY_EXPECTED_PALLETS, expectedPallets).apply();
        }

        if (expectedPallets > 0 && scanned >= expectedPallets) {
            if (!containerWasComplete) {
                containerWasComplete = true;
                onContainerCompleto(scanned);
            }
        } else {
            containerWasComplete = false;
        }
    }

    private void onContainerCompleto(int totalPallets) {
        FirebaseManager fm = FirebaseManager.getInstance(this);
        String containerCode = fm.getContainerCode();
        String label = (containerCode != null && !containerCode.isEmpty())
                ? containerCode : "Container";

        Toast.makeText(this,
                "🎉\u00A0¡" + label + "\u00A0completo!\u00A0" + totalPallets + "\u00A0pallets",
                Toast.LENGTH_LONG).show();

        notifHelper.vibrarCompletado();
        animarPulsoCompletado();
        notifHelper.notifyContainerComplete(label, totalPallets);
    }

    // ═══════════════════════════════════════════════════════════
    //                    ANIMACIONES FAB
    // ═══════════════════════════════════════════════════════════

    private void animarCambioColor(int fromColor, int toColor) {
        ValueAnimator colorAnim = ValueAnimator.ofObject(
                new ArgbEvaluator(), fromColor, toColor);
        colorAnim.setDuration(500);
        colorAnim.addUpdateListener(animator -> {
            int color = (int) animator.getAnimatedValue();
            fab.setBackgroundTintList(ColorStateList.valueOf(color));
        });
        colorAnim.start();
    }

    private void animarCambioModo() {
        fab.animate()
                .scaleX(0.85f).scaleY(0.85f)
                .setDuration(100)
                .withEndAction(() ->
                        fab.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(200)
                                .setInterpolator(new OvershootInterpolator(2f))
                                .start()
                ).start();

        fabBadge.animate()
                .alpha(0f).setDuration(80)
                .withEndAction(() ->
                        fabBadge.animate().alpha(1f).setDuration(150).start()
                ).start();
    }

    private void animarPulsoCompletado() {
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(fab, "scaleX", 1f, 1.2f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(fab, "scaleY", 1f, 1.2f, 1f);
        scaleX.setDuration(400);
        scaleY.setDuration(400);
        scaleX.setRepeatCount(2);
        scaleY.setRepeatCount(2);
        scaleX.start();
        scaleY.start();
    }

    // ═══════════════════════════════════════════════════════════
    //                  NAVIGATION DRAWER
    // ═══════════════════════════════════════════════════════════

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.nav_home) {
            mostrarHome();
        } else if (id == R.id.nav_ajustes) {
            mostrarFragment(new AjustesFragment(), "Ajustes");
        } else if (id == R.id.nav_errores) {
            mostrarFragment(new ErroresFragment(), "Errores");
        } else if (id == R.id.nav_acerca) {
            mostrarFragment(new AcercaDeFragment(), "Acerca\u00A0de");
        } else if (id == R.id.nav_actualizar) {
            new UpdateChecker().checkForUpdates(this);
        }

        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (fragmentContainer.getVisibility() == View.VISIBLE) {
            mostrarHome();
        } else {
            super.onBackPressed();
        }
    }

    private void mostrarHome() {
        homeContainer.setVisibility(View.VISIBLE);
        fragmentContainer.setVisibility(View.GONE);
        fab.setVisibility(View.VISIBLE);
        fabBadge.setVisibility(View.GONE);   // v3.4: el FAB es papelera, sin badge
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("PalletTracker");
        }
        refrescarDatos();
    }

    private void mostrarFragment(Fragment fragment, String titulo) {
        homeContainer.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);
        fab.setVisibility(View.GONE);
        fabBadge.setVisibility(View.GONE);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titulo);
        }
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }

    // ====================== DATOS ======================

    private void refrescarDatos() {
        // v4.1 — Solo containers con pallets escaneadas HOY (los datos viejos se
        // conservan pero no se muestran). Ordenados por número de container.
        List<TaskSummary> tasks = palletRepo.getContainerListToday();

        taskAdapter.setTasks(tasks);

        if (tasks.isEmpty()) {
            txtEmpty.setVisibility(View.VISIBLE);
            recyclerTasks.setVisibility(View.GONE);
        } else {
            txtEmpty.setVisibility(View.GONE);
            recyclerTasks.setVisibility(View.VISIBLE);
        }

        refrescarContador();
        refrescarContainerHeader();
        refrescarEstadoOverlay();
    }

    // ====================== OVERLAY STATUS (v2.5) ======================

    private void refrescarEstadoOverlay() {
        TextView statusOverlay = findViewById(R.id.text_overlay_status);
        if (statusOverlay == null) return;

        if (FloatingButtonService.isRunning()) {
            statusOverlay.setText("✓\u00A0Botón\u00A0flotante\u00A0activo");
            statusOverlay.setTextColor(0xFF4CAF50);
            statusOverlay.setVisibility(View.VISIBLE);
        } else {
            SharedPreferences op = getSharedPreferences("overlay_prefs", MODE_PRIVATE);
            boolean wantsOverlay = op.getBoolean("overlay_enabled", true);
            if (wantsOverlay && Settings.canDrawOverlays(this)) {
                statusOverlay.setText("⚠\u00A0Overlay\u00A0activado\u00A0pero\u00A0no\u00A0visible\u00A0—\u00A0ve\u00A0a\u00A0Ajustes");
                statusOverlay.setTextColor(0xFFFF9800);
                statusOverlay.setVisibility(View.VISIBLE);
            } else {
                statusOverlay.setVisibility(View.GONE);
            }
        }
    }

    // ====================== TASK CLICK ======================

    @Override
    public void onTaskClick(TaskSummary task) {
        // v4.0 — Al tocar un container abrimos el detalle con sus pallets.
        Intent intent = new Intent(this, TaskDetailActivity.class);
        intent.putExtra("containerCode", task.taskId);
        startActivity(intent);
    }

    // ====================== ORDEN (v4.1) ======================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_sort, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_sort) {
            mostrarDialogoOrden();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void mostrarDialogoOrden() {
        final int[] modos = {
                SortHelper.SORT_SKU, SortHelper.SORT_ILP, SortHelper.SORT_ENTRY };
        final String[] opciones = {
                SortHelper.label(SortHelper.SORT_SKU),
                SortHelper.label(SortHelper.SORT_ILP),
                SortHelper.label(SortHelper.SORT_ENTRY) };

        int actual = SortHelper.getMode(this);
        int sel = 0;
        for (int i = 0; i < modos.length; i++) {
            if (modos[i] == actual) sel = i;
        }

        new AlertDialog.Builder(this)
                .setTitle("Ordenar\u00A0pallets")
                .setSingleChoiceItems(opciones, sel, (d, which) -> {
                    SortHelper.setMode(this, modos[which]);
                    d.dismiss();
                    refrescarDatos();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // ====================== RESET ======================

    private void mostrarDialogoReset() {
        notifHelper.vibrarCorto();

        FirebaseManager fm = FirebaseManager.getInstance(this);
        String containerCode = fm.getContainerCode();
        String label = (containerCode != null && !containerCode.isEmpty())
                ? "container\u00A0" + containerCode
                : "contador";

        new AlertDialog.Builder(this)
                .setTitle("🔄\u00A0Resetear\u00A0" + label)
                .setMessage("¿Borrar todos los pallets\u00A0y\u00A0resetear\u00A0a\u00A00?\n\n"
                        + "(La meta de " + expectedPallets + "\u00A0pallets\u00A0se\u00A0mantiene)")
                .setPositiveButton("Sí,\u00A0borrar", (d, w) -> {
                    palletRepo.clearAll();
                    containerWasComplete = false;

                    FirebaseManager.getInstance(this).clearSession();

                    refrescarDatos();
                    Toast.makeText(this,
                            "🔄\u00A0Reseteado\u00A0a\u00A00",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ═══════════════════════════════════════════════════════════
    //           BORRAR CONTAINERS (v3.4) — FAB PAPELERA
    // ═══════════════════════════════════════════════════════════

    /**
     * Muestra un diálogo con la lista de containers guardados y checkboxes
     * para seleccionar cuáles borrar (selección múltiple).
     */
    private void mostrarDialogoBorrarContainers() {
        notifHelper.vibrarCorto();

        final List<String> containers = palletRepo.getUniqueContainersToday();
        if (containers.isEmpty()) {
            Toast.makeText(this,
                    "🗑\u00A0No\u00A0hay\u00A0containers\u00A0de\u00A0hoy\u00A0para\u00A0borrar",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] labels = new String[containers.size()];
        final boolean[] checked = new boolean[containers.size()];
        for (int i = 0; i < containers.size(); i++) {
            int count = palletRepo.getPalletsForTask(containers.get(i)).size();
            labels[i] = containers.get(i) + "\u00A0(" + count + "\u00A0pallets)";
        }

        new AlertDialog.Builder(this)
                .setTitle("🗑\u00A0Borrar\u00A0containers")
                .setMultiChoiceItems(labels, checked,
                        (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Borrar\u00A0seleccionados", (d, w) -> {
                    List<String> aBorrar = new ArrayList<>();
                    for (int i = 0; i < containers.size(); i++) {
                        if (checked[i]) aBorrar.add(containers.get(i));
                    }
                    if (aBorrar.isEmpty()) {
                        Toast.makeText(this,
                                "No\u00A0seleccionaste\u00A0ningún\u00A0container",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    confirmarBorradoContainers(aBorrar);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /**
     * Diálogo de confirmación final antes de borrar los containers elegidos.
     */
    private void confirmarBorradoContainers(final List<String> containers) {
        StringBuilder sb = new StringBuilder();
        for (String c : containers) {
            if (sb.length() > 0) sb.append(",\u00A0");
            sb.append(c);
        }

        new AlertDialog.Builder(this)
                .setTitle("⚠\u00A0Confirmar\u00A0borrado")
                .setMessage("¿Seguro que quieres borrar " + containers.size()
                        + "\u00A0container(s)?\n\n" + sb
                        + "\n\nEsta\u00A0acción\u00A0no\u00A0se\u00A0puede\u00A0deshacer.")
                .setPositiveButton("Sí,\u00A0borrar", (d, w) -> {
                    int total = 0;
                    for (String c : containers) {
                        total += palletRepo.deletePalletsByContainer(c);
                    }
                    notifHelper.vibrarCorto();

                    // Refrescar la lista de tasks en Home.
                    refrescarDatos();

                    // Avisar al overlay flotante para que actualice su contador.
                    try {
                        Intent fabUpdate = new Intent(FloatingButtonService.ACTION_UPDATE_FAB);
                        fabUpdate.setPackage(getPackageName());
                        sendBroadcast(fabUpdate);
                    } catch (Exception ignored) { }

                    Toast.makeText(this,
                            "🗑\u00A0" + total + "\u00A0pallets\u00A0borrados",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
