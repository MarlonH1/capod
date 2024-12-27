package eu.darken.capod.monitor.core

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.provider.MediaStore.Audio
import eu.darken.capod.common.bluetooth.BleScanResult
import eu.darken.capod.common.bluetooth.BleScanner
import eu.darken.capod.common.bluetooth.BluetoothManager2
import eu.darken.capod.common.bluetooth.ScannerMode
import eu.darken.capod.common.coroutine.AppScope
import eu.darken.capod.common.debug.DebugSettings
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.Logging.Priority.WARN
import eu.darken.capod.common.debug.logging.asLog
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.common.flow.replayingShare
import eu.darken.capod.common.flow.setupCommonEventHandlers
import eu.darken.capod.common.flow.throttleLatest
import eu.darken.capod.main.core.GeneralSettings
import eu.darken.capod.main.core.PermissionTool
import eu.darken.capod.pods.core.HasEarDetection
import eu.darken.capod.pods.core.PodDevice
import eu.darken.capod.pods.core.PodFactory
import eu.darken.capod.pods.core.apple.airpods.AirPodsMax
import eu.darken.capod.pods.core.apple.protocol.ProximityPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("PrivateApi")
@Singleton
class PodMonitor @Inject constructor(
    @AppScope private val appScope: CoroutineScope,
    private val bleScanner: BleScanner,
    private val podFactory: PodFactory,
    private val generalSettings: GeneralSettings,
    private val bluetoothManager: BluetoothManager2,
    private val debugSettings: DebugSettings,
    private val podDeviceCache: PodDeviceCache,
    private val permissionTool: PermissionTool,
    private val audioManager: AudioManager,
    ) {

    private val deviceCache = mutableMapOf<PodDevice.Id, PodDevice>()
    private val cacheLock = Mutex()
    private val getAudioProductStrategiesMethod: Method by lazy {
        HiddenApiBypass
            .getDeclaredMethod(
                Class.forName(
                "android.media.audiopolicy.AudioProductStrategy"),
                    "getAudioProductStrategies"
            )
    }
    private val setPreferredDeviceMethod: Method by lazy {
        HiddenApiBypass.getDeclaredMethod(
            AudioManager::class.java,
            "setPreferredDeviceForStrategy",
            Class.forName("android.media.audiopolicy.AudioProductStrategy"),
            Class.forName("android.media.AudioDeviceAttributes")
        )
    }
    private val getPreferredDeviceMethod: Method by lazy {
        HiddenApiBypass.getDeclaredMethod(
            AudioManager::class.java,
            "getPreferredDeviceForStrategy",
            Class.forName("android.media.audiopolicy.AudioProductStrategy"),
        )
    }


    private fun mediaStrategy(): Any {
       return (getAudioProductStrategiesMethod.invoke(audioManager) as List<*>)[5]!!
    }

    private fun getAudioProductAttributes(switchDevicesArgument: SwitchDevicesArgument): Any {
        val type = when(switchDevicesArgument) {
            SwitchDevicesArgument.SPEAKER -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            SwitchDevicesArgument.HEADPHONE -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
        val devices = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .first { it.type == type }

        return HiddenApiBypass.newInstance(Class.forName("android.media.AudioDeviceAttributes"), devices)
    }

    private fun setPreferredDevice(audioStrategy: Any, audioProductAttributes: Any) {
        setPreferredDeviceMethod.invoke(audioManager, audioStrategy, audioProductAttributes)
    }

    val devices: Flow<List<PodDevice>> = combine(
        permissionTool.missingPermissions,
        bluetoothManager.isBluetoothEnabled
    ) { missingPermissions, isBluetoothEnabled ->
        log(TAG) { "devices: missingPermissions=$missingPermissions, isBluetoothEnabled=$isBluetoothEnabled" }
        // We just want to retrigger if permissions change.
        isBluetoothEnabled
    }
        .flatMapLatest { isReady ->
            if (!isReady) {
                log(TAG, WARN) { "Bluetooth is not ready" }
                flowOf(null)
            } else {
                createBleScanner()
            }
        }
        .map { newPods ->
            val pods = processWithCache(newPods)

            val presorted = sortPodsToInterest(pods.values)
            val main = determineMainDevice(presorted)
            newPods?.firstOrNull { it.device.identifier == main?.identifier }?.let {
                podDeviceCache.saveMainDevice(it.scanResult)
            }

            presorted.sortedByDescending { it == main }
        }
        .retryWhen { cause, attempt ->
            log(TAG, WARN) { "PodMonitor failed (attempt=$attempt), will retry: ${cause.asLog()}" }
            delay(3000)
            true
        }
        .onStart { emit(emptyList()) }
        .replayingShare(appScope)

    val mainDevice: Flow<PodDevice?> = devices
        .map { determineMainDevice(it) }
        .setupCommonEventHandlers(TAG) { "mainDevice" }
        .replayingShare(appScope)

    private data class ScannerOptions(
        val scannerMode: ScannerMode,
        val showUnfiltered: Boolean,
        val offloadedFilteringDisabled: Boolean,
        val offloadedBatchingDisabled: Boolean,
        val disableDirectCallback: Boolean,
    )

    private fun createBleScanner() = combine(
        generalSettings.scannerMode.flow,
        debugSettings.showUnfiltered.flow,
        generalSettings.isOffloadedBatchingDisabled.flow,
        generalSettings.isOffloadedFilteringDisabled.flow,
        generalSettings.useIndirectScanResultCallback.flow,
    ) {
            scannermode,
            showUnfiltered,
            isOffloadedBatchingDisabled,
            isOffloadedFilteringDisabled,
            useIndirectScanResultCallback,
        ->
        ScannerOptions(
            scannerMode = scannermode,
            showUnfiltered = showUnfiltered,
            offloadedFilteringDisabled = isOffloadedFilteringDisabled,
            offloadedBatchingDisabled = isOffloadedBatchingDisabled,
            disableDirectCallback = useIndirectScanResultCallback,
        )
    }
        .throttleLatest(1000)
        .flatMapLatest { options ->
            val filters = when {
                options.showUnfiltered -> {
                    log(TAG, WARN) { "Using unfiltered scan mode" }
                    setOf(ScanFilter.Builder().build())
                }
                else -> ProximityPairing.getBleScanFilter()
            }

            bleScanner.scan(
                filters = filters,
                scannerMode = options.scannerMode,
                disableOffloadFiltering = options.offloadedFilteringDisabled,
                disableOffloadBatching = options.offloadedBatchingDisabled,
                disableDirectScanCallback = options.disableDirectCallback,
            ).map { preFilterAndMap(it) }
        }

    private suspend fun processWithCache(
        newPods: List<PodFactory.Result>?
    ): Map<PodDevice.Id, PodDevice> = cacheLock.withLock {
        if (newPods == null) {
            log(TAG) { "Null result, Bluetooth is disabled." }
            deviceCache.clear()
            return emptyMap()
        }

        val now = Instant.now()
        deviceCache.toList().forEach { (key, value) ->
            if (Duration.between(value.seenLastAt, now) > Duration.ofSeconds(20)) {
                log(TAG, VERBOSE) { "Removing stale device from cache: $value" }
                deviceCache.remove(key)
            }
        }

        val pods = mutableMapOf<PodDevice.Id, PodDevice>()

        pods.putAll(deviceCache)

        newPods.map { it.device }.forEach {
            deviceCache[it.identifier] = it
            pods[it.identifier] = it
        }
        return pods
    }

    private suspend fun preFilterAndMap(rawResults: Collection<BleScanResult>): List<PodFactory.Result> = rawResults
        .groupBy { it.address }
        .values
        .map { sameAdrDevs ->
            // For each address we only want the newest result, upstream may batch data
            val newest = sameAdrDevs.maxByOrNull { it.generatedAtNanos }!!
            sameAdrDevs.minus(newest).let {
                if (it.isNotEmpty()) log(TAG, VERBOSE) { "Discarding stale results: $it" }
            }
            newest
        }
        .mapNotNull { podFactory.createPod(it) }

    private fun sortPodsToInterest(pods: Collection<PodDevice>): List<PodDevice> {
        val now = Instant.now()

        return pods.sortedWith(
            compareByDescending<PodDevice> { true }
                .thenBy {
                    val age = Duration.between(it.seenLastAt, now).seconds
                    if (age < 5) 0L else (age / 3L)
                }
                .thenByDescending { it.signalQuality }
                .thenByDescending { (it.seenCounter / 10) }
        )
    }

    private fun determineMainDevice(pods: List<PodDevice>): PodDevice? {
        val mainDeviceModel = generalSettings.mainDeviceModel.value

        val presorted = sortPodsToInterest(pods).sortedByDescending {
            it.model == mainDeviceModel && it.model != PodDevice.Model.UNKNOWN
        }

        return presorted.firstOrNull()?.let { candidate ->
            when {
                candidate.model == PodDevice.Model.UNKNOWN -> null
                mainDeviceModel != PodDevice.Model.UNKNOWN && candidate.model != mainDeviceModel -> null
                candidate.signalQuality <= generalSettings.minimumSignalQuality.value -> null
                else -> candidate
            }
        }
    }

    suspend fun latestMainDevice(): PodDevice? {
        val currentMain = mainDevice.firstOrNull()
        log(TAG) { "Live mainDevice is $currentMain" }

        return currentMain ?: podDeviceCache.loadMainDevice()
            ?.let { podFactory.createPod(it)?.device }
            .also { log(TAG) { "Cached mainDevice is $it" } }
    }

    private fun isSpeakerPlaying(): Boolean {
        val currentAudioDeviceAttributes = getPreferredDeviceMethod.invoke(audioManager, mediaStrategy()) ?: return true
        val address = Class.forName(currentAudioDeviceAttributes::class.java.name).methods.first { it.name == "getAddress" }.invoke(currentAudioDeviceAttributes) as String

        return address.isBlank()
    }

   private enum class SwitchDevicesArgument {
        SPEAKER,
        HEADPHONE
    }

    private fun switchTo(switchDevicesArgument: SwitchDevicesArgument) {
        val mediaStrategy = mediaStrategy()
        val attributes = getAudioProductAttributes(switchDevicesArgument)
        setPreferredDevice(mediaStrategy, attributes)
    }

    private var ignoreAskToSwitchToHeadphone = false
    private var ignoreAskToSwitchToSpeaker = false
    suspend fun onPlaybackChanged() {
        val device = latestMainDevice() as? AirPodsMax ?: return

        if(device.isBeingWorn && isSpeakerPlaying() ) {
            switchTo(SwitchDevicesArgument.HEADPHONE)
            ignoreAskToSwitchToHeadphone = true
            ignoreAskToSwitchToSpeaker = false
        }  else if(!device.isBeingWorn && !isSpeakerPlaying()){
            switchTo(SwitchDevicesArgument.SPEAKER)
            ignoreAskToSwitchToSpeaker = true
            ignoreAskToSwitchToHeadphone = false
        }
    }

    companion object {
        private val TAG = logTag("Monitor", "PodMonitor")
    }
}
