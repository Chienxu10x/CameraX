/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.cameraxtest

import android.annotation.SuppressLint
import android.content.*
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.core.impl.ImageAnalysisConfig
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.Navigation
import androidx.window.WindowManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.cameraxtest.databinding.CameraUiContainerBinding
import com.example.cameraxtest.databinding.FragmentCameraBinding
import com.google.android.material.color.utilities.Contrast
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.contracts.contract
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bí danh loại trình trợ giúp được sử dụng để phân tích lệnh gọi lại trường hợp sử dụng */
typealias LumaListener = (luma: Double) -> Unit

/**
 * Đoạn chính cho ứng dụng này. Thực hiện tất cả các hoạt động của camera bao gồm:
 *   * - Kính ngắm
 *   * - Chụp ảnh
 *   * - Phân tích hình ảnh
 * ​
 */
class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null

    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var cameraUiContainerBinding: CameraUiContainerBinding? = null

    private lateinit var broadcastManager: LocalBroadcastManager

    private lateinit var mediaStoreUtils: MediaStoreUtils


    private var displayId: Int = -1
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

//    private abstract cameraInFos : List<CameraInfor>
    private lateinit var windowManager: WindowManager

    private val displayManager by lazy {
        requireContext().getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    /** Chặn các hoạt động của camera được thực hiện bằng cách sử dụng trình thực thi này */
    private lateinit var cameraExecutor: ExecutorService


    /**
     * Chúng tôi cần trình xử lý hiển thị cho các thay đổi hướng không kích hoạt cấu hình
     * thay đổi, ví dụ: nếu chúng tôi chọn ghi đè thay đổi cấu hình trong bảng kê khai hoặc 180 độ
     * thay đổi định hướng.
     */

    var cameraFacing: Int = CameraSelector.LENS_FACING_BACK
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = view?.let { view ->
            if (displayId == this@CameraFragment.displayId) {
                Log.d(TAG, "Rotation changed: ${view.display.rotation}")
                imageCapture?.targetRotation = view.display.rotation
                imageAnalyzer?.targetRotation = view.display.rotation
            }
        } ?: Unit
    }

    override fun onResume() {
        super.onResume()
        // Make sure that all permissions are still present, since the
        // user could have removed them while the app was in paused state.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
//                .navigate(
//                CameraFragmentDirections.actionCameraToPermissions()
//            )
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()

        // Unregister the broadcast receivers and listeners
        displayManager.unregisterDisplayListener(displayListener)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    private fun setGalleryThumbnail(filename: String) {
        // Run the operations in the view's thread
        cameraUiContainerBinding?.photoViewButton?.let { photoViewButton ->
            photoViewButton.post {
                // Remove thumbnail padding
                photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())

                // Load thumbnail into circular button using Glide
                Glide.with(photoViewButton)
                    .load(filename)
                    .apply(RequestOptions.circleCropTransform())
                    .into(photoViewButton)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Khởi tạo trình thực thi nền của chúng tôi
        cameraExecutor = Executors.newSingleThreadExecutor()

        broadcastManager = LocalBroadcastManager.getInstance(view.context)

        // Set up the intent filter that will receive events from our main activity
        val filter = IntentFilter().apply { addAction(KEY_EVENT_ACTION) }

        // Every time the orientation of device changes, update rotation for use cases
        displayManager.registerDisplayListener(displayListener, null)

        // Initialize WindowManager to retrieve display metrics
        windowManager = WindowManager(view.context)

        // Initialize MediaStoreUtils for fetching this app's images
        mediaStoreUtils = MediaStoreUtils(requireContext())

        // Wait for the views to be properly laid out
        fragmentCameraBinding.viewFinder.post {

            // Keep track of the display in which this view is attached
            displayId = fragmentCameraBinding.viewFinder.display.displayId

            // Build UI controls
            updateCameraUi()

            // Set up the camera and its use cases
            lifecycleScope.launch {
                setUpCamera()
            }
        }
    }

    /**
     * Tăng cường điều khiển máy ảnh và cập nhật giao diện người dùng theo cách thủ công khi thay đổi cấu hình để tránh bị xóa
     * và thêm lại công cụ tìm chế độ xem từ hệ thống phân cấp chế độ xem; điều này cung cấp một vòng quay liền mạch
     * chuyển đổi trên các thiết bị hỗ trợ nó.
     *
     * LƯU Ý: Cờ được hỗ trợ bắt đầu từ Android 8 nhưng vẫn có đèn flash nhỏ trên
     * màn hình dành cho các thiết bị chạy Android 9 trở xuống.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // Rebind the camera with the updated display metrics
        bindCameraUseCases()

        // Enable or disable switching between cameras
        updateCameraSwitchButton()
    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private suspend fun setUpCamera() {
        cameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()

        // Select lensFacing depending on the available cameras
        lensFacing = when {
            hasBackCamera() -> CameraSelector.LENS_FACING_BACK
            hasFrontCamera() -> CameraSelector.LENS_FACING_FRONT
            else -> throw IllegalStateException("Back and front camera are unavailable")
        }

        // Enable or disable switching between cameras
        updateCameraSwitchButton()

        // Build and bind the camera use cases
        bindCameraUseCases()
    }


    /** Khai báo và liên kết các trường hợp sử dụng xem trước, nắm bắt và phân tích */
    private fun bindCameraUseCases() {

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = windowManager.getCurrentWindowMetrics().bounds
        Log.d(TAG, "Screen metrics: ${metrics.width()} x ${metrics.height()}")

        val screenAspectRatio = aspectRatio(metrics.width(), metrics.height())
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = fragmentCameraBinding.viewFinder.display.rotation

        // CameraProvider
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        var cameraInfo: List<CameraInfo>
        // CameraSelector
        val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
//        val cameraSelector = CameraSelector.Builder().addCameraFilter(cameraInfo)

        // Preview
        preview = Preview.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            // Set initial target rotation
            .setTargetRotation(rotation)
            .build()

        // ImageCapture
        imageCapture = ImageCapture.Builder()
//            .setFlashMode(ImageCapture.FLASH_MODE_ON)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // Chúng tôi yêu cầu tỷ lệ khung hình nhưng không có độ phân giải để khớp với cấu hình
            // xem trước nhưng để CameraX tối ưu hóa cho bất kỳ độ phân giải cụ thể nào phù hợp nhất
            // với trường hợp sử dụng của chúng tôi
            .setTargetAspectRatio(screenAspectRatio)
            // Đặt xoay vòng mục tiêu ban đầu, chúng tôi sẽ phải gọi lại lần nữa nếu xoay vòng thay
            // đổi trong vòng đời của trường hợp sử dụng này
            .setTargetRotation(rotation)
            .build()



        imageAnalyzer = ImageAnalysis.Builder()
            // Chúng tôi yêu cầu tỷ lệ khung hình nhưng không có độ phân giải
//            .setTargetAspectRatio(screenAspectRatio)

            // Đặt xoay mục tiêu ban đầu, chúng tôi sẽ phải gọi lại nếu xoay thay đổi
            // trong vòng đời của use case này
            .setTargetRotation(rotation)
            .build()
            // Bộ phân tích sau đó có thể được gán cho phiên bản
            .also {
                it.setAnalyzer(cameraExecutor, LuminosityAnalyzer { luma ->
                    // Các giá trị được trả về từ máy phân tích của chúng tôi sẽ được chuyển đến
                    // trình nghe đính kèm. Chúng tôi ghi lại kết quả phân tích hình ảnh tại đây -
                    // thay vào đó bạn nên làm điều gì đó hữu ích!

                    Log.d(TAG, "Average luminosity: $luma")
                })
            }



        // Phải hủy liên kết các trường hợp sử dụng trước khi liên kết lại chúng
        cameraProvider.unbindAll()

        if (camera != null) {
            // Phải loại bỏ người quan sát khỏi phiên bản camera trước đó
            removeCameraStateObservers(camera!!.cameraInfo)
        }

        try {
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture,imageAnalyzer)

            preview?.setSurfaceProvider(fragmentCameraBinding.viewFinder.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }




    private fun removeCameraStateObservers(cameraInfo: CameraInfo) {
        cameraInfo.cameraState.removeObservers(viewLifecycleOwner)
    }

    /**
     *  [androidx.camera.core.ImageAnalysis.Builder] requires enum value of
     *  [androidx.camera.core.AspectRatio]. Currently it has values of 4:3 & 16:9.
     *
     *  Detecting the most suitable ratio for dimensions provided in @params by counting absolute
     *  of preview ratio to one of the provided values.
     *
     *  @param width - preview width
     *  @param height - preview height
     *  @return suitable aspect ratio
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /** Method used to re-draw the camera UI controls, called every time configuration changes. */
    private fun updateCameraUi() {

        // Remove previous UI if any
        cameraUiContainerBinding?.root?.let {
            fragmentCameraBinding.root.removeView(it)
        }

        cameraUiContainerBinding = CameraUiContainerBinding.inflate(
            LayoutInflater.from(requireContext()),
            fragmentCameraBinding.root,
            true
        )



        // In the background, load latest photo taken (if any) for gallery thumbnail
        lifecycleScope.launch {
            val thumbnailUri = mediaStoreUtils.getLatestImageFilename()
            thumbnailUri?.let {
                setGalleryThumbnail(it)
            }
        }

        // Listener for button used to capture photo
        cameraUiContainerBinding?.cameraCaptureButton?.setOnClickListener {

            // Get a stable reference of the modifiable image capture use case
            imageCapture?.let { imageCapture ->

                // Create time stamped name and MediaStore entry.
                val name = SimpleDateFormat(FILENAME, Locale.US)
                    .format(System.currentTimeMillis())
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, PHOTO_TYPE)
                    if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                        val appName = requireContext().resources.getString(R.string.app_name)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/${appName}")
                    }
                }

                // Create output options object which contains file + metadata
                val outputOptions = ImageCapture.OutputFileOptions
                    .Builder(requireContext().contentResolver,
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues)
                    .build()

                // Setup image capture listener which is triggered after photo has been taken
                imageCapture.takePicture(
                    outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                        override fun onError(exc: ImageCaptureException) {
                            Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                        }

                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            val savedUri = output.savedUri
                            Log.d(TAG, "Photo capture succeeded: $savedUri")

                            // We can only change the foreground Drawable using API level 23+ API
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                // Update the gallery thumbnail with latest picture taken
                                setGalleryThumbnail(savedUri.toString())
                            }

                            // Implicit broadcasts will be ignored for devices running API level >= 24
                            // so if you only target API level 24+ you can remove this statement
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                                // Suppress deprecated Camera usage needed for API level 23 and below
                                @Suppress("DEPRECATION")
                                requireActivity().sendBroadcast(
                                    Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                                )
                            }
                        }
                    })

                // We can only change the foreground Drawable using API level 23+ API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    // Display flash animation to indicate that photo was captured
                    fragmentCameraBinding.root.postDelayed({
                        fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                        fragmentCameraBinding.root.postDelayed(
                            { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS)
                    }, ANIMATION_SLOW_MILLIS)
                }
            }
        }
//        Switch to a different camera
        // Setup for button used to switch cameras
        cameraUiContainerBinding?.cameraSwitchButton?.let {

            // Disable the button until the camera is set up
            it.isEnabled = false

            // Listener for button used to switch cameras. Only called if the button is enabled
            it.setOnClickListener {
                lensFacing = if (CameraSelector.LENS_FACING_FRONT == lensFacing) {
                    CameraSelector.LENS_FACING_BACK
                } else {
                    CameraSelector.LENS_FACING_FRONT
                }
                // Re-bind use cases to update selected camera
                bindCameraUseCases()
            }
        }

        // Listener for button used to view the most recent photo
        cameraUiContainerBinding?.photoViewButton?.setOnClickListener {
            // Only navigate when the gallery has photos
            lifecycleScope.launch {
                if (mediaStoreUtils.getImages().isNotEmpty()) {
                    Navigation.findNavController(requireActivity(), R.id.fragment_container)
//                        .navigate(CameraFragmentDirections.actionCameraToGallery(
//                            mediaStoreUtils.mediaStoreCollection.toString()
//                        )
//                        )
                }
            }
        }

        cameraUiContainerBinding?.cameraFlashButton?.setOnClickListener {
            Toast.makeText(requireContext(), "ok", Toast.LENGTH_SHORT).show()

            if (camera!!.cameraInfo.torchState.value == 0) {
                camera!!.cameraControl.enableTorch(true)
            } else {
                camera!!.cameraControl.enableTorch(false)
            }

        }

        cameraUiContainerBinding?.seekbarContrast?.progress = 0
        cameraUiContainerBinding?.seekbarContrast?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val contrastValue = (progress + 10) / 10f
                camera?.cameraControl?.setExposureCompensationIndex(contrastValue.toInt())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }

        })

        cameraUiContainerBinding?.seekbarZoom?.progress = 0
        cameraUiContainerBinding?.seekbarZoom?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val contrastValue = (progress + 10) / 10f
                camera?.cameraControl?.setZoomRatio(contrastValue)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {

            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {

            }

        })

    }

    /** Bật hoặc tắt nút chuyển đổi camera tùy theo camera có sẵn */
    //Switch to a different camera
    private fun updateCameraSwitchButton() {
        try {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = hasBackCamera() && hasFrontCamera()
        } catch (exception: CameraInfoUnavailableException) {
            cameraUiContainerBinding?.cameraSwitchButton?.isEnabled = false
        }
    }

    /** Trả về true nếu thiết bị có sẵn camera sau. Sai nếu không */
    private fun hasBackCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ?: false
    }

    /** Trả về true nếu thiết bị có camera trước. Sai nếu không */
    private fun hasFrontCamera(): Boolean {
        return cameraProvider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ?: false
    }

    /**
     * Lớp phân tích hình ảnh tùy chỉnh của chúng tôi.
     *       *
     *       * <p>Tất cả những gì chúng ta cần làm là ghi đè hàm `phân tích` bằng các thao tác mong muốn. Đây,
     *       * chúng tôi tính toán độ sáng trung bình của hình ảnh bằng cách nhìn vào mặt phẳng Y của khung YUV.
     */
    private class LuminosityAnalyzer(listener: LumaListener? = null) : ImageAnalysis.Analyzer {
        private val frameRateWindow = 8
        private val frameTimestamps = ArrayDeque<Long>(5)
        private val listeners = ArrayList<LumaListener>().apply { listener?.let { add(it) } }
        private var lastAnalyzedTimestamp = 0L
        var framesPerSecond: Double = -1.0
            private set

        /**
        Hàm mở rộng trợ giúp được sử dụng để trích xuất một mảng byte từ bộ đệm mặt phẳng hình ảnh
         */
        private fun ByteBuffer.toByteArray(): ByteArray {
            rewind()    // Rewind the buffer to zero
            val data = ByteArray(remaining())
            get(data)   // Copy the buffer into a byte array
            return data // Return the byte array
        }

        /**
         * Phân tích một hình ảnh để tạo ra một kết quả.
         *           *
         *           * <p>Người gọi có trách nhiệm đảm bảo phương pháp phân tích này có thể được thực hiện nhanh chóng
         *           * đủ để ngăn cản quá trình thu thập hình ảnh. Nếu không, mới có sẵn
         *           * hình ảnh sẽ không được thu thập và phân tích.
         *           *
         *           * <p>Hình ảnh được truyền cho phương thức này sẽ không hợp lệ sau khi phương thức này trả về. Người gọi
         *           * không nên lưu trữ các tham chiếu bên ngoài tới hình ảnh này vì các tham chiếu này sẽ trở thành
         *           * không hợp lệ.
         *           *
         *           * Hình ảnh @param đang được phân tích RẤT QUAN TRỌNG: Phải triển khai phương pháp phân tích
         *           * gọi image.close() trên các hình ảnh nhận được khi sử dụng xong chúng. Nếu không, hình ảnh mới
         *           * có thể không nhận được hoặc máy ảnh có thể ngừng hoạt động, tùy thuộc vào cài đặt áp suất ngược.
         *
         */

        override fun analyze(image: ImageProxy) {
            // Nếu không có người nghe nào được đính kèm, chúng tôi không cần thực hiện phân tích
            if (listeners.isEmpty()) {
                image.close()
                return
            }

            // Theo dõi các khung được phân tích
            val currentTime = System.currentTimeMillis()
            frameTimestamps.push(currentTime)

            // Tính FPS bằng đường trung bình động
            while (frameTimestamps.size >= frameRateWindow) frameTimestamps.removeLast()
            val timestampFirst = frameTimestamps.peekFirst() ?: currentTime
            val timestampLast = frameTimestamps.peekLast() ?: currentTime
            framesPerSecond = 1.0 / ((timestampFirst - timestampLast) /
                    frameTimestamps.size.coerceAtLeast(1).toDouble()) * 1000.0

            // Analysis could take an arbitrarily long amount of time
            // Since we are running in a different thread, it won't stall other use cases

            lastAnalyzedTimestamp = frameTimestamps.first

            // Since format in ImageAnalysis is YUV, image.planes[0] contains the luminance plane
            val buffer = image.planes[0].buffer

            // Extract image data from callback object
            val data = buffer.toByteArray()

            // Convert the data into an array of pixel values ranging 0-255
            val pixels = data.map { it.toInt() and 0xFF }

            // Compute average luminance for the image
            val luma = pixels.average()

            // Call all listeners with new value
            listeners.forEach { it(luma) }

            val adjustedPixels = pixels.map { adjustContrast(it, 100.0)}

            // Compute average luminance for the adjusted image
            val adjustedLuma = adjustedPixels.average()

            listeners.forEach { it(adjustedLuma) }


            image.close()
        }

        private fun adjustContrast(pixelValue: Int, contrast: Double): Int {
            // Adjust contrast by multiplying pixel value by the contrast factor
            val adjustedValue = (pixelValue - 128) * contrast + 128
            // Clamp the value to the valid range [0, 255]
            return adjustedValue.coerceIn(0.0, 255.0).toInt()
        }

    }





    companion object {
        private const val TAG = "CameraXBasic"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val PHOTO_TYPE = "image/jpeg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }
}
