package com.extremis.browser.models

data class DownloadItem(
    val id: Long,
    val title: String,
    val sourceUrl: String,
    val localUri: String,
    val status: Int,
    val totalBytes: Long,
    val downloadedBytes: Long = 0L,
    val lastModified: Long,
    /**
     * MIME type reported by the source that produced the file.
     *
     * Needed because both DownloadManager and MediaStore hand back `content://` URIs, which carry
     * no file extension. Guessing the type from the URI therefore always failed and opened the
     * file as the wildcard type, which no video player accepts.
     */
    val mimeType: String = ""
)
