package com.demoaccesibilidad.capture;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * Fragment informativo — explica qué hace PalletTracker.
 */
public class AcercaDeFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_acerca_de, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        TextView txt = v.findViewById(R.id.txt_acerca_de);
        txt.setText(
            "📦\u00A0PalletTracker\u00A0v2.1\n\n" +
            "App de rastreo automático de pallets\u00A0en\u00A0warehouse.\n\n" +
            "🔍\u00A0¿Qué\u00A0hace?\n" +
            "Captura automáticamente los datos de cada pallet " +
            "mientras trabajas en el WMS:\n" +
            "•\u00A0Task\u00A0ID\n" +
            "•\u00A0Pallet\u00A0Code\u00A0(ILP)\n" +
            "•\u00A0SKU\n" +
            "•\u00A0Cantidad\n" +
            "•\u00A0Ubicación\n\n" +
            "🛠\u00A0¿Cómo\u00A0funciona?\n" +
            "1.\u00A0Activa el servicio en ☰\u00A0>\u00A0Ajustes\u00A0>\u00A0Accesibilidad\n" +
            "2.\u00A0Trabaja normalmente en el WMS\n" +
            "3.\u00A0Cada \"Put\u00A0away\u00A0success\" registra el pallet\n" +
            "4.\u00A0Revisa tus tasks y pallets en esta app\n\n" +
            "📊\u00A0Botón\u00A0Flotante\u00A0(FAB):\n" +
            "•\u00A0Tap:\u00A0Alterna entre Escaneados / Faltan / Ratio\n" +
            "•\u00A0Doble\u00A0tap:\u00A0Configurar pallets esperados\n" +
            "•\u00A0Long\u00A0press:\u00A0Resetear contador\n" +
            "•\u00A0Azul\u00A0=\u00A0en progreso\u00A0•\u00A0Verde\u00A0=\u00A0¡completo!\n\n" +
            "🔗\u00A0Multi-Dispositivo:\n" +
            "En ☰\u00A0>\u00A0Ajustes\u00A0>\u00A0Sincronización puedes\n" +
            "conectar múltiples escáneres al mismo container.\n\n" +
            "🔧\u00A0Diagnóstico:\n" +
            "En ☰\u00A0>\u00A0Ajustes\u00A0>\u00A0Ver\u00A0Capturas puedes ver\n" +
            "exactamente qué textos lee el servicio.\n\n" +
            "💡\u00A0Tip: Si un pallet no se registra, verifica\n" +
            "que el servicio de accesibilidad esté activo."
        );
    }
}
