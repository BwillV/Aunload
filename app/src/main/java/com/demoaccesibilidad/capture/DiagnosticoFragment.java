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
                    "Grabación\u00A0iniciada.\u00A0Usa la otra app y vuelve aquí al terminar.",
                    Toast.LENGTH_LONG).show();
        } else {
            repo.setDiagGrabando(false);
            int n = repo.getDumps().size();
            Toast.makeText(requireContext(),
                    "Grabación\u00A0detenida.\u00A0" + n + "\u00A0pantallas guardadas.",
                    Toast.LENGTH_LONG).show();
        }
        refrescar();
    }

    // ====================== VISUALIZACIÓN ======================

    private void refrescar() {
        actualizarEstado();
        List<String> dumps = repo.getDumps();
        if (dumps.isEmpty()) {
            txtDiag.setText("Todavía\u00A0no\u00A0hay\u00A0datos.\nInicia la grabación, usa la otra app, y vuelve aquí.");
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
            btnDiagGrabar.setText("⏹\u00A0Detener\u00A0diagnóstico");
            btnDiagGrabar.setBackgroundColor(0xFFF44336);
            String estado = "🔴\u00A0GRABANDO…\u00A0(" + n + "\u00A0pantallas\u00A0capturadas)";
            if (!ultimaHora.isEmpty()) estado += "\nÚltima\u00A0captura:\u00A0" + ultimaHora;
            txtDiagEstado.setText(estado);
        } else {
            btnDiagGrabar.setText("▶\u00A0Comenzar\u00A0diagnóstico");
            btnDiagGrabar.setBackgroundColor(0xFF2196F3);
            txtDiagEstado.setText(n > 0
                    ? "Detenido\u00A0—\u00A0" + n + "\u00A0pantallas\u00A0grabadas.\u00A0Toca\u00A0\"Copiar\"\u00A0y\u00A0envíalas."
                    : "Detenido.\u00A0Toca\u00A0\"Comenzar\"\u00A0antes\u00A0de\u00A0ir\u00A0a\u00A0la\u00A0otra\u00A0app.");
        }
    }

    // ====================== ACCIONES ======================

    private void borrar() {
        repo.limpiarDiagnostico();
        Toast.makeText(requireContext(), "Diagnóstico\u00A0borrado", Toast.LENGTH_SHORT).show();
        refrescar();
    }

    private void copiar() {
        List<String> dumps = repo.getDumps();
        String texto;
        if (dumps.isEmpty()) {
            texto = "Sin datos de diagnóstico.";
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
            clip.setPrimaryClip(ClipData.newPlainText("Diagnóstico", texto));
        }
        Toast.makeText(requireContext(), "Diagnóstico\u00A0copiado\u00A0al\u00A0portapapeles",
                Toast.LENGTH_SHORT).show();
    }
}
