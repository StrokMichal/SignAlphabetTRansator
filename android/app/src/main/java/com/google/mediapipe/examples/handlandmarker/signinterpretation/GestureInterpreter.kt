package com.google.mediapipe.examples.handlandmarker.signinterpretation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GestureInterpreter(
    private val gestureClassifier: GestureClassifier,
    private val landmarkHistoryBuffer: LandmarkHistoryBuffer,
    private val letterEmitter: LetterEmitter,
    private val listener: StableStringListener,
) {
    private val dynamicType = GestureClassifier.ClassificationType.DYNAMIC
    private val staticType = GestureClassifier.ClassificationType.STATIC
    private val selectedIndices = listOf(0, 4, 8, 12, 16, 20)

    suspend fun interpret(
        landmarks: List<FloatArray>,
        inputWidth: Int,
        inputHeight: Int
    ) {
        withContext(Dispatchers.Default) {
            // Przygotuj dynamiczne punkty (6 punktów x,y)
            val dynamicLandmarkPoints: List<FloatArray> = selectedIndices.map { idx ->
                floatArrayOf(landmarks[idx][0], landmarks[idx][1])
            }

            // Przygotuj statyczne punkty (21 punktów x,y)
            val allPoints: List<FloatArray> = landmarks.map { floatArrayOf(it[0], it[1]) }

            val staticInput = gestureClassifier.landmarkConverter(allPoints)
            val staticClassification = gestureClassifier.classify(staticInput, staticType)

            val flatDynamicLandmarkPoints = FloatArray(dynamicLandmarkPoints.size * 2)
            var index = 0
            for (point in dynamicLandmarkPoints) {
                flatDynamicLandmarkPoints[index++] = point[0]
                flatDynamicLandmarkPoints[index++] = point[1]
            }

            val denormPoints = landmarkHistoryBuffer.denormalizePoints(flatDynamicLandmarkPoints, inputWidth, inputHeight)
            landmarkHistoryBuffer.addFrame(denormPoints)

            val dynamicClassification = if (landmarkHistoryBuffer.isFull()) {
                val processedHistory = gestureClassifier.preProcessPointHistory(
                    Pair(inputWidth, inputHeight),
                    landmarkHistoryBuffer.toList()
                )
                gestureClassifier.classify(processedHistory, dynamicType)
            } else null

            // Dodaj etykiety do LetterEmitter
            letterEmitter.addStaticLabel(staticClassification.second)
            letterEmitter.addDynamicLabel(dynamicClassification?.second)

            val staticListSnapshot = letterEmitter.getStaticList()
            val dynamicListSnapshot = letterEmitter.getDynamicList()

            val mostCommonStatic = letterEmitter.getMostCommonLetter(staticListSnapshot)
            val mostCommonDynamic = letterEmitter.getMostCommonLetter(dynamicListSnapshot)

            val result: String? = letterEmitter.decideWhichModel(mostCommonStatic, mostCommonDynamic)

            // Przekaż wynik do listenera
            listener.onNewString(result)
        }
    }
}
