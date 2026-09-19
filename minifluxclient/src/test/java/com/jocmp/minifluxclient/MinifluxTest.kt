package com.jocmp.minifluxclient

import okhttp3.OkHttpClient
import com.squareup.moshi.Moshi
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MinifluxTest {
    private val adapter = Moshi.Builder().build().adapter(Category::class.java)

    @Test
    fun `decode category hide_globally`() {
        val category = adapter.fromJson(
            """{"id":1,"title":"Hidden","user_id":2,"hide_globally":true}"""
        )

        assertTrue(category!!.hide_globally)
    }

    @Test
    fun `decode category without hide_globally`() {
        val category = adapter.fromJson("""{"id":1,"title":"Visible","user_id":2}""")

        assertFalse(category!!.hide_globally)
    }

    @Test
    fun `create Miniflux client`() {
        val client = OkHttpClient()
        val miniflux = Miniflux.create(
            client = client,
            baseURL = "https://miniflux.app/v1/"
        )

        assertNotNull(miniflux)
    }
}
