package com.demoaccesibilidad.capture;

import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pantalla de detalle de un Task / Container.
 *
 * v2.3: Si viene en modo container, muestra TODOS los pallets
 *       del container agrupados visualmente por taskId.
 */
public class TaskDetailActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_task_detail);

        String taskId = getIntent().getStringExtra("taskId");
        boolean containerMode = getIntent().getBooleanExtra("containerMode", false);
        String containerCode = getIntent().getStringExtra("containerCode");

        if (taskId == null) taskId = "";

        // Toolbar.
        Toolbar toolbar = findViewById(R.id.toolbar_detail);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        TextView txtTaskTitle = findViewById(R.id.txt_task_title);
        TextView txtPalletTotal = findViewById(R.id.txt_pallet_total);

        PalletRepository repo = new PalletRepository(this);
        List<Pallet> pallets;

        if (containerMode) {
            // Modo container: mostrar TODOS los pallets.
            pallets = repo.getAllPallets();

            // Título con container code.
            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(
                        containerCode != null && !containerCode.isEmpty()
                                ? containerCode : "Container");
            }

            // Subtítulo con tasks.
            Set<String> tasks = new LinkedHashSet<>();
            for (Pallet p : pallets) {
                if (p.taskId != null && !p.taskId.isEmpty()) {
                    tasks.add(p.taskId);
                }
            }

            if (tasks.size() > 1) {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (String t : tasks) {
                    if (!first) sb.append("\u00A0/\u00A0");
                    sb.append(t);
                    first = false;
                }
                txtTaskTitle.setText(sb.toString());
            } else if (tasks.size() == 1) {
                txtTaskTitle.setText(tasks.iterator().next());
            } else {
                txtTaskTitle.setText(containerCode != null ? containerCode : "Container");
            }

            txtPalletTotal.setText(pallets.size()
                    + "\u00A0pallets\u00A0escaneados\u00A0•\u00A0"
                    + tasks.size() + "\u00A0tasks");
        } else {
            // Modo normal: un solo task.
            pallets = repo.getPalletsForTask(taskId);

            if (getSupportActionBar() != null) {
                getSupportActionBar().setTitle(taskId);
            }
            txtTaskTitle.setText(taskId);
            txtPalletTotal.setText(pallets.size() + "\u00A0pallets\u00A0escaneados");
        }

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
