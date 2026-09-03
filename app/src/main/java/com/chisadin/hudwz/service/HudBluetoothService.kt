package com.chisadin.hudwz.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.chisadin.hudwz.HudApplication
import com.chisadin.hudwz.MainActivity
import com.chisadin.hudwz.R
import com.chisadin.hudwz.bluetooth.BleTransport
import com.chisadin.hudwz.bluetooth.BleServerTransport
import com.chisadin.hudwz.bluetooth.BluetoothPermissionPolicy
import com.chisadin.hudwz.bluetooth.BluetoothTransport
import com.chisadin.hudwz.bluetooth.ClassicTransport
import com.chisadin.hudwz.bluetooth.ClassicServerTransport
import com.chisadin.hudwz.bluetooth.GattProfile
import com.chisadin.hudwz.bluetooth.TransportStatus
import com.chisadin.hudwz.bluetooth.WifiWebSocketTransport
import com.chisadin.hudwz.domain.BluetoothDeviceInfo
import com.chisadin.hudwz.domain.ConnectionPhase
import com.chisadin.hudwz.domain.ConnectionState
import com.chisadin.hudwz.domain.ReceiverSource
import com.chisadin.hudwz.domain.TransportType
import com.chisadin.hudwz.protocol.HlpProtocol
import com.chisadin.hudwz.protocol.vietmap.VietMapH1ReceiverSession
import com.chisadin.hudwz.protocol.vietmap.VietMapH50Decoder
import com.chisadin.hudwz.protocol.vietmap.VietMapH50ReceiverSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.math.min
import kotlin.random.Random

class HudBluetoothService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val protocol = HlpProtocol()
    private val repository by lazy { (application as HudApplication).container.hudRepository }
    private val settingsRepository by lazy { (application as HudApplication).container.settingsRepository }
    private val adapter by lazy {
        (getSystemService(BluetoothManager::class.java) as BluetoothManager).adapter
    }

    private var connectionJob: Job? = null
    private var transport: BluetoothTransport? = null
    private var manuallyStopped = false
    private var receiverMode = false
    private var lastStartKey: String? = null
    private var lastStartAtElapsedMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnectByUser()
                START_NOT_STICKY
            }
            ACTION_LISTEN -> {
                val type = intent.getStringExtra(EXTRA_TRANSPORT).toTransport()
                val actualType = type.takeUnless { it == TransportType.AUTO } ?: TransportType.BLE
                if (!BluetoothPermissionPolicy.has(this, BluetoothPermissionPolicy.receiverPermissions(actualType))) {
                    rejectStart(actualType, "Cần cấp quyền Bluetooth trước khi bật bộ nhận")
                    return START_NOT_STICKY
                }
                if (startListening(type)) START_STICKY else START_NOT_STICKY
            }
            ACTION_LISTEN_VIETMAP -> {
                val actualType = intent.getStringExtra(EXTRA_TRANSPORT).toTransport().let {
                    if (it == TransportType.AUTO) TransportType.BLE else it
                }
                if (!BluetoothPermissionPolicy.has(this, BluetoothPermissionPolicy.receiverPermissions(actualType))) {
                    rejectStart(actualType, "Cần cấp quyền Bluetooth trước khi bật bộ nhận VietMap")
                    return START_NOT_STICKY
                }
                if (startVietMapListening(actualType)) START_STICKY else START_NOT_STICKY
            }
            ACTION_WIFI_LISTEN -> {
                if (startWifiListening()) START_STICKY else START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                if (!BluetoothPermissionPolicy.has(this, BluetoothPermissionPolicy.connectionPermissions())) {
                    rejectStart(intent.getStringExtra(EXTRA_TRANSPORT).toTransport(), "Cần cấp quyền Bluetooth trước khi kết nối")
                    return START_NOT_STICKY
                }
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_NOT_STICKY
                val device = BluetoothDeviceInfo(
                    address = address,
                    name = intent.getStringExtra(EXTRA_NAME) ?: "Nguồn HUD",
                    transport = intent.getStringExtra(EXTRA_TRANSPORT).toTransport(),
                    bonded = intent.getBooleanExtra(EXTRA_BONDED, false),
                )
                if (startConnection(device, listen = false)) START_STICKY else START_NOT_STICKY
            }
            ACTION_RESTORE, null -> {
                if (!BluetoothPermissionPolicy.canStartConnectedDeviceService(this)) {
                    rejectStart(null, "Cần cấp quyền Bluetooth trước khi tự động kết nối lại")
                    return START_NOT_STICKY
                }
                if (!promoteToForeground("Đang khôi phục kết nối HUD")) return START_NOT_STICKY
                scope.launch { restoreConnectionIfEnabled() }
                START_STICKY
            }
            else -> {
                if (!BluetoothPermissionPolicy.canStartConnectedDeviceService(this)) {
                    rejectStart(null, "Cần cấp quyền Bluetooth trước khi tự động kết nối lại")
                    return START_NOT_STICKY
                }
                if (!promoteToForeground("Đang khôi phục kết nối HUD")) return START_NOT_STICKY
                scope.launch { restoreConnectionIfEnabled() }
                START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connectionJob?.cancel()
        (application as? HudApplication)?.container?.gpsSpeedTracker?.stop()
        runCatching {
            transport?.let { t ->
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(1200) { t.disconnect() }
                }
            }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatching {
            transport?.let { t ->
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(1200) { t.disconnect() }
                }
            }
        }
    }

    private fun startConnection(device: BluetoothDeviceInfo, listen: Boolean): Boolean {
        val startKey = "$listen:${device.transport}:${device.address}:${device.name}"
        val now = SystemClock.elapsedRealtime()
        if (startKey == lastStartKey && now - lastStartAtElapsedMs < 750L && connectionJob?.isActive == true) {
            repository.log("Bluetooth", "Bỏ qua yêu cầu khởi động trùng lặp trong ${now - lastStartAtElapsedMs}ms")
            return true
        }
        lastStartKey = startKey
        lastStartAtElapsedMs = now
        manuallyStopped = false
        receiverMode = listen
        val status = if (listen) "Đang chờ Waze Mod qua ${device.transport}" else "Đang kết nối tới ${device.name}"
        if (!promoteToForeground(status)) return false
        // Opening a new BluetoothGattServer before the cancelled session has closed leaves a
        // stale serverIf registered in some Android stacks. The remote can then discover the dead
        // duplicate service and callbacks are dispatched to a framework object whose callback is
        // already null. Always serialize teardown before registering the replacement server.
        val previousJob = connectionJob
        previousJob?.cancel()
        (application as? HudApplication)?.container?.gpsSpeedTracker?.start()
        connectionJob = scope.launch {
            previousJob?.join()
            connectionLoop(device)
        }
        return true
    }

    private fun promoteToForeground(text: String): Boolean = runCatching {
        val hasLocation = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && hasLocation) {
                serviceType = serviceType or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, notification(text), serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification(text))
        }
        true
    }.getOrElse { error ->
        repository.setConnection(
            ConnectionState(
                phase = ConnectionPhase.ERROR,
                message = error.message ?: "Không thể khởi động dịch vụ Bluetooth nền",
            ),
        )
        stopSelf()
        false
    }

    private fun rejectStart(type: TransportType?, message: String) {
        repository.setConnection(
            ConnectionState(
                phase = ConnectionPhase.ERROR,
                transport = type,
                message = message,
            ),
        )
        stopSelf()
    }

    private fun startListening(type: TransportType): Boolean {
        val actualType = type.takeUnless { it == TransportType.AUTO } ?: TransportType.BLE
        val started = startConnection(
            BluetoothDeviceInfo("", "Waze Mod", actualType, bonded = false),
            listen = true,
        )
        if (!started) return false
        scope.launch {
            settingsRepository.update { current ->
                current.copy(
                    isReceiverMode = true,
                    preferredTransport = actualType,
                    preferredDeviceAddress = null,
                    preferredDeviceName = null,
                )
            }
        }
        return true
    }

    private fun startWifiListening(): Boolean {
        val started = startConnection(
            BluetoothDeviceInfo("", "Waze Mod (Wi-Fi)", TransportType.WIFI_WEBSOCKET, bonded = false),
            listen = true,
        )
        if (!started) return false
        scope.launch {
            settingsRepository.update { current ->
                current.copy(
                    isReceiverMode = true,
                    preferredTransport = TransportType.WIFI_WEBSOCKET,
                    preferredDeviceAddress = null,
                    preferredDeviceName = null,
                )
            }
        }
        return true
    }

    private fun startVietMapListening(transportType: TransportType = TransportType.BLE): Boolean {
        receiverMode = true
        val transportLabel = if (transportType == TransportType.BLE) "BLE (GATT Service FFFF - HUD H50)" else "Bluetooth Classic (SPP)"
        repository.log("VietMap", "Bắt đầu chế độ chờ kết nối từ VietMap Live (HUD H50) qua $transportLabel...")
        val started = startConnection(
            BluetoothDeviceInfo("", "VietMap Live", transportType, bonded = false),
            listen = true,
        )
        if (!started) return false
        scope.launch {
            settingsRepository.update { current ->
                current.copy(
                    isReceiverMode = true,
                    receiverSource = ReceiverSource.VIETMAP_LIVE,
                    preferredTransport = transportType,
                    preferredDeviceAddress = null,
                    preferredDeviceName = null,
                )
            }
        }
        return true
    }

    private suspend fun restoreConnectionIfEnabled() {
        val settings = settingsRepository.settings.first()
        if (!settings.autoReconnect) return stopSelf()
        val address = settings.preferredDeviceAddress
        val isReceiver = settings.isReceiverMode || address.isNullOrBlank()
        if (isReceiver) {
            if (settings.receiverSource == ReceiverSource.VIETMAP_LIVE) {
                startVietMapListening(settings.preferredTransport.takeUnless { it == TransportType.AUTO } ?: TransportType.CLASSIC)
            } else {
                val transport = settings.preferredTransport.takeUnless { it == TransportType.AUTO } ?: TransportType.BLE
                if (transport == TransportType.WIFI_WEBSOCKET) {
                    startWifiListening()
                } else {
                    startListening(transport)
                }
            }
        } else {
            startConnection(
                BluetoothDeviceInfo(
                    address = address,
                    name = settings.preferredDeviceName ?: "Nguồn HUD",
                    transport = settings.preferredTransport.takeUnless { it == TransportType.AUTO } ?: TransportType.BLE,
                    bonded = false,
                ),
                listen = false,
            )
        }
    }

    private suspend fun connectionLoop(device: BluetoothDeviceInfo) {
        var attempt = 0
        while (!manuallyStopped) {
            val settings = settingsRepository.settings.first()
            repository.setCaptureRawPackets(settings.showRawPackets)
            val actualType = device.transport.takeUnless { it == TransportType.AUTO }
                ?: settings.preferredTransport.takeUnless { it == TransportType.AUTO }
                ?: TransportType.BLE

            if (actualType != TransportType.WIFI_WEBSOCKET) {
                val requiredPermissions = if (receiverMode) {
                    BluetoothPermissionPolicy.receiverPermissions(actualType)
                } else BluetoothPermissionPolicy.connectionPermissions()
                if (!BluetoothPermissionPolicy.has(this, requiredPermissions)) {
                    repository.setConnection(ConnectionState(ConnectionPhase.ERROR, device, actualType, "Cần cấp quyền Bluetooth"))
                    stopForegroundCompat(removeNotification = true)
                    stopSelf()
                    return
                }
                if (adapter?.isEnabled != true) {
                    repository.setConnection(ConnectionState(ConnectionPhase.ERROR, device, actualType, "Bluetooth đang tắt"))
                    stopForegroundCompat(removeNotification = true)
                    stopSelf()
                    return
                }
            }
            val isVietMap = receiverMode && (settings.receiverSource == ReceiverSource.VIETMAP_LIVE || device.name.contains("VietMap", ignoreCase = true))
            val targetName = if (isVietMap) "VietMap Live" else if (receiverMode) "Waze Mod" else device.name
            repository.setConnection(
                ConnectionState(
                    phase = if (attempt == 0) ConnectionPhase.CONNECTING else ConnectionPhase.RECONNECTING,
                    device = device,
                    transport = actualType,
                    message = if (receiverMode) "Đang chờ $targetName" else if (attempt == 0) "Đang kết nối" else "Lần kết nối lại thứ $attempt",
                    retryAttempt = attempt,
                ),
            )
            updateNotification(
                if (receiverMode) "Đang chờ $targetName qua $actualType"
                else if (attempt == 0) "Đang kết nối tới ${device.name}" else "Đang kết nối lại tới ${device.name}",
            )
            val activeTransport = createTransport(actualType, receiverMode, settings, isVietMap)
            transport = activeTransport
            val sessionStartedAt = SystemClock.elapsedRealtime()
            try {
                runConnectedSession(activeTransport, device, settings.connectionTimeoutSeconds * 1_000L, isVietMap)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                repository.log("Bluetooth", error.message ?: "Kết nối thất bại")
            } finally {
                runCatching { activeTransport.disconnect() }
                if (transport === activeTransport) transport = null
            }
            if (SystemClock.elapsedRealtime() - sessionStartedAt >= 30_000) attempt = 0
            if (manuallyStopped || !settings.autoReconnect) break
            attempt++
            val base = if (receiverMode) 1_500L else min(48_000L, 1_500L shl min(attempt - 1, 5))
            val delayMs = if (receiverMode) 1_500L else (base * Random.nextDouble(.8, 1.2)).toLong()
            repository.setConnection(
                ConnectionState(ConnectionPhase.RECONNECTING, device, actualType, "Thử lại sau ${delayMs / 1_000.0} giây", attempt),
            )
            delay(delayMs)
        }
        repository.setConnection(ConnectionState(ConnectionPhase.IDLE, message = "Đã ngắt kết nối"))
        stopForegroundCompat(removeNotification = true)
        stopSelf()
    }

    private suspend fun runConnectedSession(
        activeTransport: BluetoothTransport,
        device: BluetoothDeviceInfo,
        timeoutMillis: Long,
        isVietMap: Boolean = false,
    ) = coroutineScope {
        val vietMapSession = if (isVietMap) VietMapH50ReceiverSession(repository) else null
        vietMapSession?.onConnected()

        val incoming = launch {
            activeTransport.incoming.collect { bytes ->
                if (vietMapSession != null) {
                    val hex = VietMapH50Decoder.bytesToHex(bytes)
                    repository.log("VietMap", "H50 RX raw (${bytes.size}B): $hex")
                    val replies = vietMapSession.feed(bytes)
                    replies.forEach { reply ->
                        val replyHex = VietMapH50Decoder.bytesToHex(reply)
                        repository.log("VietMap", "H50 TX reply (${reply.size}B): $replyHex")
                        runCatching { activeTransport.write(reply) }
                    }
                } else {
                    repository.accept(bytes).forEach { reply -> activeTransport.write(reply) }
                }
            }
        }
        val metricUpdates = launch {
            activeTransport.status.collect { status ->
                if (status is TransportStatus.Connected && status.mtu != null) {
                    repository.updateTransportMetrics { it.copy(mtu = status.mtu) }
                }
            }
        }
        try {
            activeTransport.connect(device, timeoutMillis)
            val connectedStatus = activeTransport.status.value as? TransportStatus.Connected
            connectedStatus?.mtu?.let { mtu -> repository.updateTransportMetrics { it.copy(mtu = mtu) } }
            val clientName = if (isVietMap) "VietMap Live (H50)" else device.name.ifBlank { "Waze Mod" }
            repository.setConnection(ConnectionState(ConnectionPhase.CONNECTED, device, activeTransport.type, "Đã kết nối ($clientName)"))
            updateNotification("Đã kết nối tới $clientName")
            settingsRepository.update { current ->
                if (receiverMode) current.copy(
                    isReceiverMode = true,
                    receiverSource = if (isVietMap) ReceiverSource.VIETMAP_LIVE else ReceiverSource.WAZE_MOD,
                    preferredDeviceAddress = null,
                    preferredDeviceName = null,
                    preferredTransport = activeTransport.type,
                ) else current.copy(
                    isReceiverMode = false,
                    preferredDeviceAddress = device.address,
                    preferredDeviceName = device.name,
                    preferredTransport = activeTransport.type,
                )
            }
            if (!isVietMap) {
                activeTransport.write(protocol.deviceDeclaration(activeTransport.type))
            } else {
                repository.log("VietMap", "Phiên HUD H50 BLE đã sẵn sàng, chờ truy vấn từ VietMap Live")
            }

            val disconnected = async {
                when (val terminal = activeTransport.status.filter {
                    it is TransportStatus.Disconnected || it is TransportStatus.Failed
                }.first()) {
                    is TransportStatus.Disconnected -> throw IllegalStateException(terminal.reason ?: "Đã ngắt kết nối")
                    is TransportStatus.Failed -> throw IllegalStateException(terminal.reason)
                    else -> Unit
                }
            }
            val monitor = async {
                while (true) {
                    delay(5_000)
                    if (!isVietMap) {
                        activeTransport.write(protocol.ping(SystemClock.elapsedRealtime()))
                    }
                    val stale = repository.staleForMs()
                    if (stale != null && stale > 3_000) {
                        repository.setConnection(ConnectionState(ConnectionPhase.CONNECTED, device, activeTransport.type, "Tín hiệu đã cũ: ${stale}ms"))
                    }
                    val effectiveTimeout = if (receiverMode) Long.MAX_VALUE else (timeoutMillis * 4).coerceAtLeast(60_000L)
                    if (stale != null && stale > effectiveTimeout) {
                        throw IllegalStateException("Không có dữ liệu trong ${stale}ms")
                    }
                }
            }
            select<Unit> {
                disconnected.onAwait { }
                monitor.onAwait { }
            }
        } finally {
            vietMapSession?.onDisconnected()
            incoming.cancel()
            metricUpdates.cancel()
        }
    }

    private fun createTransport(
        type: TransportType,
        listen: Boolean,
        settings: com.chisadin.hudwz.domain.HudSettings? = null,
        isVietMap: Boolean = false,
    ): BluetoothTransport {
        if (type == TransportType.WIFI_WEBSOCKET) {
            return WifiWebSocketTransport(
                port = settings?.wsPort ?: 8765,
                path = settings?.wsPath ?: "/hlp",
            )
        }
        val bluetoothAdapter = adapter ?: throw IllegalStateException("Bluetooth không khả dụng")
        if (listen) {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val profile = if (isVietMap) GattProfile.VIETMAP_H50 else GattProfile.HLP
            val sppServiceName = if (isVietMap) "VIETMAP_HUD_H50" else "HLP SPP"
            return when (type) {
                TransportType.CLASSIC -> ClassicServerTransport(bluetoothAdapter, sppServiceName, repository::log)
                TransportType.WIFI_WEBSOCKET -> WifiWebSocketTransport()
                TransportType.BLE, TransportType.AUTO -> BleServerTransport(this, bluetoothManager, bluetoothAdapter, profile, repository::log)
            }
        }
        return when (type) {
            TransportType.CLASSIC -> ClassicTransport(bluetoothAdapter)
            TransportType.WIFI_WEBSOCKET -> WifiWebSocketTransport()
            TransportType.BLE, TransportType.AUTO -> BleTransport(this, bluetoothAdapter)
        }
    }

    private fun disconnectByUser() {
        manuallyStopped = true
        (application as? HudApplication)?.container?.gpsSpeedTracker?.stop()
        repository.setConnection(ConnectionState(ConnectionPhase.DISCONNECTING, message = "Đang ngắt kết nối"))
        connectionJob?.cancel()
        connectionJob = null
        scope.launch {
            transport?.disconnect()
            transport = null
            repository.setConnection(ConnectionState(ConnectionPhase.IDLE, message = "Đã ngắt kết nối"))
            stopForegroundCompat(removeNotification = true)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Bluetooth HUD", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Duy trì kết nối Bluetooth cho HUD"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun notification(text: String): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnect = PendingIntent.getService(
            this,
            1,
            Intent(this, HudBluetoothService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Waze HUD")
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Ngắt kết nối", disconnect)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun stopForegroundCompat(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    private fun String?.toTransport(): TransportType =
        runCatching { TransportType.valueOf(this ?: "AUTO") }.getOrDefault(TransportType.AUTO)

    companion object {
        private const val CHANNEL_ID = "hud_bluetooth"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_CONNECT = "com.chisadin.hudwz.CONNECT"
        private const val ACTION_LISTEN = "com.chisadin.hudwz.LISTEN"
        private const val ACTION_LISTEN_VIETMAP = "com.chisadin.hudwz.LISTEN_VIETMAP"
        private const val ACTION_WIFI_LISTEN = "com.chisadin.hudwz.WIFI_LISTEN"
        private const val ACTION_DISCONNECT = "com.chisadin.hudwz.DISCONNECT"
        private const val ACTION_RESTORE = "com.chisadin.hudwz.RESTORE"
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_BONDED = "bonded"

        fun restore(context: Context) {
            if (!BluetoothPermissionPolicy.canStartConnectedDeviceService(context)) return
            val intent = Intent(context, HudBluetoothService::class.java).setAction(ACTION_RESTORE)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun connect(context: Context, device: BluetoothDeviceInfo) {
            val intent = Intent(context, HudBluetoothService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_ADDRESS, device.address)
                .putExtra(EXTRA_NAME, device.name)
                .putExtra(EXTRA_TRANSPORT, device.transport.name)
                .putExtra(EXTRA_BONDED, device.bonded)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun listen(context: Context, type: TransportType) {
            val intent = Intent(context, HudBluetoothService::class.java)
                .setAction(ACTION_LISTEN)
                .putExtra(EXTRA_TRANSPORT, type.name)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun listenVietMap(context: Context, transportType: TransportType = TransportType.BLE) {
            val intent = Intent(context, HudBluetoothService::class.java)
                .setAction(ACTION_LISTEN_VIETMAP)
                .putExtra(EXTRA_TRANSPORT, transportType.name)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun listenWifi(context: Context) {
            val intent = Intent(context, HudBluetoothService::class.java)
                .setAction(ACTION_WIFI_LISTEN)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, HudBluetoothService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
