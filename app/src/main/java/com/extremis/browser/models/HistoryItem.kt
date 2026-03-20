package com.extremis.browser.models

data class HistoryItem(
    val id: Long = 0,
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)
