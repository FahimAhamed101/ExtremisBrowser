package com.extremis.browser.models

data class BrowserTab(
    val id: Long = System.nanoTime(),
    var title: String = "New tab",
    var url: String = NEW_TAB_URL,
    val incognito: Boolean = false
) {
    companion object {
        const val NEW_TAB_URL = "extremis://newtab"
    }
}
