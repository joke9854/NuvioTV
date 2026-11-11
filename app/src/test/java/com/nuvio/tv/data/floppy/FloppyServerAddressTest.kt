package com.nuvio.tv.data.floppy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FloppyServerAddressTest {
    @Test
    fun `https is accepted and normalised to an origin without a trailing slash`() {
        assertEquals("https://floppy.example.com", FloppyServerAddress.parse("https://floppy.example.com/")!!.baseUrl)
        assertEquals("https://floppy.example.com:8443", FloppyServerAddress.parse(" https://Floppy.Example.com:8443 ")!!.baseUrl)
        assertEquals("https://example.com/tools/floppy", FloppyServerAddress.parse("https://example.com/tools/floppy/")!!.baseUrl)
        assertEquals("https://example.com", FloppyServerAddress.parse("https://example.com:443")!!.origin)
    }

    @Test
    fun `a bare host defaults to https and a bare local host to http`() {
        assertEquals("https://floppy.example.com", FloppyServerAddress.parse("floppy.example.com")!!.baseUrl)
        assertEquals("http://192.168.1.5:8000", FloppyServerAddress.parse("192.168.1.5:8000")!!.baseUrl)
    }

    @Test
    fun `http is accepted for loopback private cgnat link local and dot local hosts`() {
        listOf(
            "http://127.0.0.1:8000", "http://localhost:8000", "http://10.1.2.3", "http://172.16.0.9", "http://172.31.255.1",
            "http://192.168.0.20:8000", "http://100.64.0.1", "http://100.127.255.254", "http://169.254.10.10",
            "http://nas.local:8000", "http://[::1]:8000", "http://[fd12:3456::1]", "http://[fe80::1]"
        ).forEach { assertNotNull("$it should be accepted", FloppyServerAddress.parse(it)) }
    }

    @Test
    fun `http is rejected for public hosts and lookalikes`() {
        listOf(
            "http://floppy.example.com", "http://8.8.8.8", "http://172.32.0.1", "http://172.15.0.1", "http://100.128.0.1",
            "http://100.63.0.1", "http://192.169.0.1", "http://fc-example.com", "http://fd.example.com",
            "http://10.example.com", "http://192.168.1.5.evil.com", "http://localhost.evil.com", "http://notlocal",
            "http://example.local.evil.com", "http://[2001:db8::1]", "http://[fec0::1]"
        ).forEach { assertNull("$it should be rejected", FloppyServerAddress.parse(it)) }
    }

    @Test
    fun `userinfo query fragment and other schemes are rejected`() {
        listOf(
            "https://user@floppy.example.com", "https://user:pass@floppy.example.com", "https://floppy.example.com?x=1",
            "https://floppy.example.com/#frag", "ftp://floppy.example.com", "", "   ", "https://", "https://floppy example.com"
        ).forEach { assertNull("$it should be rejected", FloppyServerAddress.parse(it)) }
    }

    @Test
    fun `same origin compares scheme host and port`() {
        val server = FloppyServerAddress.parse("https://floppy.example.com/app")!!
        val http = okhttp3.HttpUrl.Builder()
        assertEquals(true, server.hasSameOrigin(http.scheme("https").host("floppy.example.com").build()))
        assertEquals(false, server.hasSameOrigin(okhttp3.HttpUrl.Builder().scheme("https").host("floppy.example.com").port(8443).build()))
        assertEquals(false, server.hasSameOrigin(okhttp3.HttpUrl.Builder().scheme("https").host("other.example.com").build()))
    }
}
