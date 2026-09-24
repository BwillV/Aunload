# 🔗 Guía de Sincronización Multi-Dispositivo — PalletTracker v2.1

## Resumen

PalletTracker v2.1 incluye arquitectura preparada para sincronizar múltiples escáneres Honeywell trabajando en el mismo container. Esto permite que varios trabajadores escaneen pallets simultáneamente y todos vean el progreso en tiempo real.

---

## Opciones de Sincronización

### 1. 🔥 Firebase Realtime Database (RECOMENDADO)

**Pros:**
- ✅ Setup rápido (< 30 min)
- ✅ Sincronización en tiempo real (~100ms latencia)
- ✅ Funciona con WiFi del warehouse
- ✅ Plan gratuito suficiente para uso típico (100 conexiones simultáneas)
- ✅ Sin servidor propio que mantener
- ✅ SDK oficial de Android

**Contras:**
- ❌ Requiere cuenta Google
- ❌ Dependencia de servicio externo
- ❌ Requiere internet (no funciona sin WiFi)

**Costo estimado:**
- **Gratis** (Spark Plan): 100 conexiones, 1GB almacenamiento, 10GB/mes transferencia
- **$25/mes** (Blaze Plan): Pay-as-you-go para warehouses grandes

**Pasos para configurar:**
1. Ve a [console.firebase.google.com](https://console.firebase.google.com)
2. Crea un proyecto nuevo (ej: "PalletTracker-Warehouse")
3. En "Build" → "Realtime Database" → "Create Database"
4. Selecciona región más cercana (us-central1 para USA)
5. Inicia en "Test mode" (luego configurar reglas de seguridad)
6. Descarga `google-services.json` desde Project Settings
7. Copia el archivo a `/app/google-services.json`
8. Descomenta las dependencias en `app/build.gradle`
9. Descomenta `apply plugin: 'com.google.gms.google-services'`
10. En la app: Ajustes → Sincronización → Pega la URL de tu DB

**Reglas de seguridad recomendadas:**
```json
{
  "rules": {
    "sessions": {
      "$sessionId": {
        ".read": true,
        ".write": true
      }
    }
  }
}
```

---

### 2. 📄 Firebase Firestore

**Pros:**
- ✅ Queries más avanzadas
- ✅ Mejor escalabilidad
- ✅ Soporte offline nativo (sincroniza al reconectar)
- ✅ Estructura de datos más flexible

**Contras:**
- ❌ Latencia ligeramente mayor (~200-500ms)
- ❌ Más complejo de configurar
- ❌ Costos pueden crecer con muchas lecturas

**Costo estimado:**
- **Gratis**: 50K lecturas/día, 20K escrituras/día
- **~$5-10/mes** para warehouse típico

**Cuándo elegirlo:**
- Si necesitas búsquedas complejas (ej: "todos los pallets del SKU X")
- Si los dispositivos pierden WiFi frecuentemente (modo offline)

---

### 3. 🖥 Servidor REST API Propio

**Pros:**
- ✅ Control total de los datos
- ✅ Sin dependencia de terceros
- ✅ Personalizable al 100%
- ✅ Integrable con sistemas existentes (SAP, Oracle WMS)

**Contras:**
- ❌ Requiere servidor dedicado
- ❌ Desarrollo y mantenimiento del backend
- ❌ Necesita SSL/seguridad propia
- ❌ Tiempo de setup: 1-2 semanas

**Costo estimado:**
- **$5-20/mes** VPS (DigitalOcean, Linode, AWS Lightsail)
- **Tiempo de desarrollo**: 20-40 horas

**Stack recomendado:**
- Node.js + Express + WebSocket (para tiempo real)
- PostgreSQL o MongoDB
- Nginx como reverse proxy

---

### 4. 📡 Servidor Local en Red del Warehouse

**Pros:**
- ✅ Sin internet necesario
- ✅ Latencia ultra-baja (LAN)
- ✅ Datos nunca salen del warehouse
- ✅ Funciona 100% offline
- ✅ Sin costos de hosting

**Contras:**
- ❌ Requiere PC/Raspberry Pi en el warehouse
- ❌ Solo funciona dentro de la red WiFi local
- ❌ Mantenimiento manual del servidor
- ❌ Sin acceso remoto (a menos que configures VPN)

**Costo estimado:**
- **~$50-100** una vez (Raspberry Pi 4 + microSD)
- **$0/mes** operación

**Setup recomendado:**
- Raspberry Pi 4 con Node.js
- Conectar a la misma red WiFi que los Honeywell
- IP estática (ej: 192.168.1.100)
- mDNS para descubrimiento automático

---

## Comparativa Rápida

| Criterio | Firebase RT | Firestore | REST API | Local |
|---|---|---|---|---|
| **Setup** | 30 min | 1 hora | 1-2 sem | 2-3 horas |
| **Costo/mes** | $0-25 | $0-10 | $5-20 | $0 |
| **Latencia** | ~100ms | ~200-500ms | ~100-300ms | ~10ms |
| **Offline** | ❌ | ✅ | ❌ | ✅ (LAN) |
| **Internet** | Sí | Sí | Sí | No |
| **Escalable** | ✅✅ | ✅✅✅ | ✅✅ | ✅ |
| **Mantenimiento** | Bajo | Bajo | Alto | Medio |

---

## Arquitectura Actual

```
┌─────────────────────────────────┐
│       PalletTracker v2.1        │
│                                 │
│  ┌───────────┐  ┌────────────┐  │
│  │  PalletRepo│──│ FirebaseMgr│  │
│  │ (Local)   │  │ (Sync)     │  │
│  └───────────┘  └─────┬──────┘  │
│                       │         │
│       DUAL WRITE      │         │
│  ┌────────────────────┘         │
│  │                              │
│  ▼                              │
│  SharedPreferences (siempre)    │
│  Firebase (si habilitado)       │
└─────────────────────────────────┘
```

El `FirebaseManager` es un singleton que:
- Se inicializa desactivado (funciona offline)
- Se activa desde Ajustes → Sincronización
- Maneja sesiones de container
- Reporta pallets escaneados
- Escucha actualizaciones de otros dispositivos
- Envía heartbeat para detectar dispositivos activos

---

## Próximos Pasos

1. **Elegir opción** de sincronización
2. **Configurar backend** según la opción elegida
3. **Descomentar dependencias** en `build.gradle` (si Firebase)
4. **Implementar los TODO** en `FirebaseManager.java`
5. **Probar** con 2+ dispositivos en la misma sesión
