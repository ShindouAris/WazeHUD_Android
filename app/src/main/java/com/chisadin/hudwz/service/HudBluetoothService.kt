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
import com.chisadin.hudwz.bluetooth.TransportStatus
import com.chisadin.hudwz.domain.BluetoothDeviceInfo
import com.chisadin.hudwz.domain.ConnectionPhase
import com.chisadin.hudwz.domain.ConnectionState
import com.chisadin.hudwz.domain.TransportType
import com.chisadin.hudwz.protocol.HlpProtocol
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
        manuallyStopped = false
        receiverMode = listen
        val status = if (listen) "Đang chờ Waze Mod qua ${device.transport}" else "Đang kết nối tới ${device.name}"
        if (!promoteToForeground(status)) return false
        connectionJob?.cancel()
        connectionJob = scope.launch { connectionLoop(device) }
        return true
    }

    private fun promoteToForeground(text: String): Boolean = runCatching {
        startForeground(NOTIFICATION_ID, notification(text))
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

    private suspend fun restoreConnectionIfEnabled() {
        val settings = settingsRepository.settings.first()
        if (!settings.autoReconnect) return stopSelf()
        val address = settings.preferredDeviceAddress
        val isReceiver = settings.isReceiverMode || address.isNullOrBlank()
        if (isReceiver) {
            val transport = settings.preferredTransport.takeUnless { it == TransportType.AUTO } ?: TransportType.BLE
            startListening(transport)
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
            repository.setConnection(
                ConnectionState(
                    phase = if (attempt == 0) ConnectionPhase.CONNECTING else ConnectionPhase.RECONNECTING,
                    device = device,
                    transport = actualType,
                    message = if (receiverMode) "Đang chờ Waze Mod" else if (attempt == 0) "Đang kết nối" else "Lần kết nối lại thứ $attempt",
                    retryAttempt = attempt,
                ),
            )
            updateNotification(
                if (receiverMode) "Đang chờ Waze Mod qua $actualType"
                else if (attempt == 0) "Đang kết nối tới ${device.name}" else "Đang kết nối lại tới ${device.name}",
            )
            val activeTransport = createTransport(actualType, receiverMode)
            transport = activeTransport
            val sessionStartedAt = SystemClock.elapsedRealtime()
            try {
                runConnectedSession(activeTransport, device, settings.connectionTimeoutSeconds * 1_000L)
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
    ) = coroutineScope {
        val incoming = launch {
            activeTransport.incoming.collect { bytes ->
                repository.accept(bytes).forEach { reply -> activeTransport.write(reply) }
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
            repository.setConnection(ConnectionState(ConnectionPhase.CONNECTED, device, activeTransport.type, "Đã kết nối"))
            updateNotification("Đã kết nối tới ${device.name}")
            settingsRepository.update { current ->
                if (receiverMode) current.copy(
                    isReceiverMode = true,
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
            activeTransport.write(protocol.deviceDeclaration(activeTransport.type))

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
                    activeTransport.write(protocol.ping(SystemClock.elapsedRealtime()))
                    val stale = repository.staleForMs()
                    if (stale != null && stale > 3_000) {
                        repository.setConnection(ConnectionState(ConnectionPhase.CONNECTED, device, activeTransport.type, "Tín hiệu đã cũ: ${stale}ms"))
                    }
                    val effectiveTimeout = if (receiverMode) Long.MAX_VALUE else (timeoutMillis * 4).coerceAtLeast(60_000L)
                    if (stale != null && stale > effectiveTimeout) {
                        throw IllegalStateException("Không có dữ liệu HLP trong ${stale}ms")
                    }
                }
            }
            select<Unit> {
                disconnected.onAwait { }
                monitor.onAwait { }
            }
        } finally {
            incoming.cancel()
            metricUpdates.cancel()
        }
    }

    private fun createTransport(type: TransportType, listen: Boolean): BluetoothTransport {
        val bluetoothAdapter = adapter ?: throw IllegalStateException("Bluetooth không khả dụng")
        if (listen) {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            return when (type) {
                TransportType.CLASSIC -> ClassicServerTransport(bluetoothAdapter)
                TransportType.BLE, TransportType.AUTO -> BleServerTransport(this, bluetoothManager, bluetoothAdapter)
            }
        }
        return when (type) {
            TransportType.CLASSIC -> ClassicTransport(bluetoothAdapter)
            TransportType.BLE, TransportType.AUTO -> BleTransport(this, bluetoothAdapter)
        }
    }

    private fun disconnectByUser() {
        manuallyStopped = true
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
            ContextCompat.startForegroundService(context, intent)
        }

        fun listen(context: Context, type: TransportType) {
            val intent = Intent(context, HudBluetoothService::class.java)
                .setAction(ACTION_LISTEN)
                .putExtra(EXTRA_TRANSPORT, type.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, HudBluetoothService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
