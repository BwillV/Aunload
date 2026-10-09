package com.demoaccesibilidad.capture;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

/**
 * Pantalla de Ajustes — v2.5
 *
 * Secciones:
 *  1. Diagnóstico — ver capturas del servicio
 *  2. Container/Equipo — código compartido para trabajo en equipo
 *  3. Botón Flotante — overlay sobre todas las apps (NUEVA v2.4)
 *  4. Idioma — selector (solo español por ahora)
 *  5. Accesibilidad — estado y activación
 *  6. Sincronización — configuración multi-dispositivo (Firebase)
 *  7. Notificaciones — sonido y vibración
 */
public class AjustesFragment extends Fragment {

    private TextView txtEstadoAcc, btnActivarAcc;
    private SwitchCompat switchSync;
    private EditText editDeviceName;
    private TextView btnTestConnection, txtSyncStatus, txtDeviceCount;
    private SwitchCompat switchSonido, switchVibracion;

    // Container/Equipo (v2.3).
    private EditText editContainerCode;
    private TextView btnGuardarContainer, btnLimpiarContainer, txtContainerActual;

    // Botón Flotante / Overlay (v2.4 → v2.5).
    private SwitchCompat switchOverlay;
    private TextView btnPermisoOverlay, txtEstadoOverlay, btnForzarOverlay;
    private SeekBar seekOpacidad;
    private TextView txtOpacidadVal;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_ajustes, container, false);
        } catch (Throwable t) {
            // Si la propia inflación del layout falla (recurso/tema/vista),
            // registramos la traza real y mostramos una vista mínima en lugar
            // de tumbar la app.
            PalletTrackerApp.logException(getContext(), "AjustesFragment.onCreateView", t);
            android.widget.TextView tv = new android.widget.TextView(inflater.getContext());
            tv.setText("⚠\u00A0No\u00A0se\u00A0pudo\u00A0cargar\u00A0Ajustes.\n"
                    + "Revisa\u00A0la\u00A0pestaña\u00A0\"Errores\"\u00A0para\u00A0ver\u00A0el\u00A0detalle.");
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackgroundColor(0xFF1a1a2e);
            tv.setPadding(48, 48, 48, 48);
            tv.setTextSize(16);
            return tv;
        }
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        try {
        // ═══════ DIAGNÓSTICO ═══════
        TextView btnDiag = v.findViewById(R.id.btn_ver_diagnostico);
        btnDiag.setOnClickListener(b -> {
            requireActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new DiagnosticoFragment())
                    .addToBackStack(null)
                    .commit();
        });

        // ═══════ CONTAINER / EQUIPO (v2.3) ═══════
        editContainerCode = v.findViewById(R.id.edit_container_code);
        btnGuardarContainer = v.findViewById(R.id.btn_guardar_container);
        btnLimpiarContainer = v.findViewById(R.id.btn_limpiar_container);
        txtContainerActual = v.findViewById(R.id.txt_container_actual);

        FirebaseManager fm = FirebaseManager.getInstance(requireContext());

        // Cargar código de equipo actual (persistente).
        String currentCode = fm.getTeamCode();
        if (currentCode != null && !currentCode.isEmpty()) {
            editContainerCode.setText(currentCode);
            txtContainerActual.setText("Código\u00A0de\u00A0equipo:\u00A0" + currentCode);
            txtContainerActual.setTextColor(0xFF4CAF50);
        } else {
            txtContainerActual.setText("Código\u00A0de\u00A0equipo:\u00A0Sin\u00A0configurar");
            txtContainerActual.setTextColor(0xFFFF9800);
        }

        btnGuardarContainer.setOnClickListener(b -> {
            String code = editContainerCode.getText().toString().trim().toUpperCase();
            if (code.isEmpty()) {
                Toast.makeText(requireContext(),
                        "❌\u00A0Ingresa\u00A0un\u00A0código\u00A0de\u00A0equipo",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            // v4.9 — Unirse al equipo. El código es PERSISTENTE: quien se une
            // adopta el container que ya estaba en curso, sin borrar nada.
            fm.joinTeam(code);
            txtContainerActual.setText("Código\u00A0de\u00A0equipo:\u00A0" + code);
            txtContainerActual.setTextColor(0xFF4CAF50);

            Toast.makeText(requireContext(),
                    "✅\u00A0Te\u00A0uniste\u00A0al\u00A0equipo:\u00A0" + code,
                    Toast.LENGTH_SHORT).show();
        });

        btnLimpiarContainer.setOnClickListener(b -> {
            // v4.9 — Salir del equipo (única forma de borrar el código).
            editContainerCode.setText("");
            fm.leaveTeam();
            txtContainerActual.setText("Código\u00A0de\u00A0equipo:\u00A0Sin\u00A0configurar");
            txtContainerActual.setTextColor(0xFFFF9800);
            Toast.makeText(requireContext(),
                    "👋\u00A0Saliste\u00A0del\u00A0equipo",
                    Toast.LENGTH_SHORT).show();
        });

        // ═══════ BOTÓN FLOTANTE / OVERLAY (v2.4) ═══════
        switchOverlay = v.findViewById(R.id.switch_overlay);
        btnPermisoOverlay = v.findViewById(R.id.btn_permiso_overlay);
        txtEstadoOverlay = v.findViewById(R.id.txt_estado_overlay);
        seekOpacidad = v.findViewById(R.id.seek_opacidad);
        txtOpacidadVal = v.findViewById(R.id.txt_opacidad_val);

        SharedPreferences overlayPrefs = requireContext()
                .getSharedPreferences("overlay_prefs", 0);
        boolean overlayEnabled = overlayPrefs.getBoolean("overlay_enabled", true);
        switchOverlay.setChecked(overlayEnabled);

        actualizarEstadoOverlay();

        switchOverlay.setOnCheckedChangeListener((btn, isChecked) -> {
            overlayPrefs.edit().putBoolean("overlay_enabled", isChecked).apply();

            if (isChecked) {
                if (OverlayPermissionHelper.hasOverlayPermission(requireContext())) {
                    iniciarOverlay();
                    Toast.makeText(requireContext(),
                            "📱\u00A0Botón\u00A0flotante\u00A0activado",
                            Toast.LENGTH_SHORT).show();
                } else {
                    OverlayPermissionHelper.requestOverlayPermission(requireActivity());
                }
            } else {
                detenerOverlay();
                Toast.makeText(requireContext(),
                        "📴\u00A0Botón\u00A0flotante\u00A0desactivado",
                        Toast.LENGTH_SHORT).show();
            }
            actualizarEstadoOverlay();
        });

        btnPermisoOverlay.setOnClickListener(b -> {
            if (OverlayPermissionHelper.hasOverlayPermission(requireContext())) {
                Toast.makeText(requireContext(),
                        "✅\u00A0Permiso\u00A0ya\u00A0concedido",
                        Toast.LENGTH_SHORT).show();
            } else {
                OverlayPermissionHelper.requestOverlayPermission(requireActivity());
            }
        });

        // ═══ BOTÓN FORZAR INICIO (v2.5) ═══
        btnForzarOverlay = v.findViewById(R.id.btn_forzar_overlay);
        btnForzarOverlay.setOnClickListener(b -> {
            if (!OverlayPermissionHelper.hasOverlayPermission(requireContext())) {
                OverlayPermissionHelper.requestOverlayPermission(requireActivity());
                return;
            }
            // Forzar: matar servicio existente y reiniciar limpio.
            detenerOverlay();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                overlayPrefs.edit().putBoolean("overlay_enabled", true).apply();
                switchOverlay.setChecked(true);
                iniciarOverlayForzado();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    actualizarEstadoOverlay();
                    if (FloatingButtonService.isRunning()) {
                        Toast.makeText(requireContext(),
                                "✅\u00A0Botón\u00A0flotante\u00A0iniciado\u00A0correctamente",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(requireContext(),
                                "⚠\u00A0No\u00A0se\u00A0pudo\u00A0iniciar.\u00A0Revisa\u00A0permisos.",
                                Toast.LENGTH_LONG).show();
                    }
                }, 800);
            }, 300);
        });

        // Opacidad (50%-100% → seekbar 0-50 → mapa a 0.50-1.0).
        float savedOpacity = overlayPrefs.getFloat("overlay_opacity", 0.92f);
        int seekVal = Math.round((savedOpacity - 0.5f) * 100);
        seekOpacidad.setMax(50);
        seekOpacidad.setProgress(Math.max(0, Math.min(50, seekVal)));
        txtOpacidadVal.setText(Math.round(savedOpacity * 100) + "%");

        seekOpacidad.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                float opacity = 0.5f + (progress / 100f);
                txtOpacidadVal.setText(Math.round(opacity * 100) + "%");
                if (fromUser) {
                    overlayPrefs.edit().putFloat("overlay_opacity", opacity).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        // ═══════ IDIOMA ═══════
        TextView txtIdioma = v.findViewById(R.id.txt_idioma_actual);
        txtIdioma.setText("Español\u00A0🇪🇸");

        // ═══════ ACCESIBILIDAD ═══════
        txtEstadoAcc = v.findViewById(R.id.txt_estado_accesibilidad);
        btnActivarAcc = v.findViewById(R.id.btn_activar_accesibilidad);

        btnActivarAcc.setOnClickListener(b -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        // ═══════ SINCRONIZACIÓN FIREBASE ═══════
        switchSync = v.findViewById(R.id.switch_sync);
        editDeviceName = v.findViewById(R.id.edit_device_name);
        btnTestConnection = v.findViewById(R.id.btn_test_connection);
        txtSyncStatus = v.findViewById(R.id.txt_sync_status);
        txtDeviceCount = v.findViewById(R.id.txt_device_count);

        EditText editFirebaseUrl = v.findViewById(R.id.edit_firebase_url);
        if (editFirebaseUrl != null) {
            editFirebaseUrl.setText("aunload-rescue\u00A0(auto-configurado)");
            editFirebaseUrl.setEnabled(false);
            editFirebaseUrl.setAlpha(0.6f);
        }

        switchSync.setChecked(fm.isSyncEnabled());
        editDeviceName.setText(fm.getDeviceName());

        actualizarVistaSync(fm.isSyncEnabled());

        // Indicador de estado de conexión al abrir la pantalla.
        mostrarEstadoConexion(fm);

        switchSync.setOnCheckedChangeListener((btn, isChecked) -> {
            fm.setSyncEnabled(isChecked);
            // setSyncEnabled puede rechazar la activación si Firebase no inició.
            boolean realmente = fm.isSyncEnabled();
            if (isChecked && !realmente) {
                // No se pudo activar: mostrar el error COMPLETO.
                btn.setChecked(false);
                String err = fm.getLastInitError();
                txtSyncStatus.setText("❌\u00A0No\u00A0se\u00A0pudo\u00A0activar.\n"
                        + (err != null ? err : "Error\u00A0desconocido\u00A0al\u00A0iniciar\u00A0Firebase"));
                txtSyncStatus.setTextColor(0xFFF44336);
                Toast.makeText(requireContext(),
                        "❌\u00A0No\u00A0se\u00A0pudo\u00A0activar\u00A0la\u00A0sincronización",
                        Toast.LENGTH_LONG).show();
                return;
            }
            actualizarVistaSync(realmente);
            mostrarEstadoConexion(fm);
            Toast.makeText(requireContext(),
                    realmente ? "🔗\u00A0Sincronización\u00A0activada"
                              : "📴\u00A0Sincronización\u00A0desactivada",
                    Toast.LENGTH_SHORT).show();
        });

        editDeviceName.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override
            public void afterTextChanged(Editable s) {
                String name = s.toString().trim();
                if (!name.isEmpty()) {
                    fm.setDeviceName(name);
                }
            }
        });

        btnTestConnection.setOnClickListener(b -> {
            txtSyncStatus.setText("⏳\u00A0Probando\u00A0conexión...");
            txtSyncStatus.setTextColor(0xFF9E9E9E);
            btnTestConnection.setEnabled(false);

            fm.testConnection((success, message) -> {
                if (!isAdded()) return;
                btnTestConnection.setEnabled(true);
                if (success) {
                    txtSyncStatus.setText("✅\u00A0Conexión\u00A0exitosa\u00A0con\u00A0Firebase");
                    txtSyncStatus.setTextColor(0xFF4CAF50);
                } else {
                    txtSyncStatus.setText("❌\u00A0Error:\u00A0" + message);
                    txtSyncStatus.setTextColor(0xFFF44336);
                }
            });
        });

        int deviceCount = fm.getActiveDeviceCount();
        txtDeviceCount.setText("📱\u00A0Dispositivos\u00A0activos:\u00A0" + deviceCount);

        // ═══════ NOTIFICACIONES ═══════
        switchSonido = v.findViewById(R.id.switch_sonido);
        switchVibracion = v.findViewById(R.id.switch_vibracion);

        NotificationHelper nh = new NotificationHelper(requireContext());
        switchSonido.setChecked(nh.isSoundEnabled());
        switchVibracion.setChecked(nh.isVibrationEnabled());

        switchSonido.setOnCheckedChangeListener((btn, checked) -> nh.setSoundEnabled(checked));
        switchVibracion.setOnCheckedChangeListener((btn, checked) -> nh.setVibrationEnabled(checked));

        actualizarEstadoAccesibilidad();
        } catch (Throwable t) {
            // Captura defensiva: si algo falla al construir la pantalla de
            // Ajustes, registramos la traza real en crash_log.txt y evitamos
            // que la app se cierre por completo.
            PalletTrackerApp.logException(getContext(), "AjustesFragment.onViewCreated", t);
            if (getContext() != null) {
                Toast.makeText(getContext(),
                        "⚠\u00A0Hubo\u00A0un\u00A0problema\u00A0al\u00A0abrir\u00A0Ajustes.\u00A0Se\u00A0guardó\u00A0el\u00A0detalle.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        try {
        actualizarEstadoAccesibilidad();
        actualizarEstadoOverlay();

        // ═══ NOTA v2.8: se ELIMINÓ el auto-arranque del servicio overlay ═══
        // Antes, al abrir Ajustes con el permiso concedido se iniciaba el
        // FloatingButtonService automáticamente. Ese arranque corre en el
        // ciclo de vida del propio servicio (fuera de este try/catch) y, si
        // fallaba, tumbaba toda la app justo al entrar a Ajustes. Ahora el
        // botón flotante SOLO se inicia por acción explícita del usuario
        // (el switch o el botón "Forzar inicio").
        SharedPreferences overlayPrefs = requireContext()
                .getSharedPreferences("overlay_prefs", 0);
        boolean overlayEnabled = overlayPrefs.getBoolean("overlay_enabled", true);

        // Sync switch visual con estado real.
        if (switchOverlay != null) {
            switchOverlay.setChecked(overlayEnabled);
        }

        // ═══ v2.9: auto-arranque SEGURO del overlay ═══
        // El switch viene ON por defecto, pero como el listener no dispara con
        // setChecked(), el servicio no arrancaba solo y el botón no aparecía.
        // Ahora que FloatingButtonService.onCreate está blindado con
        // try/catch(Throwable) y el layout se infla con tema Material, es
        // seguro arrancarlo aquí: si el switch está ON, hay permiso y el
        // servicio no corre, lo iniciamos.
        if (overlayEnabled
                && OverlayPermissionHelper.hasOverlayPermission(requireContext())
                && !FloatingButtonService.isRunning()) {
            iniciarOverlay();
        }

        FirebaseManager fm = FirebaseManager.getInstance(requireContext());
        int deviceCount = fm.getActiveDeviceCount();
        if (txtDeviceCount != null) {
            txtDeviceCount.setText("📱\u00A0Dispositivos\u00A0activos:\u00A0" + deviceCount);
        }

        // Refrescar código de equipo (persistente).
        String code = fm.getTeamCode();
        if (txtContainerActual != null) {
            if (code != null && !code.isEmpty()) {
                txtContainerActual.setText("Código\u00A0de\u00A0equipo:\u00A0" + code);
                txtContainerActual.setTextColor(0xFF4CAF50);
            } else {
                txtContainerActual.setText("Código\u00A0de\u00A0equipo:\u00A0Sin\u00A0configurar");
                txtContainerActual.setTextColor(0xFFFF9800);
            }
        }
        } catch (Throwable t) {
            PalletTrackerApp.logException(getContext(), "AjustesFragment.onResume", t);
        }
    }

    private void actualizarVistaSync(boolean enabled) {
        float alpha = enabled ? 1.0f : 0.4f;
        editDeviceName.setEnabled(enabled);
        editDeviceName.setAlpha(alpha);
        // v4.2: el botón "Probar conexión" SIEMPRE queda habilitado para poder
        // diagnosticar aunque la sync esté apagada.
        btnTestConnection.setEnabled(true);
        btnTestConnection.setAlpha(1.0f);
    }

    /** v4.2: indicador de estado de la conexión con Firebase. */
    private void mostrarEstadoConexion(FirebaseManager fm) {
        if (txtSyncStatus == null) return;
        if (fm.isFirebaseAvailable()) {
            txtSyncStatus.setText("🟢\u00A0Estado:\u00A0" + fm.getConnectionStatus());
            txtSyncStatus.setTextColor(0xFF4CAF50);
        } else {
            String err = fm.getLastInitError();
            txtSyncStatus.setText("🔴\u00A0Estado:\u00A0" + fm.getConnectionStatus()
                    + (err != null ? "\n" + err : ""));
            txtSyncStatus.setTextColor(0xFFF44336);
        }
    }

    private void actualizarEstadoAccesibilidad() {
        boolean activo = isAccessibilityServiceEnabled();
        if (activo) {
            txtEstadoAcc.setText("✅\u00A0Servicio:\u00A0ACTIVO");
            txtEstadoAcc.setTextColor(0xFF4CAF50);
            btnActivarAcc.setText("⚙\u00A0Abrir\u00A0Ajustes\u00A0de\u00A0Accesibilidad");
            btnActivarAcc.setBackgroundColor(0xFF607D8B);
        } else {
            txtEstadoAcc.setText("❌\u00A0Servicio:\u00A0INACTIVO");
            txtEstadoAcc.setTextColor(0xFFF44336);
            btnActivarAcc.setText("⚙\u00A0Activar\u00A0Servicio\u00A0de\u00A0Accesibilidad");
            btnActivarAcc.setBackgroundColor(0xFFF44336);
        }
    }

    // ═══════ OVERLAY HELPERS (v2.4) ═══════

    private void actualizarEstadoOverlay() {
        if (txtEstadoOverlay == null || getContext() == null) return;
        boolean hasPermission = OverlayPermissionHelper.hasOverlayPermission(requireContext());
        boolean running = FloatingButtonService.isRunning();
        SharedPreferences op = requireContext().getSharedPreferences("overlay_prefs", 0);
        boolean switchIsOn = op.getBoolean("overlay_enabled", true);

        if (running) {
            txtEstadoOverlay.setText("✅\u00A0Botón\u00A0flotante:\u00A0ACTIVO");
            txtEstadoOverlay.setTextColor(0xFF4CAF50);
            btnPermisoOverlay.setBackgroundColor(0xFF607D8B);
            btnPermisoOverlay.setText("✅\u00A0Permiso\u00A0de\u00A0overlay\u00A0concedido");
            if (btnForzarOverlay != null) btnForzarOverlay.setVisibility(View.GONE);
        } else if (hasPermission) {
            txtEstadoOverlay.setText("⚪\u00A0Botón\u00A0flotante:\u00A0INACTIVO");
            txtEstadoOverlay.setTextColor(0xFFFF9800);
            btnPermisoOverlay.setBackgroundColor(0xFF607D8B);
            btnPermisoOverlay.setText("✅\u00A0Permiso\u00A0concedido\u00A0—\u00A0activa\u00A0el\u00A0switch");
            // Mostrar botón forzar si el switch dice ON pero servicio murió.
            if (btnForzarOverlay != null) {
                btnForzarOverlay.setVisibility(switchIsOn ? View.VISIBLE : View.GONE);
            }
        } else {
            txtEstadoOverlay.setText("❌\u00A0Sin\u00A0permiso\u00A0de\u00A0overlay");
            txtEstadoOverlay.setTextColor(0xFFF44336);
            btnPermisoOverlay.setBackgroundColor(0xFFF44336);
            btnPermisoOverlay.setText("⚙\u00A0Activar\u00A0permiso\u00A0de\u00A0overlay");
            if (btnForzarOverlay != null) btnForzarOverlay.setVisibility(View.GONE);
        }
    }

    private void iniciarOverlay() {
        if (getContext() == null) return;
        if (!OverlayPermissionHelper.hasOverlayPermission(requireContext())) return;
        if (FloatingButtonService.isRunning()) return;

        Intent intent = new Intent(requireContext(), FloatingButtonService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent);
        } else {
            requireContext().startService(intent);
        }
    }

    /** Forzar inicio: no checa isRunning(), mata y reinicia. */
    private void iniciarOverlayForzado() {
        if (getContext() == null) return;
        if (!OverlayPermissionHelper.hasOverlayPermission(requireContext())) return;

        Intent intent = new Intent(requireContext(), FloatingButtonService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent);
        } else {
            requireContext().startService(intent);
        }
    }

    private void detenerOverlay() {
        if (getContext() == null) return;
        try {
            Intent stop = new Intent(FloatingButtonService.ACTION_STOP_OVERLAY);
            stop.setPackage(requireContext().getPackageName());
            requireContext().sendBroadcast(stop);
        } catch (Exception ignored) { }
        try {
            requireContext().stopService(new Intent(requireContext(), FloatingButtonService.class));
        } catch (Exception ignored) { }
    }

    private boolean isAccessibilityServiceEnabled() {
        if (getContext() == null) return false;
        String serviceName = requireContext().getPackageName() + "/"
                + ScreenCaptureService.class.getCanonicalName();
        try {
            String enabled = Settings.Secure.getString(
                    requireContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return enabled != null && enabled.contains(serviceName);
        } catch (Exception ignored) { }
        return false;
    }
}
