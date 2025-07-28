package com.google.mediapipe.examples.handlandmarker.fragment


class TextOutputManager {
    private val stringBuilder = StringBuilder()

    fun addLetter(letter: String?): String {
        if (!letter.isNullOrEmpty() && letter != "UNKNOWN") {
            stringBuilder.append(letter.lowercase())
        }
        return stringBuilder.toString()
    }

    fun getText(): String = stringBuilder.toString()

    fun clear() = stringBuilder.clear()

    /**
     * Synchronizuje tekst z zewnętrznym źródłem (np. EditText).
     */
    fun setText(newText: String) {
        stringBuilder.clear()
        stringBuilder.append(newText)
    }
}