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
        when (intent?.action) {
            ACTION_DISCONNECT -> disconnectByUser()
            ACTION_LISTEN -> {
                val type = intent.getStringExtra(EXTRA_TRANSPORT).toTransport()
                startListening(type)
            }
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return START_STICKY
                val device = BluetoothDeviceInfo(
                    address = address,
                    name = intent.getStringExtra(EXTRA_NAME) ?: "HUD source",
                    transport = intent.getStringExtra(EXTRA_TRANSPORT).toTransport(),
                    bonded = intent.getBooleanExtra(EXTRA_BONDED, false),
                )
                startConnection(device, listen = false)
            }
            else -> {
                startForeground(NOTIFICATION_ID, notification("Restoring HUD connection"))
                scope.launch { restoreConnectionIfEnabled() }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        connectionJob?.cancel()
        scope.launch { transport?.disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startConnection(device: BluetoothDeviceInfo, listen: Boolean) {
        manuallyStopped = false
        receiverMode = listen
        val status = if (listen) "Waiting for Waze Mod via ${device.transport}" else "Connecting to ${device.name}"
        startForeground(NOTIFICATION_ID, notification(status))
        connectionJob?.cancel()
        connectionJob = scope.launch { connectionLoop(device) }
    }

    private fun startListening(type: TransportType) {
        val actualType = type.takeUnless { it == TransportType.AUTO } ?: TransportType.BLE
        startConnection(
            BluetoothDeviceInfo("", "Waze Mod", actualType, bonded = false),
            listen = true,
        )
    }

    private suspend fun restoreConnectionIfEnabled() {
        val settings = settingsRepository.settings.first()
        if (!settings.autoReconnect) return stopSelf()
        val address = settings.preferredDeviceAddress
        if (address == null && settings.preferredTransport != TransportType.AUTO) {
            startListening(settings.preferredTransport)
        } else if (address != null) {
            startConnection(
                BluetoothDeviceInfo(
                    address = address,
                    name = settings.preferredDeviceName ?: "HUD source",
                    transport = settings.preferredTransport.takeUnless { it == TransportType.AUTO } ?: TransportType.BLE,
                    bonded = false,
                ),
                listen = false,
            )
        } else stopSelf()
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
                repository.setConnection(ConnectionState(ConnectionPhase.ERROR, device, actualType, "Bluetooth permission required"))
                stopForegroundCompat(removeNotification = true)
                stopSelf()
                return
            }
            if (adapter?.isEnabled != true) {
                repository.setConnection(ConnectionState(ConnectionPhase.ERROR, device, actualType, "Bluetooth is off"))
                stopForegroundCompat(removeNotification = true)
                stopSelf()
                return
            }
            repository.setConnection(
                ConnectionState(
                    phase = if (attempt == 0) ConnectionPhase.CONNECTING else ConnectionPhase.RECONNECTING,
                    device = device,
                    transport = actualType,
                    message = if (receiverMode) "Waiting for Waze Mod" else if (attempt == 0) "Connecting" else "Reconnect attempt $attempt",
                    retryAttempt = attempt,
                ),
            )
            updateNotification(
                if (receiverMode) "Waiting for Waze Mod via $actualType"
                else if (attempt == 0) "Connecting to ${device.name}" else "Reconnecting to ${device.name}",
            )
            val activeTransport = createTransport(actualType, receiverMode)
            transport = activeTransport
            val sessionStartedAt = SystemClock.elapsedRealtime()
            try {
                runConnectedSession(activeTransport, device, settings.connectionTimeoutSeconds * 1_000L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                repository.log("Bluetooth", error.message ?: "Connection failed")
            } finally {
                runCatching { activeTransport.disconnect() }
                if (transport === activeTransport) transport = null
            }
            if (SystemClock.elapsedRealtime() - sessionStartedAt >= 30_000) attempt = 0
            if (manuallyStopped || !settings.autoReconnect) break
            attempt++
            val base = min(48_000L, 1_500L shl min(attempt - 1, 5))
            val delayMs = (base * Random.nextDouble(.8, 1.2)).toLong()
            repository.setConnection(
                ConnectionState(ConnectionPhase.RECONNECTING, device, actualType, "Retrying in ${delayMs / 1_000.0}s", attempt),
            )
            delay(delayMs)
        }
        repository.setConnection(ConnectionState(ConnectionPhase.IDLE, message = "Disconnected"))
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
            repository.setConnection(ConnectionState(ConnectionPhase.CONNECTED, device, activeTransport.type, "Connected"))
            updateNotification("Connected to ${device.name}")
            settingsRepository.update { current ->
                if (receiverMode) current.copy(
                    preferredDeviceAddress = null,
                    preferredDeviceName = null,
                    preferredTransport = activeTransport.type,
                ) else current.copy(
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
                    is TransportStatus.Disconnected -> throw IllegalStateException(terminal.reason ?: "Disconnected")
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
                        repository.setConnection(ConnectionState(ConnectionPhase.CONNECTED, device, activeTransport.type, "Signal stale: ${stale}ms"))
                    }
                    if (stale != null && stale > timeoutMillis) {
                        throw IllegalStateException("No HLP data for ${stale}ms")
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
        val bluetoothAdapter = adapter ?: throw IllegalStateException("Bluetooth is unavailable")
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
        repository.setConnection(ConnectionState(ConnectionPhase.DISCONNECTING, message = "Disconnecting"))
        connectionJob?.cancel()
        connectionJob = null
        scope.launch {
            transport?.disconnect()
            transport = null
            repository.setConnection(ConnectionState(ConnectionPhase.IDLE, message = "Disconnected"))
            stopForegroundCompat(removeNotification = true)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HUD Bluetooth", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the HUD Bluetooth connection active"
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Waze HUD")
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Disconnect", disconnect)
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
        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TRANSPORT = "transport"
        private const val EXTRA_BONDED = "bonded"

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
