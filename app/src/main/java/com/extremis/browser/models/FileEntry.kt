package com.extremis.browser.models

import java.io.File

/**
 * One row in the file browser. Mirrors KoiBrowser's `models/Directory`, extended to also carry
 * plain files since the same list renders both.
 */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val itemCount: Int = 0
) {
    val file: File get() = File(path)

    companion object {
        fun from(file: File): FileEntry {
            val isDir = file.isDirectory
            return FileEntry(
                name = file.name.ifEmpty { file.absolutePath },
                path = file.absolutePath,
                isDirectory = isDir,
                sizeBytes = if (isDir) 0L else file.length(),
                lastModified = file.lastModified(),
                itemCount = if (isDir) (file.list()?.size ?: 0) else 0
            )
        }
    }
}
