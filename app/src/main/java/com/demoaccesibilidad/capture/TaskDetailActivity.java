package com.demoaccesibilidad.capture;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

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
            getSupportActionBar().setTitle("Pallets\u00A0—\u00A0" + containerDisplay);
        }

        TextView txtTaskTitle = findViewById(R.id.txt_task_title);
        txtPalletTotal = findViewById(R.id.txt_pallet_total);
        txtTaskTitle.setText(containerDisplay);

        // RecyclerView.
        RecyclerView recycler = findViewById(R.id.recycler_pallets);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PalletAdapter();
        recycler.setAdapter(adapter);

        cargarPallets();
    }

    /** Carga los pallets del container aplicando el orden elegido. */
    private void cargarPallets() {
        PalletRepository repo = new PalletRepository(this);
        List<Pallet> pallets = repo.getPalletsForTask(containerCode);
        pallets = SortHelper.sort(pallets, SortHelper.getMode(this));
        adapter.setPallets(pallets);
        txtPalletTotal.setText(pallets.size() + "\u00A0pallets\u00A0escaneados");
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
                    cargarPallets();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }
}
