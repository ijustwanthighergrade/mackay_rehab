package app.ui

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService

/**
 * 專責管理 CameraX 初始化、切換與 Analyzer 綁定。
 * 與 UI 分離，方便在 ViewModel 層控制相機。
 */
class CameraController(
    private val context: Context,
    private val previewView: PreviewView,
    private val cameraExecutor: ExecutorService,
    private val onFrameAvailable: (ImageProxy) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    var isFrontCamera = true
        private set

    fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setImageQueueDepth(1)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(rotation)   // ← 加這行，關鍵
                .build().also { ia ->
                    imageAnalysis = ia
                    ia.setAnalyzer(cameraExecutor) { image ->
                        try {
                            onFrameAvailable(image)
                        } catch (e: Exception) {
                            Log.w("CameraController", "Analyzer error: ${e.message}")
                            image.close()
                        }
                    }
                }

            val selector = if (isFrontCamera)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(context as androidx.lifecycle.LifecycleOwner,
                selector, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        isFrontCamera = !isFrontCamera
        unbind()
        bindCamera()
    }

    fun unbind() {
        try {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
    }

    fun shutdown() {
        unbind()
        try {
            cameraExecutor.shutdown()
        } catch (_: Exception) {
        }
    }
}