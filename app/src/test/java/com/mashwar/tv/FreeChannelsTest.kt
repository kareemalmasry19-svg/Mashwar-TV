package com.mashwar.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeChannelsTest {

    private val catalog = """
    {"version":1,"channels":[
      {"id":"MBCMasr.eg","n":"MBC Masr","c":"EG","l":["ara"],"cat":["general"],"logo":"https://x/m.png","q":"1080p","st":"online",
       "s":[{"u":"https://a/mbc.m3u8","q":"1080p","st":"online","sc":90}]},
      {"id":"KTVSport.kw","n":"KTV Sport","c":"KW","l":["ara"],"cat":["sports"],"q":"720p","st":"online",
       "s":[{"u":"https://a/ktv.m3u8","st":"online","ua":"Mozilla/5.0","ref":"https://ref.example/"}]},
      {"id":"Geo.sa","n":"Geo Blocked","c":"SA","l":["ara"],"cat":["movies"],"q":"576p","st":"blocked","s":[{"u":"https://a/geo.m3u8","st":"blocked"}]},
      {"id":"BBCArabic.uk","n":"BBC Arabic","c":"UK","l":["ara","eng"],"cat":["news"],"st":"online","s":[{"u":"https://a/bbc.m3u8","st":"online"}]},
      {"id":"Bad.eg","n":"Bad URL","c":"EG","s":[{"u":"rtmp://nope"}]},
      {"id":"","n":"No id","s":[{"u":"https://a/x"}]},
      {"n":"Missing everything"},
      {"id":"Min.eg","n":"Minimal","s":[{"u":"https://a/min.ts"}]}
    ]}
    """.trimIndent()

    private val channels by lazy { M3uParser.parse(FreeCatalog.toM3u(catalog)) }

    @Test fun parsesCatalogueIntoChannels() {
        assertEquals(listOf("MBC Masr", "KTV Sport", "Geo Blocked", "BBC Arabic", "Minimal"), channels.map { it.name })
        val mbc = channels[0]
        assertEquals("MBCMasr.eg", mbc.tvgId)
        assertEquals("EG", mbc.country)
        assertEquals("general", mbc.categories)
        assertEquals("1080p", mbc.quality)
        assertEquals("online", mbc.status)
        assertEquals("🇪🇬 مصر", mbc.group)
    }

    @Test fun keepsRequiredHeaders() {
        val ktv = channels.first { it.name == "KTV Sport" }
        assertEquals("Mozilla/5.0", ktv.userAgent)
        assertEquals("https://ref.example/", ktv.referrer)
    }

    @Test fun skipsMalformedAndIncompleteEntries() {
        assertFalse(channels.any { it.url.startsWith("rtmp://nope") })
        assertFalse(channels.any { it.name == "No id" || it.name == "Missing everything" })
        val min = channels.first { it.name == "Minimal" }
        assertNull(min.country.takeIf { it?.isNotEmpty() == true })
        assertEquals("unknown", min.status)
        assertEquals("🌍 عربي من الخارج", min.group)
    }

    @Test fun guideLinkInHeader() {
        assertEquals(FreeCatalog.EPG_URL, M3uParser.epgUrlOf(FreeCatalog.toM3u(catalog)))
    }

    @Test fun filtersByCategoryLanguageStatusQualityAndSearch() {
        fun names(f: ChannelFilter) = filterChannels(channels, f, emptySet(), emptySet()).map { it.name }
        assertEquals(listOf("KTV Sport"), names(ChannelFilter(category = "sports")))
        assertEquals(listOf("BBC Arabic"), names(ChannelFilter(language = "eng")))
        assertFalse(names(ChannelFilter(onlyWorking = true)).contains("Geo Blocked"))
        assertTrue(names(ChannelFilter(onlyWorking = false)).contains("Geo Blocked"))
        assertEquals(listOf("MBC Masr"), names(ChannelFilter(minHeight = 1080)))
        assertEquals(listOf("MBC Masr", "KTV Sport"), names(ChannelFilter(minHeight = 720)))
        assertEquals(listOf("BBC Arabic"), names(ChannelFilter(query = "bbc")))
        assertEquals(listOf("MBC Masr"), names(ChannelFilter(group = "🇪🇬 مصر")))
    }

    @Test fun favouritesAndDeadFilters() {
        val favs = setOf("https://a/ktv.m3u8")
        assertEquals(listOf("KTV Sport"), filterChannels(channels, ChannelFilter(favoritesOnly = true), favs, emptySet()).map { it.name })
        assertFalse(filterChannels(channels, ChannelFilter(), emptySet(), setOf("https://a/mbc.m3u8")).any { it.name == "MBC Masr" })
    }

    @Test fun qualityHeights() {
        assertEquals(1080, qualityHeight("1080p"))
        assertEquals(1080, qualityHeight("1080i"))
        assertEquals(2160, qualityHeight("4K"))
        assertEquals(0, qualityHeight(null))
        assertEquals(0, qualityHeight("HD"))
    }

    @Test fun cacheFreshness() {
        val now = 1_000_000_000_000L
        assertTrue(FreeCatalog.isFresh(now - 60_000, now))
        assertFalse(FreeCatalog.isFresh(now - FreeCatalog.MAX_AGE_MS - 1, now))
        assertFalse(FreeCatalog.isFresh(0, now))
        assertFalse(FreeCatalog.isFresh(now + 60_000, now)) // clock skew: treat as stale
    }

    @Test fun emptyOrBrokenCatalogueGivesNoChannels() {
        assertTrue(M3uParser.parse(FreeCatalog.toM3u("{\"channels\":[]}")).isEmpty())
        assertTrue(M3uParser.parse(FreeCatalog.toM3u("{}")).isEmpty())
    }
}
