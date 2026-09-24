package com.demoaccesibilidad.capture;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptador para la lista de Tasks en la pantalla principal.
 */
public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.TaskViewHolder> {

    public interface OnTaskClickListener {
        void onTaskClick(TaskSummary task);
    }

    private List<TaskSummary> tasks = new ArrayList<>();
    private final OnTaskClickListener listener;

    public TaskAdapter(OnTaskClickListener listener) {
        this.listener = listener;
    }

    public void setTasks(List<TaskSummary> newTasks) {
        this.tasks = newTasks != null ? newTasks : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_task, parent, false);
        return new TaskViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        TaskSummary task = tasks.get(position);
        holder.bind(task, listener);
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    static class TaskViewHolder extends RecyclerView.ViewHolder {
        final TextView txtTaskId;
        final TextView txtPalletCount;
        final TextView txtLastTime;

        TaskViewHolder(@NonNull View v) {
            super(v);
            txtTaskId = v.findViewById(R.id.txt_task_id);
            txtPalletCount = v.findViewById(R.id.txt_pallet_count);
            txtLastTime = v.findViewById(R.id.txt_last_time);
        }

        void bind(TaskSummary task, OnTaskClickListener listener) {
            txtTaskId.setText(task.taskId);
            txtPalletCount.setText("(" + task.palletCount + ")");
            txtLastTime.setText(task.getLastTimeDisplay());

            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onTaskClick(task);
            });
        }
    }
}
