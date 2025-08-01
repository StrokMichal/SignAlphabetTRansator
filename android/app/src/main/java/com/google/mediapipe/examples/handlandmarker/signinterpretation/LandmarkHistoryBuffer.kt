package com.google.mediapipe.examples.handlandmarker.signinterpretation
class LandmarkHistoryBuffer(val maxFrames: Int = 16, val numPoints: Int = 12) {
    private val buffer = ArrayDeque<FloatArray>()

    fun denormalizePoints(
        normalizedPoints: FloatArray,
        width: Int,
        height: Int
    ): FloatArray {
        require(normalizedPoints.size == numPoints) { "Tablica powinna mieć dokładnie $numPoints elementów (6 punktów x,y)" }
        require(width > 0 && height > 0) { "Szerokość i wysokość muszą być większe od 0" }

        val denormalized = FloatArray(numPoints)
        for (i in 0 until numPoints step 2) {
            denormalized[i] = normalizedPoints[i] * width
            denormalized[i + 1] = normalizedPoints[i + 1] * height
        }
        return denormalized
    }

    fun addFrame(frame: FloatArray) {
        require(frame.size == numPoints) { "Frame musi mieć rozmiar $numPoints" }
        if (buffer.size == maxFrames) buffer.removeFirst()
        buffer.addLast(frame)
    }


    fun isFull(): Boolean = buffer.size == maxFrames
    fun clear() = buffer.clear()

    val size: Int
        get() = buffer.size

    fun toList(): List<FloatArray> = buffer.toList()

    fun debugString(): String = buffer.joinToString(separator = "\n") { frame ->
        frame.joinToString(prefix = "[", postfix = "]", separator = ", ")
    }
}