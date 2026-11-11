package com.demoaccesibilidad.capture;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.util.List;

/**
 * Pantalla de Diagnóstico.
 *
 * Muestra los volcados de pantalla capturados por el servicio.
 * El usuario puede comenzar/detener grabación, ver capturas,
 * copiar al portapapeles y borrar datos.
 */
public class DiagnosticoFragment extends Fragment {

    private DiagnosticoRepository repo;
    private TextView txtDiag, btnDiagCopiar, btnDiagRefrescar;
    private TextView btnDiagGrabar, txtDiagEstado, btnDiagBorrar;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_diagnostico, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        repo = new DiagnosticoRepository(requireContext());

        txtDiag          = v.findViewById(R.id.txt_diag);
        btnDiagCopiar    = v.findViewById(R.id.btn_diag_copiar);
        btnDiagRefrescar = v.findViewById(R.id.btn_diag_refrescar);
        btnDiagGrabar    = v.findViewById(R.id.btn_diag_grabar);
        txtDiagEstado    = v.findViewById(R.id.txt_diag_estado);
        btnDiagBorrar    = v.findViewById(R.id.btn_diag_borrar);

        btnDiagRefrescar.setOnClickListener(b -> refrescar());
        btnDiagCopiar.setOnClickListener(b -> copiar());
        btnDiagGrabar.setOnClickListener(b -> alternarGrabacion());
        btnDiagBorrar.setOnClickListener(b -> borrar());

        refrescar();
    }

    @Override
    public void onResume() {
        super.onResume();
        refrescar();
    }

    // ====================== GRABACIÓN ======================

    private void alternarGrabacion() {
        boolean grabando = repo.isDiagGrabando();
        if (!grabando) {
            repo.limpiarDiagnostico();
            repo.setDiagGrabando(true);
            Toast.makeText(requireContext(),
                    getString(R.string.diag_toast_iniciado2),
                    Toast.LENGTH_LONG).show();
        } else {
            repo.setDiagGrabando(false);
            int n = repo.getDumps().size();
            Toast.makeText(requireContext(),
                    getString(R.string.diag_toast_detenido2, n),
                    Toast.LENGTH_LONG).show();
        }
        refrescar();
    }

    // ====================== VISUALIZACIÓN ======================

    private void refrescar() {
        actualizarEstado();
        List<String> dumps = repo.getDumps();
        if (dumps.isEmpty()) {
            txtDiag.setText(getString(R.string.diag_sin_datos2));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dumps.size(); i++) {
            if (i > 0) sb.append("\n\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
            sb.append(dumps.get(i));
        }
        txtDiag.setText(sb.toString());
    }

    private void actualizarEstado() {
        if (btnDiagGrabar == null) return;
        boolean grabando = repo.isDiagGrabando();
        int n = repo.getDumps().size();
        String ultimaHora = repo.getUltimaCapturaTexto();

        if (grabando) {
            btnDiagGrabar.setText(getString(R.string.diag_detener));
            btnDiagGrabar.setBackgroundColor(0xFFF44336);
            String estado = getString(R.string.diag_grabando_fmt, n);
            if (!ultimaHora.isEmpty()) estado += getString(R.string.diag_ultima_captura, ultimaHora);
            txtDiagEstado.setText(estado);
        } else {
            btnDiagGrabar.setText(getString(R.string.diag_comenzar));
            btnDiagGrabar.setBackgroundColor(0xFF2196F3);
            txtDiagEstado.setText(n > 0
                    ? getString(R.string.diag_detenido_fmt, n)
                    : getString(R.string.diag_detenido_vacio));
        }
    }

    // ====================== ACCIONES ======================

    private void borrar() {
        repo.limpiarDiagnostico();
        Toast.makeText(requireContext(), getString(R.string.diag_toast_borrado), Toast.LENGTH_SHORT).show();
        refrescar();
    }

    private void copiar() {
        List<String> dumps = repo.getDumps();
        String texto;
        if (dumps.isEmpty()) {
            texto = getString(R.string.diag_sin_datos_corto);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dumps.size(); i++) {
                if (i > 0) sb.append("\n\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");
                sb.append(dumps.get(i));
            }
            texto = sb.toString();
        }
        ClipboardManager clip = (ClipboardManager)
                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clip != null) {
            clip.setPrimaryClip(ClipData.newPlainText(getString(R.string.diag_clip_label), texto));
        }
        Toast.makeText(requireContext(), getString(R.string.diag_toast_copiado2),
                Toast.LENGTH_SHORT).show();
    }
}
