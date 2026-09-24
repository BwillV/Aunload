package com.demoaccesibilidad.capture;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Modelo de datos para un pallet escaneado.
 */
public class Pallet {

    public String taskId;
    public String palletCode;
    public String sku;
    public double quantity;
    public String location;
    public long timestamp;

    public Pallet() {
        this.timestamp = System.currentTimeMillis();
    }

    public Pallet(String taskId, String palletCode, String sku,
                  double quantity, String location) {
        this.taskId = taskId;
        this.palletCode = palletCode;
        this.sku = sku;
        this.quantity = quantity;
        this.location = location;
        this.timestamp = System.currentTimeMillis();
    }

    // ====================== JSON ======================

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("taskId", taskId != null ? taskId : "");
            obj.put("palletCode", palletCode != null ? palletCode : "");
            obj.put("sku", sku != null ? sku : "");
            obj.put("quantity", quantity);
            obj.put("location", location != null ? location : "");
            obj.put("timestamp", timestamp);
        } catch (JSONException ignored) { }
        return obj;
    }

    public static Pallet fromJson(JSONObject obj) {
        Pallet p = new Pallet();
        p.taskId = obj.optString("taskId", "");
        p.palletCode = obj.optString("palletCode", "");
        p.sku = obj.optString("sku", "");
        p.quantity = obj.optDouble("quantity", 0.0);
        p.location = obj.optString("location", "");
        p.timestamp = obj.optLong("timestamp", 0L);
        return p;
    }

    // ====================== DISPLAY ======================

    public String getQuantityDisplay() {
        if (quantity == (long) quantity) {
            return String.valueOf((long) quantity);
        }
        return String.valueOf(quantity);
    }

    public String getTimestampDisplay() {
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT);
        return fmt.format(new java.util.Date(timestamp));
    }

    public String getDateDisplay() {
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("dd/MM\u00A0HH:mm", java.util.Locale.ROOT);
        return fmt.format(new java.util.Date(timestamp));
    }
}
