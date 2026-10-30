package com.extremis.browser.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.extremis.browser.MainActivity
import com.extremis.browser.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Downloads an HLS stream by fetching its segments and joining them into one file.
 *
 * DownloadManager is used for single files, but it cannot join a playlist, so streams whose URL
 * ends in `.m3u8` are handled here. The service runs in the foreground because a long playlist can
 * take minutes and the user will leave the browser while it finishes.
 *
 * Supported: master playlists (the highest bandwidth variant wins), `#EXT-X-BYTERANGE`,
 * `#EXT-X-MAP` init segments, and `AES-128` encrypted segments. Not supported: `SAMPLE-AES`.
 */
class MediaDownloadService : Service() {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "media-download")
    }

    @Volatile
    private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            stopSelf()
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER).orEmpty()
        val requestedName = intent.getStringExtra(EXTRA_FILE_NAME).orEmpty()

        // Must happen within a few seconds of startForegroundService().
        startForegroundCompat(buildNotification(title, 0, true))

        executor.execute {
            var produced: File? = null
            try {
                produced = downloadHls(url, title, requestedName, referer)
                if (produced != null && !cancelled) {
                    publish(produced, title)
                    notifyFinished(title, produced)
                }
            } catch (error: Exception) {
                Log.w(TAG, "HLS download failed for $url", error)
                if (!cancelled) notifyFailed(title)
            } finally {
                if (cancelled) produced?.delete()
                // Remove the progress notification rather than detaching it, otherwise a
                // finished "Downloading video" entry is left behind for the user to swipe away.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // HLS
    // ------------------------------------------------------------------

    private fun downloadHls(
        playlistUrl: String,
        title: String,
        requestedName: String,
        referer: String
    ): File? {
        val resolvedPlaylist = resolveMasterPlaylist(playlistUrl, referer) ?: playlistUrl
        val body = fetchText(resolvedPlaylist, referer) ?: return null

        val segments = parseSegments(body, resolvedPlaylist)
        if (segments.isEmpty()) return null

        val extension = if (body.contains("#EXT-X-MAP")) "mp4" else "ts"
        val outputDir = File(getExternalFilesDir(null), "media_downloads")
        if (!outputDir.exists()) outputDir.mkdirs()
        val output = File(outputDir, sanitizeName(title, requestedName, extension))

        // Fragmented MP4 needs its initialisation segment written exactly once, before any media
        // segment — writing it per segment would corrupt the file.
        val initUri = segments.firstNotNullOfOrNull { it.initUri }

        var key: ByteArray? = null
        var keyUri: String? = null

        FileOutputStream(output).use { sink ->
            if (initUri != null) {
                val initBytes = fetchBytes(initUri, referer, null, null)
                    ?: return null
                sink.write(initBytes)
            }

            segments.forEachIndexed { index, segment ->
                if (cancelled) return null

                if (segment.keyUri != null && segment.keyUri != keyUri) {
                    key = fetchBytes(segment.keyUri, referer, null, null)
                    keyUri = segment.keyUri
                }

                val bytes = fetchBytes(segment.uri, referer, segment.byteRange, segment.byteOffset)
                    ?: return null

                val usableKey = key
                val payload = if (segment.encrypted && usableKey != null && usableKey.size == 16) {
                    decryptAes128(bytes, usableKey, segment.iv, segment.sequenceNumber)
                } else {
                    bytes
                }
                sink.write(payload)
                updateProgress(title, ((index + 1) * 100) / segments.size)
            }
        }
        return output
    }

    /** Follow a master playlist to its highest-bandwidth variant. */
    private fun resolveMasterPlaylist(playlistUrl: String, referer: String): String? {
        val body = fetchText(playlistUrl, referer) ?: return null
        if (!body.contains("#EXT-X-STREAM-INF")) return playlistUrl

        var bestBandwidth = -1L
        var bestUri: String? = null
        val lines = body.lines()
        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
            val bandwidth = BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val uri = lines.getOrNull(index + 1)?.trim().orEmpty()
            if (uri.isNotEmpty() && !uri.startsWith("#") && bandwidth > bestBandwidth) {
                bestBandwidth = bandwidth
                bestUri = uri
            }
        }
        return bestUri?.let { absolutize(it, playlistUrl) }
    }

    private data class Segment(
        val uri: String,
        val sequenceNumber: Int,
        val byteRange: Long? = null,
        val byteOffset: Long? = null,
        val keyUri: String? = null,
        val iv: ByteArray? = null,
        val encrypted: Boolean = false,
        val initUri: String? = null
    )

    private fun parseSegments(body: String, playlistUrl: String): List<Segment> {
        val segments = mutableListOf<Segment>()

        var currentKeyUri: String? = null
        var currentIv: ByteArray? = null
        var currentEncrypted = false
        var currentInitUri: String? = null
        var pendingByteRange: Long? = null
        var pendingByteOffset: Long? = null
        var sequence = 0

        body.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    sequence = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }

                line.startsWith("#EXT-X-MAP:") -> {
                    currentInitUri = attribute(line, "URI")?.let { absolutize(it, playlistUrl) }
                }

                line.startsWith("#EXT-X-KEY:") -> {
                    val method = attribute(line, "METHOD").orEmpty()
                    if (method.equals("NONE", ignoreCase = true)) {
                        currentKeyUri = null
                        currentIv = null
                        currentEncrypted = false
                    } else {
                        currentEncrypted = method.equals("AES-128", ignoreCase = true)
                        currentKeyUri = attribute(line, "URI")?.let { absolutize(it, playlistUrl) }
                        currentIv = attribute(line, "IV")?.let(::parseIv)
                    }
                }

                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    val parts = line.substringAfter(':').trim().split('@')
                    pendingByteRange = parts.getOrNull(0)?.trim()?.toLongOrNull()
                    pendingByteOffset = parts.getOrNull(1)?.trim()?.toLongOrNull()
                }

                line.startsWith("#") -> Unit

                else -> {
                    segments.add(
                        Segment(
                            uri = absolutize(line, playlistUrl),
                            sequenceNumber = sequence,
                            byteRange = pendingByteRange,
                            byteOffset = pendingByteOffset,
                            keyUri = currentKeyUri,
                            iv = currentIv,
                            encrypted = currentEncrypted,
                            initUri = currentInitUri
                        )
                    )
                    sequence += 1
                    pendingByteRange = null
                    pendingByteOffset = null
                }
            }
        }
        return segments
    }

    // ------------------------------------------------------------------
    // Networking
    //
    // HttpURLConnection is not Closeable, so these use try/finally with disconnect() rather than
    // Kotlin's use { }.
    // ------------------------------------------------------------------

    private fun fetchText(url: String, referer: String): String? {
        val connection = open(url, referer, null, null) ?: return null
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            Log.w(TAG, "Playlist fetch failed: $url", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchBytes(
        url: String,
        referer: String,
        byteRange: Long?,
        byteOffset: Long?
    ): ByteArray? {
        val connection = open(url, referer, byteRange, byteOffset) ?: return null
        return try {
            connection.inputStream.use { it.readBytes() }
        } catch (error: Exception) {
            Log.w(TAG, "Segment fetch failed: $url", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(
        url: String,
        referer: String,
        byteRange: Long?,
        byteOffset: Long?
    ): HttpURLConnection? {
        return try {
            val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (referer.isNotBlank()) connection.setRequestProperty("Referer", referer)
            if (byteRange != null) {
                val start = byteOffset ?: 0L
                connection.setRequestProperty("Range", "bytes=$start-${start + byteRange - 1}")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                connection.disconnect()
                null
            } else {
                connection
            }
        } catch (error: Exception) {
            Log.w(TAG, "Request failed: $url", error)
            null
        }
    }

    // ------------------------------------------------------------------
    // Crypto
    // ------------------------------------------------------------------

    private fun decryptAes128(
        data: ByteArray,
        key: ByteArray,
        iv: ByteArray?,
        sequenceNumber: Int
    ): ByteArray {
        val actualIv = iv ?: sequenceNumberToIv(sequenceNumber)
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(actualIv))
            cipher.doFinal(data)
        } catch (error: Exception) {
            Log.w(TAG, "Segment decryption failed, keeping raw bytes", error)
            data
        }
    }

    /** HLS defaults to the media sequence number as a 16-byte big-endian IV. */
    private fun sequenceNumberToIv(sequence: Int): ByteArray {
        val iv = ByteArray(16)
        var value = sequence.toLong()
        for (index in 15 downTo 8) {
            iv[index] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    private fun parseIv(raw: String): ByteArray? {
        val hex = raw.removePrefix("0x").removePrefix("0X")
        if (hex.length < 32) return null
        return try {
            ByteArray(16) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------

    /** Copy the finished file into the public Downloads collection so other apps can see it. */
    private fun publish(file: File, title: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(
                        MediaStore.MediaColumns.MIME_TYPE,
                        if (file.extension.equals("mp4", ignoreCase = true)) {
                            "video/mp4"
                        } else {
                            "video/mp2t"
                        }
                    )
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/ExtremisBrowser"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    values
                ) ?: return

                contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                file.delete()
            } else {
                @Suppress("DEPRECATION")
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val target = File(downloads, file.name)
                file.inputStream().use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                file.delete()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not publish ${file.name}; it stays in app storage", error)
        }
    }

    private fun sanitizeName(title: String, requested: String, extension: String): String {
        val base = requested.ifBlank { title }.ifBlank { "video" }
        val cleaned = base.replace(INVALID_FILE_CHARS, "_").trim().take(80)
        val withoutExtension = cleaned.substringBeforeLast('.', cleaned)
        return "$withoutExtension.$extension"
    }

    private fun absolutize(uri: String, playlistUrl: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        return try {
            URI(playlistUrl).resolve(uri).toString()
        } catch (_: Exception) {
            uri
        }
    }

    private fun attribute(line: String, name: String): String? {
        val match = Regex("""$name=("[^"]*"|[^,]*)""").find(line) ?: return null
        return match.groupValues[1].trim().trim('"').takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(R.string.download_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, percent: Int, ongoing: Boolean): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.downloading_video))
            .setContentText(title.ifBlank { getString(R.string.downloads) })
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, percent <= 0)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.cancel), cancel)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateProgress(title: String, percent: Int) {
        if (cancelled) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, percent, true))
    }

    private fun notifyFinished(title: String, file: File) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.download_complete))
                .setContentText(title.ifBlank { file.name })
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notifyFailed(title: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            NOTIFICATION_ID + 1,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(getString(R.string.download_failed_short))
                .setContentText(title.ifBlank { getString(R.string.downloads) })
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val TAG = "MediaDownload"
        private const val CHANNEL_ID = "extremis_media_downloads"
        private const val NOTIFICATION_ID = 8100
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val ACTION_CANCEL = "com.extremis.browser.action.CANCEL_MEDIA_DOWNLOAD"
        private const val EXTRA_URL = "extra_media_url"
        private const val EXTRA_TITLE = "extra_media_title"
        private const val EXTRA_REFERER = "extra_media_referer"
        private const val EXTRA_FILE_NAME = "extra_media_file_name"

        private val BANDWIDTH_REGEX = Regex("""BANDWIDTH=(\d+)""")
        private val INVALID_FILE_CHARS = Regex("""[\\/:*?"<>|]""")

        /** Kick off a playlist download. Direct files go through DownloadManager instead. */
        fun start(context: Context, item: MediaItem, referer: String) {
            val intent = Intent(context, MediaDownloadService::class.java).apply {
                putExtra(EXTRA_URL, item.url)
                putExtra(EXTRA_TITLE, item.title)
                putExtra(EXTRA_REFERER, referer)
                putExtra(EXTRA_FILE_NAME, item.title)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
