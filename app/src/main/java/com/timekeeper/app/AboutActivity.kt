package com.timekeeper.app

import android.app.Activity
import android.os.Bundle
import com.timekeeper.app.databinding.ActivityAboutBinding

class AboutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}