package com.google.mediapipe.examples.handlandmarker.signinterpretation

import android.util.Log
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
            //Log.d("GestureInterpreter", "dynamicLandmarkPoints size: ${dynamicLandmarkPoints.size}")
            dynamicLandmarkPoints.forEachIndexed { i, point ->
                //Log.d("GestureInterpreter", "dynamicLandmarkPoints[$i]: ${point.joinToString(prefix = "[", postfix = "]")}")
            }

            // Przygotuj statyczne punkty (21 punktów x,y)
            Log.d("GestureInterpreter", "landmarks size: ${landmarks.size}")
            landmarks.forEachIndexed { i, point ->
                //Log.d("GestureInterpreter", "landmarks[$i]: ${point.joinToString(prefix = "[", postfix = "]")}")
            }
            val allPoints: List<FloatArray> = landmarks.map { floatArrayOf(it[0], it[1]) }

            val staticInput = gestureClassifier.landmarkConverter(allPoints)
            //landmarkConverter(allPoints)
            //Log.d("GestureInterpreter", "staticInput size: ${staticInput.size}")
            //Log.d("GestureInterpreter", "staticInput: ${staticInput.joinToString(prefix = "[", postfix = "]")}")

            val staticClassification = gestureClassifier.classify(staticInput, staticType)

            val flatDynamicLandmarkPoints = FloatArray(dynamicLandmarkPoints.size * 2)
            var index = 0
            for (point in dynamicLandmarkPoints) {
                flatDynamicLandmarkPoints[index++] = point[0]
                flatDynamicLandmarkPoints[index++] = point[1]
            }
            //Log.d("GestureInterpreter", "flatDynamicLandmarkPoints size: ${flatDynamicLandmarkPoints.size}")
            //Log.d("GestureInterpreter", "flatDynamicLandmarkPoints: ${flatDynamicLandmarkPoints.joinToString(prefix = "[", postfix = "]")}")

            val denormPoints = landmarkHistoryBuffer.denormalizePoints(flatDynamicLandmarkPoints, inputWidth, inputHeight)
            landmarkHistoryBuffer.addFrame(denormPoints)
            //Log.d("GestureInterpreter", "denormPoints size: ${denormPoints.size}")
            //Log.d("GestureInterpreter", "denormPoints: ${denormPoints.joinToString(prefix = "[", postfix = "]")}")

            val dynamicClassification = if (landmarkHistoryBuffer.isFull()) {
                val processedHistory = gestureClassifier.preProcessPointHistory(
                    Pair(inputWidth, inputHeight),
                    landmarkHistoryBuffer.toList()
                )
                //Log.d("GestureInterpreter", "processedHistory size: ${processedHistory.size}")
                //Log.d("GestureInterpreter", "processedHistory: ${processedHistory.joinToString(prefix = "[", postfix = "]")}")

                gestureClassifier.classify(processedHistory, dynamicType)
            } else null

            // Dodaj etykiety do LetterEmitter
            letterEmitter.addStaticLabel(staticClassification.second)
            letterEmitter.addDynamicLabel(dynamicClassification?.second)

            val staticListSnapshot = letterEmitter.getStaticList()
            val dynamicListSnapshot = letterEmitter.getDynamicList()

            //Log.d("GestureInterpreter", "staticListSnapshot: ${staticListSnapshot.joinToString(prefix = "[", postfix = "]")}")
            //Log.d("GestureInterpreter", "dynamicListSnapshot: ${dynamicListSnapshot.joinToString(prefix = "[", postfix = "]")}")

            val mostCommonStatic = letterEmitter.getMostCommonLetter(staticListSnapshot)
            val mostCommonDynamic = letterEmitter.getMostCommonLetter(dynamicListSnapshot)

            //Log.d("GestureInterpreter", "mostCommonStatic: $mostCommonStatic")
            //Log.d("GestureInterpreter", "mostCommonDynamic: $mostCommonDynamic")

            val result: String? = letterEmitter.decideWhichModel(mostCommonStatic, mostCommonDynamic)

            //Log.d("GestureInterpreter", "decided result: $result")

            // Przekaż wynik do listenera
            listener.onNewString(result)
        }
    }
}
