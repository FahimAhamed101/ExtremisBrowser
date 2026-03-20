package com.extremis.browser.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VideoInfo(
    val title: String,
    val url: String,
    val qualities: List<VideoQuality>,
    val thumbnailUrl: String? = null,
    val duration: String? = null
) : Parcelable
