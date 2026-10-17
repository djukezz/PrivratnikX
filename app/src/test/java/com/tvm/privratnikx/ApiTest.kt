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
    fun parsing() {
        assertEquals("abc", Api.parseKey("""{"key":" abc\n"}"""))
        assertEquals(
            listOf(Barrier("1", "A"), Barrier("2", "B")),
            Api.parseBarriers("""[{"id":"1","address":"A"},{"id":2,"address":"B"}]""")
        )
        assertTrue(Api.parseOpen("""{"state":1}"""))
        assertFalse(Api.parseOpen("""{"state":0}"""))
        assertEquals(
            mapOf("20253" to "1"),
            Api.parseCameras("""[[{"id":"ab_1","camId":"1","barrier_id":"20253"},""" +
                """{"id":"ab_2","camId":"2","barrier_id":"20253"}]]""")
        )
    }
}
