package com.extremis.browser.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Search autocomplete, ported from KoiBrowser's `search/BaseSuggestionsTask` +
 * `search/GoogleSuggestionsTask`.
 *
 * Behaviour preserved from the original:
 *  - the query is percent-encoded as ISO-8859-1 (the endpoint's expected charset),
 *  - responses are gzip-compressed and cached on disk for a day, keyed by the query URL hash,
 *  - a cached result is served when the device is offline,
 *  - at most [MAX_RESULTS] suggestions are returned.
 */
object SuggestionsProvider {

    private const val TAG = "SuggestionsProvider"
    private const val CACHE_FILE_TYPE = ".sugg"
    private const val DEFAULT_LANGUAGE = "en"
    private const val ENCODING = "ISO-8859-1"
    private val CACHE_TTL_MS = TimeUnit.DAYS.toMillis(1)
    private const val MAX_RESULTS = 10

    /**
     * Fetch suggestions for [query]. Blocking — call from a worker thread.
     *
     * @return suggestions in server order; empty when offline with no cache or on any failure.
     */
    fun fetch(context: Context, query: String): List<String> {
        if (query.isBlank()) return emptyList()

        val encoded = try {
            URLEncoder.encode(query, ENCODING)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to encode the query", error)
            query
        }

        val appContext = context.applicationContext
        val cacheFile = downloadSuggestionsForQuery(appContext, encoded, language())
            ?: return emptyList()

        if (!cacheFile.exists()) return emptyList()

        return try {
            FileInputStream(cacheFile).use { parseResults(it) }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to parse results", error)
            emptyList()
        }
    }

    private fun language(): String {
        val tag = Locale.getDefault().language
        return if (tag.isNullOrEmpty()) DEFAULT_LANGUAGE else tag
    }

    private fun queryUrl(encodedQuery: String, language: String): String =
        "https://suggestqueries.google.com/complete/search?output=toolbar" +
            "&hl=" + language + "&q=" + encodedQuery

    private fun downloadSuggestionsForQuery(
        context: Context,
        encodedQuery: String,
        language: String
    ): File? {
        var input: InputStream? = null
        var gzip: GZIPInputStream? = null
        try {
            val url = queryUrl(encodedQuery, language)
            val file = File(context.cacheDir, "${url.hashCode()}$CACHE_FILE_TYPE")

            // Fresh cache wins outright — matches the original's one-day TTL.
            if (System.currentTimeMillis() - CACHE_TTL_MS < file.lastModified()) return file

            // Offline: fall back to whatever stale cache we have.
            if (!isNetworkConnected(context)) return file

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                doInput = true
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("Accept-Charset", ENCODING)
            }
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                input = connection.inputStream
                if (input != null) {
                    gzip = GZIPInputStream(input)
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = gzip.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                    file.setLastModified(System.currentTimeMillis())
                }
            } else {
                Log.e(TAG, "Search API responded with code: $responseCode")
            }
            connection.disconnect()
            return file
        } catch (error: Exception) {
            Log.w(TAG, "Problem getting search suggestions", error)
        } finally {
            try {
                gzip?.close()
            } catch (_: Exception) {
                // ignore
            }
            try {
                input?.close()
            } catch (_: Exception) {
                // ignore
            }
        }
        return null
    }

    /**
     * The endpoint returns `<toplevel><CompleteSuggestion><suggestion data="..."/></...>`.
     */
    private fun parseResults(stream: FileInputStream): List<String> {
        val results = ArrayList<String>(MAX_RESULTS)
        BufferedInputStream(stream).use { buffered ->
            val parser = Xml.newPullParser()
            parser.setInput(buffered, ENCODING)
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "suggestion" == parser.name) {
                    val value = parser.getAttributeValue(null, "data")
                    if (!value.isNullOrEmpty()) {
                        results.add(value)
                        if (results.size >= MAX_RESULTS) break
                    }
                }
                event = parser.next()
            }
        }
        return results
    }

    private fun isNetworkConnected(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
