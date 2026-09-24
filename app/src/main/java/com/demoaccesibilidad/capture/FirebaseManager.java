package com.demoaccesibilidad.capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
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
 * FirebaseManager — Singleton para sincronización multi-dispositivo.
 *
 * ╔══════════════════════════════════════════════════════╗
 * ║  v2.3 — CONTAINERS COMPARTIDOS                      ║
 * ║  Agrupa por containerCode para trabajo en equipo.    ║
 * ║  Múltiples tasks en el mismo container.              ║
 * ╚══════════════════════════════════════════════════════╝
 *
 * Estructura en Firebase:
 *  /containers/{containerCode}/
 *      containerCode: "CNT-001"
 *      expectedPallets: 20
 *      scannedPallets: 5
 *      tasks: ["TASK-1536", "TASK-16373"]
 *      pallets/
 *          {palletCode}: { taskId, sku, quantity, location, timestamp, scannedBy }
 *      devices/
 *          {deviceId}: { name, count, lastSeen, isOnline }
 */
public class FirebaseManager {

    private static final String TAG = "FirebaseSync";
    private static final String PREFS_NAME = "firebase_sync_prefs";
    private static final String KEY_ENABLED = "sync_enabled";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";
    private static final String KEY_CURRENT_SESSION = "current_session";
    private static final String KEY_CONTAINER_CODE = "container_code";

    private static final long HEARTBEAT_INTERVAL = 10_000L;

    private static FirebaseManager instance;

    private final SharedPreferences prefs;
    private final Context appContext;
    private final Handler mainHandler;

    private boolean syncEnabled;
    private String deviceId;
    private String deviceName;

    private ContainerSession currentSession;
    private DatabaseReference sessionRef;
    private ValueEventListener sessionListener;
    private Runnable heartbeatRunnable;

    // Listener para notificar a la UI de cambios remotos.
    public interface SyncListener {
        void onRemotePalletAdded(Pallet pallet, String fromDeviceName);
        void onSessionUpdated(ContainerSession session);
        void onDeviceStatusChanged(DeviceInfo device);
        void onSyncError(String message);
    }

    private SyncListener listener;

    // ====================== SINGLETON ======================

    private FirebaseManager(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        cargarConfiguracion();
        initFirebaseApp();
    }

    public static synchronized FirebaseManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new FirebaseManager(ctx);
        }
        return instance;
    }

    // ====================== INICIALIZACIÓN ======================

    // Marca si Firebase está realmente disponible en este dispositivo.
    // En escáner sin Google Play Services, las clases de Firebase pueden
    // no cargar (NoClassDefFoundError), por eso atrapamos Throwable.
    private boolean firebaseAvailable = false;

    private void initFirebaseApp() {
        try {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(appContext);
            }
            firebaseAvailable = true;
            Log.d(TAG, "Firebase\u00A0inicializado\u00A0correctamente");
        } catch (Throwable t) {
            // Throwable cubre Exception y Error/LinkageError (dispositivos sin GMS).
            firebaseAvailable = false;
            Log.e(TAG, "Firebase\u00A0no\u00A0disponible\u00A0en\u00A0este\u00A0dispositivo", t);
        }
    }

    /**
     * Indica si Firebase pudo inicializarse en este dispositivo.
     * Si es false, toda la sincronización se ignora silenciosamente.
     */
    public boolean isFirebaseAvailable() {
        return firebaseAvailable;
    }

    private DatabaseReference getDatabase() {
        return FirebaseDatabase.getInstance().getReference();
    }

    // ====================== CONFIGURACIÓN ======================

    private void cargarConfiguracion() {
        syncEnabled = prefs.getBoolean(KEY_ENABLED, false);
        deviceId = prefs.getString(KEY_DEVICE_ID, null);
        deviceName = prefs.getString(KEY_DEVICE_NAME, null);

        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }

        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = Build.MODEL;
            prefs.edit().putString(KEY_DEVICE_NAME, deviceName).apply();
        }

        String sessionJson = prefs.getString(KEY_CURRENT_SESSION, null);
        if (sessionJson != null) {
            try {
                currentSession = ContainerSession.fromJson(new JSONObject(sessionJson));
            } catch (Exception ignored) {
                currentSession = null;
            }
        }
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean enabled) {
        // Si Firebase no cargó (dispositivo sin GMS), no permitir activar sync.
        if (enabled && !firebaseAvailable) {
            Log.w(TAG, "No\u00A0se\u00A0puede\u00A0activar\u00A0sync:\u00A0Firebase\u00A0no\u00A0disponible");
            this.syncEnabled = false;
            prefs.edit().putBoolean(KEY_ENABLED, false).apply();
            if (listener != null) {
                listener.onSyncError("Firebase no disponible en este dispositivo (falta Google Play Services)");
            }
            return;
        }
        this.syncEnabled = enabled;
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        Log.d(TAG, "Sincronización\u00A0" + (enabled ? "ACTIVADA" : "DESACTIVADA"));

        if (enabled && currentSession != null) {
            reconectarSesion();
        } else if (!enabled) {
            detenerEscucha();
        }
    }

    public void setDeviceName(String name) {
        this.deviceName = name;
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply();
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setSyncListener(SyncListener listener) {
        this.listener = listener;
    }

    // ====================== CONTAINER CODE ======================

    /**
     * Guarda el código de container en SharedPreferences.
     */
    public void setContainerCode(String code) {
        prefs.edit().putString(KEY_CONTAINER_CODE, code != null ? code.trim() : "").apply();
    }

    /**
     * Obtiene el código de container actual.
     */
    public String getContainerCode() {
        return prefs.getString(KEY_CONTAINER_CODE, "");
    }

    // ====================== SESIÓN DE CONTAINER ======================

    /**
     * Crea una nueva sesión de container.
     * Si containerCode está configurado, usa ese como key en Firebase.
     */
    public ContainerSession createNewContainerSession(String containerCode, int expectedPallets) {
        // Limpiar sesión anterior si existe.
        if (currentSession != null) {
            clearSession();
        }

        String code = (containerCode != null && !containerCode.isEmpty())
                ? containerCode : getContainerCode();

        currentSession = new ContainerSession(code, "", expectedPallets);

        DeviceInfo thisDevice = new DeviceInfo(deviceId, deviceName);
        currentSession.activeDevices.put(deviceId, thisDevice);

        guardarSesionLocal();

        if (syncEnabled && code != null && !code.isEmpty()) {
            // Usar containerCode como key en Firebase.
            sessionRef = getDatabase().child("containers").child(sanitizeKey(code));

            Map<String, Object> sessionMap = sessionToMap(currentSession);
            sessionRef.setValue(sessionMap)
                    .addOnSuccessListener(v ->
                            Log.d(TAG, "Container\u00A0creado\u00A0en\u00A0Firebase:\u00A0" + code))
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error\u00A0creando\u00A0container", e);
                        if (listener != null) listener.onSyncError("Error creando container: " + e.getMessage());
                    });

            iniciarEscucha();
            iniciarHeartbeat();
        } else if (syncEnabled) {
            // Sin containerCode → usar sessionId como antes.
            sessionRef = getDatabase().child("sessions").child(currentSession.sessionId);
            Map<String, Object> sessionMap = sessionToMap(currentSession);
            sessionRef.setValue(sessionMap);
            iniciarEscucha();
            iniciarHeartbeat();
        }

        Log.d(TAG, "Container\u00A0nuevo:\u00A0" + code + "\u00A0esperadas=" + expectedPallets);
        return currentSession;
    }

    /**
     * Actualiza la meta de pallets del container actual sin perder pallets.
     */
    public void updateContainerGoal(int newExpectedPallets) {
        if (currentSession == null) return;

        currentSession.expectedPallets = newExpectedPallets;
        currentSession.lastUpdate = System.currentTimeMillis();
        guardarSesionLocal();

        if (syncEnabled && sessionRef != null) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("expectedPallets", newExpectedPallets);
            updates.put("lastUpdate", ServerValue.TIMESTAMP);
            sessionRef.updateChildren(updates)
                    .addOnSuccessListener(v ->
                            Log.d(TAG, "Meta\u00A0actualizada:\u00A0" + newExpectedPallets))
                    .addOnFailureListener(e ->
                            Log.e(TAG, "Error\u00A0actualizando\u00A0meta", e));
        }
    }

    /**
     * Se une a un container existente por containerCode.
     */
    public void joinContainerSession(String containerCode) {
        if (containerCode == null || containerCode.isEmpty()) return;

        String key = sanitizeKey(containerCode);
        sessionRef = getDatabase().child("containers").child(key);

        sessionRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    currentSession = parseSnapshot(snapshot);
                    currentSession.containerCode = containerCode;

                    DeviceInfo me = new DeviceInfo(deviceId, deviceName);
                    currentSession.activeDevices.put(deviceId, me);

                    sessionRef.child("devices").child(deviceId)
                            .setValue(deviceToMap(me));

                    guardarSesionLocal();
                    iniciarEscucha();
                    iniciarHeartbeat();

                    if (listener != null) {
                        mainHandler.post(() -> listener.onSessionUpdated(currentSession));
                    }
                    Log.d(TAG, "Unido\u00A0a\u00A0container:\u00A0" + containerCode);
                } else {
                    // No existe → crear uno nuevo con ese código.
                    Log.d(TAG, "Container\u00A0no\u00A0encontrado,\u00A0se\u00A0creará\u00A0al\u00A0configurar\u00A0meta");
                    if (listener != null) {
                        mainHandler.post(() -> listener.onSyncError(
                                "Container " + containerCode + " no encontrado. Se creará al configurar pallets esperadas."));
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (listener != null) {
                    mainHandler.post(() -> listener.onSyncError(error.getMessage()));
                }
            }
        });
    }

    /**
     * Obtiene la lista de taskIds del container actual.
     */
    public List<String> getTasksInContainer() {
        if (currentSession == null || currentSession.taskIds == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(currentSession.taskIds);
    }

    /**
     * Compatibilidad v2.2 — initSession redirige a createNewContainerSession.
     */
    public ContainerSession initSession(String taskId, int expectedPallets) {
        String code = getContainerCode();
        ContainerSession session = createNewContainerSession(code, expectedPallets);
        if (taskId != null && !taskId.isEmpty()) {
            session.taskId = taskId;
            session.addTaskId(taskId);
        }
        return session;
    }

    /**
     * Se une a una sesión existente (por sessionId).
     * Compatibilidad con v2.2.
     */
    public void joinSession(String sessionId) {
        sessionRef = getDatabase().child("sessions").child(sessionId);

        sessionRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    currentSession = parseSnapshot(snapshot);

                    DeviceInfo me = new DeviceInfo(deviceId, deviceName);
                    currentSession.activeDevices.put(deviceId, me);

                    sessionRef.child("devices").child(deviceId)
                            .setValue(deviceToMap(me));

                    guardarSesionLocal();
                    iniciarEscucha();
                    iniciarHeartbeat();

                    if (listener != null) {
                        mainHandler.post(() -> listener.onSessionUpdated(currentSession));
                    }
                } else {
                    if (listener != null) {
                        mainHandler.post(() -> listener.onSyncError("Sesión no encontrada: " + sessionId));
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                if (listener != null) {
                    mainHandler.post(() -> listener.onSyncError(error.getMessage()));
                }
            }
        });
    }

    public ContainerSession getCurrentSession() {
        return currentSession;
    }

    // ====================== SINCRONIZACIÓN DE PALLETS ======================

    /**
     * Reporta un pallet escaneado — dual write: local + Firebase.
     * Ahora agrega el taskId al set de tasks del container.
     */
    public void reportPalletScanned(Pallet pallet) {
        if (currentSession == null) return;

        // Verificar duplicado.
        for (Pallet existing : currentSession.pallets) {
            if (existing.palletCode != null && existing.palletCode.equals(pallet.palletCode)) {
                Log.d(TAG, "Pallet\u00A0duplicado\u00A0ignorado:\u00A0" + pallet.palletCode);
                return;
            }
        }

        currentSession.scannedPallets++;
        currentSession.pallets.add(pallet);
        currentSession.lastUpdate = System.currentTimeMillis();

        // Agregar taskId al set de tasks del container.
        if (pallet.taskId != null && !pallet.taskId.isEmpty()) {
            currentSession.addTaskId(pallet.taskId);
        }

        DeviceInfo me = currentSession.activeDevices.get(deviceId);
        if (me != null) {
            me.palletsScannedByDevice++;
            me.heartbeat();
        }

        guardarSesionLocal();

        if (syncEnabled && sessionRef != null) {
            String key = pallet.palletCode != null && !pallet.palletCode.isEmpty()
                    ? sanitizeKey(pallet.palletCode)
                    : "pallet_" + System.currentTimeMillis();

            Map<String, Object> palletMap = palletToMap(pallet);
            palletMap.put("scannedBy", deviceId);
            palletMap.put("scannedByName", deviceName);

            Map<String, Object> updates = new HashMap<>();
            updates.put("pallets/" + key, palletMap);
            updates.put("scannedPallets", currentSession.scannedPallets);
            updates.put("lastUpdate", ServerValue.TIMESTAMP);

            // Actualizar lista de tasks.
            List<String> tasksList = new ArrayList<>(currentSession.taskIds);
            updates.put("tasks", tasksList);

            if (me != null) {
                updates.put("devices/" + deviceId, deviceToMap(me));
            }

            sessionRef.updateChildren(updates)
                    .addOnSuccessListener(v ->
                            Log.d(TAG, "Pallet\u00A0sincronizado:\u00A0" + pallet.palletCode))
                    .addOnFailureListener(e ->
                            Log.e(TAG, "Error\u00A0sincronizando\u00A0pallet", e));
        }
    }

    // ====================== ESCUCHAR ACTUALIZACIONES ======================

    private void iniciarEscucha() {
        if (sessionRef == null) return;
        detenerEscucha();

        sessionListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                ContainerSession remote = parseSnapshot(snapshot);
                mergeRemoteSession(remote);

                if (listener != null) {
                    mainHandler.post(() -> listener.onSessionUpdated(currentSession));
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Error\u00A0escuchando\u00A0sesión:\u00A0" + error.getMessage());
                if (listener != null) {
                    mainHandler.post(() -> listener.onSyncError(error.getMessage()));
                }
            }
        };

        sessionRef.addValueEventListener(sessionListener);
        Log.d(TAG, "Escuchando\u00A0cambios\u00A0en\u00A0container");
    }

    private void detenerEscucha() {
        if (sessionRef != null && sessionListener != null) {
            sessionRef.removeEventListener(sessionListener);
            sessionListener = null;
        }
        detenerHeartbeat();
    }

    private void mergeRemoteSession(ContainerSession remote) {
        if (currentSession == null || remote == null) return;

        currentSession.expectedPallets = remote.expectedPallets;
        currentSession.lastUpdate = remote.lastUpdate;

        // Merge taskIds.
        if (remote.taskIds != null) {
            currentSession.taskIds.addAll(remote.taskIds);
        }

        // Merge pallets.
        Map<String, Pallet> localMap = new HashMap<>();
        for (Pallet p : currentSession.pallets) {
            if (p.palletCode != null) localMap.put(p.palletCode, p);
        }

        boolean changed = false;
        for (Pallet remotePallet : remote.pallets) {
            if (remotePallet.palletCode == null) continue;

            Pallet local = localMap.get(remotePallet.palletCode);
            if (local == null) {
                currentSession.pallets.add(remotePallet);
                changed = true;

                if (listener != null) {
                    String fromDevice = findDeviceName(remote, remotePallet);
                    mainHandler.post(() ->
                            listener.onRemotePalletAdded(remotePallet, fromDevice));
                }
            } else if (remotePallet.timestamp > local.timestamp) {
                currentSession.pallets.remove(local);
                currentSession.pallets.add(remotePallet);
                changed = true;
            }
        }

        currentSession.scannedPallets = currentSession.pallets.size();
        currentSession.activeDevices = remote.activeDevices;

        if (changed) {
            guardarSesionLocal();
        }
    }

    private String findDeviceName(ContainerSession session, Pallet pallet) {
        if (session.activeDevices != null) {
            for (DeviceInfo d : session.activeDevices.values()) {
                if (!d.deviceId.equals(deviceId)) {
                    return d.deviceName;
                }
            }
        }
        return "Otro\u00A0escáner";
    }

    // ====================== HEARTBEAT ======================

    private void iniciarHeartbeat() {
        detenerHeartbeat();
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                updateDeviceStatus();
                mainHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        };
        mainHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
    }

    private void detenerHeartbeat() {
        if (heartbeatRunnable != null) {
            mainHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }

    public void updateDeviceStatus() {
        if (currentSession == null) return;

        DeviceInfo me = currentSession.activeDevices.get(deviceId);
        if (me != null) {
            me.heartbeat();
        }

        guardarSesionLocal();

        if (syncEnabled && sessionRef != null && me != null) {
            sessionRef.child("devices").child(deviceId)
                    .setValue(deviceToMap(me));
        }
    }

    // ====================== CONEXIÓN TEST ======================

    public void testConnection(final ConnectionTestCallback callback) {
        try {
            DatabaseReference testRef = getDatabase().child("connection_test").child(deviceId);

            Map<String, Object> testData = new HashMap<>();
            testData.put("timestamp", ServerValue.TIMESTAMP);
            testData.put("device", deviceName);

            testRef.setValue(testData)
                    .addOnSuccessListener(v -> {
                        Log.d(TAG, "Test\u00A0de\u00A0conexión\u00A0exitoso");
                        testRef.removeValue();
                        if (callback != null) {
                            mainHandler.post(() -> callback.onResult(true, "Conexión exitosa"));
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Test\u00A0de\u00A0conexión\u00A0fallido", e);
                        if (callback != null) {
                            mainHandler.post(() -> callback.onResult(false, e.getMessage()));
                        }
                    });
        } catch (Exception e) {
            if (callback != null) {
                callback.onResult(false, e.getMessage());
            }
        }
    }

    public interface ConnectionTestCallback {
        void onResult(boolean success, String message);
    }

    public int getActiveDeviceCount() {
        if (currentSession == null || currentSession.activeDevices == null) return 0;
        int count = 0;
        for (DeviceInfo d : currentSession.activeDevices.values()) {
            if (!d.isStale()) count++;
        }
        return count;
    }

    // ====================== RECONEXIÓN ======================

    private void reconectarSesion() {
        if (currentSession == null) return;

        // Si hay containerCode, usar la ruta de containers.
        String code = currentSession.containerCode;
        if (code != null && !code.isEmpty()) {
            sessionRef = getDatabase().child("containers").child(sanitizeKey(code));
        } else {
            sessionRef = getDatabase().child("sessions").child(currentSession.sessionId);
        }

        sessionRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    iniciarEscucha();
                    iniciarHeartbeat();
                    Log.d(TAG, "Reconectado\u00A0a\u00A0container");
                } else {
                    Map<String, Object> sessionMap = sessionToMap(currentSession);
                    sessionRef.setValue(sessionMap);
                    iniciarEscucha();
                    iniciarHeartbeat();
                    Log.d(TAG, "Container\u00A0recreado\u00A0en\u00A0Firebase");
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Error\u00A0reconectando", error.toException());
            }
        });
    }

    // ====================== PERSISTENCIA LOCAL ======================

    private void guardarSesionLocal() {
        if (currentSession == null) return;
        try {
            prefs.edit()
                    .putString(KEY_CURRENT_SESSION, currentSession.toJson().toString())
                    .apply();
        } catch (Exception ignored) { }
    }

    public void clearSession() {
        if (syncEnabled && sessionRef != null) {
            sessionRef.child("devices").child(deviceId).child("isOnline")
                    .setValue(false);
        }

        detenerEscucha();
        currentSession = null;
        sessionRef = null;
        prefs.edit().remove(KEY_CURRENT_SESSION).apply();
    }

    // ====================== HELPERS ======================

    /**
     * Sanitiza un string para usarlo como key en Firebase
     * (no permite '.', '#', '$', '[', ']').
     */
    private String sanitizeKey(String key) {
        if (key == null) return "unknown";
        return key.replaceAll("[.#$\\[\\]/]", "_");
    }

    // ====================== CONVERTERS (Java → Firebase Map) ======================

    private Map<String, Object> sessionToMap(ContainerSession s) {
        Map<String, Object> map = new HashMap<>();
        map.put("sessionId", s.sessionId);
        map.put("containerCode", s.containerCode != null ? s.containerCode : "");
        map.put("taskId", s.taskId != null ? s.taskId : "");
        map.put("expectedPallets", s.expectedPallets);
        map.put("scannedPallets", s.scannedPallets);
        map.put("lastUpdate", ServerValue.TIMESTAMP);
        map.put("createdAt", s.createdAt);

        // Tasks list.
        List<String> tasksList = new ArrayList<>(s.taskIds);
        map.put("tasks", tasksList);

        // Pallets.
        Map<String, Object> palletsMap = new HashMap<>();
        for (Pallet p : s.pallets) {
            String key = (p.palletCode != null && !p.palletCode.isEmpty())
                    ? sanitizeKey(p.palletCode) : "pallet_" + p.timestamp;
            palletsMap.put(key, palletToMap(p));
        }
        map.put("pallets", palletsMap);

        // Devices.
        Map<String, Object> devicesMap = new HashMap<>();
        for (Map.Entry<String, DeviceInfo> entry : s.activeDevices.entrySet()) {
            devicesMap.put(entry.getKey(), deviceToMap(entry.getValue()));
        }
        map.put("devices", devicesMap);

        return map;
    }

    private Map<String, Object> palletToMap(Pallet p) {
        Map<String, Object> map = new HashMap<>();
        map.put("taskId", p.taskId != null ? p.taskId : "");
        map.put("palletCode", p.palletCode != null ? p.palletCode : "");
        map.put("sku", p.sku != null ? p.sku : "");
        map.put("quantity", p.quantity);
        map.put("location", p.location != null ? p.location : "");
        map.put("timestamp", p.timestamp);
        return map;
    }

    private Map<String, Object> deviceToMap(DeviceInfo d) {
        Map<String, Object> map = new HashMap<>();
        map.put("deviceId", d.deviceId);
        map.put("deviceName", d.deviceName);
        map.put("palletsScannedByDevice", d.palletsScannedByDevice);
        map.put("lastSeen", ServerValue.TIMESTAMP);
        map.put("isOnline", d.isOnline);
        return map;
    }

    // ====================== PARSERS (Firebase → Java) ======================

    private ContainerSession parseSnapshot(DataSnapshot snapshot) {
        ContainerSession s = new ContainerSession();
        s.sessionId = snapshot.child("sessionId").getValue(String.class);
        s.containerCode = snapshot.child("containerCode").getValue(String.class);
        s.taskId = snapshot.child("taskId").getValue(String.class);

        Long exp = snapshot.child("expectedPallets").getValue(Long.class);
        s.expectedPallets = exp != null ? exp.intValue() : 0;

        Long scanned = snapshot.child("scannedPallets").getValue(Long.class);
        s.scannedPallets = scanned != null ? scanned.intValue() : 0;

        Long lu = snapshot.child("lastUpdate").getValue(Long.class);
        s.lastUpdate = lu != null ? lu : 0L;

        Long ca = snapshot.child("createdAt").getValue(Long.class);
        s.createdAt = ca != null ? ca : 0L;

        // Tasks list.
        s.taskIds = new HashSet<>();
        DataSnapshot tasksSnap = snapshot.child("tasks");
        for (DataSnapshot ts : tasksSnap.getChildren()) {
            String tid = ts.getValue(String.class);
            if (tid != null && !tid.isEmpty()) {
                s.taskIds.add(tid);
            }
        }
        if (s.taskId != null && !s.taskId.isEmpty()) {
            s.taskIds.add(s.taskId);
        }

        // Pallets.
        s.pallets = new ArrayList<>();
        DataSnapshot palletsSnap = snapshot.child("pallets");
        for (DataSnapshot ps : palletsSnap.getChildren()) {
            Pallet p = new Pallet();
            p.taskId = ps.child("taskId").getValue(String.class);
            p.palletCode = ps.child("palletCode").getValue(String.class);
            p.sku = ps.child("sku").getValue(String.class);
            Double qty = ps.child("quantity").getValue(Double.class);
            p.quantity = qty != null ? qty : 0.0;
            p.location = ps.child("location").getValue(String.class);
            Long ts2 = ps.child("timestamp").getValue(Long.class);
            p.timestamp = ts2 != null ? ts2 : 0L;
            s.pallets.add(p);
        }

        // Devices.
        s.activeDevices = new HashMap<>();
        DataSnapshot devSnap = snapshot.child("devices");
        for (DataSnapshot ds : devSnap.getChildren()) {
            DeviceInfo d = new DeviceInfo();
            d.deviceId = ds.child("deviceId").getValue(String.class);
            d.deviceName = ds.child("deviceName").getValue(String.class);
            Long pCount = ds.child("palletsScannedByDevice").getValue(Long.class);
            d.palletsScannedByDevice = pCount != null ? pCount.intValue() : 0;
            Long ls = ds.child("lastSeen").getValue(Long.class);
            d.lastSeen = ls != null ? ls : 0L;
            Boolean online = ds.child("isOnline").getValue(Boolean.class);
            d.isOnline = online != null ? online : false;
            s.activeDevices.put(ds.getKey(), d);
        }

        return s;
    }
}
