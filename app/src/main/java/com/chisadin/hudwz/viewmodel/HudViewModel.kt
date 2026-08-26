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
    private val elementUpdates = MutableSharedFlow<Pair<String, HudElementConfig>>(
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
            elementUpdates.debounce(100).collect { (profileId, element) ->
                profileRepository.updateElement(profileId, element)
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
            }.onFailure { hudRepository.log("Bluetooth", it.message ?: "BLE scan failed") }
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
        HudBluetoothService.listen(getApplication(), type)
    }

    fun disconnect() = HudBluetoothService.disconnect(getApplication())

    fun forgetSavedDevice() = updateSettings {
        it.copy(preferredDeviceAddress = null, preferredDeviceName = null)
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

    fun updateElement(profileId: String, element: HudElementConfig) {
        elementUpdates.tryEmit(profileId to element)
    }

    fun updateProfileScale(profileId: String, scale: Float) {
        scaleUpdates.tryEmit(profileId to scale)
    }

    fun addElement(profileId: String, type: HudWidgetType, x: Float, y: Float): HudElementConfig {
        val id = "${type.name.lowercase()}-${UUID.randomUUID()}"
        val element = defaultHudElement(type, id, x, y)
        viewModelScope.launch {
            profileRepository.addElement(profileId, element)
        }
        return element
    }

    fun removeElement(profileId: String, elementId: String) {
        viewModelScope.launch { profileRepository.removeElement(profileId, elementId) }
    }

    fun moveElement(profileId: String, elementId: String, move: HudLayerMove) {
        viewModelScope.launch { profileRepository.moveElement(profileId, elementId, move) }
    }

    fun acceptDebugPacket(packet: String) {
        val framed = if (packet.endsWith('\n')) packet else "$packet\n"
        hudRepository.accept(framed.encodeToByteArray())
    }

    fun diagnosticsText(): String = buildString {
        appendLine("Connection: ${connection.value.phase}")
        appendLine("Device: ${connection.value.device?.name ?: "None"}")
        appendLine("Transport: ${connection.value.transport ?: "None"}")
        appendLine("MTU: ${metrics.value.mtu ?: "N/A"}")
        appendLine("Packets: ${metrics.value.packetCount}")
        appendLine("Rate: ${"%.1f".format(metrics.value.packetRate)}/s")
        appendLine("Parser errors: ${metrics.value.parserErrors}")
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
