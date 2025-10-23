package com.google.mediapipe.examples.imagegeneration

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.examples.imagegeneration.databinding.ActivityDetectionBinding
import com.google.mediapipe.framework.PacketGetter
import com.google.mediapipe.framework.Packet
import com.google.mediapipe.components.FrameProcessor
import com.google.mediapipe.components.ExternalTextureConverter
import com.google.mediapipe.glutil.EglManager
import com.google.mediapipe.solutions.pose.Pose
import com.google.mediapipe.solutions.pose.PoseLandmark
import com.google.mediapipe.solutions.pose.PoseOptions

class DetectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetectionBinding
    private lateinit var pose: Pose
    private lateinit var converter: ExternalTextureConverter
    private lateinit var eglManager: EglManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val actionType = intent.getStringExtra("ACTION_TYPE")
        setupMediapipe(actionType)
    }

    private fun setupMediapipe(actionType: String?) {
        eglManager = EglManager(null)
        converter = ExternalTextureConverter(eglManager.context)
        converter.setFlipY(true)

        pose = Pose(
            this,
            PoseOptions.builder()
                .setStaticImageMode(false)
                .setModelComplexity(1)
                .setSmoothLandmarks(true)
                .setMinDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
        )

        pose.setResultListener { poseResult ->
            val landmarks = poseResult.poseLandmarks
            if (landmarks != null) {
                val angle = calculateAngle(landmarks)
                runOnUiThread {
                    binding.angleText.text = "角度: ${angle}°"
                    // Update count based on actionType and angle
                }
            }
        }

        converter.setConsumer(pose.getVideoSurfaceOutput())
    }

    private fun calculateAngle(landmarks: List<PoseLandmark>): Double {
        // Implement angle calculation logic
        return 0.0
    }

    override fun onResume() {
        super.onResume()
        converter.setSurfaceTextureAndAttachToGLContext(binding.previewDisplayView.surfaceTexture, binding.previewDisplayView.width, binding.previewDisplayView.height)
    }

    override fun onPause() {
        super.onPause()
        converter.close()
    }
}