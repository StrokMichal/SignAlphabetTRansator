package com.google.mediapipe.examples.handlandmarker.signinterpretation

class LetterEmitter {
    private val staticList = ArrayDeque<String>()
    private val dynamicList = ArrayDeque<String>()

    private val letterAssurance = 0.70
    private val arrayLength = 8

    private fun addLabelToBuffer(buffer: ArrayDeque<String>, label: String?) {
        if (label != null) {
            buffer.addLast(label)
            if (buffer.size > arrayLength) {
                buffer.removeFirst()
            }
        }
    }

    /**
     * Zwraca literę najczęściej występującą w buforze oraz jej względną częstość.
     * Specjalna obsługa przypadku, gdy w buforze są zarówno "G" jak i "UNKNOWN".
     */
    fun getMostCommonLetter(letterDeque: ArrayDeque<String>): Pair<String?, Double> {
        val counts = letterDeque.groupingBy { it }.eachCount()

        // Specjalna reguła biznesowa - jeśli są oba "G" i "UNKNOWN", zwracamy "G" z wysokim zaufaniem
        if ("G" in counts && "UNKNOWN" in counts) {
            return "G" to 0.9
        }

        val maxEntry = counts.maxByOrNull { it.value }
        return if (maxEntry != null) {
            val freq = maxEntry.value.toDouble() / letterDeque.size
            maxEntry.key to freq
        } else {
            null to 0.0
        }
    }

    /**
     * Decyduje, która litera powinna zostać wyemitowana na podstawie statycznego i dynamicznego modelu.
     */
    fun decideWhichModel(
        mostCommonStatic: Pair<String?, Double>,
        mostCommonDynamic: Pair<String?, Double>
    ): String? {
        return when {
            mostCommonStatic.second > letterAssurance &&
                    mostCommonDynamic.first == "STOP" &&
                    mostCommonStatic.first !in listOf("Z", "D", "F") -> mostCommonStatic.first

            mostCommonDynamic.second > letterAssurance &&
                    mostCommonDynamic.first != "STOP" -> mostCommonDynamic.first

            else -> null
        }
    }

    fun getStaticList(): ArrayDeque<String> = ArrayDeque(staticList)
    fun getDynamicList(): ArrayDeque<String> = ArrayDeque(dynamicList)
    fun addDynamicLabel(label: String?) = addLabelToBuffer(dynamicList, label)
    fun addStaticLabel(label: String?) = addLabelToBuffer(staticList, label)
    fun clearStaticList() = staticList.clear()
    fun clearDynamicList() = dynamicList.clear()

}
