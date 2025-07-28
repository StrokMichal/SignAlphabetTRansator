package com.google.mediapipe.examples.handlandmarker.fragment
import android.os.Handler
import android.os.Looper

class StableStringListener(private val confirmationThreshold: Int = 7, private var lastDisplayed: String?) {
    private var currentString: String? = null
    private var count = 0
    private val handler = Handler(Looper.getMainLooper())
    private var canCount = true
    private var countForZ1 = 0
    private var countForZ2 = 0
    private var waitForZCase: Boolean = false
    val delayMs: Long = 1500

    fun onNewString(newString: String?): String? {
        if (newString == "UNKNOWN" || !canCount) {
            return null
        }
        if (newString == "Z" && !waitForZCase) {
            waitForZCase = true
            currentString = newString
            count = -8
        }
        if (waitForZCase && newString == "Ź") {
            countForZ1++
        } else if (waitForZCase && newString == "Ż") {
            countForZ2++
        }

        if (lastDisplayed == newString){
            handler.postDelayed({
                lastDisplayed = null
                reset()
            }, delayMs)
            return null
        }

        if (newString == currentString) {
            count++
        } else {
            currentString = newString
            count = 1
        }
        if (countForZ1 >= 6){
            currentString = "Ź"
            count == 10
        } else if (countForZ2 >= 6) {
            currentString = "Ż"
            count == 8
        }


        if (count >= confirmationThreshold) {
            canCount = false
            val confirmed = currentString
            handler.postDelayed({
                canCount = true
                reset()
            }, delayMs)
            lastDisplayed = confirmed
            return confirmed
        }
        return null
    }

    fun reset() {
        currentString = null
        count = 0
        waitForZCase = false
        countForZ2 = 0
        countForZ1 = 0
    }
}