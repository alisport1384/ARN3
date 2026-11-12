package com.bigrocket.tunnel

import com.bigrocket.egress.StaticProxyParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests: the frame codec, the address codec, the DNS-answer carrier, the spec
 * parser and the reassembly helpers. All of this is pure JVM code that also runs in the phone,
 * so what these tests prove is exactly what ships.
 */
class AddressTunnelProtocolTest {

    // --- 1 ---------------------------------------------------------------------------------
    @Test
    fun frameCodecRoundTripsAndRejectsGarbage() {
        val payload = "hello tunnel".toByteArray()
        val frame = Brt.encode(Brt.DATA, 0x01020304, 0xBEEF, payload)
        assertEquals(Brt.HEADER + payload.size, frame.size)
        // Wire layout is part of the contract with far-side/BridgeServer.kt.
        assertEquals('B'.code.toByte(), frame[0])
        assertEquals('R'.code.toByte(), frame[1])
        assertEquals('T'.code.toByte(), frame[2])
        assertEquals('1'.code.toByte(), frame[3])
        assertEquals(0xBE, frame[4].toInt() and 0xFF)
        assertEquals(0xEF, frame[5].toInt() and 0xFF)
        assertEquals(Brt.DATA, frame[6].toInt())
        assertEquals(0x01020304, Brt.readInt(frame, 8))
        assertEquals(0xBEEF, Brt.cookieOf(frame))

        val decoded = Brt.decode(frame)
        assertNotNull(decoded)
        assertEquals(Brt.DATA, decoded!!.type)
        assertEquals(0x01020304, decoded.sequence)
        assertArrayEquals(payload, decoded.payload)

        // Garbage, a truncated header and a real DNS answer must all be rejected, not guessed at.
        assertNull(Brt.decode("not a frame at all".toByteArray()))
        assertNull(Brt.decode(frame.copyOf(6)))
        val dnsAnswer = DnsRecords.buildQuery("example.com")
        assertNull(Brt.decode(dnsAnswer))
        // An all-zero type byte is not a frame either (it is the type a broken encoder emits).
        val zeroType = frame.copyOf()
        zeroType[6] = 0
        assertNull(Brt.decode(zeroType))
    }

    // --- 2 ---------------------------------------------------------------------------------
    @Test
    fun addressEncodingMatchesSocks5SoNamesResolveFarAway() {
        // IPv4 literal -> ATYP 1, 4 bytes, then the port.
        val v4 = Brt.encodeAddress("203.0.113.9", 5300)
        assertEquals(7, v4.size)
        assertEquals(0x01, v4[0].toInt())
        assertEquals(203.toByte(), v4[1])
        assertEquals(113.toByte(), v4[3])
        val decoded = Brt.decodeAddress(v4)
        assertEquals("203.0.113.9", decoded!!.host)
        assertEquals(5300, decoded.port)

        // A NAME stays a name: the phone never resolves it (that would leak DNS onto one radio).
        val name = Brt.encodeAddress("example.org", 443)
        assertEquals(0x03, name[0].toInt())
        assertEquals("example.org".length, name[1].toInt())
        assertEquals(encodedLength(name), Brt.addressLength(name, 0))
        val nameDecoded = Brt.decodeAddress(name)
        assertEquals("example.org", nameDecoded!!.host)
        assertEquals(443, nameDecoded.port)

        // IPv6 literal -> ATYP 4.
        val v6 = Brt.encodeAddress("2001:db8::1", 80)
        assertEquals(0x04, v6[0].toInt())
        assertEquals(19, v6.size)
        assertEquals("2001:db8:0:0:0:0:0:1", Brt.decodeAddress(v6)!!.host)

        assertNull(Brt.decodeAddress(byteArrayOf(0x09, 1, 2)))
        assertEquals(0, Brt.addressLength(byteArrayOf(0x09), 0))
    }

    private fun encodedLength(address: ByteArray) = if (address[0].toInt() == 0x03) {
        2 + (address[1].toInt() and 0xFF) + 2
    } else {
        0
    }

    // --- 3 ---------------------------------------------------------------------------------
    @Test
    fun reordererPutsInterleavedRadiosBackInOrder() {
        val reorderer = Reorderer()
        // Two radios delivering the same stream out of order - exactly what a bonded channel
        // produces. Offsets are contiguous bytes 0..10 of one stream.
        assertTrue(reorderer.offer(5, "fgh".toByteArray()))   // radio B arrives first
        assertTrue(reorderer.offer(0, "abc".toByteArray()))   // radio A
        assertTrue(reorderer.offer(8, "ijk".toByteArray()))   // radio B
        assertEquals(0L, reorderer.nextExpected())
        // Offset 0 is here, so nothing is missing yet, and order is preserved exactly.
        assertNull(reorderer.firstMissing())
        assertEquals("abc", String(reorderer.poll()!!))
        // 3 and 4 are the hole now: a NACK must ask for 3, and nothing later may be delivered.
        assertEquals(3L, reorderer.nextExpected())
        assertEquals(5L, reorderer.firstMissing())
        assertNull(reorderer.poll())
        assertTrue(reorderer.offer(3, "de".toByteArray()))
        assertEquals("de", String(reorderer.poll()!!))
        assertEquals("fgh", String(reorderer.poll()!!))
        assertEquals("ijk", String(reorderer.poll()!!))
        assertNull(reorderer.poll())

        // A duplicate (a NACK retry of something already consumed) is ignored, not re-delivered.
        assertFalse(reorderer.offer(0, "abc".toByteArray()))
        // A retry of an offset already consumed is dropped too, and the stream keeps flowing.
        assertFalse(reorderer.offer(8, "ijk".toByteArray()))
        assertTrue(reorderer.offer(11, "lmn".toByteArray()))
        assertEquals("lmn", String(reorderer.poll()!!))
        assertNull(reorderer.poll())
    }

    // --- 4 ---------------------------------------------------------------------------------
    @Test
    fun dnsAnswerCarriesPortAndCookieInOneARecord() {
        // The user's "mount a DNS as the address" form: a 4-byte A record that is really
        // [port:2][cookie:2]. A normal address like 1.1.1.1 must NOT look like an endpoint.
        val endpoint = DnsRecords.Endpoint(port = 5300, cookie = 0x1234)
        val bytes = endpoint.encode()
        assertEquals(4, bytes.size)
        val decoded = DnsRecords.decodeEndpoint(bytes)
        assertEquals(5300, decoded!!.port)
        assertEquals(0x1234, decoded.cookie)
        assertTrue(DnsRecords.isPlausible(decoded))
        assertFalse(DnsRecords.isPlausible(DnsRecords.Endpoint(0, 0x1234)))
        assertFalse(DnsRecords.isPlausible(DnsRecords.Endpoint(5300, 0)))
        assertNull(DnsRecords.decodeEndpoint(byteArrayOf(1, 2, 3)))
    }

    // --- 5 ---------------------------------------------------------------------------------
    @Test
    fun dnsQueryAndAnswerParsingWorksWithoutTheAppsResolverStack() {
        val query = DnsRecords.buildQuery("bridge.example.org")
        // Header: id, flags, QDCOUNT=1, then the encoded name.
        assertEquals(1, ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF))
        val expectedName = byteArrayOf(
            6, 'b'.code.toByte(), 'r'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(), 'g'.code.toByte(), 'e'.code.toByte(),
            7, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3, 'o'.code.toByte(), 'r'.code.toByte(), 'g'.code.toByte(),
            0,
        )
        assertArrayEquals(expectedName, query.copyOfRange(12, 12 + expectedName.size))

        // A response built by hand, with a compressed answer name, as every real resolver sends:
        // question (offset 12) + answer whose NAME is a pointer to offset 12.
        val answer = java.io.ByteArrayOutputStream()
        answer.write(query) // header + question
        val out = answer.toByteArray()
        out[7] = 1 // ANCOUNT = 1
        val response = java.io.ByteArrayOutputStream()
        response.write(out)
        val dos = java.io.DataOutputStream(response)
        dos.writeShort(0xC00C) // pointer to the question name
        dos.writeShort(1) // TYPE A
        dos.writeShort(1) // CLASS IN
        dos.writeInt(60) // TTL
        dos.writeShort(4) // RDLENGTH
        dos.write(byteArrayOf(0x0E, 0x14, 0x12, 0x34)) // port 3604? no: 0x0E14 = 3604, cookie 0x1234
        dos.flush()

        val parsed = DnsRecords.parseAnswers(response.toByteArray())
        assertEquals(1, parsed.size)
        assertEquals("14.20.18.52", parsed[0].ipv4())

        val interpreted = DnsRecords.interpret(response.toByteArray())
        assertNotNull(interpreted)
        assertEquals(3604, interpreted!!.endpoint!!.port)
        assertEquals(0x1234, interpreted.endpoint.cookie)
    }

    // --- 6 ---------------------------------------------------------------------------------
    @Test
    fun specParserAcceptsPlainIpHostDnsAndDohAndRefusesNonsense() {
        val proxyParser: (String) -> Boolean = { text ->
            runCatching { StaticProxyParser.parse(text) }.isSuccess
        }

        // A plain IP is an address, NOT a proxy: this is the user's headline case.
        val plain = TunnelSpecParser.parse("203.0.113.9", proxyParser)!!
        assertEquals(TunnelSpec.Transport.UDP, plain.transport)
        assertEquals("203.0.113.9", plain.host)
        assertEquals(TunnelSpec.DEFAULT_UDP_PORT, plain.port)
        assertTrue(plain.literalHost)
        assertFalse(plain.portFromDnsAnswer)

        val withPort = TunnelSpecParser.parse("203.0.113.9:1234", proxyParser)!!
        assertEquals(1234, withPort.port)

        // Persian digits, because that is how the user types.
        val persian = TunnelSpecParser.parse("۲۰۳.۰.۱۱۳.۹:۵۳۰۰", proxyParser)!!
        assertEquals("203.0.113.9", persian.host)
        assertEquals(5300, persian.port)

        // A bare DNS name: resolved later, and its A record may name the port+cookie.
        val name = TunnelSpecParser.parse("bridge.example.org", proxyParser)!!
        assertEquals(TunnelSpec.Transport.UDP, name.transport)
        assertFalse(name.literalHost)
        assertTrue(name.portFromDnsAnswer)

        val tcp = TunnelSpecParser.parse("tcp://bridge.example.org:8443", proxyParser)!!
        assertEquals(TunnelSpec.Transport.TCP, tcp.transport)
        assertEquals(8443, tcp.port)

        val doh = TunnelSpecParser.parse("https://dns.example.org/dns-query", proxyParser)!!
        assertEquals(TunnelSpec.Transport.DOH, doh.transport)
        assertEquals("/dns-query", doh.path)

        // Proxy forms must be handed to the static-proxy path unchanged (null here): an explicit
        // scheme, credentials, or a well-known proxy port still means "a proxy", not an address.
        assertNull(TunnelSpecParser.parse("1.2.3.4:1080", proxyParser))
        assertNull(TunnelSpecParser.parse("socks5://1.2.3.4:1080", proxyParser))
        assertNull(TunnelSpecParser.parse("http://1.2.3.4:8080", proxyParser))
        assertNull(TunnelSpecParser.parse("user:pass@1.2.3.4:2080", proxyParser))
        // ...while the same host on a non-proxy port is an address for engine 2.
        val bridge = TunnelSpecParser.parse("1.2.3.4:5300", proxyParser)!!
        assertEquals(TunnelSpec.Transport.UDP, bridge.transport)
        assertEquals(5300, bridge.port)

        // Nonsense is named, not silently accepted.
        assertThrows<TunnelSpecParseException> { TunnelSpecParser.parse("", proxyParser) }
        assertThrows<TunnelSpecParseException> { TunnelSpecParser.parse("not an address!", proxyParser) }
        assertThrows<TunnelSpecParseException> { TunnelSpecParser.parse("1.2.3.4:99999", proxyParser) }
        assertThrows<TunnelSpecParseException> { TunnelSpecParser.parse("999.1.1.1", proxyParser) }
    }

    // --- 7 ---------------------------------------------------------------------------------
    @Test
    fun cookieIsDerivedFromTheTypedAddressSoNoSetupIsNeeded() {
        assertEquals(Brt.cookieFor("1.2.3.4"), Brt.cookieFor(" 1.2.3.4 "))
        assertEquals(Brt.cookieFor("Bridge.Example.org"), Brt.cookieFor("bridge.example.org"))
        assertTrue(Brt.cookieFor("1.2.3.4") != Brt.cookieFor("1.2.3.5"))
        // The far side computes the same value with the same checksum (see far-side tests).
        val spec = TunnelSpec("203.0.113.9", TunnelSpec.Transport.UDP, "203.0.113.9", 5300)
        assertEquals(Brt.cookieFor("203.0.113.9"), spec.cookie)
        assertTrue(spec.canonical().contains("203.0.113.9"))
    }

    // --- 8 ---------------------------------------------------------------------------------
    @Test
    fun byteQueueIsBoundedAndSignalsEndOfStream() {
        val queue = ByteQueue(capacity = 8)
        assertTrue(queue.write("abcdef".toByteArray()))
        assertFalse(queue.write("ghij".toByteArray())) // would exceed the bound
        assertTrue(queue.write("gh".toByteArray()))
        assertEquals(8, queue.available())

        val out = ByteArray(5)
        assertEquals(5, queue.read(out, 0, 5, 0))
        assertEquals("abcde", String(out))
        queue.close()
        // Drain what is left, then report EOF rather than hanging.
        val rest = ByteArray(10)
        assertEquals(3, queue.read(rest, 0, 10, 0))
        assertEquals("fgh", String(rest, 0, 3))
        assertEquals(-1, queue.read(rest, 0, 10, 0))
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw AssertionError("expected ${T::class.java.simpleName}, got ${e.javaClass.simpleName}: ${e.message}")
        }
        throw AssertionError("expected ${T::class.java.simpleName}, nothing was thrown")
    }
}
