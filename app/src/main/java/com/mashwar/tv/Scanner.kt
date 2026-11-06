package com.mashwar.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Quick "is this stream alive?" check: first bytes of the URL, and a real playlist for HLS. */
object ChannelScanner {
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }

    suspend fun isAlive(c: Channel): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(c.url)
                .header("User-Agent", c.userAgent ?: DEFAULT_UA)
                .header("Range", "bytes=0-4095")
                .apply { c.referrer?.let { header("Referer", it) } }
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use false
                val type = r.header("Content-Type").orEmpty().lowercase()
                if (type.startsWith("text/html")) return@use false // error / login page instead of a stream
                val head = r.body?.source()?.let { src -> src.request(512); src.buffer.snapshot().utf8().take(512) }.orEmpty()
                val hls = "m3u8" in c.url.lowercase() || "mpegurl" in type
                if (hls) "#EXTM3U" in head else head.isNotEmpty()
            }
        }.getOrDefault(false)
    }
}
