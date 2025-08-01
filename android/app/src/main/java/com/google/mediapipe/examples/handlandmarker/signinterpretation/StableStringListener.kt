package com.google.mediapipe.examples.handlandmarker.signinterpretation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class StableStringListener(
    private val confirmationThreshold: Int = 8,
    private val delayMs: Long = 1200,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) {
    private var currentString: String? = null
    private var count = 0

    private var countForZ1 = 0
    private var countForZ2 = 0
    private var waitingForZVariants = false

    private var lastDisplayed: String? = null

    private var canCount = true

    private val _confirmedString = MutableStateFlow<String?>(null)
    val confirmedString: StateFlow<String?> = _confirmedString

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var resetJob: Job? = null

    fun onNewString(newString: String?) {
        if (newString == null || newString == "UNKNOWN" || !canCount) return

        if (newString == "Z" && !waitingForZVariants) {
            waitingForZVariants = true
            currentString = newString
            count = -8
            countForZ1 = 0
            countForZ2 = 0
            return
        }

        if (waitingForZVariants) {
            when (newString) {
                "Ź" -> countForZ1++
                "Ż" -> countForZ2++
            }
        }

        if (newString == lastDisplayed) {
            scheduleReset()
            return
        }

        if (newString == currentString) {
            count++
        } else {
            currentString = newString
            count = 1
        }

        if (countForZ1 >= 8) {
            currentString = "Ź"
            count = 10
            waitingForZVariants = false
        } else if (countForZ2 >= 4) {
            currentString = "Ż"
            count = 8
            waitingForZVariants = false
        }

        if (count >= confirmationThreshold) {
            canCount = false
            lastDisplayed = currentString
            _confirmedString.value = currentString
            scheduleReset()
        }
    }

    private fun scheduleReset() {
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(delayMs)
            canCount = true
            lastDisplayed = null
            reset()
            _confirmedString.value = null
        }
    }

    fun reset() {
        currentString = null
        count = 0
        waitingForZVariants = false
        countForZ1 = 0
        countForZ2 = 0
    }

    fun clear() {
        resetJob?.cancel()
        reset()
        _confirmedString.value = null
        canCount = true
        lastDisplayed = null
    }

    fun cancel() {
        scope.cancel()
    }
}