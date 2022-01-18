package eu.darken.capod.reaction.popup

import eu.darken.capod.common.debug.logging.Logging.Priority.INFO
import eu.darken.capod.common.debug.logging.Logging.Priority.VERBOSE
import eu.darken.capod.common.debug.logging.log
import eu.darken.capod.common.debug.logging.logTag
import eu.darken.capod.common.flow.setupCommonEventHandlers
import eu.darken.capod.common.flow.withPrevious
import eu.darken.capod.monitor.core.PodMonitor
import eu.darken.capod.pods.core.PodDevice
import eu.darken.capod.pods.core.apple.DualApplePods
import eu.darken.capod.reaction.popup.ui.PopUpNotification
import eu.darken.capod.reaction.settings.ReactionSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PopUpReaction @Inject constructor(
    private val podMonitor: PodMonitor,
    private val reactionSettings: ReactionSettings,
    private val popUpNotification: PopUpNotification,
) {

    fun monitor(): Flow<Unit> = reactionSettings.showPopUpOnCaseOpen.flow
        .flatMapLatest { if (it) podMonitor.mainDevice else emptyFlow() }
        .withPrevious()
        .setupCommonEventHandlers(TAG) { "monitor" }
        .map { (previous, current) ->
            if (previous is DualApplePods? && current is DualApplePods) {
                log(TAG) {
                    val prev = previous?.rawCaseLidState?.let { String.format("%02X", it.toByte()) }
                    val cur = current.rawCaseLidState.let { String.format("%02X", it.toByte()) }
                    "previous=$prev (${previous?.caseLidState}), current=$cur (${current.caseLidState})"
                }
                log(TAG, VERBOSE) { "previous-id=${previous?.identifier}, current-id=${current.identifier}" }

                val isSameDeviceWithCaseNowOpen =
                    previous?.identifier == current.identifier && previous.caseLidState != current.caseLidState
                val isNewDeviceWithJustOpenedCase =
                    previous?.identifier != current.identifier && previous?.caseLidState != current.caseLidState

                if (isSameDeviceWithCaseNowOpen || isNewDeviceWithJustOpenedCase) {
                    log(TAG) { "Case lid status changed for monitored device." }

                    if (current.caseLidState == DualApplePods.LidState.OPEN && current.model != PodDevice.Model.UNKNOWN) {
                        log(TAG, INFO) { "Show popup" }
                        popUpNotification.showNotification(current)
                    } else if (current.caseLidState != DualApplePods.LidState.OPEN) {
                        log(TAG, INFO) { "Hide popup" }
                        popUpNotification.hideNotification()
                    }
                }
            }
        }

    companion object {
        private val TAG = logTag("Reaction", "PopUp")
    }
}