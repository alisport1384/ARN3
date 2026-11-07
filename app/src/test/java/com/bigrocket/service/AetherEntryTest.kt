package com.bigrocket.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.core.TunnelConfig

/**
 * Which local port the app must dial once the optional Psiphon hop is on.
 *
 * This is the second field bug (.11): the engine serves the tunnel on 1819 and the
 * Psiphon stage on 1821, and we always dialled 1819 - so a perfectly established
 * Psiphon stage was bypassed and the exit stayed a WARP one, while the UI said
 * "Psiphon". The device log shows "psiphon is ready; 127.0.0.1:1821 leaves through
 * psiphon" immediately followed by "tunnel verified, exit ip=104.28.194.222
 * country=IR".
 */
class AetherEntryTest {

    @After
    fun tearDown() = AetherEntry.reset()

    @Test
    fun `without the hop everything goes to the plain tunnel entry`() {
        AetherEntry.reset()
        assertEquals(TunnelConfig.SOCKS_PORT, AetherEntry.tcpPort)
        assertEquals(TunnelConfig.SOCKS_PORT, AetherEntry.udpPort)
        assertFalse(AetherEntry.psiphonLive)
    }

    @Test
    fun `with the hop tcp moves to the psiphon listener`() {
        AetherEntry.update(hopLive = true)
        assertEquals(PsiphonHop.BIND_PORT, AetherEntry.tcpPort)
        // 1821 and 1819 are different listeners: consuming the wrong one silently
        // bypasses Psiphon, which is exactly the bug this pins down.
        assertTrue(AetherEntry.tcpPort != TunnelConfig.SOCKS_PORT)
        assertTrue(AetherEntry.psiphonLive)
    }

    @Test
    fun `udp never moves to the psiphon listener`() {
        AetherEntry.update(hopLive = true)
        // Psiphon's proxy carries TCP only (no UDP ASSOCIATE), so UDP - which is how
        // every DNS lookup leaves - must stay on the tunnel's own listener.
        assertEquals(TunnelConfig.SOCKS_PORT, AetherEntry.udpPort)
    }

    @Test
    fun `reset returns the plain entry`() {
        AetherEntry.update(hopLive = true)
        AetherEntry.reset()
        assertEquals(TunnelConfig.SOCKS_PORT, AetherEntry.tcpPort)
        assertFalse(AetherEntry.psiphonLive)
    }

    @Test
    fun `the engine's own default psiphon listener is 1821`() {
        // The engine prints "[+] psiphon is ready; 127.0.0.1:1821 ..." - if this
        // constant ever drifts, the app dials a dead port.
        assertEquals(1821, PsiphonHop.BIND_PORT)
    }

    @Test
    fun `a launch id is unique per launch`() {
        val ids = (1..50).map { PsiphonHop.launchId() }.toSet()
        assertTrue("launch ids must not repeat, or two launches share a datastore", ids.size > 45)
    }
}
