package com.demoaccesibilidad.capture;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
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

    // ═══ Configuración Firebase del proyecto "aunload-rescue" ═══
    // Se define de forma EXPLÍCITA (y no se depende de que el plugin de
    // google-services procese el JSON) porque en los escáner Honeywell/iData
    // SIN Google Play Services la auto-configuración puede no resolver la URL
    // de la Realtime Database y lanzar "Can't determine Firebase Database URL".
    // Con estos valores + setDatabaseUrl la base de datos funciona sin GMS.
    private static final String FB_DATABASE_URL =
            "https://aunload-rescue-default-rtdb.firebaseio.com";
    private static final String FB_APP_ID =
            "1:963708693750:android:pallettracker4310d7";
    private static final String FB_API_KEY =
            "AIzaSyBnAHU2lBl9TaWUb8LKbVx_gxepiE80ut8";
    private static final String FB_PROJECT_ID = "aunload-rescue";
    private static final String FB_SENDER_ID = "963708693750";
    private static final String FB_APP_NAME = "pallettracker";

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

    // ══════════════════════════════════════════════════════════════════════
    //  v4.9 — CÓDIGO DE EQUIPO PERSISTENTE + CONTENEDOR COMPARTIDO
    //
    //  El "código de equipo" NO es el código de un container: es el identificador
    //  del grupo de escáneres que trabajan juntos. Se guarda en KEY_CONTAINER_CODE
    //  (reutilizado) y NUNCA se borra al iniciar un nuevo container, reiniciar la
    //  app o cambiar de día. Solo cambia si el usuario escribe otro código o pulsa
    //  "Salir del equipo".
    //
    //  Estructura en Firebase:
    //    equipos/{codigoEquipo}/
    //        contenedorActual: { fecha, numero, id:"yyyy-MM-dd#N", iniciadoPor, timestamp }
    //        containers/{idSanitizado}/pallets/{ilpSanitizado}:
    //            { deviceId, deviceName, palletCode, sku, cantidad, ubicacion, timestamp }
    //
    //  El total mostrado (FAB/pantalla) = nº de pallets del container actual =
    //  suma de lo escaneado por TODOS los escáneres, en tiempo real vía listener.
    // ══════════════════════════════════════════════════════════════════════
    private DatabaseReference teamContenedorRef;
    private ValueEventListener teamContenedorListener;
    private DatabaseReference teamPalletsRef;
    private ValueEventListener teamPalletsListener;
    private String currentContainerId;       // "yyyy-MM-dd#N"
    private int currentContainerNumber = 1;
    private String currentContainerDate;     // "yyyy-MM-dd"
    private int teamPalletCount = 0;
    private int myTeamPalletCount = 0;   // v5.1 — pallets de ESTE dispositivo en el container del equipo

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
    // Guarda el ÚLTIMO error de inicialización COMPLETO para mostrarlo en la UI
    // (pantalla de Ajustes) y en el registro de errores (crash_log.txt).
    private String lastInitError = null;
    // FirebaseApp propia creada con opciones explícitas.
    private FirebaseApp firebaseApp = null;

    private void initFirebaseApp() {
        try {
            // Construir las opciones MANUALMENTE con la URL de la Realtime DB.
            // Esto evita depender de que el JSON se procese, cosa que puede
            // fallar en dispositivos sin Google Play Services.
            FirebaseOptions options = new FirebaseOptions.Builder()
                    .setApplicationId(FB_APP_ID)
                    .setApiKey(FB_API_KEY)
                    .setDatabaseUrl(FB_DATABASE_URL)
                    .setProjectId(FB_PROJECT_ID)
                    .setGcmSenderId(FB_SENDER_ID)
                    .build();

            // Reutilizar la instancia si ya existe (evita IllegalStateException).
            boolean exists = false;
            for (FirebaseApp app : FirebaseApp.getApps(appContext)) {
                if (FB_APP_NAME.equals(app.getName())) {
                    firebaseApp = app;
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                firebaseApp = FirebaseApp.initializeApp(appContext, options, FB_APP_NAME);
            }

            firebaseAvailable = true;
            lastInitError = null;
            Log.d(TAG, "Firebase\u00A0inicializado\u00A0correctamente\u00A0(URL="
                    + FB_DATABASE_URL + ")");
        } catch (Throwable t) {
            // Throwable cubre Exception y Error/LinkageError (dispositivos sin GMS).
            firebaseAvailable = false;
            lastInitError = traceToString(t);
            // Dejar constancia COMPLETA en el registro de errores de la app.
            PalletTrackerApp.logException(appContext, "FirebaseManager.initFirebaseApp", t);
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

    /**
     * Devuelve el último error de inicialización (traza completa) o null si
     * Firebase se inicializó bien. Se usa para mostrar el detalle en Ajustes.
     */
    public String getLastInitError() {
        return lastInitError;
    }

    /**
     * Texto de estado legible para el indicador de conexión en Ajustes.
     */
    public String getConnectionStatus() {
        if (firebaseAvailable) {
            return syncEnabled ? "Listo\u00A0(sync\u00A0activa)" : "Listo\u00A0(sync\u00A0apagada)";
        }
        return "Error\u00A0al\u00A0iniciar\u00A0Firebase";
    }

    /** Convierte una excepción a su traza completa como texto. */
    private static String traceToString(Throwable t) {
        if (t == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    private DatabaseReference getDatabase() {
        // SIEMPRE con la URL explícita. Si por lo que sea firebaseApp quedó
        // null, caemos a la instancia por URL (también válida sin GMS).
        if (firebaseApp != null) {
            return FirebaseDatabase.getInstance(firebaseApp, FB_DATABASE_URL).getReference();
        }
        return FirebaseDatabase.getInstance(FB_DATABASE_URL).getReference();
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
                String detalle = lastInitError != null
                        ? ("\n\nDetalle:\n" + lastInitError) : "";
                listener.onSyncError("No se pudo iniciar Firebase en este dispositivo." + detalle);
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

        // v4.9 — Conectar/desconectar el contenedor compartido del equipo.
        if (enabled && firebaseAvailable && hasTeam()) {
            attachTeam(getTeamCode());
        } else if (!enabled) {
            detachTeam();
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

    /**
     * v4.6: Actualiza solo la UBICACIÓN de un pallet ya reportado, tanto en la
     * sesión local como en Firebase (sin volver a crear el pallet). Se usa
     * cuando la ubicación se completa después de guardar (escaneo, pantalla
     * "Progress"/"Location View" o edición manual).
     */
    public void updatePalletLocationRemote(String palletCode, String location) {
        if (palletCode == null || palletCode.isEmpty()
                || location == null || location.trim().isEmpty()) {
            return;
        }
        String loc = location.trim();

        // Reflejar en la sesión local en memoria.
        if (currentSession != null && currentSession.pallets != null) {
            for (Pallet ex : currentSession.pallets) {
                if (ex.palletCode != null && ex.palletCode.equals(palletCode)) {
                    ex.location = loc;
                    break;
                }
            }
            guardarSesionLocal();
        }

        if (syncEnabled && sessionRef != null) {
            try {
                String key = sanitizeKey(palletCode);
                sessionRef.child("pallets").child(key).child("location").setValue(loc)
                        .addOnSuccessListener(v ->
                                Log.d(TAG, "Ubicación\u00A0sincronizada:\u00A0" + palletCode
                                        + "\u00A0→\u00A0" + loc))
                        .addOnFailureListener(e ->
                                Log.e(TAG, "Error\u00A0sincronizando\u00A0ubicación", e));
            } catch (Exception e) {
                Log.w(TAG, "No\u00A0se\u00A0pudo\u00A0sincronizar\u00A0ubicación", e);
            }
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

    /**
     * Total de pallets escaneadas por TODOS los dispositivos en el container
     * compartido indicado. Si no hay sesión sincronizada para ese código,
     * devuelve -1 para que el llamador use el conteo local.
     */
    public int getTotalScannedForContainer(String containerCode) {
        if (!syncEnabled || currentSession == null) return -1;
        String cur = currentSession.containerCode;
        if (containerCode != null && cur != null && !cur.isEmpty()
                && !containerCode.equals(cur)) {
            // La sesión activa es de otro container.
            return -1;
        }
        // Preferir la suma por dispositivo; si no, el contador agregado.
        if (currentSession.activeDevices != null && !currentSession.activeDevices.isEmpty()) {
            int sum = 0;
            for (DeviceInfo d : currentSession.activeDevices.values()) {
                sum += Math.max(0, d.palletsScannedByDevice);
            }
            if (sum > 0) return sum;
        }
        return Math.max(0, currentSession.scannedPallets);
    }

    /**
     * v4.8 — Garantiza que, si la sincronización está activa y hay una sesión
     * guardada, estemos realmente ESCUCHANDO los cambios en Firebase. Si el
     * proceso se reinició y solo quedó vivo el servicio del botón flotante, el
     * singleton se recrea sin listener activo; aquí lo reconectamos para que el
     * total compartido se mantenga al día. No hace nada si ya está escuchando.
     */
    public void ensureListeningIfEnabled() {
        try {
            if (syncEnabled && firebaseAvailable
                    && currentSession != null && sessionListener == null) {
                reconectarSesion();
            }
            // v4.9 — Reconectar también el contenedor compartido del equipo si el
            // proceso se reinició y quedó vivo solo el servicio del botón flotante.
            if (syncEnabled && firebaseAvailable && hasTeam()
                    && teamContenedorListener == null) {
                attachTeam(getTeamCode());
            }
        } catch (Throwable ignored) { }
    }

    /**
     * v4.8 — Total de pallets del container COMPARTIDO actualmente activo en
     * Firebase, sumando lo de TODOS los dispositivos.
     *
     * A diferencia de getTotalScannedForContainer(code), NO compara contra un
     * código concreto. El botón flotante antes le pasaba el código LOCAL diario
     * ("yyyy-MM-dd#N"), que NUNCA coincide con el código compartido del
     * container en Firebase ("CNT-001", etc.), así que el total siempre volvía
     * a -1 y el botón solo mostraba el conteo propio. Este método resuelve eso:
     * mientras la sincronización esté activa y haya una sesión unida, devuelve
     * el total real de equipo.
     *
     * Devuelve -1 solo si no hay sync o no hay sesión (el llamador usa entonces
     * el conteo local).
     */
    public int getTotalScannedShared() {
        if (!syncEnabled || currentSession == null) return -1;

        // Preferir la suma por dispositivo (refleja lo de cada escáner).
        if (currentSession.activeDevices != null && !currentSession.activeDevices.isEmpty()) {
            int sum = 0;
            for (DeviceInfo d : currentSession.activeDevices.values()) {
                sum += Math.max(0, d.palletsScannedByDevice);
            }
            if (sum > 0) return sum;
        }
        // Respaldo: contador agregado de la sesión (= nº de pallets únicas).
        return Math.max(0, currentSession.scannedPallets);
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

    // ══════════════════════════════════════════════════════════════════════
    //  v4.9 — CÓDIGO DE EQUIPO PERSISTENTE + CONTENEDOR COMPARTIDO
    // ══════════════════════════════════════════════════════════════════════

    /** Código de equipo persistente (reutiliza KEY_CONTAINER_CODE). */
    public String getTeamCode() {
        return prefs.getString(KEY_CONTAINER_CODE, "");
    }

    /**
     * Guarda el código de equipo. NO borra pallets ni containers: el código es
     * del grupo, no del container. Solo se vacía si el usuario sale del equipo.
     */
    public void setTeamCode(String code) {
        prefs.edit().putString(KEY_CONTAINER_CODE, code != null ? code.trim() : "").apply();
    }

    /** True si hay un código de equipo configurado. */
    public boolean hasTeam() {
        String c = getTeamCode();
        return c != null && !c.trim().isEmpty();
    }

    /**
     * True si el equipo está realmente operativo: sync activa, Firebase
     * disponible, hay código de equipo y ya conocemos el container actual.
     */
    public boolean isTeamActive() {
        return syncEnabled && firebaseAvailable && hasTeam() && currentContainerId != null;
    }

    /**
     * Unirse a un equipo por código. Guarda el código de forma persistente y,
     * si la sync está activa, se conecta al contenedor compartido. NUNCA
     * resetea lo que ya había: adopta el container actual del equipo.
     */
    public void joinTeam(String code) {
        setTeamCode(code);
        if (syncEnabled && firebaseAvailable && hasTeam()) {
            attachTeam(getTeamCode());
        }
    }

    /**
     * Salir del equipo: desconecta listeners, limpia estado y borra el código.
     * Esta es la ÚNICA forma de borrar el código de equipo.
     */
    public void leaveTeam() {
        detachTeam();
        currentContainerId = null;
        currentContainerDate = null;
        currentContainerNumber = 1;
        teamPalletCount = 0;
        myTeamPalletCount = 0;
        setTeamCode("");
        broadcastFabUpdate();
    }

    /**
     * Conecta al contenedor compartido del equipo. Si el equipo no existe aún
     * en Firebase, crea el container #1 de hoy mediante una transacción
     * create-if-absent (no pisa lo que otro escáner haya creado primero).
     */
    private void attachTeam(final String code) {
        if (code == null || code.trim().isEmpty() || !firebaseAvailable) return;
        detachTeam();

        final String teamKey = sanitizeKey(code.trim());
        teamContenedorRef = getDatabase()
                .child("equipos").child(teamKey).child("contenedorActual");

        // Crear el container #1 SOLO si el equipo no existe todavía.
        teamContenedorRef.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData data) {
                if (data.getValue() == null) {
                    data.setValue(buildContainerMap(todayKey(), 1));
                }
                return Transaction.success(data);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot snap) {
                if (error != null) {
                    Log.e(TAG, "Error\u00A0creando\u00A0equipo:\u00A0" + error.getMessage());
                }
                // Tanto si lo creamos como si ya existía, empezamos a escuchar.
                startContenedorListener(teamKey);
            }
        });
        Log.d(TAG, "Conectado\u00A0al\u00A0equipo:\u00A0" + code);
    }

    /**
     * Escucha el contenedor actual del equipo. Cuando cambia (p. ej. otro
     * escáner inicia un nuevo container), adoptamos el nuevo id y reiniciamos
     * el conteo visible a cero en todos los dispositivos a la vez.
     */
    private void startContenedorListener(final String teamKey) {
        if (teamContenedorRef == null) return;
        if (teamContenedorListener != null) {
            teamContenedorRef.removeEventListener(teamContenedorListener);
        }
        teamContenedorListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    // El equipo quedó sin contenedor: crear el #1 de hoy.
                    teamContenedorRef.runTransaction(new Transaction.Handler() {
                        @Override
                        public Transaction.Result doTransaction(MutableData data) {
                            if (data.getValue() == null) {
                                data.setValue(buildContainerMap(todayKey(), 1));
                            }
                            return Transaction.success(data);
                        }
                        @Override
                        public void onComplete(DatabaseError e, boolean c, DataSnapshot s) { }
                    });
                    return;
                }

                String id = snapshot.child("id").getValue(String.class);
                Long numero = snapshot.child("numero").getValue(Long.class);
                String fecha = snapshot.child("fecha").getValue(String.class);

                boolean changed = (id != null && !id.equals(currentContainerId));
                currentContainerId = id;
                currentContainerNumber = numero != null ? numero.intValue() : 1;
                currentContainerDate = fecha;

                // (Re)enganchar el listener de pallets del container vigente.
                attachPalletsListener(teamKey, currentContainerId);

                if (changed) {
                    // Nuevo container → el conteo visible vuelve a cero en todos.
                    teamPalletCount = 0;
                    myTeamPalletCount = 0;
                    broadcastFabUpdate();
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Error\u00A0escuchando\u00A0contenedor\u00A0del\u00A0equipo:\u00A0"
                        + error.getMessage());
            }
        };
        teamContenedorRef.addValueEventListener(teamContenedorListener);
    }

    /**
     * Escucha los pallets del container actual del equipo. El total mostrado es
     * el nº de pallets (suma de todos los escáneres), en tiempo real.
     */
    private void attachPalletsListener(String teamKey, String containerId) {
        if (teamKey == null || containerId == null) return;

        if (teamPalletsRef != null && teamPalletsListener != null) {
            teamPalletsRef.removeEventListener(teamPalletsListener);
        }
        teamPalletsRef = getDatabase()
                .child("equipos").child(teamKey)
                .child("containers").child(sanitizeKey(containerId))
                .child("pallets");

        teamPalletsListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                teamPalletCount = (int) snapshot.getChildrenCount();
                // v5.1 — Contar también cuántos de esos pallets los escaneó
                // ESTE dispositivo, para el modo "solo este dispositivo" del FAB.
                int mine = 0;
                for (DataSnapshot ps : snapshot.getChildren()) {
                    Object dev = ps.child("deviceId").getValue();
                    if (dev != null && dev.toString().equals(deviceId)) {
                        mine++;
                    }
                }
                myTeamPalletCount = mine;
                broadcastFabUpdate();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Error\u00A0escuchando\u00A0pallets\u00A0del\u00A0equipo:\u00A0"
                        + error.getMessage());
            }
        };
        teamPalletsRef.addValueEventListener(teamPalletsListener);
    }

    /**
     * Inicia un NUEVO container compartido para el equipo. Mediante transacción:
     * si seguimos el mismo día, numero+1; si cambió el día, numero=1. Los pallets
     * anteriores NO se borran (quedan en su container en el historial). El
     * listener de contenedorActual hará que todos los escáneres pasen a 0.
     *
     * Esta misma acción se usa cuando el usuario "borra" los pallets: borrar =
     * dar por finalizado el container actual y abrir uno nuevo.
     */
    public void startNewContainerShared() {
        if (!syncEnabled || !firebaseAvailable || !hasTeam()) return;
        final String teamKey = sanitizeKey(getTeamCode().trim());
        DatabaseReference ref = getDatabase()
                .child("equipos").child(teamKey).child("contenedorActual");

        ref.runTransaction(new Transaction.Handler() {
            @Override
            public Transaction.Result doTransaction(MutableData data) {
                String hoy = todayKey();
                Object fechaObj = data.child("fecha").getValue();
                Long numObj = data.child("numero").getValue(Long.class);
                int nuevoNumero;
                if (fechaObj != null && hoy.equals(fechaObj.toString()) && numObj != null) {
                    nuevoNumero = numObj.intValue() + 1;
                } else {
                    nuevoNumero = 1;
                }
                data.setValue(buildContainerMap(hoy, nuevoNumero));
                return Transaction.success(data);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed, DataSnapshot snap) {
                if (error != null) {
                    Log.e(TAG, "Error\u00A0iniciando\u00A0nuevo\u00A0container:\u00A0"
                            + error.getMessage());
                } else {
                    Log.d(TAG, "Nuevo\u00A0container\u00A0compartido\u00A0iniciado");
                }
            }
        });
    }

    /**
     * Reporta un pallet escaneado al container compartido del equipo. Usa el ILP
     * (palletCode) como clave para evitar duplicados entre escáneres.
     */
    public void reportPalletToTeam(Pallet pallet) {
        if (!isTeamActive() || pallet == null) return;
        final String teamKey = sanitizeKey(getTeamCode().trim());
        String palletKey = (pallet.palletCode != null && !pallet.palletCode.isEmpty())
                ? sanitizeKey(pallet.palletCode)
                : "pallet_" + System.currentTimeMillis();

        Map<String, Object> map = new HashMap<>();
        map.put("deviceId", deviceId);
        map.put("deviceName", deviceName);
        map.put("palletCode", pallet.palletCode != null ? pallet.palletCode : "");
        map.put("sku", pallet.sku != null ? pallet.sku : "");
        map.put("cantidad", pallet.quantity);
        map.put("ubicacion", pallet.location != null ? pallet.location : "");
        map.put("timestamp", ServerValue.TIMESTAMP);

        getDatabase().child("equipos").child(teamKey)
                .child("containers").child(sanitizeKey(currentContainerId))
                .child("pallets").child(palletKey)
                .setValue(map)
                .addOnSuccessListener(v ->
                        Log.d(TAG, "Pallet\u00A0al\u00A0equipo:\u00A0" + pallet.palletCode))
                .addOnFailureListener(e ->
                        Log.e(TAG, "Error\u00A0reportando\u00A0pallet\u00A0al\u00A0equipo", e));
    }

    /** Actualiza la ubicación de un pallet ya reportado al equipo. */
    public void updateTeamPalletLocation(String palletCode, String location) {
        if (!isTeamActive() || palletCode == null || palletCode.isEmpty()
                || location == null || location.trim().isEmpty()) {
            return;
        }
        final String teamKey = sanitizeKey(getTeamCode().trim());
        getDatabase().child("equipos").child(teamKey)
                .child("containers").child(sanitizeKey(currentContainerId))
                .child("pallets").child(sanitizeKey(palletCode))
                .child("ubicacion").setValue(location.trim())
                .addOnFailureListener(e ->
                        Log.e(TAG, "Error\u00A0actualizando\u00A0ubicación\u00A0del\u00A0equipo", e));
    }

    /**
     * v5.0 — Borra un CONTAINER completo del equipo (y todos sus pallets) en
     * Firebase bajo equipos/{código}/containers/{id}. El cambio se propaga a
     * TODOS los escáneres del equipo por los listeners. Si el container borrado
     * es el que está activo (contenedorActual), el equipo avanza a un container
     * nuevo mediante la misma transacción de v4.9 (así nadie queda escaneando
     * sobre un container que ya no existe). NO toca el código de equipo.
     */
    public void deleteTeamContainer(String containerId) {
        if (!syncEnabled || !firebaseAvailable || !hasTeam()
                || containerId == null || containerId.isEmpty()) {
            return;
        }
        final String teamKey = sanitizeKey(getTeamCode().trim());
        getDatabase().child("equipos").child(teamKey)
                .child("containers").child(sanitizeKey(containerId))
                .removeValue()
                .addOnSuccessListener(v ->
                        Log.d(TAG, "Container\u00A0borrado\u00A0del\u00A0equipo:\u00A0" + containerId))
                .addOnFailureListener(e ->
                        Log.e(TAG, "Error\u00A0borrando\u00A0container\u00A0del\u00A0equipo", e));

        // Si borramos el container activo, el equipo entero pasa a uno nuevo.
        if (containerId.equals(currentContainerId)) {
            startNewContainerShared();
        }
    }

    /**
     * v5.0 — Borra UN pallet del container indicado del equipo en Firebase bajo
     * equipos/{código}/containers/{id}/pallets/{ilp}. El total compartido baja en
     * todos los escáneres en tiempo real. NO toca el código de equipo.
     */
    public void deleteTeamPallet(String containerId, String palletCode) {
        if (!syncEnabled || !firebaseAvailable || !hasTeam()
                || containerId == null || containerId.isEmpty()
                || palletCode == null || palletCode.isEmpty()) {
            return;
        }
        final String teamKey = sanitizeKey(getTeamCode().trim());
        getDatabase().child("equipos").child(teamKey)
                .child("containers").child(sanitizeKey(containerId))
                .child("pallets").child(sanitizeKey(palletCode))
                .removeValue()
                .addOnSuccessListener(v ->
                        Log.d(TAG, "Pallet\u00A0borrada\u00A0del\u00A0equipo:\u00A0" + palletCode))
                .addOnFailureListener(e ->
                        Log.e(TAG, "Error\u00A0borrando\u00A0pallet\u00A0del\u00A0equipo", e));
    }

    /** Nº de pallets del container actual del equipo (suma de escáneres). */
    public int getTeamPalletCount() {
        return Math.max(0, teamPalletCount);
    }

    /**
     * v5.1 — Nº de pallets del container actual del equipo escaneados por ESTE
     * dispositivo (modo "solo este dispositivo" del botón flotante).
     */
    public int getMyTeamPalletCount() {
        return Math.max(0, myTeamPalletCount);
    }

    /** Número del container compartido actual (1, 2, 3...). */
    public int getCurrentContainerNumberShared() {
        return currentContainerNumber;
    }

    /** Id del container compartido actual ("yyyy-MM-dd#N") o null. */
    public String getCurrentContainerId() {
        return currentContainerId;
    }

    /** Desconecta los listeners del equipo sin borrar el código. */
    private void detachTeam() {
        if (teamContenedorRef != null && teamContenedorListener != null) {
            teamContenedorRef.removeEventListener(teamContenedorListener);
        }
        if (teamPalletsRef != null && teamPalletsListener != null) {
            teamPalletsRef.removeEventListener(teamPalletsListener);
        }
        teamContenedorListener = null;
        teamPalletsListener = null;
        teamContenedorRef = null;
        teamPalletsRef = null;
    }

    /** Avisa al botón flotante (y a la UI) para que refresque el conteo. */
    private void broadcastFabUpdate() {
        try {
            Intent i = new Intent(FloatingButtonService.ACTION_UPDATE_FAB);
            i.setPackage(appContext.getPackageName());
            appContext.sendBroadcast(i);
        } catch (Throwable ignored) { }
    }

    /** Construye el mapa de contenedorActual. */
    private Map<String, Object> buildContainerMap(String fecha, int numero) {
        Map<String, Object> map = new HashMap<>();
        map.put("fecha", fecha);
        map.put("numero", numero);
        map.put("id", fecha + "#" + numero);
        map.put("iniciadoPor", deviceId);
        map.put("timestamp", ServerValue.TIMESTAMP);
        return map;
    }

    /** Fecha de hoy "yyyy-MM-dd" (Locale.US, igual que el repositorio local). */
    private static String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}
