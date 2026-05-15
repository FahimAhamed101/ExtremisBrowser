package com.extremis.browser.models

data class DownloadItem(
    val id: Long,
    val title: String,
    val sourceUrl: String,
    val localUri: String,
    val status: Int,
    val totalBytes: Long,
    val lastModified: Long
)
