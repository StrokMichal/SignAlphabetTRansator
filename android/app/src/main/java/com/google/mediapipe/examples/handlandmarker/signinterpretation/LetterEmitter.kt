package com.google.mediapipe.examples.handlandmarker.signinterpretation

class LetterEmitter {
    private val staticList = ArrayDeque<String>()
    private val dynamicList = ArrayDeque<String>()

    // separate locks to reduce contention
    private val lockStatic = Any()
    private val lockDynamic = Any()

    private val letterAssurance = 0.70
    private val arrayLength = 8

    // Internal helper is NOT synchronized itself; callers must synchronize appropriately
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
     * Zakładamy, że dostarczony letterDeque jest "snapshotem" — nie musimy synchronizować tu.
     */
    fun getMostCommonLetter(letterDeque: ArrayDeque<String>): Pair<String?, Double> {
        val counts = letterDeque.groupingBy { it }.eachCount()

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
                    mostCommonDynamic.first == "STOP"  -> mostCommonStatic.first

            mostCommonDynamic.second > letterAssurance &&
                    mostCommonDynamic.first != "STOP" -> mostCommonDynamic.first
            else -> null
        }
    }

    // SAFE getters: tworzymy snapshot wewnątrz zsynchronizowanego bloku
    fun getStaticList(): ArrayDeque<String> = synchronized(lockStatic) {
        // snapshot -> ArrayDeque(Collection) w kontekście synchronizacji jest bezpieczne
        ArrayDeque(staticList)
    }

    fun getDynamicList(): ArrayDeque<String> = synchronized(lockDynamic) {
        ArrayDeque(dynamicList)
    }

    // SAFE modifiers: synchronizujemy zapisy
    fun addDynamicLabel(label: String?) = synchronized(lockDynamic) {
        addLabelToBuffer(dynamicList, label)
    }

    fun addStaticLabel(label: String?) = synchronized(lockStatic) {
        addLabelToBuffer(staticList, label)
    }

    fun clearStaticList() = synchronized(lockStatic) {
        staticList.clear()
    }

    fun clearDynamicList() = synchronized(lockDynamic) {
        dynamicList.clear()
    }
}
