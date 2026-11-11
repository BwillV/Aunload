package com.demoaccesibilidad.capture;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * ErroresFragment — Pantalla "Errores" (v2.8).
 *
 * Muestra dentro de la app el contenido de crash_log.txt para que el
 * usuario pueda ver / compartir el detalle técnico de cualquier fallo
 * sin necesidad de un cable ni de acceder al administrador de archivos.
 *
 * Botones:
 *  - Actualizar: recarga el registro.
 *  - Compartir:  envía el texto por cualquier app (correo, WhatsApp...).
 *  - Limpiar:    borra el registro.
 */
public class ErroresFragment extends Fragment {

    private TextView txtContenido, txtEstado, txtRuta;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        try {
            return inflater.inflate(R.layout.fragment_errores, container, false);
        } catch (Throwable t) {
            PalletTrackerApp.logException(getContext(), "ErroresFragment.onCreateView", t);
            TextView tv = new TextView(inflater.getContext());
            tv.setText(inflater.getContext().getString(R.string.err_no_cargar));
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackgroundColor(0xFF1a1a2e);
            tv.setPadding(48, 48, 48, 48);
            return tv;
        }
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        try {
            txtContenido = v.findViewById(R.id.txt_contenido_log);
            txtEstado = v.findViewById(R.id.txt_estado_log);
            txtRuta = v.findViewById(R.id.txt_ruta_log);

            TextView btnActualizar = v.findViewById(R.id.btn_actualizar_log);
            TextView btnCompartir = v.findViewById(R.id.btn_compartir_log);
            TextView btnLimpiar = v.findViewById(R.id.btn_limpiar_log);

            if (txtRuta != null && getContext() != null) {
                txtRuta.setText(getString(R.string.err_archivo, PalletTrackerApp.getCrashLogPath(getContext())));
            }

            btnActualizar.setOnClickListener(b -> cargarLog());
            btnCompartir.setOnClickListener(b -> compartirLog());
            btnLimpiar.setOnClickListener(b -> limpiarLog());

            cargarLog();
        } catch (Throwable t) {
            PalletTrackerApp.logException(getContext(), "ErroresFragment.onViewCreated", t);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        cargarLog();
    }

    private void cargarLog() {
        if (getContext() == null || txtContenido == null) return;
        String log = PalletTrackerApp.readCrashLog(getContext());
        if (log == null || log.trim().isEmpty()) {
            txtContenido.setText(getString(R.string.err_sin_errores));
            if (txtEstado != null) {
                txtEstado.setText(getString(R.string.err_todo_ok));
                txtEstado.setTextColor(0xFF4CAF50);
            }
        } else {
            txtContenido.setText(log);
            if (txtEstado != null) {
                int errores = contarErrores(log);
                txtEstado.setText(getString(R.string.err_eventos, errores));
                txtEstado.setTextColor(0xFFFF9800);
            }
        }
    }

    private int contarErrores(String log) {
        int count = 0, idx = 0;
        while ((idx = log.indexOf("PalletTracker CRASH", idx)) != -1) {
            count++;
            idx += "PalletTracker CRASH".length();
        }
        return Math.max(count, 1);
    }

    private void compartirLog() {
        if (getContext() == null) return;
        String log = PalletTrackerApp.readCrashLog(getContext());
        if (log == null || log.trim().isEmpty()) {
            Toast.makeText(getContext(),
                    getString(R.string.err_nada_compartir),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.err_subject));
            share.putExtra(Intent.EXTRA_TEXT, log);
            startActivity(Intent.createChooser(share, getString(R.string.err_chooser)));
        } catch (Throwable t) {
            Toast.makeText(getContext(),
                    getString(R.string.err_error_compartir),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void limpiarLog() {
        if (getContext() == null) return;
        PalletTrackerApp.clearCrashLog(getContext());
        cargarLog();
        Toast.makeText(getContext(),
                getString(R.string.err_borrado),
                Toast.LENGTH_SHORT).show();
    }
}
