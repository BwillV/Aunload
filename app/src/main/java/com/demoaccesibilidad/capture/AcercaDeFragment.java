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
        // v4.5 — versión real desde el PackageManager (BuildConfig deshabilitado).
        String versionName;
        try {
            versionName = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Throwable t) {
            versionName = "?";
        }
        txt.setText(getString(R.string.acerca_contenido, versionName));
    }
}
