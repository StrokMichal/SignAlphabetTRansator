package com.google.mediapipe.examples.handlandmarker.fragment

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.*

class TTSManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech = TextToSpeech(context.applicationContext, this)
    private var isReady = false

    override fun onInit(status: Int) {
        isReady = false
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("pl", "PL"))
            isReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!isReady) {
                Toast.makeText(context, "Język polski nie jest obsługiwany w TTS", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, "Inicjalizacja TTS nie powiodła się", Toast.LENGTH_SHORT).show()
        }
    }

    fun speak(text: String) {
        if (isReady && text.isNotBlank()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun stop() {
        if (isReady) {
            tts.stop()
        }
    }

    fun shutdown() {
        tts.shutdown()
    }
}