package com.chisadin.hudwz.viewmodel

import android.app.Application
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chisadin.hudwz.HudApplication
import com.chisadin.hudwz.bluetooth.BluetoothDeviceScanner
import com.chisadin.hudwz.domain.BluetoothDeviceInfo
import com.chisadin.hudwz.domain.HudElementConfig
import com.chisadin.hudwz.domain.HudProfile
import com.chisadin.hudwz.domain.HudProfileOrientationMode
import com.chisadin.hudwz.domain.HudSettings
import com.chisadin.hudwz.domain.TransportType
import com.chisadin.hudwz.domain.HudWidgetType
import com.chisadin.hudwz.domain.HudLayerMove
import com.chisadin.hudwz.domain.defaultHudElement
import com.chisadin.hudwz.service.HudBluetoothService
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class HudViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as HudApplication).container
    private val settingsRepository = container.settingsRepository
    private val profileRepository = container.profileRepository
    private val hudRepository = container.hudRepository
    private val scanner = BluetoothDeviceScanner(
        application,
        application.getSystemService(BluetoothManager::class.java).adapter,
    )

    val settings = settingsRepository.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), HudSettings(),
    )
    val profiles = profileRepository.profiles.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        listOf(HudProfile.defaultProfile(), HudProfile.minimalProfile(), HudProfile.largeSpeedProfile()),
    )
    val activeProfile = profileRepository.activeProfile.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), HudProfile.defaultProfile(),
    )
    val hudState = hudRepository.hudState
    val connection = hudRepository.connection
    val metrics = hudRepository.metrics
    val events = hudRepository.events
    val rawPackets = hudRepository.rawPackets
    val parsedPacket = hudRepository.parsedPacket

    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private var scanJob: Job? = null
    private val elementUpdates = MutableSharedFlow<Triple<String, HudElementConfig, Boolean>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val scaleUpdates = MutableSharedFlow<Pair<String, Float>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @OptIn(FlowPreview::class)
    private fun observeEditorUpdates() {
        viewModelScope.launch {
            elementUpdates.debounce(100).collect { (profileId, element, isPortrait) ->
                profileRepository.updateElement(profileId, element, isPortrait)
            }
        }
        viewModelScope.launch {
            scaleUpdates.debounce(100).collect { (profileId, scale) ->
                profileRepository.updateScale(profileId, scale)
            }
        }
    }

    init {
        observeEditorUpdates()
        viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            if (currentSettings.autoReconnect) {
                runCatching { HudBluetoothService.restore(application) }
            }
        }
    }

    fun refreshPairedDevices() {
        _devices.value = mergeDevices(scanner.pairedDevices(), _devices.value)
    }

    fun scanBle() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _scanning.value = true
            refreshPairedDevices()
            runCatching {
                scanner.scanBle().collect { discovered ->
                    _devices.value = mergeDevices(_devices.value, discovered)
                }
            }.onFailure { hudRepository.log("Bluetooth", it.message ?: "Quét BLE thất bại") }
            _scanning.value = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    fun connect(device: BluetoothDeviceInfo) {
        stopScan()
        HudBluetoothService.connect(getApplication(), device)
    }

    fun listen(type: TransportType) {
        stopScan()
        updateSettings {
            it.copy(
                isReceiverMode = true,
                preferredDeviceAddress = null,
                preferredDeviceName = null,
                preferredTransport = type,
            )
        }
        HudBluetoothService.listen(getApplication(), type)
    }

    fun listenWifi() {
        stopScan()
        updateSettings {
            it.copy(
                isReceiverMode = true,
                preferredDeviceAddress = null,
                preferredDeviceName = null,
                preferredTransport = TransportType.WIFI_WEBSOCKET,
            )
        }
        HudBluetoothService.listenWifi(getApplication())
    }

    fun disconnect() = HudBluetoothService.disconnect(getApplication())

    fun forgetSavedDevice() = updateSettings {
        it.copy(
            preferredDeviceAddress = null,
            preferredDeviceName = null,
            isReceiverMode = true,
        )
    }

    fun updateSettings(transform: (HudSettings) -> HudSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun selectProfile(id: String) {
        viewModelScope.launch { profileRepository.select(id) }
    }

    fun createProfile(name: String) {
        viewModelScope.launch { profileRepository.create(name) }
    }

    fun duplicateProfile(id: String) {
        viewModelScope.launch { profileRepository.duplicate(id) }
    }

    fun renameProfile(id: String, name: String) {
        viewModelScope.launch { profileRepository.rename(id, name) }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch { profileRepository.delete(id) }
    }

    fun updateElement(profileId: String, element: HudElementConfig, isPortrait: Boolean = false) {
        elementUpdates.tryEmit(Triple(profileId, element, isPortrait))
    }

    fun updateProfileScale(profileId: String, scale: Float) {
        scaleUpdates.tryEmit(profileId to scale)
    }

    fun updateProfileOrientationMode(profileId: String, mode: HudProfileOrientationMode) {
        viewModelScope.launch { profileRepository.updateOrientationMode(profileId, mode) }
    }

    fun addElement(profileId: String, type: HudWidgetType, x: Float, y: Float, isPortrait: Boolean = false): HudElementConfig {
        val id = "${type.name.lowercase()}-${UUID.randomUUID()}"
        val element = defaultHudElement(type, id, x, y)
        viewModelScope.launch {
            profileRepository.addElement(profileId, element, isPortrait)
        }
        return element
    }

    fun removeElement(profileId: String, elementId: String, isPortrait: Boolean = false) {
        viewModelScope.launch { profileRepository.removeElement(profileId, elementId, isPortrait) }
    }

    fun moveElement(profileId: String, elementId: String, move: HudLayerMove, isPortrait: Boolean = false) {
        viewModelScope.launch { profileRepository.moveElement(profileId, elementId, move, isPortrait) }
    }

    fun acceptDebugPacket(packet: String) {
        val framed = if (packet.endsWith('\n')) packet else "$packet\n"
        hudRepository.accept(framed.encodeToByteArray())
    }

    fun diagnosticsText(): String = buildString {
        appendLine("Kết nối: ${connection.value.phase}")
        appendLine("Thiết bị: ${connection.value.device?.name ?: "Không có"}")
        appendLine("Kiểu kết nối: ${connection.value.transport ?: "Không có"}")
        appendLine("MTU: ${metrics.value.mtu ?: "Không có"}")
        appendLine("Gói tin: ${metrics.value.packetCount}")
        appendLine("Tốc độ nhận: ${"%.1f".format(metrics.value.packetRate)}/giây")
        appendLine("Lỗi phân tích: ${metrics.value.parserErrors}")
        events.value.takeLast(100).forEach { appendLine("${it.elapsedMs} ${it.category}: ${it.message}") }
    }

    private fun mergeDevices(
        first: List<BluetoothDeviceInfo>,
        second: List<BluetoothDeviceInfo>,
    ): List<BluetoothDeviceInfo> = (first + second)
        .associateBy { "${it.transport}:${it.address}" }
        .values
        .sortedWith(compareByDescending<BluetoothDeviceInfo> { it.bonded }.thenBy { it.name.lowercase() })
}
