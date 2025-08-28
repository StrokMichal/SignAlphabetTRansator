/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.handlandmarker.fragment

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Camera
import androidx.camera.core.AspectRatio
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import com.google.mediapipe.examples.handlandmarker.signinterpretation.GestureClassifier
import com.google.mediapipe.examples.handlandmarker.HandLandmarkerHelper
import com.google.mediapipe.examples.handlandmarker.signinterpretation.LandmarkHistoryBuffer
import com.google.mediapipe.examples.handlandmarker.MainViewModel
import com.google.mediapipe.examples.handlandmarker.R
import com.google.mediapipe.examples.handlandmarker.databinding.FragmentCameraBinding
import com.google.mediapipe.examples.handlandmarker.signinterpretation.GestureInterpreter
import com.google.mediapipe.examples.handlandmarker.signinterpretation.LetterEmitter
import com.google.mediapipe.examples.handlandmarker.signinterpretation.StableStringListener
import com.google.mediapipe.examples.handlandmarker.signinterpretation.TTSManager
import com.google.mediapipe.examples.handlandmarker.signinterpretation.TextOutputManager
import com.google.mediapipe.tasks.vision.core.RunningMode
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class CameraFragment : Fragment(), HandLandmarkerHelper.LandmarkerListener {

    companion object {
        private const val TAG = "Hand Landmarker"
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding
        get() = _fragmentCameraBinding!!

    private lateinit var handLandmarkerHelper: HandLandmarkerHelper

    lateinit var gestureClassifier: GestureClassifier

    private lateinit var speakerButton: ImageButton
    private lateinit var editText: EditText
    private lateinit var gestureInterpreter : GestureInterpreter

    private val playIcon = R.drawable.baseline_play_circle_outline_24
    private val replayIcon = R.drawable.baseline_replay_24

    //todo SHUT THE FUCK UP BUTTON
    private var isInReplayMode = false
    private lateinit var letterEmitter: LetterEmitter
    private lateinit var labelsStatic: List<String>
    private lateinit var labelsDynamic: List<String>

    var listener = StableStringListener(confirmationThreshold = 5, dispatcher = Dispatchers.Main)
    lateinit var textOutputManager: TextOutputManager
    lateinit var landmarkHistoryBuffer: LandmarkHistoryBuffer

    private lateinit var ttsManager: TTSManager
    private val viewModel: MainViewModel by activityViewModels()
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraFacing = CameraSelector.LENS_FACING_FRONT


    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ExecutorService

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(
                requireActivity(), R.id.fragment_container
            ).navigate(R.id.action_camera_to_permissions)
        }

        // Start the HandLandmarkerHelper again when users come back
        // to the foreground.
        backgroundExecutor.execute {
            if (handLandmarkerHelper.isClose()) {
                handLandmarkerHelper.setupHandLandmarker()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (this::handLandmarkerHelper.isInitialized) {
            viewModel.setMaxHands(handLandmarkerHelper.maxNumHands)
            viewModel.setMinHandDetectionConfidence(handLandmarkerHelper.minHandDetectionConfidence)
            viewModel.setMinHandTrackingConfidence(handLandmarkerHelper.minHandTrackingConfidence)
            viewModel.setMinHandPresenceConfidence(handLandmarkerHelper.minHandPresenceConfidence)
            viewModel.setDelegate(handLandmarkerHelper.currentDelegate)

            // Close the HandLandmarkerHelper and release resources
            backgroundExecutor.execute { handLandmarkerHelper.clearHandLandmarker() }
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        ttsManager.stop()
        ttsManager.shutdown()
        // Shut down our background executor
        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(
            Long.MAX_VALUE, TimeUnit.NANOSECONDS
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding =
            FragmentCameraBinding.inflate(inflater, container, false)

        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Inicjalizacja zależności
        textOutputManager = TextOutputManager()
        gestureClassifier = GestureClassifier(requireContext())
        landmarkHistoryBuffer = LandmarkHistoryBuffer(maxFrames = 16, numPoints = 12)
        letterEmitter = LetterEmitter()
        letterEmitter.clearStaticList()
        letterEmitter.clearDynamicList()
        listener = StableStringListener() // upewnij się, że listener jest zainicjalizowany

        gestureInterpreter = GestureInterpreter(
            gestureClassifier,
            landmarkHistoryBuffer,
            letterEmitter,
            listener,
        )

        ttsManager = TTSManager(requireContext())

        editText = view.findViewById(R.id.textEdit)
        speakerButton = view.findViewById(R.id.speakerButton)

        // TextWatcher
        editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                textOutputManager.setText(s.toString())
                if (isInReplayMode) {
                    speakerButton.setImageResource(playIcon)
                    isInReplayMode = false
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        speakerButton.setImageResource(playIcon)
        speakerButton.setOnClickListener {
            val textToSpeak = editText.text.toString()
            ttsManager.speak(textToSpeak)
            if (textToSpeak.isNotBlank()) {
                speakerButton.setImageResource(replayIcon)
                isInReplayMode = true
            }
        }

        backgroundExecutor = Executors.newSingleThreadExecutor()

        // Obserwacja potwierdzonych liter
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                listener.confirmedString.collect { confirmed ->
                    if (confirmed != null) {
                        textOutputManager.addLetter(confirmed)
                        editText.setText(textOutputManager.getText())
                        editText.setSelection(editText.text.length)
                        ttsManager.speak(confirmed)
                        landmarkHistoryBuffer.clear()
                        letterEmitter.clearStaticList()
                        letterEmitter.clearDynamicList()
                        listener.reset()
                    }
                }
            }
        }

        // Ustawienie kamery po ułożeniu widoków
        fragmentCameraBinding.viewFinder.post {
            setUpCamera()
        }

        // Inicjalizacja HandLandmarkerHelper w tle
        backgroundExecutor.execute {
            handLandmarkerHelper = HandLandmarkerHelper(
                context = requireContext(),
                runningMode = RunningMode.LIVE_STREAM,
                minHandDetectionConfidence = viewModel.currentMinHandDetectionConfidence,
                minHandTrackingConfidence = viewModel.currentMinHandTrackingConfidence,
                minHandPresenceConfidence = viewModel.currentMinHandPresenceConfidence,
                maxNumHands = viewModel.currentMaxHands,
                currentDelegate = viewModel.currentDelegate,
                handLandmarkerHelperListener = this
            )
        }

        // Inicjalizacja kontrolek UI
        initBottomSheetControls()
    }

    private fun initBottomSheetControls() {
        // init bottom sheet settings
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            viewModel.currentMaxHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US, "%.2f", viewModel.currentMinHandPresenceConfidence
            )

        // When clicked, lower hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence >= 0.2) {
                handLandmarkerHelper.minHandDetectionConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand detection score threshold floor
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandDetectionConfidence <= 0.8) {
                handLandmarkerHelper.minHandDetectionConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence >= 0.2) {
                handLandmarkerHelper.minHandTrackingConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand tracking score threshold floor
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandTrackingConfidence <= 0.8) {
                handLandmarkerHelper.minHandTrackingConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, lower hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdMinus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence >= 0.2) {
                handLandmarkerHelper.minHandPresenceConfidence -= 0.1f
                updateControlsUi()
            }
        }

        // When clicked, raise hand presence score threshold floor
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdPlus.setOnClickListener {
            if (handLandmarkerHelper.minHandPresenceConfidence <= 0.8) {
                handLandmarkerHelper.minHandPresenceConfidence += 0.1f
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of hands that can be detected at a
        // time
        fragmentCameraBinding.bottomSheetLayout.maxHandsMinus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands > 1) {
                handLandmarkerHelper.maxNumHands--
                updateControlsUi()
            }
        }

        // When clicked, increase the number of hands that can be detected
        // at a time
        fragmentCameraBinding.bottomSheetLayout.maxHandsPlus.setOnClickListener {
            if (handLandmarkerHelper.maxNumHands < 2) {
                handLandmarkerHelper.maxNumHands++
                updateControlsUi()
            }
        }

        // When clicked, change the underlying hardware used for inference.
        // Current options are CPU and GPU
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
            viewModel.currentDelegate, false
        )
        Log.d(TAG, "currentDelegate ${viewModel.currentDelegate}")
        fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long
                ) {
                    try {
                        handLandmarkerHelper.currentDelegate = p2
                        updateControlsUi()
                    } catch (e: UninitializedPropertyAccessException) {
                        Log.e(TAG, "HandLandmarkerHelper has not been initialized yet.")
                    }
                }

                override fun onNothingSelected(p0: AdapterView<*>?) {
                    /* no op */
                }
            }
    }

    // Update the values displayed in the bottom sheet. Reset HandLandmarker
    // helper.
    private fun updateControlsUi() {
        fragmentCameraBinding.bottomSheetLayout.maxHandsValue.text =
            handLandmarkerHelper.maxNumHands.toString()
        fragmentCameraBinding.bottomSheetLayout.detectionThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandDetectionConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.trackingThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandTrackingConfidence
            )
        fragmentCameraBinding.bottomSheetLayout.presenceThresholdValue.text =
            String.format(
                Locale.US,
                "%.2f",
                handLandmarkerHelper.minHandPresenceConfidence
            )

        // Needs to be cleared instead of reinitialized because the GPU
        // delegate needs to be initialized on the thread using it when applicable
        backgroundExecutor.execute {
            handLandmarkerHelper.clearHandLandmarker()
            handLandmarkerHelper.setupHandLandmarker()


        }
        fragmentCameraBinding.overlay.clear()
    }

    // Initialize CameraX, and prepare to bind the camera use cases
    private fun setUpCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(
            {
                // CameraProvider
                cameraProvider = cameraProviderFuture.get()

                // Build and bind the camera use cases
                bindCameraUseCases()
            }, ContextCompat.getMainExecutor(requireContext())
        )
    }

    // Declare and bind preview, capture and analysis use cases
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(cameraFacing).build()

        // Preview. Only using the 4:3 ratio because this is the closest to our models
        preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
            .build()

        // ImageAnalysis. Using RGBA 8888 to match how our models work
        imageAnalyzer =
            ImageAnalysis.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(fragmentCameraBinding.viewFinder.display.rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                // The analyzer can then be assigned to the instance
                .also {
                    it.setAnalyzer(backgroundExecutor) { image ->
                        detectHand(image)
                    }
                }

        // Must unbind the use-cases before rebinding them
        cameraProvider.unbindAll()

        try {
            // A variable number of use-cases can be passed here -
            // camera provides access to CameraControl & CameraInfo
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )

            // Attach the viewfinder's surface provider to preview use case
            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun detectHand(imageProxy: ImageProxy) {
        handLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = cameraFacing == CameraSelector.LENS_FACING_FRONT
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        imageAnalyzer?.targetRotation =
            fragmentCameraBinding.viewFinder.display.rotation
    }

    // Update UI after hand have been detected. Extracts original
    // image height/width to scale and place the landmarks properly through
    // OverlayView
    override fun onResults(resultBundle: HandLandmarkerHelper.ResultBundle) {
        val mResults = resultBundle.results.firstOrNull()
        val inputHeight = resultBundle.inputImageHeight
        val inputWidth = resultBundle.inputImageWidth

        viewLifecycleOwner.lifecycleScope.launch {
            if (mResults != null) {
                val firstHandLandmarks = mResults.landmarks().firstOrNull()
                if (firstHandLandmarks != null) {
                    val landmarksFloatArray = firstHandLandmarks.map { landmark ->
                        floatArrayOf(landmark.x(), landmark.y())
                    }

                    gestureInterpreter.interpret(landmarksFloatArray, inputWidth, inputHeight)
                }

                // Aktualizacja UI na wątku głównym
                withContext(Dispatchers.Main) {
                    if (_fragmentCameraBinding != null) {
                        fragmentCameraBinding.bottomSheetLayout.inferenceTimeVal.text =
                            String.format("%d ms", resultBundle.inferenceTime)

                        val newText = textOutputManager.getText()
                        if (editText.text.toString() != newText) {
                            editText.setText(newText)
                            editText.setSelection(newText.length)
                        }

                        fragmentCameraBinding.overlay.setResults(
                            mResults,
                            inputHeight,
                            inputWidth,
                            RunningMode.LIVE_STREAM
                        )
                        fragmentCameraBinding.overlay.invalidate()
                    }
                }
            } else {
                // Brak dłoni — czyścimy wszystko na UI
                withContext(Dispatchers.Main) {
                    landmarkHistoryBuffer.clear()
                    letterEmitter.clearStaticList()
                    letterEmitter.clearDynamicList()
                    listener.clear()

                    if (_fragmentCameraBinding != null) {
                        fragmentCameraBinding.overlay.clear()
                        fragmentCameraBinding.overlay.invalidate()
                    }
                }
            }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
            if (errorCode == HandLandmarkerHelper.GPU_ERROR) {
                fragmentCameraBinding.bottomSheetLayout.spinnerDelegate.setSelection(
                    HandLandmarkerHelper.DELEGATE_GPU, false
                )
            }
        }
    }


}


