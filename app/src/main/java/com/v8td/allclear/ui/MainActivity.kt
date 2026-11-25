package com.v8td.allclear.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.v8td.allclear.R
import com.v8td.allclear.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textViewTitle.text = getString(R.string.app_name)
    }
}
