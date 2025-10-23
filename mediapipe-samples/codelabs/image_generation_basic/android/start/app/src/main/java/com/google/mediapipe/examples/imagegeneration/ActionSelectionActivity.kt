package com.google.mediapipe.examples.imagegeneration

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.examples.imagegeneration.databinding.ActivityActionSelectionBinding

class ActionSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActionSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActionSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.squatButton.setOnClickListener {
            startDetectionActivity("squat")
        }

        binding.calfRaiseButton.setOnClickListener {
            startDetectionActivity("calf_raise")
        }
    }

    private fun startDetectionActivity(action: String) {
        val intent = Intent(this, DetectionActivity::class.java)
        intent.putExtra("ACTION_TYPE", action)
        startActivity(intent)
    }
}