package com.mashwar.tv

import com.mashwar.tv.ChannelScanner.Result.ALIVE
import com.mashwar.tv.ChannelScanner.Result.DEAD
import com.mashwar.tv.ChannelScanner.Result.UNSURE
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerTest {
    private fun c(code: Int, head: String = "", hls: Boolean = false, type: String? = null) =
        ChannelScanner.classify(code, type, head, hls)

    @Test fun workingStreams() {
        assertEquals(ALIVE, c(200, "G@\u0011\u0010", hls = false))          // MPEG-TS bytes
        assertEquals(ALIVE, c(206, "binary"))
        assertEquals(ALIVE, c(200, "#EXTM3U\n#EXT-X-VERSION:3", hls = true))
    }

    @Test fun busyOrRefusedIsNotDead() {
        // Xtream answers like this when the account is in use on another screen, or the server is busy
        for (code in listOf(401, 403, 407, 429, 451, 500, 502, 503, 509)) assertEquals("code $code", UNSURE, c(code))
        assertEquals(UNSURE, c(200, "", hls = false)) // nothing arrived in time
    }

    @Test fun definitelyGone() {
        assertEquals(DEAD, c(404))
        assertEquals(DEAD, c(410))
        assertEquals(DEAD, c(400))
        assertEquals(DEAD, c(200, "<!DOCTYPE html><html>login</html>"))
        assertEquals(DEAD, c(200, "<html>error</html>", hls = true))
        assertEquals(DEAD, c(200, "garbage that is not a playlist", hls = true))
    }
}
