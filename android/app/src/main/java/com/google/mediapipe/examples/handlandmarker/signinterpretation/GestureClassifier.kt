package com.google.mediapipe.examples.handlandmarker.signinterpretation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class GestureClassifier(
    context: Context,
    modelStaticAsset: String = "model_static.tflite",
    modelDynamicAsset: String = "model_dynamic.tflite",
    private val tfliteNumThreads: Int = 4
) {
    enum class ClassificationType { STATIC, DYNAMIC }

    private val TAG = "GestureClassifier"

    private val interpreterStatic: Interpreter
    private val interpreterDynamic: Interpreter

    private val mutexStatic = Mutex()
    private val mutexDynamic = Mutex()

    // Buffers (Array<FloatArray>) — element 0 is actual flattened vector
    private var inputStatic: Array<FloatArray>
    private var outputStatic: Array<FloatArray>

    private var inputDynamic: Array<FloatArray>
    private var outputDynamic: Array<FloatArray>

    val staticLabels: List<String>
    val dynamicLabels: List<String>

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(tfliteNumThreads)
            // add delegates here if needed (GPU, NNAPI, etc.) BEFORE creating Interpreter
        }

        interpreterStatic = Interpreter(loadModelFile(context, modelStaticAsset), options)
        interpreterDynamic = Interpreter(loadModelFile(context, modelDynamicAsset), options)

        // Ensure tensors are allocated so shapes are available
        interpreterStatic.allocateTensors()
        interpreterDynamic.allocateTensors()

        staticLabels = loadLabelsListFromCsv(context, "labels_static.csv")
        dynamicLabels = loadLabelsListFromCsv(context, "labels_dynamic.csv")

        // Prepare initial buffers based on interpreter tensor shapes
        val inLenStatic = getFlattenedInputLen(interpreterStatic)
        val inLenDynamic = getFlattenedInputLen(interpreterDynamic)

        val outLenStatic = getFlattenedOutputLen(interpreterStatic)
        val outLenDynamic = getFlattenedOutputLen(interpreterDynamic)

        inputStatic = arrayOf(FloatArray(inLenStatic))
        outputStatic = arrayOf(FloatArray(outLenStatic))

        inputDynamic = arrayOf(FloatArray(inLenDynamic))
        outputDynamic = arrayOf(FloatArray(outLenDynamic))

        Log.d(TAG, "Initialized. static inLen=$inLenStatic outLen=$outLenStatic dynamic inLen=$inLenDynamic outLen=$outLenDynamic")
    }

    private fun loadModelFile(context: Context, modelFile: String): MappedByteBuffer {
        context.assets.openFd(modelFile).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                val channel: FileChannel = fis.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    private fun loadLabelsListFromCsv(context: Context, csvFileName: String): List<String> =
        context.assets.open(csvFileName).bufferedReader().use { it.readLines() }

    private fun getFlattenedInputLen(interpreter: Interpreter): Int {
        val shape = interpreter.getInputTensor(0).shape() // e.g. [1, N] or [1, H, W, C]
        // Compute product of dims excluding batch dimension (index 0)
        return if (shape.size >= 2) shape.drop(1).fold(1) { acc, v -> acc * v } else shape[0]
    }

    private fun getFlattenedOutputLen(interpreter: Interpreter): Int {
        val shape = interpreter.getOutputTensor(0).shape()
        return if (shape.size >= 2) shape.drop(1).fold(1) { acc, v -> acc * v } else shape[0]
    }

    /**
     * Classify flattened vector 'data' for chosen model type.
     * Call from coroutine (Dispatchers.Default / IO).
     */
    suspend fun classify(data: FloatArray, type: ClassificationType): Pair<Int, String?> {
        return when (type) {
            ClassificationType.STATIC -> mutexStatic.withLock {
                runInference(interpreterStatic, data, inputStatic, outputStatic, staticLabels, "STATIC")
            }
            ClassificationType.DYNAMIC -> mutexDynamic.withLock {
                runInference(interpreterDynamic, data, inputDynamic, outputDynamic, dynamicLabels, "DYNAMIC")
            }
        }
    }

    private fun runInference(
        interpreter: Interpreter,
        data: FloatArray,
        inputArray: Array<FloatArray>,
        outputArray: Array<FloatArray>,
        labels: List<String>,
        modelName: String
    ): Pair<Int, String?> {
        try {
            var inputShape = interpreter.getInputTensor(0).shape()
            var expectedLen = if (inputShape.size >= 2) inputShape.drop(1).fold(1) { acc, v -> acc * v } else inputShape[0]

            Log.d(TAG, "[$modelName] inputShape=${inputShape.contentToString()} expectedLen=$expectedLen dataLen=${data.size}")

            // If mismatch and input is simple 2D [1, N], resize and allocate
            if (expectedLen != data.size) {
                if (inputShape.size == 2) {
                    Log.w(TAG, "[$modelName] Input length mismatch. Resizing to [1, ${data.size}]")
                    interpreter.resizeInput(0, intArrayOf(1, data.size))
                    interpreter.allocateTensors()
                    // refresh shapes and expectedLen
                    inputShape = interpreter.getInputTensor(0).shape()
                    expectedLen = if (inputShape.size >= 2) inputShape.drop(1).fold(1) { acc, v -> acc * v } else inputShape[0]
                    // update input buffer element (don't replace outer array ref — modify element)
                    inputArray[0] = FloatArray(expectedLen)
                    // update output buffer if needed
                    val outLen = getFlattenedOutputLen(interpreter)
                    if (outputArray.isEmpty() || outputArray[0].size != outLen) {
                        outputArray[0] = FloatArray(outLen)
                    }
                    Log.d(TAG, "[$modelName] Resized. New inputShape=${inputShape.contentToString()} newOutLen=${outputArray[0].size}")
                } else {
                    val msg = "[$modelName] Input length mismatch but automatic resize not supported for multi-dim input shape=${inputShape.contentToString()}. dataLen=${data.size}"
                    Log.e(TAG, msg)
                    throw IllegalArgumentException(msg)
                }
            }

            // Validate buffer size
            if (data.size > inputArray[0].size) {
                // Defensive: shouldn't happen after resize, but ensure capacity
                inputArray[0] = FloatArray(data.size)
            }

            // Copy data
            System.arraycopy(data, 0, inputArray[0], 0, data.size)

            // Run
            interpreter.run(inputArray, outputArray)

            val scores = outputArray[0]
            val predictedIdx = scores.withIndex().maxByOrNull { it.value }?.index ?: -1
            val label = labels.getOrNull(predictedIdx)

            Log.d(TAG, "[$modelName] Predicted idx=$predictedIdx label=$label score=${if (predictedIdx >= 0) scores[predictedIdx] else "n/a"}")
            return Pair(predictedIdx, label)
        } catch (e: Exception) {
            // diagnostic: shapes
            try {
                val inShapes = (0 until interpreter.inputTensorCount).map { i -> interpreter.getInputTensor(i).shape().contentToString() }
                val outShapes = (0 until interpreter.outputTensorCount).map { i -> interpreter.getOutputTensor(i).shape().contentToString() }
                Log.e(TAG, "[$modelName] Interpreter.run failed: ${e.message}. inShapes=$inShapes outShapes=$outShapes", e)
            } catch (inner: Exception) {
                Log.e(TAG, "[$modelName] Failed and couldn't read tensor shapes: ${inner.message}", inner)
            }
            throw e
        }
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
        //Log.d("GestureClassifier", "Image size: width=$imageWidth, height=$imageHeight")

        if (floatArrayList.isEmpty() || floatArrayList[0].isEmpty()) {
            //Log.w("GestureClassifier", "Input floatArrayList is empty or first element is empty")
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
        //Log.d("GestureClassifier", "Processed history size: ${flatPoints.size}")
        //Log.d("GestureClassifier", "Processed history sample: ${flatPoints.take(10)}")
        return flatPoints
    }

    /**
     * Close interpreters. Call from lifecycle onDestroy / ViewModel.onCleared.
     */
    suspend fun close() {
        // Acquire locks to ensure no inference in progress.
        mutexStatic.withLock {
            mutexDynamic.withLock {
                try { interpreterStatic.close() } catch (_: Exception) {}
                try { interpreterDynamic.close() } catch (_: Exception) {}
                Log.d(TAG, "Interpreters closed")
            }
        }
    }
}