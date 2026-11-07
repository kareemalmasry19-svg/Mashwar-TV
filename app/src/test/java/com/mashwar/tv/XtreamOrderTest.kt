package com.mashwar.tv

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class XtreamOrderTest {
    @Test fun keepsPanelCategoryOrderAndItemOrder() {
        // Panel lists categories: Sports(5), News(2), Kids(9)
        val cats = linkedMapOf("5" to "Sports", "2" to "News", "9" to "Kids")
        val items = JSONArray(
            """[
              {"stream_id":"1","name":"News A","category_id":"2"},
              {"stream_id":"2","name":"Sport B","category_id":"5"},
              {"stream_id":"3","name":"Kids C","category_id":"9"},
              {"stream_id":"4","name":"Sport A","category_id":"5"},
              {"stream_id":"5","name":"No category","category_id":"77"},
              {"stream_id":"6","name":"News B","category_id":"2"}
            ]"""
        )
        val out = panelOrder(items, cats).map { it.optString("name") }
        // categories in panel order; inside each one, the panel's own order (not alphabetical); unknown last
        assertEquals(listOf("Sport B", "Sport A", "News A", "News B", "Kids C", "No category"), out)
    }

    @Test fun nothingIsDropped() {
        val items = JSONArray("""[{"stream_id":"1","category_id":"x"},{"stream_id":"2"}]""")
        assertEquals(2, panelOrder(items, emptyMap()).size)
    }
}
