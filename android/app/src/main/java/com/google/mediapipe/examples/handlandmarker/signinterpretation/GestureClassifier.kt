package com.google.mediapipe.examples.handlandmarker.signinterpretation

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
data class Quadruple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
class GestureClassifier(context: Context ) {
    enum class ClassificationType {
        STATIC, DYNAMIC
    }


    private val interpreterStatic: Interpreter
    private val interpreterDynamic: Interpreter

    private val inputStatic: Array<FloatArray>
    private val outputStatic: Array<FloatArray>

    private val inputDynamic: Array<FloatArray>
    private val outputDynamic: Array<FloatArray>

    val staticLabels: List<String>
    val dynamicLabels: List<String>
    val numClassesStatic: Int
    val numClassesDynamic: Int

    init {
        interpreterStatic = Interpreter(loadModelFile(context, "model_static.tflite"))
        interpreterDynamic = Interpreter(loadModelFile(context, "model_dynamic.tflite"))

        staticLabels = loadLabelsListFromCsv(context, "labels_static.csv")
        dynamicLabels = loadLabelsListFromCsv(context, "labels_dynamic.csv")

        numClassesStatic = staticLabels.size
        numClassesDynamic = dynamicLabels.size

        // Tutaj musisz podać rozmiar wejścia modelu, nie liczby klas
        // Załóżmy, że masz funkcję getInputSize(interpreter) zwracającą rozmiar FloatArray wejścia
        val inputSizeStatic = getInputSize(interpreterStatic)
        val inputSizeDynamic = getInputSize(interpreterDynamic)

        inputStatic = arrayOf(FloatArray(inputSizeStatic))
        outputStatic = Array(1) { FloatArray(numClassesStatic) }

        inputDynamic = arrayOf(FloatArray(inputSizeDynamic))
        outputDynamic = Array(1) { FloatArray(numClassesDynamic) }

        Log.d("GestureClassifier", "------------------------Instance created-------------------------------------------------------")
    }
    private fun getInputSize(interpreter: Interpreter): Int {
        val inputShape = interpreter.getInputTensor(0).shape() // np. [1, 32]
        // Załóżmy, że interesuje nas wymiar 1 (pomijamy batch size)
        return inputShape.drop(1).reduce { acc, i -> acc * i }
    }



    private fun loadModelFile(context: Context, modelFile: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelFile)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun landmarkConverter(landmarkList: List<FloatArray>): FloatArray {
        if (landmarkList.isEmpty()) return FloatArray(0)
        val baseX = landmarkList[0][0]
        val baseY = landmarkList[0][1]
        val converted = FloatArray(landmarkList.size * 2)
        var maxValue = 0f

        // 1. pętla: oblicz przesunięcie i maxValue
        for ((i, point) in landmarkList.withIndex()) {
            val x = point[0] - baseX
            val y = point[1] - baseY
            converted[i * 2] = x
            converted[i * 2 + 1] = y
            maxValue = maxOf(maxValue, kotlin.math.abs(x), kotlin.math.abs(y))
        }

        // 2. pętla: normalizacja
        if (maxValue != 0f) {
            for (i in converted.indices) {
                converted[i] /= maxValue
            }
        }
        return converted
    }

    fun preProcessPointHistory(
        imageSize: Pair<Int, Int>,
        floatArrayList: List<FloatArray>
    ): FloatArray {
        val imageWidth = imageSize.first.toFloat()
        val imageHeight = imageSize.second.toFloat()
        if (floatArrayList.isEmpty() || floatArrayList[0].isEmpty()) {
            return FloatArray(96) { 0f }
        }
        val baseX = floatArrayList[0][0]
        val baseY = floatArrayList[0][1]

        val totalPoints = floatArrayList.sumOf { it.size }
        val flatPoints = FloatArray(totalPoints)

        var index = 0
        for (frame in floatArrayList) {
            for (i in frame.indices step 2) {
                flatPoints[index++] = (frame[i] - baseX) / imageWidth
                flatPoints[index++] = (frame[i + 1] - baseY) / imageHeight
            }
        }
        return flatPoints
    }


    private fun loadLabelsListFromCsv(context: Context, csvFileName: String): List<String> =
        context.assets.open(csvFileName).bufferedReader().readLines()

    fun classify(
        data: FloatArray,
        type: ClassificationType
    ): Pair<Int, String?> {
        val (interpreter, numClasses, labels, input, output) = when (type) {
            ClassificationType.STATIC -> Quadruple(interpreterStatic, numClassesStatic, staticLabels, inputStatic, outputStatic)
            ClassificationType.DYNAMIC -> Quadruple(interpreterDynamic, numClassesDynamic, dynamicLabels, inputDynamic, outputDynamic)
        }

        // Kopiujemy dane do input[0]
        System.arraycopy(data, 0, input[0], 0, data.size)

        interpreter.run(input, output)

        val predictedIdx = output[0].withIndex().maxByOrNull { it.value }?.index ?: -1

        val label = labels.getOrNull(predictedIdx)
        return Pair(predictedIdx, label)
    }
}