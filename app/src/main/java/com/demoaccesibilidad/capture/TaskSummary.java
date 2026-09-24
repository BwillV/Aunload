package com.demoaccesibilidad.capture;

/**
 * Resumen de un Task para la lista principal.
 */
public class TaskSummary {

    public String taskId;
    public int palletCount;
    public long lastTimestamp;

    public TaskSummary(String taskId, int palletCount, long lastTimestamp) {
        this.taskId = taskId;
        this.palletCount = palletCount;
        this.lastTimestamp = lastTimestamp;
    }

    public String getLastTimeDisplay() {
        if (lastTimestamp == 0L) return "";
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT);
        return fmt.format(new java.util.Date(lastTimestamp));
    }
}
