package com.extremis.browser.utils

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts-file ad blocker, ported from KoiBrowser's `utils/AdBlock` + `database/AdBlockDb`.
 *
 * KoiBrowser persisted each blocked hit into a Sugar ORM table purely to render a "blocked
 * domains" list. That table is gone; instead we keep a single running counter, which is all the
 * settings screen actually displayed.
 *
 * The hosts list is ~45k domains and takes a moment to parse, so loading happens once on a
 * background thread and [isAd] stays cheap (a HashSet lookup) on the WebView thread.
 */
object AdBlocker {

    private const val TAG = "AdBlocker"
    private const val HOSTS_ASSET = "hosts.txt"
    private const val COMMENT = "#"
    private const val LOCALHOST = "localhost"
    private const val LOCAL_IP_V4 = "127.0.0.1"
    private const val LOCAL_IP_V4_ALT = "0.0.0.0"
    private const val LOCAL_IP_V6 = "::1"
    private const val SPACE = " "
    private const val TAB = "\t"

    private val blockedDomains = HashSet<String>(65_536)
    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)

    @Volatile
    private var enabled = true

    @Volatile
    private var blockedCount: Long = 0L

    /** Kick off loading if it has not happened yet. Safe to call repeatedly. */
    fun ensureLoaded(context: Context) {
        if (loaded.get() || !loading.compareAndSet(false, true)) return
        Thread({
            val appContext = context.applicationContext
            try {
                appContext.assets.open(HOSTS_ASSET).use { stream ->
                    BufferedReader(InputStreamReader(stream)).use { reader ->
                        val buffer = HashSet<String>(65_536)
                        var line = reader.readLine()
                        while (line != null) {
                            parseLine(line, buffer)
                            line = reader.readLine()
                        }
                        synchronized(blockedDomains) {
                            blockedDomains.clear()
                            blockedDomains.addAll(buffer)
                        }
                    }
                }
                loaded.set(true)
                Log.i(TAG, "Loaded ${blockedDomains.size} blocked domains")
            } catch (error: Exception) {
                Log.e(TAG, "Failed reading $HOSTS_ASSET", error)
            } finally {
                loading.set(false)
            }
        }, "adblock-loader").apply { isDaemon = true }.start()
    }

    /**
     * Accepts both plain domain lists (one domain per line, which is what our bundled hosts.txt
     * uses) and real hosts-file syntax (`0.0.0.0 ads.example.com # comment`).
     */
    private fun parseLine(rawLine: String, target: MutableSet<String>) {
        if (rawLine.isEmpty() || rawLine.startsWith(COMMENT)) return

        var line = rawLine
            .replace(LOCAL_IP_V4, "")
            .replace(LOCAL_IP_V4_ALT, "")
            .replace(LOCAL_IP_V6, "")
            .replace(TAB, "")

        val commentIndex = line.indexOf(COMMENT)
        if (commentIndex >= 0) line = line.substring(0, commentIndex)

        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed == LOCALHOST) return

        for (domain in trimmed.split(SPACE)) {
            val candidate = domain.trim()
            if (candidate.isNotEmpty()) target.add(candidate)
        }
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
    }

    fun setBlockedCount(value: Long) {
        blockedCount = value
    }

    val count: Long get() = blockedCount

    /** True when [url] points at a host on the block list. */
    fun isAd(url: String?): Boolean {
        if (!enabled || url.isNullOrEmpty()) return false
        if (!loaded.get()) return false

        val domain = domainOf(url) ?: return false
        val blocked = synchronized(blockedDomains) { blockedDomains.contains(domain) }
        if (blocked) blockedCount += 1
        return blocked
    }

    /**
     * KoiBrowser trimmed everything after the first `/` past the scheme, then took the URI host and
     * dropped a leading `www.`. We keep that behaviour so the same hosts entries still match.
     */
    private fun domainOf(url: String): String? {
        var candidate = url
        val slashIndex = candidate.indexOf('/', 8)
        if (slashIndex != -1) candidate = candidate.substring(0, slashIndex)

        val host = try {
            URI(candidate).host
        } catch (_: Exception) {
            null
        } ?: candidate

        if (host.isEmpty()) return null
        return if (host.startsWith("www.")) host.substring(4) else host
    }

    fun isReady(): Boolean = loaded.get()
}
