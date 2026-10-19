package com.tvm.privratnikx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiTest {
    @Test
    fun validators() {
        assertTrue(Api.isPhoneValid("79001112233"))
        assertFalse(Api.isPhoneValid("89001112233"))
        assertFalse(Api.isPhoneValid("7900111223"))
        assertTrue(Api.isSmsValid("12345"))
        assertFalse(Api.isSmsValid("1234a"))
        assertTrue(Api.isKeyValid("0123456789ABCDEF0123456789abcdef"))
        assertFalse(Api.isKeyValid("0123456789abcdef0123456789abcdeg"))
    }

    @Test
    fun logMasksSecrets() {
        assertEquals("Got key '0123…'", AppLog.mask("Got key '0123456789abcdef0123456789abcdef'"))
        assertEquals(
            """[[{"id":"a_1","token":"***","session":"***","barrier_id":"20253"}]]""",
            AppLog.mask("""[[{"id":"a_1","token":"3.QinW-_x","session":"MpLQ","barrier_id":"20253"}]]""")
        )
        assertEquals("https://h/a/mpegts?token=***", AppLog.mask("https://h/a/mpegts?token=3.Qi_n"))
        assertEquals("Opening barrier 20253 (ул. Ленина, 1)", AppLog.mask("Opening barrier 20253 (ул. Ленина, 1)"))
    }

    @Test
    fun parsing() {
        assertEquals("abc", Api.parseKey("""{"key":" abc\n"}"""))
        assertEquals(
            listOf(Barrier("1", "A"), Barrier("2", "B")),
            Api.parseBarriers("""[{"id":"1","address":"A"},{"id":2,"address":"B"}]""")
        )
        assertTrue(Api.parseOpen("""{"state":1}"""))
        assertTrue(Api.parseOpen("""{"state":"ok"}"""))
        assertFalse(Api.parseOpen("""{"state":0}"""))
        assertFalse(Api.parseOpen("""{"state":"error"}"""))
        assertEquals(
            mapOf("20253" to Camera("1", "https://cam0.example/ab_1/mpegts?token=t1")),
            Api.parseCameras("""[[{"id":"ab_1","camId":"1","domain":"cam0.example","token":"t1","barrier_id":"20253"},""" +
                """{"id":"ab_2","camId":"2","domain":"cam0.example","token":"t2","barrier_id":"20253"}]]""")
        )
    }
}
