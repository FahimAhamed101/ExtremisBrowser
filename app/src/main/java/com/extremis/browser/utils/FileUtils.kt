package com.extremis.browser.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.extremis.browser.R
import java.io.File
import java.util.Locale

/**
 * Filesystem helpers, ported from KoiBrowser's `utils/FileUtils` + `utils/StorageUtil`.
 *
 * KoiBrowser hard-coded a long list of legacy mount points (`/mnt/sdcard/external_sd` and
 * friends) to find SD cards. `Context.getExternalFilesDirs` gives the same answer on every
 * device from API 19 up, so that list is gone.
 */
object FileUtils {

    /** Human-readable size, matching KoiBrowser's `getFileSize`. */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${value.toInt()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    /**
     * Every storage root we can see, internal first. The label distinguishes the SD card the way
     * KoiBrowser's StorageListActivity did.
     */
    fun storageRoots(context: Context): List<Pair<String, File>> {
        val roots = mutableListOf<Pair<String, File>>()

        Environment.getExternalStorageDirectory()?.let { primary ->
            if (primary.exists()) roots.add(context.getString(R.string.internal_storage) to primary)
        }

        val externalDirs = context.getExternalFilesDirs(null)
        if (externalDirs != null) {
            externalDirs.forEachIndexed { index, dir ->
                if (dir == null) return@forEachIndexed
                // .../Android/data/<pkg>/files → strip back to the volume root.
                val path = dir.absolutePath
                val cut = path.indexOf("/Android/")
                val rootPath = if (cut > 0) path.substring(0, cut) else path
                val root = File(rootPath)
                if (root.exists() && roots.none { it.second.absolutePath == root.absolutePath }) {
                    val label = if (index == 0) {
                        context.getString(R.string.internal_storage)
                    } else {
                        context.getString(R.string.sd_card)
                    }
                    roots.add(label to root)
                }
            }
        }
        return roots
    }

    /** The default download folder, honouring KoiBrowser's `KEY_DOWNLOAD_DIRECTORY`. */
    fun defaultDownloadDirectory(context: Context, preferences: BrowserPreferences): File {
        val configured = preferences.downloadDirectory
        if (configured.isNotBlank()) {
            val file = File(configured)
            if (file.exists() && file.isDirectory) return file
        }
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (public != null && !public.exists()) public.mkdirs()
        return public ?: context.filesDir
    }

    fun mimeTypeOf(file: File): String {
        val extension = file.extension.lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    /** Open a file in whatever external app claims its MIME type. */
    fun openFile(context: Context, file: File) {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeOf(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    fun shareFile(context: Context, file: File) {
        val uri = uriFor(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeOf(file)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name))
    }

    private fun uriFor(context: Context, file: File): Uri {
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            Uri.fromFile(file)
        }
    }

    fun deleteRecursively(file: File): Boolean = runCatching {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }.getOrDefault(false)

    fun isExternalStorageLegacy(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
}
