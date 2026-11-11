package com.demoaccesibilidad.capture;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Pantalla de detalle de un Container.
 *
 * v4.1: Muestra los pallets de un container con opción de ordenamiento
 *       (por SKU, por ILP, o por orden de entrada).
 */
public class TaskDetailActivity extends AppCompatActivity {

    private String containerCode = "Container";
    private TextView txtPalletTotal;
    private PalletAdapter adapter;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_detail);

        containerCode = getIntent().getStringExtra("containerCode");
        if (containerCode == null || containerCode.isEmpty()) {
            // Compatibilidad con el extra antiguo.
            containerCode = getIntent().getStringExtra("taskId");
        }
        if (containerCode == null || containerCode.isEmpty()) {
            containerCode = "Container";
        }

        // Toolbar.
        Toolbar toolbar = findViewById(R.id.toolbar_detail);
        setSupportActionBar(toolbar);
        // v4.4: containerCode es el id interno "yyyy-MM-dd#N" (se usa para buscar
        // los pallets); en pantalla se muestra como "Container N".
        String containerDisplay = PalletRepository.displayContainer(containerCode);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(getString(R.string.detail_titulo, containerDisplay));
        }

        TextView txtTaskTitle = findViewById(R.id.txt_task_title);
        txtPalletTotal = findViewById(R.id.txt_pallet_total);
        txtTaskTitle.setText(containerDisplay);

        // RecyclerView.
        RecyclerView recycler = findViewById(R.id.recycler_pallets);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PalletAdapter();
        recycler.setAdapter(adapter);
        // v4.6: pulsación larga sobre una pallet para escribir/editar su ubicación a mano.
        adapter.setOnPalletLongClickListener(this::mostrarDialogoUbicacion);
        // v5.0: botón rojo por fila para BORRAR una pallet (se sincroniza al equipo).
        adapter.setOnPalletDeleteListener(this::mostrarDialogoBorrarPallet);

        cargarPallets();
    }

    /**
     * v5.0: confirma y borra UNA pallet. Se elimina del equipo en Firebase
     * (baja el total en todos los escáneres en tiempo real) y también en local.
     */
    private void mostrarDialogoBorrarPallet(Pallet p) {
        if (p == null || p.palletCode == null) return;
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.cd_borrar_pallet))
                .setMessage(getString(R.string.dlg_borrar_un_mensaje))
                .setPositiveButton(getString(R.string.btn_borrar), (d, which) -> {
                    // Borrar del equipo (Firebase) y en local.
                    FirebaseManager.getInstance(this)
                            .deleteTeamPallet(containerCode, p.palletCode);
                    PalletRepository repo = new PalletRepository(this);
                    repo.deletePallet(p.palletCode);
                    PalletTrackerApp.logEvento(this, "Pallet borrada",
                            "Pallet\u00A0" + p.palletCode
                                    + "\u00A0(container\u00A0" + containerCode + ")");
                    // Avisar a la pantalla principal para refrescar contador/FAB.
                    sendBroadcast(new Intent(
                            "com.demoaccesibilidad.capture.UPDATE_FLOATING_FAB"));
                    Toast.makeText(this,
                            getString(R.string.toast_pallet_borrada, p.palletCode),
                            Toast.LENGTH_SHORT).show();
                    cargarPallets();
                })
                .setNegativeButton(getString(R.string.btn_cancelar), null)
                .show();
    }

    /** Carga los pallets del container aplicando el orden elegido. */
    private void cargarPallets() {
        PalletRepository repo = new PalletRepository(this);
        List<Pallet> pallets = repo.getPalletsForTask(containerCode);
        pallets = SortHelper.sort(pallets, SortHelper.getMode(this));
        adapter.setPallets(pallets);
        int n = pallets.size();
        txtPalletTotal.setText(getResources().getQuantityString(
                R.plurals.pallets_escaneados, n, n));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_sort, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_sort) {
            mostrarDialogoOrden();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * v4.6: diálogo para escribir o corregir a mano la ubicación de una pallet.
     */
    private void mostrarDialogoUbicacion(Pallet p) {
        if (p == null || p.palletCode == null) return;

        final EditText input = new EditText(this);
        input.setHint(getString(R.string.hint_ubicacion));
        if (p.location != null && !p.location.isEmpty()) {
            input.setText(p.location);
            input.setSelection(p.location.length());
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlg_ubicacion_titulo, p.palletCode))
                .setMessage(getString(R.string.dlg_ubicacion_mensaje))
                .setView(input)
                .setPositiveButton(getString(R.string.btn_guardar), (d, which) -> {
                    String loc = input.getText() != null
                            ? input.getText().toString().trim() : "";
                    if (loc.isEmpty()) {
                        Toast.makeText(this,
                                getString(R.string.toast_ubicacion_vacia),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    PalletRepository repo = new PalletRepository(this);
                    repo.updatePalletLocation(p.palletCode, loc, true);
                    PalletTrackerApp.logEvento(this, "Ubicación completada",
                            "Pallet\u00A0" + p.palletCode + "\u00A0→\u00A0" + loc
                                    + "\u00A0(fuente:\u00A0manual)");
                    Toast.makeText(this,
                            getString(R.string.toast_ubicacion_guardada),
                            Toast.LENGTH_SHORT).show();
                    cargarPallets();
                })
                .setNegativeButton(getString(R.string.btn_cancelar), null)
                .show();
    }

    private void mostrarDialogoOrden() {
        final int[] modos = {
                SortHelper.SORT_SKU, SortHelper.SORT_ILP, SortHelper.SORT_ENTRY };
        final String[] opciones = {
                SortHelper.label(this, SortHelper.SORT_SKU),
                SortHelper.label(this, SortHelper.SORT_ILP),
                SortHelper.label(this, SortHelper.SORT_ENTRY) };

        int actual = SortHelper.getMode(this);
        int sel = 0;
        for (int i = 0; i < modos.length; i++) {
            if (modos[i] == actual) sel = i;
        }

        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.dlg_ordenar_titulo))
                .setSingleChoiceItems(opciones, sel, (d, which) -> {
                    SortHelper.setMode(this, modos[which]);
                    d.dismiss();
                    cargarPallets();
                })
                .setNegativeButton(getString(R.string.btn_cerrar), null)
                .show();
    }
}
