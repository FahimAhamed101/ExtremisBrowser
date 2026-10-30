package com.extremis.browser.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the video that is currently playing, by keeping a copy of the bytes the page feeds to its
 * `MediaSource`.
 *
 * Why this exists: on YouTube the downloadable URLs are `signatureCipher`-protected and the media
 * itself arrives over SABR, a POST protocol a WebView cannot replay. But the player has to hand
 * the decrypted bytes to `SourceBuffer.appendBuffer` before it can show them — so by copying those
 * bytes we get the exact media, with no cipher and no signature involved. It also works for any
 * other site that streams through MediaSource (Instagram, Facebook, most DASH/HLS players).
 *
 * YouTube keeps video and audio in two separate `SourceBuffer`s, so both are captured and joined
 * with [MediaMuxer] into one ordinary MP4.
 */
object MediaCapture {

    private const val TAG = "MediaCapture"

    /** Refuse to keep growing forever if a page streams for hours. */
    private const val MAX_BYTES_PER_TRACK = 512L * 1024 * 1024

    private const val TRACK_VIDEO = "video"
    private const val TRACK_AUDIO = "audio"

    private val lock = Any()

    private var directory: File? = null
    private val files = mutableMapOf<String, File>()
    private val streams = mutableMapOf<String, FileOutputStream>()
    private val sizes = mutableMapOf<String, Long>()

    /**
     * First payload seen for each track — the initialisation segment.
     *
     * Kept across clears because a page only sends it once per stream: after a reset the next
     * append is a mid-stream chunk, and without this the file would start in the middle of the
     * media and nothing could decode it.
     */
    private val inits = mutableMapOf<String, ByteArray>()

    /** Anything smaller than this is a fragment, not a video worth publishing. */
    private const val MIN_USABLE_BYTES = 100_000L

    /** Application context, captured once so the JS bridge can append without being handed one. */
    private var appContext: Context? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            directory = File(context.applicationContext.getExternalFilesDir(null), "capture")
                .apply { if (!exists()) mkdirs() }
        }
    }

    /**
     * Fired when capture starts or is cleared, so the UI can show or hide the download button.
     * Invoked from a WebView background thread.
     */
    @Volatile
    var onCaptureChanged: (() -> Unit)? = null

    /** How much of the clip a "collect the whole video" run has gathered, as a percentage. */
    @Volatile
    var onFullCaptureProgress: ((Int) -> Unit)? = null

    /**
     * Fired once when that run ends, whatever the reason (`complete`, `ended`, `timeout`,
     * `cancelled`, `no-video`). The value is for logging only — the app saves either way.
     */
    @Volatile
    var onFullCaptureReady: ((String) -> Unit)? = null

    /**
     * Append one `appendBuffer` payload.
     *
     * Called from a WebView background thread, so everything is synchronised. Returning false
     * means the track hit the size cap and the caller should stop sending.
     */
    fun append(track: String, data: ByteArray): Boolean {
        if (data.isEmpty()) return true

        var started = false
        var seeded = 0
        val written = synchronized(lock) {
            val current = sizes[track] ?: 0L
            if (current >= MAX_BYTES_PER_TRACK) return false

            val init = if (looksLikeInit(data)) data.copyOf() else null
            if (init != null) inits[track] = init

            val stream = streams.getOrPut(track) {
                val dir = directory ?: return false
                val file = File(dir, "$track.bin")
                files[track] = file
                FileOutputStream(file, false).also { out ->
                    // A reset throws the initialisation segment away and the page never sends it
                    // again, so the remembered one goes back in front of the next chunk.
                    val remembered = inits[track]
                    if (remembered != null && init == null) {
                        runCatching { out.write(remembered) }
                        seeded = remembered.size
                    }
                }
            }
            try {
                stream.write(data)
                stream.flush()
                sizes[track] = current + seeded + data.size
                started = current == 0L
                if (started) {
                    Log.i(TAG, "start $track ${data.size}B seeded=$seeded head=${headHex(data)}")
                }
                true
            } catch (error: Exception) {
                Log.w(TAG, "Could not append to $track", error)
                false
            }
        }

        // Notify outside the lock: the listener hops to the main thread and must not be able to
        // re-enter while the mutex is held.
        if (started) runCatching { onCaptureChanged?.invoke() }
        return written
    }

    /** First bytes of a payload, for telling an init segment from a mid-stream chunk. */
    private fun headHex(data: ByteArray): String =
        data.take(8).joinToString("") { "%02x".format(it) }

    /** EBML magic (WebM) or an `ftyp` box (MP4) marks the start of a stream. */
    private fun looksLikeInit(data: ByteArray): Boolean {
        if (data.size < 8) return false
        if (data[0] == 0x1A.toByte() && data[1] == 0x45.toByte() &&
            data[2] == 0xDF.toByte() && data[3] == 0xA3.toByte()
        ) {
            return true
        }
        return data[4] == 'f'.code.toByte() && data[5] == 't'.code.toByte() &&
            data[6] == 'y'.code.toByte() && data[7] == 'p'.code.toByte()
    }

    fun hasVideo(): Boolean = synchronized(lock) { (sizes[TRACK_VIDEO] ?: 0L) > 0L }

    /** True once enough has been captured to be worth publishing. */
    fun hasUsableVideo(): Boolean =
        synchronized(lock) { (sizes[TRACK_VIDEO] ?: 0L) >= MIN_USABLE_BYTES }

    fun hasAudio(): Boolean = synchronized(lock) { (sizes[TRACK_AUDIO] ?: 0L) > 0L }

    fun videoBytes(): Long = synchronized(lock) { sizes[TRACK_VIDEO] ?: 0L }

    fun clear() {
        synchronized(lock) {
            closeStreamsLocked()
            files.values.forEach { runCatching { it.delete() } }
            files.clear()
            sizes.clear()
            // Snapshots belong to a save that did not finish cleaning up.
            directory?.let { dir ->
                listOf(TRACK_VIDEO, TRACK_AUDIO).forEach { track ->
                    runCatching { File(dir, "$track.snapshot").delete() }
                }
            }
        }
        runCatching { onCaptureChanged?.invoke() }
    }

    /**
     * Join the captured tracks into one MP4 and publish it to Downloads.
     *
     * @return the public file name on success, or null when nothing usable was captured.
     */
    fun saveToDownloads(context: Context): String? {
        val videoFile: File?
        val audioFile: File?
        synchronized(lock) {
            closeStreamsLocked()

            // Hand the captured bytes over by renaming them out of the way. The player keeps
            // streaming while we mux, and a plain append would re-open `video.bin` with truncate
            // and pull the file out from under the muxer — which silently produced a few hundred
            // kilobytes of garbage from a multi-megabyte capture. Renaming leaves the next append
            // to start a fresh file, and the snapshot stays intact.
            videoFile = detachLocked(TRACK_VIDEO)
            audioFile = detachLocked(TRACK_AUDIO)
        }
        if (videoFile == null) return null

        // A drive that stalls straight away leaves only an init segment behind; publishing that
        // would put an unplayable file in Downloads.
        if (videoFile.length() < MIN_USABLE_BYTES) {
            Log.i(TAG, "capture too small to publish (${videoFile.length()}B)")
            runCatching { videoFile.delete() }
            runCatching { audioFile?.delete() }
            return null
        }

        // The container is decided by what was actually captured rather than assumed: YouTube
        // hands a WebView VP9 + Opus, other sites send H.264 + AAC, and a mid-session codec change
        // can leave the two tracks in different families. Try the expected container first, then
        // the other one, before giving up and saving the video track on its own.
        val videoIsWebm = isWebm(videoFile)
        var containerIsWebm = videoIsWebm
        var muxed = false

        val output = File(videoFile.parentFile, "muxed.tmp")
        if (output.exists()) output.delete()

        if (audioFile != null) {
            for (candidate in listOf(videoIsWebm, !videoIsWebm)) {
                if (output.exists()) output.delete()
                muxed = try {
                    mux(videoFile, audioFile, output, candidate)
                } catch (error: Exception) {
                    Log.w(TAG, "Muxing as ${if (candidate) "webm" else "mp4"} failed", error)
                    false
                }
                if (muxed) {
                    containerIsWebm = candidate
                    break
                }
            }
        }

        val extension = if (containerIsWebm) "webm" else "mp4"
        val mimeType = if (containerIsWebm) "video/webm" else "video/mp4"

        // Neutral name on purpose: the page title would make the file obviously a YouTube grab,
        // and titles are full of characters that need sanitising anyway.
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "video_$stamp.$extension"

        // Without a usable mux the video track is still a valid (silent) file in the same
        // container, which is better than refusing the download outright.
        val source = if (muxed) output else videoFile

        return try {
            publish(context, source, fileName, mimeType)
            fileName
        } catch (error: Exception) {
            Log.w(TAG, "Could not publish capture", error)
            null
        } finally {
            runCatching { output.delete() }
            runCatching { videoFile?.delete() }
            runCatching { audioFile?.delete() }
            // Deliberately no clear() here: the player kept streaming while we muxed, and those
            // bytes are the start of the next capture.
        }
    }

    /**
     * Take a track's file out of the capture so it can be muxed without being written to.
     *
     * Caller must hold [lock]. The file is renamed to a `.snapshot` sibling and the bookkeeping for
     * that track is dropped, so the next append opens a brand new `track.bin`.
     */
    private fun detachLocked(track: String): File? {
        val file = files.remove(track) ?: return null
        sizes.remove(track)
        if (file.length() <= 0L) {
            runCatching { file.delete() }
            return null
        }
        val snapshot = File(file.parentFile, "$track.snapshot")
        Log.i(TAG, "detach $track ${file.length()}B")
        runCatching { snapshot.delete() }
        val renamed = runCatching { file.renameTo(snapshot) }.getOrDefault(false)
        return if (renamed) snapshot else file
    }

    /** EBML magic — the first four bytes of every WebM/Matroska file. */
    private fun isWebm(file: File): Boolean {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) < 4) return false
                header[0] == 0x1A.toByte() &&
                    header[1] == 0x45.toByte() &&
                    header[2] == 0xDF.toByte() &&
                    header[3] == 0xA3.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Muxing
    // ------------------------------------------------------------------

    /**
     * Write the video and audio tracks into one container.
     *
     * The muxer format has to match the captured codecs: YouTube hands a WebView VP9 + Opus, which
     * only a WebM muxer can carry, while H.264 + AAC sources need MP4. A mismatch throws, and the
     * caller then falls back to the video track alone.
     */
    private fun mux(video: File, audio: File, output: File, webm: Boolean): Boolean {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(video.absolutePath)
            audioExtractor.setDataSource(audio.absolutePath)

            val format = if (webm) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            muxer = MediaMuxer(output.absolutePath, format)

            val videoTrackIndex = selectTrack(videoExtractor, "video/")
                ?: return false
            val audioTrackIndex = selectTrack(audioExtractor, "audio/")

            val muxVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex))
            val muxAudio = audioTrackIndex?.let {
                muxer.addTrack(audioExtractor.getTrackFormat(it))
            } ?: -1

            muxer.start()

            val buffer = ByteBuffer.allocateDirect(1 shl 20)
            val info = MediaCodec.BufferInfo()

            copyTrack(videoExtractor, muxer, muxVideo, buffer, info)
            if (muxAudio >= 0) copyTrack(audioExtractor, muxer, muxAudio, buffer, info)

            muxer.stop()
            return true
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith(mimePrefix)) {
                extractor.selectTrack(index)
                return index
            }
        }
        return null
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags

            muxer.writeSampleData(trackIndex, buffer, info)
            if (!extractor.advance()) break
        }
    }

    // ------------------------------------------------------------------
    // Publishing
    // ------------------------------------------------------------------

    private fun publish(context: Context, source: File, fileName: String, mimeType: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/ExtremisBrowser"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri: Uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw IllegalStateException("MediaStore refused the download row")

            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val folder = File(downloads, "ExtremisBrowser").apply { mkdirs() }
            source.inputStream().use { input ->
                FileOutputStream(File(folder, fileName)).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun closeStreamsLocked() {
        streams.values.forEach { runCatching { it.flush(); it.close() } }
        streams.clear()
    }
}
