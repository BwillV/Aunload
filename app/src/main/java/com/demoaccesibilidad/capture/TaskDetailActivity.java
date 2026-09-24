package com.demoaccesibilidad.capture;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Pantalla de detalle de un Container.
 *
 * v4.0: Muestra los pallets escaneados dentro de un container.
 */
public class TaskDetailActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_detail);

        String containerCode = getIntent().getStringExtra("containerCode");
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
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Pallets\u00A0—\u00A0" + containerCode);
        }

        TextView txtTaskTitle = findViewById(R.id.txt_task_title);
        TextView txtPalletTotal = findViewById(R.id.txt_pallet_total);

        PalletRepository repo = new PalletRepository(this);
        List<Pallet> pallets = repo.getPalletsForTask(containerCode);

        txtTaskTitle.setText(containerCode);
        txtPalletTotal.setText(pallets.size() + "\u00A0pallets\u00A0escaneados");

        // RecyclerView.
        RecyclerView recycler = findViewById(R.id.recycler_pallets);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        PalletAdapter adapter = new PalletAdapter();
        adapter.setPallets(pallets);
        recycler.setAdapter(adapter);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
