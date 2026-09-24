package com.demoaccesibilidad.capture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Modelo de sesión de container — compatible con Firebase Realtime DB.
 *
 * v2.3: Soporte de containerCode compartido entre múltiples dispositivos
 *        y múltiples tasks en el mismo container.
 *
 * Estructura Firebase:
 *  containers/{containerCode}/
 *      containerCode: "CNT-001"
 *      expectedPallets: 20
 *      scannedPallets: 15
 *      tasks: ["TASK-1536", "TASK-16373"]
 *      pallets: { palletCode: { taskId, sku, qty, loc, ... } }
 *      devices: { deviceId: { name, count, lastSeen, ... } }
 */
public class ContainerSession {

    public String sessionId;
    public String containerCode;      // ← NUEVO: código compartido
    public String taskId;             // taskId principal (compatibilidad)
    public Set<String> taskIds;       // ← NUEVO: múltiples tasks
    public int expectedPallets;
    public int scannedPallets;
    public List<Pallet> pallets;
    public Map<String, DeviceInfo> activeDevices;
    public long lastUpdate;
    public long createdAt;

    public ContainerSession() {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.taskIds = new HashSet<>();
        this.pallets = new ArrayList<>();
        this.activeDevices = new HashMap<>();
        this.lastUpdate = System.currentTimeMillis();
        this.createdAt = System.currentTimeMillis();
    }

    public ContainerSession(String containerCode, String taskId, int expectedPallets) {
        this();
        this.containerCode = containerCode;
        this.taskId = taskId;
        this.expectedPallets = expectedPallets;
        if (taskId != null && !taskId.isEmpty()) {
            this.taskIds.add(taskId);
        }
    }

    // Compatibilidad con v2.2.
    public ContainerSession(String taskId, int expectedPallets) {
        this("", taskId, expectedPallets);
    }

    // ====================== ESTADO ======================

    public int getRemainingPallets() {
        return Math.max(0, expectedPallets - scannedPallets);
    }

    public boolean isComplete() {
        return expectedPallets > 0 && scannedPallets >= expectedPallets;
    }

    public String getRatioDisplay() {
        return scannedPallets + "/" + expectedPallets;
    }

    /**
     * Retorna los taskIds unidos con " / " para mostrar en UI.
     * Ej: "TASK-1536 / TASK-16373"
     */
    public String getTasksDisplay() {
        if (taskIds == null || taskIds.isEmpty()) {
            return taskId != null ? taskId : "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String tid : taskIds) {
            if (tid == null || tid.isEmpty()) continue;
            if (!first) sb.append("\u00A0/\u00A0");
            sb.append(tid);
            first = false;
        }
        return sb.length() > 0 ? sb.toString() : (taskId != null ? taskId : "");
    }

    /**
     * Agrega un taskId al set de tasks del container.
     */
    public void addTaskId(String tid) {
        if (tid != null && !tid.isEmpty()) {
            taskIds.add(tid);
        }
    }

    // ====================== JSON (Firebase-compatible) ======================

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("sessionId", sessionId != null ? sessionId : "");
            obj.put("containerCode", containerCode != null ? containerCode : "");
            obj.put("taskId", taskId != null ? taskId : "");
            obj.put("expectedPallets", expectedPallets);
            obj.put("scannedPallets", scannedPallets);
            obj.put("lastUpdate", lastUpdate);
            obj.put("createdAt", createdAt);

            // Tasks array.
            JSONArray tasksArr = new JSONArray();
            if (taskIds != null) {
                for (String tid : taskIds) {
                    tasksArr.put(tid);
                }
            }
            obj.put("tasks", tasksArr);

            JSONArray palletsArr = new JSONArray();
            if (pallets != null) {
                for (Pallet p : pallets) {
                    palletsArr.put(p.toJson());
                }
            }
            obj.put("pallets", palletsArr);

            JSONObject devicesObj = new JSONObject();
            if (activeDevices != null) {
                for (Map.Entry<String, DeviceInfo> entry : activeDevices.entrySet()) {
                    devicesObj.put(entry.getKey(), entry.getValue().toJson());
                }
            }
            obj.put("activeDevices", devicesObj);

        } catch (JSONException ignored) { }
        return obj;
    }

    public static ContainerSession fromJson(JSONObject obj) {
        ContainerSession s = new ContainerSession();
        s.sessionId = obj.optString("sessionId", s.sessionId);
        s.containerCode = obj.optString("containerCode", "");
        s.taskId = obj.optString("taskId", "");
        s.expectedPallets = obj.optInt("expectedPallets", 0);
        s.scannedPallets = obj.optInt("scannedPallets", 0);
        s.lastUpdate = obj.optLong("lastUpdate", 0L);
        s.createdAt = obj.optLong("createdAt", 0L);

        // Tasks.
        s.taskIds = new HashSet<>();
        JSONArray tasksArr = obj.optJSONArray("tasks");
        if (tasksArr != null) {
            for (int i = 0; i < tasksArr.length(); i++) {
                String tid = tasksArr.optString(i, "");
                if (!tid.isEmpty()) s.taskIds.add(tid);
            }
        }
        if (s.taskId != null && !s.taskId.isEmpty()) {
            s.taskIds.add(s.taskId);
        }

        s.pallets = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("pallets");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject po = arr.optJSONObject(i);
                if (po != null) s.pallets.add(Pallet.fromJson(po));
            }
        }

        s.activeDevices = new HashMap<>();
        JSONObject devObj = obj.optJSONObject("activeDevices");
        if (devObj != null) {
            Iterator<String> keys = devObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject dObj = devObj.optJSONObject(key);
                if (dObj != null) {
                    s.activeDevices.put(key, DeviceInfo.fromJson(dObj));
                }
            }
        }

        return s;
    }
}
