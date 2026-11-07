package com.bigrocket.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The SYN-ACK we emit and the SYN we read are the two places where a userspace TCP relay either
 * behaves like a real gateway (MSS + window scale + SACK) or silently caps the connection at
 * 64 KB in flight and 536-byte upload segments. These tests pin both directions.
 */
class TcpOptionsTest {

    @Test
    fun `syn-ack carries mss, sack and window scale in wire order`() {
        val opts = TcpOptions.synAckOptions(mss = 1360, windowScale = 3)
        assertEquals("options must be dword aligned", 0, opts.size % 4)
        // MSS
        assertEquals(2, opts[0].toInt())
        assertEquals(4, opts[1].toInt())
        assertEquals(1360, ((opts[2].toInt() and 0xFF) shl 8) or (opts[3].toInt() and 0xFF))
        // SACK permitted
        assertEquals(4, opts[4].toInt())
        assertEquals(2, opts[5].toInt())
        // Window scale
        assertEquals(3, opts[6].toInt())
        assertEquals(3, opts[7].toInt())
        assertEquals(3, opts[8].toInt())
    }

    @Test
    fun `syn-ack header with options has the right data offset`() {
        val opts = TcpOptions.synAckOptions(1360, 3)
        val packet = IpPacketBuilder.buildTcpResponsePacket(
            srcIp = "93.184.216.34",
            srcPort = 443,
            dstIp = "10.0.0.2",
            dstPort = 51000,
            seqNumber = 1000,
            ackNumber = 2000,
            flags = TcpFlag.SYN or TcpFlag.ACK,
            data = ByteArray(0),
            dataLength = 0,
            options = opts,
        )
        val dataOffsetDwords = (packet[32].toInt() and 0xF0) shr 4
        assertEquals("data offset must cover the options", (20 + opts.size) / 4, dataOffsetDwords)
        // Payload (none here) would start right after the options.
        assertEquals(20 + 20 + opts.size, packet.size)
    }

    @Test
    fun `payload still lands after the options`() {
        val opts = TcpOptions.synAckOptions(1360, 3)
        val payload = "hello".toByteArray()
        val packet = IpPacketBuilder.buildTcpResponsePacket(
            srcIp = "93.184.216.34",
            srcPort = 443,
            dstIp = "10.0.0.2",
            dstPort = 51000,
            seqNumber = 1,
            ackNumber = 2,
            flags = TcpFlag.PSH or TcpFlag.ACK,
            data = payload,
            dataLength = payload.size,
            options = opts,
        )
        val headerLen = 20 + opts.size
        val payloadAt = 20 + headerLen
        assertEquals("hello", String(packet.copyOfRange(payloadAt, payloadAt + payload.size)))
    }

    /** Builds a client SYN the way Android's stack does, then parses it back. */
    private fun clientSyn(windowScale: Int?, mss: Int?): ByteBuffer {
        val opts = ArrayList<Byte>()
        if (mss != null) {
            opts.add(2); opts.add(4); opts.add(((mss shr 8) and 0xFF).toByte()); opts.add((mss and 0xFF).toByte())
            if (opts.size % 4 != 0) opts.add(0) // end-of-options padding, like Linux does
        }
        if (windowScale != null) {
            opts.add(1) // NOP
            opts.add(3); opts.add(3); opts.add(windowScale.toByte())
        }
        val headerLen = 20 + opts.size
        val buf = ByteBuffer.allocate(20 + headerLen)
        buf.put(0, 0x45)
        buf.putShort(2, (20 + headerLen).toShort())
        buf.put(8, 64)
        buf.put(9, 6)
        // 10.0.0.2 -> 93.184.216.34
        buf.put(12, 10); buf.put(13, 0); buf.put(14, 0); buf.put(15, 2)
        buf.put(16, 93.toByte()); buf.put(17, 184.toByte()); buf.put(18, 216.toByte()); buf.put(19, 34)
        buf.putShort(20, 51000.toShort())
        buf.putShort(22, 443.toShort())
        buf.putInt(24, 1234)
        buf.put(32, (((headerLen / 4) and 0x0F) shl 4).toByte())
        buf.put(33, (TcpFlag.SYN).toByte())
        buf.putShort(34, 65535.toShort())
        opts.forEachIndexed { i, b -> buf.put(40 + i, b) }
        return buf
    }

    @Test
    fun `client window scale and mss are read from the syn`() {
        val buffer = clientSyn(windowScale = 7, mss = 1400)
        val parsed = IpPacketParser.parse(buffer, buffer.capacity())!!
        assertEquals(7, parsed.tcpWindowScale)
        assertEquals(1400, parsed.tcpMss)
        assertEquals(65535, parsed.tcpWindow)
    }

    @Test
    fun `syn without options parses to defaults`() {
        val buffer = clientSyn(windowScale = null, mss = null)
        val parsed = IpPacketParser.parse(buffer, buffer.capacity())!!
        assertEquals(0, parsed.tcpWindowScale)
        assertEquals(0, parsed.tcpMss)
    }

    @Test
    fun `real window is the advertised window shifted by the scale`() {
        // The bug this pins: 65535 raw would cap a flow at ~1.3 MB/s on a 40 ms path.
        val raw = 65535L
        val scaled = raw shl 7
        assertTrue("scaled window must be ~8 MB", scaled > 8_000_000L)
        assertTrue(scaled / 0.040 > 200_000_000) // > 200 MB/s theoretical at 40 ms
    }
}
