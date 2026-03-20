package com.extremis.browser.models

data class Bookmark(
    val id: Long = 0,
    val title: String,
    val url: String,
    val dateAdded: Long = System.currentTimeMillis()
)
