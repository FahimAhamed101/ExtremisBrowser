package com.extremis.browser.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VideoQuality(
    val label: String,
    val url: String,
    val height: String,
    val format: String = "mp4"
) : Parcelable
