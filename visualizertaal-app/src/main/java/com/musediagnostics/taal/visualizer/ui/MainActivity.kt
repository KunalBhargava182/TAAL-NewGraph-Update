package com.musediagnostics.taal.visualizer.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.musediagnostics.taal.visualizer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigation is driven entirely by the nav graph declared in activity_main.xml.
    }
}
