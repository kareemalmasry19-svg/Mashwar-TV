package com.mashwar.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * "Is this stream alive?" — careful version. A channel is only called DEAD when the answer is
 * definite (gone / no such server / an HTML page instead of video). Refusals, limits and slow
 * servers are UNSURE and are *not* hidden: on Xtream they usually mean the account is busy on
 * another screen, not that the channel is down.
 */
object ChannelScanner {
    enum class Result { ALIVE, DEAD, UNSURE }

    private val http by lazy {
        sharedHttp.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    suspend fun probe(c: Channel): Result {
        val first = once(c)
        if (first != Result.DEAD) return first
        delay(1_500) // one more try before calling it dead
        return once(c)
    }

    /** Pure classification of an HTTP answer (unit-tested). */
    fun classify(code: Int, contentType: String?, head: String, hls: Boolean): Result = when {
        code in 200..299 -> {
            val t = head.trimStart()
            when {
                t.startsWith("<!doctype", true) || t.startsWith("<html", true) -> Result.DEAD // a web page, not a stream
                hls -> if ("#EXTM3U" in head) Result.ALIVE else if (t.isEmpty()) Result.UNSURE else Result.DEAD
                head.isNotEmpty() -> Result.ALIVE
                else -> Result.UNSURE
            }
        }
        code == 404 || code == 410 -> Result.DEAD
        code == 401 || code == 403 || code == 407 || code == 429 || code == 451 || code == 509 || code >= 500 -> Result.UNSURE
        code in 400..499 -> Result.DEAD
        else -> Result.UNSURE
    }

    private suspend fun once(c: Channel): Result = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(c.url)
                .header("User-Agent", c.userAgent ?: DEFAULT_UA)
                .apply { c.referrer?.let { header("Referer", it) } }
                .build()
            http.newCall(req).execute().use { r ->
                val type = r.header("Content-Type").orEmpty().lowercase()
                val head = if (r.isSuccessful) {
                    r.body?.source()?.let { src -> runCatching { src.request(512) }; src.buffer.snapshot().utf8().take(512) }.orEmpty()
                } else ""
                val hls = "m3u8" in c.url.lowercase() || "mpegurl" in type
                classify(r.code, type, head, hls)
            }
        } catch (e: UnknownHostException) {
            Result.DEAD
        } catch (e: ConnectException) {
            Result.DEAD
        } catch (e: InterruptedIOException) {
            Result.UNSURE // timeout: slow server, not necessarily down
        } catch (e: SSLException) {
            Result.UNSURE
        } catch (e: Exception) {
            Result.UNSURE
        }
    }
}
