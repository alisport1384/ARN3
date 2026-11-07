package com.bigrocket.egress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The parser is the only part of the static-IP feature the user types into directly, so it is
 * the part most likely to break in a way that silently sends traffic out of the phone instead
 * of out of the proxy. Every accepted form is therefore pinned here.
 */
class StaticProxyParserTest {

    @Test
    fun `blank input means the feature is off`() {
        assertNull(StaticProxyParser.parse(null))
        assertNull(StaticProxyParser.parse(""))
        assertNull(StaticProxyParser.parse("   \n\t "))
    }

    @Test
    fun `bare ip with no port defaults to socks5 1080`() {
        val config = StaticProxyParser.parse("1.2.3.4")!!
        assertEquals(StaticProxyKind.SOCKS5, config.kind)
        assertEquals("1.2.3.4", config.host)
        assertEquals(1080, config.port)
        assertFalse(config.hasCredentials)
        assertTrue(config.kind.supportsUdp)
    }

    @Test
    fun `real world bare ip 95_85_252_115 is accepted with auto port`() {
        val config = StaticProxyParser.parse("95.85.252.115")!!
        assertEquals("95.85.252.115", config.host)
        assertTrue("port must be detected, not guessed", config.autoPort)
        assertEquals("پورت خودکار", config.portLabel())
        // It must survive a save/restore cycle as "still portless".
        assertTrue(StaticProxyParser.parse(config.canonical())!!.autoPort)
    }

    @Test
    fun `portless with credentials keeps the auto flag`() {
        val config = StaticProxyParser.parse("user:pass@95.85.252.115")!!
        assertEquals("95.85.252.115", config.host)
        assertEquals("user", config.username)
        assertTrue(config.autoPort)
    }

    @Test
    fun `persian and arabic indic digits are folded to ascii`() {
        assertEquals("95.85.252.115", StaticProxyParser.parse("\u06F9\u06F5.\u06F8\u06F5.\u06F2\u06F5\u06F2.\u06F1\u06F1\u06F5")!!.host)
        assertEquals(1080, StaticProxyParser.parse("\u06F1.\u06F2.\u06F3.\u06F4:\u06F1\u06F0\u06F8\u06F0")!!.port)
        assertEquals("1.2.3.4", StaticProxyParser.parse("\u0661.\u0662.\u0663.\u0664")!!.host)
    }

    @Test
    fun `trailing dot on a dotted quad is dropped`() {
        val config = StaticProxyParser.parse("95.85.252.115.")!!
        assertEquals("95.85.252.115", config.host)
        assertTrue(config.autoPort)
    }

    @Test
    fun `ip and port without scheme`() {
        val config = StaticProxyParser.parse("1.2.3.4:9050")!!
        assertEquals(StaticProxyKind.SOCKS5, config.kind)
        assertEquals("1.2.3.4", config.host)
        assertEquals(9050, config.port)
    }

    @Test
    fun `socks5 url with credentials`() {
        val config = StaticProxyParser.parse("socks5://alice:s3cret@proxy.example.com:1080")!!
        assertEquals(StaticProxyKind.SOCKS5, config.kind)
        assertEquals("proxy.example.com", config.host)
        assertEquals(1080, config.port)
        assertEquals("alice", config.username)
        assertEquals("s3cret", config.password)
    }

    @Test
    fun `socks5h and socks aliases behave like socks5`() {
        listOf("socks5h://1.1.1.1:1080", "socks://1.1.1.1:1080").forEach { raw ->
            val config = StaticProxyParser.parse(raw)!!
            assertEquals(StaticProxyKind.SOCKS5, config.kind)
            assertEquals("1.1.1.1", config.host)
        }
    }

    @Test
    fun `http proxy defaults to 8080 and cannot carry udp`() {
        val config = StaticProxyParser.parse("http://10.0.0.5")!!
        assertEquals(StaticProxyKind.HTTP, config.kind)
        assertEquals(8080, config.port)
        assertFalse(config.kind.supportsUdp)
        assertTrue(config.udpBypassesProxy)
    }

    @Test
    fun `https proxy is http CONNECT over tls`() {
        val config = StaticProxyParser.parse("https://user:pass@1.2.3.4:443")!!
        assertEquals(StaticProxyKind.HTTP, config.kind)
        assertTrue(config.proxyOverTls)
        assertEquals(443, config.port)
    }

    @Test
    fun `credentials without scheme`() {
        val config = StaticProxyParser.parse("user:pass@1.2.3.4:1080")!!
        assertEquals(StaticProxyKind.SOCKS5, config.kind)
        assertEquals("user", config.username)
        assertEquals("pass", config.password)
        assertEquals("1.2.3.4", config.host)
        assertEquals(1080, config.port)
    }

    @Test
    fun `legacy colon form host port user pass`() {
        val config = StaticProxyParser.parse("1.2.3.4:1080:user:password")!!
        assertEquals("1.2.3.4", config.host)
        assertEquals(1080, config.port)
        assertEquals("user", config.username)
        assertEquals("password", config.password)
    }

    @Test
    fun `garbage with several colons is rejected instead of becoming a host`() {
        try {
            StaticProxyParser.parse("1.2.3.4:1080:user:pa:ss")
            fail("expected rejection")
        } catch (_: StaticProxyParseException) {
        }
    }

    @Test
    fun `ipv6 literal with brackets and port`() {
        val config = StaticProxyParser.parse("[2001:db8::1]:1080")!!
        assertEquals("2001:db8::1", config.host)
        assertEquals(1080, config.port)
    }

    @Test
    fun `bare ipv6 literal uses default port`() {
        val config = StaticProxyParser.parse("2001:db8::1")!!
        assertEquals("2001:db8::1", config.host)
        assertEquals(1080, config.port)
    }

    @Test
    fun `whitespace and rtl marks pasted from chat are stripped`() {
        val config = StaticProxyParser.parse("  1.2.3.4:1080\u200f\n")!!
        assertEquals("1.2.3.4", config.host)
        assertEquals(1080, config.port)
    }

    @Test
    fun `trailing path is ignored`() {
        val config = StaticProxyParser.parse("http://1.2.3.4:8080/")!!
        assertEquals("1.2.3.4", config.host)
        assertEquals(8080, config.port)
    }

    @Test
    fun `percent encoded credentials are decoded`() {
        val config = StaticProxyParser.parse("socks5://us%40er:p%40ss%3Aword@1.2.3.4:1080")!!
        assertEquals("us@er", config.username)
        assertEquals("p@ss:word", config.password)
    }

    @Test
    fun `canonical form round trips`() {
        val first = StaticProxyParser.parse("socks5://us%40er:p%40ss@1.2.3.4:1080")!!
        val second = StaticProxyParser.parse(first.canonical())!!
        assertEquals(first.kind, second.kind)
        assertEquals(first.host, second.host)
        assertEquals(first.port, second.port)
        assertEquals(first.username, second.username)
        assertEquals(first.password, second.password)
    }

    @Test
    fun `describe never leaks the password`() {
        val config = StaticProxyParser.parse("socks5://alice:topsecret@1.2.3.4:1080")!!
        assertFalse(config.describe().contains("topsecret"))
        assertTrue(config.describe().contains("1.2.3.4:1080"))
    }

    @Test
    fun `invalid port is rejected`() {
        try {
            StaticProxyParser.parse("1.2.3.4:99999")
            fail("expected rejection")
        } catch (_: StaticProxyParseException) {
        }
        try {
            StaticProxyParser.parse("1.2.3.4:abc")
            fail("expected rejection")
        } catch (_: StaticProxyParseException) {
        }
    }

    @Test
    fun `unknown scheme is rejected`() {
        try {
            StaticProxyParser.parse("vless://1.2.3.4:443")
            fail("expected rejection")
        } catch (_: StaticProxyParseException) {
        }
    }

    @Test
    fun `unclosed ipv6 bracket is rejected`() {
        try {
            StaticProxyParser.parse("[2001:db8::1:1080")
            fail("expected rejection")
        } catch (_: StaticProxyParseException) {
        }
    }
}
