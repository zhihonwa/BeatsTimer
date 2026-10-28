package com.timekeeper.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import com.timekeeper.app.databinding.ActivityAboutBinding

class AboutActivity : Activity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindCollapsible(
            header = binding.aboutIntroHeader,
            content = binding.aboutIntroContent,
            chevron = binding.aboutIntroChevron,
            expandedInitially = true
        )
        bindCollapsible(
            header = binding.aboutUsageHeader,
            content = binding.aboutUsageContent,
            chevron = binding.aboutUsageChevron,
            expandedInitially = false
        )
        bindCollapsible(
            header = binding.aboutBpmHeader,
            content = binding.aboutBpmContent,
            chevron = binding.aboutBpmChevron,
            expandedInitially = false
        )
        bindCollapsible(
            header = binding.aboutRepoHeader,
            content = binding.aboutRepoContent,
            chevron = binding.aboutRepoChevron,
            expandedInitially = false
        )
    }

    private fun bindCollapsible(
        header: View,
        content: View,
        chevron: View,
        expandedInitially: Boolean
    ) {
        var expanded = expandedInitially
        applyState(content, chevron, expanded, animate = false)
        header.setOnClickListener {
            expanded = !expanded
            applyState(content, chevron, expanded, animate = true)
        }
    }

    private fun applyState(content: View, chevron: View, expanded: Boolean, animate: Boolean) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        val rotation = if (expanded) EXPANDED_ROTATION else COLLAPSED_ROTATION
        if (animate) {
            chevron.animate().rotation(rotation).setDuration(ROTATION_DURATION_MS).start()
        } else {
            chevron.rotation = rotation
        }
    }

    companion object {
        private const val EXPANDED_ROTATION = 90f
        private const val COLLAPSED_ROTATION = 0f
        private const val ROTATION_DURATION_MS = 150L
    }
}