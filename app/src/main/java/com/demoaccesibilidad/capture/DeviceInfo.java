package com.demoaccesibilidad.capture;

import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Información de un dispositivo conectado a la sesión.
 *
 * Para sincronización multi-dispositivo — cada Honeywell
 * escáner reporta su identidad y actividad.
 */
public class DeviceInfo {

    public String deviceId;
    public String deviceName;
    public int palletsScannedByDevice;
    public long lastSeen;
    public boolean isOnline;

    public DeviceInfo() {
        this.deviceId = UUID.randomUUID().toString().substring(0, 8);
        this.deviceName = Build.MODEL;
        this.lastSeen = System.currentTimeMillis();
        this.isOnline = true;
    }

    public DeviceInfo(String deviceId, String deviceName) {
        this();
        this.deviceId = deviceId;
        this.deviceName = deviceName;
    }

    // ====================== ESTADO ======================

    /**
     * Un dispositivo se considera offline si no reportó en 30 seg.
     */
    public boolean isStale() {
        return System.currentTimeMillis() - lastSeen > 30_000;
    }

    public void heartbeat() {
        this.lastSeen = System.currentTimeMillis();
        this.isOnline = true;
    }

    // ====================== JSON ======================

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("deviceId", deviceId != null ? deviceId : "");
            obj.put("deviceName", deviceName != null ? deviceName : "");
            obj.put("palletsScannedByDevice", palletsScannedByDevice);
            obj.put("lastSeen", lastSeen);
            obj.put("isOnline", isOnline);
        } catch (JSONException ignored) { }
        return obj;
    }

    public static DeviceInfo fromJson(JSONObject obj) {
        DeviceInfo d = new DeviceInfo();
        d.deviceId = obj.optString("deviceId", d.deviceId);
        d.deviceName = obj.optString("deviceName", "Desconocido");
        d.palletsScannedByDevice = obj.optInt("palletsScannedByDevice", 0);
        d.lastSeen = obj.optLong("lastSeen", 0L);
        d.isOnline = obj.optBoolean("isOnline", false);
        return d;
    }

    // ====================== DISPLAY ======================

    public String getStatusDisplay() {
        if (isStale()) return "⚫\u00A0Offline";
        return "🟢\u00A0Online";
    }

    public String getSummaryDisplay() {
        return deviceName + "\u00A0—\u00A0" + palletsScannedByDevice + "\u00A0pallets"
                + "\u00A0" + getStatusDisplay();
    }
}
